package nuna.catalog.core.commit;

import nuna.catalog.core.commit.CommitReply.Applied;
import nuna.catalog.core.commit.CommitReply.Failure;
import nuna.catalog.core.commit.CommitReply.How;
import nuna.catalog.core.commit.CommitReply.ServerError;
import nuna.catalog.core.commit.CommitReply.Success;
import nuna.catalog.core.commit.CommitReply.Unavailable;
import nuna.catalog.core.commit.CommitRule.Rejection;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Facts;
import nuna.catalog.iceberg.Facts.TableFacts;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.iceberg.Outcome;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.ObjectCreation;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerHold;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.StoreUnavailableException;
import nuna.catalog.store.WriteResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The commit loop of build-plan 2.2 for one table or view: idempotency (D10), apply the request with
 * iceberg-core's builder (D3), judge the rules (D11), write the metadata file, swap the pointer with
 * its journal, outbox and idempotency records in one transaction (D5), and retry a lost swap within
 * the bounds of P10 (D9). It answers as iceberg-core's CatalogHandlers does: a request that changes
 * nothing writes nothing, a requirement that fails against current metadata is a 409, and a
 * swap lost to a concurrent commit is retried against the new metadata. A multi-table transaction runs
 * the same loop over every table it names and swaps all their pointers in one store transaction (D15).
 *
 * <p>The REST layer resolves the catalog and namespace, authorizes the caller and maps the reply to
 * a response; this class never throws for an outcome it can answer.
 */
public final class CommitEngine {

    /** Waits between attempts; tests replace it with one that advances their clock. */
    @FunctionalInterface
    public interface Sleeper {
        Sleeper THREAD = wait -> {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        void sleep(Duration wait);
    }

    private static final System.Logger LOG = System.getLogger(CommitEngine.class.getName());
    private static final int CLAIM_ROUNDS = 3;
    private static final String COMMIT_FAILED = "CommitFailedException";
    private static final String SERVICE_FAILURE = "ServiceFailureException";
    private static final String STATE_UNKNOWN = "CommitStateUnknownException";

    private final CatalogStore store;
    private final TableFiles files;
    private final MetadataCache cache;
    private final List<CommitRule> rules;
    private final CommitEvents events;
    private final Alerts alerts;
    private final CommitSettings settings;
    private final Clock clock;
    private final Sleeper sleeper;

    public CommitEngine(CatalogStore store, TableFiles files, MetadataCache cache, List<CommitRule> rules,
                        CommitEvents events, Alerts alerts, CommitSettings settings, Clock clock, Sleeper sleeper) {
        this.store = Objects.requireNonNull(store, "store");
        this.files = Objects.requireNonNull(files, "files");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.rules = List.copyOf(rules);
        this.events = Objects.requireNonNull(events, "events");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** {@code POST .../tables} without stage-create. */
    public CommitReply createTable(ObjectName name, String requestJson, Caller caller, Optional<String> idempotencyKey) {
        return run(Operation.CREATE_TABLE, one(name, requestJson), requestJson, caller, idempotencyKey);
    }

    /** {@code POST .../tables/{table}}, including the commit of a staged create. */
    public CommitReply commitTable(ObjectName name, String requestJson, Caller caller, Optional<String> idempotencyKey) {
        return run(Operation.COMMIT_TABLE, one(name, requestJson), requestJson, caller, idempotencyKey);
    }

    /**
     * {@code POST .../register}: a table whose metadata file already exists (D34). The new table takes the
     * file's table UUID and location; the pointer names the file as it is, hashed as read. The file a dropped or
     * unregistered table of this catalog left at brings that table back as itself, in its own namespace (D13,
     * amended 2026-11-12): bringing back a dropped one is an undrop, which {@code undrop} is asked to allow.
     */
    public CommitReply registerTable(ObjectName name, String requestJson, Caller caller, Optional<String> idempotencyKey,
                                     Undrop undrop) {
        return run(Operation.REGISTER_TABLE, one(name, requestJson), requestJson, caller, idempotencyKey, undrop);
    }

    /** {@code POST .../register-view}: a view whose metadata file already exists, as {@link #registerTable}. */
    public CommitReply registerView(ObjectName name, String requestJson, Caller caller, Optional<String> idempotencyKey,
                                    Undrop undrop) {
        return run(Operation.REGISTER_VIEW, one(name, requestJson), requestJson, caller, idempotencyKey, undrop);
    }

    /**
     * {@code POST .../tables/{table}/unregister} (D13, amended 2026-11-12): the table leaves the catalog for
     * good, keeping its id, pointer and history, and its name is free at once. Its files stay where they are;
     * the answer names its current metadata file, which a client may register again. A legal hold or a DR
     * freeze on it or above it refuses it, and a commit racing it either lands first, and the unregister
     * retries against it, or is refused. So is a regulated table, or one flagged for row/column policy,
     * should a steward set the flag after the REST layer checked it.
     *
     * @param authorized the table the REST layer found by that name and authorized, if it found one. Only that
     *                   table is unregistered: when the name names another object, or none, the answer is 404,
     *                   so a table that takes the name after the check is never unregistered on another's
     *                   checks. A retry with a key still replays the first answer once the table is gone.
     */
    public CommitReply unregisterTable(ObjectName name, Optional<UUID> authorized, Caller caller,
                                       Optional<String> idempotencyKey) {
        return run(Operation.UNREGISTER_TABLE, one(name, ""), "", caller, idempotencyKey, Undrop.NEVER, authorized);
    }

    /**
     * Asked before a register brings a dropped table or view back, since that is an undrop (D25): it refuses
     * by throwing, as the REST layer's authorization does.
     */
    @FunctionalInterface
    public interface Undrop {
        /** For a request that never brings anything back. */
        Undrop NEVER = dropped -> {
            throw new IllegalStateException("this request brings nothing back");
        };

        void allow(CatalogObject dropped);
    }

    /** An object as a request names it, with its own commit request. */
    public record TableChange(ObjectName name, String json) {
        public TableChange {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(json, "json");
        }
    }

    /**
     * {@code POST .../transactions/commit} (D15): changes to existing tables of one catalog, all committed or
     * none. Each change is applied as {@link #commitTable} applies it, and every table that changes has its
     * pointer swapped in one store transaction, which locks them in key order. A missing table, a failed
     * requirement or a refusing rule refuses the whole transaction with nothing written; a swap lost to a
     * concurrent commit retries all of it against the tables' new metadata. A table whose change changes
     * nothing (requirements alone: a read guard) is held: its pointer is locked with the swapped ones and
     * must still be the one its requirements were checked against, so nothing commits to it in between.
     * A transaction that changes no table writes nothing, so its tables are not held.
     *
     * @param changes     one per table, in request order; the REST layer refuses an empty list, a table named
     *                    twice and a list over the cap
     * @param requestJson the whole request body, which an idempotency key binds to
     */
    public CommitReply commitTransaction(List<TableChange> changes, String requestJson, Caller caller,
                                         Optional<String> idempotencyKey) {
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("a transaction changes at least one table");
        }
        return run(Operation.COMMIT_TRANSACTION, List.copyOf(changes), requestJson, caller, idempotencyKey);
    }

    private CommitReply run(Operation operation, List<TableChange> targets, String json, Caller caller,
                            Optional<String> key) {
        return run(operation, targets, json, caller, key, Undrop.NEVER);
    }

    private static List<TableChange> one(ObjectName name, String json) {
        return List.of(new TableChange(name, json));
    }

    /**
     * The current metadata of an active table or view, from the cache or its file. A file that no longer
     * matches its hash is alerted; for a regulated object the read fails closed (D5, D26).
     */
    public Current readCurrent(CatalogObject object) {
        return readAt(object, store.readPointer(object.mainKey())
                .orElseThrow(() -> new IllegalStateException("active object " + object.id() + " has no pointer")));
    }

    /**
     * The metadata {@code pointer} names for a table or view, as {@link #readCurrent} reads main's: a branch's own
     * pointer, or the one a ref's cut took (D33). The file is checked against the pointer's hash, alerted and, for a
     * regulated object, refused as on main.
     */
    public Current readAt(CatalogObject object, Pointer pointer) {
        Optional<String> json = load(object, pointer, GovernanceChain.of(store, object));
        return json.<Current>map(j -> new Current.Loaded(pointer, j))
                .orElseGet(() -> new Current.Refused(new ServerError(SERVICE_FAILURE, "Metadata file "
                        + pointer.metadataLocation() + " does not match the hash its pointer recorded")));
    }

    /** What {@link #readCurrent} found. */
    public sealed interface Current {
        record Loaded(Pointer pointer, String json) implements Current {
        }

        record Refused(ServerError error) implements Current {
        }
    }

    /** {@code POST .../views}. */
    public CommitReply createView(ObjectName name, String requestJson, Caller caller, Optional<String> idempotencyKey) {
        return run(Operation.CREATE_VIEW, one(name, requestJson), requestJson, caller, idempotencyKey);
    }

    /** {@code POST .../views/{view}}: replace a view. */
    public CommitReply commitView(ObjectName name, String requestJson, Caller caller, Optional<String> idempotencyKey) {
        return run(Operation.COMMIT_VIEW, one(name, requestJson), requestJson, caller, idempotencyKey);
    }

    private enum Operation {
        CREATE_TABLE(CatalogObject.Kind.TABLE), COMMIT_TABLE(CatalogObject.Kind.TABLE),
        REGISTER_TABLE(CatalogObject.Kind.TABLE), CREATE_VIEW(CatalogObject.Kind.VIEW), COMMIT_VIEW(CatalogObject.Kind.VIEW),
        COMMIT_TRANSACTION(CatalogObject.Kind.TABLE), REGISTER_VIEW(CatalogObject.Kind.VIEW),
        UNREGISTER_TABLE(CatalogObject.Kind.TABLE);

        final CatalogObject.Kind kind;

        Operation(CatalogObject.Kind kind) {
            this.kind = kind;
        }
    }

    /** The idempotency record's fixed parts. */
    private record Key(String key, String principal, String requestHash, Instant expiresAt) {
    }

    /**
     * One request. {@code commitId} is also the idempotency claim's change id, and stays the same
     * across attempts: an attempt that lost its swap, or was found not committed, wrote nothing.
     *
     * @param targets the one object a single-object request names, or each table of a transaction
     * @param json    the whole request body
     */
    private record Request(Operation operation, List<TableChange> targets, String json, Caller caller, UUID commitId,
                           Optional<Key> key, Undrop undrop, Optional<UUID> authorized) {

        /** The object of a single-object request. */
        ObjectName name() {
            return targets.getFirst().name();
        }
    }

    // ---- idempotency (D10) ----

    private CommitReply run(Operation operation, List<TableChange> targets, String json, Caller caller,
                            Optional<String> key, Undrop undrop) {
        return run(operation, targets, json, caller, key, undrop, Optional.empty());
    }

    private CommitReply run(Operation operation, List<TableChange> targets, String json, Caller caller,
                            Optional<String> key, Undrop undrop, Optional<UUID> authorized) {
        UUID commitId = UUID.randomUUID();
        if (key.isEmpty()) {
            return work(new Request(operation, targets, json, caller, commitId, Optional.empty(), undrop, authorized));
        }
        Key fixed = new Key(key.get(), caller.principal().id(), requestHash(operation, targets, json, caller),
                clock.instant().plus(settings.keyLifetime()));
        Request request = new Request(operation, targets, json, caller, commitId, Optional.of(fixed), undrop,
                authorized);
        RuntimeException lastClaimFailure = null;
        for (int round = 0; round < CLAIM_ROUNDS; round++) {
            KeyState state;
            try {
                Instant now = clock.instant();
                state = store.claimKey(new IdempotencyClaim(fixed.key(), fixed.principal(), fixed.requestHash(),
                        commitId, now, now.plus(settings.keyLease()), fixed.expiresAt()));
            } catch (CommitOutcomeUnknownException | StoreException e) {
                // Claiming again with the same change id answers Claimed or InFlight(ours) if the first claim landed.
                LOG.log(System.Logger.Level.DEBUG, "claim of an idempotency key failed; claiming again", e);
                lastClaimFailure = e;
                continue;
            }
            switch (state) {
                case KeyState.Claimed claimed -> {
                    return finish(request, fixed);
                }
                case KeyState.InFlight inFlight when inFlight.changeId().equals(commitId) -> {
                    return finish(request, fixed);
                }
                case KeyState.Done done -> {
                    return replay(done.result());
                }
                case KeyState.Mismatch mismatch -> {
                    return new Failure(400, "BadRequestException",
                            "Idempotency key " + fixed.key() + " was used for a different request");
                }
                case KeyState.InFlight inFlight -> {
                    KeyState settled;
                    try {
                        settled = store.awaitKey(fixed.key(), settings.keyLease());
                    } catch (StoreUnavailableException e) {
                        return new Unavailable("Could not read idempotency key: " + e.getMessage());
                    } catch (StoreException e) {
                        return new ServerError(SERVICE_FAILURE, "Could not read idempotency key: " + e.getMessage());
                    }
                    if (settled instanceof KeyState.Done done) {
                        return replay(done.result());
                    }
                }
                case KeyState.Absent absent -> {
                }
            }
        }
        if (lastClaimFailure instanceof StoreUnavailableException unavailable) {
            // Nothing ran under the key; a request sent again claims it as this one would have.
            return new Unavailable("Could not claim idempotency key " + fixed.key() + ": " + unavailable.getMessage());
        }
        return new ServerError(SERVICE_FAILURE, "Could not claim idempotency key " + fixed.key());
    }

    /**
     * Runs the request holding the key, then settles the key: a commit's own transaction completed
     * it; a no-op or a definite 4xx is recorded; a 5xx releases it so a retry runs again.
     */
    private CommitReply finish(Request request, Key key) {
        CommitReply reply;
        try {
            reply = work(request);
        } catch (RuntimeException e) {
            release(key, request.commitId());
            throw e;
        }
        switch (reply) {
            case Success success when success.how() == How.COMMITTED -> {
            }
            case Success success -> complete(key, request.commitId(), 200,
                    Replies.success(success.objectId(), success.metadataLocation(), success.metadataSha256()));
            case Applied applied when applied.how() == How.COMMITTED -> {
            }
            case Applied applied -> complete(key, request.commitId(), Replies.APPLIED, "");
            case Failure failure -> complete(key, request.commitId(), failure.status(),
                    Replies.failure(failure.type(), failure.message()));
            case ServerError error -> release(key, request.commitId());
            case Unavailable unavailable -> release(key, request.commitId());
        }
        return reply;
    }

    private void complete(Key key, UUID changeId, int status, String body) {
        try {
            store.completeKey(new IdempotencyResult(key.key(), key.principal(), key.requestHash(), status, body,
                    key.expiresAt()), changeId);
        } catch (RuntimeException e) {
            // The answer still goes to the client; a retry with the key runs again once the claim lapses.
            LOG.log(System.Logger.Level.WARNING, "recording the answer for an idempotency key failed", e);
        }
    }

    private void release(Key key, UUID changeId) {
        try {
            store.releaseKey(key.key(), changeId);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "releasing an idempotency key failed; it lapses with its lease", e);
        }
    }

    private CommitReply replay(IdempotencyResult result) {
        return switch (Replies.decode(result)) {
            case Replies.Failure failure -> new Failure(failure.status(), failure.type(), failure.message());
            case Replies.Applied applied -> new Applied(How.REPLAYED);
            case Replies.Success success -> {
                String cached = cache.get(success.location());
                if (cached != null) {
                    yield new Success(success.objectId(), success.location(), success.sha256(), cached, How.REPLAYED);
                }
                TableFiles.Read read = files.read(success.location(), success.sha256());
                if (!read.intact()) {
                    alerts.metadataIntegrity(success.objectId(), success.location(), success.sha256(), read.sha256());
                    yield new ServerError(SERVICE_FAILURE, "Metadata file " + success.location()
                            + " does not match the hash its commit recorded");
                }
                cache.put(success.location(), read.json());
                yield new Success(success.objectId(), success.location(), success.sha256(), read.json(), How.REPLAYED);
            }
        };
    }

    /**
     * Binds a key to one request: what it does, to which object, for whom, with which body. A transaction
     * names its tables in its body, so it is bound to its catalog and the body.
     */
    private static String requestHash(Operation operation, List<TableChange> targets, String json, Caller caller) {
        ObjectName name = targets.getFirst().name();
        List<String> scope = operation == Operation.COMMIT_TRANSACTION
                ? List.of(name.catalogId().toString())
                : List.of(name.catalogId().toString(), name.namespaceId().toString(), name.name().normalized());
        String canonical = String.join("\n", operation.name(), String.join("\n", scope),
                caller.endUser().map(Principal::id).orElse(""), json);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM provides SHA-256", e);
        }
    }

    // ---- the request ----

    private CommitReply work(Request request) {
        try {
            return switch (request.operation()) {
                case CREATE_TABLE, CREATE_VIEW -> create(request);
                case COMMIT_TABLE, COMMIT_VIEW -> commit(request);
                case REGISTER_TABLE, REGISTER_VIEW -> register(request);
                case COMMIT_TRANSACTION -> transaction(request);
                case UNREGISTER_TABLE -> unregister(request);
            };
        } catch (StoreUnavailableException e) {
            // Thrown only before a COMMIT was sent: an attempt whose COMMIT was lost is settled by findCommit,
            // which answers CommitStateUnknownException when the store cannot say.
            LOG.log(System.Logger.Level.WARNING, "catalog store unavailable", e);
            return new Unavailable("Catalog store unavailable; nothing was applied: " + e.getMessage());
        } catch (StoreException e) {
            LOG.log(System.Logger.Level.WARNING, "catalog store failed", e);
            return new ServerError(SERVICE_FAILURE, "Catalog store failed: " + e.getMessage());
        }
    }

    private CommitReply create(Request request) {
        Optional<CatalogObject> existing = store.findObject(request.name().namespaceId(),
                request.name().name().normalized());
        if (existing.isPresent()) {
            return alreadyExists(request, existing.get().kind());
        }
        UUID id = UUID.randomUUID();
        String location = files.newLocation(id);
        Outcome outcome = request.operation() == Operation.CREATE_TABLE
                ? Metadata.createTable(request.json(), id.toString(), location)
                : Metadata.createView(request.json(), id.toString(), location, clock.millis());
        return switch (outcome) {
            case Outcome.NewMetadata created -> createObject(request, id, location, created,
                    new Metadata.Journal("[]", request.json()), alreadyExists(request, request.operation().kind),
                    Optional.empty());
            case Outcome.BadRequest bad -> new Failure(400, bad.type(), bad.message());
            case Outcome.RequirementFailed failed -> new Failure(409, COMMIT_FAILED, failed.message(),
                    failed.requirement());
            case Outcome.NoChange noChange -> throw new IllegalStateException("a create always writes metadata");
            case Outcome.NotFound notFound -> throw new IllegalStateException("a create never reads metadata");
        };
    }

    /**
     * Registers an existing metadata file. Its UUID becomes the object id, so a UUID that another object holds
     * is refused. A dropped or unregistered object of this catalog keeps its UUID and location (D13, D25), and
     * registering the file it left at, in its own namespace, brings it back as itself.
     */
    private CommitReply register(Request request) {
        Optional<CatalogObject> existing = store.findObject(request.name().namespaceId(),
                request.name().name().normalized());
        if (existing.isPresent()) {
            return alreadyExists(request, existing.get().kind());
        }
        boolean table = request.operation() == Operation.REGISTER_TABLE;
        String metadataLocation = table
                ? RestRequest.register(request.json()).metadataLocation()
                : RestRequest.registerView(request.json()).metadataLocation();
        TableFiles.Read read = files.read(metadataLocation, "");
        String kind = table ? "Table" : "View";
        Outcome outcome = table
                ? Metadata.registerTable(read.json(), metadataLocation)
                : Metadata.registerView(read.json(), metadataLocation);
        return switch (outcome) {
            case Outcome.NewMetadata found -> {
                UUID id;
                try {
                    id = UUID.fromString(found.after().uuid());
                } catch (RuntimeException e) {
                    yield new Failure(400, "BadRequestException", kind + " UUID must be a UUID, not "
                            + found.after().uuid());
                }
                TableFiles.Written file = new TableFiles.Written(metadataLocation, read.sha256());
                Optional<CatalogObject> holder = store.readObject(id);
                if (holder.isPresent()) {
                    yield revive(request, holder.get(), found, file);
                }
                yield createObject(request, id, found.after().location(), found,
                        new Metadata.Journal("[]", request.json()), alreadyExists(request, request.operation().kind),
                        Optional.of(file));
            }
            case Outcome.BadRequest bad -> new Failure(400, bad.type(), bad.message());
            case Outcome.RequirementFailed failed -> new Failure(400, "BadRequestException", failed.message());
            case Outcome.NoChange noChange -> throw new IllegalStateException("a register always creates");
            case Outcome.NotFound notFound -> throw new IllegalStateException("a register never reads a pointer");
        };
    }

    /**
     * Brings a dropped or unregistered object back under the name the request gives, keeping its id, grants and
     * history, with its main pointer swapped in the same transaction to the registered file (D13, D25, amended
     * 2026-11-12). It comes back only in the namespace it left, as an undrop restores it: another namespace
     * would hand it that namespace's grants and governance, which only a rename may do. Only the file its main
     * pointer names brings it back, so it returns exactly as it left and no rule a commit is held to is
     * skipped. Bringing back a dropped object is an undrop: it needs what an undrop needs, within the undrop
     * window, and is reported as one. Any other holder of the UUID refuses the register.
     */
    private CommitReply revive(Request request, CatalogObject holder, Outcome.NewMetadata found,
                               TableFiles.Written file) {
        ObjectName name = request.name();
        CatalogObject.Kind kind = request.operation().kind;
        String what = kind == CatalogObject.Kind.TABLE ? "Table" : "View";
        boolean revivable = holder.catalogId().equals(name.catalogId()) && holder.kind() == kind
                && (holder.state() == CatalogObject.State.DROPPED || holder.state() == CatalogObject.State.UNREGISTERED);
        if (!revivable) {
            return new Failure(409, "AlreadyExistsException", what + " UUID " + holder.id() + " already belongs to"
                    + " a table or view; only a dropped or unregistered " + what.toLowerCase(java.util.Locale.ROOT)
                    + " of this catalog is brought back by registering its own metadata file");
        }
        if (!holder.namespaceId().equals(name.namespaceId())) {
            return new Failure(409, "AlreadyExistsException", what + " UUID " + holder.id() + " belongs to a "
                    + holder.state().name().toLowerCase(java.util.Locale.ROOT) + " "
                    + what.toLowerCase(java.util.Locale.ROOT) + " of another namespace; it is brought back only in"
                    + " its own namespace, and a rename then moves it");
        }
        boolean dropped = holder.state() == CatalogObject.State.DROPPED;
        if (dropped) {
            if (!clock.instant().isBefore(holder.purgeAfter().orElseThrow())) {
                return new Failure(409, "UndropWindowClosedException", "The undrop window of " + holder.id()
                        + " closed at " + holder.purgeAfter().get() + "; it is waiting to be purged");
            }
            request.undrop().allow(holder);
        }
        CatalogObject revived = CatalogObject.active(name.catalogId(), holder.id(), name.namespaceId(), name.name(),
                kind, holder.locationRoot());
        Optional<Failure> refused = judge(revived, null, found.after(), GovernanceChain.of(store, revived));
        if (refused.isPresent()) {
            return refused.get();
        }
        CommitEvents.Kind reported = dropped ? CommitEvents.Kind.UNDROP : CommitEvents.Kind.COMMIT;
        Instant started = clock.instant();
        for (int attempt = 1; ; attempt++) {
            Pointer base = store.readPointer(holder.mainKey())
                    .orElseThrow(() -> new IllegalStateException("object " + holder.id() + " has no pointer"));
            if (!base.metadataLocation().equals(file.location())) {
                return new Failure(409, "AlreadyExistsException", what + " " + holder.id() + " is brought back only"
                        + " by the metadata file it was " + holder.state().name().toLowerCase(java.util.Locale.ROOT)
                        + " at, not " + file.location());
            }
            if (!base.sha256().equals(file.sha256())) {
                alerts.metadataIntegrity(holder.id(), file.location(), base.sha256(), file.sha256());
                return new ServerError(SERVICE_FAILURE, "Metadata file " + file.location()
                        + " does not match the hash its pointer recorded");
            }
            Pointer next = new Pointer(file.location(), base.version() + 1, file.sha256(), Projection.of(found.after()));
            CommitBatch batch = batch(request, revived, Optional.of(base), next,
                    new Metadata.Journal("[]", request.json()), false, reported);
            try {
                WriteResult result = store.reviveObject(holder.id(), holder.state(), name.namespaceId(), name.name(),
                        batch);
                switch (result) {
                    case WriteResult.Applied applied -> {
                        return committed(revived, next, found.json(), false, request.commitId());
                    }
                    case WriteResult.NameTaken taken -> {
                        return alreadyExists(request, kind);
                    }
                    case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.NAMESPACE -> {
                        return new Failure(404, "NoSuchNamespaceException",
                                "Namespace does not exist: " + name.namespaceId());
                    }
                    case WriteResult.Missing missing -> {
                        return new Failure(409, COMMIT_FAILED, what + " " + holder.id()
                                + " was brought back, purged or otherwise changed while being registered");
                    }
                    case WriteResult.VersionMismatch mismatch -> {
                        if (!backOff(attempt, started)) {
                            return new Failure(409, COMMIT_FAILED, "Register lost to concurrent changes " + attempt
                                    + " times");
                        }
                    }
                    default -> throw new IllegalStateException("reviveObject answered " + result);
                }
            } catch (CommitOutcomeUnknownException e) {
                Optional<CommitReply> settled = settle(request, holder.mainKey(),
                        () -> committed(revived, next, found.json(), false, request.commitId()), attempt, started);
                if (settled.isPresent()) {
                    return settled.get();
                }
            }
        }
    }

    /**
     * Unregisters an active table (D13), the one the REST layer authorized: its main pointer as read must
     * still be current when the store locks it, so a commit racing the unregister either lands first, and
     * the unregister reads again, or is refused after it. The answer names the metadata the table was
     * unregistered at; a key replays it. One whose outcome was unknown is decided by reading the table back,
     * waiting out a short outage as {@link #decide} does.
     */
    private CommitReply unregister(Request request) {
        Optional<CatalogObject> found = store.findObject(request.name().namespaceId(),
                request.name().name().normalized());
        if (found.isEmpty() || found.get().kind() != CatalogObject.Kind.TABLE
                || !request.authorized().equals(Optional.of(found.get().id()))) {
            return noSuch(request);
        }
        CatalogObject object = found.get();
        GovernanceChain governance = GovernanceChain.of(store, object);
        Instant started = clock.instant();
        for (int attempt = 1; ; attempt++) {
            Pointer current = store.readPointer(object.mainKey())
                    .orElseThrow(() -> new IllegalStateException("active object " + object.id() + " has no pointer"));
            Optional<String> json = load(object, current, governance);
            if (json.isEmpty()) {
                return new ServerError(SERVICE_FAILURE, "Metadata file " + current.metadataLocation()
                        + " does not match the hash its pointer recorded");
            }
            Instant at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Change change = new Change(request.commitId(), at, events.of(new CommitEvents.Commit(request.commitId(), at,
                    request.caller(), object, Optional.of(current), current, "[]", "[]", false,
                    CommitEvents.Kind.UNREGISTER)), request.key().map(key -> new IdempotencyResult(key.key(),
                    key.principal(), key.requestHash(), 200,
                    Replies.success(object.id(), current.metadataLocation(), current.sha256()), key.expiresAt())));
            Success unregistered = new Success(object.id(), current.metadataLocation(), current.sha256(), json.get(),
                    How.COMMITTED);
            WriteResult result;
            try {
                result = store.unregisterObject(object.id(), current, change);
            } catch (CommitOutcomeUnknownException e) {
                Optional<CatalogObject> after;
                try {
                    after = decide(() -> store.readObject(object.id()), started, "unregister " + request.commitId());
                } catch (StoreException readFailed) {
                    return new ServerError(STATE_UNKNOWN, "Unregister of " + display(request)
                            + " may or may not have been applied: " + e.getMessage());
                }
                if (after.filter(o -> o.state() == CatalogObject.State.UNREGISTERED
                        && o.droppedAt().equals(Optional.of(at))).isPresent()) {
                    return unregistered;
                }
                if (backOff(attempt, started)) {
                    continue;
                }
                return new ServerError(SERVICE_FAILURE, "Unregister of " + display(request)
                        + " was not applied and the retry bound is spent");
            }
            switch (result) {
                case WriteResult.Applied applied -> {
                    return unregistered;
                }
                case WriteResult.VersionMismatch mismatch -> {
                    if (!backOff(attempt, started)) {
                        return new Failure(409, COMMIT_FAILED, "Unregister lost to concurrent commits " + attempt
                                + " times; the table changed too often");
                    }
                }
                case WriteResult.Missing missing -> {
                    return noSuch(request);
                }
                case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.HELD -> {
                    return new Failure(403, "ForbiddenException", "Table " + display(request)
                            + " is under a legal hold and cannot be unregistered");
                }
                case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.FROZEN -> {
                    return new Failure(403, "ForbiddenException", "Table " + display(request)
                            + " is frozen for DR reconcile and cannot be unregistered");
                }
                case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.FLAGGED -> {
                    return new Failure(403, "ForbiddenException", "Table " + display(request) + " is regulated or"
                            + " has a row/column policy and cannot be unregistered; a steward removes the flag first");
                }
                default -> throw new IllegalStateException("unregisterObject answered " + result);
            }
        }
    }

    private CommitReply commit(Request request) {
        Optional<CatalogObject> found = store.findObject(request.name().namespaceId(),
                request.name().name().normalized());
        if (found.isPresent() && found.get().kind() == request.operation().kind) {
            return update(request, found.get());
        }
        if (request.operation() == Operation.COMMIT_VIEW) {
            return noSuch(request);
        }
        // No table by that name: only the commit of a staged create (assert-create) can succeed.
        return switch (Metadata.commitTable(request.json(), null, null)) {
            case Outcome.NewMetadata created when found.isPresent() ->
                    new Failure(409, "AlreadyExistsException", "View with same name already exists: " + display(request));
            case Outcome.NewMetadata created -> stagedCreate(request, created);
            case Outcome.NotFound notFound -> noSuch(request);
            case Outcome.BadRequest bad -> new Failure(400, bad.type(), bad.message());
            case Outcome.RequirementFailed failed -> new Failure(409, COMMIT_FAILED, failed.message(),
                    failed.requirement());
            case Outcome.NoChange noChange -> throw new IllegalStateException("a create always writes metadata");
        };
    }

    /**
     * A staged create keeps the table UUID the staging response assigned; the object takes it as its
     * id, and its location must be the one the catalog assigns to that id.
     */
    private CommitReply stagedCreate(Request request, Outcome.NewMetadata created) {
        UUID id;
        try {
            id = UUID.fromString(created.after().uuid());
        } catch (RuntimeException e) {
            return new Failure(400, "BadRequestException", "Table UUID must be the one the catalog assigned, not "
                    + created.after().uuid());
        }
        if (store.readObject(id).isPresent()) {
            return new Failure(409, COMMIT_FAILED, "Table UUID " + id + " is already in use");
        }
        return createObject(request, id, files.newLocation(id), created, Metadata.journal(request.json()),
                new Failure(409, "AlreadyExistsException", "Table already exists: " + display(request)), Optional.empty());
    }

    /**
     * Writes version 1, unless {@code existing} names a file already written, and creates the object with
     * its first pointer (storage-design 9 a).
     */
    private CommitReply createObject(Request request, UUID id, String location, Outcome.NewMetadata created,
                                     Metadata.Journal journal, Failure nameTaken,
                                     Optional<TableFiles.Written> existing) {
        ObjectName name = request.name();
        CatalogObject object = CatalogObject.active(name.catalogId(), id, name.namespaceId(), name.name(),
                request.operation().kind, location);
        Optional<Failure> refused = judge(object, null, created.after(), GovernanceChain.of(store, object));
        if (refused.isPresent()) {
            return refused.get();
        }
        TableFiles.Written written = existing.orElseGet(() -> files.write(location, 1, created.json()));
        Pointer first = new Pointer(written.location(), 1, written.sha256(), Projection.of(created.after()));
        ObjectCreation creation = new ObjectCreation(object,
                batch(request, object, Optional.empty(), first, journal, false, CommitEvents.Kind.COMMIT));
        Instant started = clock.instant();
        for (int attempt = 1; ; attempt++) {
            try {
                WriteResult result = store.createObject(creation);
                switch (result) {
                    case WriteResult.Applied applied -> {
                        return committed(object, first, created.json(), false, request.commitId());
                    }
                    case WriteResult.NameTaken taken -> {
                        return nameTaken;
                    }
                    case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.NAMESPACE -> {
                        return new Failure(404, "NoSuchNamespaceException",
                                "Namespace does not exist: " + name.namespaceId());
                    }
                    default -> throw new IllegalStateException("createObject answered " + result);
                }
            } catch (CommitOutcomeUnknownException e) {
                Optional<CommitReply> settled = settle(request, object.mainKey(),
                        () -> committed(object, first, created.json(), false, request.commitId()), attempt, started);
                if (settled.isPresent()) {
                    return settled.get();
                }
            }
        }
    }

    /** Applies the request to the current metadata and swaps the pointer, retrying a lost swap. */
    private CommitReply update(Request request, CatalogObject object) {
        GovernanceChain governance = GovernanceChain.of(store, object);
        Instant started = clock.instant();
        for (int attempt = 1; ; attempt++) {
            Pointer base = store.readPointer(object.mainKey())
                    .orElseThrow(() -> new IllegalStateException("active object " + object.id() + " has no pointer"));
            Optional<String> current = load(object, base, governance);
            if (current.isEmpty()) {
                return new ServerError(SERVICE_FAILURE, "Metadata file " + base.metadataLocation()
                        + " does not match the hash its pointer recorded");
            }
            Outcome outcome = request.operation() == Operation.COMMIT_TABLE
                    ? Metadata.commitTable(request.json(), current.get(), base.metadataLocation())
                    : Metadata.commitView(request.json(), current.get());
            Outcome.NewMetadata changed;
            switch (outcome) {
                case Outcome.NewMetadata newMetadata -> changed = newMetadata;
                case Outcome.NoChange noChange -> {
                    return new Success(object.id(), base.metadataLocation(), base.sha256(), current.get(),
                            How.UNCHANGED);
                }
                case Outcome.RequirementFailed failed -> {
                    return new Failure(409, COMMIT_FAILED, failed.message(), failed.requirement());
                }
                case Outcome.BadRequest bad -> {
                    return new Failure(400, bad.type(), bad.message());
                }
                case Outcome.NotFound notFound -> throw new IllegalStateException("current metadata was given");
            }
            Optional<Failure> refused = judge(object, changed.before(), changed.after(), governance);
            if (refused.isPresent()) {
                return refused.get();
            }
            long version = base.version() + 1;
            TableFiles.Written written = files.write(object.locationRoot(), version, changed.json());
            Pointer next = new Pointer(written.location(), version, written.sha256(), Projection.of(changed.after()));
            boolean regulatedChange = schemaOrSpecChanged(changed.before(), changed.after()) && governance.regulated();
            CommitBatch batch = batch(request, object, Optional.of(base), next,
                    Metadata.journal(request.json()), regulatedChange, CommitEvents.Kind.COMMIT);
            try {
                CommitResult result = store.commit(batch);
                switch (result) {
                    case CommitResult.Committed done -> {
                        return committed(object, next, changed.json(), regulatedChange, request.commitId());
                    }
                    case CommitResult.Conflict conflict -> {
                        if (!backOff(attempt, started)) {
                            return new Failure(409, COMMIT_FAILED, "Commit lost to concurrent commits " + attempt
                                    + " times; the table changed too often to apply it");
                        }
                    }
                    case CommitResult.Inactive inactive -> {
                        return noSuch(request);
                    }
                }
            } catch (CommitOutcomeUnknownException e) {
                Optional<CommitReply> settled = settle(request, object.mainKey(),
                        () -> committed(object, next, changed.json(), regulatedChange, request.commitId()),
                        attempt, started);
                if (settled.isPresent()) {
                    return settled.get();
                }
            }
        }
    }

    /** One table of a transaction attempt that changes: what it read, and the metadata its change makes. */
    private record Planned(CatalogObject object, Pointer base, Outcome.NewMetadata changed, String requestJson,
                           boolean regulatedChange) {
    }

    /** A planned change whose metadata file is written: {@code next} is the pointer it swaps in. */
    private record Staged(Planned plan, Pointer next) {
    }

    /**
     * Applies every change of a transaction to its table's current metadata and swaps the changed tables'
     * pointers in one batch, holding the unchanged tables' pointers as read, and retries a lost swap or hold
     * as {@link #update} retries a lost swap (D15). Each table is resolved by name once, before the first
     * attempt, as {@link #update} resolves its one table.
     */
    private CommitReply transaction(Request request) {
        List<CatalogObject> objects = new ArrayList<>();
        for (TableChange change : request.targets()) {
            Optional<CatalogObject> found = store.findObject(change.name().namespaceId(),
                    change.name().name().normalized());
            if (found.isEmpty() || found.get().kind() != CatalogObject.Kind.TABLE) {
                return new Failure(404, "NoSuchTableException", "Table does not exist: " + change.name().qualified());
            }
            objects.add(found.get());
        }
        List<GovernanceChain> governance = objects.stream().map(o -> GovernanceChain.of(store, o)).toList();
        Instant started = clock.instant();
        for (int attempt = 1; ; attempt++) {
            List<Planned> planned = new ArrayList<>();
            List<PointerHold> held = new ArrayList<>();
            for (int i = 0; i < objects.size(); i++) {
                CatalogObject object = objects.get(i);
                String table = request.targets().get(i).name().qualified();
                String json = request.targets().get(i).json();
                Pointer base = store.readPointer(object.mainKey())
                        .orElseThrow(() -> new IllegalStateException("active object " + object.id() + " has no pointer"));
                Optional<String> current = load(object, base, governance.get(i));
                if (current.isEmpty()) {
                    return new ServerError(SERVICE_FAILURE, "Metadata file " + base.metadataLocation()
                            + " of " + table + " does not match the hash its pointer recorded");
                }
                switch (Metadata.commitTable(json, current.get(), base.metadataLocation())) {
                    case Outcome.NewMetadata changed -> {
                        Optional<Failure> refused = judge(object, changed.before(), changed.after(), governance.get(i));
                        if (refused.isPresent()) {
                            Failure f = refused.get();
                            return new Failure(f.status(), f.type(), table + ": " + f.message());
                        }
                        boolean regulated = schemaOrSpecChanged(changed.before(), changed.after())
                                && governance.get(i).regulated();
                        planned.add(new Planned(object, base, changed, json, regulated));
                    }
                    case Outcome.NoChange noChange -> held.add(new PointerHold(object.mainKey(), base));
                    case Outcome.RequirementFailed failed -> {
                        return new Failure(409, COMMIT_FAILED, table + ": " + failed.message(),
                                failed.requirement());
                    }
                    case Outcome.BadRequest bad -> {
                        return new Failure(400, bad.type(), table + ": " + bad.message());
                    }
                    case Outcome.NotFound notFound -> throw new IllegalStateException("current metadata was given");
                }
            }
            if (planned.isEmpty()) {
                return new Applied(How.UNCHANGED);
            }
            // Files are written only once every table has passed, so a refused transaction leaves none behind.
            List<Staged> written = planned.stream().map(p -> {
                long version = p.base().version() + 1;
                TableFiles.Written file = files.write(p.object().locationRoot(), version, p.changed().json());
                return new Staged(p, new Pointer(file.location(), version, file.sha256(),
                        Projection.of(p.changed().after())));
            }).toList();
            try {
                switch (store.commit(transactionBatch(request, written, held))) {
                    case CommitResult.Committed done -> {
                        return applied(written, request.commitId());
                    }
                    case CommitResult.Conflict conflict -> {
                        if (!backOff(attempt, started)) {
                            return new Failure(409, COMMIT_FAILED, "Transaction lost to concurrent commits " + attempt
                                    + " times; its tables changed too often to apply it");
                        }
                    }
                    case CommitResult.Inactive inactive -> {
                        int gone = objects.stream().map(CatalogObject::mainKey).toList().indexOf(inactive.key());
                        return new Failure(404, "NoSuchTableException", "Table does not exist: "
                                + request.targets().get(gone).name().qualified());
                    }
                }
            } catch (CommitOutcomeUnknownException e) {
                Optional<CommitReply> settled = settle(request, written.getFirst().plan().object().mainKey(),
                        () -> applied(written, request.commitId()), attempt, started);
                if (settled.isPresent()) {
                    return settled.get();
                }
            }
        }
    }

    private CommitReply applied(List<Staged> written, UUID commitId) {
        for (Staged s : written) {
            committed(s.plan().object(), s.next(), s.plan().changed().json(), s.plan().regulatedChange(), commitId);
        }
        return new Applied(How.COMMITTED);
    }

    private CommitBatch transactionBatch(Request request, List<Staged> written, List<PointerHold> held) {
        Instant at = clock.instant();
        Caller caller = request.caller();
        List<PointerSwap> swaps = new ArrayList<>();
        List<OutboxEvent> outbox = new ArrayList<>();
        for (Staged s : written) {
            Planned p = s.plan();
            Metadata.Journal journal = Metadata.journal(p.requestJson());
            swaps.add(new PointerSwap(p.object().mainKey(), Optional.of(p.base()), s.next(),
                    new JournalEntry(caller.principal().id(), caller.endUser().map(Principal::id).orElse(null),
                            caller.engine(), journal.requirementsJson(), journal.updatesJson())));
            outbox.addAll(events.of(new CommitEvents.Commit(request.commitId(), at, caller, p.object(),
                    Optional.of(p.base()), s.next(), journal.requirementsJson(), journal.updatesJson(),
                    p.regulatedChange())));
        }
        Optional<IdempotencyResult> result = request.key().map(key -> new IdempotencyResult(key.key(),
                key.principal(), key.requestHash(), Replies.APPLIED, "", key.expiresAt()));
        return new CommitBatch(request.commitId(), at, swaps, held, outbox, result);
    }

    /**
     * Decides a commit whose outcome was unknown (D9), by any one key it swapped, asking the store as
     * {@link #decide} does. Empty means it did not commit and another attempt may run.
     */
    private Optional<CommitReply> settle(Request request, PointerKey anyKey, Supplier<CommitReply> landed,
                                         int attempt, Instant started) {
        CommitLookup lookup;
        try {
            lookup = decide(() -> store.findCommit(request.commitId(), anyKey), started,
                    "commit " + request.commitId());
        } catch (StoreException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot decide commit " + request.commitId(), e);
            return Optional.of(new ServerError(STATE_UNKNOWN, "Commit " + request.commitId()
                    + " may or may not have been applied: " + e.getMessage()));
        }
        if (lookup == CommitLookup.COMMITTED) {
            return Optional.of(landed.get());
        }
        if (backOff(attempt, started)) {
            return Optional.empty();
        }
        return Optional.of(new ServerError(SERVICE_FAILURE, "Commit " + request.commitId()
                + " was not applied and the retry bound is spent"));
    }

    private <T> T decide(Supplier<T> ask, Instant started, String what) {
        return decide(settings, clock, sleeper, ask, started, what);
    }

    /**
     * Asks the store what became of a write whose outcome was unknown (D9). A store that cannot be reached
     * is asked again within the retry bounds (P10): at most {@code maxAttempts} times, with the commit loop's
     * backoff, and never after the request's retry budget, counted from {@code started}. Throws the last
     * failure once those are spent, and any other {@link StoreException} (the store answers but cannot
     * say) at once. The DR reconcile ({@link Reconcile}) decides its steps the same way.
     */
    static <T> T decide(CommitSettings settings, Clock clock, Sleeper sleeper, Supplier<T> ask, Instant started,
                        String what) {
        for (int asked = 1; ; asked++) {
            try {
                return ask.get();
            } catch (StoreUnavailableException e) {
                if (!backOff(settings, clock, sleeper, asked, started)) {
                    throw e;
                }
                LOG.log(System.Logger.Level.DEBUG, "store unavailable deciding " + what, e);
            }
        }
    }

    private CommitReply committed(CatalogObject object, Pointer pointer, String json, boolean regulatedChange,
                                  UUID commitId) {
        cache.put(pointer.metadataLocation(), json);
        if (regulatedChange) {
            alerts.regulatedSchemaChange(object.id(), commitId);
        }
        return new Success(object.id(), pointer.metadataLocation(), pointer.sha256(), json, How.COMMITTED);
    }

    /**
     * Waits before the next attempt if one is allowed: at most {@code maxAttempts}, and none that
     * would start after the retry budget. The wait doubles per attempt, with up to 10% jitter.
     */
    private boolean backOff(int attempt, Instant started) {
        return backOff(settings, clock, sleeper, attempt, started);
    }

    private static boolean backOff(CommitSettings settings, Clock clock, Sleeper sleeper, int attempt,
                                   Instant started) {
        if (attempt >= settings.maxAttempts()) {
            return false;
        }
        long base = settings.firstBackoff().toMillis() << Math.min(attempt - 1, 20);
        Duration wait = Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(base / 10 + 1));
        Duration elapsed = Duration.between(started, clock.instant());
        if (elapsed.plus(wait).compareTo(settings.retryBudget()) > 0) {
            return false;
        }
        sleeper.sleep(wait);
        return true;
    }

    private Optional<Failure> judge(CatalogObject object, Facts before, Facts after, CommitRule.Governance governance) {
        CommitRule.Change change = new CommitRule.Change(object, before, after, clock.instant(), governance);
        for (CommitRule rule : rules) {
            Optional<Rejection> rejection = rule.judge(change);
            if (rejection.isPresent()) {
                Rejection r = rejection.get();
                return Optional.of(new Failure(r.status(), r.type(), r.message()));
            }
        }
        return Optional.empty();
    }

    /**
     * The metadata a pointer names, from the cache or the store. A file that no longer matches its
     * hash is alerted; a regulated object then fails closed (empty), any other goes on with it (D26).
     */
    private Optional<String> load(CatalogObject object, Pointer pointer, CommitRule.Governance governance) {
        String cached = cache.get(pointer.metadataLocation());
        if (cached != null) {
            return Optional.of(cached);
        }
        TableFiles.Read read = files.read(pointer.metadataLocation(), pointer.sha256());
        if (read.intact()) {
            cache.put(pointer.metadataLocation(), read.json());
            return Optional.of(read.json());
        }
        alerts.metadataIntegrity(object.id(), pointer.metadataLocation(), pointer.sha256(), read.sha256());
        return governance.regulated() ? Optional.empty() : Optional.of(read.json());
    }

    private CommitBatch batch(Request request, CatalogObject object, Optional<Pointer> before, Pointer next,
                              Metadata.Journal journal, boolean regulatedChange, CommitEvents.Kind kind) {
        Instant at = clock.instant();
        Caller caller = request.caller();
        JournalEntry entry = new JournalEntry(caller.principal().id(), caller.endUser().map(Principal::id).orElse(null),
                caller.engine(), journal.requirementsJson(), journal.updatesJson());
        List<OutboxEvent> outbox = events.of(new CommitEvents.Commit(request.commitId(), at, caller, object, before,
                next, journal.requirementsJson(), journal.updatesJson(), regulatedChange, kind));
        Optional<IdempotencyResult> result = request.key().map(key -> new IdempotencyResult(key.key(),
                key.principal(), key.requestHash(), 200,
                Replies.success(object.id(), next.metadataLocation(), next.sha256()), key.expiresAt()));
        return new CommitBatch(request.commitId(), at, List.of(new PointerSwap(object.mainKey(), before, next, entry)),
                outbox, result);
    }

    /** D11a: a table's current schema or default partition spec moved. */
    static boolean schemaOrSpecChanged(Facts before, Facts after) {
        return before instanceof TableFacts b && after instanceof TableFacts a
                && (b.currentSchemaId() != a.currentSchemaId() || b.defaultSpecId() != a.defaultSpecId());
    }

    /** Worded as iceberg-core words it, by what was asked for and what holds the name. */
    private static Failure alreadyExists(Request request, CatalogObject.Kind holder) {
        boolean sameKind = holder == request.operation().kind;
        String message = holder == CatalogObject.Kind.TABLE
                ? (sameKind ? "Table already exists: " : "Table with same name already exists: ")
                : (sameKind ? "View already exists: " : "View with same name already exists: ");
        return new Failure(409, "AlreadyExistsException", message + display(request));
    }

    private static Failure noSuch(Request request) {
        return request.operation().kind == CatalogObject.Kind.TABLE
                ? new Failure(404, "NoSuchTableException", "Table does not exist: " + display(request))
                : new Failure(404, "NoSuchViewException", "View does not exist: " + display(request));
    }

    private static String display(Request request) {
        return request.name().qualified();
    }
}

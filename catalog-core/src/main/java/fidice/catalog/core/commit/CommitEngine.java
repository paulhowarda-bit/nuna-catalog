package fidice.catalog.core.commit;

import fidice.catalog.core.commit.CommitReply.Failure;
import fidice.catalog.core.commit.CommitReply.How;
import fidice.catalog.core.commit.CommitReply.ServerError;
import fidice.catalog.core.commit.CommitReply.Success;
import fidice.catalog.core.commit.CommitRule.Rejection;
import fidice.catalog.core.principal.Caller;
import fidice.catalog.core.principal.Principal;
import fidice.catalog.iceberg.Facts;
import fidice.catalog.iceberg.Facts.TableFacts;
import fidice.catalog.iceberg.Metadata;
import fidice.catalog.iceberg.Outcome;
import fidice.catalog.iceberg.RestRequest;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitLookup;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.IdempotencyClaim;
import fidice.catalog.store.IdempotencyResult;
import fidice.catalog.store.JournalEntry;
import fidice.catalog.store.KeyState;
import fidice.catalog.store.ObjectCreation;
import fidice.catalog.store.OutboxEvent;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerSwap;
import fidice.catalog.store.StoreException;
import fidice.catalog.store.WriteResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The commit loop of build-plan 2.2 for one table or view: idempotency (D10), apply the request with
 * iceberg-core's builder (D3), judge the rules (D11), write the metadata file, swap the pointer with
 * its journal, outbox and idempotency records in one transaction (D5), and retry a lost swap within
 * the bounds of P10 (D9). It answers as iceberg-core's CatalogHandlers does: a request that changes
 * nothing writes nothing, a requirement that fails against current metadata is a 409, and a
 * swap lost to a concurrent commit is retried against the new metadata.
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
        return run(Operation.CREATE_TABLE, name, requestJson, caller, idempotencyKey);
    }

    /** {@code POST .../tables/{table}}, including the commit of a staged create. */
    public CommitReply commitTable(ObjectName name, String requestJson, Caller caller, Optional<String> idempotencyKey) {
        return run(Operation.COMMIT_TABLE, name, requestJson, caller, idempotencyKey);
    }

    /**
     * {@code POST .../register}: a table whose metadata file already exists (D34). The new table takes the
     * file's table UUID and location; the pointer names the file as it is, hashed as read.
     */
    public CommitReply registerTable(ObjectName name, String requestJson, Caller caller, Optional<String> idempotencyKey) {
        return run(Operation.REGISTER_TABLE, name, requestJson, caller, idempotencyKey);
    }

    /**
     * The current metadata of an active table or view, from the cache or its file. A file that no longer
     * matches its hash is alerted; for a regulated object the read fails closed (D5, D26).
     */
    public Current readCurrent(CatalogObject object) {
        Pointer pointer = store.readPointer(object.mainKey())
                .orElseThrow(() -> new IllegalStateException("active object " + object.id() + " has no pointer"));
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
        return run(Operation.CREATE_VIEW, name, requestJson, caller, idempotencyKey);
    }

    /** {@code POST .../views/{view}}: replace a view. */
    public CommitReply commitView(ObjectName name, String requestJson, Caller caller, Optional<String> idempotencyKey) {
        return run(Operation.COMMIT_VIEW, name, requestJson, caller, idempotencyKey);
    }

    private enum Operation {
        CREATE_TABLE(CatalogObject.Kind.TABLE), COMMIT_TABLE(CatalogObject.Kind.TABLE),
        REGISTER_TABLE(CatalogObject.Kind.TABLE), CREATE_VIEW(CatalogObject.Kind.VIEW), COMMIT_VIEW(CatalogObject.Kind.VIEW);

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
     */
    private record Request(Operation operation, ObjectName name, String json, Caller caller, UUID commitId,
                           Optional<Key> key) {
    }

    // ---- idempotency (D10) ----

    private CommitReply run(Operation operation, ObjectName name, String json, Caller caller, Optional<String> key) {
        UUID commitId = UUID.randomUUID();
        if (key.isEmpty()) {
            return work(new Request(operation, name, json, caller, commitId, Optional.empty()));
        }
        Key fixed = new Key(key.get(), caller.principal().id(), requestHash(operation, name, json, caller),
                clock.instant().plus(settings.keyLifetime()));
        Request request = new Request(operation, name, json, caller, commitId, Optional.of(fixed));
        for (int round = 0; round < CLAIM_ROUNDS; round++) {
            KeyState state;
            try {
                Instant now = clock.instant();
                state = store.claimKey(new IdempotencyClaim(fixed.key(), fixed.principal(), fixed.requestHash(),
                        commitId, now, now.plus(settings.keyLease()), fixed.expiresAt()));
            } catch (CommitOutcomeUnknownException | StoreException e) {
                // Claiming again with the same change id answers Claimed or InFlight(ours) if the first claim landed.
                LOG.log(System.Logger.Level.DEBUG, "claim of an idempotency key failed; claiming again", e);
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
            case Failure failure -> complete(key, request.commitId(), failure.status(),
                    Replies.failure(failure.type(), failure.message()));
            case ServerError error -> release(key, request.commitId());
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

    /** Binds a key to one request: what it does, to which object, for whom, with which body. */
    private static String requestHash(Operation operation, ObjectName name, String json, Caller caller) {
        String canonical = String.join("\n", operation.name(), name.catalogId().toString(),
                name.namespaceId().toString(), name.name().normalized(),
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
                case REGISTER_TABLE -> register(request);
            };
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
            case Outcome.RequirementFailed failed -> new Failure(409, COMMIT_FAILED, failed.message());
            case Outcome.NoChange noChange -> throw new IllegalStateException("a create always writes metadata");
            case Outcome.NotFound notFound -> throw new IllegalStateException("a create never reads metadata");
        };
    }

    /**
     * Registers an existing metadata file. Its table UUID becomes the object id, so a UUID that any object
     * in the catalog holds, a dropped one included, is refused: a dropped table keeps its UUID and location
     * until it is purged (D25), and is brought back by undrop, not by registering its file again.
     */
    private CommitReply register(Request request) {
        Optional<CatalogObject> existing = store.findObject(request.name().namespaceId(),
                request.name().name().normalized());
        if (existing.isPresent()) {
            return alreadyExists(request, existing.get().kind());
        }
        String metadataLocation = RestRequest.register(request.json()).metadataLocation();
        TableFiles.Read read = files.read(metadataLocation, "");
        return switch (Metadata.registerTable(read.json(), metadataLocation)) {
            case Outcome.NewMetadata found -> {
                UUID id;
                try {
                    id = UUID.fromString(found.after().uuid());
                } catch (RuntimeException e) {
                    yield new Failure(400, "BadRequestException", "Table UUID must be a UUID, not "
                            + found.after().uuid());
                }
                if (store.readObject(id).isPresent()) {
                    yield new Failure(409, "AlreadyExistsException", "Table UUID " + id
                            + " already belongs to a table in this catalog; a dropped table is brought back by undrop");
                }
                yield createObject(request, id, found.after().location(), found,
                        new Metadata.Journal("[]", request.json()), alreadyExists(request, CatalogObject.Kind.TABLE),
                        Optional.of(new TableFiles.Written(metadataLocation, read.sha256())));
            }
            case Outcome.BadRequest bad -> new Failure(400, bad.type(), bad.message());
            case Outcome.RequirementFailed failed -> new Failure(400, "BadRequestException", failed.message());
            case Outcome.NoChange noChange -> throw new IllegalStateException("a register always creates");
            case Outcome.NotFound notFound -> throw new IllegalStateException("a register never reads a pointer");
        };
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
            case Outcome.RequirementFailed failed -> new Failure(409, COMMIT_FAILED, failed.message());
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
                batch(request, object, Optional.empty(), first, journal, false));
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
                Optional<CommitReply> settled = settle(request, object, first, created.json(), false, attempt, started);
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
                    return new Failure(409, COMMIT_FAILED, failed.message());
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
                    Metadata.journal(request.json()), regulatedChange);
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
                }
            } catch (CommitOutcomeUnknownException e) {
                Optional<CommitReply> settled = settle(request, object, next, changed.json(), regulatedChange,
                        attempt, started);
                if (settled.isPresent()) {
                    return settled.get();
                }
            }
        }
    }

    /**
     * Decides a commit whose outcome was unknown (D9). Empty means it did not commit and another
     * attempt may run.
     */
    private Optional<CommitReply> settle(Request request, CatalogObject object, Pointer next, String json,
                                         boolean regulatedChange, int attempt, Instant started) {
        CommitLookup lookup;
        try {
            lookup = store.findCommit(request.commitId(), object.mainKey());
        } catch (StoreException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot decide commit " + request.commitId(), e);
            return Optional.of(new ServerError(STATE_UNKNOWN, "Commit " + request.commitId()
                    + " may or may not have been applied: " + e.getMessage()));
        }
        if (lookup == CommitLookup.COMMITTED) {
            return Optional.of(committed(object, next, json, regulatedChange, request.commitId()));
        }
        if (backOff(attempt, started)) {
            return Optional.empty();
        }
        return Optional.of(new ServerError(SERVICE_FAILURE, "Commit " + request.commitId()
                + " was not applied and the retry bound is spent"));
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
                              Metadata.Journal journal, boolean regulatedChange) {
        Instant at = clock.instant();
        Caller caller = request.caller();
        JournalEntry entry = new JournalEntry(caller.principal().id(), caller.endUser().map(Principal::id).orElse(null),
                caller.engine(), journal.requirementsJson(), journal.updatesJson());
        List<OutboxEvent> outbox = events.of(new CommitEvents.Commit(request.commitId(), at, caller, object, before,
                next, journal.requirementsJson(), journal.updatesJson(), regulatedChange));
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

package nuna.catalog.core.commit;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.AddedFiles;
import nuna.catalog.iceberg.Facts;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.JournalStore;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerSwap;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;

/**
 * The reconcile-from-storage step of the DR runbook (D37). After a failover to a database that is behind
 * its object storage, a table's files run past the version its pointer names. Each file names the file it
 * replaced as the newest entry of its metadata log, so the files form chains from the pointer's file.
 *
 * <p>A file is <em>proven</em> committed when a later file builds on it: every commit attempt starts from
 * the pointer the primary held, so only a committed file is ever a predecessor. Two attempts at the same
 * version leave two files there, and only one can be proven. The pointer moves only through proven files,
 * one version per swap, each journaled and published as a commit. The newest files have no successor yet;
 * they are reported as tips, never taken, unless the operator names one in {@code accept} (an attempt that
 * lost its swap left its file behind, and its client may have deleted the manifests that file lists), or
 * the audit archive records its swap (backlog 10): a swap's commit record goes to the outbox in the swap's
 * own transaction (D23), so the archive holds one only for a swap that committed on the primary, the same
 * standard a successor proves. Only a table under a DR freeze moves, so no commit races the walk.
 *
 * <p>Storage replicates to DR asynchronously and not in write order, so a proven file's manifests and data may not
 * have arrived. The walk takes a step only when storage holds every file it adds to the version before it, data
 * included, checked as it plans, and stops at the first step that lacks one (D37, amended 2026-11-20: "Walk with a
 * file check"; item 83, folded into item 28). It counts a version's files as the maintenance worker's verifier does
 * ({@link AddedFiles}). The verifier keeps each table's and view's DR watermark, the newest version whose every file
 * it found in the DR store; since the watermark is written after the version it names, it is never above the pointer.
 * A table whose pointer is above its watermark is checked from the watermark (D37, amended again 2026-11-20: "Check
 * from the watermark"): every version between them, the pointer's included, must be whole in storage too, or the walk
 * takes nothing for it and reports it for the {@linkplain #rewind rewind}, which moves it back to its watermark and
 * gives up the commits in between. A table with no watermark is walked from its pointer.
 */
public final class Reconcile {

    /** Why a step was taken: a later file builds on it, the archive records its swap, or the operator accepted it. */
    public enum Proof { SUCCESSOR, ARCHIVED, ACCEPTED }

    public record Step(String location, long version, String sha256, Proof proof) {
    }

    /**
     * A swap of a table's main pointer as the audit archive records it (D23): the file it replaced, and the file it
     * wrote with that file's pointer version and SHA-256.
     */
    public record ArchivedCommit(String before, String after, long version, String sha256) {
    }

    /**
     * @param from         the pointer the walk starts from
     * @param steps        the files the pointer moves through, oldest first
     * @param tips         files that follow the last step but that no later file, archive record or accept proves
     * @param problem      why the walk stopped where it did, when it was not for want of proof
     * @param contradicted files named in {@code accept} that the archive contradicts: it records another file, or
     *                     this one with other content, as the swap from the file they follow
     */
    public record Plan(CatalogObject table, Pointer from, List<Step> steps, List<String> tips,
                       Optional<String> problem, List<String> contradicted) {

        /** A plan the archive contradicts nothing in. */
        Plan(CatalogObject table, Pointer from, List<Step> steps, List<String> tips, Optional<String> problem) {
            this(table, from, steps, tips, problem, List.of());
        }
    }

    /** How many steps of a plan were swapped, and what stopped the rest. */
    public record Applied(Plan plan, int stepsApplied, Optional<String> problem) {
    }

    /** A commit the rewind gives up, as the journal records it, for business reconciliation. */
    public record Lost(long version, String metadataLocation, UUID commitId, Instant committedAt, String principal) {
    }

    /**
     * A rewind of a table's or view's main pointer to its DR watermark.
     *
     * @param from    the pointer it starts from
     * @param to      the watermark it moves back to; none when the object has none
     * @param sha256  of the watermark's metadata file as storage holds it; empty when there is a problem
     * @param lost    the journaled commits above the watermark, oldest first, which the rewind gives up
     * @param problem why it cannot rewind
     */
    public record Rewind(CatalogObject object, Pointer from, Optional<DrWatermark> to, String sha256, List<Lost> lost,
                         Optional<String> problem) {
    }

    /** The pointer a rewind swapped in, or why it did not. */
    public record Rewound(Rewind rewind, Optional<Pointer> to, Optional<String> problem) {
    }

    private static final int JOURNAL_PAGE = 500;

    private record Node(String location, long version, String sha256, Metadata.TableLink link) {
    }

    private final CatalogStore store;
    private final TableFiles files;
    private final CommitSettings settings;
    private final Clock clock;
    private final CommitEngine.Sleeper sleeper;

    /**
     * {@code settings}, {@code clock} and {@code sleeper} are the commit engine's, so a step whose outcome was
     * unknown is decided within the same bounds as a commit.
     */
    public Reconcile(CatalogStore store, TableFiles files, CommitSettings settings, Clock clock,
                     CommitEngine.Sleeper sleeper) {
        this.store = store;
        this.files = files;
        this.settings = settings;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Walks {@code table}'s files forward from its pointer; {@code accept} names tips the operator takes. */
    public Plan plan(CatalogObject table, Set<String> accept) {
        return plan(table, accept, Optional.empty());
    }

    /**
     * As {@link #plan(CatalogObject, Set)}, with {@code archived}, when present, every swap of the table the audit
     * archive records. Where no later file proves which file follows the one the walk has reached, a swap the archive
     * records from it decides: the file it wrote is taken when storage holds it at the recorded version and SHA-256.
     * The walk stops instead where the archive records two swaps from one file, or one whose file storage does not
     * hold as recorded, or where {@code accept} names a file other than the one it records. Where the archive records
     * none, {@code accept} decides, as without the archive.
     *
     * <p>Each step is then checked against storage, oldest first: every file it adds to the version before it (the
     * pointer's, for the first) must be there, its manifest list, the manifests and the data and delete files its
     * snapshots add, and its statistics files (D37, amended 2026-11-20). The plan keeps the steps before the first that
     * lacks one, takes no tip, and says which file is missing; a file that cannot be read stops it the same way.
     *
     * <p>When the table's pointer is above its DR watermark, the versions between them are checked first, the same way
     * from the watermark's ({@link #sinceWatermark}); if one is not whole, the plan takes nothing and says why.
     */
    public Plan plan(CatalogObject table, Set<String> accept, Optional<List<ArchivedCommit>> archived) {
        Pointer from = store.readPointer(table.mainKey())
                .orElseThrow(() -> new IllegalStateException("table " + table.id() + " has no pointer"));
        Optional<String> broken = sinceWatermark(table, from);
        if (broken.isPresent()) {
            return stopped(table, from, List.of(), broken.get());
        }
        Plan walked = walk(table, from, accept, archived);
        if (walked.steps().isEmpty()) {
            return walked;
        }
        AddedFiles.TableVersion base = AddedFiles.parse(walked.from().metadataLocation(),
                files.read(walked.from().metadataLocation(), walked.from().sha256()).json());
        for (int taken = 0; taken < walked.steps().size(); taken++) {
            Step step = walked.steps().get(taken);
            AddedFiles.TableVersion version;
            Optional<String> missing;
            try {
                version = AddedFiles.parse(step.location(), files.read(step.location(), step.sha256()).json());
                missing = AddedFiles.firstMissing(base, version, files::bytes, files::exists);
            } catch (RuntimeException e) {
                return cut(walked, taken, "the files version " + step.version() + " adds (" + step.location()
                        + ") cannot be checked: " + e.getMessage());
            }
            if (missing.isPresent()) {
                return cut(walked, taken, "storage does not hold " + missing.get() + ", which version "
                        + step.version() + " adds (" + step.location() + "): its files have not all reached this"
                        + " store, so the walk stops before it");
            }
            base = version;
        }
        return walked;
    }

    /** The plan's first {@code taken} steps and no tip, stopped by {@code problem}. */
    private static Plan cut(Plan walked, int taken, String problem) {
        return new Plan(walked.table(), walked.from(), List.copyOf(walked.steps().subList(0, taken)), List.of(),
                Optional.of(problem), walked.contradicted());
    }

    /**
     * Why the walk takes nothing for {@code table}, whose pointer is {@code from} (D37, amended again 2026-11-20: "Check
     * from the watermark"): its pointer is above its DR watermark, and a version between them, the pointer's included,
     * lacks a file it adds to the version before it (the watermark's, for the first), its own metadata file among them,
     * or cannot be checked. The versions are the ones the DR database's journal records above the watermark, the commits
     * a {@linkplain #rewind rewind} would give up, and they must run one by one to the pointer. Empty when the table has
     * no watermark, its pointer is at it, or every version between them is whole in storage.
     */
    private Optional<String> sinceWatermark(CatalogObject table, Pointer from) {
        Optional<DrWatermark> kept = store.readDrWatermark(table.id());
        if (kept.isEmpty() || kept.get().version() >= from.version()) {
            return Optional.empty();
        }
        DrWatermark watermark = kept.get();
        String between = "between the DR watermark, version " + watermark.version() + ", and the pointer, version "
                + from.version();
        List<Lost> versions = lost(table, watermark.version());
        if (!versions.stream().map(Lost::version).toList()
                .equals(LongStream.rangeClosed(watermark.version() + 1, from.version()).boxed().toList())) {
            return Optional.of("the journal does not hold every version " + between + ", one by one, so they cannot be"
                    + " checked and the walk takes nothing for the table");
        }
        String rewind = ": not every version " + between + " is whole in this store, so the walk takes nothing for the"
                + " table; rewind it to its watermark";
        String at = watermark.metadataLocation();
        try {
            if (!files.exists(at)) {
                return Optional.of("storage does not hold the DR watermark's file " + at + ", so the versions above it"
                        + " cannot be checked and the walk takes nothing for the table");
            }
            AddedFiles.TableVersion base = AddedFiles.parse(at, files.read(at, "").json());
            for (Lost version : versions) {
                at = version.metadataLocation();
                if (!files.exists(at)) {
                    return Optional.of("storage does not hold " + (version.version() == from.version()
                            ? "the pointer's file " : "the metadata file of version " + version.version() + ", ")
                            + at + rewind);
                }
                AddedFiles.TableVersion metadata = AddedFiles.parse(at, files.read(at, "").json());
                Optional<String> missing = AddedFiles.firstMissing(base, metadata, files::bytes, files::exists);
                if (missing.isPresent()) {
                    return Optional.of("storage does not hold " + missing.get() + ", which version " + version.version()
                            + " adds (" + at + ")" + rewind);
                }
                base = metadata;
            }
        } catch (RuntimeException e) {
            return Optional.of("the files of " + at + ", " + between + ", cannot be checked, so the walk takes nothing"
                    + " for the table: " + e.getMessage());
        }
        return Optional.empty();
    }

    private Plan walk(CatalogObject table, Pointer from, Set<String> accept, Optional<List<ArchivedCommit>> archived) {
        List<String> listed = files.metadataFiles(table.locationRoot());
        if (!listed.contains(from.metadataLocation())) {
            return stopped(table, from, List.of(), "storage does not hold the pointer's file "
                    + from.metadataLocation() + "; storage is behind the database");
        }
        Map<String, List<Node>> children = new HashMap<>();
        Set<String> parents = new HashSet<>();
        for (String location : listed) {
            long version = version(location);
            if (version <= from.version()) {
                continue;
            }
            TableFiles.Read read = files.read(location, "");
            Metadata.TableLink link;
            try {
                link = Metadata.tableLink(read.json(), location);
            } catch (RuntimeException e) {
                return stopped(table, from, List.of(), "file " + location + " is not table metadata: " + e.getMessage());
            }
            if (!link.uuid().equals(table.id().toString())) {
                return stopped(table, from, List.of(), "file " + location + " belongs to table " + link.uuid());
            }
            Node node = new Node(location, version, read.sha256(), link);
            link.previous().ifPresent(previous -> {
                children.computeIfAbsent(previous, p -> new ArrayList<>()).add(node);
                parents.add(previous);
            });
        }
        List<Step> steps = new ArrayList<>();
        String current = from.metadataLocation();
        long version = from.version();
        while (true) {
            List<Node> next = children.getOrDefault(current, List.of()).stream()
                    .sorted(Comparator.comparing(Node::location)).toList();
            if (next.isEmpty()) {
                return new Plan(table, from, steps, List.of(), Optional.empty());
            }
            List<Node> proven = next.stream().filter(n -> parents.contains(n.location())).toList();
            if (proven.size() > 1) {
                return stopped(table, from, steps, "later files build on " + proven.size() + " files that follow "
                        + current + ": " + proven.stream().map(Node::location).toList());
            }
            Node chosen;
            Proof proof;
            if (proven.size() == 1) {
                chosen = proven.getFirst();
                proof = Proof.SUCCESSOR;
            } else {
                List<Node> accepted = next.stream().filter(n -> accept.contains(n.location())).toList();
                String at = current;
                List<ArchivedCommit> recorded = archived.orElse(List.of()).stream()
                        .filter(commit -> commit.before().equals(at)).distinct().toList();
                List<String> tips = next.stream().map(Node::location).toList();
                if (recorded.size() > 1) {
                    return new Plan(table, from, steps, tips, Optional.of("the audit archive records " + recorded.size()
                            + " swaps from " + current + ": " + recorded.stream().map(ArchivedCommit::after).toList()));
                } else if (recorded.size() == 1) {
                    ArchivedCommit commit = recorded.getFirst();
                    Optional<Node> match = next.stream().filter(n -> n.location().equals(commit.after())
                            && n.version() == commit.version() && n.sha256().equals(commit.sha256())).findFirst();
                    List<String> contradicted = accepted.stream().map(Node::location)
                            .filter(location -> match.isEmpty() || !location.equals(match.get().location())).toList();
                    if (match.isEmpty()) {
                        return new Plan(table, from, steps, tips, Optional.of("the audit archive records the swap from "
                                + current + " to " + commit.after() + " (version " + commit.version() + ", SHA-256 "
                                + commit.sha256() + "), but storage holds no such file after it"), contradicted);
                    }
                    if (!contradicted.isEmpty()) {
                        return new Plan(table, from, steps, tips, Optional.of("accept names " + contradicted
                                + ", but the audit archive records the swap from " + current + " to " + commit.after()),
                                contradicted);
                    }
                    chosen = match.get();
                    proof = Proof.ARCHIVED;
                } else {
                    if (accepted.size() != 1) {
                        return new Plan(table, from, steps, tips, accepted.isEmpty() ? Optional.empty()
                                : Optional.of("accept names more than one file that follows " + current));
                    }
                    chosen = accepted.getFirst();
                    proof = Proof.ACCEPTED;
                }
            }
            if (chosen.version() != version + 1) {
                return stopped(table, from, steps, "file " + chosen.location() + " follows " + current
                        + " but is version " + chosen.version() + ", not " + (version + 1));
            }
            steps.add(new Step(chosen.location(), chosen.version(), chosen.sha256(), proof));
            current = chosen.location();
            version = chosen.version();
        }
    }

    /**
     * Swaps the pointer through the plan's steps, one version per commit, journaled for {@code caller} and
     * published through {@code events}. It stops at the first step whose file changed or whose swap is not
     * against the pointer the plan started from. A step whose outcome was unknown is decided as the commit
     * engine decides a commit (D9): a store that cannot be reached is asked again within the retry bounds
     * (P10), counted from the step's start, and its failure is thrown once they are spent.
     */
    public Applied apply(Plan plan, Caller caller, CommitEvents events) {
        CatalogObject table = plan.table();
        if (!frozen(table)) {
            return new Applied(plan, 0, Optional.of("not frozen for DR reconcile; place a DR freeze on it or above it"));
        }
        Pointer before = plan.from();
        int applied = 0;
        for (Step step : plan.steps()) {
            TableFiles.Read read = files.read(step.location(), step.sha256());
            if (!read.intact()) {
                return new Applied(plan, applied, Optional.of("file " + step.location() + " changed since it was planned"));
            }
            Pointer next = new Pointer(step.location(), before.version() + 1, step.sha256(),
                    Projection.of(Metadata.tableLink(read.json(), step.location()).facts()));
            String updates = "[{\"action\":\"dr-fast-forward\",\"metadata-location\":" + quote(step.location())
                    + ",\"proof\":\"" + step.proof().name().toLowerCase(java.util.Locale.ROOT) + "\"}]";
            Optional<String> refused = swap(table, before, next, updates, caller, events, "reconcile step");
            if (refused.isPresent()) {
                return new Applied(plan, applied, refused);
            }
            before = next;
            applied++;
        }
        return new Applied(plan, applied, Optional.empty());
    }

    /**
     * Plans moving a table's or view's main pointer back to its DR watermark (D37, amended 2026-11-20: views too):
     * the newest version whose every file the verifier found in the DR store. It lists the journaled commits above the
     * watermark, which the rewind gives up. There is nothing to rewind when the object has no watermark, its pointer is
     * at it, or its pointer already holds the watermark's metadata (a rewind the verifier has not passed yet), and it
     * cannot rewind when storage does not hold the watermark's metadata file as metadata of this object.
     */
    public Rewind planRewind(CatalogObject object) {
        String kind = kind(object);
        Pointer from = store.readPointer(object.mainKey())
                .orElseThrow(() -> new IllegalStateException(kind + " " + object.id() + " has no pointer"));
        Optional<DrWatermark> watermark = store.readDrWatermark(object.id());
        if (watermark.isEmpty()) {
            return new Rewind(object, from, watermark, "", List.of(), Optional.of("the " + kind + " has no DR watermark:"
                    + " the verifier has found none of its versions whole in the DR store, so there is none to rewind"
                    + " to"));
        }
        DrWatermark to = watermark.get();
        if (to.version() >= from.version()) {
            return new Rewind(object, from, watermark, "", List.of(), Optional.of("the pointer is at the DR watermark,"
                    + " version " + to.version() + "; there is nothing to rewind"));
        }
        if (!files.metadataFiles(object.locationRoot()).contains(to.metadataLocation())) {
            return new Rewind(object, from, watermark, "", lost(object, to.version()), Optional.of("storage does not"
                    + " hold the DR watermark's file " + to.metadataLocation() + "; this is not the store the verifier"
                    + " checked"));
        }
        TableFiles.Read read = files.read(to.metadataLocation(), "");
        if (read.sha256().equals(from.sha256())) {
            return new Rewind(object, from, watermark, "", List.of(), Optional.of("the pointer, version "
                    + from.version() + ", already holds the DR watermark's metadata, version " + to.version()
                    + "; there is nothing to rewind until the verifier passes it"));
        }
        List<Lost> lost = lost(object, to.version());
        try {
            String uuid = facts(object, read.json(), to.metadataLocation()).uuid();
            if (!uuid.equals(object.id().toString())) {
                return new Rewind(object, from, watermark, "", lost, Optional.of("file " + to.metadataLocation()
                        + " belongs to " + kind + " " + uuid));
            }
        } catch (RuntimeException e) {
            return new Rewind(object, from, watermark, "", lost, Optional.of("file " + to.metadataLocation()
                    + " is not " + kind + " metadata: " + e.getMessage()));
        }
        return new Rewind(object, from, watermark, read.sha256(), lost, Optional.empty());
    }

    /**
     * Moves the pointer back to the watermark in one journaled commit, published through {@code events} as the
     * fast-forward's steps are: it writes the watermark's metadata again as the next version, since a pointer only
     * moves forward and the walk stops where a file's version is not the pointer's. Only a table or view under a DR
     * freeze is rewound. It does not move the watermark, which the verifier keeps. A swap whose outcome was unknown is
     * decided as {@link #apply} decides a step's.
     */
    public Rewound rewind(Rewind plan, Caller caller, CommitEvents events) {
        if (plan.problem().isPresent()) {
            return new Rewound(plan, Optional.empty(), plan.problem());
        }
        CatalogObject object = plan.object();
        if (!frozen(object)) {
            return new Rewound(plan, Optional.empty(), Optional.of("not frozen for DR reconcile; place a DR freeze on"
                    + " it or above it"));
        }
        DrWatermark to = plan.to().orElseThrow();
        TableFiles.Read read = files.read(to.metadataLocation(), plan.sha256());
        if (!read.intact()) {
            return new Rewound(plan, Optional.empty(), Optional.of("file " + to.metadataLocation()
                    + " changed since it was planned"));
        }
        Pointer before = plan.from();
        TableFiles.Written written = files.write(object.locationRoot(), before.version() + 1, read.json());
        Pointer next = new Pointer(written.location(), before.version() + 1, written.sha256(),
                Projection.of(facts(object, read.json(), written.location())));
        String updates = "[{\"action\":\"dr-rewind\",\"metadata-location\":" + quote(written.location())
                + ",\"watermark-version\":" + to.version() + ",\"watermark-location\":" + quote(to.metadataLocation())
                + ",\"versions-given-up\":" + (before.version() - to.version()) + "}]";
        Optional<String> refused = swap(object, before, next, updates, caller, events, "rewind");
        return refused.isPresent() ? new Rewound(plan, Optional.empty(), refused)
                : new Rewound(plan, Optional.of(next), Optional.empty());
    }

    /** A table's or a view's metadata facts, read as its kind's metadata; throws on a file that is not. */
    private static Facts facts(CatalogObject object, String json, String location) {
        return object.kind() == CatalogObject.Kind.VIEW ? Metadata.viewFacts(json, location)
                : Metadata.tableLink(json, location).facts();
    }

    private static String kind(CatalogObject object) {
        return object.kind().name().toLowerCase(Locale.ROOT);
    }

    private boolean frozen(CatalogObject table) {
        return GovernanceChain.of(store, table).nearest(GovernanceAttribute.Kind.DR_FREEZE).isPresent();
    }

    /** The journaled commits of the object's main pointer above {@code version}, oldest first. */
    private List<Lost> lost(CatalogObject table, long version) {
        List<Lost> lost = new ArrayList<>();
        PageRequest request = JournalStore.afterVersion(version, JOURNAL_PAGE);
        while (true) {
            Page<JournalRecord> page = store.readJournal(table.mainKey(), JournalStore.EARLIEST, JournalStore.LATEST,
                    request);
            page.items().forEach(record -> lost.add(new Lost(record.newVersion(), record.newLocation(),
                    record.commitId(), record.committedAt(), record.entry().principal())));
            if (page.nextToken().isEmpty()) {
                return lost;
            }
            request = new PageRequest(page.nextToken(), JOURNAL_PAGE);
        }
    }

    /**
     * Swaps the table's main pointer from {@code before} to {@code next} in one commit, journaled for {@code caller}
     * with {@code updates} and published through {@code events}; empty when it committed, else why not. An unknown
     * outcome is decided as the commit engine decides a commit (D9).
     */
    private Optional<String> swap(CatalogObject table, Pointer before, Pointer next, String updates, Caller caller,
                                  CommitEvents events, String what) {
        UUID commitId = UUID.randomUUID();
        Instant at = clock.instant();
        JournalEntry entry = new JournalEntry(caller.principal().id(),
                caller.endUser().map(Principal::id).orElse(null), caller.engine(), "[]", updates);
        List<OutboxEvent> outbox = events.of(new CommitEvents.Commit(commitId, at, caller, table,
                Optional.of(before), next, "[]", updates, false));
        CommitBatch batch = new CommitBatch(commitId, at,
                List.of(new PointerSwap(table.mainKey(), Optional.of(before), next, entry)), outbox, Optional.empty());
        boolean committed;
        try {
            CommitResult result = store.commit(batch);
            if (result instanceof CommitResult.Inactive) {
                return Optional.of("the table was dropped or unregistered");
            }
            committed = result instanceof CommitResult.Committed;
        } catch (CommitOutcomeUnknownException e) {
            committed = CommitEngine.decide(settings, clock, sleeper, () -> store.findCommit(commitId,
                    table.mainKey()), at, what + " " + commitId) == CommitLookup.COMMITTED;
        }
        return committed ? Optional.empty() : Optional.of("the pointer is no longer at version " + before.version()
                + "; plan again");
    }

    private static Plan stopped(CatalogObject table, Pointer from, List<Step> steps, String problem) {
        return new Plan(table, from, steps, List.of(), Optional.of(problem));
    }

    /** The pointer version a file was written for: its name's leading number, or -1 for a name not ours. */
    static long version(String location) {
        String name = location.substring(location.lastIndexOf('/') + 1);
        int dash = name.indexOf('-');
        if (dash <= 0) {
            return -1;
        }
        try {
            return Long.parseLong(name.substring(0, dash));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}

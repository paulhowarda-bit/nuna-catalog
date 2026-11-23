package nuna.catalog.core.commit;

import nuna.catalog.core.commit.CommitReply.Failure;
import nuna.catalog.core.commit.CommitReply.ServerError;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Facts;
import nuna.catalog.iceberg.Facts.TableFacts;
import nuna.catalog.iceberg.MergeMetadata;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.JournalStore;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerHold;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefLock;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.WriteResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Merges a branch into main (D33, backlog 107): every table and view the branch changed moves onto main in one bounded
 * transaction that also ends the branch (item 26's choices 7 to 10; item 103), or nothing moves.
 *
 * <p>A table or view main has not committed to since the cut moves as the branch has it. A table main changed only by
 * its own maintenance, compaction and snapshot expiry, moves too, rebased over it (Paul, 2026-11-22, "maintenance
 * doesn't count"; {@link MergeMetadata#beyondMaintenance}): main takes the branch's version, whose snapshots hold every
 * row main's maintenance kept, since a {@code replace} snapshot holds no rows its parent lacks and the cut's files stay
 * while a ref names them (choice 14); what main's expiry removed stays removed, and the next night compacts again.
 * Anything else main did since the cut (data, a definition change, a drop, a view's replace) is a conflict, and any
 * conflict moves nothing.
 *
 * <p>Each table or view moved is judged as a commit on main of the whole change from main's metadata to the branch's,
 * since a merge carries no list of updates (D11, D24, D37; security finding 3): every rule of the catalog's profile, a
 * DR freeze included, which binds the merge though not a commit on the branch (Paul, 2026-11-22). The snapshots main's
 * maintenance added, which the merge drops, are left out of that change, except under a legal hold, which keeps every
 * snapshot main has. What the change needs of the caller, {@code write} for data alone or {@code modify} for a
 * definition change ({@link MergeMetadata#definitionChange}), the REST layer asks of the authorizer.
 *
 * <p>Main takes its own copy of the branch's metadata ({@link MergeMetadata#merged}), written unmarked at main's next
 * version with main's current file as its predecessor, never the branch's marked file. A DR reconcile after a failover
 * so walks from main's chain into a merged version as into any commit on main, proving it by a later file or by the
 * archive's record of the merge's commit on main, and never follows a branch's chain (backlog 107's choice).
 */
public final class Merge {

    /** Builds a catalog's merge: its storage profile's files and rules (D11, D28), and {@code events} for its commits. */
    @FunctionalInterface
    public interface Factory {
        Merge of(CatalogRecord catalog, CommitEvents events);
    }

    /** How many of main's journaled commits since the cut a plan reads to say who made them. */
    static final int SINCE_READ = 100;
    private static final int WALK_PAGE = 1000;
    private static final String COMMIT_FAILED = "CommitFailedException";
    private static final String STATE_UNKNOWN = "CommitStateUnknownException";

    /** Main's commits to a table or view since the cut: how many, and who made the first {@link #SINCE_READ}. */
    public record Since(long commits, List<String> by) {
        public Since {
            by = List.copyOf(by);
        }
    }

    /**
     * One table or view the branch changed, as a merge plans it.
     *
     * @param entry                 the cut's record of it: its id, kind and name as the branch knows it
     * @param namespace             its namespace's levels as the cut names them
     * @param object                main's record of it; empty once purged
     * @param cut                   the pointer the cut took
     * @param branch                the branch's own pointer
     * @param main                  main's pointer; empty when main has none
     * @param since                 main's commits since the cut
     * @param conflict              why it cannot move; empty when it moves
     * @param rebased               it moves over main's own maintenance since the cut
     * @param definition            what the branch changed beyond data, which needs {@code modify} on main; empty for
     *                              data alone, which {@code write} allows. A view's change is always one.
     * @param verdict               the refusal of main's rules on its change; empty when they pass, and for a conflict
     * @param regulatedSchemaChange it moves the schema or partition spec of a regulated table (D11a)
     * @param json                  the metadata main takes, its own copy of the branch's; empty for a conflict
     */
    public record Table(RefEntry entry, List<String> namespace, Optional<CatalogObject> object, Pointer cut,
                        Pointer branch, Optional<Pointer> main, Since since, Optional<String> conflict,
                        boolean rebased, Optional<String> definition, Optional<Failure> verdict,
                        boolean regulatedSchemaChange, Optional<String> json) {

        public Table {
            namespace = List.copyOf(namespace);
        }

        public boolean moves() {
            return conflict.isEmpty();
        }
    }

    /**
     * What a merge of {@code ref} would do now.
     *
     * @param changed   how many tables and views the branch changed
     * @param maxTables the most one merge moves ({@code nuna.refs.max-merge-tables}, item 26's choice 9)
     * @param tables    each the branch changed, in the cut's order; none when it changed more than the cap allows,
     *                  which is not planned further
     */
    public record Plan(Ref ref, long changed, int maxTables, List<Table> tables) {

        public Plan {
            tables = List.copyOf(tables);
        }

        public boolean overCap() {
            return changed > maxTables;
        }

        public List<Table> conflicts() {
            return tables.stream().filter(table -> !table.moves()).toList();
        }

        /** The first table or view main's rules refuse to move. */
        public Optional<Table> refused() {
            return tables.stream().filter(table -> table.verdict().isPresent()).findFirst();
        }
    }

    /** A table or view a merge moved: main's pointer before and after. */
    public record Moved(Table table, Pointer before, Pointer after) {
    }

    /** What a merge did. */
    public sealed interface Outcome {

        /** Every table and view moved onto main and the branch ended; nothing moved for a branch that changed nothing. */
        record Merged(Plan plan, List<Moved> moved) implements Outcome {
            public Merged {
                moved = List.copyOf(moved);
            }
        }

        /**
         * Nothing moved, as the plan says why: the branch changed more than one merge moves, a table or view conflicts,
         * or main's rules refused one.
         */
        record Refused(Plan plan) implements Outcome {
        }

        /** The branch is gone: merged, deleted or past its expiry. */
        record Gone() implements Outcome {
        }

        /**
         * Nothing moved, or it is not known whether it did: a 409 once P10's bound is spent on swaps lost to commits on
         * main or the branch, or a {@link ServerError}.
         */
        record Failed(CommitReply reply) implements Outcome {
        }
    }

    private final CommitEngine engine;
    private final CatalogStore store;
    private final TableFiles files;
    private final CommitEvents events;
    private final CommitSettings settings;
    private final Clock clock;
    private final CommitEngine.Sleeper sleeper;

    Merge(CommitEngine engine, CatalogStore store, TableFiles files, CommitEvents events, CommitSettings settings,
          Clock clock, CommitEngine.Sleeper sleeper) {
        this.engine = engine;
        this.store = store;
        this.files = files;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * What merging {@code ref} would do now, writing nothing: the dry run, and each attempt of {@link #merge}. Each
     * table or view the branch has its own pointer for is read on the branch, on main and as the cut took it; one main
     * changed beyond its maintenance, or no longer has active, conflicts; any other is judged by main's rules.
     */
    public Plan plan(Ref ref, int maxTables) {
        long changed = store.countOwnPointers(ref.id());
        if (changed > maxTables) {
            return new Plan(ref, changed, maxTables, List.of());
        }
        List<Table> tables = new ArrayList<>();
        Deque<Map.Entry<UUID, List<String>>> levels = new ArrayDeque<>();
        levels.add(Map.entry(ref.catalogId(), List.of()));
        while (!levels.isEmpty()) {
            Map.Entry<UUID, List<String>> parent = levels.removeFirst();
            for (RefEntry namespace : walk(page -> store.listRefNamespaces(ref.id(), parent.getKey(), page))) {
                List<String> path = new ArrayList<>(parent.getValue());
                path.add(namespace.name().display());
                levels.add(Map.entry(namespace.id(), List.copyOf(path)));
                for (CatalogObject.Kind kind : CatalogObject.Kind.values()) {
                    for (RefEntry entry : walk(page -> store.listRefObjects(ref.id(), namespace.id(), kind, page))) {
                        Optional<Pointer> own = store.readPointer(ref.key(entry.id()));
                        own.ifPresent(pointer -> tables.add(table(entry, path, pointer)));
                    }
                }
            }
        }
        return new Plan(ref, tables.size(), maxTables, tables);
    }

    /**
     * Merges {@code ref}. While its plan conflicts with nothing and is within the cap, asks {@code rights} whether the
     * caller may move what it would (it refuses by throwing, as the REST layer's authorization does); while main's
     * rules pass every table, writes main's copies and, in one batch, swaps main's pointers, holds every own pointer
     * of the branch as read and ends the branch. {@code records} gives what that batch writes besides: the merge's own
     * audit record and idempotency answer, under an id that is the batch's commit id and the same for every attempt.
     * A swap lost to a commit on main or on the branch plans again, within P10's bound (D9). A branch that changed
     * nothing is ended alone, with that change.
     */
    public Outcome merge(Ref ref, int maxTables, Caller caller, Consumer<Plan> rights,
                         Function<Outcome.Merged, Change> records) {
        Instant started = clock.instant();
        for (int attempt = 1; ; attempt++) {
            Plan plan = plan(ref, maxTables);
            if (plan.overCap() || !plan.conflicts().isEmpty()) {
                return new Outcome.Refused(plan);
            }
            rights.accept(plan);
            if (plan.refused().isPresent()) {
                return new Outcome.Refused(plan);
            }
            if (plan.tables().isEmpty()) {
                return end(ref, new Outcome.Merged(plan, List.of()), records);
            }
            // Files are written only once every table has passed, so a refused merge leaves none behind.
            Outcome.Merged merged = new Outcome.Merged(plan, plan.tables().stream().map(this::write).toList());
            Change change = records.apply(merged);
            try {
                switch (store.commit(batch(ref, merged, caller, change))) {
                    case CommitResult.Committed done -> {
                        return landed(merged, change.id());
                    }
                    case CommitResult.Inactive inactive when inactive.subject() == CommitResult.Subject.REF -> {
                        return new Outcome.Gone();
                    }
                    // A commit on main or the branch, or a drop on main, since the plan: plan again.
                    case CommitResult.Conflict conflict -> {
                    }
                    case CommitResult.Inactive inactive -> {
                    }
                }
            } catch (CommitOutcomeUnknownException e) {
                CommitLookup lookup;
                try {
                    lookup = CommitEngine.decide(settings, clock, sleeper, () -> store.findCommit(change.id(),
                            mainKey(merged.moved().getFirst())), started, "merge " + change.id());
                } catch (StoreException unknown) {
                    return new Outcome.Failed(new ServerError(STATE_UNKNOWN, "Merge " + change.id()
                            + " may or may not have been applied: " + unknown.getMessage()));
                }
                if (lookup == CommitLookup.COMMITTED) {
                    return landed(merged, change.id());
                }
            }
            if (!CommitEngine.backOff(settings, clock, sleeper, attempt, started)) {
                return new Outcome.Failed(new Failure(409, COMMIT_FAILED, "Merge of branch "
                        + ref.name().display() + " lost to concurrent commits " + attempt + " times; its tables"
                        + " changed too often to merge them"));
            }
        }
    }

    // ---- planning ----

    private Table table(RefEntry entry, List<String> namespace, Pointer branch) {
        RefEntry.At at = entry.pointer().orElseThrow();
        Pointer cut = new Pointer(at.metadataLocation(), at.version(), at.sha256(), new byte[0]);
        Optional<CatalogObject> object = store.readObject(entry.id());
        Optional<Pointer> main = object.flatMap(o -> store.readPointer(o.mainKey()));
        Since since = since(entry.id(), cut, main);
        if (object.isEmpty() || object.get().state() != CatalogObject.State.ACTIVE) {
            String gone = object.map(o -> o.state().name().toLowerCase(Locale.ROOT)
                    + o.droppedAt().map(when -> " at " + when).orElse("")).orElse("purged");
            return conflict(entry, namespace, object, cut, branch, main, since, "main has " + gone + " it");
        }
        CatalogObject o = object.get();
        Pointer onMain = main.orElseThrow(() -> new IllegalStateException("active object " + o.id()
                + " has no pointer"));
        GovernanceChain governance = GovernanceChain.of(store, o);
        boolean moved = onMain.version() != cut.version();
        Optional<String> branchJson = engine.load(o, branch, governance);
        Optional<String> mainJson = engine.load(o, onMain, governance);
        Optional<String> cutJson = moved && o.kind() == CatalogObject.Kind.TABLE ? engine.load(o, cut, governance)
                : mainJson;
        Optional<Pointer> unread = branchJson.isEmpty() ? Optional.of(branch) : mainJson.isEmpty() ? Optional.of(onMain)
                : cutJson.isEmpty() ? Optional.of(cut) : Optional.empty();
        if (unread.isPresent()) {
            // A regulated table's file that no longer matches its hash fails closed, as a commit's does (D26).
            return conflict(entry, namespace, object, cut, branch, main, since, "its metadata file "
                    + unread.get().metadataLocation() + " does not match the hash its pointer recorded");
        }
        if (o.kind() == CatalogObject.Kind.VIEW) {
            if (moved) {
                return conflict(entry, namespace, object, cut, branch, main, since, "main replaced the view since the"
                        + " cut");
            }
            Facts.ViewFacts before = Metadata.viewFacts(mainJson.get(), onMain.metadataLocation());
            Facts.ViewFacts after = Metadata.viewFacts(branchJson.get(), branch.metadataLocation());
            return new Table(entry, namespace, object, cut, branch, main, since, Optional.empty(), false,
                    Optional.of("view version " + before.currentVersionId() + " became " + after.currentVersionId()),
                    engine.judge(o, before, after, governance), false, branchJson);
        }
        Optional<String> beyond = moved ? MergeMetadata.beyondMaintenance(cutJson.get(), mainJson.get())
                : Optional.empty();
        if (beyond.isPresent()) {
            return conflict(entry, namespace, object, cut, branch, main, since, beyond.get());
        }
        TableFacts onMainFacts = Metadata.tableLink(mainJson.get(), onMain.metadataLocation()).facts();
        TableFacts after = Metadata.tableLink(branchJson.get(), branch.metadataLocation()).facts();
        TableFacts before = moved && !governance.held()
                ? rebased(onMainFacts, Metadata.tableLink(cutJson.get(), cut.metadataLocation()).facts())
                : onMainFacts;
        return new Table(entry, namespace, object, cut, branch, main, since, Optional.empty(), moved,
                MergeMetadata.definitionChange(cutJson.get(), branchJson.get()),
                engine.judge(o, before, after, governance),
                CommitEngine.schemaOrSpecChanged(before, after) && governance.regulated(),
                Optional.of(MergeMetadata.merged(cutJson.get(), branchJson.get(), mainJson.get(),
                        onMain.metadataLocation(), clock.millis())));
    }

    /**
     * Main's facts as the merge changes them: without the snapshots main's maintenance added since the cut, which the
     * branch's version does not have and which hold no rows the cut's lack (Paul, 2026-11-22).
     */
    private static TableFacts rebased(TableFacts main, TableFacts cut) {
        Map<Long, Long> kept = new LinkedHashMap<>(main.snapshotTimestamps());
        kept.keySet().retainAll(cut.snapshotTimestamps().keySet());
        return new TableFacts(main.uuid(), main.formatVersion(), main.location(), main.properties(),
                main.currentSchemaId(), main.defaultSpecId(), kept);
    }

    private static Table conflict(RefEntry entry, List<String> namespace, Optional<CatalogObject> object, Pointer cut,
                                  Pointer branch, Optional<Pointer> main, Since since, String why) {
        return new Table(entry, namespace, object, cut, branch, main, since, Optional.of(why), false, Optional.empty(),
                Optional.empty(), false, Optional.empty());
    }

    /** Main's commits since the cut, by the versions its pointer moved, and who made the first of them. */
    private Since since(UUID id, Pointer cut, Optional<Pointer> main) {
        if (main.isEmpty() || main.get().version() <= cut.version()) {
            return new Since(0, List.of());
        }
        Page<JournalRecord> page = store.readJournal(new PointerKey(id, PointerKey.MAIN),
                JournalStore.EARLIEST, JournalStore.LATEST, JournalStore.afterVersion(cut.version(), SINCE_READ));
        return new Since(main.get().version() - cut.version(),
                page.items().stream().map(record -> record.entry().principal()).distinct().toList());
    }

    private static List<RefEntry> walk(Function<PageRequest, Page<RefEntry>> list) {
        List<RefEntry> all = new ArrayList<>();
        PageRequest page = PageRequest.first(WALK_PAGE);
        while (true) {
            Page<RefEntry> listed = list.apply(page);
            all.addAll(listed.items());
            if (listed.nextToken().isEmpty()) {
                return all;
            }
            page = page.after(listed);
        }
    }

    // ---- moving ----

    /** Writes main's copy of a table's or view's metadata, unmarked, at main's next version. */
    private Moved write(Table table) {
        CatalogObject object = table.object().orElseThrow();
        Pointer before = table.main().orElseThrow();
        long version = before.version() + 1;
        String json = table.json().orElseThrow();
        TableFiles.Written file = files.write(object.locationRoot(), version, json);
        Facts facts = object.kind() == CatalogObject.Kind.VIEW ? Metadata.viewFacts(json, file.location())
                : Metadata.tableLink(json, file.location()).facts();
        return new Moved(table, before, new Pointer(file.location(), version, file.sha256(), Projection.of(facts)));
    }

    /**
     * The merge's one batch: main's swaps, each journaled as a {@code merge-branch} update under the merge's id with a
     * {@code Commit} event on main; the branch's own pointers held as read; the branch ended ({@link RefLock.End}).
     */
    private CommitBatch batch(Ref ref, Outcome.Merged merged, Caller caller, Change change) {
        List<PointerSwap> swaps = new ArrayList<>();
        List<OutboxEvent> outbox = new ArrayList<>();
        for (Moved moved : merged.moved()) {
            Table table = moved.table();
            String updates = MergeMetadata.journal(ref.name().display(), ref.id().toString(), table.branch().version(),
                    table.branch().metadataLocation(), table.cut().version(), table.since().commits(),
                    table.definition());
            JournalEntry entry = new JournalEntry(caller.principal().id(),
                    caller.endUser().map(Principal::id).orElse(null), caller.engine(), "[]", updates);
            swaps.add(new PointerSwap(mainKey(moved), Optional.of(moved.before()), moved.after(), entry));
            outbox.addAll(events.of(new CommitEvents.Commit(change.id(), change.at(), caller,
                    table.object().orElseThrow(), Optional.of(moved.before()), moved.after(), "[]", updates,
                    table.regulatedSchemaChange(), CommitEvents.Kind.COMMIT)));
        }
        outbox.addAll(change.outbox());
        List<PointerHold> holds = merged.plan().tables().stream()
                .map(table -> new PointerHold(ref.key(table.entry().id()), table.branch())).toList();
        return new CommitBatch(change.id(), change.at(), swaps, holds, Optional.of(new RefLock.End(ref.id())), outbox,
                change.idempotency());
    }

    private Outcome landed(Outcome.Merged merged, UUID commitId) {
        for (Moved moved : merged.moved()) {
            engine.committed(moved.table().object().orElseThrow(), moved.after(), moved.table().json().orElseThrow(),
                    moved.table().regulatedSchemaChange(), commitId);
        }
        return merged;
    }

    /** A branch that changed nothing ends with its change alone, as a deletion does (item 103's choice). */
    private Outcome end(Ref ref, Outcome.Merged merged, Function<Outcome.Merged, Change> records) {
        WriteResult result;
        try {
            result = store.deleteRef(ref.id(), records.apply(merged));
        } catch (CommitOutcomeUnknownException e) {
            return store.readRef(ref.id()).isEmpty() ? merged : new Outcome.Failed(new ServerError(STATE_UNKNOWN,
                    "Merge of branch " + ref.name().display() + " may or may not have been applied"));
        }
        return result instanceof WriteResult.Missing ? new Outcome.Gone() : merged;
    }

    private static PointerKey mainKey(Moved moved) {
        return moved.table().object().orElseThrow().mainKey();
    }
}

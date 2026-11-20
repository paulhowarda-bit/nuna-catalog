package nuna.catalog.core.commit;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerSwap;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
     */
    public Plan plan(CatalogObject table, Set<String> accept, Optional<List<ArchivedCommit>> archived) {
        Pointer from = store.readPointer(table.mainKey())
                .orElseThrow(() -> new IllegalStateException("table " + table.id() + " has no pointer"));
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
        if (GovernanceChain.of(store, table).nearest(GovernanceAttribute.Kind.DR_FREEZE).isEmpty()) {
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
                    return new Applied(plan, applied, Optional.of("the table was dropped or unregistered"));
                }
                committed = result instanceof CommitResult.Committed;
            } catch (CommitOutcomeUnknownException e) {
                committed = CommitEngine.decide(settings, clock, sleeper, () -> store.findCommit(commitId,
                        table.mainKey()), at, "reconcile step " + commitId) == CommitLookup.COMMITTED;
            }
            if (!committed) {
                return new Applied(plan, applied, Optional.of("the pointer is no longer at version " + before.version()
                        + "; plan again"));
            }
            before = next;
            applied++;
        }
        return new Applied(plan, applied, Optional.empty());
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

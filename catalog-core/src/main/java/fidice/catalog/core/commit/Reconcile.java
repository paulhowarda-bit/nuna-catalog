package fidice.catalog.core.commit;

import fidice.catalog.core.principal.Caller;
import fidice.catalog.core.principal.Principal;
import fidice.catalog.iceberg.Metadata;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitLookup;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.GovernanceAttribute;
import fidice.catalog.store.JournalEntry;
import fidice.catalog.store.OutboxEvent;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerSwap;
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
 * lost its swap left its file behind, and its client may have deleted the manifests that file lists).
 * Only a table under a DR freeze moves, so no commit races the walk.
 */
public final class Reconcile {

    /** Why a step was taken: a later file builds on it, or the operator accepted it. */
    public enum Proof { SUCCESSOR, ACCEPTED }

    public record Step(String location, long version, String sha256, Proof proof) {
    }

    /**
     * @param from    the pointer the walk starts from
     * @param steps   the files the pointer moves through, oldest first
     * @param tips    files that follow the last step but that no later file proves committed
     * @param problem why the walk stopped where it did, when it was not for want of proof
     */
    public record Plan(CatalogObject table, Pointer from, List<Step> steps, List<String> tips,
                       Optional<String> problem) {
    }

    /** How many steps of a plan were swapped, and what stopped the rest. */
    public record Applied(Plan plan, int stepsApplied, Optional<String> problem) {
    }

    private record Node(String location, long version, String sha256, Metadata.TableLink link) {
    }

    private final CatalogStore store;
    private final TableFiles files;
    private final Clock clock;

    public Reconcile(CatalogStore store, TableFiles files, Clock clock) {
        this.store = store;
        this.files = files;
        this.clock = clock;
    }

    /** Walks {@code table}'s files forward from its pointer; {@code accept} names tips the operator takes. */
    public Plan plan(CatalogObject table, Set<String> accept) {
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
                if (accepted.size() != 1) {
                    return new Plan(table, from, steps, next.stream().map(Node::location).toList(),
                            accepted.isEmpty() ? Optional.empty()
                                    : Optional.of("accept names more than one file that follows " + current));
                }
                chosen = accepted.getFirst();
                proof = Proof.ACCEPTED;
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
     * against the pointer the plan started from.
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
                committed = store.commit(batch) instanceof CommitResult.Committed;
            } catch (CommitOutcomeUnknownException e) {
                committed = store.findCommit(commitId, table.mainKey()) == CommitLookup.COMMITTED;
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

package nuna.catalog.audit.archive;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.GrantChange;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantStore;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Replays grant changes from the archive after a DR event (D37): the database restored in the DR
 * region may lack the newest grants and revocations, and the archive holds every one. Each server run's chain holds
 * its records in the order the run logged them, but a grant changed by two runs has changes in both chains (backlog
 * 262): the caller merges the chains by each record's time before {@link #net}.
 */
public final class GrantReplay {

    /** What a replay did: grants put, revocations applied, changes already in place, and changes whose securable or catalog is gone. */
    public record Report(int granted, int revoked, int unchanged, List<GrantChange> unresolved) {
    }

    private record Key(UUID securable, String group, String privilege) {
    }

    private GrantReplay() {
    }

    /** The last change of each (securable, group, privilege), in the order those last changes were made. */
    public static List<GrantChange> net(List<CatalogEvent> events) {
        Map<Key, GrantChange> last = new LinkedHashMap<>();
        for (CatalogEvent event : events) {
            if (event.data() instanceof GrantChange change) {
                Key key = new Key(change.securable().id(), change.group(), change.privilege());
                last.remove(key);
                last.put(key, change);
            }
        }
        return List.copyOf(last.values());
    }

    /**
     * Brings the store's grants in line with {@code net}: each grant whose last change granted it is
     * put, each whose last change revoked it is revoked. Both answers are idempotent, so a replay can
     * run again.
     *
     * @param catalogIds resolves a catalog's name, as the event records it, to its id
     * @param change     the change records of one replayed grant change: the store writes its audit
     *                   event and the replay is audited like any other grant change
     */
    public static Report apply(GrantStore store, List<GrantChange> net, Function<String, Optional<UUID>> catalogIds,
                               Function<GrantChange, nuna.catalog.store.Change> change) {
        int granted = 0;
        int revoked = 0;
        int unchanged = 0;
        List<GrantChange> unresolved = new ArrayList<>();
        for (GrantChange grantChange : net) {
            Optional<UUID> catalog = catalogIds.apply(grantChange.securable().catalog());
            if (catalog.isEmpty()) {
                unresolved.add(grantChange);
                continue;
            }
            Grant grant = new Grant(catalog.get(), new Securable(
                    Securable.Kind.valueOf(grantChange.securable().kind().name()), grantChange.securable().id()),
                    grantChange.group(), grantChange.privilege());
            WriteResult result = grantChange.action() == GrantChange.Action.GRANTED
                    ? store.putGrant(grant, grantChange.actor().principal(), change.apply(grantChange))
                    : store.revokeGrant(grant, change.apply(grantChange));
            switch (result) {
                case WriteResult.Applied applied -> {
                    if (grantChange.action() == GrantChange.Action.GRANTED) {
                        granted++;
                    } else {
                        revoked++;
                    }
                }
                case WriteResult.Unchanged same -> unchanged++;
                case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.GRANT -> unchanged++;
                case WriteResult.Missing missing -> unresolved.add(grantChange);
                default -> throw new IllegalStateException("a grant write answered " + result);
            }
        }
        return new Report(granted, revoked, unchanged, List.copyOf(unresolved));
    }
}

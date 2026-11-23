package nuna.catalog.server.governance;

import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceLift;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The holds released and DR freezes lifted in one catalog over the last {@value #DAYS} days (D24, D37, D45; items
 * 128 and 133), read from the store. The store keeps each release and lift, with who lifted it and when, in the
 * change's own transaction ({@link nuna.catalog.store.GovernanceStore#liftedIn}), so every one in the window is
 * listed, a level dropped since included.
 */
@Component
class HoldHistory {

    /** How many days back the console's holds page lists what was lifted ("Lifted in the last 30 days"). */
    static final int DAYS = 30;

    /** The lifts at or after {@code since}, the most recent first. */
    record Result(Instant since, List<GovernanceLift> lifted) {
    }

    private final CatalogStore store;
    private final Clock clock;

    HoldHistory(CatalogStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    Result lifted(CatalogRecord catalog) {
        Instant since = clock.instant().truncatedTo(ChronoUnit.MILLIS).minus(DAYS, ChronoUnit.DAYS);
        return new Result(since, store.liftedIn(catalog.id(), since));
    }
}

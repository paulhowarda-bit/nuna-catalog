package nuna.catalog.server.rest;

import nuna.catalog.audit.Actor;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Ref;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves the prefix {@code catalog@ref} (D33, item 26's choice 4), once, beside the catalog lookup: for every Iceberg
 * route through {@code Lookup}, and for {@code /v1/config}'s warehouse.
 */
@Component
public class Prefixes {

    private final Holdings holdings;
    private final CatalogStore store;
    private final Clock clock;

    Prefixes(Holdings holdings, CatalogStore store, Clock clock) {
        this.holdings = holdings;
        this.store = store;
        this.clock = clock;
    }

    /**
     * What {@code prefix} names. Its catalog is found as {@link Holdings#catalog} finds one, so a caller that holds
     * nothing there gets the 404 a missing catalog gets, its refusal of {@code operation} audited, whatever the ref
     * (D18 as amended). A ref the catalog does not have, or a branch past its expiry, is gone: 404
     * NoSuchWarehouseException naming the prefix, as for a warehouse that does not exist.
     *
     * @param privilege what the operation needs, or null when it needs no one privilege
     */
    public OnRef resolve(String prefix, Caller caller, Actor actor, String operation, Privilege privilege) {
        CatalogRecord catalog = holdings.catalog(OnRef.catalogName(prefix), caller, actor, operation, privilege);
        Optional<String> name = OnRef.refName(prefix);
        if (name.isEmpty()) {
            return OnRef.main(catalog);
        }
        Ref ref = store.findRef(catalog.id(), Names.normalize(name.get()))
                .filter(found -> !found.expiredAt(clock.instant()))
                .orElseThrow(() -> CatalogError.noSuchWarehouse("The given warehouse does not exist: " + prefix
                        + " (catalog " + catalog.name() + " has no branch or tag " + name.get() + ")"));
        return new OnRef(catalog, Optional.of(ref));
    }
}

package nuna.catalog.server.security;

import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.GrantChange;
import nuna.catalog.core.Names;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.Grant;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The audit catalog's grants (D23, amended 2026-11-20): at startup the server grants the readers' group {@code select}
 * on the audit catalog, and the audit table writer's group {@code create} and {@code write} there, as
 * {@code nuna.audit.tables.*} names them ({@link AuditCatalogSettings}; Paul: "Seed; admins may add", "Yes, seed
 * both"). Each is written as the grants API writes a grant: by the store's conditional put, with the separation of
 * duties checked inside the write (D18), and its {@code grant} record in the outbox in the same transaction (D23).
 * Like the catalogs declared at startup ({@link CatalogStorage}), the deployment's configuration makes them, so they
 * are recorded as granted by {@value #BY}. A grant that already exists is left as it is and records nothing, so a
 * restart, or a second server starting, changes nothing. Grant admins grant other groups there through the grants API
 * as anywhere else: nothing here refuses or revokes them.
 *
 * <p>The server refuses to start when the audit catalog is not declared, a group is named without a catalog, or a
 * grant would break the separation of duties (a grant admin or platform operator group, or a group holding a
 * conflicting duty in the catalog).
 */
@Component
@EnableConfigurationProperties(AuditCatalogSettings.class)
class AuditCatalogGrants {

    /** Who grants: the deployment's configuration, as for the catalogs and storage profiles set at startup. */
    static final String BY = "nuna-configuration";
    static final Actor CONFIGURATION = new Actor(BY, null, "catalog-server", "startup");

    /** Takes the catalogs' storage, which declares the catalogs, so the audit catalog exists first. */
    AuditCatalogGrants(AuditCatalogSettings settings, CatalogStorage storage, CatalogStore store, Separation separation,
                       RestSettings rest, Clock clock) {
        grant(settings, store, separation, rest.auditSource(), clock);
    }

    /** Makes the grants {@code settings} names, those the store already holds aside. */
    static void grant(AuditCatalogSettings settings, CatalogStore store, Separation separation, URI source,
                      Clock clock) {
        if (blank(settings.catalog())) {
            if (!blank(settings.readers()) || !blank(settings.writers())) {
                throw new IllegalStateException("nuna.audit.tables.readers and nuna.audit.tables.writers need"
                        + " nuna.audit.tables.catalog, the audit catalog they are granted on");
            }
            return;
        }
        CatalogRecord catalog = store.findCatalog(Names.normalize(settings.catalog())).orElseThrow(() ->
                new IllegalStateException("nuna.audit.tables.catalog names no catalog: " + settings.catalog()
                        + "; declare it in nuna.storage.catalogs"));
        if (!blank(settings.readers())) {
            grant(store, separation, source, clock, catalog, "nuna.audit.tables.readers", settings.readers(),
                    Privilege.SELECT);
        }
        if (!blank(settings.writers())) {
            for (Privilege privilege : List.of(Privilege.CREATE, Privilege.WRITE)) {
                grant(store, separation, source, clock, catalog, "nuna.audit.tables.writers", settings.writers(),
                        privilege);
            }
        }
    }

    private static void grant(CatalogStore store, Separation separation, URI source, Clock clock,
                              CatalogRecord catalog, String setting, String group, Privilege privilege) {
        Instant now = clock.instant();
        CatalogEvent event = CatalogEvent.create(source, now, new GrantChange(GrantChange.Action.GRANTED,
                new nuna.catalog.audit.Securable(nuna.catalog.audit.Securable.Kind.CATALOG, catalog.id(),
                        catalog.name(), List.of()), group, privilege.wireName(), CONFIGURATION));
        Change change = new Change(UUID.randomUUID(), now, List.of(new OutboxEvent(event.type().typeName(),
                new String(EventCodec.encode(event), StandardCharsets.UTF_8))), Optional.empty());
        Grant grant = new Grant(catalog.id(), new Securable(Securable.Kind.CATALOG, catalog.id()), group,
                privilege.wireName());
        AtomicReference<String> refusal = new AtomicReference<>();
        WriteResult result = store.putGrantIf(grant, BY, change, held -> {
            Optional<String> conflict = separation.refusal(group, privilege, known(held));
            refusal.set(conflict.orElse(null));
            return conflict.isEmpty();
        });
        switch (result) {
            case WriteResult.Applied applied -> {
            }
            case WriteResult.Unchanged unchanged -> {
            }
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.NOT_ADMITTED ->
                    throw new IllegalStateException(setting + ": the server may not grant " + group + " "
                            + privilege.wireName() + " on catalog " + catalog.name() + ": " + refusal.get());
            default -> throw new IllegalStateException(setting + ": granting " + group + " "
                    + privilege.wireName() + " on catalog " + catalog.name() + " answered " + result);
        }
    }

    /** The privileges this version knows, by wire name; one from a newer version conflicts with nothing here. */
    private static List<Privilege> known(List<String> held) {
        List<Privilege> known = new ArrayList<>();
        for (String name : held) {
            try {
                known.add(Privilege.fromWireName(name));
            } catch (IllegalArgumentException e) {
                // As the grant route reads it: a privilege from a newer version.
            }
        }
        return known;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

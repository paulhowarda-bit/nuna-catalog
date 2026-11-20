package nuna.catalog.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.GrantChange;
import nuna.catalog.security.Separation;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.Test;

/**
 * The audit catalog's grants at startup (D23, amended 2026-11-20): the readers' group gets {@code select} on the
 * audit catalog and the writer's group {@code create} and {@code write}, through the store's conditional put with
 * the separation of duties checked inside it and a {@code grant} record in the outbox, as the grants API writes
 * them. The store double keeps {@code putGrantIf}'s contract: it asks the check about the group's grants in the
 * catalog, answers unchanged for a grant it holds, and writes a change's outbox only with a grant it applies.
 * Needs only Java; {@code AuditTablesTest} (heavy) reads the table as the readers' group on a real server.
 */
class AuditCatalogGrantsTest {

    private static final URI SOURCE = URI.create("urn:nuna:test");
    private static final Instant NOW = Instant.parse("2026-11-20T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Separation SEPARATION = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
    private static final AuditCatalogSettings SETTINGS = new AuditCatalogSettings("nuna_audit", "auditors",
            "audit-writers");

    private final CatalogRecord audit = new CatalogRecord(UUID.randomUUID(), "nuna_audit", "nuna_audit");
    private final CatalogRecord lake = new CatalogRecord(UUID.randomUUID(), "lake", "lake");
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<CatalogEvent> outbox = new ArrayList<>();
    private final CatalogStore store = store();

    @Test
    void grantsTheReadersSelectAndTheWritersCreateAndWriteOnTheAuditCatalog() {
        AuditCatalogGrants.grant(SETTINGS, store, SEPARATION, SOURCE, CLOCK);

        Securable catalog = new Securable(Securable.Kind.CATALOG, audit.id());
        assertThat(grants).containsExactlyInAnyOrder(
                new GrantRecord(new Grant(audit.id(), catalog, "auditors", "select"), "nuna-configuration", NOW),
                new GrantRecord(new Grant(audit.id(), catalog, "audit-writers", "create"), "nuna-configuration", NOW),
                new GrantRecord(new Grant(audit.id(), catalog, "audit-writers", "write"), "nuna-configuration", NOW));
        assertThat(outbox).as("each grant recorded as the grants API records one, made by the configuration")
                .hasSize(3).allSatisfy(event -> {
                    assertThat(event.source()).isEqualTo(SOURCE);
                    assertThat(event.time()).isEqualTo(NOW);
                    GrantChange change = (GrantChange) event.data();
                    assertThat(change.action()).isEqualTo(GrantChange.Action.GRANTED);
                    assertThat(change.securable()).isEqualTo(new nuna.catalog.audit.Securable(
                            nuna.catalog.audit.Securable.Kind.CATALOG, audit.id(), "nuna_audit", List.of()));
                    assertThat(change.actor()).isEqualTo(AuditCatalogGrants.CONFIGURATION);
                });
        assertThat(outbox).extracting(event -> ((GrantChange) event.data()).group() + " "
                        + ((GrantChange) event.data()).privilege())
                .containsExactlyInAnyOrder("auditors select", "audit-writers create", "audit-writers write");
    }

    @Test
    void aGrantThatAlreadyExistsIsLeftAsItIsAndOthersGrantedThereStay() {
        Securable catalog = new Securable(Securable.Kind.CATALOG, audit.id());
        GrantRecord byHand = new GrantRecord(new Grant(audit.id(), catalog, "auditors", "select"), "gina",
                Instant.parse("2026-11-01T00:00:00Z"));
        GrantRecord another = new GrantRecord(new Grant(audit.id(), catalog, "access-reviewers", "select"), "gina",
                Instant.parse("2026-11-02T00:00:00Z"));
        grants.addAll(List.of(byHand, another));

        AuditCatalogGrants.grant(SETTINGS, store, SEPARATION, SOURCE, CLOCK);

        assertThat(grants).contains(byHand, another).hasSize(4);
        assertThat(outbox).extracting(event -> ((GrantChange) event.data()).group() + " "
                        + ((GrantChange) event.data()).privilege())
                .as("only the grants made now are recorded")
                .containsExactlyInAnyOrder("audit-writers create", "audit-writers write");
    }

    @Test
    void aRestartMakesNoDuplicates() {
        AuditCatalogGrants.grant(SETTINGS, store, SEPARATION, SOURCE, CLOCK);
        List<GrantRecord> first = List.copyOf(grants);

        AuditCatalogGrants.grant(SETTINGS, store, SEPARATION, SOURCE, Clock.offset(CLOCK, Duration.ofDays(1)));

        assertThat(grants).containsExactlyElementsOf(first);
        assertThat(outbox).hasSize(3);
    }

    @Test
    void unsetMakesNoGrants() {
        CatalogStore untouched = (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> {
                    throw new AssertionError("the store was asked " + method.getName());
                });

        AuditCatalogGrants.grant(new AuditCatalogSettings(null, null, null), untouched, SEPARATION, SOURCE, CLOCK);
        AuditCatalogGrants.grant(new AuditCatalogSettings(" ", "", null), untouched, SEPARATION, SOURCE, CLOCK);
    }

    @Test
    void aGroupLeftUnsetGetsNothing() {
        AuditCatalogGrants.grant(new AuditCatalogSettings("nuna_audit", "auditors", null), store, SEPARATION, SOURCE,
                CLOCK);

        assertThat(grants).extracting(g -> g.grant().group() + " " + g.grant().privilege())
                .containsExactly("auditors select");
    }

    @Test
    void groupsWithoutACatalogStopTheServer() {
        assertThatThrownBy(() -> AuditCatalogGrants.grant(new AuditCatalogSettings(null, "auditors", null), store,
                SEPARATION, SOURCE, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("need nuna.audit.tables.catalog");
        assertThat(grants).isEmpty();
    }

    @Test
    void aCatalogNotDeclaredStopsTheServer() {
        assertThatThrownBy(() -> AuditCatalogGrants.grant(new AuditCatalogSettings("audit_typo", "auditors",
                "audit-writers"), store, SEPARATION, SOURCE, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("nuna.audit.tables.catalog names no catalog: audit_typo; declare it in"
                        + " nuna.storage.catalogs");
        assertThat(grants).isEmpty();
    }

    @Test
    void aGrantTheSeparationOfDutiesForbidsStopsTheServerAndWritesNothing() {
        assertThatThrownBy(() -> AuditCatalogGrants.grant(new AuditCatalogSettings("nuna_audit", "grant-admins",
                null), store, SEPARATION, SOURCE, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("nuna.audit.tables.readers: the server may not grant grant-admins select on catalog"
                        + " nuna_audit: group grant-admins administers grants on every catalog and may hold no"
                        + " other grant");

        grants.add(new GrantRecord(new Grant(audit.id(), new Securable(Securable.Kind.CATALOG, audit.id()),
                "audit-writers", "manage-tags"), "gina", Instant.EPOCH));
        assertThatThrownBy(() -> AuditCatalogGrants.grant(SETTINGS, store, SEPARATION, SOURCE, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("nuna.audit.tables.writers: the server may not grant audit-writers create on"
                        + " catalog nuna_audit: group audit-writers holds manage-tags in this catalog");
        assertThat(grants).extracting(g -> g.grant().group() + " " + g.grant().privilege())
                .containsExactlyInAnyOrder("auditors select", "audit-writers manage-tags");
        assertThat(outbox).extracting(event -> ((GrantChange) event.data()).group())
                .containsExactly("auditors");
    }

    /** The two catalogs and their grants; any call the grants should not make fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> List.of(audit, lake).stream()
                            .filter(c -> c.normalizedName().equals(args[0])).findFirst();
                    case "putGrantIf" -> putGrantIf((Grant) args[0], (String) args[1], (Change) args[2],
                            (Predicate<List<String>>) args[3]);
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The contract: the group's grants in the catalog asked about, then the grant and its outbox written. */
    private WriteResult putGrantIf(Grant grant, String grantedBy, Change change, Predicate<List<String>> admits) {
        List<String> held = grants.stream().filter(g -> g.grant().catalogId().equals(grant.catalogId())
                && g.grant().group().equals(grant.group())).map(g -> g.grant().privilege()).toList();
        if (!admits.test(held)) {
            return new WriteResult.Refused(WriteResult.Reason.NOT_ADMITTED);
        }
        if (grants.stream().anyMatch(g -> g.grant().equals(grant))) {
            return WriteResult.UNCHANGED;
        }
        grants.add(new GrantRecord(grant, grantedBy, change.at()));
        for (OutboxEvent event : change.outbox()) {
            assertThat(event.type()).isEqualTo("nuna.catalog.grant");
            outbox.add(EventCodec.decode(event.payloadJson().getBytes(StandardCharsets.UTF_8)));
        }
        return WriteResult.APPLIED;
    }
}

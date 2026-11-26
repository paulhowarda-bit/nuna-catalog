package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.audittables.EventsReader;
import nuna.catalog.audit.Actor;
import nuna.catalog.audit.AuditRead;
import nuna.catalog.audit.AuditRow;
import nuna.catalog.audit.AuditRows;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Drop;
import nuna.catalog.audit.EventType;
import nuna.catalog.audit.publish.EventPublisher;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;

/**
 * Who dropped a table, for the read of the refs that keep it (backlog 170): from the drop's record in the audit table,
 * and only for an audit reader, holding select on the audit catalog (D23, amended 2026-11-22), as the feed would show
 * it; each lookup audited as a read of the object. The authorizer is the real one over a store double; the audit table
 * is a double, so this needs only Java.
 */
class AuditDropsTest {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final Instant DROPPED = NOW.minus(Duration.ofDays(2));
    /** Select on the audit catalog. */
    private static final Caller AUDREY = person("audrey", "auditors");
    /** Manage-tags on the catalog, nothing on the audit catalog. */
    private static final Caller SUE = person("sue", "stewards");

    private final CatalogRecord gate = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final CatalogRecord audit = new CatalogRecord(UUID.randomUUID(), "nuna_audit", "nuna_audit");
    private final List<GrantRecord> grants = List.of(
            grant(audit, "auditors", Privilege.SELECT),
            grant(gate, "stewards", Privilege.MANAGE_TAGS));
    private final CatalogObject staff = new CatalogObject(gate.id(), UUID.randomUUID(), UUID.randomUUID(),
            new Name("staff", "Staff"), CatalogObject.Kind.TABLE, CatalogObject.State.DROPPED, Optional.of(DROPPED),
            Optional.of(DROPPED.plus(Duration.ofDays(30))), "s3://gate/staff", Optional.empty());
    private final nuna.catalog.audit.Securable named = new nuna.catalog.audit.Securable(
            nuna.catalog.audit.Securable.Kind.TABLE, staff.id(), "gate", List.of("hr", "Staff"));

    private final List<EventsReader.Query> asked = new ArrayList<>();
    private List<AuditRow> rows = List.of();
    private RuntimeException failure;
    private boolean auditCatalog = true;
    private final List<CatalogEvent> audited = new ArrayList<>();
    private final AuditDrops drops;

    AuditDropsTest() {
        CatalogStore store = store();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Authorizer authorizer = new Authorizer(store, store, store,
                new Separation(Set.of("grant-admins"), Set.of("platform-ops")), false, Set.of());
        AuditTrail trail = new AuditTrail(Optional.<EventPublisher>of(stamped -> audited.add(stamped.event())),
                URI.create("urn:nuna:test"), clock, Duration.ofSeconds(1));
        AuditFeed feed = new AuditFeed(null, "audit", store, null, null, clock) {
            @Override
            Optional<CatalogRecord> auditCatalog() {
                return auditCatalog ? Optional.of(audit) : Optional.empty();
            }

            @Override
            Read page(EventsReader.Query query) {
                asked.add(query);
                if (failure != null) {
                    throw failure;
                }
                return new Read(new EventsReader.Page(rows, Optional.empty(), Optional.empty()),
                        new Snapshot("nuna_audit.audit.events", Optional.of(7L), Optional.of(NOW)));
            }
        };
        drops = new AuditDrops(feed, authorizer, trail);
    }

    @Test
    void anAuditReaderIsToldWhoDroppedItFromTheDropsRecordAndTheReadIsAudited() {
        rows = List.of(dropRow(UUID.randomUUID(), "someone-else"), dropRow(staff.id(), "lee"));

        Optional<RefRoutes.Dropper> by = drops.droppedBy(request(AUDREY), gate, staff, named);

        assertThat(by).contains(new RefRoutes.Dropper("lee", "ana"));
        EventsReader.Query query = asked.getFirst();
        assertThat(query.catalog()).isEqualTo("gate");
        assertThat(query.types()).containsExactly(EventType.DROP.typeName());
        assertThat(query.path()).containsExactly("hr", "staff");
        assertThat(query.from()).isEqualTo(DROPPED);
        assertThat(query.to()).isAfter(DROPPED);
        assertThat(query.branch()).isNull();
        assertThat(query.subject()).isNull();
        assertThat(query.objectId()).as("the drop is asked for by the object's id").isNotNull();
        assertThat(audited).extracting(CatalogEvent::data).singleElement().isInstanceOfSatisfying(AuditRead.class,
                read -> {
                    assertThat(read.target().id()).isEqualTo(staff.id());
                    assertThat(read.returned()).isEqualTo(1);
                    assertThat(read.actor().principal()).isEqualTo("audrey");
                });
    }

    @Test
    void aCallerWhoDoesNotReadTheAuditIsToldNoOneAndTheTableIsNotAsked() {
        rows = List.of(dropRow(staff.id(), "lee"));

        assertThat(drops.droppedBy(request(SUE), gate, staff, named)).isEmpty();
        assertThat(asked).isEmpty();
        assertThat(audited).as("asked, not required: nothing refused, nothing read").isEmpty();
    }

    @Test
    void withoutTheAuditTablesOrTheRecordThereIsNoOne() {
        assertThat(drops.droppedBy(request(AUDREY), gate, staff, named)).as("no record of the drop").isEmpty();
        assertThat(audited).extracting(CatalogEvent::data).singleElement().isInstanceOfSatisfying(AuditRead.class,
                read -> assertThat(read.returned()).isZero());

        failure = new AuditFeed.NotReady("the audit table does not exist yet");
        assertThat(drops.droppedBy(request(AUDREY), gate, staff, named)).isEmpty();

        auditCatalog = false;
        failure = null;
        rows = List.of(dropRow(staff.id(), "lee"));
        assertThat(drops.droppedBy(request(AUDREY), gate, staff, named)).isEmpty();
        assertThat(asked).hasSize(2);
    }

    private AuditRow dropRow(UUID id, String who) {
        nuna.catalog.audit.Securable object = new nuna.catalog.audit.Securable(
                nuna.catalog.audit.Securable.Kind.TABLE, id, "gate", List.of("hr", "Staff"));
        return AuditRows.of(CatalogEvent.create(URI.create("urn:nuna:test"), DROPPED,
                new Drop(object, DROPPED.plus(Duration.ofDays(30)), new Actor(who, "ana", "trino", "request-3"))),
                0, 3);
    }

    private static Request request(Caller caller) {
        return new Request(caller, new Actor(caller.principal().id(), null, "console", "request-1"));
    }

    private static GrantRecord grant(CatalogRecord catalog, String group, Privilege privilege) {
        return new GrantRecord(new Grant(catalog.id(), new Securable(Securable.Kind.CATALOG, catalog.id()), group,
                privilege.wireName()), "test", Instant.EPOCH);
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf", "holdsOn" -> List.of();
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}

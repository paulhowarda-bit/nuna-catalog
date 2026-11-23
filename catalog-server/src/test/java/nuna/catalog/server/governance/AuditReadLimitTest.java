package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import nuna.catalog.audit.AuditRead;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.publish.EventPublisher;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The audit-trail read's limit (backlog 19): each read opens a Kafka consumer, so one caller may read at most
 * {@code nuna.governance.audit-reads-per-minute} times in any minute on a server, and beyond that is answered 429
 * with {@code Retry-After}, in the error body a purge claim over its limit gets, without opening the topic. As
 * with the purge limit, the 429 is not audited. Callers are counted apart, the minute slides, and a read that is
 * refused or badly asked takes no slot. The route, guard and authorizer are the real ones over a store double, the
 * audit topic is a double that counts the reads that reach it, and the clock is the test's, so this needs only Java;
 * {@code AuditReadRouteTest} reads a real topic.
 */
class AuditReadLimitTest {

    private static final int LIMIT = 3;
    private static final Instant START = Instant.parse("2026-11-19T22:00:00Z");
    private static final Caller GINA = person("gina", "grant-admins");
    private static final Caller GUS = person("gus", "grant-admins");
    /** Select on the table, not manage-grants: refused. */
    private static final Caller TOM = person("tom", "staff-readers");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final NamespaceRecord hr = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
            new Name(Names.normalize("hr"), "hr"), Map.of(), 1);
    private final CatalogObject staff = CatalogObject.active(catalog.id(), UUID.randomUUID(), hr.id(),
            new Name(Names.normalize("staff"), "staff"), CatalogObject.Kind.TABLE, "s3://gate/staff");
    private final List<GrantRecord> grants = List.of(new GrantRecord(new Grant(catalog.id(), staff.securable(),
            "staff-readers", Privilege.SELECT.wireName()), "test", Instant.EPOCH));

    private final MutableClock clock = new MutableClock(START);
    private final List<CatalogEvent> audit = new ArrayList<>();
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final AuditReadRoutes trail;
    /** The reads that reached the audit topic: each would have opened a Kafka consumer. */
    private int topicReads;
    private Caller caller = GINA;

    AuditReadLimitTest() {
        // What RequestIds would have set before the route ran.
        http.setAttribute(RequestIds.class.getName(), "request-1");
        CatalogStore store = store();
        RestSettings rest = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        AuditPublisher publisher = audit::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, rest, clock);
        Holdings holdings = new Holdings(authorizer, store, rest, separation, denials);
        Management management = new Management(store, request -> caller, rest, holdings);
        ManagementGuard guard = new ManagementGuard(authorizer, separation, null, denials, holdings);
        GovernanceSettings settings = new GovernanceSettings(10, LIMIT, Map.of(), Duration.ofDays(7),
                Duration.ofHours(1), Map.of());
        AuditTrail auditTrail = new AuditTrail(Optional.<EventPublisher>of(audit::add), URI.create("urn:nuna:test"),
                clock, Duration.ofSeconds(1));
        // The topic's trail needs neither the audit table nor the archive (AuditFeedRoutesTest).
        trail = new AuditReadRoutes(management, guard, new Topic(), auditTrail, settings, null, null, clock);
    }

    @Test
    void readsUpToTheLimitAreAnswered() {
        for (int i = 0; i < LIMIT; i++) {
            assertThat(read().getStatusCode().value()).isEqualTo(200);
        }
        assertThat(topicReads).isEqualTo(LIMIT);
        assertThat(audited(AuditRead.class)).hasSize(LIMIT);
    }

    @Test
    void aReadOverTheLimitIsAnswered429WithRetryAfterAndReadsNothing() {
        readTheLimit();
        clock.advance(Duration.ofSeconds(59));

        ResponseEntity<String> limited = read();

        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(limited.getHeaders().getContentType()).hasToString("application/json");
        assertThat(JsonPath.<Integer>read(limited.getBody(), "$.error.code")).isEqualTo(429);
        assertThat(JsonPath.<String>read(limited.getBody(), "$.error.type")).isEqualTo("TooManyRequestsException");
        assertThat(JsonPath.<String>read(limited.getBody(), "$.error.message"))
                .isEqualTo("More than 3 audit reads in a minute by gina; retry later");
        assertThat(topicReads).as("no consumer opened for the refused read").isEqualTo(LIMIT);
        assertThat(audited(AuditRead.class)).as("nothing was read").hasSize(LIMIT);
        assertThat(audited(Denial.class)).as("not audited, as the purge limit's 429 is not").isEmpty();
    }

    @Test
    void theMinuteSlidesOneReadAtATime() {
        read();
        clock.advance(Duration.ofSeconds(20));
        read();
        clock.advance(Duration.ofSeconds(20));
        read();
        clock.advance(Duration.ofSeconds(19));
        assertThat(read().getStatusCode().value()).as("59 s after the first read").isEqualTo(429);

        clock.advance(Duration.ofSeconds(1));
        assertThat(read().getStatusCode().value()).as("the first read is a minute old").isEqualTo(200);
        assertThat(read().getStatusCode().value()).as("only its slot came free").isEqualTo(429);

        clock.advance(Duration.ofSeconds(20));
        assertThat(read().getStatusCode().value()).as("the second read is a minute old").isEqualTo(200);

        clock.advance(Duration.ofMinutes(5));
        for (int i = 0; i < LIMIT; i++) {
            assertThat(read().getStatusCode().value()).as("a quiet spell frees every slot").isEqualTo(200);
        }
        assertThat(read().getStatusCode().value()).isEqualTo(429);
        assertThat(topicReads).isEqualTo(3 + 1 + 1 + LIMIT);
    }

    @Test
    void callersAreCountedApart() {
        readTheLimit();
        assertThat(read().getStatusCode().value()).isEqualTo(429);

        caller = GUS;
        for (int i = 0; i < LIMIT; i++) {
            assertThat(read().getStatusCode().value()).as("gus has his own allowance").isEqualTo(200);
        }
        assertThat(read().getStatusCode().value()).isEqualTo(429);

        caller = GINA;
        assertThat(read().getStatusCode().value()).as("gus's reads gave gina nothing back").isEqualTo(429);
        assertThat(audited(AuditRead.class)).extracting(r -> r.actor().principal())
                .containsExactly("gina", "gina", "gina", "gus", "gus", "gus");
    }

    @Test
    void aRefusedOrBadlyAskedReadTakesNoSlot() {
        caller = TOM;
        for (int i = 0; i < 2 * LIMIT; i++) {
            assertThat(answer(this::read)).as("refused, every time").isEqualTo(403);
        }
        assertThat(audited(Denial.class)).hasSize(2 * LIMIT);

        caller = GINA;
        for (int i = 0; i < 2 * LIMIT; i++) {
            assertThat(answer(() -> trail.recent("gate", "hr", "staff", null, "0", http))).isEqualTo(400);
        }
        assertThat(topicReads).isZero();
        readTheLimit();
        assertThat(read().getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void theSettingIsSixtyAMinuteUnlessSetAndNeverBelowOne() {
        assertThat(bind(Map.of()).auditReadsPerMinute()).isEqualTo(60);
        assertThat(bind(Map.of("nuna.governance.audit-reads-per-minute", "5")).auditReadsPerMinute()).isEqualTo(5);
        assertThatThrownBy(() -> bind(Map.of("nuna.governance.audit-reads-per-minute", "0")))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("nuna.governance.audit-reads-per-minute must be at least 1");
    }

    // ---- helpers ----

    /** A read of the table's trail by the current caller, at the default count. */
    private ResponseEntity<String> read() {
        return trail.recent("gate", "hr", "staff", null, null, http);
    }

    private void readTheLimit() {
        for (int i = 0; i < LIMIT; i++) {
            assertThat(read().getStatusCode().value()).isEqualTo(200);
        }
    }

    /** The status a client is told, a thrown error included. */
    private static int answer(Callable<? extends ResponseEntity<?>> call) {
        try {
            return call.call().getStatusCode().value();
        } catch (CatalogError e) {
            return e.status();
        } catch (AccessDenied e) {
            return 403;
        } catch (Exception e) {
            throw new AssertionError("the route failed unexpectedly", e);
        }
    }

    private <T> List<T> audited(Class<T> type) {
        return audit.stream().map(CatalogEvent::data).filter(type::isInstance).map(type::cast).toList();
    }

    private static GovernanceSettings bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("nuna.governance", GovernanceSettings.class);
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    /** The audit topic, configured and empty; it counts the reads that reach it. */
    private final class Topic extends RecentAudit {

        Topic() {
            super(null);
        }

        @Override
        boolean configured() {
            return true;
        }

        @Override
        Result recent(String subject, int limit) {
            topicReads++;
            return new Result(List.of(), new Window("nuna.catalog.audit", 0, 0, 0, 0));
        }
    }

    /** One catalog, namespace and table, and the grant above; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "findNamespace" -> Optional.of(hr).filter(ns -> args[1].equals(List.of("hr")));
                    case "readNamespace" -> Optional.of(hr).filter(ns -> ns.id().equals(args[0]));
                    case "findObject" -> Optional.of(staff).filter(o -> o.namespaceId().equals(args[0])
                            && o.name().normalized().equals(args[1]));
                    case "readObject" -> Optional.of(staff).filter(o -> o.id().equals(args[0]));
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf", "holdsOn" -> List.of();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList(), Optional.empty());
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

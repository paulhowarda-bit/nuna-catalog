package keel.catalog.core.commit;

import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.core.Names;
import keel.catalog.core.commit.CommitReply.Success;
import keel.catalog.core.principal.Caller;
import keel.catalog.core.principal.Principal;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.CatalogRecord;
import keel.catalog.store.CatalogSettings;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.Change;
import keel.catalog.store.GovernanceAttribute;
import keel.catalog.store.JournalRecord;
import keel.catalog.store.LegalHold;
import keel.catalog.store.Name;
import keel.catalog.store.NamespaceRecord;
import keel.catalog.store.OutboxEvent;
import keel.catalog.store.PageRequest;
import keel.catalog.store.Pointer;
import keel.catalog.store.Securable;
import keel.catalog.store.StorageProfile;
import keel.catalog.store.WriteResult;
import keel.catalog.store.sql.SqlCatalogStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One catalog and namespace on a shared PostgreSQL, an engine over them with in-memory metadata
 * files, a clock that only the engine's waits move, and recorders for events and alerts.
 */
final class Harness {

    static final Caller ALICE = new Caller(new Principal("alice", Set.of("engineers")), "spark", false,
            Optional.empty());
    static final Caller BOB = new Caller(new Principal("bob", Set.of("engineers")), "trino", false, Optional.empty());

    private static PostgreSQLContainer postgres;
    private static CatalogStore shared;

    final FaultyStore faults;
    final CatalogStore store;
    final MemoryFiles files = new MemoryFiles();
    final TestClock clock = new TestClock();
    final List<Duration> waits = Collections.synchronizedList(new ArrayList<>());
    final List<CommitEvents.Commit> events = Collections.synchronizedList(new ArrayList<>());
    final List<String> alerts = Collections.synchronizedList(new ArrayList<>());
    final UUID catalogId = UUID.randomUUID();
    final UUID namespaceId = UUID.randomUUID();
    MetadataCache cache = new MetadataCache(1 << 22);
    CommitSettings settings = CommitSettings.DEFAULTS;
    List<CommitRule> rules = Rules.standard(files, Duration.ofSeconds(60));

    Harness() {
        faults = new FaultyStore(shared());
        store = faults.store;
        UUID profileId = UUID.randomUUID();
        assertThat(faults.real.putStorageProfile(new StorageProfile(profileId, "profile-" + profileId,
                StorageProfile.Kind.CEPH, Optional.of("https://rgw.internal:8443"), "arn:aws:iam::1:role/catalog",
                List.of(MemoryFiles.ROOT)), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(faults.real.createCatalog(new CatalogRecord(catalogId, "c-" + catalogId, "C-" + catalogId),
                new CatalogSettings(profileId, "kms-default", "acme"), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(faults.real.createNamespace(new NamespaceRecord(catalogId, namespaceId, catalogId,
                new Name("sales", "Sales"), Map.of(), 1), change())).isEqualTo(WriteResult.APPLIED);
    }

    static synchronized CatalogStore shared() {
        if (shared == null) {
            postgres = new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image", "postgres:18")));
            postgres.start();
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setUrl(postgres.getJdbcUrl());
            dataSource.setUser(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            dataSource.setSocketTimeout(60);
            shared = new SqlCatalogStore(dataSource);
            shared.migrate();
        }
        return shared;
    }

    CommitEngine engine() {
        return engine(store);
    }

    /** An engine over {@code store}: the real one stands in for another server. */
    CommitEngine engine(CatalogStore over) {
        return new CommitEngine(over, files, cache, rules, commit -> {
            events.add(commit);
            return List.of(new OutboxEvent("test.commit", "{\"commit\":\"" + commit.commitId() + "\"}"));
        }, new Alerts() {
            @Override
            public void metadataIntegrity(UUID objectId, String location, String expectedSha256, String actualSha256) {
                alerts.add("integrity " + objectId + " " + location);
            }

            @Override
            public void regulatedSchemaChange(UUID objectId, UUID commitId) {
                alerts.add("regulated " + objectId + " " + commitId);
            }
        }, settings, clock, wait -> {
            waits.add(wait);
            clock.advance(wait);
        });
    }

    ObjectName name(String display) {
        return new ObjectName(catalogId, namespaceId, new Name(Names.normalize(display), display));
    }

    Success createTable(String display) {
        return createTable(display, Map.of());
    }

    Success createTable(String display, Map<String, String> properties) {
        CommitReply reply = engine(faults.real).createTable(name(display), Requests.createTable(display, properties),
                ALICE, Optional.empty());
        assertThat(reply).isInstanceOf(Success.class);
        return (Success) reply;
    }

    Success createView(String display) {
        CommitReply reply = engine(faults.real).createView(name(display), Requests.createView(display), ALICE,
                Optional.empty());
        assertThat(reply).isInstanceOf(Success.class);
        return (Success) reply;
    }

    CatalogObject object(String display) {
        return faults.real.findObject(namespaceId, Names.normalize(display)).orElseThrow();
    }

    Pointer pointer(String display) {
        return faults.real.readPointer(object(display).mainKey()).orElseThrow();
    }

    List<JournalRecord> journal(String display) {
        return faults.real.readJournal(object(display).mainKey(), Instant.EPOCH, Instant.now().plus(Duration.ofDays(1)),
                PageRequest.first(100)).items();
    }

    void regulate(Securable target) {
        assertThat(faults.real.putAttribute(new GovernanceAttribute(target, GovernanceAttribute.Kind.REGULATED, "true",
                "test", "admin", Instant.now()), change())).isEqualTo(WriteResult.APPLIED);
    }

    void attribute(Securable target, GovernanceAttribute.Kind kind, String value) {
        assertThat(faults.real.putAttribute(new GovernanceAttribute(target, kind, value, "test", "admin", Instant.now()),
                change())).isEqualTo(WriteResult.APPLIED);
    }

    void hold(Securable target) {
        assertThat(faults.real.placeHold(new LegalHold(UUID.randomUUID(), target, "litigation", "legal", Instant.now()),
                change())).isEqualTo(WriteResult.APPLIED);
    }

    Securable namespace() {
        return new Securable(Securable.Kind.NAMESPACE, namespaceId);
    }

    static Change change() {
        return new Change(UUID.randomUUID(), Instant.now(), List.of(new OutboxEvent("test.setup", "{}")),
                Optional.empty());
    }

    /** Starts at the wall clock, so leases the store judges by its own clock agree; moves only when told. */
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        void advance(Duration by) {
            now.updateAndGet(instant -> instant.plus(by));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}

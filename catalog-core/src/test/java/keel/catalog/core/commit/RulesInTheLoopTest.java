package keel.catalog.core.commit;

import static keel.catalog.core.commit.Harness.ALICE;
import static keel.catalog.core.commit.Requests.addColumn;
import static keel.catalog.core.commit.Requests.addSnapshot;
import static keel.catalog.core.commit.Requests.assertMain;
import static keel.catalog.core.commit.Requests.assertSchema;
import static keel.catalog.core.commit.Requests.assertUuid;
import static keel.catalog.core.commit.Requests.partitionById;
import static keel.catalog.core.commit.Requests.removeProperties;
import static keel.catalog.core.commit.Requests.removeSnapshots;
import static keel.catalog.core.commit.Requests.setLocation;
import static keel.catalog.core.commit.Requests.setMain;
import static keel.catalog.core.commit.Requests.setProperties;
import static keel.catalog.core.commit.Requests.update;
import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.core.commit.CommitReply.Failure;
import keel.catalog.core.commit.CommitReply.Success;
import keel.catalog.store.GovernanceAttribute;
import keel.catalog.store.Securable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The rules layer as the commit loop runs it (D11, D11a): each refusal writes nothing. */
class RulesInTheLoopTest {

    private final Harness h = new Harness();

    private CommitReply commit(String request) {
        return h.engine().commitTable(h.name("Orders"), request, ALICE, Optional.empty());
    }

    private String uuid() {
        return assertUuid(h.object("Orders").id().toString());
    }

    private void assertRefused(CommitReply reply, int status, String messageStart) {
        assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> {
            assertThat(f.status()).isEqualTo(status);
            assertThat(f.type()).isEqualTo(status == 403 ? "ForbiddenException" : "BadRequestException");
            assertThat(f.message()).startsWith(messageStart);
        });
    }

    @Nested
    class Locations {

        @Test
        @DisplayName("a commit that moves the table is 400, and writes nothing")
        void moving() {
            h.createTable("Orders");
            int writes = h.files.writes.get();
            String root = h.object("Orders").locationRoot();

            CommitReply reply = commit(update(uuid(), setLocation(MemoryFiles.ROOT + "/elsewhere")));

            assertThat(reply).isEqualTo(new Failure(400, "BadRequestException", "Location is assigned by the catalog: "
                    + root + ", not " + MemoryFiles.ROOT + "/elsewhere"));
            assertThat(h.files.writes.get()).isEqualTo(writes);
            assertThat(h.pointer("Orders").version()).isEqualTo(1);
        }

        @Test
        @DisplayName("each write-path property must point inside the table's location")
        void writePaths() {
            h.createTable("Orders");
            String root = h.object("Orders").locationRoot();
            for (String key : Rules.PATH_PROPERTIES) {
                for (String outside : new String[] {MemoryFiles.ROOT + "/other", root + "/../other", root,
                        root.replace("s3://lake/", "s3://other/") + "/data"}) {
                    assertRefused(commit(update(uuid(), setProperties(Map.of(key, outside)))), 400,
                            "Property " + key + " must lie within the table location");
                }
                assertThat(commit(update(uuid(), setProperties(Map.of(key, root + "/" + key)))))
                        .isInstanceOf(Success.class);
            }
        }

        @Test
        @DisplayName("a create with a write path outside the table is 400, and no table is made")
        void writePathAtCreate() {
            CommitReply reply = h.engine().createTable(h.name("Orders"),
                    Requests.createTable("Orders", Map.of("write.data.path", "s3://other/data")), ALICE, Optional.empty());

            assertRefused(reply, 400, "Property write.data.path must lie within the table location");
            assertThat(h.faults.real.findObject(h.namespaceId, "orders")).isEmpty();
        }
    }

    @Nested
    class EncryptionKey {

        @Test
        @DisplayName("encryption.key-id is set at create and can then be neither changed nor removed nor added")
        void fixedAtCreate() {
            h.createTable("Orders", Map.of(Rules.ENCRYPTION_KEY_ID, "key-1"));

            assertRefused(commit(update(uuid(), setProperties(Map.of(Rules.ENCRYPTION_KEY_ID, "key-2")))), 400,
                    "Property encryption.key-id is fixed");
            assertRefused(commit(update(uuid(), removeProperties(Rules.ENCRYPTION_KEY_ID))), 400,
                    "Property encryption.key-id is fixed");
            assertThat(commit(update(uuid(), setProperties(Map.of("comment", "ok"))))).isInstanceOf(Success.class);

            h.createTable("Plain");
            assertRefused(h.engine().commitTable(h.name("Plain"), update(assertUuid(h.object("Plain").id().toString()),
                    setProperties(Map.of(Rules.ENCRYPTION_KEY_ID, "key-1"))), ALICE, Optional.empty()), 400,
                    "Property encryption.key-id is fixed");
        }
    }

    @Nested
    class Holds {

        /** A v2 table whose main branch is at snapshot 2, child of 1. */
        private void twoSnapshots() {
            h.createTable("Orders", Map.of("format-version", "2"));
            long now = h.clock.millis();
            assertThat(commit(update(assertMain(null), addSnapshot(1, null, 1, now), setMain(1))))
                    .isInstanceOf(Success.class);
            assertThat(commit(update(assertMain(1L), addSnapshot(2, 1L, 2, now), setMain(2))))
                    .isInstanceOf(Success.class);
        }

        @ParameterizedTest(name = "hold on the {0}")
        @EnumSource(value = Securable.Kind.class, names = {"TABLE", "NAMESPACE", "CATALOG"})
        @DisplayName("a held table keeps its snapshots: removing one is 403, whether the hold is on it or above it")
        void held(Securable.Kind level) {
            twoSnapshots();
            h.hold(switch (level) {
                case TABLE -> h.object("Orders").securable();
                case NAMESPACE -> h.namespace();
                default -> new Securable(Securable.Kind.CATALOG, h.catalogId);
            });

            assertRefused(commit(update(uuid(), removeSnapshots(1))), 403,
                    "Table " + h.object("Orders").id() + " is under legal hold");
            assertThat(h.pointer("Orders").version()).isEqualTo(3);
        }

        @Test
        @DisplayName("without a hold, snapshots can be removed")
        void notHeld() {
            twoSnapshots();

            assertThat(commit(update(uuid(), removeSnapshots(1)))).isInstanceOf(Success.class);
        }

        @Test
        @DisplayName("a held table still takes commits that remove nothing")
        void heldButNothingRemoved() {
            twoSnapshots();
            h.hold(h.namespace());

            assertThat(commit(update(uuid(), setProperties(Map.of("comment", "held"))))).isInstanceOf(Success.class);
        }
    }

    @Nested
    class Governance {

        private void twoSnapshots() {
            h.createTable("Orders", Map.of("format-version", "2"));
            long now = h.clock.millis();
            assertThat(commit(update(assertMain(null), addSnapshot(1, null, 1, now), setMain(1))))
                    .isInstanceOf(Success.class);
            assertThat(commit(update(assertMain(1L), addSnapshot(2, 1L, 2, now), setMain(2))))
                    .isInstanceOf(Success.class);
        }

        @Test
        @DisplayName("a DR freeze on the namespace refuses commits and creates beneath it with 403; lifted, they apply")
        void drFreeze() {
            h.createTable("Orders");
            h.attribute(h.namespace(), GovernanceAttribute.Kind.DR_FREEZE, "reconcile");

            assertRefused(commit(update(uuid(), setProperties(Map.of("comment", "x")))), 403, "Writes to ");
            assertRefused(h.engine().createTable(h.name("Other"), Requests.createTable("Other", Map.of()), ALICE,
                    Optional.empty()), 403, "Writes to ");
            assertThat(h.pointer("Orders").version()).isEqualTo(1);

            h.faults.real.removeAttribute(h.namespace(), GovernanceAttribute.Kind.DR_FREEZE, Harness.change());
            assertThat(commit(update(uuid(), setProperties(Map.of("comment", "x"))))).isInstanceOf(Success.class);
        }

        @Test
        @DisplayName("the table's own retention class overrides its namespace's: the nearest level wins")
        void nearestRetentionClass() {
            h.rules = List.of(Rules.snapshotMinimumAge(name -> name.equals("long")
                    ? Optional.of(java.time.Duration.ofDays(30)) : Optional.empty()));
            twoSnapshots();
            h.attribute(h.namespace(), GovernanceAttribute.Kind.RETENTION_CLASS, "long");

            assertRefused(commit(update(uuid(), removeSnapshots(1))), 403, "Snapshot 1 of ");

            h.attribute(h.object("Orders").securable(), GovernanceAttribute.Kind.RETENTION_CLASS, "short");
            assertThat(commit(update(uuid(), removeSnapshots(1)))).isInstanceOf(Success.class);
        }
    }

    @Nested
    class ClockSkew {

        @Test
        @DisplayName("a snapshot added off main more than 60 s ahead of the catalog clock is 400; the builder alone lets it in")
        void aheadOffMain() {
            h.createTable("Orders", Map.of("format-version", "2"));
            String ahead = update(assertMain(null), addSnapshot(1, null, 1, h.clock.millis() + 61_000));

            assertRefused(commit(ahead), 400, "Snapshot 1 is stamped");

            h.rules = List.of();
            assertThat(commit(ahead)).isInstanceOf(Success.class);
        }

        @Test
        @DisplayName("59 s ahead is within the skew")
        void within() {
            h.createTable("Orders", Map.of("format-version", "2"));

            assertThat(commit(update(assertMain(null), addSnapshot(1, null, 1, h.clock.millis() + 59_000))))
                    .isInstanceOf(Success.class);
        }

        @Test
        @DisplayName("on main, iceberg-core itself refuses a snapshot more than a minute ahead (its snapshot-log check)")
        void aheadOnMainIsTheBuilders() {
            h.createTable("Orders", Map.of("format-version", "2"));
            h.rules = List.of();

            assertThat(commit(update(assertMain(null), addSnapshot(1, null, 1, h.clock.millis() + 120_000), setMain(1))))
                    .isInstanceOfSatisfying(Failure.class, f -> {
                        assertThat(f.status()).isEqualTo(400);
                        assertThat(f.message()).startsWith("Invalid update timestamp");
                    });
        }
    }

    @Nested
    class RegulatedSchemaChanges {

        @Test
        @DisplayName("a schema change in a regulated namespace commits, is journaled as such and alerted (D11a)")
        void schemaChange() {
            h.createTable("Orders");
            h.regulate(h.namespace());

            CommitReply reply = commit(update(assertSchema(0), addColumn(1, "note")));

            assertThat(reply).isInstanceOf(Success.class);
            CommitEvents.Commit event = h.events.getLast();
            assertThat(event.regulatedSchemaChange()).isTrue();
            assertThat(h.alerts).containsExactly("regulated " + h.object("Orders").id() + " " + event.commitId());
        }

        @Test
        @DisplayName("a partition spec change in a regulated namespace is flagged too")
        void specChange() {
            h.createTable("Orders");
            h.regulate(h.namespace());

            assertThat(commit(update(uuid(), partitionById()))).isInstanceOf(Success.class);
            assertThat(h.events.getLast().regulatedSchemaChange()).isTrue();
            assertThat(h.alerts).hasSize(1);
        }

        @Test
        @DisplayName("other changes in a regulated namespace, and schema changes elsewhere, are not flagged")
        void notFlagged() {
            h.createTable("Orders");
            assertThat(commit(update(assertSchema(0), addColumn(1, "note")))).isInstanceOf(Success.class);
            assertThat(h.events.getLast().regulatedSchemaChange()).isFalse();

            h.regulate(h.namespace());
            assertThat(commit(update(uuid(), setProperties(Map.of("comment", "x"))))).isInstanceOf(Success.class);
            assertThat(h.events.getLast().regulatedSchemaChange()).isFalse();
            assertThat(h.alerts).isEmpty();
        }
    }

    @Test
    @DisplayName("without the rules, iceberg-core accepts every change the rules refuse (D11: each rule earns its place)")
    void theBuilderAloneAcceptsWhatTheRulesRefuse() {
        h.rules = List.of();
        h.createTable("Orders", Map.of("format-version", "2"));
        h.createTable("Keyed", Map.of(Rules.ENCRYPTION_KEY_ID, "key-1"));
        long now = h.clock.millis();
        assertThat(commit(update(assertMain(null), addSnapshot(1, null, 1, now), setMain(1)))).isInstanceOf(Success.class);
        assertThat(commit(update(assertMain(1L), addSnapshot(2, 1L, 2, now), setMain(2)))).isInstanceOf(Success.class);
        h.hold(h.namespace());

        assertThat(commit(update(uuid(), setProperties(Map.of("write.data.path", "s3://other/data")))))
                .isInstanceOf(Success.class);
        assertThat(h.engine().commitTable(h.name("Keyed"), update(assertUuid(h.object("Keyed").id().toString()),
                setProperties(Map.of(Rules.ENCRYPTION_KEY_ID, "key-2"))), ALICE, Optional.empty()))
                .isInstanceOf(Success.class);
        assertThat(commit(update(uuid(), removeSnapshots(1)))).isInstanceOf(Success.class);
        assertThat(commit(update(uuid(), setLocation(MemoryFiles.ROOT + "/elsewhere")))).isInstanceOf(Success.class);
    }

    @Test
    @DisplayName("a rule's refusal is 400 or 403, never retried")
    void refusalsAreNotRetried() {
        h.settings = new CommitSettings(4, Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofHours(24),
                Duration.ofSeconds(30));
        h.createTable("Orders");

        assertThat(commit(update(uuid(), setLocation(MemoryFiles.ROOT + "/x")))).isInstanceOf(Failure.class);
        assertThat(h.waits).isEmpty();
        assertThat(h.faults.calls("commit")).isZero();
    }
}

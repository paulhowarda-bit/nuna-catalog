package nuna.catalog.core.commit;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.commit.CommitRule.Rejection;
import nuna.catalog.iceberg.Facts;
import nuna.catalog.iceberg.Facts.TableFacts;
import nuna.catalog.iceberg.Facts.ViewFacts;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Name;
import nuna.catalog.store.Securable;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Each rule on its own, on plain facts. */
class RulesTest {

    private static final UUID ID = UUID.fromString("5f0e9a52-3a1c-4c39-9a8e-2b1d1c1e7f00");
    private static final String ROOT = MemoryFiles.ROOT + "/" + ID;
    private static final CatalogObject TABLE = CatalogObject.active(UUID.randomUUID(), ID, UUID.randomUUID(),
            new Name("orders", "orders"), CatalogObject.Kind.TABLE, ROOT);
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final Duration SKEW = Duration.ofSeconds(60);

    private final MemoryFiles files = new MemoryFiles();
    private final AtomicInteger governanceReads = new AtomicInteger();
    private final Map<GovernanceAttribute.Kind, GovernanceAttribute> attributes = new EnumMap<>(GovernanceAttribute.Kind.class);

    private CommitRule.Governance governance(boolean held) {
        return new CommitRule.Governance() {
            @Override
            public boolean held() {
                governanceReads.incrementAndGet();
                return held;
            }

            @Override
            public boolean regulated() {
                governanceReads.incrementAndGet();
                return false;
            }

            @Override
            public Optional<GovernanceAttribute> nearest(GovernanceAttribute.Kind kind) {
                governanceReads.incrementAndGet();
                return Optional.ofNullable(attributes.get(kind));
            }
        };
    }

    private void set(GovernanceAttribute.Kind kind, String value) {
        attributes.put(kind, new GovernanceAttribute(new Securable(Securable.Kind.NAMESPACE, UUID.randomUUID()), kind,
                value, "test", "tester", NOW));
    }

    private static TableFacts table(String location, Map<String, String> properties, Map<Long, Long> snapshots) {
        return new TableFacts(ID.toString(), 2, location, properties, 0, 0, snapshots);
    }

    private static TableFacts table(Map<String, String> properties) {
        return table(ROOT, properties, Map.of());
    }

    private CommitRule.Change change(Facts before, Facts after, boolean held) {
        return new CommitRule.Change(TABLE, before, after, NOW, governance(held));
    }

    private CommitRule.Change change(Facts before, Facts after) {
        return change(before, after, false);
    }

    private static Map<String, String> props(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("the standard set holds the DR freeze and the five D11 rules, in order")
    void standard() {
        assertThat(Rules.standard(files, SKEW)).hasSize(6);
    }

    @Nested
    class DrFreeze {

        @Test
        void aFrozenObjectTakesNoCommit() {
            set(GovernanceAttribute.Kind.DR_FREEZE, "reconcile INC-7");
            assertThat(Rules.drFreeze(change(table(Map.of()), table(props("a", "b")))))
                    .contains(Rejection.forbidden("Writes to " + ID + " are frozen for DR reconcile: reconcile INC-7"));
            assertThat(Rules.drFreeze(change(null, table(Map.of())))).as("nor a create").isPresent();
        }

        @Test
        void anUnfrozenObjectCommits() {
            set(GovernanceAttribute.Kind.RETENTION_CLASS, "standard");
            assertThat(Rules.drFreeze(change(table(Map.of()), table(props("a", "b"))))).isEmpty();
        }

        @Test
        @DisplayName("the standard set judges the freeze first")
        void judgedFirst() {
            set(GovernanceAttribute.Kind.DR_FREEZE, "dr");
            CommitRule.Change elsewhere = change(table(Map.of()), table("s3://elsewhere", Map.of(), Map.of()));
            assertThat(Rules.standard(files, SKEW).getFirst().judge(elsewhere)).get()
                    .extracting(Rejection::status).isEqualTo(403);
        }
    }

    @Nested
    class SnapshotMinimumAge {

        private final long minuteAgo = NOW.minus(Duration.ofMinutes(1)).toEpochMilli();
        private final long hourAgo = NOW.minus(Duration.ofHours(1)).toEpochMilli();
        private final long weekAgo = NOW.minus(Duration.ofDays(7)).toEpochMilli();
        private final CommitRule rule = Rules.snapshotMinimumAge(Duration.ofMinutes(30), name -> switch (name) {
            case "trade-records" -> Optional.of(Duration.ofDays(3));
            case "brief" -> Optional.of(Duration.ofMinutes(5));
            default -> Optional.empty();
        });

        private Optional<Rejection> remove(long removedAt) {
            return rule.judge(change(table(ROOT, Map.of(), Map.of(1L, removedAt, 2L, NOW.toEpochMilli())),
                    table(ROOT, Map.of(), Map.of(2L, NOW.toEpochMilli()))));
        }

        @Test
        @DisplayName("a snapshot younger than the deployment's minimum stays, with or without a class, and governance is not read")
        void aSnapshotYoungerThanTheDeploymentMinimumStays() {
            assertThat(remove(minuteAgo)).get().extracting(Rejection::status).isEqualTo(403);
            assertThat(remove(minuteAgo).orElseThrow().message()).contains("PT30M", "deployment's minimum");
            assertThat(governanceReads).hasValue(0);
            set(GovernanceAttribute.Kind.RETENTION_CLASS, "brief");
            assertThat(remove(minuteAgo).orElseThrow().message()).contains("PT30M", "deployment's minimum");
            assertThat(remove(hourAgo)).isEmpty();
        }

        @Test
        void aSnapshotYoungerThanItsClassAllowsStays() {
            set(GovernanceAttribute.Kind.RETENTION_CLASS, "trade-records");
            assertThat(remove(hourAgo)).get().extracting(Rejection::status).isEqualTo(403);
            assertThat(remove(hourAgo).orElseThrow().message()).contains("PT72H", "trade-records");
        }

        @Test
        void anOlderSnapshotGoes() {
            set(GovernanceAttribute.Kind.RETENTION_CLASS, "trade-records");
            assertThat(remove(weekAgo)).isEmpty();
        }

        @Test
        void aClassWithoutMinimumOrNoClassAllowsRemoval() {
            assertThat(remove(hourAgo)).isEmpty();
            set(GovernanceAttribute.Kind.RETENTION_CLASS, "scratch");
            assertThat(remove(hourAgo)).isEmpty();
        }

        @Test
        @DisplayName("governance is read only when something is removed")
        void readOnlyWhenNeeded() {
            set(GovernanceAttribute.Kind.RETENTION_CLASS, "trade-records");
            assertThat(rule.judge(change(table(ROOT, Map.of(), Map.of(1L, hourAgo)),
                    table(ROOT, Map.of(), Map.of(1L, hourAgo, 2L, NOW.toEpochMilli()))))).isEmpty();
            assertThat(rule.judge(change(null, table(ROOT, Map.of(), Map.of(1L, hourAgo))))).isEmpty();
            assertThat(governanceReads).hasValue(0);
        }
    }

    @Nested
    class AssignedLocation {

        @Test
        void staysPut() {
            assertThat(Rules.assignedLocation(change(table(Map.of()), table(Map.of())))).isEmpty();
        }

        @Test
        void moved() {
            assertThat(Rules.assignedLocation(change(table(Map.of()), table(ROOT + "/x", Map.of(), Map.of()))))
                    .contains(Rejection.badRequest("Location is assigned by the catalog: " + ROOT + ", not " + ROOT + "/x"));
        }

        @Test
        void createdElsewhere() {
            assertThat(Rules.assignedLocation(change(null, table("s3://other/t", Map.of(), Map.of())))).isPresent();
        }
    }

    @Nested
    class PathProperties {

        @Test
        void insideIsFine() {
            for (String key : Rules.PATH_PROPERTIES) {
                assertThat(Rules.pathProperties(change(table(Map.of()), table(props(key, ROOT + "/data"))), files))
                        .isEmpty();
            }
        }

        @Test
        void outsideIsRefused() {
            for (String key : Rules.PATH_PROPERTIES) {
                assertThat(Rules.pathProperties(change(table(Map.of()), table(props(key, MemoryFiles.ROOT + "/x"))), files))
                        .contains(Rejection.badRequest("Property " + key + " must lie within the table location " + ROOT
                                + ", not " + MemoryFiles.ROOT + "/x"));
                assertThat(Rules.pathProperties(change(null, table(props(key, ROOT))), files)).isPresent();
            }
        }

        @Test
        @DisplayName("a value the change did not touch is not judged again")
        void untouchedIsNotJudged() {
            Map<String, String> old = props("write.data.path", "s3://legacy/data");

            assertThat(Rules.pathProperties(change(table(old), table(old)), files)).isEmpty();
        }

        @Test
        @DisplayName("other properties are not paths")
        void otherProperties() {
            assertThat(Rules.pathProperties(change(null, table(props("comment", "s3://anywhere"))), files)).isEmpty();
        }
    }

    @Nested
    class EncryptionKeyId {

        @Test
        void setAtCreate() {
            assertThat(Rules.encryptionKeyId(change(null, table(props(Rules.ENCRYPTION_KEY_ID, "k1"))))).isEmpty();
        }

        @Test
        void unchanged() {
            Map<String, String> p = props(Rules.ENCRYPTION_KEY_ID, "k1");
            assertThat(Rules.encryptionKeyId(change(table(p), table(props(Rules.ENCRYPTION_KEY_ID, "k1", "a", "b")))))
                    .isEmpty();
        }

        @Test
        void changedRemovedOrAdded() {
            Map<String, String> p = props(Rules.ENCRYPTION_KEY_ID, "k1");
            Optional<Rejection> refused = Optional.of(Rejection.badRequest(
                    "Property encryption.key-id is fixed when the table is created"));
            assertThat(Rules.encryptionKeyId(change(table(p), table(props(Rules.ENCRYPTION_KEY_ID, "k2")))))
                    .isEqualTo(refused);
            assertThat(Rules.encryptionKeyId(change(table(p), table(Map.of())))).isEqualTo(refused);
            assertThat(Rules.encryptionKeyId(change(table(Map.of()), table(p)))).isEqualTo(refused);
        }
    }

    @Nested
    class RetentionHold {

        private final Map<Long, Long> two = new LinkedHashMap<>(Map.of(1L, 1L, 2L, 2L));

        @Test
        void removalUnderHoldIsForbidden() {
            assertThat(Rules.retentionHold(change(table(ROOT, Map.of(), two), table(ROOT, Map.of(), Map.of(2L, 2L)), true)))
                    .contains(Rejection.forbidden("Table " + ID + " is under legal hold: its snapshots cannot be removed"));
        }

        @Test
        void removalWithoutHoldIsFine() {
            assertThat(Rules.retentionHold(change(table(ROOT, Map.of(), two), table(ROOT, Map.of(), Map.of(2L, 2L)), false)))
                    .isEmpty();
        }

        @Test
        @DisplayName("governance is read only when something is removed")
        void readOnlyWhenNeeded() {
            assertThat(Rules.retentionHold(change(table(ROOT, Map.of(), two),
                    table(ROOT, Map.of(), Map.of(1L, 1L, 2L, 2L, 3L, 3L)), true))).isEmpty();
            assertThat(Rules.retentionHold(change(null, table(ROOT, Map.of(), two), true))).isEmpty();
            assertThat(governanceReads).hasValue(0);
        }
    }

    @Nested
    class ClockSkew {

        private Optional<Rejection> judge(Map<Long, Long> before, Map<Long, Long> after) {
            return Rules.clockSkew(change(before == null ? null : table(ROOT, Map.of(), before),
                    table(ROOT, Map.of(), after)), SKEW);
        }

        @Test
        void atTheBoundIsFine() {
            assertThat(judge(Map.of(), Map.of(1L, NOW.plus(SKEW).toEpochMilli()))).isEmpty();
        }

        @Test
        void pastTheBoundIsRefused() {
            long ahead = NOW.plus(SKEW).toEpochMilli() + 1;
            assertThat(judge(Map.of(), Map.of(1L, ahead))).contains(Rejection.badRequest("Snapshot 1 is stamped "
                    + Instant.ofEpochMilli(ahead) + ", more than PT1M ahead of the catalog clock"));
            assertThat(judge(null, Map.of(1L, ahead))).isPresent();
        }

        @Test
        @DisplayName("a snapshot the table already had is not judged again")
        void existingSnapshots() {
            long ahead = NOW.plus(Duration.ofHours(1)).toEpochMilli();
            assertThat(judge(Map.of(1L, ahead), Map.of(1L, ahead, 2L, NOW.toEpochMilli()))).isEmpty();
        }

        @Test
        void viewsHaveNoSnapshots() {
            ViewFacts view = new ViewFacts(ID.toString(), 1, ROOT, Map.of(), 0, 1);
            assertThat(Rules.clockSkew(change(null, view), SKEW)).isEmpty();
        }
    }
}

package fidice.catalog.core.commit;

import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.core.commit.CommitRule.Rejection;
import fidice.catalog.iceberg.Facts;
import fidice.catalog.iceberg.Facts.TableFacts;
import fidice.catalog.iceberg.Facts.ViewFacts;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.Name;
import java.time.Duration;
import java.time.Instant;
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
        };
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
    @DisplayName("the standard set holds the five D11 rules, in order")
    void standard() {
        assertThat(Rules.standard(files, SKEW)).hasSize(5);
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

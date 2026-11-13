package keel.catalog.core.commit;

import static keel.catalog.core.commit.Harness.ALICE;
import static keel.catalog.core.commit.Harness.BOB;
import static keel.catalog.core.commit.Requests.addSnapshot;
import static keel.catalog.core.commit.Requests.assertMain;
import static keel.catalog.core.commit.Requests.assertUuid;
import static keel.catalog.core.commit.Requests.setMain;
import static keel.catalog.core.commit.Requests.setProperties;
import static keel.catalog.core.commit.Requests.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import keel.catalog.core.commit.CommitEngine.TableChange;
import keel.catalog.core.commit.CommitReply.Applied;
import keel.catalog.core.commit.CommitReply.Failure;
import keel.catalog.core.commit.CommitReply.How;
import keel.catalog.core.commit.CommitReply.ServerError;
import keel.catalog.core.commit.CommitReply.Success;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitOutcomeUnknownException;
import keel.catalog.store.CommitResult;
import keel.catalog.store.GovernanceAttribute;
import keel.catalog.store.JournalRecord;
import keel.catalog.store.Pointer;
import keel.catalog.store.PointerHold;
import keel.catalog.store.StoreException;
import keel.catalog.store.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.iceberg.TableMetadataParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Multi-table transactions (D15) on PostgreSQL, through the storage interface only. */
class TransactionTest {

    private final Harness h = new Harness();

    private TableChange change(String table, String request) {
        return new TableChange(h.name(table), request);
    }

    /** The request body an idempotency key binds to; the engine takes the changes already parsed. */
    private static String body(TableChange... changes) {
        return Arrays.stream(changes).map(c -> c.name().qualified() + "=" + c.json()).collect(Collectors.joining("\n"));
    }

    private CommitReply transact(TableChange... changes) {
        return h.engine().commitTransaction(List.of(changes), body(changes), ALICE, Optional.empty());
    }

    private CommitReply transact(String key, TableChange... changes) {
        return h.engine().commitTransaction(List.of(changes), body(changes), ALICE, Optional.of(key));
    }

    private String set(String table, String property, String value) {
        return update(assertUuid(h.object(table).id().toString()), setProperties(Map.of(property, value)));
    }

    /** Changes nothing: the builder records no change for the schema already current. */
    private String noOp(String table) {
        return update(assertUuid(h.object(table).id().toString()), "{\"action\":\"set-current-schema\",\"schema-id\":0}");
    }

    private Map<String, String> properties(String table) {
        return TableMetadataParser.fromJson(h.files.read(h.pointer(table).metadataLocation(), "").json()).properties();
    }

    private static CommitOutcomeUnknownException unknown(Object[] args) {
        return new CommitOutcomeUnknownException(((CommitBatch) args[0]).commitId(), null);
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    @Nested
    class Applying {

        @Test
        @DisplayName("every table's change is committed in one store commit, under one commit id, each with its journal entry and event")
        void committed() {
            h.createTable("Orders");
            h.createTable("Lines");

            CommitReply reply = transact(change("Orders", set("Orders", "a", "1")), change("Lines", set("Lines", "a", "2")));

            assertThat(reply).isEqualTo(new Applied(How.COMMITTED));
            assertThat(h.faults.calls("commit")).isEqualTo(1);
            assertThat(h.pointer("Orders").version()).isEqualTo(2);
            assertThat(h.pointer("Lines").version()).isEqualTo(2);
            assertThat(properties("Orders")).containsEntry("a", "1");
            assertThat(properties("Lines")).containsEntry("a", "2");
            JournalRecord orders = h.journal("Orders").getLast();
            JournalRecord lines = h.journal("Lines").getLast();
            assertThat(orders.commitId()).isEqualTo(lines.commitId());
            assertThat(orders.entry().updatesJson()).contains("\"a\":\"1\"");
            assertThat(lines.entry().updatesJson()).contains("\"a\":\"2\"");
            assertThat(orders.entry().requirementsJson()).contains("assert-table-uuid");
            assertThat(h.events.subList(2, 4)).extracting(e -> e.object().id())
                    .containsExactly(h.object("Orders").id(), h.object("Lines").id());
            assertThat(h.events.subList(2, 4)).extracting(CommitEvents.Commit::commitId)
                    .containsOnly(orders.commitId());
        }

        @Test
        @DisplayName("a failed requirement on any table refuses the whole transaction with 409, naming the table, and writes no file")
        void requirementFailed() {
            h.createTable("Orders");
            h.createTable("Lines");
            int writes = h.files.writes.get();

            CommitReply reply = transact(change("Orders", set("Orders", "a", "1")),
                    change("Lines", update(assertUuid(UUID.randomUUID().toString()), setProperties(Map.of("a", "2")))));

            assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> {
                assertThat(f.status()).isEqualTo(409);
                assertThat(f.type()).isEqualTo("CommitFailedException");
                assertThat(f.message()).startsWith("Lines: ");
            });
            assertThat(h.files.writes.get()).isEqualTo(writes);
            assertThat(h.faults.calls("commit")).isZero();
            assertThat(h.pointer("Orders").version()).isEqualTo(1);
            assertThat(h.pointer("Lines").version()).isEqualTo(1);
        }

        @Test
        @DisplayName("a table that does not exist refuses the whole transaction with 404")
        void missingTable() {
            h.createTable("Orders");

            assertThat(transact(change("Orders", set("Orders", "a", "1")),
                    change("Ghost", update(assertUuid(UUID.randomUUID().toString()), setProperties(Map.of("a", "1"))))))
                    .isEqualTo(new Failure(404, "NoSuchTableException", "Table does not exist: Ghost"));
            assertThat(h.pointer("Orders").version()).isEqualTo(1);
        }

        @Test
        @DisplayName("a view is not a table: naming one is 404")
        void viewIsNoTable() {
            h.createTable("Orders");
            h.createView("Revenue");

            assertThat(transact(change("Orders", set("Orders", "a", "1")), change("Revenue", set("Orders", "a", "1"))))
                    .isEqualTo(new Failure(404, "NoSuchTableException", "Table does not exist: Revenue"));
            assertThat(h.pointer("Orders").version()).isEqualTo(1);
        }

        @Test
        @DisplayName("a rule refusing one table refuses them all, with no file written")
        void ruleRefusesOne() {
            h.createTable("Orders");
            h.createTable("Lines");
            h.attribute(h.object("Lines").securable(), GovernanceAttribute.Kind.DR_FREEZE, "reconcile");
            int writes = h.files.writes.get();

            assertThat(transact(change("Orders", set("Orders", "a", "1")), change("Lines", set("Lines", "a", "2"))))
                    .isInstanceOfSatisfying(Failure.class, f -> {
                        assertThat(f.status()).isEqualTo(403);
                        assertThat(f.message()).startsWith("Lines: ");
                    });
            assertThat(h.files.writes.get()).isEqualTo(writes);
            assertThat(h.pointer("Orders").version()).isEqualTo(1);
        }

        @Test
        @DisplayName("a transaction that changes nothing writes nothing")
        void nothingChanges() {
            h.createTable("Orders");
            h.createTable("Lines");
            int writes = h.files.writes.get();

            assertThat(transact(change("Orders", noOp("Orders")), change("Lines", noOp("Lines"))))
                    .isEqualTo(new Applied(How.UNCHANGED));
            assertThat(h.files.writes.get()).isEqualTo(writes);
            assertThat(h.faults.calls("commit")).isZero();
        }

        @Test
        @DisplayName("a table whose change changes nothing is held at the pointer it was checked against, not written, and the others commit")
        void someChange() {
            h.createTable("Orders");
            h.createTable("Lines");
            Pointer orders = h.pointer("Orders");
            h.faults.once("commit", (args, proceed) -> {
                CommitBatch batch = (CommitBatch) args[0];
                assertThat(batch.swaps()).singleElement()
                        .satisfies(s -> assertThat(s.key()).isEqualTo(h.object("Lines").mainKey()));
                assertThat(batch.holds()).containsExactly(new PointerHold(h.object("Orders").mainKey(), orders));
                return proceed.call();
            });

            assertThat(transact(change("Orders", noOp("Orders")), change("Lines", set("Lines", "a", "2"))))
                    .isEqualTo(new Applied(How.COMMITTED));
            assertThat(h.pointer("Orders").version()).isEqualTo(1);
            assertThat(h.pointer("Lines").version()).isEqualTo(2);
        }

        @Test
        @DisplayName("an empty transaction is refused before it runs")
        void empty() {
            assertThatThrownBy(() -> h.engine().commitTransaction(List.of(), "{}", ALICE, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Retrying {

        @Test
        @DisplayName("a swap lost to a concurrent commit on one table retries the whole transaction on the new metadata; the lost attempt applied nothing")
        void lostSwapIsRetried() {
            h.createTable("Orders");
            h.createTable("Lines");
            h.faults.once("commit", (args, proceed) -> {
                assertThat(h.engine(h.faults.real).commitTable(h.name("Lines"), set("Lines", "b", "2"), BOB,
                        Optional.empty())).isInstanceOf(Success.class);
                return proceed.call();
            });

            assertThat(transact(change("Orders", set("Orders", "a", "1")), change("Lines", set("Lines", "a", "2"))))
                    .isEqualTo(new Applied(How.COMMITTED));

            assertThat(h.faults.calls("commit")).isEqualTo(2);
            assertThat(h.waits).hasSize(1);
            assertThat(h.pointer("Orders").version()).isEqualTo(2);
            assertThat(h.journal("Orders")).hasSize(2);
            assertThat(h.pointer("Lines").version()).isEqualTo(3);
            assertThat(properties("Lines")).containsEntry("a", "2").containsEntry("b", "2");
        }

        @Test
        @DisplayName("a transaction that loses the race with one table's drop is 404 naming it, and no table changes")
        void dropWinsTheRace() {
            h.createTable("Orders");
            h.createTable("Lines");
            CatalogObject lines = h.object("Lines");
            Pointer orders = h.pointer("Orders");
            h.faults.once("commit", (args, proceed) -> {
                assertThat(h.faults.real.dropObject(lines.id(), Instant.now().plus(Duration.ofDays(7)),
                        Harness.change())).isEqualTo(WriteResult.APPLIED);
                return proceed.call();
            });

            assertThat(transact(change("Orders", set("Orders", "a", "1")), change("Lines", set("Lines", "a", "2"))))
                    .isInstanceOfSatisfying(Failure.class, f -> {
                        assertThat(f.status()).isEqualTo(404);
                        assertThat(f.message()).isEqualTo("Table does not exist: " + h.name("Lines").qualified());
                    });

            assertThat(h.pointer("Orders")).isEqualTo(orders);
            assertThat(h.faults.real.readPointer(lines.mainKey()).orElseThrow().version()).isEqualTo(1);
        }

        @Test
        @DisplayName("a retry that finds a requirement no longer holds is 409, and no table changes")
        void retryFindsTheValueStale() {
            h.createTable("Orders");
            h.createTable("Lines", Map.of("format-version", "2"));
            long now = h.clock.millis();
            h.faults.once("commit", (args, proceed) -> {
                assertThat(h.engine(h.faults.real).commitTable(h.name("Lines"),
                        update(assertMain(null), addSnapshot(7, null, 1, now), setMain(7)), BOB, Optional.empty()))
                        .isInstanceOf(Success.class);
                return proceed.call();
            });

            CommitReply reply = transact(change("Orders", set("Orders", "a", "1")),
                    change("Lines", update(assertMain(null), addSnapshot(8, null, 1, now), setMain(8))));

            assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> {
                assertThat(f.status()).isEqualTo(409);
                assertThat(f.message()).startsWith("Lines: ");
            });
            assertThat(h.faults.calls("commit")).isEqualTo(1);
            assertThat(h.pointer("Orders").version()).isEqualTo(1);
            assertThat(h.pointer("Lines").version()).isEqualTo(2);
        }

        /**
         * The write-skew race: the guard's requirement held when the attempt read it, and the guarded table
         * moves before the store commit. Held, the guard makes the store refuse the batch; the retry finds
         * the requirement false.
         */
        @Test
        @DisplayName("a read guard whose table moves before the swap is not passed: the retry finds the requirement false, 409, and no table changes")
        void guardMovedBeforeTheSwap() {
            h.createTable("Orders");
            h.createTable("Lines", Map.of("format-version", "2"));
            long now = h.clock.millis();
            h.faults.once("commit", (args, proceed) -> {
                assertThat(h.engine(h.faults.real).commitTable(h.name("Lines"),
                        update(assertMain(null), addSnapshot(7, null, 1, now), setMain(7)), BOB, Optional.empty()))
                        .isInstanceOf(Success.class);
                return proceed.call();
            });

            CommitReply reply = transact(change("Orders", set("Orders", "a", "1")), change("Lines", update(assertMain(null))));

            assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> {
                assertThat(f.status()).isEqualTo(409);
                assertThat(f.type()).isEqualTo("CommitFailedException");
                assertThat(f.message()).startsWith("Lines: ");
            });
            assertThat(h.faults.calls("commit")).isEqualTo(1);
            assertThat(h.waits).hasSize(1);
            assertThat(h.pointer("Orders").version()).isEqualTo(1);
            assertThat(h.journal("Orders")).hasSize(1);
            assertThat(h.pointer("Lines").version()).isEqualTo(2);
        }

        @Test
        @DisplayName("a read guard whose table moves in a way its requirement allows is retried, and the transaction commits")
        void guardMovedWithinItsRequirement() {
            h.createTable("Orders");
            h.createTable("Lines", Map.of("format-version", "2"));
            h.faults.once("commit", (args, proceed) -> {
                assertThat(h.engine(h.faults.real).commitTable(h.name("Lines"), set("Lines", "b", "2"), BOB,
                        Optional.empty())).isInstanceOf(Success.class);
                return proceed.call();
            });

            assertThat(transact(change("Orders", set("Orders", "a", "1")), change("Lines", update(assertMain(null)))))
                    .isEqualTo(new Applied(How.COMMITTED));

            assertThat(h.faults.calls("commit")).isEqualTo(2);
            assertThat(h.waits).hasSize(1);
            assertThat(h.pointer("Orders").version()).isEqualTo(2);
            assertThat(h.journal("Orders")).hasSize(2);
            assertThat(h.pointer("Lines").version()).isEqualTo(2);
            assertThat(h.journal("Lines")).hasSize(2);
        }

        @Test
        @DisplayName("retries stop at the attempt bound, then 409, with no table changed")
        void retriesStopAtTheAttemptBound() {
            h.createTable("Orders");
            h.createTable("Lines");
            h.faults.always("commit", (args, proceed) ->
                    new CommitResult.Conflict(((CommitBatch) args[0]).swaps().getFirst().key(), Optional.empty()));

            assertThat(transact(change("Orders", set("Orders", "a", "1")), change("Lines", set("Lines", "a", "2"))))
                    .isInstanceOfSatisfying(Failure.class, f -> {
                        assertThat(f.status()).isEqualTo(409);
                        assertThat(f.type()).isEqualTo("CommitFailedException");
                    });
            assertThat(h.faults.calls("commit")).isEqualTo(4);
            assertThat(h.pointer("Orders").version()).isEqualTo(1);
            assertThat(h.pointer("Lines").version()).isEqualTo(1);
        }
    }

    @Nested
    class UnknownOutcomes {

        @Test
        @DisplayName("a transaction whose outcome was lost but which landed is applied, once")
        void landed() {
            h.createTable("Orders");
            h.createTable("Lines");
            h.faults.once("commit", (args, proceed) -> {
                proceed.call();
                throw unknown(args);
            });

            assertThat(transact(change("Orders", set("Orders", "a", "1")), change("Lines", set("Lines", "a", "2"))))
                    .isEqualTo(new Applied(How.COMMITTED));
            assertThat(h.faults.calls("commit")).isEqualTo(1);
            assertThat(h.faults.calls("findCommit")).isEqualTo(1);
            assertThat(h.pointer("Orders").version()).isEqualTo(2);
            assertThat(h.pointer("Lines").version()).isEqualTo(2);
        }

        @Test
        @DisplayName("a transaction whose outcome was lost and which did not land is retried, and lands once")
        void didNotLand() {
            h.createTable("Orders");
            h.createTable("Lines");
            h.faults.once("commit", (args, proceed) -> {
                throw unknown(args);
            });

            assertThat(transact(change("Orders", set("Orders", "a", "1")), change("Lines", set("Lines", "a", "2"))))
                    .isEqualTo(new Applied(How.COMMITTED));
            assertThat(h.faults.calls("commit")).isEqualTo(2);
            assertThat(h.journal("Orders")).hasSize(2);
            assertThat(h.journal("Lines")).hasSize(2);
        }

        @Test
        @DisplayName("a transaction whose outcome cannot be decided is 500 CommitStateUnknown")
        void undecidable() {
            h.createTable("Orders");
            h.createTable("Lines");
            h.faults.once("commit", (args, proceed) -> {
                throw unknown(args);
            });
            h.faults.once("findCommit", (args, proceed) -> {
                throw new StoreException("standby unreachable", null);
            });

            assertThat(transact(change("Orders", set("Orders", "a", "1")), change("Lines", set("Lines", "a", "2"))))
                    .isInstanceOfSatisfying(ServerError.class,
                            e -> assertThat(e.type()).isEqualTo("CommitStateUnknownException"));
        }
    }

    @Nested
    class Idempotency {

        @Test
        @DisplayName("a retried transaction is replayed, not applied again (D10)")
        void replayed() {
            h.createTable("Orders");
            h.createTable("Lines");
            String key = key();
            TableChange orders = change("Orders", set("Orders", "a", "1"));
            TableChange lines = change("Lines", set("Lines", "a", "2"));

            assertThat(transact(key, orders, lines)).isEqualTo(new Applied(How.COMMITTED));
            assertThat(transact(key, orders, lines)).isEqualTo(new Applied(How.REPLAYED));

            assertThat(h.faults.calls("commit")).isEqualTo(1);
            assertThat(h.journal("Orders")).hasSize(2);
            assertThat(h.journal("Lines")).hasSize(2);
        }

        @Test
        @DisplayName("a key reused for a different transaction is 400, and it does not run")
        void differentRequest() {
            h.createTable("Orders");
            h.createTable("Lines");
            String key = key();
            transact(key, change("Orders", set("Orders", "a", "1")), change("Lines", set("Lines", "a", "2")));

            assertThat(transact(key, change("Orders", set("Orders", "a", "3")), change("Lines", set("Lines", "a", "2"))))
                    .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(400));
            assertThat(h.pointer("Orders").version()).isEqualTo(2);
        }

        @Test
        @DisplayName("a definite 4xx and a no-op are replayed as they were answered")
        void outcomesReplayed() {
            h.createTable("Orders");
            h.createTable("Lines");
            String refusedKey = key();
            String noOpKey = key();
            TableChange stale = change("Lines", update(assertUuid(UUID.randomUUID().toString()),
                    setProperties(Map.of("a", "2"))));
            CommitReply refused = transact(refusedKey, change("Orders", set("Orders", "a", "1")), stale);
            assertThat(refused).isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(409));
            TableChange[] nothing = {change("Orders", noOp("Orders")), change("Lines", noOp("Lines"))};
            assertThat(transact(noOpKey, nothing)).isEqualTo(new Applied(How.UNCHANGED));

            assertThat(transact(refusedKey, change("Orders", set("Orders", "a", "1")), stale)).isEqualTo(refused);
            assertThat(transact(noOpKey, nothing)).isEqualTo(new Applied(How.REPLAYED));
            assertThat(h.faults.calls("commit")).isZero();
        }
    }
}

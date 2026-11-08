package fidice.catalog.core.commit;

import static fidice.catalog.core.commit.Harness.ALICE;
import static fidice.catalog.core.commit.Harness.BOB;
import static fidice.catalog.core.commit.Requests.addColumn;
import static fidice.catalog.core.commit.Requests.addSnapshot;
import static fidice.catalog.core.commit.Requests.assertMain;
import static fidice.catalog.core.commit.Requests.assertSchema;
import static fidice.catalog.core.commit.Requests.assertUuid;
import static fidice.catalog.core.commit.Requests.assertViewUuid;
import static fidice.catalog.core.commit.Requests.setMain;
import static fidice.catalog.core.commit.Requests.setProperties;
import static fidice.catalog.core.commit.Requests.update;
import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.core.commit.CommitReply.Failure;
import fidice.catalog.core.commit.CommitReply.How;
import fidice.catalog.core.commit.CommitReply.ServerError;
import fidice.catalog.core.commit.CommitReply.Success;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.JournalRecord;
import fidice.catalog.store.Name;
import fidice.catalog.store.ObjectCreation;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.StoreException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.view.ViewMetadataParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The commit loop on PostgreSQL, through the storage interface only. */
class CommitEngineTest {

    private final Harness h = new Harness();

    private CommitReply commit(String table, String request) {
        return h.engine().commitTable(h.name(table), request, ALICE, Optional.empty());
    }

    private CommitReply commit(String table, String request, String key) {
        return h.engine().commitTable(h.name(table), request, ALICE, Optional.of(key));
    }

    private static TableMetadata metadata(CommitReply reply) {
        return TableMetadataParser.fromJson(((Success) reply).metadataJson());
    }

    private String setA(String table, String value) {
        return update(assertUuid(h.object(table).id().toString()), setProperties(Map.of("a", value)));
    }

    /**
     * A request the builder records no change for. Setting a property to the value it already has is
     * not one: iceberg-core records it and writes a new file, and so does the reference handler.
     */
    private String noOp() {
        return update(assertUuid(h.object("Orders").id().toString()),
                "{\"action\":\"set-current-schema\",\"schema-id\":0}");
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    @Nested
    class Creating {

        @Test
        @DisplayName("a new table is committed as version 1 at the location the catalog assigns, not the one asked for")
        void createTable() {
            CommitReply reply = h.engine().createTable(h.name("Orders"),
                    Requests.createTable("Orders", Map.of("owner", "sales")), ALICE, Optional.empty());

            CatalogObject object = h.object("orders");
            assertThat(reply).isInstanceOfSatisfying(Success.class, s -> {
                assertThat(s.how()).isEqualTo(How.COMMITTED);
                assertThat(s.objectId()).isEqualTo(object.id());
            });
            assertThat(object.name()).isEqualTo(new Name("orders", "Orders"));
            assertThat(object.locationRoot()).isEqualTo(MemoryFiles.ROOT + "/" + object.id());
            TableMetadata metadata = metadata(reply);
            assertThat(metadata.location()).isEqualTo(object.locationRoot());
            assertThat(metadata.uuid()).isEqualTo(object.id().toString());
            assertThat(metadata.properties()).containsEntry("owner", "sales");
            Pointer pointer = h.pointer("orders");
            assertThat(pointer.version()).isEqualTo(1);
            assertThat(pointer.metadataLocation()).isEqualTo(((Success) reply).metadataLocation())
                    .startsWith(object.locationRoot() + "/metadata/00001-");
            assertThat(pointer.sha256()).isEqualTo(((Success) reply).metadataSha256());
            JournalRecord journal = h.journal("orders").getFirst();
            assertThat(journal.entry().principal()).isEqualTo("alice");
            assertThat(journal.entry().engine()).isEqualTo("spark");
            assertThat(journal.entry().requirementsJson()).isEqualTo("[]");
            assertThat(journal.entry().updatesJson()).contains("\"Orders\"");
            assertThat(h.events).singleElement().satisfies(e -> {
                assertThat(e.before()).isEmpty();
                assertThat(e.after()).isEqualTo(pointer);
                assertThat(e.object().id()).isEqualTo(object.id());
            });
        }

        @Test
        @DisplayName("a taken name is 409 AlreadyExists, and no file is written")
        void nameTaken() {
            h.createTable("Orders");
            int writes = h.files.writes.get();

            CommitReply reply = h.engine().createTable(h.name("ORDERS"), Requests.createTable("ORDERS", Map.of()),
                    ALICE, Optional.empty());

            assertThat(reply).isEqualTo(new Failure(409, "AlreadyExistsException", "Table already exists: ORDERS"));
            assertThat(h.files.writes.get()).isEqualTo(writes);
        }

        @Test
        @DisplayName("a view holding the name refuses a table, as iceberg-core does")
        void viewHoldsTheName() {
            h.createView("Orders");

            assertThat(h.engine().createTable(h.name("orders"), Requests.createTable("orders", Map.of()), ALICE,
                    Optional.empty()))
                    .isEqualTo(new Failure(409, "AlreadyExistsException", "View with same name already exists: orders"));
        }

        @Test
        @DisplayName("a create that loses the name to a concurrent create is 409")
        void lostRace() {
            h.faults.once("createObject", (args, proceed) -> {
                h.createTable("Orders");
                return proceed.call();
            });

            assertThat(h.engine().createTable(h.name("Orders"), Requests.createTable("Orders", Map.of()), ALICE,
                    Optional.empty()))
                    .isEqualTo(new Failure(409, "AlreadyExistsException", "Table already exists: Orders"));
        }

        @Test
        @DisplayName("a create in a namespace that does not exist is 404")
        void missingNamespace() {
            UUID gone = UUID.randomUUID();
            ObjectName name = new ObjectName(h.catalogId, gone, new Name("orders", "orders"));

            assertThat(h.engine().createTable(name, Requests.createTable("orders", Map.of()), ALICE, Optional.empty()))
                    .isEqualTo(new Failure(404, "NoSuchNamespaceException", "Namespace does not exist: " + gone));
        }

        @Test
        @DisplayName("a malformed create is 400 and writes nothing")
        void malformed() {
            CommitReply reply = h.engine().createTable(h.name("Orders"), "{\"name\":\"Orders\"}", ALICE, Optional.empty());

            assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(400));
            assertThat(h.files.count()).isZero();
        }

        @Test
        @DisplayName("a new view is committed at the location the catalog assigns")
        void createView() {
            Success reply = h.createView("Revenue");

            CatalogObject object = h.object("revenue");
            assertThat(object.kind()).isEqualTo(CatalogObject.Kind.VIEW);
            assertThat(ViewMetadataParser.fromJson(reply.metadataJson()).location()).isEqualTo(object.locationRoot());
            assertThat(h.pointer("revenue").version()).isEqualTo(1);
        }

        @Test
        @DisplayName("a create whose outcome was lost but which committed is a success, written once")
        void unknownButCommitted() {
            h.faults.once("createObject", (args, proceed) -> {
                proceed.call();
                throw new CommitOutcomeUnknownException(((ObjectCreation) args[0]).firstCommit().commitId(), null);
            });

            CommitReply reply = h.engine().createTable(h.name("Orders"), Requests.createTable("Orders", Map.of()),
                    ALICE, Optional.empty());

            assertThat(reply).isInstanceOfSatisfying(Success.class, s -> assertThat(s.how()).isEqualTo(How.COMMITTED));
            assertThat(h.faults.calls("createObject")).isEqualTo(1);
            assertThat(h.faults.calls("findCommit")).isEqualTo(1);
            assertThat(h.journal("orders")).hasSize(1);
        }

        @Test
        @DisplayName("a create whose outcome was lost and which did not commit is sent again")
        void unknownAndNotCommitted() {
            h.faults.once("createObject", (args, proceed) -> {
                throw new CommitOutcomeUnknownException(((ObjectCreation) args[0]).firstCommit().commitId(), null);
            });

            CommitReply reply = h.engine().createTable(h.name("Orders"), Requests.createTable("Orders", Map.of()),
                    ALICE, Optional.empty());

            assertThat(reply).isInstanceOf(Success.class);
            assertThat(h.faults.calls("createObject")).isEqualTo(2);
            assertThat(h.journal("orders")).hasSize(1);
        }
    }

    @Nested
    class Committing {

        @Test
        @DisplayName("a change is committed as the next version, with its journal entry and event")
        void committed() {
            Success created = h.createTable("Orders");

            CommitReply reply = commit("Orders", setA("Orders", "1"));

            assertThat(reply).isInstanceOfSatisfying(Success.class, s -> assertThat(s.how()).isEqualTo(How.COMMITTED));
            TableMetadata metadata = metadata(reply);
            assertThat(metadata.properties()).containsEntry("a", "1");
            assertThat(metadata.previousFiles()).extracting(TableMetadata.MetadataLogEntry::file)
                    .containsExactly(created.metadataLocation());
            Pointer pointer = h.pointer("orders");
            assertThat(pointer.version()).isEqualTo(2);
            assertThat(pointer.metadataLocation()).isEqualTo(((Success) reply).metadataLocation());
            List<JournalRecord> journal = h.journal("orders");
            assertThat(journal).hasSize(2);
            assertThat(journal.get(1).entry().requirementsJson()).contains("assert-table-uuid");
            assertThat(journal.get(1).entry().updatesJson()).contains("set-properties");
            assertThat(journal.get(1).oldLocation()).contains(created.metadataLocation());
            assertThat(h.events.getLast().before().map(Pointer::version)).contains(1L);
        }

        @Test
        @DisplayName("a request that changes nothing writes nothing and answers with the current metadata (D3)")
        void noOpIsSkipped() {
            Success created = h.createTable("Orders");
            int writes = h.files.writes.get();

            CommitReply reply = commit("Orders", noOp());

            assertThat(reply).isEqualTo(new Success(created.objectId(), created.metadataLocation(),
                    created.metadataSha256(), created.metadataJson(), How.UNCHANGED));
            assertThat(h.files.writes.get()).isEqualTo(writes);
            assertThat(h.faults.calls("commit")).isZero();
            assertThat(h.pointer("orders").version()).isEqualTo(1);
        }

        @Test
        @DisplayName("a failed requirement is 409 and writes nothing")
        void requirementFailed() {
            h.createTable("Orders");
            int writes = h.files.writes.get();

            CommitReply reply = commit("Orders", update(assertUuid(UUID.randomUUID().toString()),
                    setProperties(Map.of("a", "1"))));

            assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> {
                assertThat(f.status()).isEqualTo(409);
                assertThat(f.type()).isEqualTo("CommitFailedException");
            });
            assertThat(h.files.writes.get()).isEqualTo(writes);
            assertThat(h.faults.calls("commit")).isZero();
        }

        @Test
        @DisplayName("a request built on a value the table has moved past is 409 (stale value)")
        void staleValue() {
            h.createTable("Orders", Map.of("format-version", "2"));
            long now = h.clock.millis();
            assertThat(commit("Orders", update(assertMain(null), addSnapshot(1, null, 1, now), setMain(1))))
                    .isInstanceOf(Success.class);
            int writes = h.files.writes.get();

            CommitReply reply = commit("Orders", update(assertMain(null), addSnapshot(2, null, 1, now), setMain(2)));

            assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> {
                assertThat(f.status()).isEqualTo(409);
                assertThat(f.type()).isEqualTo("CommitFailedException");
            });
            assertThat(h.files.writes.get()).isEqualTo(writes);
            assertThat(h.pointer("orders").version()).isEqualTo(2);
        }

        @Test
        @DisplayName("an update the builder refuses is 400")
        void badUpdate() {
            h.createTable("Orders");

            CommitReply reply = commit("Orders", update(assertUuid(h.object("Orders").id().toString()),
                    "{\"action\":\"set-current-schema\",\"schema-id\":42}"));

            assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> {
                assertThat(f.status()).isEqualTo(400);
                assertThat(f.type()).isEqualTo("BadRequestException");
            });
        }

        @Test
        @DisplayName("a commit to a table that does not exist is 404")
        void missingTable() {
            assertThat(commit("Ghost", update(assertUuid(UUID.randomUUID().toString()), setProperties(Map.of("a", "1")))))
                    .isEqualTo(new Failure(404, "NoSuchTableException", "Table does not exist: Ghost"));
        }

        @Test
        @DisplayName("a view commit naming a table is 404 NoSuchView")
        void viewCommitOnATable() {
            h.createTable("Orders");

            assertThat(h.engine().commitView(h.name("Orders"), update(assertViewUuid(UUID.randomUUID().toString()),
                    setProperties(Map.of("a", "1"))), ALICE, Optional.empty()))
                    .isEqualTo(new Failure(404, "NoSuchViewException", "View does not exist: Orders"));
        }

        @Test
        @DisplayName("a view is replaced as its next version")
        void viewReplaced() {
            Success created = h.createView("Revenue");

            CommitReply reply = h.engine().commitView(h.name("Revenue"), update(assertViewUuid(
                    h.object("Revenue").id().toString()), setProperties(Map.of("comment", "monthly"))), ALICE,
                    Optional.empty());

            assertThat(reply).isInstanceOfSatisfying(Success.class, s -> {
                assertThat(s.how()).isEqualTo(How.COMMITTED);
                assertThat(ViewMetadataParser.fromJson(s.metadataJson()).properties()).containsEntry("comment", "monthly");
            });
            assertThat(h.pointer("Revenue").version()).isEqualTo(2);
            assertThat(created.objectId()).isEqualTo(h.object("Revenue").id());
        }

        @Test
        @DisplayName("a staged create is committed under the UUID the staging response gave it")
        void stagedCreate() {
            UUID id = UUID.randomUUID();

            CommitReply reply = commit("Staged", Requests.stagedCreate(id.toString(), h.files.newLocation(id)));

            assertThat(reply).isInstanceOfSatisfying(Success.class, s -> assertThat(s.how()).isEqualTo(How.COMMITTED));
            CatalogObject object = h.object("staged");
            assertThat(object.id()).isEqualTo(id);
            assertThat(object.locationRoot()).isEqualTo(h.files.newLocation(id));
            assertThat(metadata(reply).properties()).containsEntry("created-by", "staging");
            assertThat(h.journal("staged").getFirst().entry().requirementsJson()).contains("assert-create");
        }

        @Test
        @DisplayName("a staged create placed anywhere but its assigned location is 400")
        void stagedCreateElsewhere() {
            UUID id = UUID.randomUUID();

            CommitReply reply = commit("Staged", Requests.stagedCreate(id.toString(), "s3://lake/warehouse/mine"));

            assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(400));
            assertThat(h.faults.real.readObject(id)).isEmpty();
        }

        @Test
        @DisplayName("a staged create of a taken name is 409")
        void stagedCreateNameTaken() {
            h.createTable("Staged");
            UUID id = UUID.randomUUID();

            assertThat(commit("Staged", Requests.stagedCreate(id.toString(), h.files.newLocation(id))))
                    .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(409));
        }

        @Test
        @DisplayName("a staged create that reuses another object's id is 409")
        void stagedCreateIdTaken() {
            UUID taken = h.createTable("Orders").objectId();

            assertThat(commit("Staged", Requests.stagedCreate(taken.toString(), h.files.newLocation(taken))))
                    .isEqualTo(new Failure(409, "CommitFailedException", "Table UUID " + taken + " is already in use"));
        }
    }

    @Nested
    class Retrying {

        private CommitResult.Conflict conflict(Object[] args) {
            return new CommitResult.Conflict(((CommitBatch) args[0]).swaps().getFirst().key(), Optional.empty());
        }

        @Test
        @DisplayName("a swap lost to a concurrent commit is retried on the new metadata, after a backoff")
        void lostSwapIsRetried() {
            h.createTable("Orders");
            String other = update(assertUuid(h.object("Orders").id().toString()), setProperties(Map.of("b", "2")));
            h.faults.once("commit", (args, proceed) -> {
                assertThat(h.engine(h.faults.real).commitTable(h.name("Orders"), other, BOB, Optional.empty()))
                        .isInstanceOf(Success.class);
                return proceed.call();
            });

            CommitReply reply = commit("Orders", setA("Orders", "1"));

            assertThat(reply).isInstanceOf(Success.class);
            assertThat(metadata(reply).properties()).containsEntry("a", "1").containsEntry("b", "2");
            assertThat(h.pointer("orders").version()).isEqualTo(3);
            assertThat(h.faults.calls("commit")).isEqualTo(2);
            assertThat(h.waits).singleElement().satisfies(w ->
                    assertThat(w).isBetween(Duration.ofMillis(100), Duration.ofMillis(110)));
        }

        @Test
        @DisplayName("a retry that finds its requirement no longer holds is 409, not a blind overwrite")
        void retryFindsTheValueStale() {
            h.createTable("Orders", Map.of("format-version", "2"));
            long now = h.clock.millis();
            h.faults.once("commit", (args, proceed) -> {
                assertThat(h.engine(h.faults.real).commitTable(h.name("Orders"),
                        update(assertMain(null), addSnapshot(7, null, 1, now), setMain(7)), BOB, Optional.empty()))
                        .isInstanceOf(Success.class);
                return proceed.call();
            });

            CommitReply reply = commit("Orders", update(assertMain(null), addSnapshot(8, null, 1, now), setMain(8)));

            assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> {
                assertThat(f.status()).isEqualTo(409);
                assertThat(f.type()).isEqualTo("CommitFailedException");
            });
            assertThat(h.faults.calls("commit")).isEqualTo(1);
            assertThat(TableMetadataParser.fromJson(h.files.read(h.pointer("orders").metadataLocation(), "").json())
                    .currentSnapshot().snapshotId()).isEqualTo(7);
        }

        @Test
        @DisplayName("retries stop after 4 attempts, waiting 100, 200 and 400 ms plus at most 10% jitter, then 409")
        void retriesStopAtTheAttemptBound() {
            h.createTable("Orders");
            h.faults.always("commit", (args, proceed) -> conflict(args));

            CommitReply reply = commit("Orders", setA("Orders", "1"));

            assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> {
                assertThat(f.status()).isEqualTo(409);
                assertThat(f.type()).isEqualTo("CommitFailedException");
            });
            assertThat(h.faults.calls("commit")).isEqualTo(4);
            assertThat(h.waits).hasSize(3);
            for (int i = 0; i < 3; i++) {
                long base = 100L << i;
                assertThat(h.waits.get(i)).isBetween(Duration.ofMillis(base), Duration.ofMillis(base + base / 10));
            }
            assertThat(h.pointer("orders").version()).isEqualTo(1);
        }

        @Test
        @DisplayName("no retry starts after the time budget, however many attempts are allowed")
        void retriesStopAtTheTimeBudget() {
            h.settings = new CommitSettings(10, Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofHours(24),
                    Duration.ofSeconds(30));
            h.createTable("Orders");
            h.faults.always("commit", (args, proceed) -> conflict(args));

            assertThat(commit("Orders", setA("Orders", "1"))).isInstanceOf(Failure.class);

            assertThat(h.faults.calls("commit")).isEqualTo(4);
            assertThat(h.waits.stream().reduce(Duration.ZERO, Duration::plus)).isLessThanOrEqualTo(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("with one attempt allowed, a lost swap is 409 at once")
        void oneAttempt() {
            h.settings = new CommitSettings(1, Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofHours(24),
                    Duration.ofSeconds(30));
            h.createTable("Orders");
            h.faults.always("commit", (args, proceed) -> conflict(args));

            assertThat(commit("Orders", setA("Orders", "1"))).isInstanceOf(Failure.class);
            assertThat(h.faults.calls("commit")).isEqualTo(1);
            assertThat(h.waits).isEmpty();
        }
    }

    @Nested
    class UnknownOutcomes {

        private CommitOutcomeUnknownException unknown(Object[] args) {
            return new CommitOutcomeUnknownException(((CommitBatch) args[0]).commitId(), null);
        }

        @Test
        @DisplayName("a commit whose outcome was lost but which landed is a success, applied once")
        void landed() {
            h.createTable("Orders");
            h.faults.once("commit", (args, proceed) -> {
                proceed.call();
                throw unknown(args);
            });

            CommitReply reply = commit("Orders", setA("Orders", "1"));

            assertThat(reply).isInstanceOfSatisfying(Success.class, s -> assertThat(s.how()).isEqualTo(How.COMMITTED));
            assertThat(h.faults.calls("commit")).isEqualTo(1);
            assertThat(h.faults.calls("findCommit")).isEqualTo(1);
            assertThat(h.pointer("orders").version()).isEqualTo(2);
        }

        @Test
        @DisplayName("a commit whose outcome was lost and which did not land is retried, and lands once")
        void didNotLand() {
            h.createTable("Orders");
            h.faults.once("commit", (args, proceed) -> {
                throw unknown(args);
            });

            assertThat(commit("Orders", setA("Orders", "1"))).isInstanceOf(Success.class);
            assertThat(h.faults.calls("commit")).isEqualTo(2);
            assertThat(h.pointer("orders").version()).isEqualTo(2);
            assertThat(h.journal("orders")).hasSize(2);
        }

        @Test
        @DisplayName("a commit whose outcome cannot be decided is 500 CommitStateUnknown")
        void undecidable() {
            h.createTable("Orders");
            h.faults.once("commit", (args, proceed) -> {
                throw unknown(args);
            });
            h.faults.once("findCommit", (args, proceed) -> {
                throw new StoreException("standby unreachable", null);
            });

            assertThat(commit("Orders", setA("Orders", "1"))).isInstanceOfSatisfying(ServerError.class,
                    e -> assertThat(e.type()).isEqualTo("CommitStateUnknownException"));
        }

        @Test
        @DisplayName("a store failure before COMMIT is 500 ServiceFailure, with nothing applied")
        void cleanFailure() {
            h.createTable("Orders");
            h.faults.once("commit", (args, proceed) -> {
                throw new StoreException("connection refused", null);
            });

            assertThat(commit("Orders", setA("Orders", "1"))).isInstanceOfSatisfying(ServerError.class,
                    e -> assertThat(e.type()).isEqualTo("ServiceFailureException"));
            assertThat(h.pointer("orders").version()).isEqualTo(1);
        }
    }

    @Nested
    class Idempotency {

        @Test
        @DisplayName("a retried request is replayed, not applied again (D10)")
        void replayed() {
            h.createTable("Orders", Map.of("format-version", "2"));
            String key = key();
            String append = update(assertMain(null), addSnapshot(1, null, 1, h.clock.millis()), setMain(1));

            CommitReply first = commit("Orders", append, key);
            CommitReply again = commit("Orders", append, key);

            Success done = (Success) first;
            assertThat(done.how()).isEqualTo(How.COMMITTED);
            assertThat(again).isEqualTo(new Success(done.objectId(), done.metadataLocation(), done.metadataSha256(),
                    done.metadataJson(), How.REPLAYED));
            assertThat(h.pointer("orders").version()).isEqualTo(2);
            assertThat(h.journal("orders")).hasSize(2);
        }

        @Test
        @DisplayName("a replay reads the file again when it is not cached, and checks it")
        void replayedFromTheStore() {
            h.cache = new MetadataCache(0);
            h.createTable("Orders");
            String key = key();
            Success done = (Success) commit("Orders", setA("Orders", "1"), key);
            int reads = h.files.reads.get();

            assertThat(commit("Orders", setA("Orders", "1"), key)).isEqualTo(new Success(done.objectId(),
                    done.metadataLocation(), done.metadataSha256(), done.metadataJson(), How.REPLAYED));
            assertThat(h.files.reads.get()).isEqualTo(reads + 1);
        }

        @Test
        @DisplayName("a key reused for a different request is 400, and the request does not run")
        void differentRequest() {
            h.createTable("Orders");
            String key = key();
            commit("Orders", setA("Orders", "1"), key);

            assertThat(commit("Orders", setA("Orders", "2"), key)).isInstanceOfSatisfying(Failure.class,
                    f -> assertThat(f.status()).isEqualTo(400));
            assertThat(h.pointer("orders").version()).isEqualTo(2);
        }

        @Test
        @DisplayName("a key reused by another principal is 400")
        void differentPrincipal() {
            h.createTable("Orders");
            String key = key();
            String request = setA("Orders", "1");
            commit("Orders", request, key);

            assertThat(h.engine().commitTable(h.name("Orders"), request, BOB, Optional.of(key)))
                    .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(400));
        }

        @Test
        @DisplayName("a definite 4xx is replayed even after the table has changed so the request would now succeed")
        void failureReplayed() {
            h.createTable("Orders");
            String key = key();
            String request = update(assertSchema(1), setProperties(Map.of("a", "1")));
            CommitReply refused = commit("Orders", request, key);
            assertThat(refused).isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(409));
            assertThat(commit("Orders", update(assertSchema(0), addColumn(1, "note")))).isInstanceOf(Success.class);

            assertThat(commit("Orders", request, key)).isEqualTo(refused);
            assertThat(commit("Orders", request)).isInstanceOf(Success.class);
        }

        @Test
        @DisplayName("a no-op's answer is replayed as it was given")
        void noOpReplayed() {
            Success created = h.createTable("Orders");
            String key = key();
            assertThat(commit("Orders", noOp(), key)).isInstanceOfSatisfying(Success.class,
                    s -> assertThat(s.how()).isEqualTo(How.UNCHANGED));
            assertThat(commit("Orders", setA("Orders", "2"))).isInstanceOf(Success.class);

            assertThat(commit("Orders", noOp(), key)).isEqualTo(new Success(created.objectId(),
                    created.metadataLocation(), created.metadataSha256(), created.metadataJson(), How.REPLAYED));
        }

        @Test
        @DisplayName("a 500 is never replayed: the retry runs the request again")
        void serverErrorNotReplayed() {
            h.createTable("Orders");
            String key = key();
            h.faults.once("commit", (args, proceed) -> {
                throw new StoreException("connection refused", null);
            });
            assertThat(commit("Orders", setA("Orders", "1"), key)).isInstanceOf(ServerError.class);

            assertThat(commit("Orders", setA("Orders", "1"), key)).isInstanceOfSatisfying(Success.class,
                    s -> assertThat(s.how()).isEqualTo(How.COMMITTED));
            assertThat(h.pointer("orders").version()).isEqualTo(2);
        }

        @Test
        @DisplayName("after CommitStateUnknown, the retry with the key learns that the commit landed")
        void unknownThenReplayed() {
            h.createTable("Orders");
            String key = key();
            h.faults.once("commit", (args, proceed) -> {
                proceed.call();
                throw new CommitOutcomeUnknownException(((CommitBatch) args[0]).commitId(), null);
            });
            h.faults.once("findCommit", (args, proceed) -> {
                throw new StoreException("standby unreachable", null);
            });
            assertThat(commit("Orders", setA("Orders", "1"), key)).isInstanceOfSatisfying(ServerError.class,
                    e -> assertThat(e.type()).isEqualTo("CommitStateUnknownException"));

            assertThat(commit("Orders", setA("Orders", "1"), key)).isInstanceOfSatisfying(Success.class,
                    s -> assertThat(s.how()).isEqualTo(How.REPLAYED));
            assertThat(h.pointer("orders").version()).isEqualTo(2);
        }

        @Test
        @DisplayName("a second request with the key waits for the first and replays its answer")
        void concurrentSameKey() throws Exception {
            h.createTable("Orders");
            String key = key();
            String request = setA("Orders", "1");
            CountDownLatch secondWaits = new CountDownLatch(1);
            h.faults.once("commit", (args, proceed) -> {
                assertThat(secondWaits.await(30, TimeUnit.SECONDS)).isTrue();
                return proceed.call();
            });
            h.faults.once("awaitKey", (args, proceed) -> {
                secondWaits.countDown();
                return proceed.call();
            });

            CompletableFuture<CommitReply> first = CompletableFuture.supplyAsync(() -> commit("Orders", request, key));
            CompletableFuture<CommitReply> second = CompletableFuture.supplyAsync(() -> {
                try {
                    // Only start once the first holds the key.
                    while (h.faults.calls("commit") == 0) {
                        Thread.sleep(5);
                    }
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return commit("Orders", request, key);
            });

            Success one = (Success) first.get(60, TimeUnit.SECONDS);
            Success two = (Success) second.get(60, TimeUnit.SECONDS);
            assertThat(one.how()).isEqualTo(How.COMMITTED);
            assertThat(two.how()).isEqualTo(How.REPLAYED);
            assertThat(two.metadataLocation()).isEqualTo(one.metadataLocation());
            assertThat(h.pointer("orders").version()).isEqualTo(2);
        }

        @Test
        @DisplayName("a replay whose file was changed behind the catalog's back is 500, and alerted")
        void tamperedReplay() {
            h.cache = new MetadataCache(0);
            h.createTable("Orders");
            String key = key();
            Success done = (Success) commit("Orders", setA("Orders", "1"), key);
            h.files.tamper(done.metadataLocation(), done.metadataJson().replace("\"a\":\"1\"", "\"a\":\"9\""));

            assertThat(commit("Orders", setA("Orders", "1"), key)).isInstanceOf(ServerError.class);
            assertThat(h.alerts).containsExactly("integrity " + done.objectId() + " " + done.metadataLocation());
        }

        @Test
        @DisplayName("a retried create is replayed, and the table is created once")
        void createReplayed() {
            String key = key();
            String request = Requests.createTable("Orders", Map.of());

            Success first = (Success) h.engine().createTable(h.name("Orders"), request, ALICE, Optional.of(key));
            CommitReply again = h.engine().createTable(h.name("Orders"), request, ALICE, Optional.of(key));

            assertThat(again).isEqualTo(new Success(first.objectId(), first.metadataLocation(), first.metadataSha256(),
                    first.metadataJson(), How.REPLAYED));
        }
    }

    @Nested
    class Integrity {

        @Test
        @DisplayName("a metadata file changed behind the catalog's back is alerted; an unregulated table goes on")
        void unregulatedGoesOn() {
            h.cache = new MetadataCache(0);
            Success created = h.createTable("Orders");
            h.files.tamper(created.metadataLocation(), created.metadataJson() + " ");

            assertThat(commit("Orders", setA("Orders", "1"))).isInstanceOf(Success.class);
            assertThat(h.alerts).containsExactly("integrity " + created.objectId() + " " + created.metadataLocation());
        }

        @Test
        @DisplayName("a regulated table fails closed on a changed metadata file (D26)")
        void regulatedFailsClosed() {
            h.cache = new MetadataCache(0);
            Success created = h.createTable("Orders");
            h.regulate(h.namespace());
            h.files.tamper(created.metadataLocation(), created.metadataJson() + " ");
            int writes = h.files.writes.get();

            assertThat(commit("Orders", setA("Orders", "1"))).isInstanceOf(ServerError.class);
            assertThat(h.files.writes.get()).isEqualTo(writes);
            assertThat(h.pointer("orders").version()).isEqualTo(1);
            assertThat(h.alerts).hasSize(1);
        }
    }
}

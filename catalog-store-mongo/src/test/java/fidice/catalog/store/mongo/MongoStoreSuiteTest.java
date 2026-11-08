package fidice.catalog.store.mongo;

import static fidice.catalog.store.suite.Commits.fullBatch;
import static fidice.catalog.store.suite.Commits.newKey;
import static fidice.catalog.store.suite.Commits.next;
import static fidice.catalog.store.suite.Commits.pointer;
import static fidice.catalog.store.suite.Commits.swap;
import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.suite.CommitSuite;
import fidice.catalog.store.suite.StoreUnderTest;
import fidice.catalog.store.suite.WrittenRows;
import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shared suite against a three-member MongoDB 8.0 replica set, plus MongoDB-only hazards. */
class MongoStoreSuiteTest extends CommitSuite {

    private MongoReplicaSet mongo;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    @Override
    protected StoreUnderTest storeUnderTest() {
        mongo = new MongoReplicaSet();
        return mongo;
    }

    @AfterAll
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    @DisplayName("MongoDB: a commitTransaction whose outcome comes back unknown is sent again on the same session"
            + " and answers Committed, without replaying the transaction")
    void unknownCommitOutcomeIsResolvedOnTheSameSession() {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = fullBatch(swap(key, Optional.of(base), pointer("outcome-unknown", 2)));
        // The driver resends commitTransaction once within each call, so two unknown answers outlast
        // the store's first call whether or not the driver resends. A server fail point rather than
        // a link fault: how long one call lasts under a link fault depends on the driver's monitor.
        mongo.commitAnswersUnknown(2);

        assertThat(store().commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));

        assertThat(store().readPointer(key)).contains(next(batch));
        assertThat(mongo.writtenRows(batch)).isEqualTo(WrittenRows.allOf(batch));
    }

    @Test
    @DisplayName("MongoDB: migrate is repeatable and records every step once")
    void migrateIsRepeatable() {
        store().migrate();
        store().migrate();

        BsonDocument schema = mongo.schemaVersion(mongo.database(MongoReplicaSet.DATABASE));
        assertThat(schema.getInt32("version").getValue()).isEqualTo(MongoSchema.STEPS.getLast().version());
        assertThat(schema.getArray("applied")).extracting(step -> step.asDocument().getString("description"))
                .containsExactlyElementsOf(MongoSchema.STEPS.stream().map(step -> new BsonString(step.description())).toList());
    }

    @Test
    @DisplayName("MongoDB: the schema runner skips applied steps, and runners racing each other record each step once")
    void schemaRunnerAppliesEachStepOnce() throws Exception {
        Map<Integer, AtomicInteger> runs = new ConcurrentHashMap<>();
        List<MongoSchema.Step> steps = List.of(1, 2, 3).stream().map(version -> new MongoSchema.Step(version,
                "step " + version, database -> runs.computeIfAbsent(version, v -> new AtomicInteger()).incrementAndGet())).toList();

        MongoDatabase alone = mongo.database("schema_" + UUID.randomUUID().toString().replace("-", ""));
        MongoSchema.migrate(alone, steps);
        MongoSchema.migrate(alone, steps);
        assertThat(runs).hasSize(steps.size()).allSatisfy((version, count) -> assertThat(count).as("runs of step %d", version).hasValue(1));
        assertRecordedOnce(mongo.schemaVersion(alone), steps);

        MongoDatabase raced = mongo.database("schema_" + UUID.randomUUID().toString().replace("-", ""));
        CyclicBarrier start = new CyclicBarrier(8);
        List<Future<?>> runners = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            runners.add(threads.submit(() -> {
                start.await();
                MongoSchema.migrate(raced, steps);
                return null;
            }));
        }
        for (Future<?> runner : runners) {
            runner.get(60, TimeUnit.SECONDS);
        }
        assertRecordedOnce(mongo.schemaVersion(raced), steps);
    }

    private static void assertRecordedOnce(BsonDocument schema, List<MongoSchema.Step> steps) {
        assertThat(schema.getInt32("version").getValue()).isEqualTo(steps.getLast().version());
        assertThat(schema.getArray("applied")).extracting(step -> step.asDocument().getInt32("version").getValue())
                .containsExactlyElementsOf(steps.stream().map(MongoSchema.Step::version).toList());
    }
}

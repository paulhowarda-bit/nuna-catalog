package keel.catalog.core.commit;

import static keel.catalog.core.commit.Harness.ALICE;
import static keel.catalog.core.commit.Requests.assertUuid;
import static keel.catalog.core.commit.Requests.setProperties;
import static keel.catalog.core.commit.Requests.update;
import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.core.commit.CommitReply.Failure;
import keel.catalog.core.commit.CommitReply.How;
import keel.catalog.core.commit.CommitReply.ServerError;
import keel.catalog.core.commit.CommitReply.Success;
import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitOutcomeUnknownException;
import keel.catalog.store.JournalRecord;
import keel.catalog.store.StoreException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.iceberg.TableMetadataParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Concurrent writers on one table, with the store's answers randomly lost before or after COMMIT
 * or failing cleanly. Whatever the interleaving, every request is applied exactly once or not at
 * all, its reply says which, and a retry with its key never applies it twice (D9, D10).
 */
class CommitEnginePropertyTest {

    private static final int WRITERS = 4;
    private static final int REQUESTS_PER_WRITER = 6;

    private record Sent(String property, String request, Optional<String> key, CommitReply reply) {
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {11, 23, 37, 41, 59})
    @DisplayName("property: each request lands at most once, exactly when its reply says it did")
    void eachRequestLandsOnceOrNotAtAll(long seed) throws Exception {
        Harness h = new Harness();
        h.createTable("Orders");
        String uuid = h.object("Orders").id().toString();
        Random faults = new Random(seed);
        h.faults.always("commit", (args, proceed) -> {
            int roll;
            synchronized (faults) {
                roll = faults.nextInt(10);
            }
            CommitBatch batch = (CommitBatch) args[0];
            return switch (roll) {
                case 0 -> {
                    proceed.call();
                    throw new CommitOutcomeUnknownException(batch.commitId(), null);
                }
                case 1 -> throw new CommitOutcomeUnknownException(batch.commitId(), null);
                case 2 -> throw new StoreException("connection refused", null);
                default -> proceed.call();
            };
        });

        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        List<Future<List<Sent>>> writers = new ArrayList<>();
        for (int w = 0; w < WRITERS; w++) {
            int writer = w;
            Random choices = new Random(seed * 31 + w);
            writers.add(pool.submit(() -> {
                List<Sent> sent = new ArrayList<>();
                for (int i = 0; i < REQUESTS_PER_WRITER; i++) {
                    String property = "w" + writer + "-" + i;
                    String request = update(assertUuid(uuid), setProperties(Map.of(property, "v")));
                    Optional<String> key = choices.nextBoolean()
                            ? Optional.of("key-" + seed + "-" + property) : Optional.empty();
                    sent.add(new Sent(property, request, key,
                            h.engine().commitTable(h.name("Orders"), request, ALICE, key)));
                }
                return sent;
            }));
        }
        List<Sent> all = new ArrayList<>();
        for (Future<List<Sent>> writer : writers) {
            all.addAll(writer.get(5, TimeUnit.MINUTES));
        }
        pool.shutdown();

        assertLandedExactlyAsReported(h, all);

        // Retry every keyed request with no faults: a landed one replays, a 5xx one runs again, a 409 replays.
        h.faults.always("commit", (args, proceed) -> proceed.call());
        List<Sent> retried = new ArrayList<>();
        for (Sent sent : all) {
            if (sent.key().isEmpty()) {
                retried.add(sent);
                continue;
            }
            CommitReply again = h.engine().commitTable(h.name("Orders"), sent.request(), ALICE, sent.key());
            switch (sent.reply()) {
                case Success first -> assertThat(again).isEqualTo(new Success(first.objectId(),
                        first.metadataLocation(), first.metadataSha256(), first.metadataJson(), How.REPLAYED));
                case Failure first -> assertThat(again).isEqualTo(first);
                case ServerError first -> assertThat(again).isInstanceOfSatisfying(Success.class,
                        s -> assertThat(s.how()).isEqualTo(How.COMMITTED));
                case CommitReply.Unavailable first -> assertThat(again).isInstanceOfSatisfying(Success.class,
                        s -> assertThat(s.how()).isEqualTo(How.COMMITTED));
            }
            retried.add(new Sent(sent.property(), sent.request(), sent.key(),
                    again instanceof Success s && s.how() == How.REPLAYED ? sent.reply() : again));
        }

        assertLandedExactlyAsReported(h, retried);
    }

    /**
     * A Success landed once; a Failure or a plain 500 did not land. The pointer moved once per landed
     * request, and the journal holds exactly those commits, in version order.
     */
    private static void assertLandedExactlyAsReported(Harness h, List<Sent> sent) {
        assertThat(sent).noneSatisfy(s -> assertThat(s.reply()).isInstanceOfSatisfying(ServerError.class,
                e -> assertThat(e.type()).isEqualTo("CommitStateUnknownException")));
        List<String> landed = sent.stream().filter(s -> s.reply() instanceof Success).map(Sent::property).toList();
        List<String> notLanded = sent.stream().filter(s -> !(s.reply() instanceof Success)).map(Sent::property).toList();
        assertThat(sent).allSatisfy(s -> assertThat(s.reply()).satisfiesAnyOf(
                r -> assertThat(r).isInstanceOfSatisfying(Success.class, x -> assertThat(x.how()).isEqualTo(How.COMMITTED)),
                r -> assertThat(r).isInstanceOfSatisfying(Failure.class, x -> assertThat(x.status()).isEqualTo(409)),
                r -> assertThat(r).isInstanceOfSatisfying(ServerError.class,
                        x -> assertThat(x.type()).isEqualTo("ServiceFailureException"))));

        var pointer = h.pointer("Orders");
        Map<String, String> properties = TableMetadataParser.fromJson(
                h.files.read(pointer.metadataLocation(), pointer.sha256()).json()).properties();
        assertThat(properties.keySet()).containsAll(landed);
        // A run whose every request landed leaves notLanded empty, which doesNotContainAnyElementsOf refuses.
        assertThat(notLanded).noneMatch(properties::containsKey);
        assertThat(pointer.version()).isEqualTo(1 + landed.size());
        List<JournalRecord> journal = h.journal("Orders");
        assertThat(journal).extracting(JournalRecord::newVersion)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, pointer.version()).boxed().toList());
    }
}

package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.PointerKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * The metadata log's pages, newest first, over a journal kept as the stores keep it: ascending by version, a page
 * at a time after the version its token names ({@link LifecycleRoutes#logPage}). The route itself is in
 * {@link LifecycleTest}.
 */
class MetadataLogPageTest {

    private static final PointerKey KEY = new PointerKey(UUID.randomUUID(), PointerKey.MAIN);

    @Test
    void pagesRunFromTheNewestVersionToTheFirst() {
        Journal journal = new Journal(LongStream.rangeClosed(1, 5));
        LifecycleRoutes.LogPage first = LifecycleRoutes.logPage(6, 2, journal);
        assertThat(versions(first)).containsExactly(5L, 4L);
        assertThat(first.before()).contains(4L);
        LifecycleRoutes.LogPage second = LifecycleRoutes.logPage(4, 2, journal);
        assertThat(versions(second)).containsExactly(3L, 2L);
        assertThat(second.before()).contains(2L);
        LifecycleRoutes.LogPage last = LifecycleRoutes.logPage(2, 2, journal);
        assertThat(versions(last)).containsExactly(1L);
        assertThat(last.before()).isEmpty();
    }

    @Test
    void everyVersionComesOnceWhateverThePageSize() {
        Journal journal = new Journal(LongStream.rangeClosed(1, 13));
        List<Long> expected = LongStream.iterate(13, v -> v >= 1, v -> v - 1).boxed().toList();
        for (int size = 1; size <= 15; size++) {
            assertThat(walk(14, size, journal)).as("page size " + size).isEqualTo(expected);
        }
    }

    @Test
    void missingVersionsShortenAPageButLoseNothing() {
        Journal journal = new Journal(LongStream.of(1, 2, 3, 7, 8));
        assertThat(versions(LifecycleRoutes.logPage(7, 2, journal))).isEmpty();
        assertThat(LifecycleRoutes.logPage(7, 2, journal).before()).contains(5L);
        for (int size = 1; size <= 9; size++) {
            assertThat(walk(9, size, journal)).as("page size " + size).containsExactly(8L, 7L, 3L, 2L, 1L);
        }
    }

    @Test
    void commitsAfterTheFirstPageDoNotShiftTheRest() {
        Journal journal = new Journal(LongStream.rangeClosed(1, 9));
        assertThat(versions(LifecycleRoutes.logPage(4, 2, journal))).containsExactly(3L, 2L);
    }

    @Test
    void eachPageIsOneReadOfAtMostItsSize() {
        Journal journal = new Journal(LongStream.rangeClosed(1, 1000));
        LifecycleRoutes.logPage(1001, 25, journal);
        LifecycleRoutes.logPage(500, 25, journal);
        assertThat(journal.reads).hasSize(2).allSatisfy(read -> assertThat(read.size()).isEqualTo(25));
        assertThat(journal.returned).allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(25));
    }

    private static List<Long> walk(long top, int size, Journal journal) {
        List<Long> seen = new ArrayList<>();
        Optional<Long> before = Optional.of(top);
        while (before.isPresent()) {
            LifecycleRoutes.LogPage page = LifecycleRoutes.logPage(before.get(), size, journal);
            assertThat(page.entries()).hasSizeLessThanOrEqualTo(size);
            seen.addAll(versions(page));
            before = page.before();
        }
        return seen;
    }

    private static List<Long> versions(LifecycleRoutes.LogPage page) {
        return page.entries().stream().map(JournalRecord::newVersion).toList();
    }

    /** A journal read as the stores read it: by version, after the version a token names, a page at a time. */
    private static final class Journal implements Function<PageRequest, Page<JournalRecord>> {

        private final List<JournalRecord> records;
        final List<PageRequest> reads = new ArrayList<>();
        final List<Integer> returned = new ArrayList<>();

        Journal(LongStream versions) {
            records = versions.mapToObj(v -> new JournalRecord(UUID.randomUUID(), KEY, Instant.EPOCH.plusSeconds(v),
                    new JournalEntry("tester", null, null, "[]", "[]"), v == 1 ? Optional.empty()
                    : Optional.of("v" + (v - 1)), "v" + v, v)).toList();
        }

        @Override
        public Page<JournalRecord> apply(PageRequest request) {
            reads.add(request);
            long after = request.token().map(t -> Long.parseLong(PageToken.decode(t, 1).getFirst())).orElse(0L);
            List<JournalRecord> following = records.stream().filter(r -> r.newVersion() > after).toList();
            List<JournalRecord> page = following.stream().limit(request.size()).toList();
            returned.add(page.size());
            return new Page<>(page, following.size() > page.size()
                    ? Optional.of(PageToken.encode(Long.toString(page.getLast().newVersion()))) : Optional.empty());
        }
    }
}

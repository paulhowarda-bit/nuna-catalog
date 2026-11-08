package fidice.catalog.core.commit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MetadataCacheTest {

    @Test
    @DisplayName("the least recently used entries go first when the budget is exceeded")
    void evictsLeastRecentlyUsed() {
        MetadataCache cache = new MetadataCache(10);
        cache.put("a", "aaaa");
        cache.put("b", "bbbb");
        cache.get("a");
        cache.put("c", "cccc");

        assertThat(cache.get("a")).isEqualTo("aaaa");
        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("c")).isEqualTo("cccc");
    }

    @Test
    @DisplayName("an entry larger than the whole budget is not kept, and evicts nothing")
    void tooLarge() {
        MetadataCache cache = new MetadataCache(4);
        cache.put("a", "aa");
        cache.put("b", "bbbbb");

        assertThat(cache.get("a")).isEqualTo("aa");
        assertThat(cache.get("b")).isNull();
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("property: the cache agrees with a model LRU map of the same budget")
    void agreesWithAModel(long seed) {
        Random random = new Random(seed);
        int budget = 40;
        MetadataCache cache = new MetadataCache(budget);
        LinkedHashMap<String, String> model = new LinkedHashMap<>(16, 0.75f, true);
        for (int step = 0; step < 2_000; step++) {
            String key = "k" + random.nextInt(12);
            if (random.nextBoolean()) {
                assertThat(cache.get(key)).isEqualTo(model.get(key));
            } else {
                String value = "x".repeat(random.nextInt(budget + 5));
                cache.put(key, value);
                if (value.length() <= budget) {
                    model.put(key, value);
                    int total = model.values().stream().mapToInt(String::length).sum();
                    var oldest = model.entrySet().iterator();
                    while (total > budget) {
                        Map.Entry<String, String> evicted = oldest.next();
                        total -= evicted.getValue().length();
                        oldest.remove();
                    }
                }
            }
            assertThat(cache.size()).isEqualTo(model.size());
        }
    }
}

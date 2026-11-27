package nuna.datagen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nuna.datagen.kit.Kit;
import nuna.datagen.world.Event;
import nuna.datagen.world.Feed;
import nuna.datagen.world.Market;
import nuna.datagen.world.Sink;
import nuna.datagen.world.World;
import org.junit.jupiter.api.Test;

/**
 * The scale setting (item 154): demo.sh's NUNA_DEMO_ACCOUNTS, NUNA_DEMO_SECURITIES and NUNA_DEMO_CATCH_UP_DAYS reach the
 * generator's bootstrap, whose settings change the size of the world it fixes.
 */
class ScaleTest {

    private static final Path K8S = Path.of("..", "demo", "k8s");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    @Test
    void unsetTheWorldIsAThousandAccountsThreeHundredSecuritiesAndThreeBusinessDaysBack() {
        World.Settings s = Main.settings(name -> null, TODAY);
        assertThat(s).isEqualTo(new World.Settings(20261122, LocalDate.of(2026, 11, 23), 1000, 300));
    }

    @Test
    void theEnvironmentSetsTheScaleWithinItsBounds() {
        Map<String, String> env = Map.of("BROKERAGE_ACCOUNTS", "2500", "BROKERAGE_SECURITIES", "120",
            "BROKERAGE_CATCH_UP_DAYS", "10", "BROKERAGE_SEED", "7");
        World.Settings s = Main.settings(env::get, TODAY);
        assertThat(s.accounts()).isEqualTo(2500);
        assertThat(s.securities()).isEqualTo(120);
        assertThat(s.seed()).isEqualTo(7);
        // Ten business days before the 27th: Thanksgiving (the 26th) is not one.
        assertThat(s.start()).isEqualTo(LocalDate.of(2026, 11, 12));
        for (Map<String, String> wrong : java.util.List.of(Map.of("BROKERAGE_ACCOUNTS", "150"),
            Map.of("BROKERAGE_ACCOUNTS", "20001"), Map.of("BROKERAGE_SECURITIES", "1001"),
            Map.of("BROKERAGE_SECURITIES", "a lot"), Map.of("BROKERAGE_CATCH_UP_DAYS", "0"))) {
            assertThatThrownBy(() -> Main.settings(wrong::get, TODAY)).as(wrong.toString())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(wrong.keySet().iterator().next());
        }
    }

    @Test
    void theSettingsChangeTheWorldsSize() {
        Map<Feed, Integer> small = day(new World.Settings(5, LocalDate.of(2026, 11, 2), 250, 60));
        Map<Feed, Integer> large = day(new World.Settings(5, LocalDate.of(2026, 11, 2), 1500, 400));
        assertThat(small.get(Feed.ACCOUNTS)).isEqualTo(250);
        assertThat(large.get(Feed.ACCOUNTS)).isEqualTo(1500);
        assertThat(small.get(Feed.SECURITIES)).isEqualTo(60);
        assertThat(large.get(Feed.SECURITIES)).isEqualTo(400);
        assertThat(small.get(Feed.BARS_5M)).isEqualTo(60 * Market.BARS);
        assertThat(large.get(Feed.BARS_5M)).isEqualTo(400 * Market.BARS);
        // The streams and the books grow with it: sessions with the accounts, quotes with the securities.
        assertThat(large.get(Feed.SESSIONS)).isGreaterThan(3 * small.get(Feed.SESSIONS));
        assertThat(large.get(Feed.QUOTES)).isGreaterThan(2 * small.get(Feed.QUOTES));
        assertThat(large.get(Feed.FINANCIAL_TRANSACTIONS)).isGreaterThan(3 * small.get(Feed.FINANCIAL_TRANSACTIONS));
    }

    private static Map<Feed, Integer> day(World.Settings settings) {
        World w = new World(settings, Kit.load());
        Map<Feed, Integer> counts = new EnumMap<>(Feed.class);
        w.runDay(new Sink() {
            @Override
            public void accept(Event e) {
                counts.merge(e.feed(), 1, Integer::sum);
            }
        });
        assertThat(w.unexpectedRejections()).isZero();
        return counts;
    }

    /**
     * The generator's manifests take the scale from demo.sh, which fills in every value they name: an unfilled
     * ${BROKERAGE_ACCOUNTS} would reach the generator as text.
     */
    @Test
    void demoShFillsTheScaleIntoTheGeneratorsManifests() throws IOException {
        String sh = Files.readString(K8S.resolve("..").resolve("demo.sh"));
        Set<String> filled = new TreeSet<>();
        Matcher f = Pattern.compile("s\\|\\\\\\$\\{(\\w+)}\\|").matcher(sh);
        while (f.find()) {
            filled.add(f.group(1));
        }
        assertThat(sh).contains("BROKERAGE_ACCOUNTS=\"${NUNA_DEMO_ACCOUNTS:-1000}\"",
            "BROKERAGE_SECURITIES=\"${NUNA_DEMO_SECURITIES:-300}\"",
            "BROKERAGE_CATCH_UP_DAYS=\"${NUNA_DEMO_CATCH_UP_DAYS:-3}\"");
        for (String manifest : java.util.List.of("brokerage.yaml", "brokerage-bootstrap.yaml")) {
            String yaml = Files.readString(K8S.resolve(manifest));
            for (String name : java.util.List.of("BROKERAGE_ACCOUNTS", "BROKERAGE_SECURITIES", "BROKERAGE_CATCH_UP_DAYS")) {
                assertThat(yaml).as(manifest).contains("{name: " + name + ", value: \"${" + name + "}\"}");
            }
            Matcher used = Pattern.compile("\\$\\{(\\w+)}").matcher(yaml);
            while (used.find()) {
                assertThat(filled).as(manifest + "'s ${" + used.group(1) + "}").contains(used.group(1));
            }
        }
    }
}

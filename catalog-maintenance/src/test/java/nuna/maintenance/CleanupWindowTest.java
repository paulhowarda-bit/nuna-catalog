package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * The nightly cleanup window (D30, amended 2026-11-15), without a catalog: open from its start, closed from its end,
 * in its named zone; across midnight when it ends before it starts; shorter on the night the clocks go forward and
 * longer on the night they go back, never opening or closing twice; the whole day when it starts as it ends. It binds
 * from YAML with its times quoted. {@link NightlyCleanupTest} drives the worker across the same edges.
 */
class CleanupWindowTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    @Test
    void openFromItsStartClosedFromItsEnd() {
        CleanupWindow window = new CleanupWindow(LocalTime.of(1, 0), LocalTime.of(5, 0), LONDON);
        // November: London is on GMT, UTC itself.
        CleanupWindow.Night night = new CleanupWindow.Night(LocalDate.of(2026, 11, 15),
                Instant.parse("2026-11-15T01:00:00Z"), Instant.parse("2026-11-15T05:00:00Z"));

        assertThat(window.night(Instant.parse("2026-11-15T00:59:59.999Z"))).isEmpty();
        assertThat(window.night(Instant.parse("2026-11-15T01:00:00Z"))).contains(night);
        assertThat(window.night(Instant.parse("2026-11-15T04:59:59.999Z"))).contains(night);
        assertThat(window.night(Instant.parse("2026-11-15T05:00:00Z"))).as("closed at its end").isEmpty();
        assertThat(window.night(Instant.parse("2026-11-15T13:00:00Z"))).isEmpty();
        // July: British Summer Time, an hour ahead of UTC.
        assertThat(window.night(Instant.parse("2026-07-01T00:00:00Z"))).contains(new CleanupWindow.Night(
                LocalDate.of(2026, 7, 1), Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-01T04:00:00Z")));
        assertThat(window.night(Instant.parse("2026-07-01T04:00:00Z"))).isEmpty();
    }

    @Test
    void aWindowEndingBeforeItStartsCrossesMidnight() {
        CleanupWindow window = new CleanupWindow(LocalTime.of(22, 30), LocalTime.of(4, 0), LONDON);
        CleanupWindow.Night night = new CleanupWindow.Night(LocalDate.of(2026, 11, 14),
                Instant.parse("2026-11-14T22:30:00Z"), Instant.parse("2026-11-15T04:00:00Z"));

        assertThat(window.night(Instant.parse("2026-11-14T22:29:59Z"))).isEmpty();
        assertThat(window.night(Instant.parse("2026-11-14T22:30:00Z"))).contains(night);
        assertThat(window.night(Instant.parse("2026-11-14T23:59:59Z"))).contains(night);
        assertThat(window.night(Instant.parse("2026-11-15T00:00:00Z"))).as("past midnight, the same night")
                .contains(night);
        assertThat(window.night(Instant.parse("2026-11-15T03:59:59Z"))).contains(night);
        assertThat(window.night(Instant.parse("2026-11-15T04:00:00Z"))).isEmpty();
        assertThat(window.night(Instant.parse("2026-11-15T22:30:00Z"))).as("the next night")
                .contains(window.on(LocalDate.of(2026, 11, 15)));
    }

    /** On 28 March 2027, at 01:00 GMT, London's clocks go forward to 02:00 BST: 01:00 to 02:00 never happens. */
    @Test
    void theNightTheClocksGoForwardIsAnHourShorter() {
        CleanupWindow window = new CleanupWindow(LocalTime.of(23, 0), LocalTime.of(2, 0), LONDON);
        CleanupWindow.Night night = window.night(Instant.parse("2027-03-27T23:00:00Z")).orElseThrow();

        assertThat(night).isEqualTo(new CleanupWindow.Night(LocalDate.of(2027, 3, 27),
                Instant.parse("2027-03-27T23:00:00Z"), Instant.parse("2027-03-28T01:00:00Z")));
        assertThat(Duration.between(night.opens(), night.closes())).as("two hours, not three")
                .isEqualTo(Duration.ofHours(2));
        assertThat(window.night(Instant.parse("2027-03-28T00:59:59Z"))).contains(night);
        assertThat(window.night(Instant.parse("2027-03-28T01:00:00Z"))).as("02:00 BST").isEmpty();

        // A start the clocks skip opens as late as the skip: 01:30 on 28 March is 02:30 BST.
        CleanupWindow skipped = new CleanupWindow(LocalTime.of(1, 30), LocalTime.of(4, 0), LONDON);
        assertThat(skipped.on(LocalDate.of(2027, 3, 28))).isEqualTo(new CleanupWindow.Night(LocalDate.of(2027, 3, 28),
                Instant.parse("2027-03-28T01:30:00Z"), Instant.parse("2027-03-28T03:00:00Z")));
        assertThat(skipped.night(Instant.parse("2027-03-28T01:29:59Z"))).isEmpty();
        assertThat(skipped.night(Instant.parse("2027-03-28T01:30:00Z"))).isPresent();
    }

    /** On 25 October 2026, at 02:00 BST, London's clocks go back to 01:00 GMT: 01:00 to 02:00 happens twice. */
    @Test
    void theNightTheClocksGoBackIsAnHourLongerAndNeverReopens() {
        CleanupWindow window = new CleanupWindow(LocalTime.of(0, 30), LocalTime.of(3, 0), LONDON);
        CleanupWindow.Night night = window.night(Instant.parse("2026-10-24T23:30:00Z")).orElseThrow();

        assertThat(night).isEqualTo(new CleanupWindow.Night(LocalDate.of(2026, 10, 25),
                Instant.parse("2026-10-24T23:30:00Z"), Instant.parse("2026-10-25T03:00:00Z")));
        assertThat(Duration.between(night.opens(), night.closes())).as("three and a half hours, not two and a half")
                .isEqualTo(Duration.ofMinutes(210));
        assertThat(window.night(Instant.parse("2026-10-25T00:30:00Z"))).as("01:30 BST").contains(night);
        assertThat(window.night(Instant.parse("2026-10-25T01:30:00Z"))).as("01:30 GMT, the second time")
                .contains(night);

        // A window inside the repeated hour opens and closes the first time round only.
        CleanupWindow repeated = new CleanupWindow(LocalTime.of(1, 15), LocalTime.of(1, 45), LONDON);
        CleanupWindow.Night first = new CleanupWindow.Night(LocalDate.of(2026, 10, 25),
                Instant.parse("2026-10-25T00:15:00Z"), Instant.parse("2026-10-25T00:45:00Z"));
        assertThat(repeated.night(Instant.parse("2026-10-25T00:15:00Z"))).contains(first);
        assertThat(repeated.night(Instant.parse("2026-10-25T00:45:00Z"))).isEmpty();
        assertThat(repeated.night(Instant.parse("2026-10-25T01:15:00Z"))).as("01:15 GMT, the second time round")
                .isEmpty();
        assertThat(repeated.night(Instant.parse("2026-10-25T01:30:00Z"))).isEmpty();
    }

    @Test
    void aStartEqualToItsEndIsTheWholeDay() {
        CleanupWindow window = new CleanupWindow(LocalTime.NOON, LocalTime.NOON, ZoneOffset.UTC);

        assertThat(window.night(Instant.parse("2026-11-15T11:59:59Z"))).contains(new CleanupWindow.Night(
                LocalDate.of(2026, 11, 14), Instant.parse("2026-11-14T12:00:00Z"),
                Instant.parse("2026-11-15T12:00:00Z")));
        assertThat(window.night(Instant.parse("2026-11-15T12:00:00Z"))).as("the next opens as the last closes")
                .contains(new CleanupWindow.Night(LocalDate.of(2026, 11, 15), Instant.parse("2026-11-15T12:00:00Z"),
                        Instant.parse("2026-11-16T12:00:00Z")));
    }

    /** Spring reads YAML 1.1: an unquoted 22:30 is the number 1350, in base 60, and refused as a time. */
    @Test
    void quotedTimesBindFromYaml() throws IOException {
        assertThat(bind("start: \"22:30\"\nend: 04:00\nzone: Europe/London\n"))
                .isEqualTo(new CleanupWindow(LocalTime.of(22, 30), LocalTime.of(4, 0), LONDON));
        assertThatThrownBy(() -> bind("start: 22:30\nend: \"04:00\"\nzone: Europe/London\n"))
                .isInstanceOf(BindException.class);
    }

    private static CleanupWindow bind(String window) throws IOException {
        String yaml = "nuna:\n  maintenance:\n    cleanup-window:\n" + window.lines().map(line -> "      " + line + "\n")
                .collect(Collectors.joining());
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("worker.yaml",
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        return new Binder(ConfigurationPropertySources.from(sources)).bind("nuna.maintenance.cleanup-window",
                CleanupWindow.class).get();
    }

    @Test
    void everyPartIsRequired() {
        assertThatThrownBy(() -> new CleanupWindow(null, LocalTime.of(5, 0), LONDON))
                .hasMessageContaining("cleanup-window.start");
        assertThatThrownBy(() -> new CleanupWindow(LocalTime.of(1, 0), null, LONDON))
                .hasMessageContaining("cleanup-window.end");
        assertThatThrownBy(() -> new CleanupWindow(LocalTime.of(1, 0), LocalTime.of(5, 0), null))
                .hasMessageContaining("cleanup-window.zone");
    }
}

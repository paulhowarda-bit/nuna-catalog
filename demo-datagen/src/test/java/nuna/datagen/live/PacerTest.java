package nuna.datagen.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PacerTest {

    private static final Instant NOW = Instant.parse("2026-11-23T15:14:00Z");

    private final List<Duration> slept = new ArrayList<>();
    private final Pacer pacer = new Pacer(Clock.fixed(NOW, ZoneOffset.UTC), slept::add);

    @Test
    void anEventWhoseTimeHasPassedGoesAtOnce() throws InterruptedException {
        pacer.await(NOW.minus(Duration.ofDays(3)));
        pacer.await(NOW.minusSeconds(1));
        pacer.await(NOW);
        assertThat(slept).isEmpty();
    }

    @Test
    void anEventStillToComeWaitsForItsTimeOnTheRealClock() throws InterruptedException {
        pacer.await(NOW.plusSeconds(90));
        pacer.await(Instant.parse("2026-11-24T12:00:00Z"));
        assertThat(slept).containsExactly(Duration.ofSeconds(90), Duration.ofHours(20).plusMinutes(46));
    }
}

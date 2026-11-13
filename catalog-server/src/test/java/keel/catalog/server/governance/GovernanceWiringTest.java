package keel.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The start-up checks of the governance wiring that need no server. */
class GovernanceWiringTest {

    @Test
    void theSoftDropWindowMustOutlastThePitrWindow() {
        assertThatCode(() -> GovernanceWiring.requireDropWindowBeyondPitr(Duration.ofDays(30), Duration.ofDays(7)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> GovernanceWiring.requireDropWindowBeyondPitr(Duration.ofDays(7), Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keel.rest.drop-retention (PT168H)")
                .hasMessageContaining("keel.governance.pitr-window (PT168H)");
        assertThatThrownBy(() -> GovernanceWiring.requireDropWindowBeyondPitr(Duration.ofDays(3), Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class);
    }
}

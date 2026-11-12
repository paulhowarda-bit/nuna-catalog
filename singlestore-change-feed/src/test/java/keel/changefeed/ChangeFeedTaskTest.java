package keel.changefeed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangeFeedTaskTest {

    @Test
    void theStoredOffsetIsReadAsWhateverNumberTheConverterGives() {
        assertThat(ChangeFeedTask.receiptedThrough(Map.of("snapshot_id", 5009893770895635200L))).isEqualTo(5009893770895635200L);
        assertThat(ChangeFeedTask.receiptedThrough(Map.of("snapshot_id", 42))).isEqualTo(42L);
        assertThat(ChangeFeedTask.receiptedThrough(Map.of("snapshot_id", "5009893770895635200"))).isEqualTo(5009893770895635200L);
        assertThat(ChangeFeedTask.receiptedThrough(Map.of())).isNull();
        assertThat(ChangeFeedTask.receiptedThrough(null)).isNull();
    }
}

package nuna.catalog.audit.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventType;
import nuna.catalog.audit.LogStamp;
import nuna.catalog.audit.SampleEvents;
import nuna.catalog.audit.StampedEvent;
import nuna.catalog.audit.publish.StampedTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The audit feed takes every change; the CloudEvents change stream takes changes to catalog objects and refs only. */
class ChangeStreamTest {

    private static final UUID RUN = UUID.randomUUID();

    /** The sample events as the audit trail logged them, numbered from 1. */
    private static List<StampedEvent> logged() {
        List<StampedEvent> logged = new ArrayList<>();
        for (CatalogEvent event : SampleEvents.events()) {
            logged.add(new StampedEvent(event, new LogStamp(RUN, logged.size() + 1)));
        }
        return logged;
    }

    @Test
    @DisplayName("each batch goes whole to the audit feed, and its object changes, in order, to the change stream")
    void fansOut() {
        List<StampedEvent> audit = new ArrayList<>();
        List<StampedEvent> changes = new ArrayList<>();
        List<StampedEvent> batch = logged();

        ChangeStream.fanOut(audit::addAll, changes::addAll).deliver(batch);

        assertThat(audit).containsExactlyElementsOf(batch);
        assertThat(changes).extracting(e -> e.event().type()).containsExactly(EventType.COMMIT, EventType.DROP,
                EventType.NAMESPACE_CHANGE, EventType.RENAME, EventType.UNDROP, EventType.PURGE, EventType.UNREGISTER,
                EventType.LABEL_CHANGE, EventType.REF);
        assertThat(changes).as("each with the run and number it was logged with").isSubsetOf(batch);
    }

    @Test
    @DisplayName("grants, governance, holds, reads and the other audit-only types never reach the change stream")
    void auditOnlyTypes() {
        assertThat(ChangeStream.TYPES).doesNotContain(EventType.GRANT_CHANGE, EventType.GOVERNANCE_CHANGE,
                EventType.HOLD_CHANGE, EventType.BREAK_GLASS, EventType.OBJECT_READ, EventType.DENIAL,
                EventType.AUTH_FAILURE, EventType.CREDENTIAL_ISSUE);
    }

    @Test
    @DisplayName("a failure on the change stream fails the batch, so the relay delivers it again")
    void changeStreamFailureFailsTheBatch() {
        StampedTarget down = batch -> {
            throw new IllegalStateException("change stream down");
        };
        assertThatThrownBy(() -> ChangeStream.fanOut(batch -> { }, down).deliver(logged()))
                .hasMessage("change stream down");
    }
}

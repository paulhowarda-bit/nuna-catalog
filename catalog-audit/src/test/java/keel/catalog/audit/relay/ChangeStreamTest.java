package keel.catalog.audit.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import keel.catalog.audit.CatalogEvent;
import keel.catalog.audit.EventType;
import keel.catalog.audit.SampleEvents;
import keel.catalog.audit.publish.RelayTarget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The audit feed takes every change; the CloudEvents change stream takes changes to catalog objects only. */
class ChangeStreamTest {

    @Test
    @DisplayName("each batch goes whole to the audit feed, and its object changes, in order, to the change stream")
    void fansOut() {
        List<CatalogEvent> audit = new ArrayList<>();
        List<CatalogEvent> changes = new ArrayList<>();
        List<CatalogEvent> batch = SampleEvents.events();

        ChangeStream.fanOut(audit::addAll, changes::addAll).deliver(batch);

        assertThat(audit).containsExactlyElementsOf(batch);
        assertThat(changes).extracting(CatalogEvent::type).containsExactly(EventType.COMMIT, EventType.DROP,
                EventType.NAMESPACE_CHANGE, EventType.RENAME, EventType.UNDROP, EventType.PURGE, EventType.UNREGISTER,
                EventType.LABEL_CHANGE);
        assertThat(changes).isSubsetOf(batch);
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
        RelayTarget down = batch -> {
            throw new IllegalStateException("change stream down");
        };
        assertThatThrownBy(() -> ChangeStream.fanOut(batch -> { }, down).deliver(SampleEvents.events()))
                .hasMessage("change stream down");
    }
}

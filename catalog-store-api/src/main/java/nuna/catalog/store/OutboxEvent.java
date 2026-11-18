package nuna.catalog.store;

import java.util.Objects;

/** A change event written in the commit's transaction and relayed later (D23). */
public record OutboxEvent(String type, String payloadJson) {

    public OutboxEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
    }
}

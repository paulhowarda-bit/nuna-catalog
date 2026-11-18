package nuna.catalog.audit;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * A string field that holds JSON text is written as the JSON value itself, re-encoded compactly,
 * so an event never spans more than one line (the archive relies on that); it is read back as
 * compact JSON text.
 */
final class RawJson {

    private RawJson() {
    }

    static final class Writer extends ValueSerializer<String> {
        @Override
        public void serialize(String json, JsonGenerator generator, SerializationContext context) {
            generator.writeTree(EventCodec.MAPPER.readTree(json));
        }
    }

    static final class Reader extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            return EventCodec.MAPPER.writeValueAsString(context.readTree(parser));
        }
    }
}

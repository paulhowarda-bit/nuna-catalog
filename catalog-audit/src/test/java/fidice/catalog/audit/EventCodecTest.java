package fidice.catalog.audit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class EventCodecTest {

    /** Version 1 of each payload schema: the fields that must not be null, and those that may be. */
    private static final Map<EventType, List<String>> REQUIRED = Map.ofEntries(
            Map.entry(EventType.COMMIT, List.of("commitId", "object", "branch", "after", "updates", "actor")),
            Map.entry(EventType.GRANT_CHANGE, List.of("action", "securable", "group", "privilege", "actor")),
            Map.entry(EventType.DROP, List.of("object", "purgeAfter", "actor")),
            Map.entry(EventType.BREAK_GLASS, List.of("securable", "justification", "actor")),
            Map.entry(EventType.OBJECT_READ, List.of("object", "branch", "served", "actor")),
            Map.entry(EventType.DENIAL, List.of("operation", "securable", "reason", "actor")),
            Map.entry(EventType.AUTH_FAILURE, List.of("reason", "requestId")),
            Map.entry(EventType.CREDENTIAL_ISSUE, List.of("object", "storageProfile", "locationPrefix", "access", "sessionName",
                    "expiresAt", "actor")),
            Map.entry(EventType.NAMESPACE_CHANGE, List.of("action", "namespace", "set", "removed", "actor")),
            Map.entry(EventType.RENAME, List.of("object", "fromPath", "actor")),
            Map.entry(EventType.STORAGE_SETUP, List.of("action", "id", "name", "settings", "actor")));
    private static final Map<EventType, List<String>> NULLABLE = Map.ofEntries(
            Map.entry(EventType.COMMIT, List.of("before")),
            Map.entry(EventType.GRANT_CHANGE, List.of()),
            Map.entry(EventType.DROP, List.of()),
            Map.entry(EventType.BREAK_GLASS, List.of()),
            Map.entry(EventType.OBJECT_READ, List.of()),
            Map.entry(EventType.DENIAL, List.of("privilege")),
            Map.entry(EventType.AUTH_FAILURE, List.of("issuer", "claimedSubject", "clientId", "remoteAddress")),
            Map.entry(EventType.CREDENTIAL_ISSUE, List.of()),
            Map.entry(EventType.NAMESPACE_CHANGE, List.of()),
            Map.entry(EventType.RENAME, List.of()),
            Map.entry(EventType.STORAGE_SETUP, List.of()));
    /** The shared records, as they appear inside a commit: actor, securable and pointer state. */
    private static final List<String> NESTED_REQUIRED = List.of("actor.principal", "actor.requestId", "object.kind",
            "object.id", "object.catalog", "object.path", "after.metadataLocation", "after.version", "after.sha256");
    private static final List<String> NESTED_NULLABLE = List.of("actor.endUser", "actor.engine");

    static Stream<CatalogEvent> events() {
        return SampleEvents.events().stream();
    }

    @ParameterizedTest
    @MethodSource("events")
    void everyTypeRoundTrips(CatalogEvent event) {
        assertThat(EventCodec.decode(EventCodec.encode(event))).isEqualTo(event);
    }

    @Test
    void encodingIsPinned() throws IOException {
        String encoded = SampleEvents.events().stream().map(e -> new String(EventCodec.encode(e), UTF_8))
                .collect(Collectors.joining("\n", "", "\n"));
        try (InputStream golden = getClass().getResourceAsStream("events-v1.jsonl")) {
            assertThat(encoded).isEqualTo(new String(golden.readAllBytes(), UTF_8));
        }
    }

    @ParameterizedTest
    @MethodSource("events")
    void envelopeFollowsCloudEvents(CatalogEvent event) {
        JsonNode envelope = EventCodec.MAPPER.readTree(EventCodec.encode(event));
        envelope.propertyNames().forEach(name -> assertThat(name).matches("[a-z0-9]+"));
        assertThat(envelope.get("specversion").asString()).isEqualTo("1.0");
        for (String required : List.of("id", "source", "type")) {
            assertThat(envelope.get(required).asString()).isNotEmpty();
        }
        assertThat(envelope.get("type").asString()).startsWith("fidice.catalog.");
        assertThat(URI.create(envelope.get("dataschema").asString()).isAbsolute()).isTrue();
        assertThat(OffsetDateTime.parse(envelope.get("time").asString()).toInstant()).isEqualTo(event.time());
        assertThat(envelope.get("datacontenttype").asString()).isEqualTo("application/json");
        assertThat(envelope.get("data").isObject()).isTrue();
        if (event.subject() == null) {
            assertThat(envelope.has("subject")).isFalse();
            assertThat(envelope.has("partitionkey")).isFalse();
        } else {
            assertThat(envelope.get("subject").asString()).isEqualTo(event.subject());
            assertThat(envelope.get("partitionkey").asString()).isEqualTo(event.subject());
        }
    }

    @Test
    void jsonTextFieldsAreWrittenAsCompactJsonOnOneLine() {
        String pretty = "[\n  {\n    \"action\" : \"set-properties\",\n    \"updates\" : {\"owner\" : \"Zoë\"}\n  }\n]\n";
        Commit compact = (Commit) SampleEvents.oneOfEachType().get(0);
        Commit indented = new Commit(compact.commitId(), compact.object(), compact.branch(), compact.before(),
                compact.after(), pretty, compact.actor());

        byte[] encoded = EventCodec.encode(SampleEvents.event(indented, 1));

        assertThat(encoded).isEqualTo(EventCodec.encode(SampleEvents.event(compact, 1)));
        assertThat(new String(encoded, UTF_8)).doesNotContain("\n", "\r");
        assertThat(EventCodec.MAPPER.readTree(encoded).get("data").get("updates").isArray()).isTrue();
    }

    @Test
    void schemaListsCoverEveryEncodedField() {
        for (CatalogEvent event : SampleEvents.events()) {
            Set<String> expected = new HashSet<>(REQUIRED.get(event.type()));
            expected.addAll(NULLABLE.get(event.type()));
            assertThat(fieldNames(data(event))).as(event.type().name()).isEqualTo(expected);
        }
        ObjectNode commit = data(SampleEvents.events().get(0));
        Set<String> nested = new HashSet<>();
        for (String parent : List.of("actor", "object", "after")) {
            fieldNames((ObjectNode) commit.get(parent)).forEach(name -> nested.add(parent + "." + name));
        }
        Set<String> expected = new HashSet<>(NESTED_REQUIRED);
        expected.addAll(NESTED_NULLABLE);
        assertThat(nested).isEqualTo(expected);
    }

    static Stream<Arguments> requiredFields() {
        List<Arguments> cases = new ArrayList<>();
        for (CatalogEvent event : SampleEvents.events()) {
            REQUIRED.get(event.type()).forEach(field -> cases.add(Arguments.of(event.type() + "." + field, event, field)));
        }
        NESTED_REQUIRED.forEach(path -> cases.add(Arguments.of("COMMIT." + path, SampleEvents.events().get(0), path)));
        return cases.stream();
    }

    static Stream<Arguments> nullableFields() {
        List<Arguments> cases = new ArrayList<>();
        for (CatalogEvent event : SampleEvents.events()) {
            NULLABLE.get(event.type()).forEach(field -> cases.add(Arguments.of(event.type() + "." + field, event, field)));
        }
        NESTED_NULLABLE.forEach(path -> cases.add(Arguments.of("COMMIT." + path, SampleEvents.events().get(0), path)));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredFields")
    void nullInRequiredFieldIsRejected(String name, CatalogEvent event, String path) {
        byte[] mutated = mutateData(event, data -> parentOf(data, path).putNull(leafOf(path)));
        assertThatThrownBy(() -> EventCodec.decode(mutated)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullableFields")
    void nullInNullableFieldIsAccepted(String name, CatalogEvent event, String path) {
        byte[] mutated = mutateData(event, data -> parentOf(data, path).putNull(leafOf(path)));
        CatalogEvent decoded = EventCodec.decode(mutated);
        assertThat(EventCodec.encode(decoded)).isEqualTo(mutated);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullableFields")
    void absentFieldIsRejectedEvenWhenNullable(String name, CatalogEvent event, String path) {
        byte[] mutated = mutateData(event, data -> parentOf(data, path).remove(leafOf(path)));
        assertThatThrownBy(() -> EventCodec.decode(mutated)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownDataFieldIsRejected() {
        byte[] mutated = mutateData(SampleEvents.events().get(0), data -> data.put("approvedBy", "mallory"));
        assertThatThrownBy(() -> EventCodec.decode(mutated)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPrimitiveIsRejected() {
        byte[] mutated = mutateData(SampleEvents.events().get(0),
                data -> ((ObjectNode) data.get("after")).putNull("version"));
        assertThatThrownBy(() -> EventCodec.decode(mutated)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> brokenEnvelopes() {
        CatalogEvent commit = SampleEvents.events().get(0);
        CatalogEvent authFailure = SampleEvents.events().get(6);
        return Stream.of(
                Arguments.of("other spec version", commit, edit(e -> e.put("specversion", "0.3"))),
                Arguments.of("unknown type", commit, edit(e -> e.put("type", "fidice.catalog.rename"))),
                Arguments.of("other schema version", commit,
                        edit(e -> e.put("dataschema", "urn:fidice:catalog:event:commit:2"))),
                Arguments.of("schema of another type", commit,
                        edit(e -> e.put("dataschema", EventType.DROP.dataSchema().toString()))),
                Arguments.of("not JSON data", commit, edit(e -> e.put("datacontenttype", "text/plain"))),
                Arguments.of("subject disagrees", commit, edit(e -> e.put("subject", UUID.randomUUID().toString()))),
                Arguments.of("subject missing", commit, edit(e -> e.remove("subject"))),
                Arguments.of("partition key disagrees", commit, edit(e -> e.put("partitionkey", "other"))),
                Arguments.of("partition key missing", commit, edit(e -> e.remove("partitionkey"))),
                Arguments.of("subject on an event about nothing", authFailure, edit(e -> e.put("subject", "x"))),
                Arguments.of("data null", commit, edit(e -> e.putNull("data"))),
                Arguments.of("data a string", commit, edit(e -> e.put("data", "{}"))),
                Arguments.of("id missing", commit, edit(e -> e.remove("id"))),
                Arguments.of("id not a UUID", commit, edit(e -> e.put("id", "42"))),
                Arguments.of("source missing", commit, edit(e -> e.remove("source"))),
                Arguments.of("source empty", commit, edit(e -> e.put("source", ""))),
                Arguments.of("time missing", commit, edit(e -> e.remove("time"))),
                Arguments.of("time not RFC 3339", commit, edit(e -> e.put("time", "yesterday"))),
                Arguments.of("type missing", commit, edit(e -> e.remove("type"))),
                Arguments.of("not JSON", commit, (Consumer<ObjectNode>) null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenEnvelopes")
    void brokenEnvelopeIsRejected(String name, CatalogEvent event, Consumer<ObjectNode> edit) {
        byte[] mutated;
        if (edit == null) {
            mutated = "{\"specversion\":".getBytes(UTF_8);
        } else {
            ObjectNode envelope = (ObjectNode) EventCodec.MAPPER.readTree(EventCodec.encode(event));
            edit.accept(envelope);
            mutated = EventCodec.MAPPER.writeValueAsBytes(envelope);
        }
        assertThatThrownBy(() -> EventCodec.decode(mutated)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventsRequireIdSourceTimeAndData() {
        EventData data = SampleEvents.oneOfEachType().get(0);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new CatalogEvent(null, SampleEvents.SOURCE, SampleEvents.TIME, data))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CatalogEvent(id, null, SampleEvents.TIME, data))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CatalogEvent(id, SampleEvents.SOURCE, null, data))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CatalogEvent(id, SampleEvents.SOURCE, SampleEvents.TIME, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void eventsCarryANonEmptySource() {
        assertThatThrownBy(() -> new CatalogEvent(UUID.randomUUID(), URI.create(""), SampleEvents.TIME,
                SampleEvents.oneOfEachType().get(0))).isInstanceOf(IllegalArgumentException.class);
    }

    private static Consumer<ObjectNode> edit(Consumer<ObjectNode> edit) {
        return edit;
    }

    private static ObjectNode data(CatalogEvent event) {
        return (ObjectNode) EventCodec.MAPPER.readTree(EventCodec.encode(event)).get("data");
    }

    private static Set<String> fieldNames(ObjectNode node) {
        Set<String> names = new HashSet<>();
        Iterator<String> it = node.propertyNames().iterator();
        it.forEachRemaining(names::add);
        return names;
    }

    private static byte[] mutateData(CatalogEvent event, Consumer<ObjectNode> edit) {
        ObjectNode envelope = (ObjectNode) EventCodec.MAPPER.readTree(EventCodec.encode(event));
        edit.accept((ObjectNode) envelope.get("data"));
        return EventCodec.MAPPER.writeValueAsBytes(envelope);
    }

    private static ObjectNode parentOf(ObjectNode data, String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? data : (ObjectNode) data.get(path.substring(0, dot));
    }

    private static String leafOf(String path) {
        return path.substring(path.indexOf('.') + 1);
    }
}

package nuna.catalog.audit;

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
            Map.entry(EventType.DENIAL, List.of("operation", "reason", "actor")),
            Map.entry(EventType.AUTH_FAILURE, List.of("reason", "requestId")),
            Map.entry(EventType.CREDENTIAL_ISSUE, List.of("object", "storageProfile", "locationPrefix", "access", "sessionName",
                    "expiresAt", "actor")),
            Map.entry(EventType.NAMESPACE_CHANGE, List.of("action", "namespace", "set", "removed", "actor")),
            Map.entry(EventType.RENAME, List.of("object", "fromPath", "actor")),
            Map.entry(EventType.ENGINE_CHANGE, List.of("action", "issuer", "clientId", "endUserClaim", "flags", "actor")),
            Map.entry(EventType.STORAGE_SETUP, List.of("action", "id", "name", "settings", "actor")),
            Map.entry(EventType.GOVERNANCE_CHANGE, List.of("action", "target", "attribute", "actor")),
            Map.entry(EventType.HOLD_CHANGE, List.of("action", "holdId", "target", "reason", "actor")),
            Map.entry(EventType.UNDROP, List.of("object", "actor")),
            Map.entry(EventType.PURGE, List.of("object", "actor")),
            Map.entry(EventType.AUDIT_READ, List.of("target", "returned", "actor")),
            Map.entry(EventType.UNREGISTER, List.of("object", "pointer", "actor")),
            Map.entry(EventType.LABEL_CHANGE, List.of("target", "label", "actor")),
            Map.entry(EventType.PURGE_CLAIM, List.of("object", "actor")),
            Map.entry(EventType.FILE_DELETION, List.of("action", "object", "files", "actor")),
            Map.entry(EventType.MAINTENANCE_EXEMPTION, List.of("object", "operation", "actor")),
            Map.entry(EventType.REF, List.of("action", "ref", "name", "kind", "catalog", "from", "tables", "actor")),
            Map.entry(EventType.ROW_COLUMN_RULE_CHANGE, List.of("action", "table", "group", "columnMasks", "actor")),
            Map.entry(EventType.RUN_END, List.of("host")),
            Map.entry(EventType.EXPORT, List.of("catalog", "what", "returned", "actor")));
    private static final Map<EventType, List<String>> NULLABLE = Map.ofEntries(
            Map.entry(EventType.COMMIT, List.of("before")),
            Map.entry(EventType.GRANT_CHANGE, List.of()),
            Map.entry(EventType.DROP, List.of()),
            Map.entry(EventType.BREAK_GLASS, List.of()),
            Map.entry(EventType.OBJECT_READ, List.of()),
            Map.entry(EventType.DENIAL, List.of("securable", "privilege")),
            Map.entry(EventType.AUTH_FAILURE, List.of("issuer", "claimedSubject", "clientId", "remoteAddress")),
            Map.entry(EventType.CREDENTIAL_ISSUE, List.of()),
            Map.entry(EventType.NAMESPACE_CHANGE, List.of()),
            Map.entry(EventType.RENAME, List.of()),
            Map.entry(EventType.ENGINE_CHANGE, List.of()),
            Map.entry(EventType.STORAGE_SETUP, List.of()),
            Map.entry(EventType.GOVERNANCE_CHANGE, List.of("value", "provenance")),
            Map.entry(EventType.HOLD_CHANGE, List.of()),
            Map.entry(EventType.UNDROP, List.of()),
            Map.entry(EventType.PURGE, List.of()),
            Map.entry(EventType.AUDIT_READ, List.of()),
            Map.entry(EventType.UNREGISTER, List.of()),
            Map.entry(EventType.LABEL_CHANGE, List.of("value")),
            Map.entry(EventType.PURGE_CLAIM, List.of()),
            Map.entry(EventType.FILE_DELETION, List.of("notBefore")),
            Map.entry(EventType.MAINTENANCE_EXEMPTION, List.of()),
            Map.entry(EventType.REF, List.of("expiresAt")),
            Map.entry(EventType.ROW_COLUMN_RULE_CHANGE, List.of("rowFilter")),
            Map.entry(EventType.RUN_END, List.of()),
            Map.entry(EventType.EXPORT, List.of()));
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
        assertThat(envelope.get("type").asString()).startsWith("nuna.catalog.");
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
        if (event.type() == EventType.DENIAL && path.equals("securable")) {
            // A denial about no securable (the engine registry) has no subject, so its envelope names none.
            ObjectNode envelope = (ObjectNode) EventCodec.MAPPER.readTree(mutated);
            envelope.remove("subject");
            envelope.remove("partitionkey");
            mutated = EventCodec.MAPPER.writeValueAsBytes(envelope);
        }
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

    /** A ref record with no description: schema version 1, as before version 2 added {@code description}. */
    private static final String REF_V1 = "{\"specversion\":\"1.0\",\"id\":\"00000000-e0e0-e0e0-0000-000000000017\","
            + "\"source\":\"urn:nuna:deployment:test\",\"type\":\"nuna.catalog.ref\",\"datacontenttype\":"
            + "\"application/json\",\"dataschema\":\"urn:nuna:catalog:event:ref:1\",\"subject\":"
            + "\"f0000000-0000-0000-0000-000000000001\",\"time\":\"2026-11-07T10:15:30.123Z\",\"partitionkey\":"
            + "\"f0000000-0000-0000-0000-000000000001\",\"data\":{\"action\":\"MERGED\",\"ref\":"
            + "\"f0000000-0000-0000-0000-000000000001\",\"name\":\"dév\",\"kind\":\"BRANCH\",\"catalog\":{\"kind\":"
            + "\"CATALOG\",\"id\":\"00000000-0000-0000-0000-00000000000c\",\"catalog\":\"Trading\",\"path\":[]},"
            + "\"from\":\"main\",\"expiresAt\":\"2026-12-07T10:15:30.123Z\",\"tables\":[{\"kind\":\"TABLE\",\"id\":"
            + "\"7f0c2a52-9d0e-4a8b-9c61-2b1e6f0d4a11\",\"catalog\":\"Trading\",\"path\":[\"Équités\",\"Orders\"]}],"
            + "\"actor\":{\"principal\":\"svc-trino\",\"endUser\":\"alice@example.com\",\"engine\":\"trino-shared\","
            + "\"requestId\":\"req-0001\"}}}";

        /** The sample ref record, made with a description: schema version 2 (backlog 167). */
    private static CatalogEvent described(String description) {
        CatalogEvent sample = SampleEvents.events().stream().filter(e -> e.type() == EventType.REF).findFirst()
                .orElseThrow();
        RefChange ref = (RefChange) sample.data();
        return new CatalogEvent(sample.id(), sample.source(), sample.time(), new RefChange(ref.action(), ref.ref(),
                ref.name(), ref.kind(), ref.catalog(), ref.from(), ref.expiresAt(), description, ref.tables(),
                ref.actor()));
    }

    @Test
    void aRefRecordWithNoDescriptionIsWrittenAndReadAsVersion1() {
        CatalogEvent sample = SampleEvents.events().stream().filter(e -> e.type() == EventType.REF).findFirst()
                .orElseThrow();

        assertThat(new String(EventCodec.encode(sample), UTF_8)).as("as a server of the previous release wrote it")
                .isEqualTo(REF_V1);
        CatalogEvent decoded = EventCodec.decode(REF_V1.getBytes(UTF_8));
        assertThat(decoded).isEqualTo(sample);
        assertThat(((RefChange) decoded.data()).description()).isNull();
    }

    @Test
    void aRefRecordWithADescriptionIsWrittenAndReadAsVersion2() {
        CatalogEvent event = described("before the <b>November</b> restatement");

        byte[] encoded = EventCodec.encode(event);

        JsonNode envelope = EventCodec.MAPPER.readTree(encoded);
        assertThat(envelope.get("dataschema").asString()).isEqualTo("urn:nuna:catalog:event:ref:2");
        assertThat(envelope.get("data").get("description").asString()).isEqualTo("before the <b>November</b> restatement");
        assertThat(EventCodec.decode(encoded)).isEqualTo(event);
    }

    @Test
    void aRefRecordOfVersion1CarryingADescriptionIsRejected() {
        ObjectNode envelope = (ObjectNode) EventCodec.MAPPER.readTree(REF_V1);
        ((ObjectNode) envelope.get("data")).put("description", "before the restatement");
        byte[] mutated = EventCodec.MAPPER.writeValueAsBytes(envelope);
        assertThatThrownBy(() -> EventCodec.decode(mutated)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRefRecordOfVersion2WithoutADescriptionIsRejected() {
        ObjectNode absent = (ObjectNode) EventCodec.MAPPER.readTree(REF_V1);
        absent.put("dataschema", "urn:nuna:catalog:event:ref:2");
        ObjectNode none = absent.deepCopy();
        ((ObjectNode) none.get("data")).putNull("description");
        for (ObjectNode envelope : List.of(absent, none)) {
            byte[] mutated = EventCodec.MAPPER.writeValueAsBytes(envelope);
            assertThatThrownBy(() -> EventCodec.decode(mutated)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anAuditRowOfARefRecordReadsInEitherVersion() {
        CatalogEvent plain = EventCodec.decode(REF_V1.getBytes(UTF_8));
        CatalogEvent event = described("before the restatement");

        AuditRow plainRow = AuditRows.of(plain, 0, 0);
        AuditRow describedRow = AuditRows.of(event, 0, 1);

        assertThat(EventCodec.MAPPER.readTree(plainRow.data()).has("description")).isFalse();
        assertThat(AuditRows.event(plainRow)).isEqualTo(plain);
        assertThat(AuditRows.event(describedRow)).isEqualTo(event);
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
                Arguments.of("unknown type", commit, edit(e -> e.put("type", "nuna.catalog.rename"))),
                Arguments.of("other schema version", commit,
                        edit(e -> e.put("dataschema", "urn:nuna:catalog:event:commit:2"))),
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

    @ParameterizedTest
    @MethodSource("events")
    void aLoggedRecordCarriesItsStampAndStillDecodesToTheEvent(CatalogEvent event) {
        LogStamp stamp = new LogStamp(UUID.fromString("4b1d0000-0000-4000-8000-000000000001"), 4_294_967_297L);
        byte[] logged = EventCodec.encode(event, stamp);
        JsonNode envelope = EventCodec.MAPPER.readTree(logged);
        assertThat(envelope.get(EventCodec.RUN).asString()).isEqualTo(stamp.run().toString());
        assertThat(envelope.get(EventCodec.SEQUENCE).asString()).as("a string: CloudEvents integers are 32 bits")
                .isEqualTo("4294967297");
        envelope.propertyNames().forEach(name -> assertThat(name).matches("[a-z0-9]{1,20}"));
        assertThat(EventCodec.decode(logged)).isEqualTo(event);
        assertThat(EventCodec.envelope(logged)).isEqualTo(new EventCodec.Envelope(event.type().typeName(),
                java.util.Optional.of(stamp)));
        assertThat(EventCodec.envelope(EventCodec.encode(event))).isEqualTo(new EventCodec.Envelope(
                event.type().typeName(), java.util.Optional.empty()));
    }

    @Test
    void aStampIsWholeAndNumberedFromOne() {
        CatalogEvent event = SampleEvents.events().getFirst();
        LogStamp stamp = new LogStamp(UUID.randomUUID(), 7);
        for (Consumer<ObjectNode> edit : List.<Consumer<ObjectNode>>of(
                e -> e.remove(EventCodec.SEQUENCE),
                e -> e.remove(EventCodec.RUN),
                e -> e.put(EventCodec.SEQUENCE, "0"),
                e -> e.put(EventCodec.SEQUENCE, "07"),
                e -> e.put(EventCodec.SEQUENCE, "-7"),
                e -> e.put(EventCodec.SEQUENCE, 7),
                e -> e.put(EventCodec.RUN, "not-a-run"))) {
            ObjectNode envelope = (ObjectNode) EventCodec.MAPPER.readTree(EventCodec.encode(event, stamp));
            edit.accept(envelope);
            byte[] mutated = EventCodec.MAPPER.writeValueAsBytes(envelope);
            assertThatThrownBy(() -> EventCodec.envelope(mutated)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new LogStamp(UUID.randomUUID(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventCodec.envelope("[1]".getBytes(UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }

    /** Backlog 113: a grant that names a branch is version 2; one on every ref stays version 1, as before. */
    @Test
    void aGrantNamingABranchIsVersion2AndOneOnEveryRefVersion1() {
        UUID dev = UUID.fromString("b0000000-0000-0000-0000-000000000001");
        CatalogEvent everyRef = SampleEvents.event(new GrantChange(GrantChange.Action.GRANTED, SampleEvents.CATALOG,
                "grp-equities-write", "write", SampleEvents.ACTOR), 1);
        CatalogEvent onDev = SampleEvents.event(new GrantChange(GrantChange.Action.GRANTED, SampleEvents.CATALOG,
                "grp-equities-write", "write", SampleEvents.ACTOR, "dev", dev), 2);

        JsonNode v1 = EventCodec.MAPPER.readTree(EventCodec.encode(everyRef));
        JsonNode v2 = EventCodec.MAPPER.readTree(EventCodec.encode(onDev));

        assertThat(v1.get("dataschema").asString()).isEqualTo("urn:nuna:catalog:event:grant:1");
        assertThat(v1.get("data").has("branch")).isFalse();
        assertThat(v1.get("data").has("branchId")).isFalse();
        assertThat(v2.get("dataschema").asString()).isEqualTo("urn:nuna:catalog:event:grant:2");
        assertThat(v2.get("data").get("branch").asString()).isEqualTo("dev");
        assertThat(v2.get("data").get("branchId").asString()).isEqualTo(dev.toString());
        assertThat(EventCodec.decode(EventCodec.encode(everyRef))).isEqualTo(everyRef);
        assertThat(EventCodec.decode(EventCodec.encode(onDev))).isEqualTo(onDev);
    }

    /** Backlog 113: a credential that writes one branch's prefix only is version 2. */
    @Test
    void aCredentialWritingOneBranchIsVersion2() {
        CatalogEvent branchWriter = SampleEvents.event(new CredentialIssue(SampleEvents.TABLE, "s3-east",
                "s3://lake/t/7f0c/", CredentialIssue.Access.READ_WRITE, "nuna-svc-trino-req-0001",
                SampleEvents.TIME.plusSeconds(900), SampleEvents.ACTOR, "s3://lake/t/7f0c/branches/b0/"), 1);

        JsonNode encoded = EventCodec.MAPPER.readTree(EventCodec.encode(branchWriter));

        assertThat(encoded.get("dataschema").asString()).isEqualTo("urn:nuna:catalog:event:credential-issue:2");
        assertThat(encoded.get("data").get("writePrefix").asString()).isEqualTo("s3://lake/t/7f0c/branches/b0/");
        assertThat(EventCodec.decode(EventCodec.encode(branchWriter))).isEqualTo(branchWriter);
    }

    /** Each version carries exactly its own fields: version 1 none of version 2's, version 2 all of them. */
    @Test
    void eachVersionCarriesItsOwnFieldsOnly() {
        CatalogEvent everyRef = SampleEvents.event(new GrantChange(GrantChange.Action.GRANTED, SampleEvents.CATALOG,
                "grp-equities-write", "write", SampleEvents.ACTOR), 1);
        CatalogEvent onDev = SampleEvents.event(new GrantChange(GrantChange.Action.GRANTED, SampleEvents.CATALOG,
                "grp-equities-write", "write", SampleEvents.ACTOR, "dev", UUID.randomUUID()), 2);
        List<byte[]> broken = List.of(
                mutateData(everyRef, d -> d.putNull("branch").putNull("branchId")),
                mutateData(everyRef, d -> d.put("branch", "dev").put("branchId", UUID.randomUUID().toString())),
                mutateData(onDev, d -> d.remove("branch")),
                mutateData(onDev, d -> d.putNull("branch").putNull("branchId")),
                mutateData(onDev, d -> d.putNull("branchId")));
        for (byte[] json : broken) {
            assertThatThrownBy(() -> EventCodec.decode(json)).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode laterThanKnown = (ObjectNode) EventCodec.MAPPER.readTree(EventCodec.encode(onDev));
        laterThanKnown.put("dataschema", "urn:nuna:catalog:event:grant:3");
        assertThatThrownBy(() -> EventCodec.decode(EventCodec.MAPPER.writeValueAsBytes(laterThanKnown)))
                .isInstanceOf(IllegalArgumentException.class);
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

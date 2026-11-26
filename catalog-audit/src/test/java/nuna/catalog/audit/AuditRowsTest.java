package nuna.catalog.audit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The queryable audit table's rows (D23, amended 2026-11-20), mapped from every line of the golden file: one record
 * of each event type, so a type added without a mapping fails here.
 */
class AuditRowsTest {

    private static final int PARTITION = 3;
    private static final long FIRST_OFFSET = 100;

    private static final Set<EventType> ABOUT_THE_TABLE = EnumSet.of(EventType.COMMIT, EventType.DROP,
            EventType.BREAK_GLASS, EventType.OBJECT_READ, EventType.DENIAL, EventType.CREDENTIAL_ISSUE,
            EventType.RENAME, EventType.GOVERNANCE_CHANGE, EventType.UNDROP, EventType.PURGE, EventType.AUDIT_READ,
            EventType.UNREGISTER, EventType.PURGE_CLAIM, EventType.FILE_DELETION, EventType.MAINTENANCE_EXEMPTION,
            EventType.ROW_COLUMN_RULE_CHANGE);
    private static final Set<EventType> ABOUT_THE_NAMESPACE = EnumSet.of(EventType.NAMESPACE_CHANGE,
            EventType.HOLD_CHANGE, EventType.LABEL_CHANGE);
    private static final Set<EventType> ABOUT_THE_CATALOG = EnumSet.of(EventType.GRANT_CHANGE, EventType.STORAGE_SETUP,
            EventType.REF);
    private static final Set<EventType> ABOUT_NO_OBJECT = EnumSet.of(EventType.AUTH_FAILURE, EventType.ENGINE_CHANGE,
            EventType.RUN_END);

    /** A golden line, its event and its row. */
    private record Mapped(String line, CatalogEvent event, AuditRow row) {
    }

    private static List<Mapped> golden() throws IOException {
        List<String> lines;
        try (InputStream in = AuditRowsTest.class.getResourceAsStream("events-v1.jsonl")) {
            lines = new String(in.readAllBytes(), UTF_8).lines().toList();
        }
        List<Mapped> mapped = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            CatalogEvent event = EventCodec.decode(lines.get(i).getBytes(UTF_8));
            mapped.add(new Mapped(lines.get(i), event, AuditRows.of(event, PARTITION, FIRST_OFFSET + i)));
        }
        return mapped;
    }

    private static Map<EventType, AuditRow> byType() throws IOException {
        Map<EventType, AuditRow> rows = new EnumMap<>(EventType.class);
        golden().forEach(m -> rows.put(m.event().type(), m.row()));
        return rows;
    }

    @Test
    void everyEventTypeHasARow() throws IOException {
        assertThat(byType().keySet()).isEqualTo(EnumSet.allOf(EventType.class));
        assertThat(ABOUT_THE_TABLE.size() + ABOUT_THE_NAMESPACE.size() + ABOUT_THE_CATALOG.size()
                + ABOUT_NO_OBJECT.size()).isEqualTo(EventType.values().length);
    }

    @Test
    void theEnvelopeAndThePayloadAreKeptAsTheTopicCarriesThem() throws IOException {
        List<Mapped> golden = golden();
        for (int i = 0; i < golden.size(); i++) {
            Mapped m = golden.get(i);
            JsonNode envelope = EventCodec.MAPPER.readTree(m.line());
            assertThat(m.row().eventId()).isEqualTo(envelope.get("id").asString());
            assertThat(m.row().type()).isEqualTo(envelope.get("type").asString());
            assertThat(m.row().source()).isEqualTo(envelope.get("source").asString());
            assertThat(m.row().time()).isEqualTo(Instant.parse(envelope.get("time").asString()));
            // data is the envelope's last attribute: the row holds the very bytes the topic carries.
            String data = m.line().substring(m.line().indexOf(",\"data\":") + ",\"data\":".length(),
                    m.line().length() - 1);
            assertThat(m.row().data()).as(m.row().type()).isEqualTo(data);
            assertThat(m.row().topicPartition()).isEqualTo(PARTITION);
            assertThat(m.row().topicOffset()).isEqualTo(FIRST_OFFSET + i);
        }
    }

    @Test
    void everyRequestNamesWhoMadeIt() throws IOException {
        byType().forEach((type, row) -> {
            if (type == EventType.RUN_END) {
                // A server's own record: no request, no one who made it.
                assertThat(row.requestId()).isNull();
                assertThat(row.principal()).isNull();
                return;
            }
            assertThat(row.requestId()).as(type.typeName()).isNotNull();
            if (type == EventType.AUTH_FAILURE) {
                assertThat(row.principal()).isNull();
                assertThat(row.endUser()).isNull();
                assertThat(row.engine()).isNull();
                assertThat(row.requestId()).isEqualTo("req-0002");
            } else {
                assertThat(row.principal()).as(type.typeName()).isEqualTo(SampleEvents.ACTOR.principal());
                assertThat(row.endUser()).as(type.typeName()).isEqualTo(SampleEvents.ACTOR.endUser());
                assertThat(row.engine()).as(type.typeName()).isEqualTo(SampleEvents.ACTOR.engine());
                assertThat(row.requestId()).as(type.typeName()).isEqualTo(SampleEvents.ACTOR.requestId());
            }
        });
    }

    @Test
    void everyRecordNamesWhatItIsAbout() throws IOException {
        byType().forEach((type, row) -> {
            Securable expected = ABOUT_THE_TABLE.contains(type) ? SampleEvents.TABLE
                    : ABOUT_THE_NAMESPACE.contains(type) ? SampleEvents.NAMESPACE
                    : ABOUT_THE_CATALOG.contains(type) ? SampleEvents.CATALOG : null;
            if (expected == null) {
                assertThat(row.catalog()).as(type.typeName()).isNull();
                assertThat(row.objectKind()).as(type.typeName()).isNull();
                assertThat(row.objectId()).as(type.typeName()).isNull();
                assertThat(row.objectPath()).as(type.typeName()).isNull();
            } else {
                assertThat(row.catalog()).as(type.typeName()).isEqualTo(expected.catalog());
                assertThat(row.objectKind()).as(type.typeName()).isEqualTo(expected.kind().name());
                assertThat(row.objectId()).as(type.typeName()).isEqualTo(expected.id().toString());
                assertThat(row.objectPath()).as(type.typeName()).isEqualTo(expected.path());
            }
        });
    }

    @Test
    void operationPrivilegeAndReasonWhereTheTypeHasThem() throws IOException {
        Map<EventType, AuditRow> rows = byType();
        AuditRow denial = rows.get(EventType.DENIAL);
        assertThat(denial.operation()).isEqualTo("updateTable");
        assertThat(denial.privilege()).isEqualTo("modify");
        assertThat(denial.reason()).isEqualTo("missing privilege modify");
        assertThat(rows.get(EventType.GRANT_CHANGE).privilege()).isEqualTo("select");
        assertThat(rows.get(EventType.AUTH_FAILURE).reason()).isEqualTo("token expired");
        assertThat(rows.get(EventType.HOLD_CHANGE).reason()).isEqualTo("matter 2026-114");
        assertThat(rows.get(EventType.MAINTENANCE_EXEMPTION).operation()).isEqualTo("loadTable");

        Set<EventType> withOperation = EnumSet.of(EventType.DENIAL, EventType.MAINTENANCE_EXEMPTION);
        Set<EventType> withPrivilege = EnumSet.of(EventType.DENIAL, EventType.GRANT_CHANGE);
        Set<EventType> withReason = EnumSet.of(EventType.DENIAL, EventType.AUTH_FAILURE, EventType.HOLD_CHANGE);
        rows.forEach((type, row) -> {
            assertThat(row.operation() != null).as(type.typeName()).isEqualTo(withOperation.contains(type));
            assertThat(row.privilege() != null).as(type.typeName()).isEqualTo(withPrivilege.contains(type));
            assertThat(row.reason() != null).as(type.typeName()).isEqualTo(withReason.contains(type));
        });
    }

    @Test
    void aDenialOfNothingInParticularHasNoObject() {
        CatalogEvent event = CatalogEvent.create(SampleEvents.SOURCE, SampleEvents.TIME,
                new Denial("listCatalogs", null, null, "not signed in to any catalog", SampleEvents.ACTOR));
        AuditRow row = AuditRows.of(event, 0, 0);
        assertThat(row.catalog()).isNull();
        assertThat(row.objectId()).isNull();
        assertThat(row.objectPath()).isNull();
        assertThat(row.privilege()).isNull();
        assertThat(row.operation()).isEqualTo("listCatalogs");
        assertThat(row.data()).isEqualTo(new String(EventCodec.encode(event), UTF_8).replaceFirst(".*,\"data\":", "")
                .replaceFirst("}$", ""));
    }

    @Test
    void aRecordFromTheLogKeepsItsRunAndNumberAndOneFromTheTopicHasNeither() throws IOException {
        // Backlog 272: the stamp places a row in its run's archive chain; a record from an older server carries none.
        UUID run = UUID.fromString("a0000000-0000-4000-8000-000000000272");
        for (Mapped m : golden()) {
            byte[] logged = EventCodec.encode(m.event(), new LogStamp(run, 41));
            AuditRow row = AuditRows.of(m.event(), EventCodec.envelope(logged).stamp(), -1, 9);
            assertThat(row.logRun()).as(m.event().type().typeName()).isEqualTo(run.toString());
            assertThat(row.logSequence()).as(m.event().type().typeName()).isEqualTo(41L);
            assertThat(row.logStamp()).contains(new LogStamp(run, 41));
            assertThat(m.row().logRun()).isNull();
            assertThat(m.row().logSequence()).isNull();
            assertThat(m.row().logStamp()).isEmpty();
        }
    }

    @Test
    void aRowCarriesBothItsRunAndItsNumberOrNeither() {
        AuditRow row = AuditRows.of(CatalogEvent.create(SampleEvents.SOURCE, SampleEvents.TIME,
                new Denial("listCatalogs", null, null, "no", SampleEvents.ACTOR)), 0, 0);
        assertThatThrownBy(() -> new AuditRow(row.eventId(), row.time(), row.type(), row.source(), null, null, null,
                null, null, null, null, null, null, null, null, row.data(), 0, 0, "a0000000-0000-4000-8000-000000000272",
                null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commitsReadsAndRefRecordsNameTheRefAndARefRecordListsItsRefAndTables() throws IOException {
        // Backlog 169: the feed filters by branch and by subject without reading payloads.
        Set<EventType> through = EnumSet.of(EventType.COMMIT, EventType.OBJECT_READ, EventType.REF);
        byType().forEach((type, row) -> {
            if (!through.contains(type)) {
                assertThat(row.branch()).as(type.typeName()).isNull();
            }
            if (type != EventType.REF) {
                assertThat(row.subjectIds()).as(type.typeName()).isNull();
            }
        });
        Map<EventType, AuditRow> rows = byType();
        assertThat(rows.get(EventType.COMMIT).branch()).isEqualTo("main");
        assertThat(rows.get(EventType.OBJECT_READ).branch()).isEqualTo("main");
        assertThat(rows.get(EventType.REF).branch()).as("the ref's name as given").isEqualTo("dév");
        assertThat(rows.get(EventType.REF).subjectIds()).as("the ref, then the tables it names")
                .containsExactly("f0000000-0000-0000-0000-000000000001", "7f0c2a52-9d0e-4a8b-9c61-2b1e6f0d4a11");

        UUID ref = UUID.randomUUID();
        AuditRow removed = AuditRows.of(CatalogEvent.create(SampleEvents.SOURCE, SampleEvents.TIME,
                new RefChange(RefChange.Action.OBJECT_REMOVED, ref, "q3", RefChange.Kind.TAG, SampleEvents.CATALOG,
                        "main", null, List.of(SampleEvents.TABLE), SampleEvents.ACTOR)), 0, 0);
        assertThat(removed.objectId()).as("a ref record's object is its catalog").isEqualTo(SampleEvents.CATALOG.id()
                .toString());
        assertThat(removed.subjectIds()).containsExactly(ref.toString(), SampleEvents.TABLE.id().toString());
        AuditRow branchRead = AuditRows.of(CatalogEvent.create(SampleEvents.SOURCE, SampleEvents.TIME,
                new ObjectRead(SampleEvents.TABLE, "Dev-1", SampleEvents.V2, SampleEvents.ACTOR)), 0, 0);
        assertThat(branchRead.branch()).isEqualTo("Dev-1");
    }

    @Test
    void everyRowGivesBackTheRecordAsTheTopicCarriesIt() throws IOException {
        // The audit feed shows each row as its event (backlog 125): the bytes the topic and the archive hold.
        for (Mapped m : golden()) {
            assertThat(new String(EventCodec.encode(AuditRows.event(m.row())), UTF_8)).as(m.event().type().typeName())
                    .isEqualTo(m.line());
        }
    }
}

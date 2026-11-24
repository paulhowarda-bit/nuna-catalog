package nuna.catalog.audit;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/** One fixed event of every type, with non-ASCII names, for golden and round-trip tests. */
public final class SampleEvents {

    public static final URI SOURCE = URI.create("urn:nuna:deployment:test");
    public static final Instant TIME = Instant.parse("2026-11-07T10:15:30.123Z");

    public static final Actor ACTOR = new Actor("svc-trino", "alice@example.com", "trino-shared", "req-0001");
    public static final Securable CATALOG = new Securable(Securable.Kind.CATALOG,
            UUID.fromString("00000000-0000-0000-0000-00000000000c"), "Trading", List.of());
    public static final Securable NAMESPACE = new Securable(Securable.Kind.NAMESPACE,
            UUID.fromString("5a1d0c3e-0000-4000-8000-0000000000a1"), "Trading", List.of("Équités"));
    public static final Securable TABLE = new Securable(Securable.Kind.TABLE,
            UUID.fromString("7f0c2a52-9d0e-4a8b-9c61-2b1e6f0d4a11"), "Trading", List.of("Équités", "Orders"));
    public static final PointerState V1 = new PointerState("s3://lake/t/7f0c/metadata/00001.json", 1, "a".repeat(64));
    public static final PointerState V2 = new PointerState("s3://lake/t/7f0c/metadata/00002.json", 2, "b".repeat(64));

    public static final String UPDATES = "[{\"action\":\"set-properties\",\"updates\":{\"owner\":\"Zoë\"}}]";

    private SampleEvents() {
    }

    public static List<EventData> oneOfEachType() {
        return List.of(
                new Commit(UUID.fromString("c0000000-0000-0000-0000-000000000001"), TABLE, "main", V1, V2, UPDATES,
                        ACTOR),
                new GrantChange(GrantChange.Action.GRANTED, CATALOG, "grp-equities-read", "select", ACTOR),
                new Drop(TABLE, Instant.parse("2026-12-07T10:15:30Z"), ACTOR),
                new BreakGlass(TABLE, "incident INC-42", ACTOR),
                new ObjectRead(TABLE, "main", V2, ACTOR),
                new Denial("updateTable", TABLE, "modify", "missing privilege modify", ACTOR),
                new AuthFailure("https://login.example.com/tenant/v2.0", "u-123", "app-9", "token expired", "req-0002",
                        "10.0.0.7"),
                new CredentialIssue(TABLE, "s3-east", "s3://lake/t/7f0c/", CredentialIssue.Access.READ,
                        "nuna-svc-trino-req-0001", Instant.parse("2026-11-07T10:45:30Z"), ACTOR),
                new NamespaceChange(NamespaceChange.Action.PROPERTIES_CHANGED, NAMESPACE,
                        Map.of("owner", "Zoë", "comment", "desk"), List.of("stale"), ACTOR),
                new Rename(TABLE, List.of("Équités", "Orders_old"), ACTOR),
                new EngineChange(EngineChange.Action.REGISTERED, "https://login.example.com/tenant/v2.0", "trino-shared",
                        "oid", List.of("delegation-none"), ACTOR),
                new StorageSetup(StorageSetup.Action.CATALOG_CREATED, CATALOG.id(), "Trading",
                        Map.of("storage-profile", "s3-east", "kms-key-set", "equities-eu", "legal-entity", "Nuna Bourse SA"),
                        ACTOR),
                new GovernanceChange(GovernanceChange.Action.SET, TABLE, "classification", "confidential",
                        "collibra:asset/7f0c@2026-11-07", ACTOR),
                new HoldChange(HoldChange.Action.PLACED, UUID.fromString("d0000000-0000-0000-0000-000000000001"),
                        NAMESPACE, "matter 2026-114", ACTOR),
                new Undrop(TABLE, ACTOR),
                new Purge(TABLE, ACTOR),
                new AuditRead(TABLE, 50, ACTOR),
                new Unregister(TABLE, V2, ACTOR),
                new LabelChange(NAMESPACE, "classification", "confidential", ACTOR),
                new PurgeClaim(TABLE, ACTOR),
                new FileDeletion(FileDeletion.Action.RECORDED, TABLE, 3, Instant.parse("2026-11-14T10:15:30.123Z"), ACTOR),
                new MaintenanceExemption(TABLE, "loadTable", ACTOR),
                new RefChange(RefChange.Action.MERGED, UUID.fromString("f0000000-0000-0000-0000-000000000001"), "dév",
                        RefChange.Kind.BRANCH, CATALOG, "main", Instant.parse("2026-12-07T10:15:30.123Z"),
                        List.of(TABLE), ACTOR),
                new RowColumnRuleChange(RowColumnRuleChange.Action.SET, TABLE, "grp-equities-read",
                        "desk = 'EQ' AND region = \"région\"", Map.of("ssn", "'***'", "salary", "NULL"), ACTOR));
    }

    /** An event with an id fixed by {@code n}, so its encoding is fixed too. */
    public static CatalogEvent event(EventData data, int n) {
        return new CatalogEvent(new UUID(0xe0e0e0e0L, n), SOURCE, TIME, data);
    }

    public static List<CatalogEvent> events() {
        List<EventData> data = oneOfEachType();
        return IntStream.range(0, data.size()).mapToObj(i -> event(data.get(i), i + 1)).toList();
    }
}

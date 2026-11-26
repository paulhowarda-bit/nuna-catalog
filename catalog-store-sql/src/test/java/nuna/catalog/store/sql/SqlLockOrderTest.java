package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.ScriptedDatabase.row;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.Change;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The order in which the PostgreSQL and CockroachDB writes lock rows, read from the statements they send to a scripted
 * database (docs/storage-design.md section 9 (f) 8). Every write that locks the catalog's row takes it first, before a
 * namespace, an object or a ref, so no two writes take the same locks in opposite orders. The store suites show what
 * the locks serialize, on a real database; this shows the order, which a wrong one would not show until two writes
 * deadlocked.
 */
class SqlLockOrderTest {

    private static final UUID CATALOG = UUID.randomUUID();
    private static final UUID OUTER = UUID.randomUUID();
    private static final UUID NAMESPACE = UUID.randomUUID();
    private static final UUID OBJECT = UUID.randomUUID();
    private static final Change CHANGE = new Change(UUID.randomUUID(), Instant.parse("2026-11-25T09:00:00Z"),
            List.of(new OutboxEvent("test.changed", "{}")), Optional.empty());
    /** Every pointer of the object, by branch: unregister and a removal from refs lock them before the object. */
    private static final String POINTERS = "FROM pointer WHERE object_id = ? ORDER BY branch FOR UPDATE";
    private static final OffsetDateTime DROPPED = OffsetDateTime.of(2026, 11, 1, 9, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void aNamespaceDropLocksTheCatalogForShareBeforeTheNamespaceAndReadsRefsAfterBoth() {
        ScriptedDatabase database = new ScriptedDatabase()
                .answer("SELECT catalog_id FROM namespace WHERE id = ?", row("catalog_id", CATALOG))
                .answer("SELECT 1 FROM catalog WHERE id = ? FOR SHARE", row("one", 1))
                .answer("SELECT catalog_id, ancestors FROM namespace WHERE id = ? FOR UPDATE",
                        row("catalog_id", CATALOG, "ancestors", new UUID[] {OUTER}));

        assertThat(new SqlNamespaces(database.transactions()).drop(NAMESPACE, CHANGE)).isEqualTo(WriteResult.APPLIED);

        int catalog = database.indexOf("FROM catalog WHERE id = ? FOR SHARE");
        int namespace = database.indexOf("FROM namespace WHERE id = ? FOR UPDATE");
        int refs = database.indexOf("JOIN ref_entry");
        assertThat(catalog).as("the catalog's row is locked").isNotNegative();
        assertThat(catalog).as("before the namespace").isLessThan(namespace);
        assertThat(namespace).as("and both before refs are read").isLessThan(refs);
    }

    @Test
    void aPurgeClaimLocksTheCatalogTheObjectAndTheNamespacesAboveItBeforeReadingHolds() {
        ScriptedDatabase database = new ScriptedDatabase()
                .answer("SELECT catalog_id FROM catalog_object WHERE id = ?", row("catalog_id", CATALOG))
                .answer("SELECT 1 FROM catalog WHERE id = ? FOR SHARE", row("one", 1))
                .answer("FROM catalog_object WHERE id = ? AND state IN ('DROPPED', 'PURGING') FOR UPDATE",
                        row("catalog_id", CATALOG, "namespace_path", new UUID[] {OUTER, NAMESPACE},
                                "purge_after", DROPPED, "purge_claimant", null))
                .answer("FROM namespace WHERE id = ANY(?) ORDER BY id FOR SHARE", row("id", OUTER), row("id", NAMESPACE))
                .answer("FROM legal_hold", row("one", 1));

        assertThat(new SqlObjects(database.transactions()).claimPurge(OBJECT, "worker", CHANGE))
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));

        assertLockedInOrderBeforeHolds(database, "FROM catalog WHERE id = ? FOR SHARE",
                "FROM catalog_object WHERE id = ? AND state IN ('DROPPED', 'PURGING') FOR UPDATE",
                "FROM namespace WHERE id = ANY(?) ORDER BY id FOR SHARE");
    }

    @Test
    void aRemovalFromRefsLocksTheCatalogTheRefsThePointersTheObjectAndTheNamespacesAboveItBeforeReadingHolds() {
        UUID ref = UUID.randomUUID();
        ScriptedDatabase database = new ScriptedDatabase()
                .answer("SELECT catalog_id FROM catalog_object WHERE id = ?", row("catalog_id", CATALOG))
                .answer("SELECT 1 FROM catalog WHERE id = ? FOR SHARE", row("one", 1))
                .answer("SELECT 1 FROM ref WHERE id = ? AND catalog_id = ? FOR UPDATE", row("one", 1))
                .answer("SELECT namespace_path FROM catalog_object", row("namespace_path", new UUID[] {OUTER, NAMESPACE}))
                .answer("FROM namespace WHERE id = ANY(?) ORDER BY id FOR SHARE", row("id", OUTER), row("id", NAMESPACE))
                .answer("FROM legal_hold", row("one", 1));

        assertThat(new SqlRefs(database.transactions()).remove(OBJECT, Set.of(ref), false, CHANGE))
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));

        assertLockedInOrderBeforeHolds(database, "FROM catalog WHERE id = ? FOR SHARE",
                "FROM ref WHERE id = ? AND catalog_id = ? FOR UPDATE", POINTERS, "SELECT namespace_path FROM catalog_object",
                "FROM namespace WHERE id = ANY(?) ORDER BY id FOR SHARE");
    }

    @Test
    void anUnregisterLocksThePointersBeforeTheObjectAsARemovalFromRefsDoes() {
        ScriptedDatabase database = new ScriptedDatabase();

        assertThat(new SqlObjects(database.transactions()).unregister(OBJECT, new Pointer("s3://b/t/v1.json", 1, "",
                new byte[0]), CHANGE)).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));

        int pointers = database.indexOf(POINTERS);
        assertThat(pointers).as("the object's pointers are locked").isNotNegative();
        assertThat(pointers).as("before the object")
                .isLessThan(database.indexOf("FROM catalog_object WHERE id = ? AND state = ? FOR UPDATE"));
    }

    @Test
    void aHoldOrAFreezeLocksItsTargetForUpdateAndAnyOtherAttributeForShare() {
        ScriptedDatabase database = new ScriptedDatabase()
                .answer("SELECT catalog_id FROM namespace WHERE id = ?", row("catalog_id", CATALOG))
                .answer("SELECT id FROM catalog WHERE id = ?", row("id", CATALOG));
        SqlGovernance governance = new SqlGovernance(database.transactions());
        Securable namespace = new Securable(Securable.Kind.NAMESPACE, NAMESPACE);
        Securable catalog = new Securable(Securable.Kind.CATALOG, CATALOG);

        assertThat(governance.placeHold(new LegalHold(UUID.randomUUID(), namespace, "matter", "legal", CHANGE.at()),
                CHANGE)).isEqualTo(WriteResult.APPLIED);
        assertThat(governance.putAttribute(new GovernanceAttribute(catalog, GovernanceAttribute.Kind.DR_FREEZE, "drill",
                "api", "admin", CHANGE.at()), CHANGE)).isEqualTo(WriteResult.APPLIED);
        assertThat(governance.putAttribute(new GovernanceAttribute(catalog, GovernanceAttribute.Kind.REGULATED, "true",
                "api", "steward", CHANGE.at()), CHANGE)).isEqualTo(WriteResult.APPLIED);

        assertThat(database.sent()).filteredOn(sql -> sql.startsWith("SELECT")).containsExactly(
                "SELECT version()",
                "SELECT catalog_id FROM namespace WHERE id = ? FOR UPDATE",
                "SELECT id FROM catalog WHERE id = ? FOR UPDATE",
                "SELECT id FROM catalog WHERE id = ? FOR SHARE");
    }

    /** Each of {@code locks} was sent, in that order, and all before the first read of legal holds. */
    private static void assertLockedInOrderBeforeHolds(ScriptedDatabase database, String... locks) {
        int previous = -1;
        for (String lock : locks) {
            int at = database.indexOf(lock);
            assertThat(at).as("%s, after the lock before it", lock).isGreaterThan(previous);
            previous = at;
        }
        assertThat(database.indexOf("FROM legal_hold")).as("holds are read after every lock").isGreaterThan(previous);
    }
}

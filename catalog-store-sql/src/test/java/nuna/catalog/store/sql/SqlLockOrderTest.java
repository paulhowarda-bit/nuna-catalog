package nuna.catalog.store.sql;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.Change;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.WriteResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final UUID NAMESPACE = UUID.randomUUID();
    private static final Change CHANGE = new Change(UUID.randomUUID(), Instant.parse("2026-11-25T09:00:00Z"),
            List.of(new OutboxEvent("test.changed", "{}")), Optional.empty());

    @Test
    void aNamespaceDropLocksTheCatalogForShareBeforeTheNamespaceAndReadsRefsAfterBoth() {
        ScriptedDatabase database = new ScriptedDatabase()
                .answer("SELECT catalog_id FROM namespace WHERE id = ?", Map.of("catalog_id", CATALOG))
                .answer("SELECT 1 FROM catalog WHERE id = ? FOR SHARE", Map.of("one", 1))
                .answer("SELECT catalog_id, ancestors FROM namespace WHERE id = ? FOR UPDATE",
                        Map.of("catalog_id", CATALOG, "ancestors", new UUID[0]));

        assertThat(new SqlNamespaces(database.transactions()).drop(NAMESPACE, CHANGE)).isEqualTo(WriteResult.APPLIED);

        int catalog = database.indexOf("FROM catalog WHERE id = ? FOR SHARE");
        int namespace = database.indexOf("FROM namespace WHERE id = ? FOR UPDATE");
        int refs = database.indexOf("JOIN ref_entry");
        assertThat(catalog).as("the catalog's row is locked").isNotNegative();
        assertThat(catalog).as("before the namespace").isLessThan(namespace);
        assertThat(namespace).as("and both before refs are read").isLessThan(refs);
    }
}

package nuna.datagen.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nuna.datagen.world.MonthEnd;
import nuna.datagen.world.Played;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SessionCatalog;
import org.apache.iceberg.catalog.TableCommit;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.RESTCatalogAdapter;
import org.junit.jupiter.api.Test;

/**
 * The month-end close's writer (item 153) against a REST catalog over an in-memory one: the close is one multi-table
 * commit and a tag; the bust's restatement goes on a branch merged into main; read at the tag, the statements are the
 * ones first published, and on main the restated ones. Replayed, neither writes again.
 */
class MonthEndWriterTest {

    /** A REST client that counts its multi-table commits. */
    private static final class Counting extends RESTCatalog {
        int transactions;

        Counting(RESTCatalogAdapter adapter) {
            super(SessionCatalog.SessionContext.createEmpty(), conf -> adapter);
        }

        @Override
        public void commitTransaction(List<TableCommit> commits) {
            transactions++;
            super.commitTransaction(commits);
        }
    }

    private static RESTCatalog client(InMemoryCatalog backend, RESTCatalog c) {
        c.initialize("test", Map.of(CatalogProperties.FILE_IO_IMPL, InMemoryFileIO.class.getName()));
        return c;
    }

    private static InMemoryCatalog backend(String name) {
        InMemoryCatalog b = new InMemoryCatalog();
        b.initialize(name, Map.of(CatalogProperties.WAREHOUSE_LOCATION, "mem://" + name));
        return b;
    }

    private static TableOperations ops(InMemoryCatalog c, TableIdentifier id) {
        return ((HasTableOperations) c.loadTable(id)).operations();
    }

    /** Catalog refs as nuna keeps them, over in-memory catalogs: a tag pins each table, a branch is a catalog. */
    private static final class Refs implements MonthEndWriter.Refs {
        final InMemoryCatalog main;
        final Map<String, Map<TableIdentifier, Long>> tags = new HashMap<>();
        final Map<String, InMemoryCatalog> branches = new HashMap<>();
        final Map<String, Map<TableIdentifier, String>> points = new HashMap<>();
        final List<String> merged = new ArrayList<>();

        Refs(InMemoryCatalog main) {
            this.main = main;
        }

        private List<TableIdentifier> tables() {
            List<TableIdentifier> ids = new ArrayList<>();
            for (MonthEnd.Table t : MonthEnd.Table.values()) {
                ids.add(IcebergLake.id(t.lakeTable()));
            }
            return ids;
        }

        @Override
        public boolean exists(String ref) {
            return tags.containsKey(ref) || branches.containsKey(ref);
        }

        @Override
        public void createTag(String name, String from, String description) {
            Map<TableIdentifier, Long> pinned = new HashMap<>();
            for (TableIdentifier id : tables()) {
                Table t = main.loadTable(id);
                if (t.currentSnapshot() != null) {
                    pinned.put(id, t.currentSnapshot().snapshotId());
                }
            }
            tags.put(name, pinned);
        }

        @Override
        public void createBranch(String name, String from, String description) {
            InMemoryCatalog b = backend(name);
            Map<TableIdentifier, String> point = new HashMap<>();
            for (TableIdentifier id : tables()) {
                if (!b.namespaceExists(id.namespace())) {
                    b.createNamespace(id.namespace());
                }
                String location = ops(main, id).current().metadataFileLocation();
                b.registerTable(id, location);
                point.put(id, location);
            }
            branches.put(name, b);
            points.put(name, point);
        }

        @Override
        public void delete(String name) {
            branches.remove(name);
            tags.remove(name);
        }

        @Override
        public void merge(String branch) {
            InMemoryCatalog b = branches.remove(branch);
            for (TableIdentifier id : tables()) {
                TableMetadata changed = ops(b, id).current();
                if (changed.metadataFileLocation().equals(points.get(branch).get(id))) {
                    continue;
                }
                TableOperations m = ops(main, id);
                assertThat(m.current().metadataFileLocation()).as("main moved since the branch").isEqualTo(
                    points.get(branch).get(id));
                m.commit(m.current(), changed);
            }
            merged.add(branch);
        }

        @Override
        public RESTCatalog onBranch(String branch) {
            return client(branches.get(branch), new RESTCatalog(SessionCatalog.SessionContext.createEmpty(),
                conf -> new RESTCatalogAdapter(branches.get(branch))));
        }
    }

    private static List<Record> read(Table t, Long snapshot) throws IOException {
        List<Record> out = new ArrayList<>();
        IcebergGenerics.ScanBuilder scan = IcebergGenerics.read(t);
        if (snapshot != null) {
            scan = scan.useSnapshot(snapshot);
        }
        try (CloseableIterable<Record> rows = scan.build()) {
            rows.forEach(out::add);
        }
        return out;
    }

    @Test
    void theCloseIsOneCommitAndATagAndTheRestatementIsMergedWhileTheTagKeepsTheFirstStatements() throws IOException {
        Played p = Played.closing(true);
        MonthEnd close = p.closes.getFirst();
        MonthEnd.Restatement r = p.restatements.getFirst();

        InMemoryCatalog main = backend("lakehouse");
        main.createNamespace(Namespace.of("brokerage"));
        for (MonthEnd.Table t : MonthEnd.Table.values()) {
            Namespace ns = IcebergLake.id(t.lakeTable()).namespace();
            if (!main.namespaceExists(ns)) {
                main.createNamespace(ns);
            }
        }
        Counting catalog = (Counting) client(main, new Counting(new RESTCatalogAdapter(main)));
        Refs refs = new Refs(main);
        MonthEndWriter writer = new MonthEndWriter(catalog, refs);
        writer.bootstrap();

        writer.write(close);
        assertThat(catalog.transactions).isEqualTo(1);
        assertThat(refs.tags).containsOnlyKeys("month-end-2026-09");
        for (MonthEnd.Table t : MonthEnd.Table.values()) {
            Table table = catalog.loadTable(IcebergLake.id(t.lakeTable()));
            if (close.rows().get(t).isEmpty()) {
                assertThat(table.currentSnapshot()).as(t.lakeTable()).isNull();
                continue;
            }
            assertThat(table.snapshots()).as(t.lakeTable()).hasSize(1);
            assertThat(table.currentSnapshot().summary()).containsEntry(MonthEndWriter.CLOSE, "2026-09");
            assertThat(read(table, null)).as(t.lakeTable()).hasSize(close.rows().get(t).size());
        }

        writer.restated(r);
        assertThat(refs.merged).containsExactly("restate-2026-09");
        assertThat(catalog.transactions).isEqualTo(1);

        TableIdentifier statements = IcebergLake.id(MonthEnd.Table.STATEMENTS.lakeTable());
        Table onMain = catalog.loadTable(statements);
        Long tagged = refs.tags.get("month-end-2026-09").get(statements);
        Map<Long, BigDecimal> before = payable(read(onMain, tagged));
        Map<Long, BigDecimal> after = payable(read(onMain, null));
        assertThat(after).hasSameSizeAs(before);
        BigDecimal value = r.rows().get(MonthEnd.Table.STATEMENTS).stream()
            .filter(s -> s.get("brokerage_account").equals(r.brokerageAccount())).map(s -> (BigDecimal) s.get(
                "unsettled_purchases_payable")).findFirst().orElseThrow();
        for (Map.Entry<Long, BigDecimal> e : before.entrySet()) {
            if (e.getKey() == r.brokerageAccount()) {
                assertThat(after.get(e.getKey())).isEqualByComparingTo(value).isLessThan(e.getValue());
            } else {
                assertThat(after.get(e.getKey())).as("account " + e.getKey()).isEqualByComparingTo(e.getValue());
            }
        }
        // At the tag, the statements as first published: what the close computed.
        Map<String, Object> published = close.rows().get(MonthEnd.Table.STATEMENTS).stream()
            .filter(s -> s.get("brokerage_account").equals(r.brokerageAccount())).findFirst().orElseThrow();
        assertThat(before.get(r.brokerageAccount()))
            .isEqualByComparingTo((BigDecimal) published.get("unsettled_purchases_payable"));

        // Replayed after a restart, the close and the restatement are done already.
        int snapshots = onMain.snapshots().iterator().hasNext() ? count(onMain) : 0;
        writer.write(close);
        writer.restated(r);
        assertThat(catalog.transactions).isEqualTo(1);
        assertThat(count(catalog.loadTable(statements))).isEqualTo(snapshots);
        assertThat(refs.merged).hasSize(1);
    }

    private static int count(Table t) {
        int n = 0;
        for (var ignored : t.snapshots()) {
            n++;
        }
        return n;
    }

    private static Map<Long, BigDecimal> payable(List<Record> statements) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Record s : statements) {
            out.put((Long) s.getField("brokerage_account"), (BigDecimal) s.getField("unsettled_purchases_payable"));
        }
        return out;
    }
}

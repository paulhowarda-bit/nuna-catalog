package keel.catalog.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableCommit;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/** The stock Iceberg Java client works against our /v1/config response, prefix and routes. */
class IcebergClientConfigTest {

    @Test
    void stockClientCreatesAndLoadsATableUnderTheCatalogPrefix() throws Exception {
        Map<String, String> properties = new HashMap<>(ConformanceServer.clientStorage());
        properties.put(CatalogProperties.URI, ConformanceServer.start().toString());
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, ConformanceServer.WAREHOUSE);
        properties.put("token", ConformanceServer.token());
        try (RESTCatalog client = new RESTCatalog()) {
            client.setConf(new Configuration());
            client.initialize("probe", properties);

            Namespace namespace = Namespace.of("probe_" + System.nanoTime());
            client.createNamespace(namespace);
            TableIdentifier id = TableIdentifier.of(namespace, "t");
            client.createTable(id, new Schema(Types.NestedField.required(1, "id", Types.LongType.get())));
            Table table = client.loadTable(id);

            assertThat(client.listNamespaces()).contains(namespace);
            assertThat(table.location()).startsWith("s3://" + ConformanceServer.bucket() + "/tables/");
            assertThat(client.dropTable(id)).isTrue();
            assertThat(client.tableExists(id)).isFalse();
        }
    }

    /**
     * D15: the client sends a multi-table commit only when /v1/config advertises the endpoint. Both tables
     * change together; a commit whose requirement fails for either changes neither.
     */
    @Test
    void stockClientCommitsSeveralTablesAtomically() throws Exception {
        try (RESTCatalog client = client()) {
            Namespace namespace = Namespace.of("txn_" + System.nanoTime());
            client.createNamespace(namespace);
            Schema schema = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
            TableIdentifier orders = TableIdentifier.of(namespace, "orders");
            TableIdentifier lines = TableIdentifier.of(namespace, "lines");
            client.createTable(orders, schema);
            client.createTable(lines, schema);
            TableMetadata ordersBase = current(client, orders);
            TableMetadata linesBase = current(client, lines);

            client.commitTransaction(
                    TableCommit.create(orders, ordersBase, withProperty(ordersBase, "batch", "1")),
                    TableCommit.create(lines, linesBase, withProperty(linesBase, "batch", "1")));

            assertThat(client.loadTable(orders).properties()).containsEntry("batch", "1");
            assertThat(client.loadTable(lines).properties()).containsEntry("batch", "1");

            // Another writer adds a column to lines after this client read it, so the client's schema change,
            // built on what it read, asserts a last column id that no longer holds.
            TableMetadata ordersNow = current(client, orders);
            TableMetadata linesRead = current(client, lines);
            client.loadTable(lines).updateSchema().addColumn("other", Types.StringType.get()).commit();
            Schema withNote = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()),
                    Types.NestedField.optional(2, "note", Types.StringType.get()));
            TableMetadata stale = TableMetadata.buildFrom(linesRead).setCurrentSchema(withNote, 2).build();
            assertThatThrownBy(() -> client.commitTransaction(
                    TableCommit.create(orders, ordersNow, withProperty(ordersNow, "batch", "2")),
                    TableCommit.create(lines, linesRead, stale)))
                    .isInstanceOf(CommitFailedException.class);
            assertThat(client.loadTable(orders).properties()).containsEntry("batch", "1");
            assertThat(client.loadTable(lines).schema().findField("note")).isNull();
        }
    }

    private static RESTCatalog client() throws Exception {
        Map<String, String> properties = new HashMap<>(ConformanceServer.clientStorage());
        properties.put(CatalogProperties.URI, ConformanceServer.start().toString());
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, ConformanceServer.WAREHOUSE);
        properties.put("token", ConformanceServer.token());
        RESTCatalog client = new RESTCatalog();
        client.setConf(new Configuration());
        client.initialize("probe", properties);
        return client;
    }

    private static TableMetadata current(RESTCatalog client, TableIdentifier id) {
        return ((HasTableOperations) client.loadTable(id)).operations().current();
    }

    private static TableMetadata withProperty(TableMetadata base, String key, String value) {
        return TableMetadata.buildFrom(base).setProperties(Map.of(key, value)).build();
    }
}

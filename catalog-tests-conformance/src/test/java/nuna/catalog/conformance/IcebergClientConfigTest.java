package nuna.catalog.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SupportsLabels;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableCommit;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.view.BaseView;
import org.apache.iceberg.view.View;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The stock Iceberg Java client works against our /v1/config response, prefix and routes. */
@Tag("heavy")
class IcebergClientConfigTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

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

    /**
     * D13 and D27, amended 2026-11-12: the stock client registers a view from its metadata file when /v1/config
     * advertises register-view (a dropped view's own file brings it back as itself), and reads the labels a table
     * load sends through {@link SupportsLabels}.
     */
    @Test
    void stockClientRegistersAViewAndReadsTableLabels() throws Exception {
        try (RESTCatalog client = client()) {
            Namespace namespace = Namespace.of("labels_" + System.nanoTime());
            client.createNamespace(namespace);
            Schema schema = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
            TableIdentifier tableId = TableIdentifier.of(namespace, "t");
            client.createTable(tableId, schema);
            assertThat(((SupportsLabels) client.loadTable(tableId)).labels().objectLabels()).isEmpty();

            HttpResponse<String> set = HTTP.send(HttpRequest.newBuilder(ConformanceServer.start()
                            .resolve("management/v1/catalogs/" + ConformanceServer.WAREHOUSE + "/namespaces/"
                                    + namespace.level(0) + "/attributes/classification"))
                    .header("Authorization", "Bearer " + ConformanceServer.stewardToken())
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"internal\",\"provenance\":\"rck\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
            assertThat(((SupportsLabels) client.loadTable(tableId)).labels().objectLabels())
                    .isEqualTo(Map.of("classification", "internal"));

            TableIdentifier viewId = TableIdentifier.of(namespace, "v");
            View view = client.buildView(viewId).withSchema(schema).withDefaultNamespace(namespace)
                    .withQuery("spark", "select 1 as id").create();
            String file = ((BaseView) view).operations().current().metadataFileLocation();
            assertThat(client.dropView(viewId)).isTrue();

            TableIdentifier registeredId = TableIdentifier.of(namespace, "registered");
            View registered = client.registerView(registeredId, file);

            assertThat(registered.uuid()).isEqualTo(view.uuid());
            assertThat(client.loadView(registeredId).currentVersion().versionId())
                    .isEqualTo(view.currentVersion().versionId());
            assertThat(client.viewExists(viewId)).isFalse();
        }
    }

    /**
     * D33 (items 104 to 106): on {@code catalog@tag} the stock client reads a table as the tag took it, made through
     * item 106's route, and may not commit; on {@code catalog@branch} it commits two tables in one transaction and
     * appends a data file, then reads the branch's files and rows back, while main does not move.
     */
    @Test
    void stockClientReadsATagAndCommitsOnABranch() throws Exception {
        try (RESTCatalog client = client()) {
            Namespace namespace = Namespace.of("refs_" + System.nanoTime());
            client.createNamespace(namespace);
            Schema schema = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
            TableIdentifier orders = TableIdentifier.of(namespace, "orders");
            TableIdentifier lines = TableIdentifier.of(namespace, "lines");
            client.createTable(orders, schema);
            client.createTable(lines, schema);
            client.loadTable(orders).updateProperties().set("stage", "cut").commit();
            String tag = "t" + System.nanoTime();
            String branch = "b" + System.nanoTime();
            makeRef(tag, "tag");
            makeRef(branch, "branch");
            client.loadTable(orders).updateProperties().set("stage", "main-since").commit();

            try (RESTCatalog onTag = client(ConformanceServer.WAREHOUSE + "@" + tag)) {
                Table tagged = onTag.loadTable(orders);
                assertThat(tagged.properties()).containsEntry("stage", "cut");
                assertThatThrownBy(() -> tagged.updateProperties().set("batch", "0").commit())
                        .isInstanceOf(BadRequestException.class).hasMessageContaining("a tag is read-only");
            }
            String appended;
            try (RESTCatalog onBranch = client(ConformanceServer.WAREHOUSE + "@" + branch)) {
                TableMetadata ordersBase = current(onBranch, orders);
                TableMetadata linesBase = current(onBranch, lines);
                onBranch.commitTransaction(
                        TableCommit.create(orders, ordersBase, withProperty(ordersBase, "batch", "1")),
                        TableCommit.create(lines, linesBase, withProperty(linesBase, "batch", "1")));
                Table branched = onBranch.loadTable(orders);
                appended = branched.location() + "/data/rows-" + System.nanoTime() + ".parquet";
                DataFile rows = DataFiles.builder(PartitionSpec.unpartitioned()).withPath(appended)
                        .withFormat(FileFormat.PARQUET).withFileSizeInBytes(1024).withRecordCount(3).build();
                branched.newAppend().appendFile(rows).commit();

                Table readBack = onBranch.loadTable(orders);
                assertThat(readBack.properties()).containsEntry("batch", "1").containsEntry("stage", "cut");
                assertThat(readBack.currentSnapshot()).isNotNull();
                assertThat(files(readBack)).singleElement().satisfies(file -> {
                    assertThat(file.location()).isEqualTo(appended);
                    assertThat(file.recordCount()).isEqualTo(3);
                });
                assertThat(onBranch.loadTable(lines).properties()).containsEntry("batch", "1");
                assertThat(current(onBranch, orders).metadataFileLocation()).contains(".branch-");
            }

            Table main = client.loadTable(orders);
            assertThat(main.properties()).containsEntry("stage", "main-since").doesNotContainKey("batch");
            assertThat(main.currentSnapshot()).isNull();
            assertThat(files(main)).isEmpty();
            assertThat(client.loadTable(lines).properties()).doesNotContainKey("batch");
            assertThat(current(client, orders).metadataFileLocation()).doesNotContain(".branch-");
        }
    }

    /** Makes a ref of the catalog through the management route (item 106). */
    private static void makeRef(String name, String kind) throws Exception {
        HttpResponse<String> made = HTTP.send(HttpRequest.newBuilder(ConformanceServer.start()
                        .resolve("management/v1/catalogs/" + ConformanceServer.WAREHOUSE + "/refs"))
                .header("Authorization", "Bearer " + ConformanceServer.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(made.statusCode()).as(made.body()).isBetween(200, 201);
    }

    /** The data files a scan of the table's current snapshot plans. */
    private static List<DataFile> files(Table table) throws Exception {
        List<DataFile> files = new ArrayList<>();
        try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
            tasks.forEach(task -> files.add(task.file()));
        }
        return files;
    }

    private static RESTCatalog client() throws Exception {
        return client(ConformanceServer.WAREHOUSE);
    }

    /** A stock client on {@code warehouse}: the catalog, or {@code catalog@ref}. */
    private static RESTCatalog client(String warehouse) throws Exception {
        Map<String, String> properties = new HashMap<>(ConformanceServer.clientStorage());
        properties.put(CatalogProperties.URI, ConformanceServer.start().toString());
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, warehouse);
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

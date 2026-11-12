package keel.catalog.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
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
}

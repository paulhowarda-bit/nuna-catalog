package fidice.catalog.conformance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.Test;

/** The stock Iceberg Java client accepts our /v1/config response and uses its prefix. */
class IcebergClientConfigTest {

    @Test
    void stockClientInitializesAndUsesTheCatalogPrefix() throws Exception {
        try (RESTCatalog client = new RESTCatalog()) {
            client.setConf(new Configuration());
            client.initialize("probe", Map.of(
                    CatalogProperties.URI, ConformanceServer.start().toString(),
                    CatalogProperties.WAREHOUSE_LOCATION, ConformanceServer.WAREHOUSE));

            // M0 serves no namespace route: the client's first call reaches our 404 handler
            // under the prefix our config returned.
            assertThatThrownBy(client::listNamespaces)
                    .hasMessageContaining("v1/" + ConformanceServer.WAREHOUSE + "/namespaces");
        }
    }
}

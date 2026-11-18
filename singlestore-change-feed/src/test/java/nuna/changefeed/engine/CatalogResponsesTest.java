package nuna.changefeed.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.LoadTableResponseParser;
import org.junit.jupiter.api.Test;

/**
 * What the feed reads from the catalog is table loads, through its pinned Iceberg client; it reads none of the
 * catalog's change-stream or audit events, so the UNREGISTER and label-change types of 2026-11-12 never reach it.
 * The loads it does read now carry labels (D27, amended 2026-11-12), and may carry fields a later catalog adds.
 */
class CatalogResponsesTest {

    private static final String LOAD = """
            {"metadata-location":"s3://lake/tables/t/metadata/00001.metadata.json",
             "metadata":{"format-version":2,"table-uuid":"6d2b5f24-c705-4f23-852d-f3223b03a082",
              "location":"s3://lake/tables/t","last-sequence-number":0,"last-updated-ms":1794540991306,
              "last-column-id":1,"current-schema-id":0,
              "schemas":[{"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"}]}],
              "default-spec-id":0,"partition-specs":[{"spec-id":0,"fields":[]}],"last-partition-id":999,
              "default-sort-order-id":0,"sort-orders":[{"order-id":0,"fields":[]}],"properties":{},
              "current-snapshot-id":-1,"refs":{},"snapshots":[],"statistics":[],"partition-statistics":[],
              "snapshot-log":[],"metadata-log":[]},
             "config":{},
             "labels":{"object-labels":{"classification":"internal","owner":"desk-7"}},
             "a-field-a-later-catalog-adds":{"anything":[1,2,3]}}""";

    @Test
    void thePinnedClientReadsALoadWithLabelsAndFieldsItDoesNotKnow() {
        LoadTableResponse response = LoadTableResponseParser.fromJson(LOAD);

        assertThat(response.tableMetadata().uuid()).isEqualTo("6d2b5f24-c705-4f23-852d-f3223b03a082");
        assertThat(response.labels().objectLabels()).isEqualTo(Map.of("classification", "internal", "owner", "desk-7"));
    }
}

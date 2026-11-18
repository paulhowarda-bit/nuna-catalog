package nuna.catalog.core.commit;

import nuna.catalog.iceberg.Facts;
import nuna.catalog.iceberg.Facts.TableFacts;
import nuna.catalog.iceberg.Facts.ViewFacts;
import java.nio.charset.StandardCharsets;

/**
 * The compact projection stored beside each pointer (D5): enough to list and describe an object
 * without reading its metadata file. Every value is a UUID or a number, so no escaping is needed.
 */
final class Projection {

    private Projection() {
    }

    static byte[] of(Facts facts) {
        String json = switch (facts) {
            case TableFacts t -> "{\"kind\":\"table\",\"uuid\":\"" + t.uuid() + "\",\"format-version\":" + t.formatVersion()
                    + ",\"current-schema-id\":" + t.currentSchemaId() + ",\"default-spec-id\":" + t.defaultSpecId()
                    + ",\"snapshots\":" + t.snapshotTimestamps().size() + "}";
            case ViewFacts v -> "{\"kind\":\"view\",\"uuid\":\"" + v.uuid() + "\",\"format-version\":" + v.formatVersion()
                    + ",\"current-schema-id\":" + v.currentSchemaId() + ",\"current-version-id\":"
                    + v.currentVersionId() + "}";
        };
        return json.getBytes(StandardCharsets.UTF_8);
    }
}

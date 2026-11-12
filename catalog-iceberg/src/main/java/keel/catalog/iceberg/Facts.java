package keel.catalog.iceberg;

import java.util.Map;

/**
 * The parts of table or view metadata that the rules layer and the journal read (D11, D11a, D24, D26),
 * in plain types, so nothing outside this module needs iceberg-core to inspect a change.
 */
public sealed interface Facts {

    String uuid();

    int formatVersion();

    String location();

    Map<String, String> properties();

    /** @param snapshotTimestamps snapshot ID to timestamp-ms, in metadata order */
    record TableFacts(String uuid, int formatVersion, String location, Map<String, String> properties,
                      int currentSchemaId, int defaultSpecId, Map<Long, Long> snapshotTimestamps)
            implements Facts {
    }

    record ViewFacts(String uuid, int formatVersion, String location, Map<String, String> properties,
                     int currentSchemaId, int currentVersionId) implements Facts {
    }
}

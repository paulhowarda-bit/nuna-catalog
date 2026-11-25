package nuna.catalog.iceberg;

import java.util.List;
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

    /**
     * @param snapshotTimestamps snapshot ID to timestamp-ms, in metadata order
     * @param columns            the current schema's top-level columns, by name, in schema order: what a column mask
     *                           or row filter names (D20, backlog 240)
     */
    record TableFacts(String uuid, int formatVersion, String location, Map<String, String> properties,
                      int currentSchemaId, int defaultSpecId, Map<Long, Long> snapshotTimestamps,
                      List<String> columns) implements Facts {

        public TableFacts {
            columns = List.copyOf(columns);
        }
    }

    record ViewFacts(String uuid, int formatVersion, String location, Map<String, String> properties,
                     int currentSchemaId, int currentVersionId) implements Facts {
    }
}

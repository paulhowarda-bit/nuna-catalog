package nuna.audittables;

import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;

/**
 * The catalog the writer's Java-only tests write to: Iceberg's in-memory catalog, whose next commit that adds rows can
 * be made to fail, before it lands or after (its answer lost).
 */
final class FaultyCatalog extends InMemoryCatalog {

    enum Fault {
        /** The commit never reaches the catalog: nothing lands. */
        LOST,
        /** The commit lands, but its answer is lost: the writer cannot tell whether it did. */
        ANSWER_LOST
    }

    private Fault next;
    private int faults;

    FaultyCatalog() {
        initialize("audit", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "mem://" + UUID.randomUUID()));
    }

    /** Makes the next commit that adds rows fail so. */
    synchronized void failNextRows(Fault fault) {
        next = fault;
    }

    /** How many commits were made to fail. */
    synchronized int faults() {
        return faults;
    }

    private synchronized Fault take(TableMetadata base, TableMetadata metadata) {
        Snapshot snapshot = metadata.currentSnapshot();
        boolean addsRows = snapshot != null && (base == null || base.currentSnapshot() == null
                || base.currentSnapshot().snapshotId() != snapshot.snapshotId())
                && Long.parseLong(snapshot.summary().getOrDefault("added-records", "0")) > 0;
        Fault fault = addsRows ? next : null;
        if (fault != null) {
            next = null;
            faults++;
        }
        return fault;
    }

    @Override
    protected TableOperations newTableOps(TableIdentifier identifier) {
        TableOperations ops = super.newTableOps(identifier);
        return new TableOperations() {
            @Override
            public TableMetadata current() {
                return ops.current();
            }

            @Override
            public TableMetadata refresh() {
                return ops.refresh();
            }

            @Override
            public void commit(TableMetadata base, TableMetadata metadata) {
                Fault fault = take(base, metadata);
                if (fault == Fault.LOST) {
                    // Not a CommitFailedException, which Iceberg's client would retry at once.
                    throw new IllegalStateException("injected: the catalog never received the commit");
                }
                ops.commit(base, metadata);
                if (fault == Fault.ANSWER_LOST) {
                    throw new CommitStateUnknownException(new IllegalStateException("injected: the answer was lost"));
                }
            }

            @Override
            public FileIO io() {
                return ops.io();
            }

            @Override
            public EncryptionManager encryption() {
                return ops.encryption();
            }

            @Override
            public String metadataFileLocation(String fileName) {
                return ops.metadataFileLocation(fileName);
            }

            @Override
            public LocationProvider locationProvider() {
                return ops.locationProvider();
            }

            @Override
            public TableOperations temp(TableMetadata uncommittedMetadata) {
                return ops.temp(uncommittedMetadata);
            }

            @Override
            public long newSnapshotId() {
                return ops.newSnapshotId();
            }
        };
    }
}

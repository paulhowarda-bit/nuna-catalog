package nuna.catalog.store.sql;

import nuna.catalog.store.StoreException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The PostgreSQL-wire database behind a store, told apart by {@code version()}. The two share
 * the statements of the store; they differ in schema steps, self-check, durability barrier and
 * journal partitioning (docs/storage-design.md sections 5.1, 5.4 and 7). YugabyteDB speaks the
 * same protocol and is refused: it is not a supported backend (decision-ledger.md D7).
 */
enum Backend {

    /** The shared steps, then the journal's partitioning. */
    POSTGRESQL(new String[] {Locations.SHARED, "classpath:nuna/store/sql/postgresql"}, true, true, true),
    /** Its own steps, in CockroachDB's dialect; its journal splits into ranges by itself. */
    COCKROACHDB(new String[] {"classpath:nuna/store/sql/cockroachdb"}, false, false, false);

    private static final class Locations {
        /** Not a parent of the other locations: Flyway scans a location recursively. */
        static final String SHARED = "classpath:nuna/store/sql/migration";
    }

    /** Where Flyway finds this backend's schema steps. */
    final String[] migrations;
    /**
     * A commit can be visible before it is durable: PostgreSQL shows a commit whose wait for the
     * synchronous standby was cancelled, and answers that COMMIT with a warning. On CockroachDB
     * a visible commit is on a quorum, and a warning on COMMIT says nothing about it.
     */
    final boolean needsDurabilityBarrier;
    /** The journal is range-partitioned by month, and its horizon is extended ahead of time. */
    final boolean partitionsJournal;
    /**
     * A schema step runs in one transaction. CockroachDB commits each DDL statement by itself, so
     * a transaction would undo nothing of a failed step.
     */
    final boolean transactionalSchemaSteps;

    Backend(String[] migrations, boolean needsDurabilityBarrier, boolean partitionsJournal,
            boolean transactionalSchemaSteps) {
        this.migrations = migrations;
        this.needsDurabilityBarrier = needsDurabilityBarrier;
        this.partitionsJournal = partitionsJournal;
        this.transactionalSchemaSteps = transactionalSchemaSteps;
    }

    static Backend of(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT version()")) {
            row.next();
            return of(row.getString(1));
        }
    }

    /**
     * CockroachDB answers "CockroachDB CCL v…", YugabyteDB "PostgreSQL 15.12-YB-2025.2…". YugabyteDB
     * is refused, not taken for PostgreSQL: PostgreSQL's journal partitioning breaks on it, and
     * PostgreSQL's self-check and durability barrier say nothing true about it.
     */
    static Backend of(String version) {
        if (version.startsWith("CockroachDB")) {
            return COCKROACHDB;
        }
        if (version.contains("-YB-")) {
            throw new StoreException("YugabyteDB is not a supported backend (the server reports \"" + version
                    + "\"); the supported backends are PostgreSQL, CockroachDB, Oracle and MongoDB", null);
        }
        return POSTGRESQL;
    }
}

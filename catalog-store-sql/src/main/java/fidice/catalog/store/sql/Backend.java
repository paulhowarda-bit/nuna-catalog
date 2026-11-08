package fidice.catalog.store.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The PostgreSQL-wire database behind a store, told apart by {@code version()}. The three share
 * the statements of the store; they differ in schema steps, self-check, durability barrier and
 * journal partitioning (docs/storage-design.md sections 5.1, 5.4, 5.5 and 7).
 */
enum Backend {

    /** The shared steps, then the journal's partitioning. */
    POSTGRESQL(new String[] {Locations.SHARED, "classpath:fidice/store/sql/postgresql"}, true, true, true),
    /**
     * PostgreSQL's steps but not the journal's partitioning: on YugabyteDB the renames it needs
     * invalidate every open transaction, and its journal splits into tablets by itself (section 5.5).
     */
    YUGABYTEDB(new String[] {Locations.SHARED}, false, false, false),
    /** Its own steps, in CockroachDB's dialect; its journal splits into ranges by itself. */
    COCKROACHDB(new String[] {"classpath:fidice/store/sql/cockroachdb"}, false, false, false);

    private static final class Locations {
        /** Not a parent of the other locations: Flyway scans a location recursively. */
        static final String SHARED = "classpath:fidice/store/sql/migration";
    }

    /** Where Flyway finds this backend's schema steps. */
    final String[] migrations;
    /**
     * A commit can be visible before it is durable: PostgreSQL shows a commit whose wait for the
     * synchronous standby was cancelled, and answers that COMMIT with a warning. On the Raft
     * backends a visible commit is on a quorum, and a warning on COMMIT says nothing about it.
     */
    final boolean needsDurabilityBarrier;
    /** The journal is range-partitioned by month, and its horizon is extended ahead of time. */
    final boolean partitionsJournal;
    /**
     * A schema step runs in one transaction. CockroachDB and YugabyteDB commit each DDL statement
     * by itself, so a transaction would undo nothing of a failed step; on YugabyteDB it would also
     * build every index without online backfill, which is unsafe while the table takes writes.
     * Without it, Flyway's lock must not hold a transaction open either: an online index build
     * waits for every open transaction to end.
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

    /** CockroachDB answers "CockroachDB CCL v…", YugabyteDB "PostgreSQL 15.12-YB-2025.2…". */
    static Backend of(String version) {
        if (version.startsWith("CockroachDB")) {
            return COCKROACHDB;
        }
        return version.contains("-YB-") ? YUGABYTEDB : POSTGRESQL;
    }
}

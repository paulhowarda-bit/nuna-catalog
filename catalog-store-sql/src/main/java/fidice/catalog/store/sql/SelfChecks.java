package fidice.catalog.store.sql;

import fidice.catalog.store.SafetyFinding;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The self-check of each backend (docs/storage-design.md section 7). */
final class SelfChecks {

    /** Voting replicas CockroachDB needs so a commit survives the loss of one node. */
    static final int VOTERS = 3;

    private static final Pattern NUM_REPLICAS = Pattern.compile("num_replicas = (\\d+)");
    private static final Pattern NUM_VOTERS = Pattern.compile("num_voters = (\\d+)");

    private SelfChecks() {
    }

    static List<SafetyFinding> postgres(Connection connection) throws SQLException {
        String sql = "SELECT current_setting('synchronous_commit'), current_setting('synchronous_standby_names'),"
                + " (SELECT count(*) FROM pg_stat_replication WHERE sync_state IN ('sync', 'quorum')),"
                + " current_setting('fsync'), current_setting('full_page_writes'),"
                + " current_setting('statement_timeout'), current_setting('idle_in_transaction_session_timeout')";
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            String synchronousCommit = row.getString(1);
            String standbyNames = row.getString(2);
            long syncStandbys = row.getLong(3);
            String fsync = row.getString(4);
            String fullPageWrites = row.getString(5);
            String statementTimeout = row.getString(6);
            String idleTimeout = row.getString(7);
            List<SafetyFinding> findings = new ArrayList<>();
            if (!synchronousCommit.equals("on") && !synchronousCommit.equals("remote_apply")) {
                findings.add(new SafetyFinding("pg.sync-commit-off",
                        "synchronous_commit is " + synchronousCommit + "; it must be on or remote_apply"));
            }
            if (standbyNames.isBlank() || syncStandbys == 0) {
                findings.add(new SafetyFinding("pg.no-sync-standby",
                        "synchronous_standby_names is '" + standbyNames + "' and " + syncStandbys
                                + " standbys stream in sync or quorum state (the catalog role needs pg_monitor to see them)"));
            }
            if (fsync.equals("off") || fullPageWrites.equals("off")) {
                findings.add(new SafetyFinding("pg.fsync-off",
                        "fsync is " + fsync + " and full_page_writes is " + fullPageWrites + "; both must be on"));
            }
            if (!statementTimeout.equals("0") || !idleTimeout.equals("0")) {
                findings.add(new SafetyFinding("pg.commit-cancel-timeout",
                        "statement_timeout is " + statementTimeout + " and idle_in_transaction_session_timeout is "
                                + idleTimeout + "; a timeout can cancel a COMMIT's wait for the standby, so both must be 0"));
            }
            return findings;
        }
    }

    /**
     * CockroachDB (section 5.4): every zone that holds the catalog's data keeps three voters, the
     * Raft log is synced, and READ COMMITTED is not upgraded to SERIALIZABLE.
     */
    static List<SafetyFinding> cockroach(Connection connection) throws SQLException {
        List<SafetyFinding> findings = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        targets.add("DATABASE " + quote(Jdbc.first(connection, "SELECT current_database()", row -> row.getString(1))
                .orElseThrow()));
        for (String table : Jdbc.query(connection, "SELECT tablename FROM pg_tables WHERE schemaname = current_schema()"
                + " ORDER BY tablename", row -> row.getString(1))) {
            targets.add("TABLE " + quote(table));
        }
        List<String> weak = new ArrayList<>();
        for (String target : targets) {
            String zone = Jdbc.first(connection, "SELECT raw_config_sql FROM [SHOW ZONE CONFIGURATION FROM " + target + "]",
                    row -> row.getString(1)).orElse("");
            int voters = number(NUM_VOTERS, zone).or(() -> number(NUM_REPLICAS, zone)).orElse(0);
            if (voters < VOTERS) {
                weak.add(target + " has " + voters);
            }
        }
        if (!weak.isEmpty()) {
            findings.add(new SafetyFinding("crdb.replicas", "every zone holding catalog data needs " + VOTERS
                    + " voting replicas; " + String.join(", ", weak)));
        }
        try {
            String unsynced = Jdbc.first(connection, "SHOW CLUSTER SETTING kv.raft_log.synchronization.unsafe.disabled",
                    row -> row.getString(1)).orElseThrow();
            if (!unsynced.equals("false") && !unsynced.equals("f")) {
                findings.add(new SafetyFinding("crdb.fsync-off",
                        "kv.raft_log.synchronization.unsafe.disabled is " + unsynced + "; it must be false"));
            }
        } catch (SQLException e) {
            findings.add(new SafetyFinding("crdb.fsync-off", "cannot read kv.raft_log.synchronization.unsafe.disabled"
                    + " (the catalog role needs VIEWCLUSTERSETTING): " + e.getMessage()));
        }
        String isolation = effectiveIsolation(connection, "SHOW transaction_isolation");
        if (!isolation.equals("read committed")) {
            findings.add(new SafetyFinding("crdb.read-committed-off", "a READ COMMITTED transaction runs as " + isolation
                    + "; set sql.txn.read_committed_isolation.enabled to true, the isolation the store's locking is"
                    + " verified under"));
        }
        return findings;
    }

    /** What {@code show} reports inside a transaction begun as READ COMMITTED. */
    private static String effectiveIsolation(Connection connection, String show) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try {
            return Jdbc.first(connection, show, row -> row.getString(1)).orElseThrow().toLowerCase();
        } finally {
            connection.rollback();
            connection.setAutoCommit(autoCommit);
        }
    }

    private static Optional<Integer> number(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}

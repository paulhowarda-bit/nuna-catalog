package nuna.catalog.store.oracle;

import nuna.catalog.store.SafetyFinding;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Whether migration V5's Scheduler job can keep the journal's monthly partitions ahead of the commits
 * (docs/storage-design.md, M2b deviation 9). When it cannot, each month's first commit creates its own partition,
 * which took 0.5 to 2.6 s on Free and can pass the pool's read timeout, failing that commit. The self-check then
 * reports {@link #FINDING}, which refuses the server's start like every other finding (D8, amended 2026-11-20:
 * "Refuse, like others"; backlog 72).
 */
final class JournalJob {

    static final String NAME = "JOURNAL_PARTITIONS_AHEAD";
    static final String FINDING = "ora.journal-job-down";
    /** The schema version whose migration creates the job. Below it, migrate() is about to create it. */
    static final int CREATED_IN = 5;
    /**
     * Failed runs in a row, newest first with no success among them, that make a job that keeps failing. One is not:
     * the last success created this month's partition and the next two, so nothing runs short for a month or more,
     * and a run that failed for a passing reason succeeds the next day. Two in a row, two days on the daily schedule,
     * is a cause that stays. The run log decides, not FAILURE_COUNT: Oracle's reference gives that as the times the
     * job has failed, not the times since its last success, so one failure long ago would refuse every start.
     */
    static final int FAILED_RUNS = 2;
    /** States in which the job runs no more: BROKEN, after failing too often in a row, and the states of an ended job. */
    private static final Set<String> STOPPED = Set.of("BROKEN", "COMPLETED", "FAILED", "SUCCEEDED");

    /** The job's row in USER_SCHEDULER_JOBS. */
    record Job(boolean enabled, String state) {
    }

    /** A logged run of the job: whether it succeeded, and its ORA error number if not (0 when none is logged). */
    record Run(boolean succeeded, int error) {
    }

    private JournalJob() {
    }

    /**
     * The finding, if the job cannot run: read from the schema's own Scheduler views, which need no grant.
     * {@code jobQueueProcesses} is the value of the parameter JOB_QUEUE_PROCESSES; {@code schemaVersion} the newest
     * migration applied.
     */
    static Optional<SafetyFinding> check(Connection connection, String jobQueueProcesses, int schemaVersion)
            throws SQLException {
        Optional<Job> job = Jdbc.first(connection, "SELECT enabled, state FROM user_scheduler_jobs WHERE job_name = ?",
                row -> new Job("TRUE".equals(row.getString(1)), row.getString(2)), NAME);
        List<Run> newestFirst = Jdbc.query(connection, "SELECT status, error# FROM user_scheduler_job_run_details"
                        + " WHERE job_name = ? ORDER BY log_date DESC, log_id DESC FETCH FIRST " + FAILED_RUNS + " ROWS ONLY",
                row -> new Run("SUCCEEDED".equals(row.getString(1)), row.getInt(2)), NAME);
        return judge(jobQueueProcesses, schemaVersion >= CREATED_IN, job.orElse(null), newestFirst);
    }

    /**
     * One finding naming each cause and what to do about it, or none. {@code job} is null when the schema has no such
     * job, which is a cause only once {@code created} (migration V5 has run); {@code newestFirst} is the newest logged
     * runs, at least {@link #FAILED_RUNS} of them when there are.
     */
    static Optional<SafetyFinding> judge(String jobQueueProcesses, boolean created, Job job, List<Run> newestFirst) {
        List<String> causes = new ArrayList<>();
        if ("0".equals(jobQueueProcesses)) {
            causes.add("JOB_QUEUE_PROCESSES is 0, so no Scheduler job runs: set it above 0");
        }
        if (job == null) {
            if (created) {
                causes.add("the Scheduler job " + NAME + " is missing: create it again as migration V5"
                        + " (V5__journal_partitions_ahead.sql) does");
            }
        } else if ("BROKEN".equals(job.state())) {
            causes.add(NAME + " is BROKEN, disabled by Oracle after failing too often: fix why its runs fail"
                    + " (USER_SCHEDULER_JOB_RUN_DETAILS), then DBMS_SCHEDULER.ENABLE('" + NAME + "')");
        } else if (STOPPED.contains(job.state())) {
            causes.add(NAME + " is " + job.state() + " and will not run again: drop it and create it again as"
                    + " migration V5 (V5__journal_partitions_ahead.sql) does");
        } else if (!job.enabled()) {
            causes.add(NAME + " is disabled: DBMS_SCHEDULER.ENABLE('" + NAME + "')");
        }
        List<Run> failed = newestFirst.stream().takeWhile(run -> !run.succeeded()).toList();
        if (failed.size() >= FAILED_RUNS) {
            int error = failed.getFirst().error();
            causes.add(NAME + "'s last " + FAILED_RUNS + " runs failed"
                    + (error == 0 ? "" : String.format(Locale.ROOT, ", the newest with ORA-%05d", error))
                    + ": fix the cause (USER_SCHEDULER_JOB_RUN_DETAILS), then DBMS_SCHEDULER.RUN_JOB('" + NAME
                    + "', use_current_session => FALSE); a run that succeeds clears this");
        }
        if (causes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SafetyFinding(FINDING, String.join("; ", causes) + ". Until the job runs, each month's"
                + " first commit creates its own journal partition, 0.5 to 2.6 s on Free, which can pass the pool's read"
                + " timeout and fail that commit"));
    }
}

package nuna.catalog.store.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.SafetyFinding;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * How the self-check judges the journal-partition job from what it reads (backlog 72): JOB_QUEUE_PROCESSES, the job's
 * row and its newest logged runs. The reads themselves are OracleStoreSuiteTest's (heavy); none of this needs a
 * database.
 */
class JournalJobTest {

    private static final String QUEUE = "80";
    private static final JournalJob.Job AS_V5_MAKES_IT = new JournalJob.Job(true, "SCHEDULED");
    private static final JournalJob.Run SUCCESS = new JournalJob.Run(true, 0);
    private static final JournalJob.Run FAILURE = new JournalJob.Run(false, 20072);

    @Test
    @DisplayName("the job as V5 makes it, before its first run, running, and after a success, is no finding")
    void theJobAsV5MakesItIsNoFinding() {
        assertThat(JournalJob.judge(QUEUE, true, AS_V5_MAKES_IT, List.of())).isEmpty();
        assertThat(JournalJob.judge(QUEUE, true, new JournalJob.Job(true, "RUNNING"), List.of())).isEmpty();
        assertThat(JournalJob.judge(QUEUE, true, AS_V5_MAKES_IT, List.of(SUCCESS, SUCCESS))).isEmpty();
    }

    @Test
    @DisplayName("JOB_QUEUE_PROCESSES 0 is a finding, whatever the job's state")
    void noJobQueueProcessesIsAFinding() {
        assertThat(detail(JournalJob.judge("0", true, AS_V5_MAKES_IT, List.of(SUCCESS))))
                .contains("JOB_QUEUE_PROCESSES is 0", "set it above 0");
        assertThat(detail(JournalJob.judge("0", false, null, List.of()))).as("before V5 too: the job it makes cannot run")
                .contains("JOB_QUEUE_PROCESSES is 0");
    }

    @Test
    @DisplayName("a missing job is a finding once V5 has run, and none before: migrate() is about to create it")
    void aMissingJobIsAFindingOnlyOnceV5HasRun() {
        assertThat(detail(JournalJob.judge(QUEUE, true, null, List.of())))
                .contains(JournalJob.NAME + " is missing", "V5__journal_partitions_ahead.sql");
        assertThat(JournalJob.judge(QUEUE, false, null, List.of())).isEmpty();
    }

    @Test
    @DisplayName("a disabled job is a finding that says how to enable it")
    void aDisabledJobIsAFinding() {
        assertThat(detail(JournalJob.judge(QUEUE, true, new JournalJob.Job(false, "DISABLED"), List.of(SUCCESS))))
                .contains(JournalJob.NAME + " is disabled", "DBMS_SCHEDULER.ENABLE('" + JournalJob.NAME + "')");
    }

    @Test
    @DisplayName("a broken job is a finding that says to fix its runs, then enable it")
    void aBrokenJobIsAFinding() {
        assertThat(detail(JournalJob.judge(QUEUE, true, new JournalJob.Job(false, "BROKEN"), List.of())))
                .contains(JournalJob.NAME + " is BROKEN", "USER_SCHEDULER_JOB_RUN_DETAILS", "DBMS_SCHEDULER.ENABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED", "SUCCEEDED"})
    @DisplayName("an ended job runs no more, enabled or not: a finding that says to create it again")
    void anEndedJobIsAFinding(String state) {
        assertThat(detail(JournalJob.judge(QUEUE, true, new JournalJob.Job(true, state), List.of())))
                .contains(JournalJob.NAME + " is " + state + " and will not run again", "V5__journal_partitions_ahead.sql");
    }

    @Test
    @DisplayName("one failed run after a success is no finding: the last success made this month's partition and the"
            + " next two")
    void oneFailedRunIsNoFinding() {
        assertThat(JournalJob.judge(QUEUE, true, AS_V5_MAKES_IT, List.of(FAILURE, SUCCESS))).isEmpty();
        assertThat(JournalJob.judge(QUEUE, true, AS_V5_MAKES_IT, List.of(FAILURE))).as("the first run failed").isEmpty();
    }

    @Test
    @DisplayName("two failed runs in a row are a finding naming the newest run's error")
    void twoFailedRunsInARowAreAFinding() {
        assertThat(detail(JournalJob.judge(QUEUE, true, AS_V5_MAKES_IT, List.of(FAILURE, new JournalJob.Run(false, 1031)))))
                .contains(JournalJob.NAME + "'s last 2 runs failed, the newest with ORA-20072",
                        "DBMS_SCHEDULER.RUN_JOB('" + JournalJob.NAME + "', use_current_session => FALSE)");
        assertThat(detail(JournalJob.judge(QUEUE, true, AS_V5_MAKES_IT, List.of(new JournalJob.Run(false, 0), FAILURE))))
                .as("a run logged without an error number").contains("last 2 runs failed:").doesNotContain("ORA-");
    }

    @Test
    @DisplayName("failures followed by a success are no finding: a success clears them")
    void aSuccessAfterFailuresIsNoFinding() {
        assertThat(JournalJob.judge(QUEUE, true, AS_V5_MAKES_IT, List.of(SUCCESS, FAILURE))).isEmpty();
    }

    @Test
    @DisplayName("several causes make one finding that names each, and says what a job that cannot run costs")
    void severalCausesMakeOneFinding() {
        Optional<SafetyFinding> finding = JournalJob.judge("0", true, new JournalJob.Job(false, "DISABLED"),
                List.of(FAILURE, FAILURE));
        assertThat(finding).map(SafetyFinding::code).contains(JournalJob.FINDING);
        assertThat(detail(finding)).contains("JOB_QUEUE_PROCESSES is 0", " is disabled", "last 2 runs failed",
                "each month's first commit creates its own journal partition");
    }

    private static String detail(Optional<SafetyFinding> finding) {
        assertThat(finding).map(SafetyFinding::code).contains(JournalJob.FINDING);
        return finding.orElseThrow().detail();
    }
}

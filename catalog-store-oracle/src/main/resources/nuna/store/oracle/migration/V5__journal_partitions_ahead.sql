-- M2b: a daily scheduler job creates the journal's partitions for this month and the next two,
-- before any commit lands in them. Otherwise interval partitioning creates a month's partition
-- inside its first commit, which took 0.5 to 2.6 s on Free instead of about 20 ms, and can run past
-- the pool's read timeout (docs/storage-design.md, M2b deviations).
--
-- Locking an interval partition that has no rows yet materializes it (VLDB and Partitioning Guide,
-- "Adding a Partition to an Interval-Partitioned Table", whose example locks IN SHARE MODE). This
-- job locks IN ROW SHARE MODE, which also materializes it (OracleStoreSuiteTest) and conflicts only
-- with an exclusive table lock, so the job never waits for a commit or blocks one. Data Guard
-- carries the job to a physical standby like any other change, and after a failover the new
-- primary runs it. The catalog role needs CREATE JOB, and JOB_QUEUE_PROCESSES must not be 0, or no
-- Scheduler job runs (Reference, JOB_QUEUE_PROCESSES).
-- The procedure and the job need different names: they share the schema's namespace (ORA-27477).
CREATE OR REPLACE PROCEDURE create_journal_partitions AS
    this_month DATE := TRUNC(CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE), 'MM');
BEGIN
    FOR ahead IN 0 .. 2 LOOP
        EXECUTE IMMEDIATE 'LOCK TABLE journal PARTITION FOR (TIMESTAMP '''
            || TO_CHAR(ADD_MONTHS(this_month, ahead), 'YYYY-MM-DD HH24:MI:SS') || ''') IN ROW SHARE MODE';
    END LOOP;
    COMMIT;
END;
/

BEGIN
    DBMS_SCHEDULER.CREATE_JOB(
        job_name => 'JOURNAL_PARTITIONS_AHEAD',
        job_type => 'STORED_PROCEDURE',
        job_action => 'CREATE_JOURNAL_PARTITIONS',
        repeat_interval => 'FREQ=DAILY',
        enabled => TRUE,
        comments => 'Creates the journal partitions of this month and the next two (docs/storage-design.md)');
END;
/

-- M2: the journal is partitioned by commit month (D5), so retention drops whole partitions.
-- Oracle cannot partition on a TIMESTAMP WITH TIME ZONE column (ORA-03001), so the key is a
-- virtual column holding the commit time in UTC. Interval partitioning adds a partition for each
-- month a commit lands in; commits before 2000 share the first partition. The conversion runs
-- ONLINE, so the previous release keeps writing the journal while it applies; the primary key and
-- journal_by_pointer stay global indexes.
--
-- Partitioning is a separately licensed option of Enterprise Edition (Free includes it). Where it
-- is not enabled this step stops at its first statement, before changing anything.
DECLARE
    enabled VARCHAR2(64);
BEGIN
    SELECT value INTO enabled FROM v$option WHERE parameter = 'Partitioning';
    IF enabled <> 'TRUE' THEN
        RAISE_APPLICATION_ERROR(-20001, 'the journal needs the Partitioning option (docs/storage-design.md)');
    END IF;
END;
/

ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;

ALTER TABLE journal ADD (committed_utc TIMESTAMP GENERATED ALWAYS AS (SYS_EXTRACT_UTC(committed_at)) VIRTUAL);

ALTER TABLE journal MODIFY PARTITION BY RANGE (committed_utc) INTERVAL (NUMTOYMINTERVAL(1, 'MONTH'))
    (PARTITION journal_before_2000 VALUES LESS THAN (TIMESTAMP '2000-01-01 00:00:00')) ONLINE;

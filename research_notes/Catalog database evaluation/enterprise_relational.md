# Primary-replica and enterprise relational databases for the catalog (D7)

Research date 2026-11-06. Scored against `00_requirements.md` as revised the same day:
- One catalog in ONE environment, on-prem or AWS. The same engine must be self-hostable in both and never spans them. A managed AWS offering is acceptable only if the same engine runs self-hosted on-prem; behavioural differences are noted.
- Zero loss of acknowledged commits inside one region or metro (3 AZs or 3 local sites, synchronous quorum, ~1-2 ms per commit).
- Asynchronous replication to a DR region is acceptable. Cross-region synchronous writes are not required.

The revised durability rule suits this family: every candidate here is a single-primary database with synchronous in-region standbys, so it is scored on exactly that topology.

## Bottom line

- **Finalists: PostgreSQL and Oracle Database.**
  - **PostgreSQL**: self-managed with Patroni (or CloudNativePG on Kubernetes) on-prem; in AWS the same stack on EC2/EKS, or Aurora PostgreSQL with listed caveats.
    - It has the most catalog precedent: Polaris, Lakekeeper, Nessie, Gravitino and Unity OSS all run on it.
    - The commit CAS semantics are documented.
    - pgJDBC was made Loom-friendly.
    - Debezium follows a failover through PG17 failover slots.
    - It costs nothing to license.
    - Its weakness: in-region HA lives outside the engine (Patroni or CNPG plus a 3-site etcd), and one subtle hazard needs a rule: a COMMIT whose synchronous-replication wait is cancelled becomes visible before it is replicated.
  - **Oracle** (Enterprise Edition + Active Data Guard, optionally RAC; Exadata via Oracle Database@AWS in AWS). It is the only candidate whose engine directly answers two requirements every other candidate must hand-build:
    - Requirement 5, ambiguous commits: Transaction Guard's `GET_LTXID_OUTCOME` returns committed or not, and fences the in-flight transaction.
    - Requirement 3, read-after-commit reads off the standby: `STANDBY_MAX_DATA_DELAY=0` on a SYNC standby.
    - Data Guard Fast-Start Failover with lag limit 0 and a third-site observer is the turnkey form of the decided topology.
    - The firm is publicly named as adopting Oracle Database@AWS.
    - The costs are licence price, no Iceberg catalog precedent, and JDK 25 not yet in Oracle's JDBC compatibility table.
- **Runner-up: SQL Server Always On.** Its in-region durability is as good: sync-commit AG plus `REQUIRED_SYNCHRONIZED_SECONDARIES_TO_COMMIT`. It loses on the other two requirements:
  - Readable secondaries give no read-your-writes guarantee.
  - There is no commit-outcome API.
  - CDC must be re-armed on every failover.
  - Its Kafka-native streaming (CES) is preview and targets Event Hubs only.
- **Not recommended:**
  - **EDB Postgres Distributed.** Its default (async plus last-update-wins) silently breaks a CAS. The safe mode, Quorum Commit, is six months old and needs EDB's proprietary Postgres builds.
  - **Db2 LUW HADR.** Standby reads are Uncommitted Read only, there is only one synchronous standby, CDC needs an IIDR licence, and there is no Iceberg precedent.
  - **MySQL InnoDB Cluster.** Workable, but it adds nothing over PostgreSQL here.
- **Out under the deployment rule: Db2 for z/OS with GDPS.** It is the firm's gold standard for RPO 0 inside Metro Mirror distance, but it cannot run in AWS.
- **The design matters more than the engine for three requirements.** The same three patterns apply to every candidate (section 1):
  1. A conditional `UPDATE … WHERE metadata_location = ?` with a row-count check at READ COMMITTED, under one pointer-row lock.
  2. An ambiguity-resolution protocol keyed on a `commit_id` in the journal. Never let the connection pool replay a commit: Iceberg's own JdbcCatalog is losing committed snapshots that way, in issue #18743, open since 2026-11-01.
  3. "Pointer on the primary, content by version from anywhere", which turns 20k loads/s into 20k tiny primary-key reads plus an immutable cache.

Tags:
- **[read: URL]**: the source was fetched this session with curl (by me or by a helper agent) into `scratchpad/downloads/db_relational/<engine>/`. I re-checked every load-bearing quote by grepping the saved file.
- **[read (WebFetch summary): URL]**: IBM Docs and dev.mysql.com refuse curl (HTTP 403), so only a paraphrase was available.
- **[secondary: URL]**: a third-party report.
- **[inference]**: my reasoning, not a claim made by the source.

Quotes are kept short; most findings are paraphrased.

---

## 1. What any relational store must do for this workload

### 1.1 The commit transaction and the right isolation level

Portable shape. PostgreSQL dialect; Oracle and Db2 use `MERGE`, MySQL uses `ON DUPLICATE KEY` [inference]:

```sql
-- metadata.json already written to object storage; no object-store I/O inside the transaction
BEGIN;                                   -- READ COMMITTED
INSERT INTO idempotency_key (key, request_hash, expires_at) VALUES (?, ?, ?);
                                         -- unique-key conflict => replay path (concurrent duplicate waits on the index)
UPDATE table_pointer
   SET metadata_location = :new, metadata_sha256 = :sha, version = version + 1
 WHERE table_id = :id AND metadata_location = :expected;     -- rowcount must be 1, else ROLLBACK -> re-read -> 409/retry
INSERT INTO table_projection (...) VALUES (...)
  ON CONFLICT (table_id) DO UPDATE SET ...;                  -- safe: written under the pointer-row lock
INSERT INTO commit_journal (table_id, version, commit_id, principal, requirements, updates) VALUES (...);
INSERT INTO audit_outbox (...) VALUES (...);
UPDATE idempotency_key SET response = ? WHERE key = ?;
COMMIT;
```

**Why READ COMMITTED and a conditional UPDATE, not SERIALIZABLE** [inference, grounded in the cited behaviour]:
- The UPDATE takes an exclusive row lock on the table's pointer row before anything else is written. Every other row of that commit (projection, journal version N+1) is therefore written while holding the one lock that all writers of that table must take. The pointer, journal and outbox cannot disagree, and no wider isolation is needed.
- The guarantee comes from how each engine evaluates the WHERE clause of an UPDATE that waited on a concurrent writer:
  - **PostgreSQL** documents that the WHERE clause is re-evaluated against the updated row version (established in `persistence_scale_ha.md`).
  - **SQL Server under RCSI** documents that UPDATE reverts from row versions to the actual data and takes update locks to select rows. With optimized locking it "reevaluates (requalifies) the predicate" before modifying [read: https://learn.microsoft.com/en-us/sql/relational-databases/sql-server-transaction-locking-and-row-versioning-guide; https://learn.microsoft.com/en-us/sql/relational-databases/performance/optimized-locking].
  - **Oracle**'s Concepts guide says a writer blocked by a committed transaction proceeds against the newly changed row. Its documented optimistic-locking pattern is exactly this compare-in-WHERE UPDATE. The statement-restart mechanism is not described in Concepts (19c or 26ai), so the benchmark must prove it (section 12) [read: https://docs.oracle.com/en/database/oracle/oracle-database/19/cncpt/data-concurrency-and-consistency.html].
- Every precedent implements the CAS this way, and none sets an isolation level:
  - Iceberg's JdbcCatalog: `UPDATE iceberg_tables SET metadata_location = ? … WHERE … AND metadata_location = ?` and `if (updatedRecords == 1)`.
  - Gravitino: `AND current_version = #{…}` with `if (updated == 0) throw`.
  - OpenHouse: JPA `@Version`.
  - [read: https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/jdbc/JdbcUtil.java; https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/jdbc/JdbcTableOperations.java; https://github.com/apache/gravitino/blob/main/core/src/main/java/org/apache/gravitino/storage/relational/service/OccWriteSupport.java; https://github.com/linkedin/openhouse/blob/main/ARCHITECTURE.md]
- **Do not lean on REPEATABLE READ.** Iceberg's lock-free Hive Metastore commit (HIVE-26882) relied on REPEATABLE READ and failed differently on each engine:
  - On MariaDB and MySQL 5.6 it lost updates ("72 processes succeeded but only 37 properties were added").
  - On Oracle it failed because Oracle has no REPEATABLE READ.
  - Only PostgreSQL and SQL Server 2017 behaved.
  - The fix, HIVE-28121, switched to direct SQL with an affected-row check [read: https://issues.apache.org/jira/browse/HIVE-26882 (JIRA REST comments); https://issues.apache.org/jira/browse/HIVE-28121].
  - Jepsen likewise found that MySQL Repeatable Read "cannot safely read a value and then write it" [read: https://jepsen.io/analyses/mysql-8.0.34].
- **Multi-table commit (`/transactions/commit`, N up to about 100)**: lock the pointer rows in `table_id` order inside one transaction, so two multi-table commits cannot deadlock [inference]. Every engine detects deadlocks and kills a victim:
  - PostgreSQL: `deadlock_timeout` 1 s [read: https://www.postgresql.org/docs/current/runtime-config-locks.html].
  - SQL Server: error 1205 [read: https://learn.microsoft.com/en-us/sql/relational-databases/sql-server-deadlocks-guide].
  - Map a deadlock victim to a retryable 409.

**Hot-row contention** [inference]:
- The lock is held from the UPDATE to the end of COMMIT. That is a few inserts plus one in-region synchronous replication round trip, about 2-5 ms.
- So one table can serialise a few hundred commits/s, two orders of magnitude above the 1-2 commits/s that client-side metadata I/O allows (`persistence_scale_ha.md` §1).
- Waiters queue on the row lock. PostgreSQL waits indefinitely unless `lock_timeout` is set; its default 0 disables the timeout [read: https://www.postgresql.org/docs/current/explicit-locking.html; https://www.postgresql.org/docs/current/runtime-config-client.html]. Db2's `LOCKTIMEOUT` also defaults to −1, meaning wait forever [read (WebFetch summary): https://www.ibm.com/docs/en/SSEPGG_12.1.0/com.ibm.db2.luw.admin.config.doc/doc/r0000329.html].
- Set a per-statement lock timeout of a few seconds on the UPDATE, and return 503/409 rather than piling up virtual threads.

### 1.2 Ambiguous outcomes: a protocol every engine needs

What the drivers actually do when the server or connection dies mid-COMMIT:

| Engine / driver | What the client sees | Engine-provided resolution |
|---|---|---|
| PostgreSQL / pgJDBC | connection error; pgJDBC docs say nothing about COMMIT in flight (gap) | `pg_xact_status(xid8)`; docs name this exact use: deciding "whether their transaction committed or aborted" after a disconnect mid-COMMIT [read: https://www.postgresql.org/docs/current/functions-info.html] |
| Aurora / AWS Advanced JDBC Wrapper 4.6.0 | `TransactionStateUnknownSQLException`, SQLState 08007; the wrapper tries a rollback that "might be unsuccessful" [read: https://github.com/aws/aws-advanced-jdbc-wrapper/blob/main/docs/using-the-jdbc-driver/using-plugins/UsingTheFailoverPlugin.md] | none beyond PostgreSQL's |
| Oracle / ojdbc | ORA-17008-class errors | **Transaction Guard**: LTXID from `getLogicalTransactionId()`, then `DBMS_APP_CONT.GET_LTXID_OUTCOME` from a new session. When it returns not committed, it blocks the LTXID so the in-flight transaction can never commit later. Works across Data Guard failover [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/arpls/DBMS_APP_CONT.html; https://docs.oracle.com/en/database/oracle/oracle-database/26/adfns/transaction-guard.html] |
| SQL Server / mssql-jdbc | connection error; connection resiliency does not restore a connection with an open transaction [read: https://learn.microsoft.com/en-us/sql/connect/jdbc/connection-resiliency] | none found |
| Db2 / JCC | SQL30108N (-4498/-4499 in Java): reconnected, transaction rolled back. The HADR team notes the last transaction may be committed on the new server though never reported as committed [read: https://ibm.github.io/db2-hadr-wiki/clientReroute.html] | none found |
| EDB PGD | timeout under a commit scope: "the outcome is unknown" [read: https://github.com/EnterpriseDB/docs/blob/develop/product_docs/docs/pgd/6.5/geo-replication-dev/using-commit-scopes.mdx] | `bdr.logical_transaction_status()` for CAMO pairs only |

**Engine-neutral protocol** [inference]:
1. The server generates `commit_id` before BEGIN and writes it to `commit_journal` inside the transaction.
2. On any error after COMMIT was sent, the server opens a new connection to the current primary.
3. It takes `SELECT … FOR UPDATE` on the pointer row with a lock timeout. Any still-running instance of the old transaction must finish before this returns.
4. It looks up `commit_id`. Found means committed (200); absent means not committed, so retry or 409.
5. On PostgreSQL, add a **durability barrier** before answering "committed": a small synchronous-commit write, such as a heartbeat row, whose acknowledgement implies the earlier WAL is on a synchronous standby. This covers the cancelled-sync-wait case in section 2.
6. If resolution itself fails, return 5xx (CommitStateUnknown).

Oracle's Transaction Guard does steps 3-4 in the engine.

**Do not let the pool replay a commit.** Apache Iceberg's JdbcCatalog:
- Treats SQLSTATEs 08000/08003/08006/08007/40001 as retryable, with retry-by-default.
- Re-runs the CAS UPDATE on a fresh connection after a connection loss.
- The replay matches 0 rows and is reported as `CommitFailedException`. That is a cleanable failure, so the client deletes the manifests of a commit that actually succeeded.
- Issue #18743 (open, filed 2026-11-01, verified by the reporter against PostgreSQL 17.11).
- The JDBC operations never call `checkCommitStatus`, unlike HiveTableOperations.
- [read: https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/jdbc/JdbcClientPool.java; https://github.com/apache/iceberg/issues/18743; https://github.com/apache/iceberg/blob/main/hive-metastore/src/main/java/org/apache/iceberg/hive/HiveTableOperations.java]
- Gravitino's Iceberg REST server uses this same JdbcCatalog for its JDBC backend [read: https://github.com/apache/gravitino/blob/main/docs/iceberg-rest-service.md; inference that it inherits the bug].

### 1.3 Read-after-commit at 5k-20k loads/s on a single primary

Engine features differ widely (scored per candidate in dimension 3), but one design makes them matter less [inference]:
- **Pointer on the primary, content by version from anywhere.** A load first reads `(table_id, version, etag)` from the primary, a ~200-byte primary-key lookup.
- If the client's `If-None-Match` matches, answer 304.
- Otherwise fetch the projection for that exact version. A version-keyed projection is immutable, so it can come from a node-local cache, a replica (retry or fall back to the primary if the replica's row is older), or object storage.
- Read-after-commit holds because the version decision always comes from the primary, after the commit was acknowledged.
- 20k tiny PK reads/s is ordinary for a single PostgreSQL, Oracle or SQL Server primary on server hardware. This is not measured here; the local benchmark's B4 measures the ratio.
- This is the same "read index" idea Raft systems use. It removes the need for consistent replica reads, though Oracle's `STANDBY_MAX_DATA_DELAY=0` or PG19's `WAIT FOR LSN` can still offload the projection read.

### 1.4 Outbox to Kafka

- Log-based CDC (Debezium or vendor tools) gives commit-ordered, at-least-once delivery on all engines. Consumers dedupe by outbox id (the Debezium outbox router recommends this) [read: https://github.com/debezium/debezium/blob/main/documentation/modules/ROOT/pages/transformations/outbox-event-router.adoc].
- Polling the outbox table needs care: ids allocated at insert can commit out of order. A poller must read only below the oldest in-flight transaction (PostgreSQL snapshot xmin, Oracle SCN), or it will skip rows [inference].
- Oracle can make the outbox *be* a transactional queue (TxEventQ), described in section 5.

### 1.5 TTL, case-insensitive names, prefix paging

- **TTL.** No candidate has native row TTL [read: absence in each engine's docs; the PostgreSQL absence is noted in section 2].
  - Use an indexed `expires_at`, filter it on read, and purge with a batched DELETE from a scheduler: pg_cron, DBMS_SCHEDULER, SQL Agent, or the application with a lease.
  - Or partition the idempotency table by day and drop old partitions.
  - In PostgreSQL, DROP or DETACH PARTITION avoids the VACUUM cost of bulk DELETE. `DETACH … CONCURRENTLY` needs only a SHARE UPDATE EXCLUSIVE lock on the parent [read: https://www.postgresql.org/docs/current/ddl-partitioning.html].
  - Oracle partitioning is a paid option ($11,500 per processor list; section 5).
- **Case-insensitive uniqueness plus prefix paging.** The portable answer is a normalized `name_key` column (casefolded), in a binary/C collation, carrying the unique index. Page with keyset `WHERE ns_id = ? AND name_key >= :prefix AND name_key < :prefix_hi AND name_key > :after ORDER BY name_key` [inference]. Engine-native case-insensitive collations exist (see each section) but often defeat index prefix scans.
- **Sizing note.** 500 commits/s × ~10 KB of journal is about 430 GB/day at sustained peak; 50/s average is about 43 GB/day, roughly 15 TB/year retained "for years" [inference, from the requirements' envelope]. That exceeds PostgreSQL's 32 TB per-relation limit within about two years [read: https://www.postgresql.org/docs/current/limits.html]. So the journal must be time-partitioned on every engine, and older partitions are candidates for export to an Iceberg table.

---

## 2. PostgreSQL, self-managed (Patroni or CloudNativePG)

Versions as of 2026-11-06:
- PostgreSQL 18.6 is current; 18 is supported until 2030-11-14. PostgreSQL 19 RC1 was announced 2026-10-15 [read: https://www.postgresql.org/support/versioning/; https://www.postgresql.org/docs/19/release-19.html].
- Patroni 4.1.5 (2026-08-12); CloudNativePG 1.30.1 (2026-09-23); pgJDBC 42.7.14 (2026-10-07); Debezium 3.7.1.Final (2026-11-06) [read: GitHub releases/tags APIs and Maven metadata for each].

**Transactions and contention**
- Section 1.1 applies directly.
- A plain UPDATE takes a FOR NO KEY UPDATE row lock. Row locks never block readers. `SELECT … FOR UPDATE` waits, then returns the updated row; under RR/SERIALIZABLE it errors instead [read: https://www.postgresql.org/docs/current/explicit-locking.html].
- `NOWAIT` errors immediately. `SKIP LOCKED` is for queue consumers [read: https://www.postgresql.org/docs/current/sql-select.html].
- `transaction_timeout` (new in 17) bounds the whole transaction [read: https://www.postgresql.org/docs/current/runtime-config-client.html; https://www.postgresql.org/docs/17/release-17.html].
- Any update locks the whole row, so the docs advise keeping JSON documents to a manageable size to reduce lock contention. Keep the projection in its own table, not in the pointer row [read: https://www.postgresql.org/docs/current/datatype-json.html].
- Jepsen (2020, PostgreSQL 12.3):
  - Found SERIALIZABLE violating serializability (G2-item); this was fixed in the August 2020 minors.
  - Found Repeatable Read to be snapshot isolation.
  - Found read committed "appeared correct".
  - [read: https://jepsen.io/analyses/postgresql-12.3]

**In-region synchronous topology** (3 sites: primary + 2 standbys; or 3 AZs)
- `synchronous_commit` levels [read: https://www.postgresql.org/docs/current/runtime-config-wal.html]:
  - `on` waits for the synchronous standbys to flush the commit record.
  - `remote_apply` also waits until the commit is visible to queries on them.
  - The setting can be changed per transaction.
- `synchronous_standby_names` [read: https://www.postgresql.org/docs/current/runtime-config-replication.html]:
  - `ANY 1 (s1, s2)` is quorum commit.
  - `FIRST n` is priority-based.
  - Only connected, streaming standbys count.
- Synchronous replication's only loss case is "both the primary and the standby suffer crashes at the same time" [read: https://www.postgresql.org/docs/current/warm-standby.html].
- Commits that were waiting when the primary restarts are marked committed on recovery. The guarantee is only that the client gets no acknowledgement before the standbys have the WAL [read: same page].
- **The hazard: a cancelled wait makes an unreplicated commit visible.**
  - If the backend is cancelled or terminated while waiting for the synchronous ack, PostgreSQL can neither acknowledge nor raise an error. It sends a WARNING, "The transaction has already committed locally, but might not have been replicated to the standby" [read: https://github.com/postgres/postgres/blob/REL_18_STABLE/src/backend/replication/syncrep.c].
  - Patroni's docs spell out the consequence: such changes "become visible for other backends", "are not yet replicated and may be lost in case of standby promotion". This holds even with `synchronous_mode_strict` [read: https://github.com/patroni/patroni/blob/master/docs/replication_modes.rst].
  - The rules this implies [inference]:
    - Never put a client query timeout or `statement_timeout` on COMMIT. Use `lock_timeout` on the UPDATE instead.
    - Never `pg_terminate_backend` application sessions.
    - Treat the ambiguity resolver's "found" answer as final only after the durability barrier (§1.2).
- **Patroni** turns this into automatic failover without loss [read: https://github.com/patroni/patroni/blob/master/docs/replication_modes.rst]:
  - With `synchronous_mode`, Patroni "will not promote a standby unless it is certain that the standby contains all transactions that may have returned a successful commit status".
  - `synchronous_mode_strict` blocks writes rather than drop to async when no synchronous replica exists.
  - `synchronous_mode: quorum` keeps state in the DCS so "any subset of voters that can achieve quorum includes at least one node with the latest successful commit". Example: `ANY 2 (node1,node2,node3)`.
  - In async mode, loss is bounded by `maximum_lag_on_failover` plus the last `ttl` seconds of writes.
- **Multi-site DCS** [read: https://github.com/patroni/patroni/blob/master/docs/ha_multi_dc.rst; https://github.com/patroni/patroni/blob/master/docs/dcs_failsafe_mode.rst]:
  - Run an odd number of etcd/Consul/ZooKeeper nodes; tolerating a zone loss needs at least 3 zones, one node in each.
  - DCS failsafe mode lets a primary keep running during a DCS outage only if it can reach every member over the REST API.
- **CloudNativePG** (Kubernetes) [read: https://github.com/cloudnative-pg/cloudnative-pg/blob/main/docs/src/replication.md; https://github.com/cloudnative-pg/cloudnative-pg/blob/main/docs/src/failover.md]:
  - `dataDurability: required` "ensures zero data loss (RPO=0)" at an availability cost.
  - Quorum-based failover promotes only if it can confirm the candidate holds all synchronously committed data.
  - The scope is a single Kubernetes cluster.
- **RTO**: governed by Patroni `ttl`/`loop_wait` or the CNPG probes. No figure was read; measure it in the PoC [gap].
- **Write latency**: one in-region round trip plus the standby fsync (`on`), or plus replay (`remote_apply`) [inference].
- **DR region**: an asynchronous standby cluster. Patroni's multi-DC page says a two-DC async setup cannot auto-promote, and promoting while the source is up causes split-brain. DR promotion is therefore a manual runbook, which fits the reconcile-from-storage plan [read: https://github.com/patroni/patroni/blob/master/docs/ha_multi_dc.rst].
- **Single writer**: writes are active in one site only.

**Read scaling with read-after-commit**
- Standbys are "eventually consistent" [read: https://www.postgresql.org/docs/current/hot-standby.html].
- `remote_apply` gives causal reads only on the standbys counted in the ack. With `ANY 1 (s1,s2)` the other standby may still be behind [inference from the quorum semantics].
- Standby queries can be cancelled by replay conflicts (`max_standby_streaming_delay` default 30 s) [read: https://www.postgresql.org/docs/current/hot-standby.html; https://www.postgresql.org/docs/current/runtime-config-replication.html].
- **PostgreSQL 19 adds `WAIT FOR LSN`** for "read-your-writes" consistency on standbys. Return values are success, timeout, or not in recovery. It is RC1 now, not GA (as of 2026-11-06) [read: https://www.postgresql.org/docs/19/sql-wait.html; https://www.postgresql.org/docs/19/release-19.html].
- Until 19 is adopted, use §1.3: version on the primary, content from a cache.

**CDC / outbox**
- Logical slots are crash-safe. After a crash they may resend changes, and clients must tolerate duplicates [read: https://www.postgresql.org/docs/current/logicaldecoding-explanation.html].
- PG17 failover slots (`failover`, `sync_replication_slots`, `synchronized_standby_slots`) let decoding resume on a promoted standby. Only slots already in synced state at failover time are usable [read: https://www.postgresql.org/docs/current/logicaldecoding-explanation.html; https://www.postgresql.org/docs/current/logical-replication-failover.html].
- Debezium's PostgreSQL connector:
  - Uses `slot.failover=true` on PG17+ to continue "without losing events".
  - Is at-least-once after faults.
  - Orders per single-partition topic.
  - [read: https://github.com/debezium/debezium/blob/main/documentation/modules/ROOT/pages/connectors/postgresql.adoc]
- CNPG manages `synchronized_standby_slots` on PG17+ [read: cnpg replication.md].
- Watch slot WAL retention: `max_slot_wal_keep_size` defaults to unlimited [read: https://www.postgresql.org/docs/current/runtime-config-replication.html].

**Limits, TTL, names**
- Field size 1 GB; relation size 32 TB; 65,535 query parameters, which matters for N = 100 multi-table batches. Values over about 2 kB are TOASTed [read: https://www.postgresql.org/docs/current/limits.html; https://www.postgresql.org/docs/current/storage-toast.html].
- No native TTL. Use partition drop, or pg_cron: second-granularity schedules; it does not run on a hot standby but starts on promotion [read: https://github.com/citusdata/pg_cron/blob/main/README.md].
- Case-insensitive uniqueness:
  - Nondeterministic ICU collations (`und-u-ks-level2`) work and unique indexes honour them; the evidence is regression-test output, as I found no prose statement [read: https://www.postgresql.org/docs/current/collation.html; https://github.com/postgres/postgres/blob/REL_18_STABLE/src/test/regress/expected/collate.icu.utf8.out].
  - PG18 added LIKE for them [read: https://www.postgresql.org/docs/18/release-18.html].
  - But the planner refuses index prefix scans under a nondeterministic collation (`if (NONDETERMINISTIC(expr_coll)) return NIL;`) [read: https://github.com/postgres/postgres/blob/REL_18_STABLE/src/backend/utils/adt/like_support.c].
  - So use the normalized `name_key` column in C collation (PG18 `casefold()`) for uniqueness and paging (§1.5).

**Ops**
- PITR from base backup plus WAL archive; incremental backups in 17+ [read: https://www.postgresql.org/docs/current/continuous-archiving.html].
- Major upgrades: pg_upgrade "in minutes, particularly with --link", or logical replication with "only several seconds of downtime". PG17 preserves logical slots through pg_upgrade, and `pg_createsubscriber` builds a logical replica from a physical standby [read: https://www.postgresql.org/docs/current/upgrading.html; https://www.postgresql.org/docs/current/app-pgcreatesubscriber.html].

**Java**
- pgJDBC replaced `synchronized` with `ReentrantLock` for Loom in 42.6.0 [read: https://github.com/pgjdbc/pgjdbc/blob/master/CHANGELOG.md].
- Multi-host URLs with `targetServerType=primary` route new connections to the primary [read: https://github.com/pgjdbc/pgjdbc/blob/master/docs/content/documentation/use.md]; an open connection is not moved on failover [inference].

**Licence, expertise, precedent**
- PostgreSQL licence, no cost; commercial support optional [inference].
- The firm's postings mention PostgreSQL and Aurora PostgreSQL alongside Oracle and Db2 [secondary: https://builtin.com/job/principal-database-administrator-dba-mainframe-db2-z-os/9604993; https://builtinbengaluru.in/job/principal-software-engineering-data-engineerdba/4261945].
- Precedent is the strongest of any engine (section 9).

**Disqualifiers**: none. The risks are that HA tooling sits outside the engine and the cancelled-wait rule above.

---

## 3. PostgreSQL as a managed service in AWS: Aurora PostgreSQL and RDS for PostgreSQL

The SQL is the same as section 2, so the catalog code is portable. The behaviour that matters for requirements 3-6 is not. These are the differences from self-hosted PostgreSQL, as of 2026-11-06.

| Aspect | Self-hosted PostgreSQL + Patroni | Aurora PostgreSQL | RDS for PostgreSQL Multi-AZ |
|---|---|---|---|
| In-region durability | WAL to synchronous standbys (`ANY n`) | Storage replicated to six nodes across three AZs [read: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/Concepts.AuroraHighAvailability.html]; commit acknowledged once 4 of 6 writes are durable (AWS blog only, not the User Guide) [read: https://aws.amazon.com/blogs/database/amazon-aurora-postgresql-parameters-part-2-replication-security-and-logging/] | Instance: synchronous standby, not readable, failover typically 60-120 s. Cluster: semi-synchronous, needs an ack from at least one of two readable standbys, failover typically under 35 s [read: https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZ.Failover.html; https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/multi-az-db-clusters-concepts.html; https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/multi-az-db-clusters-concepts-failover.html] |
| Writer failover | Patroni/CNPG | Typically under 60 s, often under 30 s; in-flight reads and writes fail [read: Concepts.AuroraHighAvailability.html] | as above |
| Replica reads | standbys, eventually consistent | asynchronous; lag "usually much less than 100 milliseconds" [read: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/Aurora.Replication.html]; read-after-write needs the writer, or **write forwarding with `apg_write_forward.consistency_mode = GLOBAL`**, where a query waits until the replica has all data committed on the writer as of query start [read: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-postgresql-write-forwarding-configuring.html] | Cluster readers: Jepsen found Long Fork (Snapshot Isolation violated) on Multi-AZ *clusters* via replica reads, with no faults injected; single-node looked like strong SI; AWS says Single-AZ is unaffected [read: https://jepsen.io/analyses/amazon-rds-for-postgresql-17.4; https://aws.amazon.com/blogs/database/understanding-transaction-visibility-in-postgresql-clusters-with-read-replicas/] |
| CDC slots on failover | PG17 failover slots | **Not available**: an AWS blog (2026-08-12) says PG17 failover slots aren't available on Aurora, `sync_replication_slots` isn't exposed, and after writer failover slots "must still be recreated and Debezium reconnected". The same blog elsewhere says a subscription resumes automatically, so it contradicts itself. No decoding from replicas [read: https://aws.amazon.com/blogs/database/migrate-amazon-aurora-postgresql-across-major-versions-with-active-debezium-cdc-connectors-using-native-logical-replication/; https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/AuroraPostgreSQL.Replication.Logical.html] | not researched (gap) |
| DR region | async standby cluster, manual | Aurora Global Database: up to 10 secondary Regions, lag typically under a second, failover RPO "measured in seconds", RTO "minutes", switchover RPO 0. Managed RPO `rds.global_db_rpo` has a minimum of 20 s and pauses commits beyond it. Write fencing is "best-effort" (split-brain possible). The old primary's volume is snapshotted for recovering lost data [read: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-global-database.html; https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-global-database-disaster-recovery.html] | cross-Region read replicas, async |
| Ambiguity | pg_xact_status | plus AWS JDBC Wrapper 08007 `TransactionStateUnknownSQLException` (§1.2) | same |
| Upgrades | pg_upgrade / logical | Blue/Green switchover "typically under a minute", but DDL during the deployment degrades it. Zero-downtime patching for minors drops sessions with open transactions or locks [read: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/blue-green-deployments-overview.html; https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/blue-green-deployments-considerations.html; https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/USER_UpgradeDBInstance.PostgreSQL.MinorUpgrade.html] | Blue/Green |
| Backups | own (pgBackRest etc., not researched) | continuous, 1-35 days PITR, cannot be disabled [read: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/Aurora.Managing.Backups.html] | same model |
| Versions | 18.6 | Aurora PostgreSQL 18 released 2026-06-11; minors 18.6/17.11/16.15/15.19 released 2026-09-29 [read: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraPostgreSQLReleaseNotes/aurorapostgresql-release-calendar.html] | — |

Further notes:
- **Write forwarding** does not forward SAVEPOINT, LOCK, sequence `nextval()` or DDL, and does not support SERIALIZABLE [read: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-postgresql-write-forwarding-limitations.html]. Commits must therefore go to the writer; use only the read-side GLOBAL mode [inference].
- **Verdict** [inference]:
  - Aurora gives the strongest *automatic* in-region durability and the least operational work of any PostgreSQL form.
  - It meets the "same engine on-prem" rule only at the SQL level. HA, read consistency and CDC continuity all behave differently from the on-prem Patroni stack, so the catalog would need two tested HA/CDC profiles.
  - The CDC-slot gap means the outbox relay must survive slot recreation without gaps. One option is a polling fallback that reads only below the visibility horizon (§1.4); another is a Debezium re-snapshot with dedupe.
  - For maximum parity, run the same Patroni/CNPG stack on EC2/EKS in AWS. Choose Aurora when operational load outweighs parity.
- **Aurora DSQL** is out under the deployment rule (AWS-only engine). It would also fail on limits: 3,000 rows and 10 MiB per transaction [read: https://docs.aws.amazon.com/aurora-dsql/latest/userguide/CHAP_quotas.html].

---

## 4. EDB Postgres Distributed (PGD)

**Versions**:
- PGD 6.5.0 (2026-08-20) is "PGD 6's last feature release".
- PGD 5 standard support ends 2027-06-17.
- The next major is "PGD 19", which ships Postgres and PGD together (forward-looking, early 2027) [read: https://github.com/EnterpriseDB/docs/blob/develop/product_docs/docs/pgd/6.5/rel_notes/index.mdx; https://github.com/EnterpriseDB/docs/blob/develop/product_docs/docs/pgd/6.5/overview/product-lifecycle.mdx].

**Why the defaults break a CAS**
- The default commit scope is asynchronous "local protect" [read: …/pgd/6.5/commit-scopes/predefined-commit-scopes.mdx].
- The default conflict resolver is `update_if_newer`, i.e. last-update-wins [read: https://github.com/EnterpriseDB/docs/blob/develop/product_docs/docs/pgd/6.5/conflict-management/conflicts/04_conflict_resolution.mdx].
- EDB's own words: two nodes "can each read the same row, pass their local validation checks, and both commit successfully … with no error reported on either side" [read: https://github.com/EnterpriseDB/docs/blob/develop/product_docs/docs/pgd/6.5/commit-scopes/quorum-commit/index.mdx].
- Row locks from `SELECT … FOR UPDATE` are not replicated. SERIALIZABLE "is supported, but transactions aren't serialized across nodes" [read: …/pgd/6.5/developing-applications/planning.mdx].
- So two catalog nodes writing the same pointer through different PGD nodes can both get 200, and one commit silently vanishes [inference].

**What makes it safe**
- **Quorum Commit**, new in 6.4.0 (2026-05-26):
  - Two-phase commit with always-on eager conflict resolution. "Committed means committed everywhere", with no divergence [read: https://github.com/EnterpriseDB/docs/blob/develop/product_docs/docs/pgd/6.5/commit-scopes/quorum-commit/how-quorum-commit-works.mdx].
  - Writes must go through the single write leader.
  - It costs about two extra network round trips.
  - It "doesn't guarantee consistency when transactions using it execute concurrently on the same tables as transactions that don't", so it must be the group default [read: …/pgd/6.5/commit-scopes/quorum-commit/using-quorum-commit.mdx].
  - It is available only on EDB Postgres Extended or Advanced Server, not community PostgreSQL [read: …/pgd/6.5/planning/choosing_server.mdx].
- In PGD 5.x the only eager option, Group Commit, is "experimental" [read: …/pgd/5.9/commit-scopes/group-commit.mdx].

**Ambiguity**
- After a commit-scope timeout "the outcome is unknown".
- CAMO resolves it only for a two-node pair ("the number of nodes in the group must equal exactly 2").
- Quorum Commit "can't be combined with CAMO".
- [read: …/pgd/6.5/geo-replication-dev/using-commit-scopes.mdx; …/pgd/6.5/commit-scopes/camo.mdx; …/pgd/6.5/overview/known_issues.mdx]

**Topology**
- Write-leader failover uses Raft election timeouts: 3 s local, 6 s global [read: …/pgd/6.5/connection-manager/overview.mdx].
- Under the revised requirements (single home region, async DR acceptable), PGD's distinctive feature, multi-site writes, is not needed. In-region it does the same job as Patroni with more moving parts [inference].

**CDC**
- CDC failover slots (5.7+, EPAS/PGE only) are at-least-once: "there's no way to ensure exactly-once delivery". No Debezium/Kafka guidance [read: …/pgd/6.5/advanced-functionality/cdc-failover.mdx].

**Licence and precedent**
- Per-core subscription; no public price (the pricing page returned 404) [read: EDB Licensing Guide PDF].
- Precedent: none found.

**Verdict**: not recommended. It is unsafe by default, the safe mode is new and proprietary-build-only, there is no precedent, and a re-platform is coming in 2027 [inference].

---

## 5. Oracle Database (RAC + Data Guard / Active Data Guard; Globally Distributed Database)

**Versions**:
- "Oracle AI Database 26ai" replaced 23ai (announced 2025-10-14); docs.oracle.com `/23/` URLs now redirect to `/26/` [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/index.html].
- On-prem Linux x86-64 GA came with RU 23.26.1 (January 2026) [secondary: DBTA, 2026-02-04].
- ojdbc17/ojdbc11 23.26.4.0.0 (Maven, 2026-10-29) [read: https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc17/maven-metadata.xml].

**Transactions and contention**
- READ COMMITTED gives statement-level read consistency. A writer blocked by a committed transaction "proceeds with its intended update to the newly changed row" [read: https://docs.oracle.com/en/database/oracle/oracle-database/19/cncpt/data-concurrency-and-consistency.html].
- The docs present compare-in-WHERE UPDATE as the lost-update defence [read: same page].
- SERIALIZABLE raises ORA-08177 on a write-write conflict. That is a documented fallback for the commit transaction if the benchmark disproves READ COMMITTED re-evaluation [read: same page].
- Row locks never escalate [read: same page].
- `SELECT … FOR UPDATE WAIT n` (26ai accepts sub-second units), `NOWAIT` and `SKIP LOCKED` are available [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/SELECT.html].
- With RAC, a hot block written from several instances shows `gc buffer busy` contention [read: https://docs.oracle.com/en/database/oracle/oracle-database/19/racad/monitoring-performance.html]. At 1-2 commits/s per table this is noise. Spreading many hot tables across instances is fine, and service affinity can pin writes [inference].

**Ambiguous outcomes: Transaction Guard** (§1.2)
- Included in Enterprise Edition. Application Continuity, which uses it, needs the RAC, RAC One Node or ADG option [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/dblic/Licensing-Information.html].
- Needs a user-defined service with `COMMIT_OUTCOME=TRUE`. `RETENTION_TIMEOUT` defaults to 24 h, maximum 30 days [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/adfns/transaction-guard.html].
- The LTXID replicates through redo, so it works across a Data Guard failover. It is excluded for GoldenGate, logical standby and two-phase XA [read: same].
- The 26ai JDBC driver auto-enables Application Continuity support for all data sources [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/jjdbc/application-continuity.html].
- For the catalog, call `GET_LTXID_OUTCOME` explicitly, rather than letting AC replay the commit, so the server stays in control of 409 vs 5xx [inference].

**In-region synchronous topology and DR**
- **Maximum Availability** (SYNC/AFFIRM, or SYNC/NOAFFIRM "FASTSYNC") [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/sbydb/oracle-data-guard-protection-modes.html]:
  - A commit waits for a standby acknowledgement.
  - If no synchronized standby acknowledges, the primary "operates as if it were in maximum performance mode". There is no loss on primary failure provided a synchronized standby exists.
  - NOAFFIRM can lose data in a simultaneous primary-plus-standby-power failure.
- **Maximum Protection** shuts the primary down rather than commit unprotected, so Oracle recommends two standbys [read: same].
- With several SYNC destinations, a commit waits for redo to reach every enabled one [read: https://docs.oracle.com/en/database/oracle/oracle-database/19/sbydb/oracle-data-guard-redo-transport-services.html].
- **Fast-Start Failover** [read: https://docs.oracle.com/en/database/oracle/oracle-database/19/dgbkr/using-data-guard-broker-to-manage-switchovers-failovers.html; https://docs.oracle.com/en/database/oracle/oracle-database/26/dgbkr/faststartfailoverlaglimit.html; https://docs.oracle.com/en/database/oracle/oracle-database/26/haovw/optimizing-automatic-failover-common-scenarios-minimize-downtime1.html]:
  - In Maximum Availability with `FastStartFailoverLagLimit = 0` (the 26ai default for that mode) it is "guaranteed to lose no data".
  - The observer belongs at a third site, with up to 3 observers.
  - MAA recommends a 6-15 s threshold.
  - The old primary is automatically reinstated through Flashback Database.
  - This is the decided topology as one documented recipe: primary (RAC optional) in site A, SYNC standby in site B, observer in site C, plus an ASYNC standby in the DR region.
- **MAA targets** (MAA overview PDF, August 2026; the slide layout extracts jumbled, so check the image) [read: https://www.oracle.com/a/tech/docs/maa-onpremises-overview.pdf]:
  - Gold (RAC + ADG): local HA RTO under 60 s, regional DR RTO under 5 min, RPO zero or near-zero.
  - The same deck charts 26ai Data Guard failover on Exadata at about 15-26 s, down from about 80-94 s on 19c.
- **Far Sync** gives zero data loss "at any distance" but needs the ADG licence [read: https://docs.oracle.com/en/database/oracle/oracle-database/19/sbydb/creating-oracle-data-guard-far-sync-instance.html]. It is not needed in-metro.
- **RAC** is active-active instances on shared storage inside a site; it does not replace DR. Extended (stretched) RAC is "not recommended" beyond 100 km [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/racad/introduction-to-oracle-rac.html; https://oracle.com/a/otn/docs/database/oracle-rac-on-extended-clusters.pdf].
- **Write latency**: one round trip to the SYNC standby plus its standby-redo-log write (AFFIRM) [inference].
- **Single writer**: writes are active in one site, or across RAC instances within a site.

**Globally Distributed Database with Raft** (status 2026-11-06)
- Documented in the 26ai set: "fast automatic failover with zero data loss", failover "sub-3 seconds" with under 10 ms between AZs [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/shard/raft-replication-concepts.html].
- Each replication unit has 3 replicas; the replication factor is "currently limited to three".
- It requires system-managed sharding and does not protect the shard catalog, which needs Data Guard [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/shard/raft-replication.html].
- Multi-shard DML uses 2PC with manual in-doubt recovery [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/shard/supported-dmls-and-examples.html].
- Licensing: every Raft shard needs a RAC licence; AWS deployments are limited to three shards [read: Licensing-Information.html].
- The first release where Raft went GA was not established (gap).
- Verdict: a catalog of a few hundred GB does not need sharding, and Data Guard already meets the in-region rule. Revisit only for MAA "Diamond" (under 3 s RTO) [inference].

**Read scaling with read-after-commit**
- RAC instances are all fully consistent, giving in-site read scale-out with no staleness [inference from RAC's shared-database model].
- **Active Data Guard real-time query** [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/sbydb/managing-oracle-data-guard-physical-standby-databases.html]:
  - With `STANDBY_MAX_DATA_DELAY = 0`, a standby query "is guaranteed to return the exact same result as if the query were issued on the primary". If the standby lags it returns ORA-3172, and the server falls back to the primary.
  - `ALTER SESSION SYNC WITH PRIMARY` waits until the standby catches up.
  - Both require SYNC transport and a SYNCHRONIZED standby in Max Availability or Max Protection, which is the decided topology anyway.
- ADG DML redirection gives read-your-writes to the redirecting session, but in cloud it is OCI-only [read: same; Licensing-Information.html].

**CDC / outbox**
- **Transactional Event Queues** [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/adque/aq-operations-using-pl-sql.html; https://docs.oracle.com/en/database/oracle/oracle-database/26/adque/Kafka_cient_interface_TEQ.html; https://repo1.maven.org/maven2/com/oracle/database/messaging/okafka/maven-metadata.xml]:
  - Enqueue with `visibility = ON_COMMIT`, the default, which makes it part of the current transaction. The outbox is then a durable, ordered queue inside the commit.
  - It bridges to Kafka through the TxEventQ Kafka connectors (DB 21c+, Kafka 3.1+), or a relay using okafka. okafka covers only a subset of the Kafka 2.8.0 client APIs.
  - Latest okafka: 23.26.1.0 (2026-07-17).
- **Debezium Oracle** [read: https://github.com/debezium/debezium/blob/main/documentation/modules/ROOT/pages/connectors/oracle.adoc]:
  - The LogMiner adapter is free. XStream needs a GoldenGate licence, and so does capture of the native JSON type.
  - It can read from a physical standby and accepts a list of archive destinations to survive failover.
- LogMiner `CONTINUOUS_MINE` was desupported in 19c [read: https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_LOGMNR.html].
- GoldenGate for Distributed Applications and Analytics streams committed transactions to Kafka. It is $20,000 per processor list [read: price list below].

**Limits and names**
- VARCHAR2 is 4,000 bytes, or 32,767 with `MAX_STRING_SIZE=EXTENDED`. The JSON type holds up to 32 MB; LOBs are terabytes [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/datatype-limits.html].
- Column collation `BINARY_CI` makes unique and primary-key indexes case-insensitive, but it needs `MAX_STRING_SIZE=EXTENDED`, which is one-way. An `NLSSORT` or `UPPER` function-based unique index is the alternative [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/nlspg/linguistic-sorting-and-matching.html; https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/MAX_STRING_SIZE.html].
- Prefer the normalized `name_key` (§1.5) [inference].

**Ops**
- Flashback Database rewinds a database to a point in time without a restore and reinstates failed primaries [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/haovw/oracle-flashback-technology.html].
- Flashback Query (`AS OF TIMESTAMP`) gives as-of reads of the pointer table, useful for the journal-backed as-of views (D33) [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/SELECT.html; inference for D33].
- Release upgrades take "seconds to minutes" with DBMS_ROLLING, which needs ADG. Edition-Based Redefinition and `DBMS_REDEFINITION` give online schema change [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/haovw/ha-planned-downtime.html; https://docs.oracle.com/en/database/oracle/oracle-database/26/sbydb/using-DBMS_ROLLING-to-perform-rolling-upgrade.html; https://docs.oracle.com/en/database/oracle/oracle-database/26/admin/managing-tables.html].

**Java**
- "Starting from Oracle Database Release 21c, JDBC drivers support virtual threads".
- The 26.x compatibility table lists `ojdbc17.jar` with JDK 17, 19 and 21 only; JDK 25 needs vendor confirmation.
- UCP Fast Connection Failover handles FAN events.
- [read: https://docs.oracle.com/en/database/oracle/oracle-database/26/jjdbc/JDBC-getting-started.html; https://docs.oracle.com/en/database/oracle/oracle-database/26/jjucp/fast-connection-failover.html]

**AWS**
- **RDS for Oracle**: synchronous Multi-AZ standby, no RAC. Read replicas are ADG (read-only, ADG licence) or mounted Data Guard (DR, cross-Region) [read: https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZSingleStandby.html; https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Oracle.Concepts.FeatureSupport.html; https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/oracle-read-replicas.overview.html]. Transaction Guard support on RDS was not checked (gap).
- **Oracle Database@AWS** (Exadata/Autonomous inside AWS data centres) went GA 2025-07-08 and is in 20 Regions as of 2026-05-29 [read: https://aws.amazon.com/about-aws/whats-new/2025/07/oracle-database-at-aws-now-generally-available/; https://aws.amazon.com/about-aws/whats-new/2026/05/oracle-database-aws-available-twenty-regions/]. This is the AWS form with full parity: RAC, ADG and Transaction Guard on the same engine as on-prem [inference].
- **Licensing on EC2/RDS**: 2 vCPUs count as 1 processor licence with hyperthreading on (policy in effect 2026-09-04) [read: https://www.oracle.com/assets/cloud-licensing-070579.pdf].

**Licence and cost**
- Oracle Technology Global Price List, 2026-09-15, USD list per processor (licence / annual support) [read: https://www.oracle.com/assets/technology-price-list-070617.pdf; rows checked by PDF word coordinates]:

| Product | Licence | Annual support |
|---|---|---|
| Enterprise Edition | 47,500 | 10,450 |
| Real Application Clusters | 23,000 | 5,060 |
| Active Data Guard | 11,500 | 2,530 |
| Partitioning | 11,500 | 2,530 |
| GoldenGate | 17,500 | 3,850 |

- A firm of this size almost certainly holds an enterprise agreement, so the marginal cost may be far lower than list [inference].

**Precedent**
- Hive Metastore ships Oracle schema scripts [read: https://github.com/apache/hive/tree/master/standalone-metastore/metastore-server/src/main/sql].
- No Iceberg REST catalog supports Oracle.
- Iceberg's JdbcCatalog SQL uses `LIMIT 1` and `ADD COLUMN`, which Oracle does not accept [read: JdbcUtil.java; inference on dialect].
- The firm is named in Oracle's Database@AWS GA press release as adopting it [read: https://www.oracle.com/news/announcement/oracle-database-at-aws-now-generally-available-2025-07-08/].

**Disqualifiers**: none. The risks are cost, being first to put an Iceberg catalog on Oracle, the unverified JDK 25 certification, and the undocumented statement-restart behaviour, which the benchmark settles.

---

## 6. Microsoft SQL Server (Always On availability groups)

**Versions**:
- SQL Server 2025 went GA 2025-11-18; mainstream support ends 2031-01-07 [read: https://learn.microsoft.com/en-us/lifecycle/products/sql-server-2025].
- mssql-jdbc 13.6.2 (2026-11-02) [read: https://api.github.com/repos/microsoft/mssql-jdbc/releases].

**Transactions and contention**
- Under RCSI an UPDATE selects rows from the actual data with U locks. SNAPSHOT isolation instead aborts with error 3960 on an update conflict [read: https://learn.microsoft.com/en-us/sql/relational-databases/sql-server-transaction-locking-and-row-versioning-guide].
- **Optimized locking** (SQL Server 2025, opt-in per database; always on in Azure SQL DB) [read: https://learn.microsoft.com/en-us/sql/relational-databases/performance/optimized-locking]:
  - Adds TID locking.
  - Adds lock-after-qualification (LAQ): the predicate is checked on the latest committed version and requalified if the row changed.
  - LAQ is skipped for `MERGE`, `OUTPUT` clauses that return results, locking hints, and statements with more than one seek or scan. So write the CAS as a plain single-seek UPDATE and use `executeUpdate`'s row count.
- Lock escalation triggers at about 5,000 locks per statement [read: locking guide]. That is irrelevant for single-row CAS, but watch the N = 100 multi-table commit and the TTL purge batches [inference].
- Default collation `SQL_Latin1_General_CP1_CI_AS` is case-insensitive [read: https://learn.microsoft.com/en-us/sql/relational-databases/collations/collation-and-unicode-support]. That gives case-insensitive unique names for free; I found no doc sentence on unique-index behaviour (gap).

**Ambiguity**: no engine facility. Connection resiliency does not cover open transactions; retry logic must ensure the whole transaction "finished or … rolled back" [read: https://learn.microsoft.com/en-us/sql/connect/jdbc/connection-resiliency; https://learn.microsoft.com/en-us/azure/azure-sql/database/troubleshoot-common-connectivity-issues]. Use §1.2.

**In-region synchronous topology and DR**
- **Synchronous commit** [read: https://learn.microsoft.com/en-us/sql/database-engine/availability-groups/windows/availability-modes-always-on-availability-groups; https://learn.microsoft.com/en-us/sql/t-sql/statements/create-availability-group-transact-sql]:
  - The client is acknowledged after the secondary hardens the log.
  - A secondary exceeding `SESSION_TIMEOUT` (default 10 s) is marked NOT SYNCHRONIZING, and the primary "runs exposed" if none remain.
  - `REQUIRED_SYNCHRONIZED_SECONDARIES_TO_COMMIT` (2017+) closes that gap: "If the minimum number of replicas isn't available, then commits on the primary fail".
  - Up to 5 synchronous replicas (2019+).
- **Failover data loss** [read: https://learn.microsoft.com/en-us/sql/database-engine/availability-groups/windows/failover-and-failover-modes-always-on-availability-groups]:
  - Automatic and planned failover are without loss.
  - Forced failover may lose data, including committed transactions.
  - After failover the new primary rolls back uncommitted work but not committed work.
- **Commit latency** [read: https://learn.microsoft.com/en-us/sql/database-engine/configure-windows/availability-group-commit-time-server-configuration-options; inference on the latency effect]:
  - SQL Server waits 10 ms by default to fill AG log blocks before sending them to secondaries.
  - SQL Server 2025 adds `availability group commit time` to lower it.
  - Tune it, or synchronous commits at low concurrency may pay up to 10 ms.
- **Cluster managers** [read: https://learn.microsoft.com/en-us/sql/linux/business-continuity/availability-groups/high-availability; https://learn.microsoft.com/en-us/azure/azure-sql/virtual-machines/windows/hadr-cluster-quorum-configure-how-to]:
  - WSFC with a witness, or Pacemaker on Linux (`CLUSTER_TYPE=EXTERNAL`).
  - On Linux, `REQUIRED_SYNCHRONIZED_SECONDARIES_TO_COMMIT` is set by default and maintained by Pacemaker. Value 1 keeps one secondary synchronized and makes the primary unavailable until one is [read: https://learn.microsoft.com/en-us/sql/linux/business-continuity/availability-groups/linux-ag-overview].
  - A cloud witness is recommended for multi-site and multi-zone deployments.
- **DR region**: an async replica, or a distributed AG [read: https://learn.microsoft.com/en-us/sql/database-engine/availability-groups/windows/distributed-availability-groups]:
  - Distributed AG failover is manual only: `FORCE_FAILOVER_ALLOW_DATA_LOSS`.
  - On 2022+ the distributed AG itself accepts `REQUIRED_SYNCHRONIZED_SECONDARIES_TO_COMMIT`, which makes a planned failover lossless [read: https://learn.microsoft.com/en-us/sql/database-engine/availability-groups/windows/configure-distributed-availability-groups].
- **Contained AGs** (2022+) carry logins and jobs with the AG, which removes a classic failover gap [read: https://learn.microsoft.com/en-us/sql/database-engine/availability-groups/windows/contained-availability-groups-overview].
- **RTO**: no figure read (gap).

**Read scaling with read-after-commit**: weak.
- Secondary queries are "automatically mapped to snapshot isolation" and see a change only after redo applies it. Latency is usually seconds; latency-intolerant work belongs on the primary [read: https://learn.microsoft.com/en-us/sql/database-engine/availability-groups/windows/active-secondaries-readable-secondary-replicas-always-on-availability-groups].
- No read-your-writes facility was found.
- §1.3 is the only route.

**CDC / outbox**
- CDC capture uses the log reader, which does not process log until it is hardened on all AG secondaries. With only two replicas it stalls after a failover until the old one returns [read: https://learn.microsoft.com/en-us/sql/database-engine/availability-groups/windows/replicate-track-change-data-capture-always-on-availability].
- Capture jobs must be created on the new primary [read: same page].
- Default capture cadence: up to 1,000 transactions per scan, 5 s between scans [read: https://learn.microsoft.com/en-us/sql/relational-databases/track-changes/about-change-data-capture-sql-server].
- Debezium SQL Server sits on CDC, is at-least-once after outages, and can read the change tables from an Always On read-only replica (CDC itself runs only on the primary) [read: https://github.com/debezium/debezium/blob/main/documentation/modules/ROOT/pages/connectors/sqlserver.adoc].
- **Change event streaming** (2025) [read: https://learn.microsoft.com/en-us/sql/relational-databases/track-changes/change-event-streaming/overview; CES FAQ]:
  - Still preview.
  - Targets Event Hubs/Fabric only ("Can CES stream directly to Kafka? No").
  - At-least-once.
  - Must be reconfigured after failover.
  - Cannot coexist with CDC.

**Limits**
- 8,060 bytes in-row with off-row `max` types up to 2 GB.
- Index key limit 900/1,700 bytes [read: https://learn.microsoft.com/en-us/sql/sql-server/maximum-capacity-specifications-for-sql-server].
- Native `json` type in 2025, up to 2 GB; mssql-jdbc supports it from 13.2.0 [read: https://learn.microsoft.com/en-us/sql/t-sql/data-types/json-data-type; https://learn.microsoft.com/en-us/sql/connect/jdbc/use-json-data-type].

**Ops**
- Full recovery model gives point-in-time restore from log backups [read: https://learn.microsoft.com/en-us/sql/relational-databases/backup-restore/recovery-models-sql-server].
- Rolling upgrade costs a single manual failover, but readable secondaries are unavailable during a version upgrade [read: https://learn.microsoft.com/en-us/sql/database-engine/availability-groups/windows/upgrading-always-on-availability-group-replica-instances].
- Accelerated Database Recovery [read: https://learn.microsoft.com/en-us/sql/relational-databases/accelerated-database-recovery-concepts].
- Linux and Kubernetes are supported [read: https://learn.microsoft.com/en-us/sql/linux/business-continuity/containers/high-availability-overview].

**Java**
- mssql-jdbc 13.6 is tested on OpenJDK 25 and 26 [read: https://learn.microsoft.com/en-us/sql/connect/jdbc/system-requirements-for-the-jdbc-driver].
- The changelog notes "Driver is Loom friendly" (12.1.0) [read: https://github.com/microsoft/mssql-jdbc/blob/main/CHANGELOG.md].

**AWS**
- RDS for SQL Server Multi-AZ uses AGs (2025 Standard and Enterprise) and fails over only when fully in sync. "Cross-Region Multi-AZ isn't supported". Enterprise cross-Region read replicas are asynchronous [read: https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_SQLServerMultiAZ.html; https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/SQLServer.ReadReplicas.html].
- CDC on RDS is disabled during restores [read: https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Appendix.SQLServer.CommonDBATasks.CDC.html].

**Licence**
- Full AGs, distributed AGs and contained AGs are Enterprise only. Standard edition basic AGs allow two replicas, one database and no readable secondary [read: https://learn.microsoft.com/en-us/sql/sql-server/editions-and-components-of-sql-server-2025; https://learn.microsoft.com/en-us/sql/database-engine/availability-groups/windows/basic-availability-groups-always-on-availability-groups].
- 2022 list price for Enterprise is $15,123 per 2-core pack; the 2025 pricing page returns 404 [read: https://www.microsoft.com/en-us/sql-server/sql-server-2022-pricing].
- Software Assurance covers passive failover instances for HA and DR [read: https://www.microsoft.com/licensing/guidance/SQL].

**Precedent**
- Hive Metastore supports MS SQL, which behaved correctly in the HIVE-26882 test [read: JIRA comments].
- No Iceberg REST catalog supports SQL Server.

**Disqualifiers**: none. Weaker than Oracle on read-after-commit, ambiguity and CDC.

---

## 7. IBM Db2 (LUW HADR, pureScale; z/OS + GDPS as reference)

**Versions**:
- Db2 LUW 12.1.x; the HADR wiki lists features "New in v12.1.5" [read: https://ibm.github.io/db2-hadr-wiki/featureHistory.html].
- JCC 12.1.5.0 (Maven, 2026-09-18); IBM maps it to driver 4.38.7 [read: https://repo1.maven.org/maven2/com/ibm/db2/jcc/maven-metadata.xml; read (WebFetch summary): https://www.ibm.com/support/pages/db2-jdbc-driver-versions-and-downloads].
- Db2 13 for z/OS function level 508 (2025-10-30) [read (WebFetch summary): IBM Community blog].

**Transactions and contention**
- Cursor Stability is the default, with "currently committed" semantics so readers don't wait on writers [read (WebFetch summary): https://www.ibm.com/docs/en/SSEPGG_12.1.0/com.ibm.db2.luw.admin.perf.doc/doc/c0004121.html; https://www.ibm.com/docs/en/SSEPGG_12.1.0/com.ibm.db2.luw.admin.perf.doc/doc/c0053760.html].
- Exactly how a searched UPDATE re-evaluates its predicate after waiting was not read (gap; benchmark item).
- `LOCKTIMEOUT` defaults to −1 (wait forever). Set `CURRENT LOCK TIMEOUT` [read (WebFetch summary): r0000329.html; r0011857.html].
- Lock escalation from row to table is governed by MAXLOCKS [read (WebFetch summary): r0000268.html].

**Ambiguity**
- Automatic client reroute returns SQL30108N, -4498 or -4499: the transaction is rolled back.
- The HADR team says the last transaction can be committed on the new server even though the client never got the ack.
- No outcome API was found [read: https://ibm.github.io/db2-hadr-wiki/clientReroute.html].

**In-region synchronous topology and DR**
- **SYNC** commits after logs are on disk at both sites. **NEARSYNC** (the default) commits after they are in the standby's memory, so it loses data only on a double failure [read: https://ibm.github.io/db2-hadr-wiki/hadrSyncMode.html].
- **Peer window** blocks primary logging when the standby connection drops, so "if a failover is performed within the window, there will be no data loss" [read: https://ibm.github.io/db2-hadr-wiki/hadrConfig.html].
  - The default is 0; a minimum of 120 s is recommended [read (WebFetch summary): r0052656.html].
  - The integrated cluster manager uses `TAKEOVER … PEER WINDOW ONLY`.
- **Only the principal standby** can be synchronous and automated. Auxiliaries are SUPERASYNC only [read (WebFetch summary): https://www.ibm.com/docs/en/SSEPGG_12.1.0/com.ibm.db2.luw.admin.ha.doc/doc/c0059994.html].
  - 12.1.5 raised the standby limit from 3 to 31 [read: featureHistory.html].
  - So the in-region design is 2 synchronous copies plus a third-site tiebreaker, not a 3-copy quorum.
- **Pacemaker** is integrated since 11.5.5. 12.1.5 adds SBD fencing and a tiebreaker policy [read: https://ibm.github.io/db2-hadr-wiki/clusterManagers.html; featureHistory.html].
- **pureScale** gives in-site active-active through the coupling facility. But HADR on pureScale allows no peer window, only one standby and no reads on standby [read (WebFetch summary): c0061049.html].

**Read scaling**: HADR reads on standby support "Only the uncommitted read (UR) isolation level" [read (WebFetch summary): c0054258.html; read: https://public.dhe.ibm.com/software/data/db2/chatsgcg/hadrDeploy.pdf]. RDS for Db2 replicas are the same [read: https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/db2-replication.html]. That is unusable for loads, so the primary must serve them (§1.3).

**CDC**
- The Debezium Db2 connector needs SQL Replication (ASN capture), which "requires a separate license to IBM Infosphere Data replication (IIDR)".
- It reads change-data tables with default 30 s commit and 5 s sleep intervals.
- [read: https://github.com/debezium/debezium/blob/main/documentation/modules/ROOT/pages/connectors/db2.adoc]
- IIDR's Kafka target offers a transactionally consistent, de-duplicating consumer [read (WebFetch summary): https://www.ibm.com/docs/en/SSTRGZ_11.4.0/com.ibm.cdcdoc.cdckafka.doc/concepts/kafkatcc.html].

**Limits**
- VARCHAR max 32,672 bytes, so projections over about 32 KB must be LOBs.
- Index key 8 KB [read (WebFetch summary): r0001029.html].
- No native JSON type; JSON is stored as text or BSON [read (WebFetch summary): JSON document representation page].
- Case-insensitive uniqueness: a `CLDRCOLL_S2` database collation, or a unique expression index [read (WebFetch summary): Unicode collation and index-on-expression pages].

**Ops**
- ROLLFORWARD to a point in time [read (WebFetch summary)].
- HADR rolling updates cover modification levels only, "cannot be used when upgrading … to a new major release" [read (WebFetch summary): t0011766.html].
- RDS for Db2: GA 2023-11-27; v12.1 support added 2026-06-03 [read: https://aws.amazon.com/about-aws/whats-new/2023/11/amazon-rds-db2/; https://aws.amazon.com/about-aws/whats-new/2026/06/amazon-rds-db2-v12-community-edition/].

**Java**: JCC needs JRE 1.8 or later. There is no statement on JDK 25 or virtual threads (gap).

**Licence**
- HADR is included in Standard edition (up to 16 cores and 128 GB). pureScale needs Advanced [read (WebFetch summary): https://www.ibm.com/docs/en/SSEPGG_12.1.0/com.ibm.db2.luw.licensing.doc/doc/c0058536.html].
- Prices were not found.

**Precedent**: none. Hive Metastore's schema directories are derby, mssql, mysql, oracle and postgres; there is no Db2 [read: https://github.com/apache/hive/tree/master/standalone-metastore/metastore-server/src/main/sql].

**Db2 for z/OS + GDPS (reference)** [read: https://www.redbooks.ibm.com/redbooks/pdfs/sg246374.pdf, SG24-6374-20, August 2025]:
- Data sharing in a Parallel Sysplex presents one image.
- Only synchronous replication with automation gives RPO 0.
- Metro Mirror is supported to 300 km, and HyperSwap switches disks with a brief I/O hold.
- GDPS Continuous Availability is asynchronous software replication with RPO of seconds.
- This is the firm's tier-1 pattern for brokerage [secondary: the firm's Db2 z/OS DBA posting]. But it is **out under the deployment rule**: there is no Db2 for z/OS in AWS.

**Verdict**: weakest fit among the engines the firm already runs [inference].

---

## 8. MySQL InnoDB Cluster / Group Replication (brief)

- **Single-primary Group Replication** elects a new primary automatically. Writes commit only after group certification [read (WebFetch summary): https://dev.mysql.com/doc/refman/8.4/en/group-replication-single-primary-mode.html; https://dev.mysql.com/doc/refman/8.4/en/group-replication-summary.html].
- **Consistency `AFTER`** makes a write wait until all members have applied it, so later reads on any member see it. `BEFORE` makes reads wait instead [read (WebFetch summary): https://dev.mysql.com/doc/refman/8.4/en/group-replication-configuring-consistency-guarantees.html]. This is real read-after-commit replica scaling, paid for in commit latency [inference].
- **Transaction size limit** defaults to about 143 MB [read (WebFetch summary): system-variables page].
- **ClusterSet** (DR region) is asynchronous: "There is no guarantee that data will be preserved in the event of an emergency failover", and that failover is manual [read (WebFetch summary): https://dev.mysql.com/doc/mysql-shell/8.4/en/innodb-clusterset.html].
- **Jepsen 8.0.34**: Repeatable Read allows lost updates. Read Committed and Serializable looked correct on single nodes and small binlog-replicated clusters, through faults. RDS MySQL clusters violated Serializability (replica commit order off). Group Replication was not tested [read: https://jepsen.io/analyses/mysql-8.0.34].
- **Default collation** `utf8mb4_0900_ai_ci` is case- and accent-insensitive [read (WebFetch summary): https://dev.mysql.com/doc/refman/8.4/en/charset-unicode-sets.html]. Accent-insensitivity would make `é` and `e` collide in identifiers, so pick a collation deliberately [inference].
- **Versions**: MySQL moved to calendar versions (26.7 = July 2026); Connector/J 26.10.0 (2026-10-20) [read (WebFetch summary): https://dev.mysql.com/doc/refman/9.7/en/mysql-releases.html; read: https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/maven-metadata.xml].
- **Not researched this session**: Debezium MySQL binlog connector, Connector/J virtual-thread behaviour, MySQL Enterprise pricing.
- **Precedent**: LinkedIn OpenHouse runs its House Tables service on MySQL in production, with JPA `@Version` CAS [read: https://github.com/linkedin/openhouse/blob/main/ARCHITECTURE.md; https://engineering.linkedin.com/blog/2023/taking-charge-of-tables--introducing-openhouse-for-big-data-mana].
- **Verdict**: workable; adds nothing over PostgreSQL here [inference].

---

## 9. Precedent and Jepsen (cross-cutting)

**Which store each catalog uses** (beyond `persistence_scale_ha.md`):

| Catalog | Relational store | How it does the commit CAS | Source |
|---|---|---|---|
| Iceberg JdbcCatalog | any JDBC; docs example is MySQL; **tests use SQLite only** | `UPDATE … WHERE metadata_location = ?`, row count = 1; no isolation set; pool replays on connection loss (bug #18743) | [read: https://github.com/apache/iceberg/blob/main/docs/docs/jdbc.md; JdbcUtil.java; JdbcClientPool.java] |
| Polaris | PostgreSQL (recommended), CockroachDB | entity-version CAS | `persistence_scale_ha.md` |
| Lakekeeper | PostgreSQL only | — | `persistence_scale_ha.md` |
| Nessie JDBC2 | PostgreSQL production; MariaDB and MySQL "experimental"; no Oracle, SQL Server or Db2 | — | [read: https://github.com/projectnessie/nessie/blob/main/site/in-dev/configuration.md] |
| Gravitino | MySQL, PostgreSQL, H2 | `current_version` OCC plus `SELECT … FOR UPDATE` on parents | [read: Gravitino TableMetaBaseSQLProvider.java; schema-2.0.0-*.sql] |
| Unity Catalog OSS | H2 default; MySQL and PostgreSQL examples via Hibernate | unique `(table_id, commit_version)` for Delta commits | [read: https://github.com/unitycatalog/unitycatalog/blob/main/docs/server/deployment.md; DeltaCommitDAO.java] |
| LinkedIn OpenHouse | **MySQL in production** | JPA `@Version`; the code admits rename is not version-guarded | [read: openhouse ARCHITECTURE.md; UserTablesServiceImpl.java] |
| Hive Metastore (HiveCatalog) | Derby, MySQL/MariaDB, PostgreSQL, **Oracle, MS SQL** | HMS lock, or `expected_parameter` check (HIVE-28121); `checkCommitStatus` on uncertainty | [read: hive sql directory tree; HiveTableOperations.java] |

**At scale**
- Netflix's re:Invent 2023 talk (via a third-party transcript):
  - A MySQL-RDS-backed Hive Metastore with about 250 M partitions hit limits under competing commits.
  - Netflix built an internal "Polaris" metastore on CockroachDB, citing point updates and horizontal scale.
  - [secondary: https://lilys.ai/en/notes/apache-iceberg-20251101/aws-reinvent-netflix-iceberg-data-lake]
  - Iceberg removes partitions from the catalog, so that load profile does not transfer [inference].
- Airbnb, Bloomberg, Apple: no page naming their catalog's backing database was read (gap).

**Jepsen coverage** [read: https://jepsen.io/analyses, index fetched 2026-11-06]
- Analyses exist for PostgreSQL 12.3 (2020), MySQL 8.0.34 (2023), Amazon RDS for PostgreSQL 17.4 (2025) and MariaDB Galera 12.1.2 (2026).
- **There is no Jepsen analysis of Oracle, SQL Server, Db2, EDB PGD, Patroni or Aurora.** Their failover-safety claims rest on vendor documentation alone.

---

## 10. Scorecard

Scale: 3 = strong fit, 2 = fits with work, 1 = weak or risky, 0 = fails. Dimension 2 is scored for the decided topology only: 3 AZs or sites synchronous in one region, plus async DR.

| Dimension | PostgreSQL self-managed (Patroni / CNPG) | Aurora / RDS PostgreSQL (AWS form) | EDB PGD | Oracle (EE + ADG, ± RAC; DB@AWS) | SQL Server AG | Db2 LUW HADR | MySQL InnoDB Cluster | Db2 z/OS + GDPS |
|---|---|---|---|---|---|---|---|---|
| 1. Transactions, CAS, contention, ambiguity | **3**: RC re-evaluates WHERE (doc); row-lock queue; `lock_timeout`/`transaction_timeout`; `pg_xact_status` | **3**: same engine; JDBC wrapper 08007 | **1**: default async + LWW loses a CAS; Quorum Commit (6.4+, EPAS/PGE) needed | **3**: compare-in-WHERE documented, restart mechanism undocumented; ORA-08177; no escalation; **Transaction Guard** | **2**: RCSI U-lock or LAQ requalify; escalation; no outcome API | **2**: CS; `LOCKTIMEOUT` −1 default; -30108 rollback; no outcome API | **2**: conditional UPDATE fine; RR unsafe (Jepsen); 143 MB txn cap | (3) |
| 2. In-region sync + async DR | **2**: Patroni quorum/strict or CNPG `required`; 3-site etcd; cancelled-wait hazard; manual DR | **3**: 6 copies / 3 AZ storage quorum; failover <60 s; Global DB RPO seconds, best-effort fencing | **2**: majority Quorum Commit; 3-6 s leader failover; new | **3**: Max Availability SYNC + FSFO lag 0 + 3rd-site observer; ASYNC DR standby; MAA Gold | **3**: sync AG + `REQUIRED_SYNCHRONIZED_SECONDARIES_TO_COMMIT`; witness; async/distributed AG DR; 10 ms batching to tune | **2**: one sync (principal) standby + peer window; auxiliaries SUPERASYNC | **2**: Paxos majority in region; ClusterSet async, manual lossy failover | (3, metro only) |
| 3. Strong read scaling | **2**: `remote_apply`; PG19 `WAIT FOR LSN` (RC); §1.3 | **2**: write-forwarding GLOBAL waits on replica; lag <100 ms | **1**: no documented consistent remote reads | **3**: RAC instances consistent; ADG `STANDBY_MAX_DATA_DELAY=0` (ORA-3172 fallback) | **1**: secondaries SI-mapped, lagging, no RYW | **1**: standby UR only | **2**: GR `AFTER`/`BEFORE` | (n/a) |
| 4. CDC / outbox | **3**: Debezium + PG17 failover slots; at-least-once, ordered | **1**: no failover slots on Aurora; slots recreated after writer failover | **2**: CDC failover slots (EPAS/PGE), at-least-once | **2**: TxEventQ same-txn queue + Kafka connector; Debezium LogMiner free; XStream/JSON need GG | **2**: CDC + Debezium; re-arm on failover; CES preview, Event Hubs only | **1**: IIDR licence even for Debezium; seconds latency | **2**: binlog/Debezium (not researched) | (1) |
| 5. Limits | **3**: 1 GB field; partition the journal (32 TB/relation) | **3**: 256 TiB volume (newer versions) | **2**: direct-to-writer streaming of large txns experimental | **3**: JSON 32 MB, LOBs TB | **3**: `max`/`json` 2 GB | **2**: VARCHAR 32 KB, so LOBs for projections | **2**: 143 MB GR txn | (2) |
| 6. Ops maturity | **2**: PITR, logical-replication upgrades in seconds; HA is add-on tooling | **3**: managed PITR, Blue/Green, ZDP | **2**: rolling upgrades; PGD 19 re-platform 2027; scarce skills | **3**: RMAN, Flashback, DBMS_ROLLING, EBR, FSFO; deep DBA bench | **3**: log PITR, ADR, 1-failover rolling upgrade, contained AGs | **2**: fix-pack-only rolling; Pacemaker | **2** | (3) |
| 7. Licence / cost | **3**: free | **2**: consumption; AWS only | **1**: per-core subscription, unpublished, EPAS/PGE needed | **1**: EE $47.5k + ADG $11.5k (+ RAC $23k) per processor list | **2**: Enterprise $15,123 / 2 cores (2022 list); SA covers passive replicas | **2**: HADR in Standard; IIDR extra; prices unpublished | **3** [inference: GPL community] | (1) |
| 8. Precedent | **3**: Polaris, Lakekeeper, Nessie, Gravitino, Unity | **2**: same code, no named Aurora user read | **0** | **1**: HMS only; no REST catalog | **1**: HMS only | **0** | **2**: OpenHouse (LinkedIn), Gravitino, HMS | 0 |
| 9. Disqualifiers | none | none hard; HA/CDC behaviour differs from on-prem | soft: unsafe default, new safe mode | none (cost; JDK 25 cert) | none | soft: UR-only reads, one sync standby | none | **OUT**: not deployable in AWS |
| TTL / CI-unique / prefix paging | partition drop, pg_cron; ICU `ks-level2` (no prefix index) or `casefold()` key | same; pg_cron on RDS/Aurora not checked | as PostgreSQL | partitions (paid option) or DBMS_SCHEDULER; `BINARY_CI` (one-way EXTENDED) or FBI | partition switch or Agent job; default CI collation | `CLDRCOLL_S2` or expression index | default `_ai_ci` (accent-insensitive) | — |
| **Total (dims 1-8)** | **21** | **19** | **11** | **19** | **17** | **12** | **17** | n/a |

The totals are a reading aid, not the decision. Section 11 gives the reasons.

---

## 11. Finalists

1. **PostgreSQL** (Patroni or CNPG on-prem; the same stack, or Aurora PostgreSQL with the section 3 caveats, in AWS).
   - It meets every requirement with the portable design in section 1.
   - It has by far the most Iceberg-catalog precedent, the best open CDC path (failover slots plus Debezium), a Loom-ready driver and no licence cost.
   - The firm already runs PostgreSQL and Aurora PostgreSQL [secondary].
   - What it needs:
     - Patroni `synchronous_mode: quorum` with strict mode, or CNPG `dataDurability: required` with a failover quorum, and etcd in 3 sites.
     - A "never cancel a COMMIT" rule plus the §1.2 durability barrier.
     - Section 1.3 for loads until PostgreSQL 19's `WAIT FOR LSN` is adopted.
2. **Oracle Database** (EE + Active Data Guard; RAC if the DBA standard requires it; Oracle Database@AWS for the AWS form).
   - The strongest *engine-native* fit to the hardest requirements:
     - Transaction Guard makes ambiguous commits definitively resolvable (requirement 5).
     - `STANDBY_MAX_DATA_DELAY=0` gives read-after-commit off the synchronous standby (requirement 3).
     - Max Availability with FSFO lag limit 0 and a third-site observer is a documented, vendor-supported, zero-loss automatic failover (requirement 4, in-region).
     - TxEventQ can make the outbox transactional and ordered (requirement 6).
   - It is the firm's established tier-1 platform, and the firm is publicly adopting it inside AWS.
   - Choose it if the DBA organisation wants the catalog on its standard Oracle MAA tier and an enterprise agreement makes cost moot.
   - Its risks: being the first Iceberg catalog on Oracle, JDK 25 certification, and the undocumented READ COMMITTED restart (benchmark B1 settles it; SERIALIZABLE is the documented fallback).

**Why not SQL Server**: equal on in-region durability and ops. It is worse on read-after-commit (no consistent secondary reads), ambiguity (no outcome API) and CDC (re-arm per failover; CES preview and not Kafka). Re-open it if the DBA organisation's SQL Server AG estate is the stronger tier-1 platform.

**Why not Aurora as the PostgreSQL choice everywhere**: it cannot run on-prem. On AWS it is an attractive operational choice, but its HA and CDC semantics differ from on-prem PostgreSQL, so pick it knowingly.

**Why not EDB PGD, Db2 LUW or MySQL**: see sections 4, 7 and 8.

---

## 12. Proposed local benchmark (Docker on a Windows laptop) for the two finalists

**Setup** [inference unless tagged]
- Docker Desktop (WSL2), with fixed CPU and memory limits per container. The load generator runs on the host JVM.
- **PostgreSQL 18**: three containers (primary + 2 streaming standbys) under Patroni with a 3-node etcd. `synchronous_mode: quorum` and strict mode. Run once with `synchronous_commit=on` and once with `remote_apply`.
  - A second variant uses the PostgreSQL 19 RC image for `WAIT FOR LSN`.
  - An image tag that bundles Patroni was not checked this session; building a small Patroni image is the fallback.
- **Oracle**: the Oracle AI Database 26ai Free container. **Unverified this session**: Free-edition resource caps, and whether Free includes Data Guard, Transaction Guard, TxEventQ and LogMiner. Check the Free licence and feature list before planning Oracle items beyond single-instance CAS. If Data Guard is not in Free, the Oracle HA items need an Enterprise Edition test environment.
- **Kafka** (Redpanda or Apache Kafka) plus Kafka Connect with Debezium 3.7 (PostgreSQL connector; Oracle LogMiner connector).
- **Toxiproxy** between the JVM and each database, to cut connections at exact points. Optional `tc netem` delay of about 1-2 ms between PostgreSQL containers to imitate AZ spacing; whether netem works on the WSL2 kernel is unverified.
- **Harness**: Java 25, virtual threads, HikariCP, pgJDBC 42.7.14 and ojdbc17 23.26.4.
  - Record JFR virtual-thread pinning events, to settle Oracle's undeclared JDK 25 support empirically.
  - Identical schema and the §1.1 transaction on both engines.
  - Pool-level retries **disabled**.

**Workloads**

| ID | Workload | Measure | Pass / look for |
|---|---|---|---|
| B1 | **Contended single-row CAS**: 1-64 writers on one table, three variants: (a) conditional UPDATE + row count at READ COMMITTED; (b) `SELECT … FOR UPDATE` then UPDATE; (c) SERIALIZABLE. Plus a **deterministic interleaving test**: S1 updates and holds; S2 issues the conditional UPDATE with the old expected value; S1 commits; S2 must report 0 rows. | commits/s, p50/p99, outcome mix (409, 40001/ORA-08177, lock timeouts) | **Zero double winners**: journal versions contiguous, pointer version = success count, outbox count = journal count. The interleaving test proves Oracle's undocumented READ COMMITTED restart. |
| B2 | **Multi-record commit** across 100k tables at 50-500/s; projection 2 KB, 50 KB and 300 KB; journal 1-20 KB with 1% at 2 MB (jsonb vs Oracle JSON/LOB) | p50/p99, CPU per commit, WAL/redo bytes per commit | Latency flat as payload grows; synchronous-ack cost visible with netem. |
| B3 | **Multi-table commit** N = 1, 10, 50, 100 with B1 running, rows locked in `table_id` order; then deliberately unordered | latency, abort and deadlock rate | All-or-nothing via journal; ordered = no deadlocks; unordered shows the victim path maps to 409. |
| B4 | **Load QPS**: (a) full projection from the primary; (b) §1.3 pointer-version on primary + version-keyed cache; (c) PostgreSQL standby reads under `remote_apply` and PG19 `WAIT FOR LSN`. Ramp toward 20k/s with B2 running. | reads/s per DB core, p99, primary CPU | **Read-after-commit checker**: writer acks, then signals a reader on another connection; 0 stale reads. Shows how far (b) cuts primary load. |
| B5 | **Ambiguous commits**: Toxiproxy cuts the connection right after COMMIT is sent; `docker kill` / `pause` of the PostgreSQL primary mid-commit (Patroni fails over). Resolve with §1.2 (PostgreSQL) and `GET_LTXID_OUTCOME` (Oracle). Also re-enable pool retry to reproduce Iceberg #18743. | misclassified outcomes, resolution latency | **0 misclassified**: every acked commit present; every "not committed" verdict stays not-committed. Pool replay reproduces the false 409. |
| B6 | **PostgreSQL cancelled-wait hazard**: pause both standbys, start a commit, cancel the backend, read the row from another session, kill the primary, let Patroni promote | visible-then-lost count | Demonstrates the hazard; verify Patroni strict/quorum and the no-cancel rule prevent it in normal operation. |
| B7 | **Outbox to Kafka**: Debezium PostgreSQL with `slot.failover` across a Patroni failover; Oracle via Debezium LogMiner (and TxEventQ if available in Free) | end-to-end lag p99, gaps, duplicates, per-table order | No gaps after failover; duplicates only (dedupe by outbox id). |
| B8 | **TTL and names**: idempotency purge (batched DELETE vs partition drop) under B2; duplicate-key storms; 50k-table namespace listed by keyset on `name_key` with prefix | p99 impact of purge; paging correctness | No skipped or duplicated names; case variants rejected; expired keys never served. |

**What a laptop cannot show**
- Real inter-AZ latency and jitter, or enterprise-storage fsync. Per-commit latency at production spacing stays unknown.
- Oracle RAC, Data Guard / FSFO, ADG `STANDBY_MAX_DATA_DELAY=0` and Exadata/Database@AWS behaviour, unless Free supports them, which is unverified. Their RTO needs an Enterprise Edition environment.
- Aurora: write forwarding, storage-quorum failover, slot behaviour after writer failover and Global Database DR. These need an AWS account test.
- 20k loads/s and 500 commits/s on dedicated hardware, with laptop CPU shared by every container. Multi-TB journal growth and partition maintenance at production volume.
- Cross-region async DR lag, failover runbooks and the reconcile-from-storage procedure. Licence terms and DBA operability.

---

## 13. Gaps and open questions for the firm

**Gaps in this research**
- No failover-RTO figures read for Patroni, CNPG or SQL Server AG.
- No doc text for:
  - Oracle READ COMMITTED statement restart.
  - Db2 searched-UPDATE predicate re-evaluation.
  - SQL Server unique-index behaviour on CI collations.
  - pgJDBC behaviour on connection loss during COMMIT.
- Aurora logical-slot behaviour after in-region writer failover: AWS's only statement contradicts itself.
- Transaction Guard on RDS for Oracle: not checked.
- Oracle JDK 25 certification and Db2 JCC virtual-thread support: not stated by the vendors.
- SQL Server 2025 and Db2 list prices: not available.
- IBM and MySQL docs read only as WebFetch summaries (curl blocked).

**Questions for the firm**
1. Which engine and MAA tier does the DBA organisation offer as tier 1 for a new service: Oracle MAA Gold/Platinum, SQL Server AG, Patroni-managed PostgreSQL or Aurora? And does an Oracle enterprise agreement cover EE + ADG (+ RAC)?
2. Is Oracle Database@AWS (named in Oracle's press release) available to this team as the AWS form?
3. Is there a supported on-prem PostgreSQL HA standard (Patroni, CNPG, EDB tooling) and a 3-site etcd or Consul?
4. Is TxEventQ, GoldenGate or IIDR already licensed and run? That decides the cheapest durable outbox path on Oracle or Db2.

---

## Provenance

- **Method.** Helper agents fetched sources with curl into `C:/Users/paulh/AppData/Local/Temp/claude/d9ac3f89f5ec51f6/2eb8cba6-c503-4baa-9e18-b3df89b0f733/scratchpad/downloads/db_relational/`, in the subfolders `postgres/`, `aws_pg/`, `edb_pgd/`, `oracle/`, `sqlserver/`, `db2_mysql/` and `precedent/`. HTML was stripped to `.txt` with a stdlib script. I re-grepped the saved files for every load-bearing quote above. The Oracle price rows were re-read by PDF word coordinates, because `pdftotext -layout` shifts the rows.
- **Read (saved and grepped).**
  - PostgreSQL 18 docs and PG19 `WAIT`/release notes; PostgreSQL source (`syncrep.c`, `xact.c`, `like_support.c`, collate regression output, `jsonb.h`).
  - Patroni docs (master); CloudNativePG docs (main); pgJDBC changelog and docs; pg_cron README.
  - Debezium connector docs (postgresql, oracle, sqlserver, db2, outbox router).
  - AWS Aurora, RDS, Aurora DSQL and RDS for Db2, Oracle and SQL Server pages; AWS Advanced JDBC Wrapper docs; AWS database blogs (2021-04-12, 2025-05-02, 2026-08-12).
  - EDB PGD 5.9, 6.1 and 6.5 docs (GitHub MDX) and the EDB licensing guide.
  - Oracle 19c and 26ai docs (Data Guard, broker, HA overview, RAC, Transaction Guard, DBMS_APP_CONT, JDBC, UCP, sharding/Raft, TxEventQ, SQL reference, NLS, licensing), the MAA PDF (Aug 2026), the RAC extended-cluster whitepaper, the price list (2026-09-15) and the cloud-licensing policy.
  - Microsoft Learn SQL Server docs (GitHub Markdown, `live` branch), the mssql-jdbc changelog and releases, and the SQL Server 2022 pricing page.
  - IBM Db2 HADR wiki (ibm.github.io), the IBM GDPS Redbook SG24-6374-20 and the 2011 HADR deck.
  - Jepsen: index, PostgreSQL 12.3, RDS for PostgreSQL 17.4, MySQL 8.0.34.
  - Code: Iceberg (JdbcCatalog, HiveTableOperations, issue #18743 and PR #18745), Gravitino, OpenHouse, Unity OSS, Nessie configuration, Hive `sql/` tree and DatabaseProduct.java, HIVE-26882/28121 JIRA.
  - The Oracle Database@AWS GA press release; the LinkedIn OpenHouse blog.
- **WebFetch summaries only.** IBM Docs (Db2 12.1, Db2 13 z/OS JCC, IIDR 11.4, IBM Community posts) and dev.mysql.com (8.4, 9.7, Shell ClusterSet). Both return HTTP 403 to curl.
- **Secondary.** The Netflix re:Invent 2023 transcript (lilys.ai); the firm's job postings on Built In mirrors (the firm's own careers site returned "Page Not Found"); DBTA (2026-02-04) and Oracle-Base on 26ai release numbering.
- **Seen in search snippets only, not used.** The firm's postings listing SingleStore, Aerospike and DynamoDB; a Waters Technology "all in on AWS" article; re:Invent 2014 FIN202.
- **Inherited, not redone.** `../Enterprise Iceberg REST catalog plan/persistence_scale_ha.md`: PostgreSQL READ COMMITTED WHERE re-evaluation, Aurora Global DR basics, Polaris/Lakekeeper/Nessie/Gravitino/Unity store choices.

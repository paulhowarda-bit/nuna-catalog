# Distributed SQL candidates for the catalog database (D7)

Research date 2026-11-06. Scored against `00_requirements.md` as revised the same day: one catalog in ONE environment (on-prem or AWS), the same engine self-hostable in both; zero loss of acknowledged commits inside one region or metro (3 AZs or 3 sites, synchronous quorum); asynchronous replication to a DR region is acceptable; cross-region synchronous writes are not required.

## Bottom line

- **Finalists: CockroachDB and YugabyteDB (YSQL).** Both are PostgreSQL-wire, run self-hosted on-prem and in AWS, give SERIALIZABLE transactions with synchronous Raft quorum across 3 AZs, and ship an asynchronous DR product to a second region. CockroachDB has the stronger catalog precedent (Polaris JDBC supports it; Netflix's Iceberg metastore reportedly runs on it), native row TTL and a documented ambiguous-commit error code (40003). YugabyteDB is Apache 2.0 and has the best outbox story: its CDC delivers changes in commit order per slot.
- **TiDB is the runner-up.** It has strongly consistent follower reads (best read scale-out of the three), native TTL and native case-insensitive collations, but offers snapshot isolation only (no SERIALIZABLE), so check-then-act operations need explicit locking discipline, and its default 6 MB row limit needs raising for MB-sized journal entries.
- **Spanner Omni is the one to watch, not to pick now.** It is the only self-hostable Spanner, with the strongest consistency model here (external consistency), but it went GA on 2026-09-30 (five weeks ago), has no SLA, no documented Omni-to-Omni async DR product, full backups only, and cannot run under VM live migration.
- **Out on the deployment rule:** Cloud Spanner (GCP-managed only) and Aurora DSQL (AWS-managed only, no self-hosted equivalent). DSQL would also fail on limits: 2 MiB rows, 1 MiB non-indexed columns and 10 MiB per write transaction.
- **Disqualifiers found: none for the two finalists.** The real risks are CockroachDB's licence cost (proprietary since 24.3) and YugabyteDB's thin catalog precedent and lack of native YSQL TTL.

Tags: **[read: URL]** I fetched and read the source this session; **[secondary: URL]** a third-party summary I read; **[inference]** my reasoning, not a source claim. GitHub doc links point at the Markdown files I curl-ed (raw copies saved under the scratchpad `downloads/db_distsql/` folder). Spanner and AWS pages were fetched as HTML and stripped to text.

---

## 1. What the workload asks of a distributed SQL database

The commit unit is: read pointer, compare-and-set (CAS) the pointer, upsert the projection, insert a journal row, insert outbox rows, insert the idempotency result. Typically 5-6 rows, a few KB to a few hundred KB; a multi-table commit is the same across up to ~100 tables. Loads dominate (5k sustained, 20k peak per second); commits peak at ~500/s with hot tables at 1-2/s. [read: 00_requirements.md]

Portable shape of the commit transaction (PostgreSQL dialect; MySQL and Spanner variants differ only in syntax) [inference]:

```sql
BEGIN;
UPDATE table_pointer
   SET metadata_location = $new_loc, version = version + 1,
       projection = $proj, metadata_sha256 = $sha, updated_at = now()
 WHERE table_id = $id AND version = $expected;      -- 0 rows => CAS lost => server-side retry / 409
INSERT INTO commit_journal (table_id, version, commit_id, principal, body) VALUES (...);
                                                     -- PK (table_id, version): a second guard against
                                                     -- two winners from the same base
INSERT INTO audit_outbox (...) VALUES (...);
INSERT INTO idempotency (idem_key, response, expires_at) VALUES (...);  -- unique key
COMMIT;
```

Design notes that apply to every candidate [inference]:
- Linearizable CAS comes from the write lock (or write-write conflict check) on the pointer row, not from the isolation level name. Every candidate below detects a concurrent writer on the same row at READ COMMITTED or above. The journal primary key `(table_id, version)` makes "two winners from one base" impossible even if a lock were lost.
- Ambiguous commits (requirement 5): any error on `COMMIT` that is not a definite abort must become a 5xx. Resolve it later by looking up `commit_id` in the journal; that lookup works the same on every engine.
- Check-then-act operations need care below SERIALIZABLE. Example: drop-namespace-if-empty racing create-table-in-namespace is write skew under snapshot isolation. Use `SELECT ... FOR UPDATE` on the namespace row, or a foreign key.

---

## 2. CockroachDB (v26.x)

**Versions (dated).** v25.4 is the current LTS (LTS from 2026-05-03); v26.2 is the latest regular release (2026-04-27); v26.3 is an innovation release (2026-08-19, maintenance to 2027-02-19, no assistance phase). [read: https://github.com/cockroachdb/docs/blob/main/src/current/_data/versions.csv]

**Licence change (2024).** Every version from 24.3.0 on, and patches to 23.1-24.2 released after that date, ship under the proprietary CockroachDB Software License. The free "Core" edition is gone. "Enterprise Free" applies only below $10M combined annual revenue including affiliates, so the firm needs paid Enterprise. Free and Trial licences require telemetry; a cluster that goes 7 days without sending it is throttled to 5 concurrent transactions. A cluster with no licence key is throttled 7 days after initialisation, which matters for multi-node benchmarks. Single-node dev clusters need no key. Major-version upgrades need a valid key. Paid Enterprise never throttles on expiry. Prices go through sales. [read: https://github.com/cockroachdb/docs/blob/main/src/current/_includes/common/licensing-faqs.md; https://github.com/cockroachdb/docs/blob/main/src/current/_includes/common/upgrade-cockroach-version-license-limitations.md]

**Atomic unit and isolation.**
- Multi-statement ACID transactions "spanning arbitrary tables and rows". SERIALIZABLE is the default. READ COMMITTED is available, is "stronger than the PostgreSQL READ COMMITTED", and is the strongest level that does not raise serialization errors needing client-side handling. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/transactions.md] The v26.2 setting `sql.txn.read_committed_isolation.enabled` defaults to true. [read: https://github.com/cockroachdb/cockroach/blob/release-26.2/docs/generated/settings/settings.html]
- Errors: retry errors are SQLSTATE `40001` "restart transaction"; ambiguous outcomes are `40003` "result is ambiguous", possible only on the final `COMMIT`/`RELEASE` or an implicit statement. A connection drop before commit means a definite abort. [read: transactions.md; https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/common-errors.md] This maps directly onto requirement 5.
- Single-key linearizability: Jepsen (2017, beta build) found single keys linearizable and the database serializable but not strictly serializable, provided clock offset stays within bounds. Skew beyond the bound means "all bets are off". [read: https://jepsen.io/analyses/cockroachdb-beta-20160829] Current docs agree: skew outside `--max-offset` "can result in violations of single-key linearizability", and a node whose clock is out of sync with at least half the cluster by 80% of max-offset crashes. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/architecture/transaction-layer.md] `--max-offset` defaults to 500 ms. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/cockroach-start.md] The Jepsen report is nine years old; there is no newer Jepsen analysis of CockroachDB on jepsen.io as of 2026-10-31. [read: https://jepsen.io/analyses]

**One hot row.** Conflicting requests wait in lock wait-queues that are generally first-come-first-served, with documented non-FIFO exceptions under mixed contention. `SELECT ... FOR UPDATE` puts competing writers "into a queue based on when they first try to read". [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/select-for-update.md; architecture/transaction-layer.md] Under SERIALIZABLE you can still get `RETRY_WRITE_TOO_OLD` ("common ... when you have too much contention") or `RETRY_SERIALIZABLE`. The documented mitigations are sending the statements as one batch (which lets the server auto-retry) and `SELECT FOR UPDATE`. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/transaction-retry-error-reference.md] Server-side auto-retry works only while results stay under `sql.defaults.results_buffer.size`, 512 KiB in v26.2. [read: settings.html v26.2] (The transactions page still says 16 KiB; the generated settings table is authoritative.) Field evidence: Nessie lists CockroachDB as experimental, "known to raise user-facing 'write too old' errors under contention". [read: https://github.com/projectnessie/nessie/blob/main/site/in-dev/configuration.md] The lesson is that a catalog on CockroachDB must own a 40001 retry loop; Nessie evidently surfaces them instead. [inference]

**Limits.** There is no default per-transaction row cap; `transaction_rows_written_err`/`_read_err` are opt-in guardrails. [read: https://github.com/cockroachdb/docs/blob/main/src/current/_includes/v26.3/sql/transactions-limit-rows.md] The row-size guardrail `sql.guardrails.max_row_size_err` defaults to 80 MiB (logged above 16 MiB). `kv.transaction.max_intents_bytes` is 4 MiB; it bounds lock-tracking memory and is not a size cap. [read: settings.html v26.2] Neither bites this workload. [inference]

**Topology (in-region sync, async DR).**
- In one region, ranges replicate 3x (or 5x) by Raft across zones. ZONE survival is the default and keeps the database available through a zone failure. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/multiregion-survival-goals.md] RPO is 0 for node or AZ loss. [inference]
- DR region: Physical Cluster Replication (PCR) replicates asynchronously to a standby cluster, with lag "generally in the tens-of-seconds range" and failover "typically ... within five minutes". It can fail over to a past timestamp. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/physical-cluster-replication-overview.md]
- Logical Data Replication (active-active) resolves conflicts last-write-wins, so it is unsafe for a CAS pointer. Use it only one-way, if at all. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/logical-data-replication-overview.md; inference]
- Per-table home region is possible (`REGIONAL BY ROW` with a hidden `crdb_region` column) but moot under the single-region decision. If it were used, global uniqueness of a name not keyed by region costs a cross-region check on insert. [read: https://github.com/cockroachdb/docs/blob/main/src/current/_includes/v26.3/sql/regional-by-row-table-description.md; https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/alter-table.md]

**Strongly consistent read scaling.** Strong reads come from the leaseholder only; follower reads are historical (`AS OF SYSTEM TIME`). `GLOBAL` tables allow strong reads from any replica but make writes pay commit-wait. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/follower-reads.md; https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/global-tables.md] The closed-timestamp target defaults to 3 s. [read: settings.html v26.2] Loads must therefore hit leaseholders. With 1M tables spread over many ranges, leaseholders spread across nodes and read capacity grows with node count. A single table polled thousands of times a second would concentrate on one leaseholder; ETag/304 checks keep each read cheap. [inference]

**CDC and outbox.** Native changefeeds to Kafka: per-key ordering on first emission, at-least-once delivery, and `resolved` timestamps per partition. Changefeeds "do not support total message ordering or transactional ordering", so ordering across outbox rows needs buffering on resolved timestamps (or sorting by `updated`/`ts_hlc`). [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/changefeed-messages.md] The row-level TTL docs name the outbox pattern explicitly. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/row-level-ttl.md] A Debezium CockroachDB connector built on changefeeds exists (Apache 2.0; tags up to v3.7.1.Final and v3.8.0.Alpha1, created 2025-06-20). [read: https://github.com/debezium/debezium-connector-cockroachdb README and tags] Paul has run CockroachDB changefeeds into Kafka and Debezium before. [read: ~/.claude/notes/user-profile.md]

**TTL, case-insensitive names, paging.**
- Native row-level TTL (`ttl_expire_after` or `ttl_expiration_expression`) runs as rate-limited background jobs. Expired rows "may not be deleted right away", so idempotency reads must also filter on `expires_at > now()`. [read: row-level-ttl.md]
- Case-insensitive unique names: a unique expression index on `lower(name)`, or a `ks-level2` (case-insensitive) collation. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/expression-indexes.md; https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/collate.md]
- Ordered primary keys support keyset paging by `(namespace_id, name)` prefix. [inference]

**Ops.**
- Full and incremental backups with revision history give point-in-time restore inside the GC window; scheduled backups protect data from GC until backed up. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/backup-and-restore-overview.md; take-backups-with-revision-history-and-restore-from-a-point-in-time.md]
- Rolling patch and major upgrades, with rollback before finalisation. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/upgrade-cockroach-version.md]
- Online schema changes run as background jobs "without holding locks". [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/online-schema-changes.md]
- Kubernetes via an operator, Helm or StatefulSets. [read: kubernetes-overview.md]
- Java: stock PgJDBC, with a documented retry pattern and `reWriteBatchedInserts=true`. [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/build-a-java-app-with-cockroachdb.md]

**Precedent.**
- Apache Polaris JDBC "currently supports only PostgreSQL and CockroachDB". [read: https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/metastores/relational-jdbc.md] CockroachDB support was added 2026-03-09 (PR #3352) as a schema port; the commit message mentions no retry handling. [read: https://github.com/apache/polaris/commit/8f9ff62a993e6fa990677d26a499676a01593291]
- Netflix reportedly backs its internal Iceberg metastore (also called "Polaris", unrelated to Apache Polaris) with CockroachDB, citing AWS re:Invent 2023 NFX306. [secondary: https://vutr.substack.com/p/i-spent-4-hours-learning-how-netflix] Not confirmed from a Netflix source.

**Disqualifiers.** None. Risks: proprietary licence and cost; clock discipline (NTP, 500 ms max-offset, crash-on-skew); 40001 handling is mandatory.

---

## 3. YugabyteDB YSQL (v2025.2 LTS / v2026.1 STS)

**Versions.** v2026.1 is the current STS and v2025.2 the current LTS; v2026.2, the next LTS, is planned for "End 2026". [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/releases/ybdb-releases/_index.md; releases/_index.md]

**Licence.** The core database is "100% open source ... Apache 2.0". YugabyteDB Anywhere (self-managed control plane) is commercial; Aeon is the managed service. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/faq/general.md]

**Atomic unit and isolation.**
- YSQL supports Serializable, Snapshot (as REPEATABLE READ) and Read Committed. Read Committed needs `yb_enable_read_committed_isolation`. That flag is on by default for new v2025.2+ universes deployed by yugabyted, YBA or Aeon; otherwise RC silently runs as Snapshot. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/architecture/transactions/isolation-levels.md]
- Errors: retryable `40001` (serialization failure) and `40P01` (deadlock). [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/develop/learn/transactions/transactions-errorcodes-ysql.md] I found no documented ambiguous-commit SQLSTATE comparable to CockroachDB's 40003, so treat commit-time connection loss as unknown. [read absence in that page; inference]
- Read-after-commit under clock skew is protected by "read restart" errors, raised when the database cannot resolve the ambiguity internally. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/architecture/transactions/read-restart-error.md] `--max_clock_skew_usec` defaults to 500 ms. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/reference/configuration/yb-tserver.md]
- Jepsen (2019, v1.3.1) found G2-item anomalies when masters were unreachable (since fixed), DEFAULT columns initialised to NULL, and fragile DDL. Jepsen advised against automating DDL. [read: https://jepsen.io/analyses/yugabyte-db-1.3.1] Old, and there is no newer Jepsen report. [read: https://jepsen.io/analyses]

**One hot row.** The default is Wait-on-Conflict (`enable_wait_queues=true`): "the same semantics as PostgreSQL", with in-memory wait queues, distributed deadlock detection and best-effort internal retries. The alternative Fail-on-Conflict mode uses wound/die priorities. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/architecture/transactions/concurrency-control.md] Clients still need a 40001 retry loop for SERIALIZABLE and REPEATABLE READ; Read Committed retries internally. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/develop/learn/transactions/transactions-retries-ysql.md]

**Limits.** Rows up to 32 MB, with ≤10 MB recommended; columns ≤2 MB recommended. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/develop/best-practices-develop/data-modeling-perf.md] I found no documented per-transaction row cap. MB-scale journal rows fit but sit above the recommendation. [inference]

**Topology.**
- In-region: RF3 across 3 AZs with automatic failover. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/explore/multi-region-deployments/synchronous-replication-ysql.md]
- DR region: transactional xCluster (async) preserves transactional atomicity and commit order on the target. Failover has "non-zero" RPO (equal to replication lag) and "very low" RTO. Automatic mode (data and schema changes) is the recommended setup from v2025.2.1. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/deploy/multi-dc/async-replication/async-replication-transactional.md] Non-transactional bidirectional xCluster is last-writer-wins, so unsafe for CAS. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/architecture/docdb-replication/async-replication.md]
- Per-table home region via tablespaces with `leader_preference` and list-partitioned geo-partitioning is possible but moot. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/explore/going-beyond-sql/tablespaces.md; row-level-geo-partitioning.md]

**Strong read scaling.** Strong reads go to the tablet leader. Follower reads run at "current time minus" `yb_follower_read_staleness_ms`, default 30 s, so they are unsuitable for loads. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/additional-features/follower-reads.md; yb-tserver.md] Scale-out comes from spreading tablet leaders, as with CockroachDB. [inference]

**CDC and outbox.** PostgreSQL logical replication protocol with the YugabyteDB connector (Debezium-based). Guarantees: "per-slot ordered delivery" (changes from all published tables in commit order), at-least-once, and no gaps. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/additional-features/change-data-capture/using-logical-replication/_index.md] This is the strongest native ordering of any candidate for an audit outbox. [inference]

**TTL, case-insensitive names, paging.**
- No native YSQL TTL: the YSQL TTL page is "DISABLED for lack of content". [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/develop/learn/ttl-data-expiration-ysql.md] `pg_cron` is pre-bundled for scheduled deletes. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/additional-features/pg-extensions/_index.md]
- Collations are deterministic only and `citext` is not in the supported-extension list, so use a unique `lower(name)` expression index. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/explore/ysql-language-features/advanced-features/collations.md; pg-extensions/_index.md]
- Choose `HASH` or `ASC` per key column explicitly; use `(namespace_id HASH, name ASC)` for prefix paging. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/architecture/docdb-sharding/sharding.md; inference]

**Ops.**
- PITR uses flashback within a configurable history retention (Aeon's default is 15 min) plus snapshot schedules; PITR is unavailable during an upgrade until finalise or rollback. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/manage/backup-restore/point-in-time-recovery/_index.md; manage/upgrade-deployment.md]
- Rolling upgrades with finalise and rollback phases. [read: upgrade-deployment.md]
- Transactional DDL is early access, disabled by default. [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/explore/transactions/transactional-ddl.md]
- Java: stock PgJDBC, or the YugabyteDB smart driver (a PgJDBC fork with cluster-aware load balancing). [read: https://github.com/yugabyte/yugabyte-db/blob/master/docs/content/stable/develop/drivers-orms/java/yugabyte-jdbc.md]

**Precedent.** I found no Iceberg catalog or Hive Metastore deployment on YugabyteDB (one search). [read: search results only, no hit]

**Disqualifiers.** None. Risks: no catalog precedent; no native TTL; DDL robustness was historically weak (Jepsen 2019); RC is off by default on manually deployed universes.

---

## 4. TiDB (v8.5 LTS)

**Versions.** 8.5.x is the current LTS line; the latest patch is 8.5.8 (2026-08-27). There is no 9.x GA in the release timeline. [read: https://github.com/pingcap/docs/blob/master/releases/release-timeline.md] Licence: Apache 2.0. [read: https://github.com/pingcap/tidb/blob/master/LICENSE]

**Atomic unit and isolation.**
- Snapshot isolation, advertised as REPEATABLE READ, which "allows broad phantoms (P3) and write skews". READ COMMITTED applies in pessimistic mode only. There is no SERIALIZABLE. [read: https://github.com/pingcap/docs/blob/master/transaction-isolation-levels.md]
- Pessimistic mode is the default for clusters created on v3.0.8+. DML performs a "current read" of the latest committed version under a lock; waiters block up to `innodb_lock_wait_timeout` (50 s), deadlocks return 1213, and pessimistic transactions are capped at 1 h by default (`max-txn-ttl`). [read: https://github.com/pingcap/docs/blob/master/pessimistic-transaction.md]
- So a conditional `UPDATE ... WHERE version = ?` is a safe CAS; drop-namespace-if-empty style checks need `FOR UPDATE`. [inference]
- Caveat: the default pipelined and in-memory pessimistic locks can be lost on TiKV failure. Then "other transactions that modify the same data cannot be blocked", and application logic that relies on lock waiting loses correctness; the transactions themselves stay correct, with a "low probability" that a commit fails. [read: pessimistic-transaction.md] So the CAS must not rely on the lock alone: the conditional `UPDATE ... WHERE version = ?` plus the journal PK guard in section 1 still prevent two winners, because at most one of two conflicting commits can succeed. [inference]
- Jepsen (2019): 2.1.7 lost updates by default because of auto-retry. With auto-retry disabled, which is the default since 3.0.0-rc.2, TiDB passed snapshot-isolation and single-key-linearizability tests. [read: https://jepsen.io/analyses/tidb-2.1.7] Error 8022 means "rolled back ... can safely retry". I found no ambiguous-commit code. [read: https://github.com/pingcap/docs/blob/master/error-codes.md]

**Limits.**
- Single row/entry 6 MB by default (`txn-entry-size-limit`, configurable up to 120 MB; TiKV `raft-entry-max-size` of 8 MB must be raised too). [read: https://github.com/pingcap/docs/blob/master/tidb-configuration-file.md]
- Transactions 100 MB by default (up to 1 TB). [read: https://github.com/pingcap/docs/blob/master/transaction-overview.md]
- MB-scale journal entries need configuration. [inference]

**Topology.**
- 3 AZs in one region: "RPO = 0". Writes cost at least two inter-AZ round trips (2PC), and every transaction fetches a timestamp (TSO) from the single PD leader. [read: https://github.com/pingcap/docs/blob/master/multi-data-centers-in-one-city-deployment.md]
- DR: a primary-secondary cluster via TiCDC gives RPO < 10 s and RTO < 5 min; BR log backup gives RPO < 5 min. [read: https://github.com/pingcap/docs/blob/master/dr-solution-introduction.md]
- Per-table leader region via placement policies (`PRIMARY_REGION`) is possible but moot. [read: https://github.com/pingcap/docs/blob/master/placement-rules-in-sql.md]

**Strong read scaling.** The best of the three: Follower Read reads followers "while maintaining strong consistency" via Raft ReadIndex, at the cost of one extra round trip to the leader. [read: https://github.com/pingcap/docs/blob/master/follower-read.md]

**CDC.** TiCDC to Kafka (canal-json, Avro, Debezium format since v8.0). Single-row order is preserved; downstream transaction order is not guaranteed; duplicates are possible. [read: https://github.com/pingcap/docs/blob/master/ticdc/ticdc-overview.md; ticdc/ticdc-debezium.md]

**TTL, case-insensitive names.** Native row-level table TTL run as background jobs. [read: https://github.com/pingcap/docs/blob/master/time-to-live.md] Filter idempotency reads on `expires_at` anyway, as with the others. [inference] Native `_ci` collations; the new collation framework defaults to on, fixed at bootstrap. Note that `utf8mb4_general_ci` is PAD SPACE, so trailing spaces compare equal; prefer `utf8mb4_0900_ai_ci` (NO PAD, but also accent-insensitive) or a `lower()` approach. [read: https://github.com/pingcap/docs/blob/master/character-set-and-collation.md; tidb-configuration-file.md]

**Ops.**
- BR snapshot plus log-backup PITR. [read: https://github.com/pingcap/docs/blob/master/br/br-pitr-guide.md]
- TiUP rolling upgrade, but "does not support version downgrade or rolling back ... after the upgrade". [read: https://github.com/pingcap/docs/blob/master/upgrade-tidb-using-tiup.md]
- "All supported DDL changes can be performed online." [read: https://github.com/pingcap/docs/blob/master/mysql-compatibility.md]
- Java: MySQL Connector/J. [read: https://github.com/pingcap/docs/blob/master/develop/dev-guide-sample-application-java-jdbc.md]

**Precedent.** Zhihu moved its Hive Metastore from MySQL to TiDB to scale metadata storage. [read: https://pingcap.com/case-studies/horizontally-scaling-hive-metastore-database-by-migrating-from-mysql-to-tidb] I found no Iceberg REST catalog on TiDB.

**Disqualifiers.** None hard. Weaknesses: no SERIALIZABLE; MySQL dialect, which forks the SQL from the PostgreSQL-wire finalists and from Polaris/Lakekeeper practice; no downgrade path. Vendor third-party-risk review of PingCAP is a question for the firm's procurement. [inference]

---

## 5. Spanner Omni (self-hosted Spanner)

**Status (dated).** Preview 2026-04-22; GA 2026-09-30 with release 2026.r4-lts (security fixes "for up to one year"); 2026.r5 on 2026-10-29. In June 2026 a beta patch fixed "a TrueTime issue in which time servers entered a repeated failover mode". [read: https://docs.cloud.google.com/spanner-omni/release-notes] Google reports over 2 million downloads since launch. [read: https://cloud.google.com/blog/products/databases/spanner-omni-deploy-anywhere-version-of-spanner-is-now-ga]

**Deployment.** Runs on VMs, containers or Kubernetes, on-prem (RHEL 9, Ubuntu 22) and in AWS (EC2, EKS). Topologies: single server, single zone, multi-zone (regional; minimum 3 zones) and multi-cluster. Paxos replication with software TrueTime. [read: https://docs.cloud.google.com/spanner-omni/overview] It meets the "self-hostable in both environments" rule.

**Atomic unit and isolation.**
- Read-write transactions default to SERIALIZABLE with external consistency (strict serializability); REPEATABLE READ is optional. Concurrency is lock-based (wound-wait). Aborted transactions are retried by the client libraries, and each retry raises lock priority. [read: https://docs.cloud.google.com/spanner/docs/transactions] Omni ships the same engine, and r5 "includes all updates in the ... managed Spanner service up to" 2026-10-27. [read: release-notes]
- Software TrueTime relies on one primary time server per deployment; uncertainty grows with RTT to it. It is "not supported during live migrations" of VMs or containers, except qualified AWS machine types and AMIs. That restriction rules out vMotion-style on-prem virtualisation. [read: https://docs.cloud.google.com/spanner-omni/true-time-external-consistency]

**Limits (managed Spanner; the same engine is assumed for Omni [inference]).** 10 MiB per cell; 100 MiB commit; 80,000 mutations per commit; 8 KiB keys. [read: https://docs.cloud.google.com/spanner/quotas]

**Topology.** In-region: multi-zone Paxos. For DR, Google documents only managed-Spanner-to-Omni replication via change streams and a Beam/Dataflow pipeline you build. There is no packaged Omni-to-Omni async DR. [read: https://docs.cloud.google.com/spanner-omni/replicate-data] Omni lacks geo-partitioning, incremental backups and built-in CMEK. Its PITR version retention goes up to 30 days. [read: https://docs.cloud.google.com/spanner-omni/differences]

**Reads.** Strong reads are guaranteed to observe all prior commits "independent of which replica receives the read". [read: https://docs.cloud.google.com/spanner/docs/reads]

**CDC.** Change streams, via the Debezium Spanner connector ≥3.7.0 with `spanner.type=OMNI`. [read: https://docs.cloud.google.com/spanner-omni/debezium] Records are ordered by commit timestamp per primary key; cross-key ordering comes from low-watermark buffering. [read: https://docs.cloud.google.com/spanner/docs/change-streams/use-kafka]

**TTL, case-insensitive names.** TTL by row deletion policy, "typically ... within 72 hours". [read: https://docs.cloud.google.com/spanner/docs/ttl] For case-insensitive uniqueness, use a stored generated `LOWER(name)` column with a unique index. [inference, not verified this session]

**Ops, licence, Java.**
- No SLA; full backups to S3 or GCS; LTS-to-LTS upgrades only, no skipping; rollback impossible after finalise. [read: differences; https://docs.cloud.google.com/spanner-omni/backups; https://docs.cloud.google.com/spanner-omni/upgrade-deployment]
- Disk-level encryption is yours to configure; authentication by password (OPAQUE) or client certificate; no custom roles. [read: differences]
- Commercial edition: annual per-vCPU subscription, price via sales; the free Developer edition is non-production. [read: https://docs.cloud.google.com/spanner-omni/editions-overview]
- Java: the Spanner JDBC driver (gRPC, ≥2.41.0, `;type=omni`) or the client library. It is not PostgreSQL wire; PGAdapter offers that for the PostgreSQL dialect. [read: https://docs.cloud.google.com/spanner-omni/jdbc-driver; overview]

**Disqualifiers.** Not formally, but too new for a tier-1 go-live today: five weeks GA, no SLA, no packaged async DR, scarce DBA expertise [inference], and the live-migration restriction.

---

## 6. Brief coverage: out under the deployment rule

**Cloud Spanner (managed).** It would score top marks on correctness and reads, but it is GCP-only and therefore out under D36 unless the self-hosted twin (Spanner Omni, section 5) is chosen. [read: https://docs.cloud.google.com/spanner-omni/differences]

**Amazon Aurora DSQL.** AWS-managed only, with no self-hosted equivalent, so it is out under D36. For the record:
- Optimistic concurrency at snapshot isolation; conflicts surface at commit as `40001` with OC000 (data) or OC001 (schema). [read: https://docs.aws.amazon.com/aurora-dsql/latest/userguide/working-with-concurrency-control.html]
- Limits: 3,000 mutated rows and 10 MiB of modified data per transaction; 5 min maximum transaction age; 2 MiB rows; 1 MiB non-indexed columns; 128 MiB query memory. [read: https://docs.aws.amazon.com/aurora-dsql/latest/userguide/CHAP_quotas.html] The 1-2 MiB caps fail the "occasionally MBs" journal entry, and a 100-table commit with projections of a few hundred KB each exceeds 10 MiB. [inference]
- C collation only; no triggers or temporary tables. [read: https://docs.aws.amazon.com/aurora-dsql/latest/userguide/working-with-postgresql-compatibility-unsupported-features.html]
- Multi-region linked clusters with a witness region. [read: https://docs.aws.amazon.com/aurora-dsql/latest/userguide/disaster-recovery-resiliency.html]
- CDC goes to Kinesis only, in `UNORDERED` mode, at-least-once; records for one primary key "can arrive out of commit order". [read: https://docs.aws.amazon.com/aurora-dsql/latest/userguide/cdc-streams.md] A web-search snippet described it as a May 2026 preview; I did not read the announcement, so its GA status is unverified.

**Other systems screened.**
- **Oracle Globally Distributed AI Database with Raft replication** is credible if the firm's Oracle team offers it. Commits are acknowledged once a majority of replicas hold the commit record ("zero data loss"); failover is under 3 s with under 10 ms network latency. [read: https://docs.oracle.com/en/database/oracle/oracle-database/23/shard/raft-replication-concepts.html] I did not evaluate cross-shard transaction semantics, which a multi-table commit would need; treat it as unevaluated.
- OceanBase, SingleStore, Vitess/PlanetScale, Aurora Limitless, AlloyDB Omni and YDB were not researched this session. FoundationDB is a key-value store, not SQL, and belongs to the KV evaluation.

---

## 7. Scorecard

Scale: 3 = strong fit, 2 = fits with work, 1 = weak or risky, 0 = fails. Dimension 2 is scored only for the decided topology: 3 AZs synchronous in one region plus async DR.

| Dimension | CockroachDB | YugabyteDB YSQL | TiDB | Spanner Omni | Cloud Spanner | Aurora DSQL |
|---|---|---|---|---|---|---|
| 1. Transactions, CAS, contention | **3**: SERIALIZABLE default, RC optional; lock wait-queues; 40001 retry loop mandatory; 40003 ambiguous code | **3**: SERIALIZABLE/SI/RC; PostgreSQL-style wait-on-conflict; 40001/40P01; no ambiguous code found | **2**: SI only, pessimistic current-read CAS is safe; check-then-act needs FOR UPDATE; locks can be lost on failure | **3**: externally consistent serializable; wound-wait; library retries aborts | 3 (out) | 1: OCC first-committer-wins; hard 3,000-row, 10 MiB, 5 min caps |
| 2. In-region sync + async DR | **3**: Raft 3 AZ RPO 0; PCR async, lag tens of seconds, failover ≤5 min | **3**: RF3 3 AZ; transactional xCluster async, RPO = lag, low RTO | **3**: 3 AZ RPO 0; TiCDC 1:1 RPO <10 s, RTO <5 min; single PD TSO leader | **2**: multi-zone Paxos; no packaged Omni-to-Omni async DR; full backups only | out (GCP only) | out (AWS only) |
| 3. Strong read scaling | **2**: leaseholder-only strong reads; follower reads stale | **2**: leader-only; follower reads 30 s stale | **3**: strongly consistent follower reads (ReadIndex) | **3**: strong reads from any replica | 3 (out) | n/a |
| 4. CDC / outbox | **2**: native changefeeds to Kafka, per-key order plus resolved ts; Debezium connector Final | **3**: logical replication with per-slot commit order, no gaps | **2**: TiCDC per-row order, no txn order; Debezium format | **2**: change streams plus Debezium (OMNI); per-key order plus watermark | 2 (out) | 1: Kinesis only, unordered, at-least-once |
| 5. Limits | **3**: 80 MiB row guardrail; no default txn cap | **2**: 32 MB row max, ≤2 MB columns advised | **2**: 6 MB row default (configurable to 120 MB), 100 MB txn | **2**: 10 MiB cell, 100 MiB commit | 2 (out) | **0**: 2 MiB row, 1 MiB column, 10 MiB txn |
| 6. Ops maturity | **3**: revision-history PITR, PCR, rolling upgrades with rollback, operator, online DDL | **3**: PITR (flashback plus snapshots), rolling upgrades with rollback, YBA; DDL historically fragile | **2**: BR plus log PITR, TiUP rolling, online DDL; **no downgrade** | **1**: GA 5 weeks; no SLA; no live migration; DIY encryption at rest | 3 (out) | managed (out) |
| 7. Licence / cost | **2**: proprietary CSL since 24.3; paid Enterprise; price via sales | **3**: Apache 2.0 core; optional commercial YBA | **3**: Apache 2.0 | **2**: per-vCPU annual, unpublished price | consumption (out) | consumption (out) |
| 8. Precedent | **3**: Polaris JDBC support (2026-03); Netflix metastore [secondary]; Nessie "experimental" | **1**: none found | **2**: Zhihu Hive Metastore | **1**: Spanner engine at Google; Omni brand new; no catalog | 2 | 1 |
| 9. Disqualifiers | none (risks: licence, clocks) | none (risks: precedent, no TTL) | none hard (no SERIALIZABLE; MySQL dialect) | none formal; maturity | **OUT**: managed-only | **OUT**: managed-only; limits |
| TTL / CI unique / paging | native TTL; `lower()` index or ks-level2; ordered keys | pg_cron; `lower()` index; HASH/ASC keys | native TTL; `_ci` collations; ordered | TTL ≤72 h; generated column [inference] | as Omni | no TTL found; C collation |
| **Total (dims 1-8)** | **21** | **20** | **19** | **16** | n/a | n/a |

The totals are a reading aid, not the decision. The finalists are chosen on the qualitative reasons in section 8.

---

## 8. Finalists

1. **CockroachDB.** The highest correctness bar delivered today: SERIALIZABLE by default, a documented ambiguous-commit code that maps one-to-one onto requirement 5, and native TTL. It also has the most catalog precedent (Apache Polaris JDBC, reportedly Netflix) and native Kafka changefeeds, plus a Debezium connector and Paul's own changefeed experience. PCR covers the async DR leg. The cost is a proprietary, sales-priced licence (the 2024 change) and strict clock discipline.
2. **YugabyteDB YSQL.** The open-licence alternative with the same PostgreSQL-wire, Raft-in-region, async-DR shape. It has PostgreSQL lock-wait semantics on hot rows and the best audit-outbox CDC (commit-ordered per slot). It is weaker on precedent and needs `pg_cron` for TTL.

Why not TiDB: it is the closest third and wins on read scale-out. It loses on isolation (SI only), on SQL dialect (MySQL, while the rest of the plan and the Polaris and Lakekeeper references are PostgreSQL), on the 6 MB default row limit and on upgrade rollback. Re-open it if the firm's DBA team already runs TiDB.

Why not Spanner Omni: it is the strongest consistency model, but it is too new for tier 1. Revisit at the next LTS (roughly 2027), checking for Omni-to-Omni async DR and published pricing.

---

## 9. Proposed local benchmark (Docker on a Windows laptop)

**Setup** [inference unless noted]
- Docker Desktop (WSL2 backend). Give the database containers fixed CPU and memory limits and run the load generator on the host JVM, so the generator does not starve the databases.
- CockroachDB: 3 containers of `cockroachdb/cockroach` (v25.4 LTS or v26.2), `cockroach start --join` then `cockroach init`. An unlicensed multi-node cluster is throttled to 5 concurrent transactions 7 days after initialisation, so finish within 7 days, use a 30-day Trial key (telemetry required), or rebuild the cluster. [read: licensing FAQ]
- YugabyteDB: 3 containers of `yugabytedb/yugabyte` (v2025.2 LTS), `yugabyted start --join`, RF3. Run SERIALIZABLE and READ COMMITTED variants; RC must be enabled explicitly on manual deployments. [read: isolation-levels.md]
- Kafka: one Redpanda or Apache Kafka container plus Kafka Connect for the Debezium/YugabyteDB connectors.
- Optional: add 1 ms of inter-node delay with `tc netem` (container `NET_ADMIN`) or Pumba to imitate AZ spacing. Whether netem works in the Docker Desktop WSL2 kernel is unverified, so check it first.
- Optional third: Spanner Omni single-server (`us-docker.pkg.dev/spanner-omni/images/spanner-omni:2026.r5`) for functional parity only, since a single server has no Paxos quorum. [read: https://docs.cloud.google.com/spanner-omni/download]
- Harness: one Java 25 program with virtual threads, PgJDBC plus HikariCP, and identical SQL for both finalists. It implements the section 1 transaction, the D3 server-side retry loop, and a 40001 retry loop with jittered backoff.

**Workloads and what each must show**

| ID | Workload | Measure | Pass / look for |
|---|---|---|---|
| W1 | Contended single-row CAS: 1, 2, 4, 8, 16, 32, 64 writers on one table, each doing read-base, build, commit | Commits/s, p50/p99, outcome mix (CAS-lost 409, 40001, timeouts) | **Zero double winners**: journal versions per table contiguous with no duplicates; pointer version = success count; outbox count = journal count. Find the knee where 40001 or timeouts dominate (real hot tables need only 1-2/s). |
| W2 | Uncontended commits across 100k tables at 50-500/s; projection 2 KB, 50 KB and 300 KB; journal 1-20 KB with 1% at 2 MB | p50/p99, throughput ceiling per core | Latency stays flat as payload grows; MB rows accepted. |
| W3 | Multi-table commit N = 1, 10, 50, 100 atomically, with W1 running in the background | Latency, abort rate | All-or-nothing verified by journal; no deadlock storms. |
| W4 | Loads: point read of pointer plus projection by `(ns, lower(name))`, ETag-only variant and full fetch; ramp toward 20k/s with W2 running | Reads/s per DB core, p99 | **Read-after-commit checker**: the writer acknowledges, then signals a reader on another connection and node; 0 stale reads allowed. |
| W5 | List a namespace of 50k tables by keyset page, with prefix filter | Page p99, stability under concurrent creates | No skipped or duplicated names across pages. |
| W6 | Idempotency: duplicate-key insert storms; TTL or pg_cron expiry running | Conflict handling, delete impact on W2 p99 | Expired keys not served (filter on `expires_at`). |
| W7 | Faults: `docker kill` or `pause` of one node, and of the leaseholder/leader node, during W1-W3 | Unavailability window, count of ambiguous commits (40003, connection loss at COMMIT) | **Every acknowledged commit present afterwards; every ambiguous one resolvable by `commit_id` lookup.** |
| W8 | Outbox to Kafka: CockroachDB changefeed (and its Debezium connector) vs YugabyteDB logical replication connector | End-to-end lag p99, duplicates, per-table order violations, recovery after connector restart | Ordered per table after dedup; no gaps after a kill. |

**What a laptop cannot show**
- Real inter-AZ and cross-region latency and jitter (netem only approximates them), so per-commit latency at production spacing stays unknown.
- Async DR behaviour: PCR or xCluster lag under WAN conditions, failover RTO, and the reconcile-from-storage runbook.
- Scale effects: 1M tables over thousands of ranges or tablets, leaseholder rebalancing, sustained 20k loads/s on dedicated hardware (laptop CPU is shared by all nodes and the generator), and enterprise storage fsync behaviour.
- Clock behaviour on real hosts (NTP or PTP drift, the 500 ms max-offset crash path, YugabyteDB read restarts under real skew).
- Rolling upgrades under load at scale, Kubernetes operator behaviour, and licence and support terms.

---

## Provenance

- **Read in full or in the relevant sections:**
  - CockroachDB v26.3 docs: transactions, retry reference, common-errors, transaction layer, select-for-update, survival goals, regional/global tables, follower reads, changefeed messages and overview, row-level TTL, collate, expression indexes, backup and PITR, PCR, LDR, upgrade, online schema changes, cockroach-start, Kubernetes, Java app; includes for licensing, limits and RBR; v26.2 generated settings table; versions.csv.
  - YugabyteDB stable docs: isolation, concurrency control, read restart, error codes, retries, best practices, collations, extensions, sharding, synchronous and xCluster replication, follower reads, tablespaces, geo-partitioning, logical-replication CDC, PITR, upgrades, JDBC, FAQ, releases, yb-tserver flags, transactional DDL.
  - TiDB docs: transactions, isolation, pessimistic transactions, config file, error codes, multi-DC, DR, placement rules, follower read, TiCDC overview and Debezium, TTL, collations, BR PITR, TiUP upgrade, MySQL compatibility, release timeline.
  - Spanner Omni docs: release notes, overview, differences, editions, deployment configuration, TrueTime, replicate-data, Debezium, JDBC, backups, upgrade, FAQ, download, client libraries; the GA blog.
  - Managed Spanner: quotas, transactions, reads, TTL, change streams to Kafka.
  - Aurora DSQL: quotas, concurrency control, unsupported features, resiliency, CDC streams.
  - Jepsen: analyses index; CockroachDB 2017, YugabyteDB 1.3.1, TiDB 2.1.7 (YugabyteDB 1.1.9 downloaded, not used).
  - Polaris relational-JDBC doc and the CockroachDB commit; Nessie configuration; Debezium CockroachDB connector README and tags; Zhihu case study; Oracle Raft replication pages.
- **Secondary only:** the Netflix metastore on CockroachDB (substack recap of re:Invent 2023 NFX306).
- **Also read for single facts:** TiDB LICENSE header (Apache 2.0); GitHub API for the Debezium CockroachDB connector tags and creation date, and for Polaris commit 8f9ff62 (date and message).
- **Not verified this session:** Spanner case-insensitive pattern; CockroachDB implicit locking on plain `UPDATE` (I cite only `SELECT FOR UPDATE` queueing); YugabyteDB online-DDL behaviour beyond transactional DDL; netem on WSL2; licence prices for CockroachDB, TiDB and Spanner Omni; DBA expertise availability (no market data read; Paul's CockroachDB experience is the one known factor).

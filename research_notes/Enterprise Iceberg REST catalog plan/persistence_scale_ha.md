# Persistence, concurrency, performance, HA and DR for an enterprise Iceberg REST catalog

Research date: 2026-11-06. Lens: a from-scratch Iceberg REST catalog for a large US regulated financial firm (thousands of tables; Spark, Flink, Trino, Snowflake and PyIceberg clients; multi-region HA). The user leans towards Java and MongoDB and has CockroachDB experience.

**Provenance tags.**
- `[read: URL]`: I fetched the raw source or doc with curl into `C:/Users/paulh/AppData/Local/Temp/claude/d9ac3f89f5ec51f6/2eb8cba6-c503-4baa-9e18-b3df89b0f733/scratchpad/downloads/persistence/` and read or grepped the exact text.
- `[read (WebFetch summary): URL]`: I read the page only through WebFetch's paraphrase, so the numbers are as the summarizer reported them.
- `[inference]`: my reasoning or arithmetic, not a sourced fact.

GitHub `blob/main` links point to the same files I curled from `raw.githubusercontent.com` on 2026-11-06.

**Versions current on 2026-11-06** (from the GitHub releases API):

| Project | Latest release | Date | Other releases |
|---|---|---|---|
| Apache Iceberg | 1.12.0 | 2026-09-30 | 1.11.0 (2026-05-20) |
| Apache Polaris | 1.8.0 | 2026-09-28 | |
| Lakekeeper | v0.15.0 | 2026-11-06 | |
| Nessie | 0.110.0 | 2026-11-06 | |
| Apache Gravitino | v1.3.1 | 2026-09-30 | |
| Unity Catalog OSS | v0.7.0 | 2026-10-13 | |
| PostgreSQL | 18 | | 19 RC1 announced 2026-10-15 |
| CockroachDB docs | v26.3 | | |

PostgreSQL status is from [read: https://www.postgresql.org/docs/current/transaction-iso.html].

---

## 1. Workload shape: read/write ratio, hot tables, contention and client retries, metadata.json growth, large listings

### Takeaway
Each table is its own small, append-heavy compare-and-set (CAS) register. Commit throughput per table is capped at about 1–2 commits/s, by the client's metadata I/O rather than by the catalog. The total commit rate is the sum over tables, probably tens to low hundreds per second at enterprise scale. Reads (loadTable) dominate by an order of magnitude or more, and their cost grows with metadata.json size. On streaming tables, metadata.json size is driven by snapshot retention. A catalog-wide serialization point, such as a single "HEAD" pointer, is the main architectural mistake to avoid.

### Cited Findings

**Iceberg Java client commit retry**

These are the defaults in `TableProperties` [read: https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/TableProperties.java]. They are unchanged in tags 1.10.0, 1.11.0 and 1.12.0 (I curled each tag).

| Property | Default |
|---|---|
| `commit.retry.num-retries` | 4 |
| `commit.retry.min-wait-ms` | 100 |
| `commit.retry.max-wait-ms` | 60,000 (1 minute) |
| `commit.retry.total-timeout-ms` | 1,800,000 (30 minutes) |
| `commit.status-check.num-retries` | 3 |

- `SnapshotProducer.commit()` runs `Tasks.foreach(ops).retry(num-retries).exponentialBackoff(min, max, total, 2.0)` with `.onlyRetryOn(CommitFailedException.class)`. Each attempt re-runs `apply()` against the refreshed base and calls `taskOps.commit(base, updated)` [read: https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/SnapshotProducer.java].
- A `CommitStateUnknownException` is rethrown immediately. It is not retried, and the cleanup path is skipped (`catch (CommitStateUnknownException e) { throw e; }`) [read: same file].
- The REST spec maps the commit (updateTable) responses as follows [read: https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml]:
  - 409 is `CommitFailedException`: "one or more requirements failed. The client may retry."
  - 500, 502 and 504 mean "the commit state is unknown" and are typed as `CommitStateUnknownException`.
  - 503 has its own ServiceUnavailable response.
- **HTTP-layer retry** is separate from the commit retry [read: https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/ExponentialHttpRequestRetryStrategy.java; https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/HTTPClient.java]:
  - 429 is retried for every request.
  - 429, 503, 500, 502, 504 and 408 are retried only for idempotent requests, or for requests that carry an `Idempotency-Key` header.
  - `rest.client.max-retries` defaults to 5.
- **Idempotency-Key** is part of the spec in Iceberg 1.11.0 and later.
  - The key is a UUIDv7. The server replays the finalized result for 200/201/204 and deterministic 4xx (including 409) responses, and does not finalize 5xx responses [read: rest-catalog-open-api.yaml].
  - The Java client sends the key on mutation endpoints only when the server advertises `idempotency-key-lifetime` in `/v1/config` (`RESTSessionCatalog`: "Enable Idempotency-Key header for mutation endpoints if the server advertises support") [read: https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/RESTSessionCatalog.java].

**Streaming writer cadence**
- Kafka Connect sink: `iceberg.control.commit.interval-ms` defaults to 300,000 (5 minutes), and `iceberg.control.commit.timeout-ms` defaults to 30,000 [read: https://github.com/apache/iceberg/blob/main/kafka-connect/kafka-connect/src/main/java/org/apache/iceberg/connect/IcebergSinkConfig.java].
- Flink sink: "Iceberg commit happened after successful Flink checkpoint in the `notifyCheckpointComplete` callback", so the commit cadence equals the checkpoint interval [read: https://github.com/apache/iceberg/blob/main/docs/docs/flink-writes.md].

**Per-table ceiling**

Chris Douglas's discrete-event simulation (2026-03-09, corrected 2026-06-09) [read (WebFetch summary): https://cdouglas.github.io/posts/2026/03/catalog]:
- "Sustained commit rates above 1-2 commits/sec are unattainable without making some transactions uncommittable."
- Each attempt costs about 5 S3 round-trips, roughly 175 ms at S3 median latencies (GET 27 ms, PUT 60 ms).
- "Single-table commit throughput is limited by the cost of preparing retries, not by the catalog."
- Catalog CAS latency of up to 120 ms adds only modest overhead.

**metadata.json growth and load latency**
- The ClickHouse measurement (issue opened 2026-10-31) found each snapshot costs about 535 bytes in `LoadTableResult.metadata`, and each snapshot-log entry about 64 bytes [read: https://github.com/ClickHouse/ClickHouse/issues/128348]. Full loads at 10,000 snapshots were:
  - 5.34–6.02 MB per response.
  - Query latency of 394 ms (Polaris 1.8.0), 512 ms (Lakekeeper) and 356 ms (the Iceberg REST fixture). 10-snapshot tables took about 16–20 ms.
- Iceberg docs [read: https://github.com/apache/iceberg/blob/main/docs/docs/maintenance.md]:
  - "Each change to a table produces a new metadata file". "Tables with frequent commits, like those written by streaming jobs, may need to regularly clean metadata files."
  - Regular snapshot expiration keeps "the size of table metadata small".
- The defaults that govern growth are below [read: TableProperties.java]:

| Property | Default |
|---|---|
| `history.expire.max-snapshot-age-ms` | 5 days |
| `history.expire.min-snapshots-to-keep` | 1 |
| `write.metadata.previous-versions-max` | 100 |
| `write.metadata.delete-after-commit.enabled` | false |
| `gc.enabled` | true |

**Listing at scale**
- The spec supports `pageToken` and `pageSize` on list endpoints ("For servers that support pagination…"). The Java client property is `rest-page-size` [read: rest-catalog-open-api.yaml; https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/RESTCatalogProperties.java].
- Polaris's NoSQL design note: "The state of a data-lakehouse catalog can contain many thousand, potentially a few 100,000, tables/views/namespaces." Its embedded index spills to separate rows when it nears the 350 kB limit [read: https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md].
- Managed-service ceilings:
  - Glue: 200,000 tables per database, 1,000,000 tables per account, and 100,000 table versions per table (all adjustable) [read: https://docs.aws.amazon.com/general/latest/gr/glue.html].
  - S3 Tables: 10,000 tables per table bucket, 10,000 namespaces per bucket, and 100 table buckets per Region [read: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-regions-quotas.html].

### Inferences
- **Retry budget.** With the defaults, a conflicting writer makes 5 attempts. The backoff sleeps total about 100+200+400+800 ≈ 1.5 s, plus the per-attempt I/O. Jitter was not checked. So a hot table with more than 2–3 concurrent committers starts surfacing `CommitFailedException` to jobs. The catalog cannot fix this. It can only avoid adding latency and avoid spurious 409s. [inference]
- **A 5xx on a commit hurts the client.** The client neither cleans up nor retries; the job simply fails. The server should therefore resolve uncertain DB outcomes itself, by re-reading the pointer. It should return 5xx on commit only when it truly cannot know the outcome, and it should advertise `idempotency-key-lifetime` so that HTTP-level retries are safe. [inference]
- **Metadata growth arithmetic** (≈535 B per snapshot plus 64 B per snapshot-log entry, with the default 5-day retention). [inference]

  | Commit interval | Snapshots retained | metadata.json size | Rewrite cost |
  |---|---|---|---|
  | 10 s (aggressive Flink checkpoint) | 43,200 | ≈26 MB | ≈2.6 MB/s of metadata writes per table |
  | 60 s | 7,200 | ≈4.3 MB | |
  | 5 min (Kafka Connect default) | 1,440 | ≈0.9 MB | |

  At these sizes, loadTable of a large streaming table moves from about 20 ms to hundreds of ms (see the ClickHouse data). The catalog therefore needs short snapshot retention on streaming tables, enforced by policy, and `snapshots=refs` support.
- **Aggregate load estimate.** For example, 1,000 tables at 60 s plus 300 tables at 10 s plus 5,000 hourly batch tables gives about 17 + 30 + 1.4 ≈ 50 commits/s. Reads are likely 10–100× that, before 304 caching. Every engine query plan calls loadTable once per referenced table. None of this is large for any candidate database. Correctness and failover behaviour, not throughput, decide the store. [inference]

### Gaps
- No primary source gives a measured loadTable:commit ratio for a production catalog. The 10–100× figure is my estimate.
- I did not verify whether `Tasks.exponentialBackoff` adds jitter.
- An Iceberg Summit 2026 recap on single-file commits and an optional metadata.json (v4 proposals) surfaced only as search snippets. I did not read the recap, so it is not used here.

---

## 2. Atomic CAS commits and multi-table transactions on candidate databases

### Takeaway
Every candidate can implement a correct per-table CAS. They differ on three things:
1. Whether an acknowledged commit can be lost on failover, which is the RPO.
2. Whether writes survive a region loss without an operator, which is RTO and automation.
3. How multi-table atomic commits behave under contention.

MongoDB with `w:majority` and journaling, and CockroachDB with REGION survival, both give RPO 0 for acknowledged commits with automatic failover. PostgreSQL gives the simplest semantics but needs synchronous cross-region replication, or must accept seconds of RPO (Aurora Global). DynamoDB global tables in the default MREC mode are last-writer-wins, which is unsafe for CAS across regions; only MRSC is safe.

### Cited Findings

**MongoDB (current manual; MongoDB 8.x behaviours noted)**
- **Single-document atomic CAS.** "write operations are atomic on the single-document level… To prevent conflicts during concurrent updates, include the expected current value in the update filter." The docs warn that filtering on a field you do not update causes lost updates [read: https://github.com/mongodb/docs/blob/main/content/manual/manual/source/core/write-operations-atomicity.txt].
- **Write concern.**
  - "Starting in MongoDB 5.0, `{ w: "majority" }` is the default write concern for *most* MongoDB deployments" [read: https://github.com/mongodb/docs/blob/main/content/manual/manual/source/core/replica-set-rollbacks.txt].
  - "Starting in MongoDB 8.0, `{ w: "majority" }` writes return an acknowledgment after a majority of data-bearing members durably write the oplog entry. Members then asynchronously apply the changes". As a result, "Queries on secondaries immediately after a `{ w: "majority" }` write acknowledgment may read from the collection before the secondary" has applied it [read: https://github.com/mongodb/docs/blob/main/content/manual/manual/source/reference/write-concern.txt].
- **Rollback.**
  - `{w:1}` writes, "includ[ing] data written in multi-document transactions that commit using `{ w: 1 }`", can be rolled back if the primary steps down before replication.
  - "To prevent rollbacks of data that have been acknowledged to the client, run all voting members with journaling enabled and use `{ w: "majority" }`".
  - "A rollback does *not* occur if the write operations replicate to another member… before the primary steps down *and* if that member remains available and accessible to a majority". Rolled-back data is written to BSON rollback files [read: replica-set-rollbacks.txt].
- **Linearizable reads.**
  - "reflects all successful majority-acknowledged writes that completed prior to the start of the read". Primary only. "may be significantly slower than reads with `majority` or `local`". "Always use `maxTimeMS`". Unavailable with causally consistent sessions.
  - Combined with majority writes, it gives a linearizable schedule on a single document [read: https://github.com/mongodb/docs/blob/main/content/manual/manual/source/reference/read-concern-linearizable.txt].
- **Causal consistency.** Majority reads and majority writes in a client session have a causal relationship [read: https://github.com/mongodb/docs/blob/main/content/manual/manual/source/core/read-isolation-consistency-recency.txt].
- **Retryable writes.**
  - Drivers "retry specific write operations once after network errors or if they cannot find a healthy primary". `w:0` is not retryable.
  - Transaction commit and abort are retried once even if `retryWrites=false`. "Writes inside a transaction are not individually retryable" [read: https://github.com/mongodb/docs/blob/main/content/manual/manual/source/core/retryable-writes.txt].
- **Multi-document transaction limits** [read: https://github.com/mongodb/docs/blob/main/content/manual/manual/source/core/transactions-production-consideration.txt]:
  - Requires a replica set; standalone deployments are not supported.
  - Default runtime is under 1 minute (`transactionLifetimeLimitSeconds`).
  - Each oplog entry must stay within the 16 MB BSON limit.
  - Transactions wait **5 ms** by default for locks, then abort (`maxTransactionLockRequestTimeoutMillis`, which Atlas restricts changing).
  - Pending DDL on a collection blocks new transactions on it.
  - Sharded clusters with arbiters have restrictions.
- **Write conflicts.** "If a transaction is in progress and a write outside the transaction modifies a document that an operation in the transaction later tries to modify, the transaction aborts because of a write conflict." A non-transactional write to a document the transaction has locked "waits until the transaction ends" [read: https://github.com/mongodb/docs/blob/main/content/manual/manual/source/includes/extracts-transactions.yaml].
- **Application retry labels.** Applications handle `TransientTransactionError` by retrying the whole transaction, and `UnknownTransactionCommitResult` by retrying the commit [read: https://github.com/mongodb/docs/blob/main/content/manual/manual/source/core/transactions-in-applications.txt].
- **Geography.** "use an odd number of data centers… If your deployment only runs in two data centers, a single data center outage can prevent your deployment from handling writes" [read: https://github.com/mongodb/docs/blob/main/content/manual/manual/source/core/replica-set-architecture-geographically-distributed.txt].
- **Atlas.**
  - Built-in custom write concerns `twoRegions` (`{region: 2}`) and `threeRegions` [read: https://github.com/mongodb/docs/blob/main/content/atlas/source/reference/replica-set-tags.txt].
  - Atlas Global Clusters support "up to nine distinct zones", each with one "Highest Priority" region. "Clients can issue write operations only to the primary" of the zone's shard [read: https://github.com/mongodb/docs/blob/main/content/atlas/source/global-clusters.txt].

**PostgreSQL 18** [read: https://www.postgresql.org/docs/current/transaction-iso.html]
- **Read Committed UPDATE.** If the target row was concurrently updated, the second updater waits. Then "The search condition of the command (the WHERE clause) is re-evaluated to see if the updated version of the row still matches." So `UPDATE … WHERE metadata_location = $expected` is a correct CAS at the default isolation level.
- **Repeatable Read and Serializable.** These raise `could not serialize access…` (SQLSTATE 40001), and "applications… must be prepared to retry". "A sequential scan will always necessitate a relation-level predicate lock", which raises the failure rate.
- **Aurora Global Database** [read: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-global-database-disaster-recovery.html]:
  - "For an Aurora global database, RPO is typically measured in seconds."
  - Aurora PostgreSQL managed RPO "Blocks the transaction if all secondary DB clusters have RPO lag times that are larger than the RPO."
  - DR options are "Switchover" (planned, no loss) and "Failover (allow data loss)".

**CockroachDB v26.3**
- **REGION survival** [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/multiregion-survival-goals.md]:
  - "write latency will be increased by at least as much as the round-trip time to the nearest region. Read performance will be unaffected."
  - Three regions raise replication from 3 to 5 replicas (2+2+1). At least 3 database regions are required.
- **GLOBAL tables.** Local reads through non-blocking transactions. "Writes incur higher latencies… since they require a 'commit-wait' step." Follower-read latency spikes when inter-node RTT exceeds 150 ms [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/global-tables.md].
- **REGIONAL BY ROW.** A per-row `crdb_region` home region gives low-latency reads and writes in that region [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/regional-tables.md].

**DynamoDB** [read: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html]
- `TransactWriteItems` "groups up to 100 write actions in a single all-or-nothing operation", with an aggregate size of at most 4 MB.
- A `ClientRequestToken` makes the call idempotent, and "is valid for 10 minutes after the request that uses it finishes".

**DynamoDB global tables** [read: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/V2globaltables_HowItWorks.html]
- **MREC** (the default): concurrent cross-region modifications resolve by "latest internal timestamp on a per-item basis… 'last writer wins'".
- **MRSC:**
  - "must be deployed in exactly three Regions" (three replicas, or two plus a witness).
  - "support a Recovery Point Objective (RPO) of zero".
  - "Conditional writes always evaluate the condition expression against the latest version of an item".
  - TTL is not supported.
- Polaris's design note: "DynamoDB has a hard 400kB row size limit" [read: https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md].

**Which store each existing catalog chose**

| Catalog | Supported stores and support level | Source |
|---|---|---|
| **Polaris** | In-memory (test only); PostgreSQL via JDBC ("Ready to use… Recommended for production"); MongoDB NoSQL ("Experimental… Currently in beta"); DynamoDB NoSQL ("Experimental") | [read: https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/metastores/_index.md] |
| **Polaris** (detail) | JDBC "currently supports only PostgreSQL and CockroachDB" (`database-type=cockroachdb`); a separate `dynamodb-atomic` backend is "Work in progress" | [read: https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/metastores/relational-jdbc.md; https://github.com/apache/polaris/blob/main/persistence/dynamodb-atomic/README.md] |
| **Nessie** | RocksDB (production, single node only); BigTable (production); MongoDB `MONGODB2` (production); PostgreSQL `JDBC2` (production); DynamoDB (beta, "Not recommended for use with Nessie Catalog… due to its restrictive row-size limit"); CockroachDB (experimental, "Known to raise user-facing 'write too old' errors under contention"); Cassandra (experimental) | [read: https://github.com/projectnessie/nessie/blob/main/site/in-dev/configuration.md] |
| **Lakekeeper** | "We currently support only Postgres"; separate read and write URLs; CloudNativePG used internally | [read: https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md; https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/production.md] |
| **Gravitino** | Embedded H2 by default ("makes no consistency or durability guarantee"); a production server uses MySQL or PostgreSQL | [read: https://github.com/apache/gravitino/blob/main/docs/gravitino-server-config.md] |
| **Unity Catalog OSS** | The build pulls H2 2.2.224, PostgreSQL JDBC 42.7.14 and Hibernate ORM | [read: https://github.com/unitycatalog/unitycatalog/blob/main/build.sbt] |

**How Polaris commits, by backend**
- **JDBC backend.** It does CAS "on both entity_version and grant_records_version" and throws `RetryOnConcurrencyException` on a mismatch. The code notes behaviour "With SERIALIZABLE isolation" [read: https://github.com/apache/polaris/blob/main/persistence/relational-jdbc/src/main/java/org/apache/polaris/persistence/relational/jdbc/JdbcBasePersistenceImpl.java]. JDBC retries default to 1 attempt, with no retries (`max-retries`, `max-duration-in-ms=5000`, `initial-delay-in-ms=100`) [read: relational-jdbc.md].
- **NoSQL rationale.** "all databases targeted… support 'compare and swap' operations on a single row. These CAS operations are the only requirement". It uses a 350 kB hard object limit.
- **NoSQL commit model.** "The state of the whole catalog is referenced via the so-called HEAD (think: Git HEAD)… maintained in one named pointer". "The behavior and achievable throughput depend on the database being used; some databases perform _much_ better than others" [read: https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md].

### Comparison for this workload

All cells are drawn from the findings above. Latency and fit judgements are [inference].

| | **MongoDB replica set, 3 regions (2-2-1), w:majority, j:true** | **PostgreSQL (Patroni or Aurora)** | **CockroachDB, 3 regions, REGION survival** | **DynamoDB** | **Spanner / FoundationDB** |
|---|---|---|---|---|---|
| Single-table CAS | `findOneAndUpdate({_id, metadataLocation: expected}, {$set…})`; null result means 409 | `UPDATE … WHERE id=$1 AND metadata_location=$2` at Read Committed (WHERE re-evaluated); 0 rows means 409 | Same SQL; serializable by default; 40001 retries | `PutItem`/`UpdateItem` with `ConditionExpression` | Native transactions (not read this session) |
| Multi-table atomic commit | Multi-doc transaction: under 60 s, 5 ms lock wait, write conflict aborts (TransientTransactionError) | Native; order the updates by table id to avoid deadlocks | Native serializable; contention gives "write too old"/40001 (Nessie's warning) | `TransactWriteItems`: 100 items, 4 MB, 10-minute token | Native |
| Acknowledged-commit loss on failover | None with majority plus journaling | Async replica: possible. Aurora Global: "seconds" unless commits are blocked by managed RPO | None (Raft quorum across regions) | MREC: last-writer-wins can silently drop a CAS winner. MRSC: RPO 0 | Spanner: none [inference] |
| Region-loss write availability | Automatic election if the 3rd region holds a tie-breaker; two-region setups lose writes | Manual or Patroni promotion; Aurora managed switchover or failover | Automatic | MRSC automatic; MREC always writable (but LWW) | Automatic [inference] |
| Commit latency add | ≥ RTT to the nearest majority region [inference] | Intra-region unless replication is synchronous | ≥ RTT to the nearest region (documented) | MRSC: higher write latency (documented) | Similar quorum cost [inference] |
| Size limits | 16 MB document | Effectively unbounded (TOAST) [inference] | Large-row guidance not read | 400 KB item | n/a |
| Used by | Nessie (production), Polaris NoSQL (beta) | Polaris (recommended), Lakekeeper (only), Gravitino, Unity OSS | Polaris JDBC (supported), Nessie (experimental) | Polaris (experimental or WIP), Nessie (beta) | none found |
| On-prem possible | Yes (Enterprise Advanced) or Atlas | Yes | Yes (self-hosted) or Cloud | AWS only | Spanner GCP only; FoundationDB yes |

### Inferences
- **The single named pointer is the wrong shape for this workload.** Polaris NoSQL and Nessie both serialize every commit in a catalog through one HEAD pointer. That turns independent per-table commits into one contended register, which explains both Nessie's CockroachDB "write too old" warning and Polaris's caveat that "some databases perform much better". A Fidelity catalog should keep **one pointer document or row per table**, so that contention is per table, and use a multi-document transaction only for `/transactions/commit` and namespace-level operations. [inference]
- **MongoDB per-table CAS is the textbook case.** A single-document `findOneAndUpdate` with the expected `metadataLocation` (and a monotonic `version`) in the filter is atomic. It is retryable on network errors, so the driver retries once and the server stays exactly-once. With `w:majority`/`j:true`, an acknowledged commit cannot be rolled back on failover. [inference from the cited docs]
- **Multi-table commits on MongoDB.** Use a transaction with `readConcern: snapshot` and `writeConcern: majority`. Put every table's conditional update inside it, and abort with 409 if any matched count is 0. Treat `TransientTransactionError` (write conflict with a concurrent single-table commit) as a 409, not a server-side spin, because the client already retries 409s. Keep transactions tiny, because of the 5 ms lock wait. [inference]
- **Idempotency on MongoDB without a transaction.** Embed the last N idempotency keys with their outcomes in the table document (`$push` with `$slice`) within the same `findOneAndUpdate`. A replayed key is then answered from the document atomically. Separately, keep a TTL collection for non-table mutations. [inference]
- **Reads.** CAS makes read staleness a performance issue, not a correctness one: a stale base yields a 409. But engines expect read-after-write across different clients (Spark commits, then Trino reads). Since MongoDB 8.0 acknowledges majority writes before secondaries apply them, **serve loadTable pointer reads from the primary with `readConcern: majority`**. Do not use linearizable reads; they are too slow and unnecessary given CAS. Do not use secondaries either. [inference]
- **Do not shard and do not use Atlas Global Clusters for the catalog.** 100k tables × about 2 KB of pointer documents is a few hundred MB. Zones would turn multi-table transactions into distributed transactions and give each table a single writable zone anyway. Use them only if data-residency rules force per-jurisdiction catalogs. [inference]
- **PostgreSQL's weakness here is multi-region RPO and failover automation, not CAS.** If Fidelity's DR standard requires RPO 0 for the catalog across regions, PostgreSQL needs synchronous replication to another region. That carries a latency cost similar to MongoDB or CockroachDB, plus Patroni with a 3-site DCS. Otherwise Aurora Global DB's "seconds" RPO applies. [inference]
- **DynamoDB MREC is disqualified for active-active writes.** Two regions can each accept a conditional write against their local item version, and LWW then keeps only one. The other client got a 200, so its committed snapshot silently vanishes. MRSC fixes this, but it is AWS-only and the region set is fixed at three. [inference]

### Gaps
- I did not read Spanner or FoundationDB docs this session, so their table cells are background knowledge only.
- I did not read Patroni docs (synchronous mode or failover data-loss semantics).
- I did not read the MongoDB 8.x release notes for cross-region majority latency numbers.
- I did not find whether DynamoDB MRSC supports `TransactWriteItems`.
- Glue's backing store is not public.
- The Gravitino and Unity Catalog CAS implementations were not read.

---

## 3. Where metadata should live: object storage only vs also in the DB

### Takeaway
The portable source of truth must remain `metadata.json` in object storage, written before the pointer CAS. Every engine and every DR or migration tool depends on it. Leading implementations also keep a parsed or normalized copy in the database: Lakekeeper normalizes, Polaris NoSQL memoizes, and Nessie Catalog serves from its version store. This makes loadTable, `snapshots=refs` and ETags cheap. Store a DB copy as a cache and accelerator, never as the only copy.

### Cited Findings
- **Lakekeeper stores table metadata normalized in Postgres.** Its 2024-11-06 migration `split_table_metadata` creates the tables `table_schema`, `table_current_schema`, `table_partition_spec`, `table_properties`, `table_snapshot`, `table_snapshot_log`, `table_metadata_log`, `table_sort_order` and `table_refs`. It also adds a trigger that forbids writing the old `metadata` blob column ("metadata must be null") [read: https://github.com/lakekeeper/lakekeeper/blob/main/crates/lakekeeper-storage-postgres/migrations/20241106201139_split_table_metadata.sql].
- **Lakekeeper still writes metadata files**, honours `write.metadata.previous-versions-max`, and has enabled `delete-after-commit` by default since v0.10.0 [read: https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/table-maintenance.md].
- **Polaris NoSQL memoizes table metadata in the DB.** "Purging unused table/view metadata memoized in the database is one piece" of cleanup. Large objects can span multiple rows, but only for eventually consistent writes. Strongly consistent writes must fit in 350 kB [read: https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md].
- **Polaris JDBC** stores entities with `properties` and `internal_properties` JSONB columns and an `entity_version` [read: https://github.com/apache/polaris/blob/main/persistence/relational-jdbc/src/main/resources/postgres/schema.sql].
- **Nessie** "keeps only the current snapshot" and ignores `snapshots=refs`. Its loadTable at 10,000 snapshots took about 10 ms, versus 356–512 ms for full-history catalogs [read: https://github.com/ClickHouse/ClickHouse/issues/128348].
- **A DB backup alone is not a full backup.** Lakekeeper: "a database backup restores the catalog, not the data… back up the table files in storage" [read: https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md].

### Inferences
- **Recommended layout** [inference]:
  - **Write path.** Write the new `metadata.json` to the table's storage first. Then CAS the pointer document `{metadataLocation, version, lastUpdatedMs, etag}`. In the same atomic write, store a **compact current-state projection**: schema id, current snapshot id, refs, last-sequence-number and properties. Also optionally store the gzip'd current metadata JSON when it is under about 1 MB. This keeps clear of the 16 MB BSON limit and keeps oplog and replication traffic sane.
  - **Large tables.** Stream full metadata from object storage through an immutable, location-keyed server cache.
- **Trade-offs** [inference]:
  - Latency: DB-served loadTable avoids an S3 GET of 20–500 ms on cache misses.
  - Consistency: the projection is updated atomically with the pointer, so it cannot disagree.
  - DR: the DB copy replicates synchronously with the pointer, so the DR region can always describe the table. The manifests and data files still replicate asynchronously (see section 5).
- **Do not make the DB the only copy.** That would break `registerTable`, engine-side "read metadata at location" paths, and exit or migration to another catalog. It would also make the DB backup the only backup of table history. [inference]

### Gaps
- I did not read Polaris's IcebergCatalog code to confirm whether 1.8.0 serves loadTable from DB-memoized metadata or from object storage. A `METADATA_CACHE` key was not present in `FeatureConfiguration.java` or `BehaviorChangeConfiguration.java` on main.

---

## 4. Caching: ETag and If-None-Match, snapshots=refs, server caches, cross-node invalidation

### Takeaway
Iceberg 1.11+ clients already do conditional loadTable with ETags and keep a small client-side table cache. A catalog that issues good ETags and answers 304 removes most read load. `snapshots=refs` cuts response size by up to about 500× on long-history tables. On the server, cache parsed metadata by `metadata-location`, which is immutable and so needs no invalidation. Read the mutable pointer from the DB, or cache it with explicit invalidation.

### Cited Findings
- **Spec, If-None-Match.** loadTable accepts `If-None-Match` and returns 304 "if the metadata is current". The value is "the ETag received in a CreateTableResponse, LoadTableResponse or CommitTableResponse" [read: https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml].
- **Spec, ETag distinctness.** "Implementations that support ETags should produce unique tags for responses that return different metadata content… the `snapshots` query parameter… should have distinct ETags" [read: same].
- **Spec, snapshots parameter.** `snapshots` is `all` (the default) or `refs`, which returns only snapshots referenced by branches or tags [read: same].
- **Java client table cache.** `RESTSessionCatalog` keeps a `RESTTableCache` of `TableWithETag` entries, sends `If-None-Match`, and stores the response ETag. Properties: `rest-table-cache.expire-after-write-ms` (default 5 minutes) and `rest-table-cache.max-entries` (default 100). `snapshot-loading-mode` defaults to `ALL` [read: https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/RESTSessionCatalog.java; https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/RESTCatalogProperties.java]. `RESTTableCache.java` exists at tag 1.11.0 and not at 1.10.0 (HTTP 200 vs 404 check).
- **Lakekeeper's 304 behaviour.** "Clients such as Iceberg Java 1.11 and newer can ask whether a table changed… Lakekeeper answers `304 Not Modified`". When the earlier response carried vended credentials, "Lakekeeper stops answering `304` well before they expire" [read: https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md].
- **ClickHouse measurements with `snapshots=refs`** [read: https://github.com/ClickHouse/ClickHouse/issues/128348]:
  - Payload fell from 5.34–5.63 MB to 10–12 KB at 10,000 snapshots.
  - Per-query latency fell from 512 to 178 ms (Lakekeeper) and from 394 to 238 ms (Polaris 1.8.0).
  - The snapshot log stays in `refs` responses and still grows at about 64 B per entry.
- **Catalog bugs with refs.**
  - Polaris 1.8.0 and the Iceberg fixture return HTTP 400 for `refs` when statistics files sit on unreferenced snapshots (apache/polaris#5810, apache/iceberg#17538).
  - Polaris 1.6.0 omitted `metadata-location`.
  - None of the tested catalogs advertises `snapshot-loading-mode` in `/v1/config`.
  - Trino writes a statistics file on every insert by default, so most Trino-written tables hit the 400 path [read: same issue].
- **Lakekeeper cross-node invalidation** [read: https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/configuration.md; https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/production.md]:
  - Every cached-entry write also writes a change record in the same transaction.
  - Each instance polls change records from the primary every 1 s (`POLL_INTERVAL`), and evicts again after 10 s (`REEVICT_GRACE`) to cover replica lag.
  - After a DB failover, an instance that cannot know what it missed "empties all the caches".
  - Read replicas must stay under the grace period behind the primary.
- **Polaris NoSQL objects are immutable.** "Since most `Obj`s are by default assumed to be immutable, caching is very straight forward and does not require any coordination". Its commit log can be polled for changes since a commit ID [read: https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md].

### Inferences
- **ETag design** [inference]:
  - Compute `ETag = H(metadataLocation, snapshotsMode, referencedBy-scope, credential-epoch)`.
  - Include a credential epoch, or stop issuing 304s near credential expiry as Lakekeeper does. A 304 carries no new vended credentials.
  - Answer 304 by comparing against the pointer document only. This needs one indexed primary read and no metadata read.
- **Support `snapshots=refs` correctly from day one,** including tables with statistics on unreferenced snapshots, which Polaris gets wrong. Also advertise `snapshot-loading-mode` in `/v1/config`, since ClickHouse notes that only Databricks does. [inference]
- **Server caches** [inference]:
  - L1: a parsed `TableMetadata` cache keyed by `metadata-location`. It is immutable, so it is safe across nodes without invalidation.
  - L2 (optional): a pointer cache with invalidation through MongoDB change streams, or a Lakekeeper-style change-record poll.
  - Given CAS semantics, pointer caching only saves one point read. Skip it at first. Read pointers from the primary on every request, and rely on ETags and 304s to cut payload cost.

### Gaps
- I did not read MongoDB change-stream docs this session (resume-token semantics across failover).
- I did not verify whether Spark, Trino or Snowflake clients send `If-None-Match`. Only the Iceberg Java core client was verified.

---

## 5. Multi-region HA and DR: topology, RPO and RTO, the pointer-ahead-of-files hazard, backup and PITR consistency, runbooks

### Takeaway
A table's correctness needs exactly one serialization point for its pointer. Active-active is therefore achievable only as stateless catalog nodes in every region over one strongly consistent database (MongoDB 3-region replica set, CockroachDB REGION survival, DynamoDB MRSC), not as two independent databases. The larger hazard is the object store. S3 Cross-Region Replication is asynchronous ("99.9 percent… within 15 minutes" with RTC). A synchronously replicated pointer can therefore reach the DR region before the files it references. The design needs a per-table "replicated-safe" pointer in the DR region, or a post-failover rewind and reconcile procedure. Retention settings must keep files alive for at least the DB point-in-time-recovery (PITR) window.

### Cited Findings
- **S3 RTC.** "S3 RTC replicates most objects that you upload to Amazon S3 in seconds, and 99.9 percent of those objects within 15 minutes". It provides replication metrics, including operations and bytes pending replication [read: https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication-time-control.html].
- **MongoDB.** Majority plus journaling prevents rollback of acknowledged writes. A two-DC deployment can lose write availability. Use an odd number of sites [read: replica-set-rollbacks.txt; replica-set-architecture-geographically-distributed.txt].
- **CockroachDB.** REGION survival keeps the database "fully available for reads and writes, even if an entire region becomes unavailable" [read: https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/multiregion-survival-goals.md].
- **Aurora Global.** RPO is "typically measured in seconds". Failover offers "allow data loss", while switchover is planned with no loss [read: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-global-database-disaster-recovery.html].
- **DynamoDB.** MREC is last-writer-wins. MRSC offers RPO zero across exactly three regions [read: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/V2globaltables_HowItWorks.html].
- **Iceberg metadata-log.** "Each metadata file tracks the older metadata files in the `metadata-log` field". With `delete-after-commit` enabled, the oldest tracked file is deleted on each commit beyond `previous-versions-max` [read: https://github.com/apache/iceberg/blob/main/docs/docs/maintenance.md].
- **Lakekeeper on backups and file access.** It recommends regular, tested DB backups. It warns that writers holding vended credentials "can overwrite or delete any file of the table, including the files of older versions". It recommends bucket versioning or soft delete [read: https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md; https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/production.md].
- **Cache reset on failover.** Lakekeeper empties caches after a DB failover [read: https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/configuration.md].

### Inferences

**Topology** [inference]
- Stateless catalog API nodes run in all regions behind GSLB.
- One logical DB spans 3 regions. Two of them are data-bearing (2+2), and the third holds 1 voting member, which can also be a smaller region.
- Each table has a **home region**: its bucket and its writers. Writers in other regions pay cross-region latency, or are disallowed.
- On CockroachDB, REGIONAL BY ROW can pin each table's pointer row to its home region for local-latency CAS while still surviving region loss. MongoDB's equivalent would be Global Cluster zones (not recommended, see section 2).

**The pointer-ahead-of-files hazard and designs that handle it** [inference]
1. **Replication watermark (recommended).**
   - The catalog keeps a per-table commit journal: commit time, the new `metadata.json` path, the manifest list, and the newly added manifests.
   - A DR-side "replication verifier" advances `drSafeMetadataLocation` only after it confirms the replicated copies. It can confirm with HEAD requests, S3 Replication events, or inventory. Data files can be checked from the manifests, or bounded by time with RTC's 15-minute SLA plus margin.
   - DR-region readers are served `drSafeMetadataLocation`. Failover promotes it.
   - RPO equals the replication lag (seconds, up to 15 minutes at p99.9). The DR view is always self-consistent.
2. **Rewind on failover (minimum).**
   - At promotion, for each table committed within the last N minutes, walk back through `metadata-log` to the newest metadata file whose full closure exists in the DR bucket.
   - CAS the pointer to it, and record the "lost tail" for business reconciliation.
   - Freeze orphan-file removal until reconciliation is complete.
3. **Fast-forward after a lossy DB failover.** This applies to PostgreSQL async replication, Aurora "allow data loss" and DynamoDB MREC.
   - The pointer can regress while newer `metadata.json` files exist in storage. If orphan removal runs, it will **permanently delete the "lost" commits' files**.
   - Runbook: suspend all GC, list metadata directories for files newer than the pointer, verify that their `metadata-log` contains the current pointer (lineage), CAS forward, then resume GC.
   - This risk is a strong argument for an RPO-0 database.

**Backup and PITR consistency** [inference]
- A DB PITR to time T is only useful if every file referenced by any pointer at T still exists. Concretely:
  - `history.expire.max-snapshot-age-ms` must be at least the PITR window.
  - The orphan-removal `older-than` must be at least the PITR window plus the replication window.
  - Bucket versioning must be on, with noncurrent-version retention of at least the PITR window. Object Lock should be considered for regulated tables.
  - Metadata `delete-after-commit` must keep enough history: `previous-versions-max` multiplied by the commit interval should be at least the PITR window, or metadata deletion should be deferred to GC.
- Back up the DB, which holds pointers, grants, policies and the commit journal. Treat the bucket, with versioning and replication, as the data backup. Restore drills must validate the closure of a sample of tables.

**Region-failover runbook outline** [inference]
1. Declare the event, and fence the old region by revoking the old region's write credentials and DNS.
2. DB: confirm the new primary or leaseholders (automatic on MongoDB and CockroachDB), and confirm no rollback files contain catalog writes.
3. Suspend all maintenance and GC.
4. Run the watermark promotion or rewind (design 1 or 2).
5. Flush server caches.
6. Re-vend credentials scoped to DR buckets. Table `location` rewriting is needed if bucket names differ by region; this argues for multi-region access points or location aliasing, which I did not research.
7. Resume writers.
8. Reconcile the lost tail.
9. Resume GC.

### Gaps
- I did not read S3 Multi-Region Access Points or S3 Replication event docs. I did not check whether CRR preserves write ordering between a manifest and a metadata file. The design above assumes it does not.
- On-premise object stores (for example ECS, MinIO, NetApp StorageGRID) were not researched. Fidelity may use one.
- No published end-to-end DR runbook from any OSS catalog was found.

---

## 6. Published performance numbers

### Takeaway
There are very few rigorous public numbers. The useful ones:
- Polaris loadTable median 7 ms / p99 12 ms, and createTable 136 req/s at concurrency 40 (Dremio, July 2025; backend not stated).
- loadTable cost grows from about 20 ms to about 400–500 ms between 10 and 10,000 snapshots (ClickHouse, October 2026).
- A per-table commit ceiling of about 1–2 commits/s from client-side I/O (simulation).
- Glue and S3 Tables table-count quotas.

I found no Netflix, Apple or LinkedIn catalog throughput numbers.

### Cited Findings
- **Polaris benchmark** (Pierre Laporte, Dremio, 2025-07-01) [read (WebFetch summary): https://www.dremio.com/blog/benchmarking-framework-for-the-apache-iceberg-catalog-polaris/]:
  - Dataset: 16,383 namespaces, 24,576 tables and 16,384 views.
  - Create Table: 135.78 req/s at concurrency 40, median 89 ms, p99 154 ms.
  - 90/10 read/write mix at 150 ops/s: Fetch Table median 7 ms, p99 12 ms.
  - The hardware and persistence backend are not stated.
- **polaris-tools Gatling simulations:** `CreateTreeDataset`, `ReadTreeDataset`, `ReadUpdateTreeDataset`, `CreateCommits` and `WeightedWorkloadOnTreeDataset` ("testing performance when clients conflict"). The README publishes no result numbers [read: https://github.com/apache/polaris-tools/blob/main/benchmarks/README.md].
- **ClickHouse loadTable plus query latency** (4-core Graviton, catalogs on localhost):
  - 10-snapshot tables: 16 ms (fixture), 19.5 ms (Lakekeeper), 19 ms (Polaris).
  - 10,000 snapshots: 356 ms (fixture), 512 ms (Lakekeeper), 394–460 ms (Polaris 1.8.0).
  - Throughput rose about 1.7× with `refs` [read: https://github.com/ClickHouse/ClickHouse/issues/128348].
- **catalog-bench** (querygraph, sweep of 2026-08-27). This is **vendor-affiliated**: the same organization builds the top-ranked LakeCat, and the repo had 1 GitHub star.
  - Method: eight writers issuing `set-properties` commits to a shared table.
  - Results: LakeCat 147.5 accepted commits/s, Polaris 58.1/s, Gravitino 56.8/s.
  - Lakekeeper 0.13.3 "returned PostgreSQL deadlock-backed HTTP 503s in all six" rounds.
  - Nessie 0.108.4 produced HTTP 500s and was unranked [read: https://github.com/querygraph/catalog-bench].
- **Per-table commit ceiling:** about 1–2 commits/s sustainable, with about 175 ms of S3 I/O per attempt [read (WebFetch summary): https://cdouglas.github.io/posts/2026/03/catalog].
- **Managed-service quotas:**
  - Glue: 200,000 tables per database, 1,000,000 per account, and 100,000 versions per table [read: https://docs.aws.amazon.com/general/latest/gr/glue.html].
  - S3 Tables: 10,000 tables per bucket, 10,000 namespaces per bucket, and 100 buckets per Region (adjustable through Support) [read: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-regions-quotas.html].
- **Polaris NoSQL ID space:** Snowflake IDs allow "1024 concurrently active 'JVMs running Polaris'" and 4,096 IDs per ms per node [read: https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md].

### Inferences
- **No public number shows a catalog database to be the bottleneck at enterprise scale.** The observed limits are client-side metadata I/O per table, metadata.json size on load, and implementation bugs: deadlocks, 5xx under concurrency, and `refs` handling. Fidelity's acceptance tests should target those. [inference]
- **Proposed SLOs to validate in a proof of concept** (not published numbers) [inference]:
  - loadTable p99 under 50 ms for tables under 1 MB of metadata, and 304 p99 under 10 ms.
  - Commit p99 under 300 ms, excluding client I/O.
  - Zero 5xx at 8–32 concurrent writers on one table.
  - At least 500 commits/s aggregate across tables.

### Gaps
- Netflix, Apple and LinkedIn: a search returned nothing citable on catalog commits/s or table counts.
- Search snippets mentioned a LanceDB metadata benchmark (Polaris commit latency of about 372–583 ms), an Adobe document (15 commits/min per dataset), and a GitHub issue claiming Polaris serves "6,000–8,000 TPS". **I did not read these, so they are not used.**
- Lakekeeper publishes no benchmark in its docs (production.md and concepts.md checked).

---

## 7. Table maintenance at scale: who owns compaction, expiration, orphan removal and manifest rewrite; drop and purge; soft delete

### Takeaway
Maintenance falls into three models:
- **Catalog-managed with a built-in executor:** S3 Tables (on by default), and Lakekeeper Plus, which is commercial.
- **Catalog stores the policy, external engines execute it:** Polaris policies.
- **Pure external jobs:** Spark or Flink actions.

For a regulated enterprise with thousands of tables, the catalog should own **policy, scheduling, soft-delete and purge**, because only it knows commit cadence and drop state. Execution should run in a separate, horizontally scaled worker tier that commits through the catalog like any writer.

### Cited Findings
- **S3 Tables** [read: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-maintenance.html]:
  - "Compaction is enabled by default for all tables, with a default target file size of 512MB, or a custom value… between 64MB to 512MB". Sort compaction applies when a sort order is defined, binpack otherwise.
  - Snapshot management uses `MaximumSnapshotAge` (120 hours by default).
  - Unreferenced-file removal is offered. "Compaction will incur additional costs."
- **Lakekeeper** [read: https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/table-maintenance.md; https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/optimize.md]:
  - The open core does metadata-file cleanup (`delete-after-commit` default on since v0.10.0).
  - "Lakekeeper Plus" (commercial) adds:
    - Expire snapshots, per warehouse or table, with a 24-hour minimum-age guard against typos.
    - Optimize: compaction to 512 MiB plus manifest rewrite to 8 MiB, scheduled about 1 minute after a commit, at most hourly per table.
    - Orphan removal.
  - Tables with `gc.enabled=false`, and encrypted tables, are skipped by expiration.
- **Lakekeeper drop, purge and soft delete** [read: https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md]:
  - Drop of managed tables defaults to `purgeRequested=true`.
  - Soft deletion lets tables be restored until expiry, with the expiry fixed at drop time.
  - With Iceberg 1.12+, setting `spark.sql.catalog.<name>.rest-catalog-purge=true` makes Spark pass PURGE to the catalog instead of deleting files itself. Without it, Spark deletes the files itself and "undermines soft deletion".
  - Recursive namespace delete needs `force` in soft-delete warehouses.
- **Polaris policies** [read: https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/policy.md; https://github.com/apache/polaris/blob/main/polaris-core/src/main/java/org/apache/polaris/core/config/FeatureConfiguration.java]:
  - Types: `system.data-compaction`, `system.metadata-compaction`, `system.orphan-file-removal` and `system.snapshot-expiry`, inheritable from catalog to namespace to table.
  - "Polaris stores and resolves these policies but does not run the maintenance operations itself."
  - `DROP_WITH_PURGE_ENABLED` defaults to false.
- **Iceberg** [read: https://github.com/apache/iceberg/blob/main/docs/docs/maintenance.md]:
  - Orphaned metadata files "can only be cleaned with an orphan file deletion procedure".
  - With `delete-after-commit=false`, untracked metadata files accumulate.

### Inferences
- **Recommended ownership** [inference]:
  - The catalog owns: maintenance policies (inherited, with typed schemas like Polaris), a scheduler triggered by commits (like Lakekeeper's "about one minute after a burst"), soft-delete and undrop, and purge.
  - A worker tier owns execution. It uses Iceberg core actions for expiration, orphan removal and manifest rewrite, and Spark for heavy compaction, and it commits through the catalog's CAS.
  - The catalog enforces guardrails: minimum ages, the PITR-window coupling from section 5, and suspension during DR events.
- **Streaming tables need policy-enforced short snapshot retention** (hours, not 5 days) plus frequent manifest rewrite. Otherwise the metadata.json sizes in section 1 make loadTable slow and every commit expensive. [inference]
- **Make purge server-side only.**
  - Advertise and require `rest-catalog-purge` for Spark, which needs Iceberg 1.12+.
  - Prefer remote signing or credentials without `s3:DeleteObject` for non-maintenance principals, so clients cannot bypass soft delete.
  - Lakekeeper itself notes that vended write credentials allow deletion. [inference]

### Gaps
- Tabular (now part of Databricks) and Apache Amoro were not researched this session.
- I did not read Polaris maintenance or "metastore-maintenance" modules beyond the directory names.

---

## 8. Java runtime and framework for a locked-down enterprise

### Takeaway
The Java OSS catalogs split as follows: Polaris and Nessie are on Quarkus 3.40.1.1, Gravitino runs Jetty 9.4 plus Jersey 2, and Unity Catalog runs Armeria. Lakekeeper is Rust. JDK 25 (LTS, September 2025) with virtual threads, whose `synchronized` pinning was removed in JDK 24 by JEP 491, makes a blocking, thread-per-request design (sync MongoDB driver, blocking S3 I/O) scale without reactive frameworks. For a curated artifact repository, the deciding factor is which framework Fidelity's platform team already curates and patches, more than raw features.

### Cited Findings
- **Polaris** pins `quarkus = "3.40.1.1"` and `mongodb-driver-sync 5.14.0`. Its MongoDB backend "leverages the Quarkus MongoDB extension" [read: https://github.com/apache/polaris/blob/main/gradle/libs.versions.toml; https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/metastores/nosql-mongodb.md].
- **Nessie** pins `quarkus = "3.40.1.1"` and the `quarkus-bom` [read: https://github.com/projectnessie/nessie/blob/main/gradle/libs.versions.toml].
- **Gravitino** pins `jetty = "9.4.58.v20250814"` and `jersey = "2.41"` [read: https://github.com/apache/gravitino/blob/main/gradle/libs.versions.toml].
- **Unity Catalog OSS** pins `armeriaVersion = "1.41.1"`, with Hibernate ORM and the H2 and PostgreSQL drivers [read: https://github.com/unitycatalog/unitycatalog/blob/main/build.sbt].
- **Lakekeeper** is a Rust Cargo workspace (for example `crates/lakekeeper-storage-postgres/`) [read: repository tree via the GitHub API, https://github.com/lakekeeper/lakekeeper].
- **Latest framework releases** (GitHub releases API, 2026-11-06):

  | Framework | Latest releases | Date |
  |---|---|---|
  | Quarkus | 3.40.1.1 | 2026-10-19 |
  | Spring Boot | v4.1.2 and v4.0.9 | 2026-10-22 |
  | Helidon | 27.0.1 and 4.5.6 | 2026-10-20 |
  | Micronaut core | v5.2.22 | 2026-11-04 |
  | Javalin | 7.2.4 | 2026-10-25 |

  Sources: [read: https://github.com/quarkusio/quarkus/releases; https://github.com/spring-projects/spring-boot/releases; https://github.com/helidon-io/helidon/releases; https://github.com/micronaut-projects/micronaut-core/releases; https://github.com/javalin/javalin/releases]
- **JDK:**
  - JEP 491 "Synchronize Virtual Threads without Pinning" was delivered in JDK 24. It "will eliminate nearly all cases of virtual threads being pinned to platform threads" [read: https://openjdk.org/jeps/491].
  - JDK 25 "will be a long-term support (LTS) release", with General Availability on 2025-09-16 [read: https://openjdk.org/projects/jdk/25/].
  - JDK 27 reached GA on 2026-09-15 [read: https://openjdk.org/projects/jdk/27/].
- **Iceberg core's server-side handlers.** The Iceberg REST test fixture is built on Iceberg's own `CatalogHandlers` [read: https://github.com/ClickHouse/ClickHouse/issues/128348, which names "Iceberg `CatalogHandlers`"].

### Inferences
- **Recommendation: JDK 25 LTS, virtual threads, and a small framework the enterprise already curates.** [inference]
  - Spring Boot 4.x is the safest choice if Fidelity's golden path is Spring. Its transitive set is large but almost certainly already approved.
  - Otherwise prefer a minimal stack: Helidon SE 4.x, or Javalin 7 on Jetty 12. Javalin 7 on Jetty 12 is assumed, not verified.
  - Add Jackson, the MongoDB sync driver, `iceberg-core` (for `TableMetadataParser`, `UpdateRequirement`/`MetadataUpdate` and `CatalogHandlers` semantics), and the cloud SDK.
  - Reusing `iceberg-core`'s metadata-update logic is the biggest correctness lever. It ensures commit validation is identical to the reference implementation.
- **Avoid Quarkus unless the enterprise already curates its extension ecosystem.** Its value (build-time CDI, native image, dev services) matters less for a long-running, multi-instance service, and its BOM adds many artifacts to approve. Polaris's own MongoDB path depends on the Quarkus MongoDB extension. [inference]
- **GraalVM native image is not worth it here.** Startup time is irrelevant for a 24×7 service, and native image complicates reflection-heavy Jackson and Iceberg code and security scanning. [inference]
- **Gravitino's Jetty 9.4 line is a supply-chain flag to raise** with security review. [inference: I did not read Jetty's support-status page]

### Gaps
- I did not read Spring Boot, Helidon, Micronaut or Quarkus docs on virtual-thread configuration this session.
- I did not count transitive dependencies for each stack. A `mvn dependency:tree` comparison is a cheap next step on the work machine's curated repository.
- I did not verify that Helidon's "27.0.1" tag is a GA line, as opposed to a renumbering.

---

## Decisions this implies

### D1. Catalog database: MongoDB vs PostgreSQL vs CockroachDB (vs DynamoDB)

**Options**

| Option | Strengths | Weaknesses |
|---|---|---|
| MongoDB 3-region replica set | RPO 0 for acknowledged commits; automatic election | Multi-document transactions are workable but constrained |
| PostgreSQL | Simplest semantics; most OSS precedent | Cross-region RPO 0 needs synchronous replication plus Patroni, or accepts Aurora's seconds of RPO |
| CockroachDB REGION survival | Native serializable SQL; survives a region; REGIONAL BY ROW home regions | ≥ RTT on writes; "write too old" retries under contention |
| DynamoDB MRSC | RPO 0 | AWS-only; exactly 3 regions; 400 KB items |

**Evidence.** Section 2's cited findings and the comparison table.

**Recommendation: MongoDB, with the right data model.**
- **Data model.** One pointer document per table, CAS through `findOneAndUpdate` with the expected `metadataLocation` and `version`, and `w:majority` plus `j:true`.
- **Reads.** Primary reads with `readConcern: majority`. No secondaries and no sharding.
- **Topology.** A 2-2-1 replica set across three sites.
- **Multi-table commits.** Multi-document transactions only for `/transactions/commit` and namespace operations, mapping write conflicts to 409.

It meets the RPO-0, auto-failover bar, fits the team's Java and MongoDB lean, and has production precedent in Nessie (`MONGODB2`).

**Do not** copy Polaris NoSQL's or Nessie's catalog-wide HEAD-pointer model. **Do not** use w:1, Global Clusters, or reads from secondaries for loadTable.

**CockroachDB is an equally valid choice, and the better one if** Fidelity's DBA organization already operates it, or SQL and serializable multi-table semantics are wanted for audit queries. Use REGIONAL BY ROW with each table's home region, plus a client-side 40001 retry loop. Polaris JDBC already supports it.

**Choose PostgreSQL only if** the DR standard accepts active-passive with RPO measured in seconds (Aurora Global), or the team will run synchronous cross-region replication with Patroni. PostgreSQL is the lowest-risk single-region store.

**Reject** DynamoDB MREC outright: last-writer-wins breaks CAS.

### D2. Where metadata lives
- **Options:** object storage only; object storage plus a DB projection or cache (Lakekeeper, Polaris NoSQL); DB only.
- **Recommendation:** `metadata.json` in object storage is the source of truth, written before the CAS. Store a compact current-state projection atomically with the pointer, plus small compressed metadata under about 1 MB. Serve larger metadata through an immutable, location-keyed cache. Never make the DB the only copy.

### D3. Caching and protocol features to ship in v1
- **Recommendation:**
  - ETags (including mode and credential epoch) with 304s.
  - Correct `snapshots=refs`, including statistics on unreferenced snapshots, advertised in `/v1/config`.
  - Pagination with keyset tokens.
  - Advertise `idempotency-key-lifetime`, store finalized responses, and never finalize 5xx.
  - On an uncertain DB outcome, re-read the pointer before answering, so that `CommitStateUnknown` (5xx) is rare.
- **Evidence:** sections 1 and 4.

### D4. Multi-region topology and DR
- **Options:** active-passive with two databases; active-active stateless API over one 3-region database.
- **Recommendation:** active-active API over one 3-region strongly consistent database. Each table has a home region. Build a **replication-watermark (`drSafeMetadataLocation`) verifier** and a **failover rewind and fast-forward runbook** with a GC freeze. Couple retention settings to the PITR window: snapshot age, orphan `older-than`, bucket noncurrent-version retention, and metadata `previous-versions-max`.
- **Evidence:** section 5. S3 RTC is 99.9% within 15 minutes, so a pointer can outrun its files.

### D5. Maintenance ownership
- **Options:** external jobs only; catalog-stored policy (Polaris); catalog-scheduled with a built-in executor (S3 Tables, Lakekeeper Plus).
- **Recommendation:** the catalog owns policy, scheduling, soft-delete and undrop, and purge. A separate worker pool executes and commits through the catalog. Require `rest-catalog-purge` (Iceberg 1.12+) for Spark. Enforce short retention for streaming tables.
- **Evidence:** section 7.

### D6. Java stack
- **Options:** Quarkus, Spring Boot, Helidon, Micronaut, or a minimal stack (Javalin or Jetty).
- **Recommendation:** JDK 25 LTS with virtual threads and blocking I/O, using the enterprise-curated framework (likely Spring Boot 4.x), or else Helidon SE or Javalin. Embed `iceberg-core` for update and requirement semantics. Skip GraalVM native.
- **Evidence:** section 8.

### D7. Acceptance and benchmark harness
- **Recommendation:**
  - Fork or adapt the polaris-tools Gatling simulations, which are catalog-agnostic REST workloads.
  - Add the ClickHouse-style snapshot-history loadTable test and a single-table 8–32-writer conflict test.
  - Gate on zero 5xx and no deadlocks, which is where Lakekeeper 0.13.3 and Nessie failed in catalog-bench.
- **Evidence:** section 6.

## Open questions for the enterprise
1. **DR standard for this tier.** Is the required RPO for an acknowledged table commit zero, or minutes? What is the RTO? This decides D1 between MongoDB or CockroachDB and PostgreSQL, and whether the replication-watermark design is mandatory.
2. **Database platform reality.** Which databases does Fidelity's DBA organization operate with 3-site topologies today: MongoDB Enterprise or Atlas, CockroachDB, Aurora, or on-prem PostgreSQL with Patroni? Is a third site or region available for a MongoDB voting member or CockroachDB replicas?
3. **Object storage.** Is it AWS S3 (and which regions), or an on-prem S3-compatible store? What is the replication mechanism and its SLA? Are bucket versioning and Object Lock mandated for regulated data? Is the table `location` region-specific, which forces location rewriting on failover?
4. **Write topology.** Will any table have writers in more than one region? A single home region per table is assumed.
5. **Streaming footprint.** How many tables have Flink or Kafka Connect writers, and at what checkpoint or commit intervals? This sizes snapshot retention policy and metadata.json growth.
6. **Retention and regulatory holds.** What are the SEC 17a-4 or WORM retention obligations for table data? They constrain snapshot expiration, orphan removal, purge and soft-delete windows. (Compliance input is needed, not researched here.)
7. **Curated artifact repository.** Which framework BOMs (Spring Boot or Quarkus versions), JDK distribution (25 LTS?), MongoDB driver and `iceberg-core` versions are approved? What is the lead time to add artifacts?
8. **Engine client versions.** Are Spark, Flink and PyIceberg on Iceberg 1.11+ (ETag cache, Idempotency-Key) and 1.12+ (`rest-catalog-purge`)? How do Snowflake and Trino behave against a custom REST catalog, given that their `If-None-Match` and `snapshots=refs` support is unverified?
9. **Credential model.** Will the catalog vend credentials or use remote signing? Can delete permission be withheld from non-maintenance principals, so that soft delete is enforceable?
10. **Maintenance compute.** Who funds and operates the maintenance worker tier (Spark cluster vs Java workers), and under which change-control regime?

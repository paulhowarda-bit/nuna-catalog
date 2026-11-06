# Non-relational candidates for the catalog store

Research date: 2026-11-06. Input: `00_requirements.md` in this folder, including the decisions added on 2026-11-06:

- **D36/D36a.** One catalog in one environment, either on-prem or AWS. The same database must be self-hostable in both, and it never spans them. Managed-only services are out as the single choice.
- **D37.** Zero loss of acknowledged commits within one region or metro (3 AZs or 3 local sites, synchronous quorum, about 1-2 ms per commit). Async replication to a DR region is acceptable. Cross-region synchronous writes are not required.

Candidates: FoundationDB (in depth, with the Record Layer), MongoDB, Amazon DynamoDB, Apache Cassandra, ScyllaDB, etcd, and briefly Aerospike.

Provenance tags:
- `[read: URL]` means I fetched the source and read the cited passage. Downloads are in `C:/Users/paulh/AppData/Local/Temp/claude/d9ac3f89f5ec51f6/2eb8cba6-c503-4baa-9e18-b3df89b0f733/scratchpad/downloads/db_nosql/`.
- `[read via WebFetch: URL]` means the site blocked curl, so I only saw WebFetch's extract.
- `[inference]` is my reasoning.
- Nothing here was benchmarked. No database was installed or run.

---

## Verdict

**Finalists: FoundationDB, then MongoDB (replica set, Enterprise Advanced on-prem or self-managed/Atlas on AWS).** Everything else is out for this workload.

- **FoundationDB** is the only candidate whose default transaction is strictly serializable, multi-key and multi-range. It has no locks and resolves "unknown result" deterministically. Its single-region `three_data_hall` mode maps exactly onto D37 (3 AZs, synchronous, zero loss).
  - It also has the strongest precedent for exactly this job: it is Snowflake's metadata store, and has been since 2014.
  - Its costs:
    - It has no vendor support and scarce expertise. I found no commercial vendor.
    - Hard limits (5 s per transaction, 10 MB, 100 KB values) force a layered design: chunk projections, and pre-stage large payloads.
    - Minor-version upgrades bounce every process at once (seconds of unavailability, not rolling).
    - There is no access control inside the database.
    - Native CDC is experimental, as of 8.0.
- **MongoDB** expresses the commit unit naturally as one replica-set transaction with `w:majority`. Its documents are big enough (16 MiB), and it has TTL indexes, collation-based case-insensitive unique indexes, and change streams.
  - Expertise and a vendor are easy to find, and Nessie runs on it in production.
  - Its costs:
    - Transactions are snapshot isolation, not serializable. That is sufficient here because every commit writes its pointer document, but Jepsen's last look (4.2.6, 2020) found SI violations even at the strongest settings.
    - Strongly consistent reads come from one primary, and `majority` reads can be stale during a two-primaries window. Only `linearizable` closes that gap, and it is "significantly slower".
    - The licence is SSPL or commercial.
- **Out:**
  - **DynamoDB:** out by D36. Separately, MRSC global tables reject transactions.
  - **Cassandra 5.0.9:** LWT is single-partition only. Accord exists only in 6.0-alpha2.
  - **ScyllaDB:** LWT is single-partition. Raft "strongly-consistent-tables" is an experimental flag. The source-available licence is restrictive.
  - **etcd:** an 8 GiB storage ceiling, 1.5 MiB requests and 128 ops per transaction.
  - **Aerospike 8:** technically plausible, but thin evidence and no catalog precedent.

---

## 0. Design points that change the evaluation for every candidate [inference]

These came out of reading the limits. They lower the bar every store has to clear, so I applied them to all candidates.

1. **Only the pointer needs strongly consistent reads.** A projection is a pure function of the metadata file it was derived from, and D5 stores the file's SHA-256 with the pointer. So key the projection by `(table, metadata-location)` or by hash; it is then immutable.
   - Any node can cache it, or read it from any replica, without staleness risk.
   - Each `loadTable` then needs one strongly consistent read of a ~200 B pointer, plus a cache hit.
   - This turns "5k-20k strongly consistent loads/s of up to a few hundred KB" into "5k-20k tiny linearizable point reads/s", which every finalist handles. It also cuts the cost of DynamoDB-style per-item read limits.
2. **Pre-stage big payloads outside the commit transaction.** Write a large projection, or a multi-MB journal body (large schema), in earlier transactions keyed by content hash. Then make the commit transaction write only pointers, references and small rows.
   - This keeps FoundationDB well under its 1 MB guidance and 10 MB limit, even for a 100-table commit.
   - It keeps MongoDB transactions small, which helps with the 5 ms lock wait and cache pressure.
   - Cost: GC of orphaned staged blobs, much like Iceberg's own orphan files.
3. **Resolving an unknown outcome needs a unique commit id that the transaction itself writes.** The idempotency row or the journal entry serves. After an ambiguous error, read it back; if the store guarantees the transaction is no longer in flight, the answer is definitive.
   - FoundationDB gives exactly that guarantee for `commit_unknown_result`, but not for `transaction_timed_out` (§1.2).
   - Where the store gives no such guarantee, return 5xx as requirement 5 says.

---

## 1. FoundationDB (in depth)

### 1.1 What and which version

- FDB was created in 2009, acquired by Apple in 2015, and open-sourced under Apache 2.0 in 2018. It is "the underpinning of cloud infrastructure at Apple, Snowflake and other companies" [read: https://www.foundationdb.org/files/fdb-paper.pdf (SIGMOD 2021, Zhou et al.)].
- **Versions, as of 2026-11-06:**
  - The docs download page lists **7.3.77** server and client packages [read: https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/downloads.rst].
  - GitHub marks 7.3.77 (2026-04-16) as a full release. 7.4.8 (2026-09-24) and **8.0.0 (2026-10-02)** are flagged pre-release [read: https://api.github.com/repos/apple/foundationdb/releases].
  - Maven Central's `fdb-java` lists `8.0.0` as latest, updated 2026-11-06 [read: https://repo1.maven.org/maven2/org/foundationdb/fdb-java/maven-metadata.xml].
  - **Treat 7.3.x as the production line, with 8.0 as the next major** [inference].
- **8.0.0 removals:**
  - The experimental **multitenancy/metacluster** features. Tenant C symbols now "abort the process when called".
  - Experimental **encryption at rest**. File-level backup encryption stays.
  - Experimental **blob granules and storage-server ChangeFeed**.
- **8.0.0 additions:** an experimental **Native CDC**, "disabled by default through `ENABLE_NATIVE_CDC`", where "CDC does not provide exactly-once application side effects".
- 8.0.0 also "Removed deprecated Java finalizer-based cleanup. Java applications must close databases, transactions, and other native-backed objects explicitly" [read: https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/release-notes/release-notes-800.rst].

### 1.2 Transaction primitives and the commit unit

- **Isolation.** "FoundationDB provides global, ACID transactions with strict serializability using optimistic concurrency" [read: https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/developer-guide.rst].
- **How it works:**
  - Reads happen at a read version (GRV). Writes are buffered on the client.
  - At commit, the resolver aborts the transaction if any key it read was written by a transaction that committed between its read version and its commit version.
  - "FoundationDB transactions are optimistic: we never block on reads or writes (there are no locks), instead we abort transactions at commit time."
  - "Read-only transactions will never conflict and never cause conflicts" [read: developer-guide.rst].
- **Read-after-commit.** The GRV proxy returns "the last committed version as of the time it received the request — this is important to guarantee external consistency". It also checks that the log system "is still writable… to prevent that we fetch read versions from a GRV proxy that has been declared to be dead" [read: developer-guide.rst].
  - So a load that starts after a commit ack sees it, including across failovers. That is requirement 3, with no edge case comparable to MongoDB's two-primaries window.
  - Only the opt-in `causal-read-risky` or cached read versions weaken this.
- **The commit unit as one FDB transaction** [inference; key layout is a sketch]:
  ```
  tx.get(ptr/<tableId>)                         -> {metaLoc, version, sha256}; abort 409 if != base
  tx.set(ptr/<tableId>, {newMetaLoc, v+1, sha})  -- the CAS; conflict range = this key
  tx.set(proj/<tableId>/<v+1>/<chunk i>, ...)    -- projection chunked < 100 KB, or a reference to a pre-staged blob
  tx.set(journal/<tableId>/<v+1>, ...)          -- or versionstamped; body pre-staged if large
  tx.setVersionstampedKey(outbox/<epoch>/<vs>, event)   -- conflict-free append
  tx.set(idem/<key>, {commitId, response}); tx.set(idemExpiry/<expiresAt>/<key>, "")
  tx.commit()
  ```
  - The read of `ptr/<tableId>` adds a read-conflict range. Two writers from the same base therefore cannot both commit: one gets `not_committed` (1020), retries, re-reads the pointer, sees it changed, and returns 409. That is requirement 1.
  - Multi-table `/transactions/commit` is the same thing over N pointers in one transaction.
  - Grants plus their audit row are one transaction.
- **Hot key.** No locks means no waiting and no deadlock. Losers abort at commit and the retry loop re-runs.
  - On a pointer this is exactly CAS semantics, so a loser is a genuine 409, not wasted work.
  - Successful commits on one key are bounded by roughly one per GRV-read-commit round trip. At the paper's single-DC latencies (about 1 ms GRV and 2 ms commit, below) that is hundreds per second per table, against a requirement of 1-2/s [inference from read numbers].
- **Unknown results.** On `commit_unknown_result`, "the transaction either committed or not and if it didn't commit, it will never commit in the future… it is guaranteed that the transaction is not in-flight anymore" [read: developer-guide.rst].
  - So the server can read back `idem/<key>` or the commit id and answer definitively.
  - `transaction_timed_out` and `operation_cancelled` "lack the one guarantee", so the transaction may still commit later. They must map to 5xx, which is requirement 5.
  - The `automatic_idempotency` option "is currently experimental and not recommended for use in production" [read: https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/automatic-idempotency.rst].
  - Do not set client-side timeouts that fire mid-commit; bound the work instead [inference].

### 1.3 Limits against this workload

All four limits are from [read: https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/known-limitations.rst]; the impacts are [inference].

| Limit (documented) | Workload impact |
|---|---|
| Transaction ≤ 10,000,000 bytes of affected data. "If any single transaction exceeds one megabyte… you should modify your design." | **Single-table commit:** a 5-6 record commit of a few KB is fine. A few hundred KB of projection is under 1 MB, but chunk it anyway. **Multi-table commit:** 100 tables × 300 KB of projections is about 30 MB and **fails**, so pre-stage the projections (design point 0.2). The commit then writes about 100 pointers plus references. |
| Values ≤ 100,000 bytes; keys ≤ 10,000 bytes | **Projections and journal bodies:** both must be split across keys, and the Record Layer does this automatically (§1.6). **Keys:** 10 KB is ample for `(namespace path, name)`. |
| "does not support transactions running for over five seconds" | **Commit transaction:** it lasts milliseconds, provided the metadata.json PUT to object storage happens *before* the transaction starts, which D5 already requires. **Lists of tens of thousands of tables:** must page across transactions with continuations. That is normal for FDB, and the Record Layer cursors return continuations for exactly this. |
| Hot-key reads: "aggregate read performance of any given key… limited to… three server processes (typically on the order of 100,000 reads per second)" | Far above any one table's load rate. |
| Tested to "500 cores/processes" and "100 TB" | The catalog is small: 1M tables × KB-scale data, plus a journal growing by years. |

### 1.4 Topology under D37 (single region, 3 AZs, async DR)

- **Primary choice: `three_data_hall` in one region, plus async DR.**
  - "FoundationDB stores data in triplicate, with one copy on a storage server in each of three data halls. The transaction logs are replicated four times, with two data halls containing two replicas apiece… This configuration enables the cluster to remain available after losing a single data hall and one machine in another data hall" [read: https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/configuration.rst].
  - The Kubernetes operator documents three-data-hall as replication "across three data halls, or availability zones", and recruits 9 coordinators for it [read: https://raw.githubusercontent.com/FoundationDB/fdb-kubernetes-operator/main/docs/manual/fault_domains.md].
  - **RPO is 0 inside the region**: logs are synchronously in two halls before the ack [read]. **RTO is seconds**: across 289 production recoveries, "The median and 90-percentile are 3.08 and 5.28 seconds… client reads were not impacted" [read: fdb-paper.pdf §5.3]. **Commit latency** is roughly GRV plus a commit to logs in two AZs. On a single-DC test cluster the paper measured "about 0.35ms to read a key, 2ms to commit, and 1ms to get a read version" [read: fdb-paper.pdf §5.2]. Cross-AZ will add the AZ RTT [inference].
- **DR region: `fdbdr` to a second cluster.**
  - "the secondary database… will always contain a *consistent* copy of the primary… from some past point in time… the 'ACI' in ACID is preserved but D (Durability) is lost for some amount of most recent changes… lag behind… by as little as a few seconds".
  - The destination is "locked to prevent accidental use", so the DR region cannot serve reads.
  - `fdbdr switch` is a planned swap that locks both databases "for a few seconds". `fdbdr abort` after a disaster "will lose some amount of prior commits" [read: https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/backups.rst].
  - This matches D37's "seconds to minutes of RPO, reconcile from storage".
- **Alternative: region configuration ("fearless DR").** Not required by D37, but it is the same machinery.
  - It "enables automatic failover between two datacenters, without adding a WAN latency for commits". Commits are made durable in the primary DC and a nearby **satellite** that holds only logs; the remote region replicates asynchronously.
  - When the primary DC fails, the satellite's log tail lets the remote catch up "without suffering any data loss". "If a region failover occurs, clients will generally only see a latency spike of a few seconds."
  - **The catch:** if the primary DC and its satellites are lost together, `force_recovery_with_data_loss` "will discard the portion of the mutation log which did not make it across the WAN".
  - **Further limits:**
    - At most two regions hold replicas.
    - Reading from the non-primary region still needs a GRV from the primary region.
    - About 5% of reads go remote unless clients set `datacenter-id` [read: configuration.rst].
    - Apple's production cluster ran this with a 6.5 ms satellite and a 60.6 ms remote. Average commit was about 22 ms and p99.9 about 281 ms [read: fdb-paper.pdf §5.1].
  - **[inference]** For a single-metro deployment, the three AZs can play the roles of primary DC, satellite and remote DC. That gives zero-loss *automatic* failover within the metro, at the cost of two full replicas. `three_data_hall` is simpler and meets D37, so I would only revisit this if PLT-1 demands an automated DR region.
- **Per-table home region:** not applicable. A region config has one active region for the whole cluster, and writes go there [read: configuration.rst "route client traffic to the currently active region"].

### 1.5 Strongly consistent read scaling

- Default reads are strictly serializable. Each needs a GRV, which proxies batch, plus storage reads served by any replica [read: developer-guide.rst].
- The paper's CloudKit cluster averaged "390.4K" reads/s and 138.5K writes/s, with average read latency about 1 ms and p99.9 about 19 ms [read: fdb-paper.pdf §5.1].
- 5k-20k pointer loads/s is two orders of magnitude below demonstrated production load [inference].
- **Java client caveat.** Each process has "*only one* network thread", which can saturate. The remedy is "running additional client processes" or the multi-threaded client [read: https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/api-general.rst]. Size the catalog fleet with that in mind and measure network-thread CPU [inference].

### 1.6 CDC/outbox, TTL, indexes, names, paging, and the Record Layer

- **Outbox via versionstamps.** A versionstamp "can be generated at commit-time with no read conflict ranges, providing a unique sequence ID in a single conflict-free write". It "guarantees uniqueness and monotonically increasing values for the entire lifetime of a single FDB cluster", but "Special care must be taken when moving data between two FoundationDB clusters… the differing cluster versions might break the monotonicity" [read: https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/data-modeling.rst].
  - **[inference]** An outbox keyed `outbox/<epoch>/<versionstamp>` is a **gap-free, totally ordered log**. A relay that scans it at read version V sees every commit ≤ V, with no in-flight-sequence gaps of the kind Postgres sequences have.
  - Writers never conflict with each other or with the relay.
  - The relay publishes to Kafka, then deletes or advances a cursor in a transaction, which gives at-least-once delivery. Dedupe on the versionstamp downstream.
  - The `epoch` prefix exists for DR/restore. CloudKit faced the same problem when moving users between clusters and solved it with an application-level "incarnation" ordered before the version [read: https://arxiv.org/pdf/1901.04452 (Record Layer paper)].
  - FDB 8.0 Native CDC is experimental and off by default [read: release-notes-800.rst]. Do not depend on it.
- **TTL.** None is built in [inference; nothing in the docs read]. Write `idemExpiry/<expiresAt>/<key>` in the same transaction and run a sweeper that range-reads expired keys and clears both. Range clears are cheap [inference].
- **Grants by principal and by object, and case-insensitive uniqueness.** All are manual index key-spaces maintained in the same transaction as the base rows, so they are transactionally consistent.
  - For a unique name, read `nameIdx/<ns>/<lower(name)>` before inserting. Serializability makes the check race-free [inference from §1.2].
- **Prefix paging.** Keys are ordered. Use range reads with a limit, then resume after the last key, not key-selector offsets ("Key selectors with large offsets are slow… O(offset)") [read: known-limitations.rst].
- **The Record Layer** is Apache-2.0, Java, built by Apple, latest `fdb-record-layer-core` 4.18.3.0 [read: https://repo1.maven.org/maven2/org/foundationdb/fdb-record-layer-core/maven-metadata.xml]. It supplies most of the above:
  - A record store with Protobuf schemas and transactionally maintained indexes, including `unique: true`, and a SQL/JDBC interface [read: https://raw.githubusercontent.com/FoundationDB/fdb-record-layer/main/README.md; https://raw.githubusercontent.com/FoundationDB/fdb-record-layer/main/docs/sphinx/source/Overview.md].
  - A `COLLATE` key-expression function (`collate_jre`, or ICU) with a strength argument, giving case-insensitive index keys [read: https://raw.githubusercontent.com/FoundationDB/fdb-record-layer/main/fdb-record-layer-core/src/main/java/com/apple/foundationdb/record/metadata/expressions/CollateFunctionKeyExpression.java]. A unique index over a collated key gives case-insensitive uniqueness [inference].
  - Records "split across multiple keys", which works around the 100 KB value limit.
  - Continuations "allow scan or query operations that exceed the transaction time limit to be split across multiple transactions".
  - VERSION indexes ordered by commit version, which is what CloudKit sync uses [read: Record Layer paper].
  - **[inference]** Using the Record Layer removes most of the layer-writing risk but adds a dependency with its own release cadence. A thin hand-written layer over the raw `fdb-java` API is also realistic, since the catalog's key space is small and fixed.

### 1.7 Operations

- **Backup and PITR.** Continuous backup to local disk or a blob store (S3), with "repeated inconsistent snapshots" plus mutation logs, restorable to a version or `--timestamp` [read: backups.rst]. Expire old data with `fdbbackup expire --expire-before-timestamp` to align with object-store retention [read: backups.rst].
  - backups.rst says "Backup data is not encrypted at rest". The 7.4.x and 8.0 release notes do add backup-file encryption with GCM tags [read: release-notes-740.rst; release-notes-800.rst]. **Verify against the version actually deployed** [inference].
- **Upgrades.**
  - Patch upgrades are compatible. Minor and major upgrades "are incompatible and require that all processes are updated in a short time period" [read: https://raw.githubusercontent.com/FoundationDB/fdb-kubernetes-operator/main/docs/manual/upgrades.md].
  - Clients need the multi-version client loaded ahead of the upgrade [read: https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/administration.rst; api-general.rst].
  - Apple upgrades "by restarting all processes at the same time, which usually finishes within a few seconds" [read: fdb-paper.pdf §6.3].
  - **This is not the rolling upgrade the requirements ask for.** It is a few-second outage per minor version, and the catalog server must return 503 and retry through it [inference].
  - Downgrades between non-patch releases are not supported [read: administration.rst].
- **Kubernetes.** `fdb-kubernetes-operator` v2.39.0 (2026-10-30) is actively released. It ships CRDs for clusters, backups and restores, and supports multi-Kubernetes-cluster and multi-region deployment [read: https://api.github.com/repos/FoundationDB/fdb-kubernetes-operator/releases; README.md; fault_domains.md]. Its Helm chart "is not published and not actively tested" [read: README.md].
  - On VMs or bare metal, RPM and DEB packages exist [read: downloads.rst].
- **Security.** "Anyone who can connect to a FoundationDB cluster can read and write every key… There is no user-level access control" [read: known-limitations.rst].
  - Mutual TLS with peer-verification rules is available [read: https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/tls.rst].
  - 8.0 removed tenants and encryption at rest [read: release-notes-800.rst].
  - **For a regulated firm:** isolate the cluster on a dedicated network, use mTLS with certificates issued only to catalog servers, encrypt the disks, and treat the catalog service as the only principal [inference]. Expect security review to ask about this.
- **Java driver.**
  - `fdb-java` is JNI over `libfdb_c` and needs the native client library in the image.
  - It is async, returning `CompletableFuture` [background, not re-read this session]. Blocking `join()` from virtual threads should be fine because completion arrives from the network thread, not inside a native frame [inference; verify in the benchmark].
  - 8.0 requires explicit `close()` [read: release-notes-800.rst].
  - **No 7.x Windows client exists.** The Windows installer only goes up to 6.3.12 [read: downloads.rst], so developers and benchmarks run the client in Linux containers or WSL.
- **Expertise and vendor.** I found no current commercial support vendor in a web search. Treat that as absence of evidence, not proof.
  - Snowflake built an in-house FDB team: "After FDB was bought in 2015, Snowflake created a team tasked with improving this beneficial but complex technology" [read: https://medium.com/snowflake/migrating-snowflakes-metadata-with-no-downtime-ca90604b677c (2022-02-08)].
  - **The expertise gap is FDB's biggest risk at a bank-scale firm** [inference].
- **Licence and cost.** Apache 2.0 with no licence fee [read: paper; RL LICENSE]. The cost is people, plus about 9+ hosts per cluster across 3 AZs, plus a DR cluster [inference].

### 1.8 Precedent

- **Snowflake** (from Ashish Motivala's post of 2018-04-19) [read via WebFetch: https://www.snowflake.com/en/blog/how-foundationdb-powers-snowflake-metadata-forward/]:
  - Snowflake has used FDB "as our metadata store" since 2014.
  - It holds catalog definitions, users, access control, transaction state, lock queues and encryption keys, using watches for lock queues.
  - Data is triple-replicated "across multiple cloud availability zones".
- **Snowflake migration (2022):** "more than 20 deployments… across multiple regions in both AWS and Microsoft Azure… At the heart of each deployment was an FDB3 cluster with hundreds of CPUs, serving millions of transactions per second". The migration to open-source FDB6 took two years [read: Medium post above].
- **Apple CloudKit** runs on FDB plus the Record Layer, "billions of independent databases" [read: Record Layer paper]. Its geo-replicated cluster held 292 TB [read: fdb-paper.pdf §5.1].
- **Iceberg catalogs:** I found none on FDB. Nessie's supported list does not include FDB [read: https://raw.githubusercontent.com/projectnessie/nessie/main/site/in-dev/configuration.md]. So the catalog layer would be greenfield, though the store's metadata pedigree is the best of any candidate [inference].

### 1.9 FDB risks and disqualifiers

None is a hard disqualifier. The material risks are:
1. Expertise and support.
2. Non-rolling minor upgrades.
3. No in-database access control.
4. The 5 s / 10 MB / 100 KB limits, which force chunking and pre-staging.
5. CDC must be hand-built on versionstamps.
6. 8.0 just removed several experimental features, so avoid anything marked experimental.

---

## 2. MongoDB

### 2.1 Version and licence

- **9.0.0 was released 2026-09-28**; 9.0.4 followed on 2026-10-13. 8.0, 8.2 and 8.3 notes also exist [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/release-notes/9.0.txt].
- New in 9.0: concurrent multi-document transactions are capped by `maxConcurrentMultiDocumentTransactions` (default `10000`), and excess transactions get `TooManyOpenTransactions` [read: 9.0.txt].
- Community Server is under the **SSPL v1** [read: https://raw.githubusercontent.com/mongodb/mongo/master/LICENSE-Community.txt]. Enterprise Advanced is commercial (pricing not researched).
- The Java sync driver is at `5.14.0` (2026-10-28) [read: https://repo1.maven.org/maven2/org/mongodb/mongodb-driver-sync/maven-metadata.xml].
- The same engine runs on-prem (self-managed EA) and on AWS (self-managed or Atlas), which satisfies D36 [inference].

### 2.2 Transaction primitives and the commit unit

- **Single-document CAS** is atomic. Two options [inference]:
  - Embed the latest N idempotency keys in the pointer document.
  - Use **a multi-document transaction for the full unit**: pointer `updateOne({_id, metaLoc: base})` with matched=0 meaning 409; upsert the projection; insert the journal, outbox and idempotency documents.
  - Use `writeConcern: majority`, `readConcern: snapshot` and the callback API.
- **Transactions need a replica set.** Defaults: "a transaction must have a runtime of less than one minute" (`transactionLifetimeLimitSeconds` = 60). Each oplog entry must be ≤ 16 MB. Transactions "wait up to `5` milliseconds to acquire locks… If the transaction cannot acquire its required locks within the `5` milliseconds, the transaction aborts" [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/core/transactions-production-consideration.txt; reference/parameters.txt].
  - A pending DDL operation, such as `createIndex` on a collection, makes new transactions on that collection wait and abort [read: same]. So schedule index builds carefully [inference].
- **Hot document** [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/includes/extracts-transactions.yaml]:
  - "If a transaction is in progress and a write outside the transaction modifies a document that an operation in the transaction later tries to modify, the transaction aborts because of a write conflict."
  - "If a transaction… has taken a lock to modify a document, when a write outside the transaction tries to modify the same document, the write waits until the transaction ends."
  - The callback API retries the whole transaction on `TransientTransactionError` and retries the commit on `UnknownTransactionCommitResult` [read: core/transactions-in-applications.txt].
  - **[inference]** For CAS this is fine: the retry re-reads the pointer and returns 409. Keep the transaction small, because of the 5 ms lock wait. A 100-table commit racing single-table commits on the same tables will abort more often.
- **Isolation is snapshot isolation, not serializable.**
  - Jepsen on 4.2.6 (2020-05-15): "even at the strongest levels of read and write concern, it failed to preserve snapshot isolation… read skew, cyclic information flow, duplicate writes". MongoDB attributed this to "a bug in the transaction retry mechanism… a patch is scheduled for 4.2.8".
  - Jepsen also found that transactions ignore database- and collection-level concerns, so "users must be careful to set read and write concern on each transaction directly" [read: https://jepsen.io/analyses/mongodb-4.2.6].
  - **There has been no newer Jepsen analysis of MongoDB as of 2026-11-06** [read: https://jepsen.io/analyses index].
  - **[inference]** SI is enough *for this commit unit*, because every commit writes the pointer document, so first-committer-wins prevents two commits from the same base. Write skew could only bite invariants checked by reading without writing, such as a grant check during a concurrent revoke. Case-insensitive uniqueness relies on a unique index, not a read check, so it is safe.
- **Ambiguity.** Map `UnknownTransactionCommitResult` that persists after the commit retry to 5xx. Otherwise read back the commit id with `majority` [inference].

### 2.3 Durability and topology under D37

- Use a 3-AZ replica set (P-S-S, one per AZ) with `w: majority` and journaling.
  - "The default global write concern is majority" [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/reference/write-concern.txt].
  - "To prevent rollbacks of data that have been acknowledged to the client, run all voting members with journaling enabled and use `{ w: "majority" }`" [read: core/replica-set-rollbacks.txt].
  - That gives **RPO 0 within the region** [inference].
- **8.0 change.** "`{ w: "majority" }` writes return an acknowledgment after a majority of data-bearing members durably write the oplog entry. Members then asynchronously apply the changes… Queries on secondaries immediately after a `{ w: "majority" }` write acknowledgment may read from the collection before the secondary applies changes" [read: write-concern.txt].
  - So **do not serve loads from secondaries** unless you use causal sessions, which only help the same client [inference].
- **RTO.** "The median time before a cluster elects a new primary should not typically exceed 12 seconds" with default settings, with a 10 s `electionTimeoutMillis` [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/includes/fact-election-latency.rst; core/replica-set-elections.txt].
  - That is slower than FDB's 3-5 s recovery, and tunable [inference].
- **DR region.** Add non-voting secondaries there.
  - The majority is "calculated as the smaller of… the majority of *all* voting members… the number of all **data-bearing** voting members", so non-voting DR members add no commit latency [read: write-concern.txt].
  - Failover to the DR region is a forced reconfiguration. RPO equals replication lag [inference].
- **Commit latency** is one majority round trip to the nearest other AZ, plus journal fsync, a few ms [inference].

### 2.4 Read scaling and read-after-commit

- **Linearizable reads:** "reads with linearizable read concern may be significantly slower than reads with "majority""; they are primary-only; and they are unavailable in causal sessions [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/reference/read-concern-linearizable.txt].
- **Two primaries:** "two nodes in a replica set may *transiently* believe that they are the primary… clients that connect to the former primary may observe stale data despite having requested read preference primary" [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/includes/footnote-two-primaries-edge-cases.rst].
  - **So primary + `majority` meets requirement 3 except during that partition window.** `linearizable` closes the window at a latency cost [inference].
  - Given CAS, a stale load leads to a 409 later, not corruption. But an engine that polls and reads an older snapshot right after another engine's ack does violate requirement 3 transiently [inference].
- **Scale.** All strongly consistent pointer reads hit one primary. 20k/s point reads of about 200 B documents is well within one primary's capability [inference, not measured]. Sharding would add primaries but turns multi-table commits into distributed transactions, so don't shard a 1M-document catalog [inference].

### 2.5 Limits, CDC, TTL, indexes

- **Documents:** "The maximum BSON document size is 16 mebibytes" [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/includes/fact-document-max-size.rst]. That covers projections of hundreds of KB and journal bodies of a few MB. Use GridFS or chunking above that.
- **Change streams** need a replica set and are resumable via resume tokens. A past start point "must be in the time range of the oplog" [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/changeStreams.txt].
  - **[inference]** The relay must persist resume tokens and alert on oplog-window risk. Alternatively, poll the outbox collection by a commit sequence taken from the pointer document's version, which is monotonic per table, not globally. A global order would need a counter document, which is a hot spot. Ordering per table, or via change-stream oplog order, is the realistic contract.
- **TTL:** "The background task that removes expired documents runs every 60 seconds"; deletion may lag further under load [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/includes/fact-ttl-collection-background-timing.rst]. Idempotency lookups must also check expiry themselves [inference].
- **Case-insensitive unique names:** create the index with `collation: {locale, strength: 1|2}`. "To use an index that specifies a collation, query and sort operations must specify the same collation" [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/core/index-case-insensitive.txt].
- **Grants:** compound indexes `(principal, object)` and `(object, principal)`.
- **Paging:** range on `(namespaceId, name)` [inference].
- **Backup/PITR:** Ops Manager or Cloud Manager "creates snapshots at set intervals and offers point-in-time recovery". Ops Manager is the on-prem version for subscribers [read: https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/core/backups.txt]. On AWS, Atlas or Ops Manager do the same (Atlas backup docs not read).
- **Rolling upgrades** of replica sets are documented, for example in the `9.0-upgrade-replica-set` notes (file list read, page not read). Online index builds are standard. Being schemaless, schema migration is application-level [inference].

### 2.6 Precedent

- **Nessie:** "MongoDB | production | `MONGODB2`" [read: https://raw.githubusercontent.com/projectnessie/nessie/main/site/in-dev/configuration.md].
- **Polaris:** "MongoDB (NoSQL) | Experimental | … Currently in beta" [read: https://raw.githubusercontent.com/apache/polaris/main/site/content/in-dev/unreleased/metastores/_index.md].
- **Both use a catalog-wide HEAD pointer.** Polaris: "The state of the whole catalog is referenced via the so-called HEAD"; "backing stores… support 'compare and swap' operations on a single row" [read: https://raw.githubusercontent.com/apache/polaris/main/persistence/nosql/persistence/README.md].
  - Neither uses per-table pointers with multi-document transactions, so their production use does not demonstrate the commit unit chosen in D6 [inference].

### 2.7 MongoDB risks and disqualifiers

None is a hard disqualifier. The material risks are:
1. Snapshot isolation with a 2020 Jepsen record of violations, never independently retested.
2. Every strong read hits one primary, and `majority` is not linearizable during a two-primaries window.
3. A 5 ms lock wait plus aborts during DDL.
4. CDC ordering is only as good as the oplog window.
5. SSPL means Enterprise Advanced in practice.

---

## 3. Amazon DynamoDB (brief: **out by D36**, since it is managed-only and cannot be self-hosted on-prem)

These facts are recorded so the question does not get reopened:
- **Transactions:** `TransactWriteItems` takes "up to 100 write actions… up to 100 distinct items… The aggregate size of the items in the transaction cannot exceed 4 MB", and "You can't target the same item with multiple operations within the same transaction". The client token "is valid for 10 minutes"; isolation is serializable [read: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.md].
  - At about 5 items per table, a multi-table commit caps at roughly 20 tables [inference].
- **Items:** maximum 400 KB [read: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.md].
- **Per-item throughput:** "up to the partition maximum of 3,000 RCUs and 1,000 WCUs to that single item's primary key" [read: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/burst-adaptive-capacity.md]. A strongly consistent read of a 200 KB projection costs 50 RCUs, so about 60 such reads/s per item [inference]. Design point 0.1 fixes this.
- **Global tables:**
  - MREC is last-writer-wins.
  - MRSC "must be deployed in exactly three Regions", has RPO zero, and "Time to Live (TTL) is not supported".
  - **MRSC tables "do not support transaction operations, and will return an error if those operations are invoked"** [read: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/V2globaltables_HowItWorks.md]. So zero-RPO multi-region and the atomic commit unit are mutually exclusive.
- **Other limits:**
  - Streams keep 24 h, with order per item only [read: Streams.md].
  - TTL deletes "within a few days" [read: TTL.md].
  - PITR covers 1-35 days, and "LatestRestorableDateTime is typically five minutes before the current time" [read: PointInTimeRecovery_Howitworks.md].
  - GSIs are eventually consistent [read: HowItWorks.ReadConsistency.md].
- **DynamoDB Local is useless for contention tests.** It is "for development and testing purposes only", and "`TransactionConflictExceptions` aren't thrown by downloadable DynamoDB" [read: DynamoDBLocal.md; DynamoDBLocal.UsageNotes.md].
- **Precedent:** Nessie rates it beta, "Not recommended for use with Nessie Catalog… due to its restrictive row-size limit" [read: Nessie configuration.md]; Polaris rates it experimental [read: Polaris _index.md].

## 4. Apache Cassandra (out for now)

- **Versions, as of 2026-11-06:** the newest stable release is **5.0.9**, and the newest 6.0 artifact is **6.0-alpha2** [read: https://downloads.apache.org/cassandra/ listing; https://repo1.maven.org/maven2/org/apache/cassandra/cassandra-all/maven-metadata.xml].
- **LWT (Paxos) is single-partition.** The source rejects "Batch with conditions cannot span multiple partitions" and "…cannot span multiple tables" [read: https://raw.githubusercontent.com/apache/cassandra/cassandra-5.0/src/java/org/apache/cassandra/cql3/statements/BatchStatement.java]. Paxos v2: "Expect 2RTs for a write" (recommended; v1 is the default) [read: https://raw.githubusercontent.com/apache/cassandra/cassandra-5.0/conf/cassandra.yaml].
  - **[inference]** A single-table commit could be one conditional batch if pointer, journal, outbox and idempotency rows share one partition (`tableId`) of one CQL table. But multi-table commits, namespace-scoped unique names, grants across objects and a global outbox order are all impossible without multi-partition atomicity. The journal partition also grows without bound for years.
- **Accord (CEP-15)** is documented on the 6.0 branch: "Strict Serializability", multi-partition and multi-table `BEGIN TRANSACTION … COMMIT TRANSACTION`, opt-in per table via `transactional_mode='full'`, and enabled with `accord: enabled: true` [read: https://raw.githubusercontent.com/apache/cassandra/cassandra-6.0/doc/modules/cassandra/pages/developing/cql/transactions.adoc]. **It ships only in alpha builds as of 2026-11-06.**
- **Precedent:** Nessie rates it "experimental, known issues… user-facing errors due to… letting the driver timeout too early" [read: Nessie configuration.md].
- Revisit when 6.0 is GA and Accord has an independent safety analysis [inference].

## 5. ScyllaDB (out)

- **Versions:** the newest stable tag is 2026.3.4, and 2026.4.0-rc1 is in progress [read: https://api.github.com/repos/scylladb/scylladb/tags].
- **LWT:** "Conditional statements and conditional batches are executed atomically, as a Lightweight Transaction (LWT)", on Paxos per partition [read: https://raw.githubusercontent.com/scylladb/scylladb/master/docs/features/lwt.rst]. The same single-partition shape as Cassandra applies [inference].
- **Raft-based strongly consistent tables** are behind the `"strongly-consistent-tables"` **experimental** feature flag in 2026.3.4 [read: https://raw.githubusercontent.com/scylladb/scylladb/scylla-2026.3.4/db/config.cc]. The design doc is in progress [read: https://raw.githubusercontent.com/scylladb/scylladb/master/docs/dev/strong_consistency.md].
- **Jepsen (2020-12-23, 4.2-rc3, ScyllaDB-funded):** "LWT exhibited split-brain in healthy clusters", fixed in 4.2. Split-brain persisted "limited to membership changes concurrent with other faults" [read: https://jepsen.io/analyses/scylla-4.2-rc3].
- **Licence:** "ScyllaDB Software License Agreement" v1.1 (updated 2026-04-12). It is restricted to "Never Customers" and assigns modifications to the licensor [read: https://raw.githubusercontent.com/scylladb/scylladb/master/LICENSE-ScyllaDB-Source-Available.md]. A commercial subscription would be mandatory [inference].

## 6. etcd (reference point; disqualified)

- **Limits** [read: https://raw.githubusercontent.com/etcd-io/website/main/content/en/docs/v3.6/dev-guide/limit.md]:
  - "By default, the maximum size of any request is 1.5 MiB."
  - "The default storage size limit is 2 GiB… 8 GiB is a suggested maximum size".
  - `--max-txn-ops '128'` [read: https://raw.githubusercontent.com/etcd-io/website/main/content/en/docs/v3.6/op-guide/configuration.md].
- **Safety:** Jepsen on 3.4.3 (2020-01-30) found "key-value operations appear to be strict serializable, and that watches deliver every change to a key in order" [read: https://jepsen.io/analyses/etcd-3.4.3].
- **Verdict:** the semantics are excellent but the size is impossible. Years of journal, plus 1M projections, is far beyond 8 GiB, and a 100-table commit exceeds 128 ops. The latest release is v3.7.3 (2026-10-22) [read: https://api.github.com/repos/etcd-io/etcd/releases].

## 7. Others

- **Aerospike 8.x** (not shortlisted).
  - Transactions "were introduced in Aerospike Database 8.0.0" and need the strong-consistency namespace mode "to ensure strict serializability" [read: https://aerospike.com/docs/database/learn/transactions.md].
  - Writes take record locks (dual versions). A concurrent write to a locked record "fails immediately", and transactional reads get `AS_ERR_MRT_BLOCKED`. The client is the coordinator, with a monitor record whose commit bit decides the outcome [read: https://aerospike.com/docs/database/learn/architecture/transactions.md].
  - **[inference]** It is technically capable of the commit unit, but it is client-coordinated, has no catalog precedent, and is less common in enterprise DBA teams. I did not read its record-size limits, backup or licence pages, so its rating is low-confidence.
- **Not evaluated (no sources read): TiKV, Couchbase, HBase, Redis.** No claims made.

---

## 8. Scorecard

Ratings: **S** strong, **A** adequate, **W** weak, **X** fails or disqualified. Evidence is in the sections above.

| Dimension | FoundationDB 7.3 (+Record Layer) | MongoDB 8.0/9.0 replica set | DynamoDB | Cassandra 5.0 (6.0 Accord) | ScyllaDB 2026.3 | etcd 3.6 | Aerospike 8 |
|---|---|---|---|---|---|---|---|
| 1 Txn primitives, hot key | **S**: strict-serializable multi-key OCC; no locks; losers abort at commit; definitive `commit_unknown_result` | **A**: SI multi-doc txns, 60 s default; WriteConflict then retry; 5 ms lock wait; Jepsen 2020 SI violations (patched, not retested) | **A** (single region): serializable `TransactWriteItems`, 100 items / 4 MB; **X** with MRSC (no txns) | **X**: LWT single partition and single table; Accord alpha only | **X**: LWT single partition; Raft tables experimental | **S** semantics (strict serializable), 128 ops | **A**: strict serializable in SC mode, client-coordinated |
| 2 Topology, RPO/RTO, latency (D37) | **S**: `three_data_hall` across 3 AZs, RPO 0, recovery median 3.1 s; `fdbdr` async DR seconds; ~2 ms commit (single-DC paper) | **A**: 3-AZ RS `w:majority`, RPO 0; election ~12 s median; DR via non-voting members, forced reconfig | **X** (D36); MRSC RPO 0 but 3 regions fixed | **A** topology (multi-DC native) but irrelevant given dim 1 | **A**, same | **A** (Raft, 3-5 nodes) | not read |
| 3 Strong read scaling | **S**: any replica after GRV; 390k reads/s in production | **A**: primary only; `majority` stale during two-primaries window; linearizable "significantly slower" | **A**: 3,000 RCU/item (fix with design point 0.1) | **W**: SERIAL reads go through Paxos | **W** | **A** | not read |
| 4 CDC/outbox | **A**: versionstamped outbox is gap-free and totally ordered (hand-built); native CDC experimental | **A**: change streams, resume tokens, oplog-window bound | **A**: Streams 24 h, per-item order | **W**: per-node commitlog CDC (not read) | **A**: CDC log tables (intro read) | **S**: ordered watches | not read |
| 5 Limits | **W/A**: 10 MB txn (1 MB advised), 100 KB value, 5 s; needs chunking and pre-staging | **S**: 16 MiB docs; 16 MB per oplog entry; 60 s | **W**: 400 KB items; 100 items / 4 MB | **A** (large partitions) | **A** | **X**: 1.5 MiB requests, 8 GiB store | not read |
| 6 Ops | **W/A**: continuous backup and PITR to S3; K8s operator active; minor upgrades bounce all processes; no in-DB authz; no Windows client; scarce expertise | **S**: Ops Manager/Atlas PITR; rolling upgrades; mature tooling; abundant expertise | n/a (D36) | **A**: widely known, but repairs and tuning | **A** | **A** | **W** (not read) |
| 7 Licence/cost | **S** licence (Apache 2.0, free); cost is staff | **A**: SSPL, or commercial EA/Atlas | n/a | **S**: Apache 2.0 | **W**: restrictive source-available, commercial needed | **S**: Apache 2.0 | not read |
| 8 Precedent | **S** metadata (Snowflake since 2014, CloudKit); no Iceberg catalog | **A**: Nessie production, Polaris beta, both catalog-wide HEAD | **W**: Nessie beta "not recommended", Polaris experimental | **W**: Nessie experimental, known issues | none found | Kubernetes metadata (not catalogs) | none found |
| 9 Disqualifier? | No: risks are expertise, upgrades, authz, limits | No: risks are SI, primary-only reads, Jepsen age | **Yes**: D36; MRSC has no txns | **Yes**: no multi-partition atomicity GA | **Yes**: same, plus licence | **Yes**: size | Not disqualified; insufficient evidence |

## 9. Finalists

**1. FoundationDB**, raw `fdb-java` with a thin custom layer, or the Record Layer.
- It is the only candidate where every correctness requirement (1, 2, 3 and 5) is met by the database's default semantics, not by careful configuration.
- Its D37 topology is a documented, first-class mode.
- The atomic unit, multi-table commits and the gap-free outbox all fall out of one primitive.
- **Its precedent is the closest to this exact use:** Snowflake's catalog and metadata.
- **The decision hinges on whether Fidelity will staff or contract FDB expertise, and accept:**
  - a few seconds of outage per minor upgrade;
  - network-level isolation in place of database access control;
  - the chunking and pre-staging design work.

**2. MongoDB**, replica set, `w:majority`, `readConcern: majority` on the primary (or `linearizable` for loads), callback-API transactions.
- It needs the least custom engineering: TTL, case-insensitive unique indexes, CDC, 16 MiB documents and PITR tooling all exist.
- Expertise and vendor support are abundant, and the user already leans this way.
- **Its gaps must be proven, not assumed:**
  - SI behaviour of the exact commit transaction under contention and failover, given Jepsen's 2020 findings;
  - read-after-commit through a primary failover;
  - outbox ordering via change streams.

Everything else fails a hard requirement today (§3-§6).

## 10. Proposed local benchmark (Docker on the Windows laptop; finalists only)

**Constraint from this session:** do not install or run databases now. This is a plan only.

### Setup
- **FDB:** `foundationdb/foundationdb:7.3.77`, 5 containers in `triple` mode, or 6 containers with `three_data_hall` using `locality_data_hall` labels to exercise the 4-way log replication.
  - The Java harness must run in a Linux container (`eclipse-temurin:25` plus the `foundationdb-clients` 7.3.77 deb), because no 7.x Windows client exists [read: downloads.rst].
  - Docker Hub has 7.3.x and 8.0.0 tags [read: https://hub.docker.com/v2/repositories/foundationdb/foundationdb/tags].
- **MongoDB:** `mongo:8.0` and `mongo:9.0`, 3-member replica set, `w:majority`, `j:true`, Java sync driver 5.14 on virtual threads, in the same kind of container.
- **AZ simulation:** add `tc netem delay 0.5ms` (≈1 ms RTT) between containers (NET_ADMIN) for one run set.
- **Same harness for both:** one `CatalogStore` interface with an FDB implementation and a Mongo implementation.
- **Measurement:**
  - Use open-loop load generation with HdrHistogram and intended-start timestamps, to avoid coordinated omission.
  - Record p50/p99/p99.9, throughput, abort/retry counts by error class, and 409 counts.

### Workloads
1. **Contended single-key CAS.** Run 1, 2, 4, 8, 16, 32 and 64 writers against one table. Each does read pointer, then the full 5-6-record commit with a 10 KB projection.
   - Measure successful commits/s, 409 rate, retries (FDB `not_committed` 1020; Mongo WriteConflict/TransientTransactionError), and latency.
   - Check invariants after every run: `final version == successful commits`; journal count, outbox count and idempotency rows equal successes; no two successes share a base.
2. **Uncontended commit throughput.** 10k tables with uniformly random writers. Projections of 4 KB, 64 KB and 300 KB (FDB chunked versus pre-staged).
3. **Multi-record and multi-table commit.** N = 1, 10, 50, 100 tables, with and without a concurrent single-table writer on overlapping tables.
   - Record abort rate, latency, and the FDB transaction size actually sent.
   - Confirm that the 100 × 300 KB direct variant fails on FDB and that pre-staging fixes it.
4. **Load QPS.** 100k to 1M pointers, zipfian, at fixed offered rates of 5k, 10k and 20k/s, while committing in the background at 50-500/s.
   - FDB: default strict reads.
   - Mongo: primary `majority` versus `linearizable`.
   - Also test with projections served from the immutable cache (design point 0.1) versus read from the DB.
5. **Read-after-commit checker.** Writer A acknowledges version v and signals; reader B on another connection must read ≥ v. Count violations, especially while running workload 6.
6. **Fault-lite.** `docker kill` or `docker pause` the Mongo primary, or the FDB process holding the transaction log or commit proxy, during workloads 1 and 5.
   - Verify that ambiguous outcomes surface as unknown or 5xx, that no acknowledged commit is lost, and that the read-back resolution (design point 0.3) works.
   - Record time to the first successful commit after the fault.
7. **Outbox drain.** A relay to a local Kafka (or a stub) under workload 2. Check for gaps, duplicates and per-table and global ordering. FDB uses a versionstamp scan; Mongo uses a change stream with resume after relay restart.

### What a laptop cannot show
- **Hardware:**
  - Real cross-AZ latency and its variance. netem only approximates it.
  - Real fsync cost: Docker Desktop's WSL2 virtual disk is neither a production SSD nor a cloud volume.
  - Throughput ceilings beyond one host's CPU and disk.
- **Faults:** real network partitions, asymmetric partitions, clock skew and disk faults. `docker pause` is not a Jepsen nemesis, and the two-primaries window is unlikely to reproduce.
- **Long-run behaviour:** compaction, the MVCC window, oplog-window pressure, WiredTiger cache with a large working set, and journal growth over years.
- **Operations:** minor-version upgrades on FDB, rolling upgrades on Mongo, backup and PITR restore time at real size, DR switchover, the Kubernetes operator, and on-call burden.
- **Use of the numbers:** absolute values are not transferable. Use the results only for **relative shape**: contention curves, abort behaviour, invariant violations, and ambiguity handling.

---

## 11. Gaps and things I did not verify

- **FDB:**
  - No commercial support vendor found (web search only).
  - Record Layer release cadence and schema-evolution docs not read in depth.
  - `SchemaEvolution.md` and the operator backup page not read.
- **MongoDB:**
  - Atlas backup and PITR, Ops Manager lifecycle and support dates, and the Kafka connector and Debezium docs not read.
  - Enterprise Advanced pricing not researched.
  - 9.0 upgrade pages listed but not read.
- **Others:** the Cassandra CDC page, the full ScyllaDB CDC docs, and Aerospike limits and licence not read.
- **Sources only partly read:** the Snowflake 2018 blog (WebFetch extract only; curl blocked by Cloudflare); Jepsen MongoDB 3.6.4 (downloaded, not used).
- **Performance claims:** none here was measured.

## Sources read (all fetched 2026-11-06)

FoundationDB:
- https://raw.githubusercontent.com/apple/foundationdb/main/documentation/sphinx/source/ — `known-limitations.rst`, `configuration.rst`, `developer-guide.rst`, `automatic-idempotency.rst`, `backups.rst`, `administration.rst`, `data-modeling.rst`, `api-general.rst`, `tls.rst`, `downloads.rst`, `release-notes/release-notes-800.rst`, `release-notes/release-notes-740.rst`
- https://api.github.com/repos/apple/foundationdb/releases
- https://www.foundationdb.org/files/fdb-paper.pdf
- https://arxiv.org/pdf/1901.04452
- https://raw.githubusercontent.com/FoundationDB/fdb-record-layer/main/ — `README.md`, `docs/sphinx/source/Overview.md`, `CollateFunctionKeyExpression.java`, `LICENSE`
- https://raw.githubusercontent.com/FoundationDB/fdb-kubernetes-operator/main/ — `README.md`, `docs/manual/fault_domains.md`, `docs/manual/upgrades.md`
- Maven metadata for `fdb-java` and `fdb-record-layer-core`
- https://medium.com/snowflake/migrating-snowflakes-metadata-with-no-downtime-ca90604b677c
- https://www.snowflake.com/en/blog/how-foundationdb-powers-snowflake-metadata-forward/ (WebFetch extract)

MongoDB:
- https://raw.githubusercontent.com/mongodb/docs/main/content/manual/manual/source/ — `reference/write-concern.txt`, `reference/read-concern-linearizable.txt`, `core/transactions-production-consideration.txt`, `core/transactions-in-applications.txt`, `includes/extracts-transactions.yaml`, `includes/footnote-two-primaries-edge-cases.rst`, `includes/fact-document-max-size.rst`, `includes/fact-ttl-collection-background-timing.rst`, `includes/fact-election-latency.rst`, `core/index-case-insensitive.txt`, `changeStreams.txt`, `core/replica-set-rollbacks.txt`, `core/replica-set-elections.txt`, `core/backups.txt`, `reference/parameters.txt`, `release-notes/9.0.txt`
- https://raw.githubusercontent.com/mongodb/mongo/master/LICENSE-Community.txt
- Maven metadata for `mongodb-driver-sync`

DynamoDB:
- https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ — `transaction-apis.md`, `V2globaltables_HowItWorks.md`, `Constraints.md`, `burst-adaptive-capacity.md`, `Streams.md`, `TTL.md`, `PointInTimeRecovery_Howitworks.md`, `HowItWorks.ReadConsistency.md`, `DynamoDBLocal.md`, `DynamoDBLocal.UsageNotes.md`

Cassandra:
- https://raw.githubusercontent.com/apache/cassandra/ — `cassandra-6.0/doc/modules/cassandra/pages/developing/cql/transactions.adoc`, `cassandra-5.0/doc/modules/cassandra/pages/developing/cql/dml.adoc`, `cassandra-5.0/src/java/org/apache/cassandra/cql3/statements/BatchStatement.java`, `cassandra-5.0/conf/cassandra.yaml`
- https://downloads.apache.org/cassandra/
- Maven metadata for `cassandra-all`

ScyllaDB:
- https://raw.githubusercontent.com/scylladb/scylladb/ — `master/docs/features/lwt.rst`, `master/docs/architecture/tablets.rst`, `master/docs/dev/strong_consistency.md`, `master/docs/features/cdc/cdc-intro.rst`, `scylla-2026.3.4/db/config.cc`, `master/LICENSE-ScyllaDB-Source-Available.md`
- https://api.github.com/repos/scylladb/scylladb/tags

etcd:
- https://raw.githubusercontent.com/etcd-io/website/main/content/en/docs/v3.6/ — `dev-guide/limit.md`, `op-guide/configuration.md`
- https://api.github.com/repos/etcd-io/etcd/releases

Jepsen:
- https://jepsen.io/analyses (index)
- https://jepsen.io/analyses/mongodb-4.2.6
- https://jepsen.io/analyses/scylla-4.2-rc3
- https://jepsen.io/analyses/etcd-3.4.3

Catalog precedent:
- https://raw.githubusercontent.com/projectnessie/nessie/main/site/in-dev/configuration.md
- https://raw.githubusercontent.com/apache/polaris/main/site/content/in-dev/unreleased/metastores/_index.md
- https://raw.githubusercontent.com/apache/polaris/main/persistence/nosql/persistence/README.md

Aerospike:
- https://aerospike.com/docs/database/learn/transactions.md
- https://aerospike.com/docs/database/learn/architecture/transactions.md

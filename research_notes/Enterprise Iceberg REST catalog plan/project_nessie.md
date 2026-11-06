# Project Nessie as a design reference for an enterprise Iceberg REST catalog (MongoDB focus)

Research date: 2026-11-06. Lens: a from-scratch Java + MongoDB Iceberg REST catalog for a large regulated financial firm.

**Provenance key.** `[read: URL]` = I downloaded and read the primary source (source code and repo docs were curled raw from `projectnessie/nessie@main` on 2026-11-06 into the scratchpad and read there; web pages marked read were fetched with WebFetch, which paraphrases, so quoted fragments from web pages are short). `[secondary: URL]` = only a search-engine summary or a third party's account was seen. `[inference]` = my own reasoning from the cited material. Code citations give the repository path; all paths are on `main` as of 2026-11-06 (HEAD pushed 2026-11-06T11:42Z per the GitHub API).

Base URLs used below: `GH` = `https://github.com/projectnessie/nessie/blob/main/`.

---

## 1. Architecture: the git-like model, and why the persistence layer was rewritten

### Takeaway
Nessie models a whole catalog as a git-like repository: content keys (namespace.table) map to content objects, commits change many keys atomically, and named references (branches and tags) are rows updated by compare-and-swap. The original "database adapter" layer funnelled every change through one global-pointer row and was built around a "global state" concept that was later dropped. That made it a contention point and hard to scale with many keys, so it was replaced (in 2022–2023) by a "Persist" API with two logical tables, `refs` and `objs`: immutable, content-addressed objects plus per-reference CAS. The old layer was deleted in 0.75.0 (2023-12-15).

### Cited Findings
- The new storage README says the old "database adapter" model "was originally developed with the later removed ability to track some so called 'global state'", was "practically hard to use with many content keys", and made stricter commit-time content validation "practically not doable" [read: GH versioned/storage/README.md](https://github.com/projectnessie/nessie/blob/main/versioned/storage/README.md).
- New model design choices from that README:
  - Named references "can be anything, not just branches and tags".
  - A generic object model serves commits, spilled index segments and content values.
  - Every object ID is "always derived from the hash over the object's content". Objects are therefore immutable and can be cached "without having to implement distributed cache coherency".
  - Each commit carries an "incremental index" of key changes. When it becomes too big it spills into a "reference index", which can itself be "striped across multiple segments".
  - Key lookups become index lookups, so commits can validate expected content cheaply without scanning the commit log.
  - All iterators are pageable.

  [read: GH versioned/storage/README.md](https://github.com/projectnessie/nessie/blob/main/versioned/storage/README.md)
- The README states that "all `Persist` implementation _must_ perform CAS(-like) operations". Objects must not be stored twice, and reference creations and updates must be conditional. The Persist API is "a low-level `Persist` interface" over a "named references table" and an "objects table" [read: same].
- Creating and deleting references on non-transactional databases goes through an internal reference (`int/refs`, `InternalRef.REF_REFS`), which is itself a commit log. A create is (1) a commit adding the name to `REF_REFS`, then (2) `Persist.addReference`. A delete is mark-deleted, then a REMOVE commit, then a purge. Readers "resume" half-finished create or delete operations, which serves as crash recovery. Updates to a reference's pointer are not tracked in `REF_REFS` [read: GH versioned/storage/common/.../logic/ReferenceLogicImpl.java class javadoc](https://github.com/projectnessie/nessie/blob/main/versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/logic/ReferenceLogicImpl.java).
- Old-model contention, from Nessie's 2022 incident post-mortem: on non-transactional databases a single "global pointer" held "the mapping of all named references to their HEAD commit IDs, the HEAD of the ref-log and the HEAD of the global-log". Every update was a CAS on that one row. The post-mortem says it "does not scale well" and calls it a "single point of contention". A rolling upgrade from ≤0.25.0 to ≥0.26.0 corrupted that pointer in production, because two versions interpreted the `global_id` field differently. The repair procedure was manual. Rolling-upgrade tests were added to CI afterwards (PR #4350) [read: GH site/docs/blog/posts/2022-05-incident.md](https://github.com/projectnessie/nessie/blob/main/site/docs/blog/posts/2022-05-incident.md).
- 0.75.0 (2023-12-15): "The deprecated version-store implementations based on 'database datapter' have been removed". Users had to migrate **before** upgrading [read: GH CHANGELOG.md](https://github.com/projectnessie/nessie/blob/main/CHANGELOG.md).
- Migration from the old to the new model is offline: stop the servers, export, change the store type, import, start [read: GH versioned/storage/README.md](https://github.com/projectnessie/nessie/blob/main/versioned/storage/README.md). V2 imports are "more expensive by design" because every commit must carry complete indexes. The index fix-up code "is the only code that is allowed to actually _update_ a (commit) object" [read: same].
- A second storage generation came in 0.96.0 (2024-09-11): `JDBC2`, `CASSANDRA2`, `DYNAMODB2` and `MONGODB2`. Each has "the same functionality" as its predecessor but "uses way less attributes/columns, which reduces storage overhead". The v1 types were deprecated, and moving to v2 again requires export/import with the Server Admin Tool [read: GH CHANGELOG.md, 0.96.0 section](https://github.com/projectnessie/nessie/blob/main/CHANGELOG.md).
- Servers are stateless and do not talk to each other: "There is no form of 'distributed consensus'". The README floats an un-implemented idea of routing commits for the same branch to the same server, "as a best-effort approach" [read: GH versioned/storage/README.md](https://github.com/projectnessie/nessie/blob/main/versioned/storage/README.md).
- Cache history:
  - Immutable `objs` were cached early; there are "very few reads against that table in production systems".
  - Reference caching was added later. It is disabled by default, and is safe with multiple instances only with "distributed cache invalidation" (PR #8463), delivered as HTTP messages on the management port 9000.
  - Each reference lookup costs two reads (one per `refs/heads/` and `refs/tags/` prefix), so negative-cache TTLs are needed.

  [read: GH site/docs/blog/posts/2024-05-cache-enhancements.md](https://github.com/projectnessie/nessie/blob/main/site/docs/blog/posts/2024-05-cache-enhancements.md)
- Rolling-upgrade matrix: rolling upgrades are supported from 0.61.0 or newer to 0.110.0. Several historical boundaries (0.26, 0.40, 0.61) were not safe for rolling upgrade [read: GH site/docs/server-upgrade.md](https://github.com/projectnessie/nessie/blob/main/site/docs/server-upgrade.md).

### Inferences
- [inference] The rewrite's core lesson: make the only mutable state a small per-reference pointer row updated by single-row CAS, and make everything else immutable and content-addressed. That removes the need for multi-row transactions, makes retries idempotent, and makes caching trivial. A from-scratch MongoDB design can adopt this directly.
- [inference] The cost was two storage-format generations in about 18 months (v1 Persist in 2023, then "2" types in 2024), each needing an offline export/import. An enterprise catalog should version its on-disk format from day one and support online migration.

### Gaps
- I did not read the old `versioned/persist` adapter code (it is deleted from `main`). The description of the old model comes from the README and the 2022 post-mortem only.

---

## 2. Backends and how reference CAS is implemented (MongoDB in detail)

### Takeaway
Every backend reduces to two primitives: an **insert-if-absent** for immutable objects, and a **conditional single-row update** of a reference (pointer, deleted flag, created-at and extended-info must all still match). On MongoDB this is a plain `updateOne(filter, $set)` on one document, plus `insertOne` with duplicate-key detection. The code uses no multi-document transactions or sessions, creates no secondary indexes, and sets no read or write concern; those come entirely from the connection string. Nessie's docs label MongoDB "production", and there are almost no MongoDB-specific bug reports.

### Cited Findings — MongoDB (`MONGODB2`, current; `MONGODB`, deprecated)
- **Collections and document layout.**
  - Collections are `refs2` and `objs2` (v1: `refs` and `objs`).
  - Reference document: `_id: {r: <repositoryId>, n: <refName>}`, `p` pointer (binary ObjId), `d` deleted (bool), `c` created-at micros, `e` extended-info ObjId, `h` previous-pointers history (binary).
  - Object document: `_id: {r: <repositoryId>, i: <binary ObjId>}`, `y` type short name, `V` version token (mutable objects only), `v` the whole object serialized as one binary blob, `z` "referenced" timestamp in micros.

  [read: GH versioned/storage/mongodb2/.../MongoDB2Constants.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/mongodb2/src/main/java/org/projectnessie/versioned/storage/mongodb2/MongoDB2Constants.java); [read: GH .../mongodb2/MongoDB2Persist.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/mongodb2/src/main/java/org/projectnessie/versioned/storage/mongodb2/MongoDB2Persist.java)
- **v1 vs v2.** A diff of `MongoDBPersist.java` against `MongoDB2Persist.java` shows the persistence logic is identical; only the encoding differs.
  - v1 stores each object type as a native BSON sub-document through per-type `ObjSerializers`.
  - v2 stores one binary field `v` produced by `ProtoSerialization.serializeObj`.

  So v2 trades queryable BSON for compactness [read: GH .../mongodb/MongoDBPersist.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/mongodb/src/main/java/org/projectnessie/versioned/storage/mongodb/MongoDBPersist.java) vs MongoDB2Persist.java.
- **Reference CAS (commit point).** `updateReferencePointer` runs `refs().updateOne(referenceCondition(ref), [$set p=new, $set h=prevPointers])`.
  - The filter is an AND of: `_id` equal to `{r,n}`; `p` equal to the expected pointer; `d` equal to false; `c` equal to the expected value, or must not exist; `e` equal to the expected value, or must not exist.
  - If `modifiedCount != 1` and `matchedCount == 1`, the update is treated as a no-op success. Otherwise the reference is re-fetched and the code throws `RefNotFoundException` or `RefConditionFailedException`.
  - `markReferenceAsDeleted` uses the same filter with `$set d=true`. `purgeReference` uses `deleteOne` with the same filter. `addReference` is `insertOne`, and `DUPLICATE_KEY` maps to `RefAlreadyExistsException`.

  [read: MongoDB2Persist.java]
- **Object writes.**
  - `storeObj` is `insertOne`. On `DUPLICATE_KEY` it instead runs `updateOne($set z=now)` to refresh the "referenced" timestamp, and returns false.
  - `storeObjs` is a `bulkWrite` of `InsertOneModel`s. On `MongoBulkWriteException` it rethrows any non-duplicate error, retries the remaining inserts, then bulk-updates `z` for the duplicates.
  - Mutable ("updateable") objects use optimistic versioning: `findOneAndUpdate(filter _id + y + V==expectedVersion, $set ...)` and `findOneAndDelete` with the same version filter.
  - `upsertObj(s)` is `replaceOne(..., upsert=true)`.
  - `deleteWithReferenced` is `findOneAndDelete` conditioned on the `z` timestamp. This is what lets repository cleanup avoid deleting an object that was re-referenced concurrently.

  [read: MongoDB2Persist.java]
- **Reads.** `find(eq(_id, {r,i}))` for single objects; `find(in(_id, [...]))` for batches; `find(in(_id, [...]))` for multiple references. A full-repository scan or erase filters on `_id.r` [read: MongoDB2Persist.java; MongoDB2Backend.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/mongodb2/src/main/java/org/projectnessie/versioned/storage/mongodb2/MongoDB2Backend.java).
- **No transactions, indexes, or concerns in code.** A grep of all downloaded storage sources for `WriteConcern`, `ReadConcern`, `createIndex`, `startSession`, `withTransaction` and `ReadPreference` returned nothing. `MongoDB2Backend` only calls `getDatabase` / `getCollection` (`setupSchema` creates nothing) [read: MongoDB2Backend.java]. The MongoDB settings Nessie documents are only `quarkus.mongodb.database` and `quarkus.mongodb.connection-string`, plus a pointer to Quarkus' full MongoDB client options [read: GH site/in-dev/configuration.md](https://github.com/projectnessie/nessie/blob/main/site/in-dev/configuration.md).
- **Ambiguous outcomes.** `MongoInterruptedException`, `MongoTimeoutException`, `MongoServerUnavailableException`, `MongoSocketReadTimeoutException`, `MongoExecutionTimeoutException`, and write errors in category `EXECUTION_TIMEOUT` or `UNCATEGORIZED` are mapped to `UnknownOperationResultException`, meaning the outcome is unknown [read: MongoDB2Persist.java].
- **Object size and the 16 MB limit.**
  - `Persist.hardObjectSizeLimit()` defaults to `Integer.MAX_VALUE`. MongoDB does not override it; DynamoDB2 does, with `ITEM_SIZE_LIMIT = 400 * 1024`.
  - The effective index-size limits are `min(config, hardLimit/2)`, and the `StoreConfig` defaults are: incremental index 50 KiB, serialized index segment 200 KiB, at most 50 reference-index stripes per commit.

  So on MongoDB, object sizes are bounded by those index settings, not by an explicit 16 MB guard [read: GH .../common/persist/Persist.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/persist/Persist.java); [read: GH .../common/config/StoreConfig.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/config/StoreConfig.java); [read: GH .../dynamodb2/DynamoDB2Constants.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/dynamodb2/src/main/java/org/projectnessie/versioned/storage/dynamodb2/DynamoDB2Constants.java).
- **Commit retry policy (all backends).** Commit retries are unlimited but bounded by `commit-timeout` (default 5,000 ms). The randomized backoff starts at 5–25 ms and doubles up to 250 ms. The assumed wall-clock drift is 5 s [read: StoreConfig.java].
- **Sharding advice** (maintainer `dimas-b`, issue #6981, 2023): shard `objs` on `_id.r` + `_id.i` and `refs` on `_id.r`, adding `_id.n` if only one repository exists. The maintainer added "We do not really attempt to provide and maintain tuning guides for backend storage" [read: gh issue view 6981](https://github.com/projectnessie/nessie/issues/6981).
- **Support status.** Nessie's support matrix lists MongoDB as "production" (`MONGODB2`, with `MONGODB` deprecated) [read: GH site/in-dev/configuration.md](https://github.com/projectnessie/nessie/blob/main/site/in-dev/configuration.md). The storage README's informal Docker load tests: "MongoDB - works fine - probably a good choice"; RocksDB was fastest but single-node [read: GH versioned/storage/README.md](https://github.com/projectnessie/nessie/blob/main/versioned/storage/README.md).
- **Issue history.** A GitHub issue search for "mongodb" in projectnessie/nessie returned only configuration, Helm and sharding questions, plus old (2021) adapter-era test bugs. I found no reported MongoDB-specific production failures or performance problems [read: `gh search issues --repo projectnessie/nessie mongodb`, 2026-11-06](https://github.com/projectnessie/nessie/issues?q=mongodb).

### Cited Findings — JDBC2 (PostgreSQL, MariaDB/MySQL, H2; CockroachDB experimental)
- **Schema.**
  - `refs2(repo, ref_name, pointer, deleted, created_at, ext_info, prev_ptr)`, primary key (repo, ref_name).
  - `objs2(repo, obj_id, obj_type, obj_vers, obj_value, obj_ref)`, primary key (repo, obj_id).
  - The value is a single binary column: BYTEA on PostgreSQL, LONGBLOB on MariaDB. MariaDB names use `VARCHAR(255) ... utf8mb4_bin`.
  - Batches are at most 50 rows.

  [read: GH .../jdbc2/SqlConstants.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/jdbc2/src/main/java/org/projectnessie/versioned/storage/jdbc2/SqlConstants.java); [read: GH .../jdbc2/DatabaseSpecifics.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/jdbc2/src/main/java/org/projectnessie/versioned/storage/jdbc2/DatabaseSpecifics.java); [read: GH .../jdbc2/Jdbc2Backend.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/jdbc2/src/main/java/org/projectnessie/versioned/storage/jdbc2/Jdbc2Backend.java)
- **CAS.** `UPDATE refs2 SET pointer=?, prev_ptr=? WHERE repo=? AND ref_name=? AND pointer=? AND deleted=? AND created_at… AND ext_info…`, and the code checks `executeUpdate() == 1`. Object inserts use `ON CONFLICT DO NOTHING` on PostgreSQL plus constraint-violation detection. Connections run with `autoCommit=false` [read: SqlConstants.java; AbstractJdbc2Persist.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/jdbc2/src/main/java/org/projectnessie/versioned/storage/jdbc2/AbstractJdbc2Persist.java); [read: DatabaseSpecifics.java].
- **Documented limits.** The docs say "Relational databases are generally slower and tend to become a bottleneck when concurrent Nessie commits against the same branch happen". CockroachDB is "Known to raise user-facing 'write too old' errors under contention" [read: GH site/in-dev/configuration.md](https://github.com/projectnessie/nessie/blob/main/site/in-dev/configuration.md). The README says PostgreSQL "is definitely not the right choice for higher throughput requirements", while PostgreSQL and CockroachDB are fine "for up to a couple of Nessie commits per second" [read: GH versioned/storage/README.md](https://github.com/projectnessie/nessie/blob/main/versioned/storage/README.md).

### Cited Findings — DynamoDB2, Cassandra2, BigTable, RocksDB
- **DynamoDB2.**
  - Reference update is a conditional `putItem`. The `conditionExpression` requires the expected pointer and deleted flag, plus `createdAt` and `extendedInfo` equality or `attribute_not_exists`. A new reference requires `attribute_not_exists(p)`; a new object requires `attribute_not_exists(y)`. Conflicts surface as `ConditionalCheckFailedException`.
  - `ITEM_SIZE_LIMIT` is 400 KiB. Batch-get is 100 items, batch-write 25.

  [read: GH .../dynamodb2/DynamoDB2Persist.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/dynamodb2/src/main/java/org/projectnessie/versioned/storage/dynamodb2/DynamoDB2Persist.java); [read: DynamoDB2Constants.java]. The docs say DynamoDB is "beta, only tested against the simulator" and "Not recommended for use with Nessie Catalog (Iceberg REST) due to its restrictive row-size limit" [read: GH site/in-dev/configuration.md](https://github.com/projectnessie/nessie/blob/main/site/in-dev/configuration.md).
- **Cassandra2.** It uses lightweight transactions: `STORE_OBJ = UPSERT_OBJ + " IF NOT EXISTS"`, `ADD_REFERENCE ... IF NOT EXISTS`, and `UPDATE_REFERENCE_POINTER ... IF <conditions>`, executed through `backend.executeCas(stmt)` [read: GH .../cassandra2/Cassandra2Constants.java & Cassandra2Persist.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/cassandra2/src/main/java/org/projectnessie/versioned/storage/cassandra2/Cassandra2Persist.java). The docs rate it "experimental, known issues", with user-facing errors from driver or database timeouts [read: configuration.md].
- **BigTable.** It is rated "production" [read: configuration.md]. The 0.108.8 changelog (2026-09-09) records that a gRPC channel-pool misconfiguration had pinned each Nessie instance to a single channel. That "silently capped BigTable throughput per Nessie instance and surfaced as `DEADLINE_EXCEEDED` errors under load" [read: GH CHANGELOG.md](https://github.com/projectnessie/nessie/blob/main/CHANGELOG.md). Issue #8474 (open since 2024) notes that BigTable's CAS (`CheckAndMutateRow`) is not retried at the client level [read: gh issue view 8474](https://github.com/projectnessie/nessie/issues/8474).
- **RocksDB.** "production, single node only" [read: configuration.md].

### Inferences
- [inference] On MongoDB, the `_id` is a compound sub-document `{r, n}` / `{r, i}`. Equality and `$in` on the whole `_id` use the default `_id` index. Filters on `_id.r` alone (used for `scanAllObjects`, `eraseRepositories` and Polaris-style repository maintenance) cannot use that index, so they are collection scans. The README's note that scan and erase "_will_ require full table scan" agrees with this.
- [inference] Correctness of the reference CAS does not depend on read concern, because the conditional `updateOne` always runs on the primary. Freshness does, though. A stale `fetchReference` from a secondary (if the connection string sets `readPreference=secondary*`) causes extra CAS failures and retries, not wrong results. With `w:1`, a primary failover can roll back an acknowledged reference update; for a regulated catalog `w:majority` (and `readConcern: majority` on reference reads) must be set explicitly. Nessie leaves all of this to the connection string.
- [inference] MongoDB's 16 MB limit is far above Nessie's 200 KiB index-segment default, so the only realistic large objects are content and catalog snapshot objects. Nessie's catalog keeps only one Iceberg snapshot per content version (see §3), which keeps those small. A catalog that stores full Iceberg metadata, including snapshot history, in MongoDB documents would need its own size budget and spill strategy.
- [inference] Every commit to a given branch serializes on one MongoDB document. Throughput per branch is therefore bounded by roughly 1 / (read-modify-CAS round trip at the chosen write concern), and degrades through retries under contention.

### Gaps
- I did not read the BigTable or RocksDB persist source.
- I found no published MongoDB benchmark numbers (commits/sec, latency) for Nessie, only the README's qualitative "works fine".
- No public reports of multi-region MongoDB deployments of Nessie.

---

## 3. Nessie's Iceberg REST catalog ("Nessie Catalog", 0.9x+)

### Takeaway
Nessie serves Iceberg REST at `/iceberg`. The REST `{prefix}` encodes `branch|warehouse`. Each commit both **writes a new `metadata.json` to object storage** and **stores a parsed snapshot object in Nessie's own database**, and the Nessie commit records the metadata location. The `/transactions/commit` endpoint becomes one atomic Nessie commit. Nessie deliberately returns only **one** Iceberg snapshot per table, the one for the current Nessie commit, which departs from normal Iceberg history semantics. Nessie's own docs still call REST support "experimental". Credential vending for GCS was broken until September 2026.

### Cited Findings
- Announced 2024-05-13 ("we will soon integrate the Apache Iceberg REST spec into open-source Nessie") [read: GH site/docs/blog/posts/2024-05-catalog-announcement.md](https://github.com/projectnessie/nessie/blob/main/site/docs/blog/posts/2024-05-catalog-announcement.md).
- The guide on `main` still opens with "Support for Iceberg REST is currently considered experimental in Nessie!" It says that with REST, "Nessie manages the metadata of the tables and views" and therefore needs access to the object store [read: GH site/docs/guides/iceberg-rest.md](https://github.com/projectnessie/nessie/blob/main/site/docs/guides/iceberg-rest.md).
- **Prefix and branch mapping.** Clients append the branch and/or warehouse to the `uri` instead of setting `prefix`, for example `http://host:19120/iceberg/experiments|sales`. The guide says "Note that setting the `prefix` parameter doesn't work for pyiceberg". Server-side, `DecodedPrefix` = `parsedReference` + `warehouse`. Time travel and branch access go through the table name: ``my_table@branch#2024-07-01T00:00:00Z`` or `#<commit hash>` [read: iceberg-rest.md](https://github.com/projectnessie/nessie/blob/main/site/docs/guides/iceberg-rest.md); [read: GH catalog/service/rest/.../DecodedPrefix.java](https://github.com/projectnessie/nessie/blob/main/catalog/service/rest/src/main/java/org/projectnessie/catalog/service/rest/DecodedPrefix.java).
- **Single-snapshot semantics.** "Nessie will always return only the Iceberg table snapshot that corresponds to the Nessie commit", chosen "to retain Nessie's consistency and cross-branch/tag isolation guarantees". Table locations are mandated by Nessie under the warehouse, and "Changes to the table base location are ignored" [read: iceberg-rest.md].
- **Metadata storage (code).** `CatalogServiceImpl.commit(...)`:
  1. Fetches current contents for all keys and requires the target to be a branch ("Can only commit to a branch").
  2. Applies the Iceberg updates for each operation.
  3. Writes the metadata JSON to object storage through `objectIO.writeObject(...)`.
  4. Performs **one** Nessie commit for all operations ("At this point, all metadata files have been written").
  5. On failure, deletes the stored metadata files.
  6. After commit, stores each `NessieTableSnapshot` as an `EntitySnapshotObj` in the Persist store, keyed by `snapshotObjIdForContent(content)`.

  Only `ICEBERG_TABLE` and `ICEBERG_VIEW` are accepted ("(Yet) unsupported entity type"). On the commit path, existing state is loaded from that stored object (`loadExistingTableSnapshot` → `icebergStuff().retrieveIcebergSnapshot(snapshotId, content)`). I did not trace the `loadTable` read path end to end [read: GH catalog/service/impl/.../CatalogServiceImpl.java](https://github.com/projectnessie/nessie/blob/main/catalog/service/impl/src/main/java/org/projectnessie/catalog/service/impl/CatalogServiceImpl.java); [read: GH catalog/service/common/.../objtypes/EntitySnapshotObj.java](https://github.com/projectnessie/nessie/blob/main/catalog/service/common/src/main/java/org/projectnessie/catalog/service/objtypes/EntitySnapshotObj.java).
- **Multi-table REST commit.** `POST /v1/{prefix}/transactions/commit` maps every `tableChange` to an `IcebergCatalogOperation` inside one `CatalogCommit` and calls `catalogService.commit`, producing one atomic Nessie commit [read: GH catalog/service/rest/.../IcebergApiV1GenericResource.java](https://github.com/projectnessie/nessie/blob/main/catalog/service/rest/src/main/java/org/projectnessie/catalog/service/rest/IcebergApiV1GenericResource.java).
- **Unimplemented endpoints.** The scan-planning endpoints (`/plan`, `/plan/{plan-id}`, `/tasks`) are marked "NOT implemented". `/v1/oauth/tokens` returns 501 with "please configure the catalog client with the oauth2-server-uri property", so an external IdP is required [read: IcebergApiV1GenericResource.java].
- [read: Iceberg OpenAPI, `/v1/{prefix}/transactions/commit` "Commit updates to multiple tables in an atomic operation", with an `idempotency-key` parameter](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml). A grep of the downloaded Nessie catalog sources found no reference to idempotency keys.
- **Credentials.** Nessie supports S3 request signing ("This requires a REST call for every S3 request") and STS-vended down-scoped credentials ("overall the faster and less resource intensive approach"). ADLS cannot be path-scoped [read: iceberg-rest.md].
- **Credential fixes, 2024–2026, from the changelog:**
  - 0.96.0 (2024-09): ADLS SAS tokens are "**not** scoped down" (experimental).
  - 0.108.8 (2026-09-09): GCS down-scoped credentials "were not functional and failed every credential-vending request". Also fixed: "Vended credentials scoped to a table location no longer grant access to sibling locations whose name starts with the same characters" (for example `warehouse/orders` covering `warehouse/orders2`).
  - 0.109.0 (2026-10-08): S3 signing fix for virtual-hosted style on custom endpoints.
  - 0.110.0 (2026-11-06): GCS credential refresh endpoint.

  [read: GH CHANGELOG.md](https://github.com/projectnessie/nessie/blob/main/CHANGELOG.md)
- Commit attribution can be passed via the `Nessie-Commit-Authors`, `Nessie-Commit-SignedOffBy` and `Nessie-Commit-Message` HTTP headers [read: iceberg-rest.md].

### Inferences
- [inference] Nessie stores metadata twice: `metadata.json` in object storage for interoperability, and a parsed snapshot in its database. The database copy is presumably what lets it serve table state without re-reading S3; I verified this only for the commit path. The dual-storage pattern is sound to copy. The single-snapshot response is not: it breaks engines and tools that expect `snapshots[]` / `snapshot-log` (Iceberg time travel by snapshot-id, incremental reads, `expire_snapshots` reasoning). It is a direct cost of branch isolation.
- [inference] Smuggling the branch into the `uri`/prefix (with a pyiceberg caveat) and the reference into the table name (`table@branch#hash`) shows that the Iceberg REST spec has no first-class notion of catalog-level references. Any catalog that adds them needs engine-side conventions.
- [inference] The 2026 credential-vending fixes (GCS entirely broken; S3/GCS prefix over-grant) are a security warning. A financial firm's catalog needs adversarial tests of the scoping logic, especially prefix boundaries.

### Gaps
- I did not verify view support depth, `register` / `rename` behaviour, or how Nessie handles `assert-ref-snapshot-id` / `assert-current-schema-id` requirements under concurrent writers on different branches.
- I did not confirm whether Nessie persists Iceberg `snapshots[]` history anywhere at all (code reading suggests not in REST responses).

---

## 4. Multi-table transactions and cross-table consistency

### Takeaway
Nessie offers atomic multi-table change in two ways: (a) one commit that touches many keys, used by REST `/transactions/commit`, and (b) work on a branch merged or transplanted into `main`. Its docs admit that engine support for real isolation levels is still missing. I found **no named enterprise case study** of Nessie branch-based data-as-code or write-audit-publish (WAP), only tutorials, vendor content, and two users' bug reports showing branch-merge workflows hitting timeouts. Atomic multi-table commit itself is now available in the vendor-neutral Iceberg REST spec (`/transactions/commit`), and per-table WAP is available through Iceberg-native branches and tags.

### Cited Findings
- **Two mechanisms.** The docs describe "Via Branches ... exposed as a single consistent view to other users through the use of a merge operation" and "Single Commits ... tools will need to be enhanced to take advantage". Isolation is optimistic only: Read Committed, Repeated Read, and Serializable (via `Unchanged` operations). The docs add: "work still needs to be done with tool developers", and "most tools operate in either Read Committed ... or Repeated Read" [read: GH site/docs/guides/transactions.md](https://github.com/projectnessie/nessie/blob/main/site/docs/guides/transactions.md).
- **Merge implementation.** A squash merge or transplant computes a diff, then commits it. A non-squash merge replays source commits. The current commit-message rewrite "requires reading all commits in between" [read: GH versioned/storage/README.md](https://github.com/projectnessie/nessie/blob/main/versioned/storage/README.md).
- **Real-world branch workflows hitting limits:**
  - Issue #12843 (opened 2026-08-04, still open). A "long-lived branch that receives frequent fan-in merges from many short-lived working branches (thousands of merge commits accumulated over months)". Merging about 45 keys takes more than 280 s even as a dry run, and even when the merge is a no-op. The time goes to `MergeBase.identifyAllMergeBases`, which fetches commits one at a time from JDBC2. In the reporter's words: "history grows without bound and merges get progressively slower until they no longer complete". The fix PR #12884 (prefetch commits) is open, not merged [read: gh issue view 12843](https://github.com/projectnessie/nessie/issues/12843).
  - Issue #10423 (opened 2025-02-19, open). Develop-into-main merges fail intermittently with "could not be performed within the configured commit timeout after 363321 milliseconds" while `main` takes concurrent commits (`RefConditionFailedException`). The maintainer's diagnosis: "a lot of concurrent changes, which slows down the merge operation (due to retries)" [read: gh issue view 10423](https://github.com/projectnessie/nessie/issues/10423).
- **Iceberg-native alternative.** Iceberg branches and tags are "named references to snapshots with their own independent lifecycles", with retention policies at branch and tag level. They are positioned for GDPR, audit retention and "experimental branches for testing and validating new jobs" [read: apache/iceberg docs/docs/branching.md](https://github.com/apache/iceberg/blob/main/docs/docs/branching.md). Per a secondary account, Netflix's WAP for "thousands of Apache Iceberg tables" uses Iceberg-native hidden snapshots, not Nessie [secondary: vutr.substack.com](https://vutr.substack.com/p/how-does-netflix-ensure-the-data?open=false).
- **Evidence of use.** WAP with Nessie appears in tutorials and blueprints: a Towards Data Science reference implementation and a Kestra blueprint [secondary: towardsdatascience.com WAP article](https://towardsdatascience.com/write-audit-publish-for-data-lakes-in-pure-python-no-jvm-25fbd971b17d/); [secondary: kestra.io blueprint](https://kestra.io/blueprints/nessie-iceberg-branch-wap-and-atomic-merge-gate). Searches for named production case studies returned none.
- **Branches do not port.** Migrating Nessie to Polaris (Dremio, 2026-10-27): branches and tags have "no automatic one-to-one mapping that preserves all semantics". Teams must choose among a registration per branch, collapsed tip-only registration, or separate catalogs [read: dremio.com migrating-from-nessie-to-apache-polaris](https://www.dremio.com/blog/migrating-from-nessie-to-apache-polaris-without-rewriting-iceberg-data/).

### Inferences
- [inference] The value an enterprise most needs, atomic multi-table publish, comes from the **catalog-wide commit plus single CAS** mechanism, not from user-facing branches. The REST `/transactions/commit` endpoint exposes exactly that, and any catalog with a catalog-scoped pointer can serve it.
- [inference] Catalog-wide **tags** (an immutable, consistent "as-of" view across all tables) are nearly free once a commit log exists. They are valuable for a regulated firm's reproducibility ("what did every table look like at 17:00 on close date") without branch-and-merge machinery.
- [inference] The real usage patterns in #12843 and #10423 (fan-in merges, concurrent writers on `main`) are the ones that hurt Nessie. Long-lived branches turn the commit DAG into a growing liability.

### Gaps
- No primary-source enterprise testimonials or conference talks were read. Dremio and Subsurface talks were not retrieved.
- No data on how many Nessie users actually use branches beyond `main`.

---

## 5. Garbage collection (nessie-gc) and repository growth

### Takeaway
A data file may be referenced from any commit on any branch or tag, so Iceberg's per-table `expire_snapshots` / orphan cleanup cannot be used safely. Nessie needs its own repository-wide mark-and-sweep. It runs as a separate tool with its own JDBC database, uses per-reference cutoff policies, bloom filters and object-store listings, and is hazardous enough that the docs recommend deferred deletion with human review. Separately, Nessie's own database grows without bound. A `cleanup-repository` admin command for unreferenced objects arrived only in 0.101.0 (2024-12-06).

### Cited Findings
- "Since Nessie is maintaining many versions of metadata and data-pointers simultaneously, you must rely on Nessie to clean up old data" [read: GH site/docs/guides/management.md](https://github.com/projectnessie/nessie/blob/main/site/docs/guides/management.md).
- **Phases.**
  1. **Identify/mark** walks all named references and stores the "live content set" in a database (PostgreSQL, MariaDB, MySQL or H2). It never modifies Nessie and never touches the lake.
  2. **Expire/sweep** works per content-ID. It maps live snapshots to metadata files, manifest lists, manifests and data files through Iceberg, recursively lists the base locations, and deletes what is not live, or records it for **deferred deletion**.
  3. **Delete** can be split out.

  [read: management.md]
- **Cutoff policies** per reference-name regex: `NONE` (the default when no policy matches: "everything in the named reference is considered 'live'"), number of commits, a duration, or a timestamp. Parallelism defaults to 4 references and 4 content-IDs [read: management.md].
- **Live-file tracking** uses a bloom filter. Defaults are 1,000,000 files per content-ID and a false-positive probability of 0.0001; expire aborts if a content ID "_massively_ exceeds" that [read: management.md]. Matching uses relative URIs under base locations [read: GH site/in-dev/gc.md](https://github.com/projectnessie/nessie/blob/main/site/in-dev/gc.md).
- **Security posture.** "object-store permissions are the primary deletion boundary". If less-trusted users can write table metadata, "use _deferred deletion_ first and review the generated deletion list" [read: management.md; gc.md].
- **Recent GC bugs** from the changelog:
  - 0.108.3 (2026-07-23): GC failed with `Path must be relative` for files outside the table location (`write.data.path`, old locations), issue #10817. Such files are now tracked by absolute URI and are "never deleted when they are outside all base locations".
  - 0.108.4 (2026-07-31): a missing HTTP client dependency after an AWS SDK bump.

  [read: GH CHANGELOG.md](https://github.com/projectnessie/nessie/blob/main/CHANGELOG.md)
- **Database-side growth.** 0.101.0 (2024-12-06) added "`cleanup-repository` ... to delete unneeded objects from a Nessie repository (backend database)". Later fixes covered "large index processing" and data "written before Nessie version 0.101.0" [read: CHANGELOG.md]. The MongoDB `z` "referenced" timestamp and `deleteWithReferenced` (a conditional `findOneAndDelete`) support this safely under concurrency [read: MongoDB2Persist.java]. The open 2022 issue #5282 is titled "Support cleanup of the TableMetadata (specific to table on a branch) for Nessie managed table" [secondary: issue title from `gh search issues` only; body not read](https://github.com/projectnessie/nessie/issues/5282).
- **Polaris carries the same problem forward.** Its Nessie-derived NoSQL persistence README says: "Despite the beauty of having a 'commit log' ... the size of that database would always grow". It lists three purge problems (memoized metadata, old commit-log entries, then unreferenced objects) and notes "maintenance service not yet in the code base" [read: apache/polaris persistence/nosql/persistence/README.md](https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md).

### Inferences
- [inference] GC is hard across branches because liveness is a property of the whole reference graph, not of one table's metadata. Every branch or tag pins files until a cutoff policy releases it, and the default policy `NONE` keeps everything.
- [inference] Operational cost: a second database, a scheduled multi-phase job needing broad object-store list and delete permissions, bloom-filter sizing per table, and a human review gate. Without user-facing branches, Iceberg's standard per-table maintenance (`expire_snapshots`, `remove_orphan_files`) remains valid and is what engines and managed services already run.
- [inference] Commit-log retention must be designed in from the start: compaction of old commits plus unreferenced-object sweep. Both Nessie (2024) and Polaris (still pending) added it late.

### Gaps
- No published GC runtime or cost numbers (hours per million files, request costs).

---

## 6. Scale and performance evidence

### Takeaway
Nessie publishes almost no quantitative scale data. The only primary statements are qualitative local Docker tests: commit performance does not degrade with commit count, is barely affected by 30k+ keys, and PostgreSQL is fine for "a couple of Nessie commits per second". The operational complaints that do exist are about commit and merge timeouts under contention and with deep history, plus backend-specific throughput traps.

### Cited Findings
- **Storage README** (subjective; "not ... 'proper' performance test results"; no database tuned; nothing clustered):
  - "Commit performance does not degrade with the number of commits."
  - "Commit performance is negligibly affected by a 'huge' amount of keys (30k and more)."
  - The UI and reads respond "instantaneous".
  - The README shows content-generator invocations `-t 5000 -n 1000000` and `-t 300000 -n 200000 --puts-per-commit 10`. I assume `-t` means tables and `-n` means commits, but I did not check the tool's flag definitions.

  [read: GH versioned/storage/README.md](https://github.com/projectnessie/nessie/blob/main/versioned/storage/README.md)
- **Contention starvation.** In the README's "asymmetric operation counts" experiment, under the old model a process with 10 puts per commit always hit commit timeouts against a 1-put process. The new model lets both progress, but "The problem is not 100% solvable ... without introducing a more coordinated 'commit-target-lease mechanism'" [read: same].
- **perftest.** `perftest/gatling` and `perftest/simulations` exist, but their README contains no result numbers [read: raw perftest/simulations/README.md](https://github.com/projectnessie/nessie/blob/main/perftest/simulations/README.md).
- **Complaints:**
  - Merge timeouts under concurrency (#10423).
  - Merge cost growing with history depth on JDBC2, with sequential per-commit fetches (#12843).
  - BigTable silently capped at one gRPC channel, fixed in 2026 (0.108.8).
  - CockroachDB "write too old" errors and Cassandra timeouts (support matrix).
  - DynamoDB's 400 KiB item limit makes it unsuitable for the catalog.

  [read: sources cited in §2 and §4]
- **Polaris sizing assumption.** Polaris's Nessie-derived persistence assumes a catalog of "many thousand, potentially a few 100,000, tables/views/namespaces". It uses a "common hard limit of 350kB" per row and spills the embedded index to separate rows [read: apache/polaris persistence/nosql/persistence/README.md](https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md).

### Inferences
- [inference] Throughput per branch is bounded by the CAS loop on one row and degrades through retries as writers rise. A firm with many engines committing to `main` concurrently (streaming plus batch) will hit this first. Partitioning the CAS target (per catalog or namespace group), or coalescing commits server-side per target, is the main scaling lever. Nessie never built it; the README only describes it as an idea.
- [inference] "Doesn't degrade with commit count" holds for commits but not for merges or any operation that walks history (#12843). History-walking operations need batching and bounded depth.

### Gaps
- No commits/sec, p99 latency, or reference counts from any production Nessie deployment were found.
- No public benchmark of Nessie on MongoDB.

---

## 7. Status as of November 2026

### Takeaway
Nessie is alive but in effective maintenance mode under essentially one maintainer. Releases still ship roughly monthly (0.110.0 on 2026-11-06), but about 81% of commits in the last 12 months are Renovate dependency bumps, and recent changelogs are almost all fixes. Dremio, Nessie's sponsor, moved its product catalog to Apache Polaris, publicly committed to fold Nessie's capabilities into Polaris, now publishes Nessie-to-Polaris migration guides, and itself agreed in May 2026 to be acquired by SAP. SAP's commitments name Iceberg, Polaris and Arrow, not Nessie.

### Cited Findings
- **Repository state** (GitHub API, 2026-11-06): not archived, 1,527 stars, 202 forks, 167 open issues, last push 2026-11-06 [read: api.github.com/repos/projectnessie/nessie](https://api.github.com/repos/projectnessie/nessie).
- **Releases** (GitHub releases API):
  - 0.110.0 (2026-11-06) and 0.109.0 (2026-10-08).
  - 0.108.0 through 0.108.8 (June to September 2026).
  - 0.107.0 through 0.107.9 (January to June 2026).
  - 0.106.2 (2026-09-22, a maintenance-line backport).

  The project is still pre-1.0 [read: api.github.com/repos/projectnessie/nessie/releases](https://github.com/projectnessie/nessie/releases). PR #13063 (2026-09-22) added a maintenance-release publication policy (mainline vs maintenance lines, with a 0.106 backport) [read: github.com/projectnessie/nessie/pull/13063](https://github.com/projectnessie/nessie/pull/13063).
- **Contributors** (GitHub commits API, my count):
  - 2025-11-06 to 2026-11-06: 1,667 commits. renovate[bot] 1,349, release bot 45, `snazy` (Robert Stupp, Dremio) 230, `adutra` 9; no other author has more than 6.
  - 2024-11-06 to 2025-11-06: renovate 1,433, release bot 74, `snazy` 122, `dimas-b` 11, `adutra` 6.

  [read: `gh api repos/projectnessie/nessie/commits`](https://github.com/projectnessie/nessie/commits/main)
- **Changelog character.** I read the entries from 0.105.7 (2025-11-06) to 0.110.0. They are almost all fixes or upgrade notes: catalog S3/GCS credentials, BigTable, GC, Java 17 minimum in 0.107.0. The only "New Features" entries are Helm chart settings (`startupProbe`, BigTable channel pool) [read: GH CHANGELOG.md](https://github.com/projectnessie/nessie/blob/main/CHANGELOG.md).
- **Polaris intent.** Nessie's own blog (2024-08-02): "The intent is to contribute Nessie's capabilities, like Catalog Level Versioning, Git-like semantics, multi-table transaction semantics to Polaris" [read: GH site/docs/blog/posts/2024-08-02-polaris.md](https://github.com/projectnessie/nessie/blob/main/site/docs/blog/posts/2024-08-02-polaris.md).
- **October 2024.** Dremio's catalog was described as "based on Nessie". Dremio VP of Product James Rowland-Jones spoke of plans to "merge the capabilities of Project Nessie into Apache Polaris (Incubating)"; the article noted "The Nessie-Polaris merger has yet to happen" [read: BigDATAwire 2024-10-29](https://www.hpcwire.com/bigdatawire/2024/10/29/dremio-goes-hybrid-with-nessie-based-metadata-catalog/).
- **Dremio's own assessment.** An editor's note dated September 2026 on Dremio's Nessie explainer: "Nessie is still available and self-deployable under the Apache-2.0 licence", "It is not an Apache Software Foundation project", and "its development has slowed considerably". The same page calls Dremio's current product "Open Catalog, built on Apache Polaris" [read: dremio.com/blog/what-is-nessie-catalog-versioning-and-git-for-data](https://www.dremio.com/blog/what-is-nessie-catalog-versioning-and-git-for-data/).
- **Migration guidance.** Dremio published "Migrating from Nessie to Apache Polaris Without Rewriting Iceberg Data" (Alex Merced, 2026-10-27) [read: dremio.com](https://www.dremio.com/blog/migrating-from-nessie-to-apache-polaris-without-rewriting-iceberg-data/).
- **SAP acquisition.** SAP agreed to acquire Dremio on 2026-05-04, with the deal expected to close in Q3 2026. The release says Dremio has been "a leading steward of open-source projects ... Apache Iceberg, Apache Polaris and Apache Arrow, and SAP is fully committed to continuing to invest" in them. Nessie is not mentioned. Dremio's site navigation reads "Open Catalog Powered by Apache Polaris" [read: dremio.com/newsroom/sap-to-acquire-dremio...](https://www.dremio.com/newsroom/sap-to-acquire-dremio-to-unify-sap-and-non-sap-data-to-power-agentic-ai/). Closing was reported in July 2026 [secondary: search summaries incl. vermoegenszentrum.ch / itdaily.com](https://itdaily.com/news/business/sap-finalizes-dremio-acquisition-all-sap-data-bundled/).
- **Nessie's design now lives on in Polaris.** Polaris's NoSQL persistence README describes the same building blocks:
  - "compare and swap' operations on a single row ... are the only requirement";
  - "Named pointers" ("think: Git HEAD");
  - a commit log;
  - an embedded index spilled to separate rows;
  - immutable objects cached without coordination.

  The per-catalog named pointer is `cat/%d/heads/main` [read: apache/polaris persistence/nosql/persistence/README.md](https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md). Polaris's MongoDB backend is "currently in **beta**" [read: apache/polaris site/content/in-dev/unreleased/metastores/nosql-mongodb.md](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/metastores/nosql-mongodb.md). A Dremio post attributes Polaris NoSQL persistence optimizations (PR #4071) to `snazy` [secondary: dremio.com Polaris 1.5.0 deep-dive, via search summary](https://www.dremio.com/blog/apache-polaris-1-5-0-deep-dive-into-the-future-of-open-data-catalogs/).

### Inferences
- [inference] The bus factor is effectively one: a single Dremio (now SAP) engineer, who is also building Polaris's NoSQL persistence. Nessie should not be adopted as a long-lived enterprise platform. It is valuable as a design reference and as a source of failure modes.
- [inference] The migration of Nessie's ideas into Polaris is selective. The persistence machinery (commit log, named pointers, CAS) moved; user-facing git branching is not evident. The Polaris README lists only `heads/main` per catalog, and I saw no merge or branch API. That is a market signal about which half of Nessie was worth keeping.

### Gaps
- I found no formal "maintenance mode" announcement from the Nessie project.
- I did not verify the SAP deal completion from a primary source.
- I did not read Polaris source to confirm whether any branch or merge API is planned.

---

## 8. Lessons: is git-style multi-table versioning worth its cost?

### Takeaway
The evidence splits Nessie in two. The **internal mechanism** is a clear win and is being carried into Polaris: an immutable content-addressed object store, a commit log, and a catalog-scoped pointer advanced by single-row CAS, which gives atomic multi-table commits on any database with a conditional single-row write, MongoDB included. The **user-facing git model** of long-lived branches, merges and transplants is expensive in every dimension measured:
- unbounded history and merge cost (#12843);
- cross-branch GC complexity;
- divergence from Iceberg REST semantics (single snapshot, `table@branch` naming, prefix tricks);
- lossy migration out;
- no named enterprise case studies.

Its main sponsor has stepped back from it.

### Cited Findings
- Atomic multi-table commit plus per-table WAP is now standard without git semantics: REST `/transactions/commit` [read: Iceberg OpenAPI](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml) and Iceberg-native branches and tags [read: Iceberg branching.md](https://github.com/apache/iceberg/blob/main/docs/docs/branching.md).
- Costs observed in Nessie: merge and history walks (#12843, #10423), cross-branch GC (§5), single-snapshot REST responses (§3), and no 1:1 branch migration (§4). All cited above.
- Polaris keeps the commit log and named pointers, with a per-catalog `heads/main` [read: Polaris NoSQL README](https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md).

### Inferences
- [inference] For a firm operating its own catalog, the parts worth building are:
  - catalog-scoped atomic commits;
  - an append-only change log (audit trail, change feed; Polaris's README notes clients can poll the log);
  - immutable catalog-wide tags for regulatory "as-of" reproducibility.
- [inference] User-facing branching should be deferred until a concrete, funded use case exists, and then implemented as short-lived, auto-expiring branches with bounded depth. It is not worth building as a general git model.

### Gaps
- No quantitative cost/benefit data from a Nessie adopter.

---

## Decisions this implies

**D1. Core persistence model**
- **Options.**
  - (a) Nessie/Polaris model: immutable content-addressed objects plus a commit log plus one CAS pointer per catalog.
  - (b) Classic per-table pointer rows (like the Iceberg JDBC catalog), with multi-document MongoDB transactions for `/transactions/commit`.
  - (c) A hybrid: catalog-scoped commit log and CAS pointer, partitioned when hot.
- **Evidence.** Nessie's rewrite away from a single global pointer (§1). The CAS-only requirement works across 7 databases (§2). Polaris adopted the same design (§7).
- **Recommendation.** (a), evolving to (c). Keep the single-document CAS as the commit point; do not rely on MongoDB multi-document transactions. Design the pointer granularity (catalog or namespace group) up front, because per-branch serialization is the throughput ceiling (§6).

**D2. User-facing git branches and merges**
- **Options.** Full Nessie-style branching; catalog-wide immutable tags only; Iceberg-native per-table branches only.
- **Evidence.** Merge timeouts and unbounded history (#12843, #10423). Cross-branch GC burden (§5). REST semantic divergence (§3). Lossy migration (§4). No named enterprise users (§4). Dremio's retreat (§7).
- **Recommendation.** No user-facing branches or merges in v1. Provide:
  1. `/transactions/commit` for atomic multi-table publish;
  2. catalog-wide immutable tags (cheap on a commit log; valuable for regulatory as-of queries);
  3. pass-through of Iceberg-native table branches and tags for WAP.

  Revisit branching only with a concrete use case.

**D3. Java + MongoDB usage pattern** (Nessie's direct lesson)
- **Options.**
  - Copy Nessie's pattern: `insertOne` for immutables with duplicate-key-as-success, plus `updateOne` with an expected-state filter for references.
  - Use MongoDB transactions.
  - Use change streams for cache invalidation.
- **Evidence.** Nessie's MongoDB backend is labelled "production", the README calls it "works fine - probably a good choice", and there are no MongoDB-specific bug reports (§2). Polaris's MongoDB backend is still "beta" (§7). Nessie sets no write or read concern, creates no indexes, and does not guard the 16 MB limit explicitly (§2).
- **Recommendation.** Java + MongoDB is a defensible choice; Nessie is the strongest public evidence that the CAS-plus-immutable-objects pattern runs on MongoDB. Do what Nessie left implicit:
  - set `w:majority`, `readConcern: majority` (or primary reads) for references, and `retryWrites` in code, not only in the connection string;
  - set an explicit per-document size budget well under 16 MB, with spill and stripe logic (Polaris uses 350 kB, which keeps DynamoDB/RDBMS portability);
  - choose the shard key up front (`_id.r` + `_id.i` for objects, `_id.r` (+ `_id.n`) for references, per maintainer guidance);
  - map timeouts to "unknown outcome" and re-read state before retrying, as Nessie's `UnknownOperationResultException` does;
  - avoid compound-`_id` field scans, or add a secondary index on the repository/realm field for maintenance scans.

**D4. Where Iceberg metadata lives**
- **Options.** `metadata.json` only (classic); database only; both (Nessie).
- **Evidence.** Nessie writes `metadata.json` and also stores a parsed snapshot object (`EntitySnapshotObj`) in the database, and reads it back when committing (§3). Dremio's Polaris migration relies on metadata-file pointers (§4).
- **Recommendation.** Both. Serve `loadTable` from the database copy for latency, and always write `metadata.json` for interoperability, disaster recovery and exit. Unlike Nessie, return the full snapshot history and logs in REST responses so engines behave normally.

**D5. Retention, GC and history compaction**
- **Options.** Repository-wide mark-and-sweep (Nessie GC); per-table Iceberg maintenance only; catalog-log compaction plus unreferenced-object sweep.
- **Evidence.** Nessie GC complexity and its review-before-delete guidance (§5). `cleanup-repository` arrived only in 0.101.0. Polaris's maintenance service is "not yet in the code base".
- **Recommendation.** With no user-facing branches, per-table Iceberg maintenance stays valid. Build catalog-log compaction and an unreferenced-object sweep (with Nessie's "referenced timestamp" conditional-delete trick) in v1. Tags pin history explicitly, which maps cleanly onto regulatory retention.

**D6. Throughput and contention strategy**
- **Options.** Accept per-pointer serialization; partition pointers; add server-side commit coalescing per pointer (Nessie's un-built "coordinated commit targets" idea).
- **Evidence.** The asymmetric-commit starvation note (§6), #10423, and the 5 s default commit timeout (§2).
- **Recommendation.** Measure first: load-test the MongoDB CAS round trip at `w:majority` across the intended topology. Design for pointer partitioning, and optionally per-pointer commit queuing on a leader. Make the commit timeout and backoff configurable and observable, which Nessie does.

**D7. Adopt, fork or borrow**
- **Options.** Run Nessie; fork Nessie; borrow design (and possibly Polaris NoSQL modules) into a from-scratch build.
- **Evidence.** One maintainer, 81% bot commits, REST still "experimental", sponsor moved to Polaris, SAP ownership (§7).
- **Recommendation.** Do not adopt or fork Nessie. Borrow its persistence ideas and failure lessons. Track Polaris's NoSQL persistence as the maintained descendant of the same design; this also tests the "Polaris is bloated" premise at module level rather than wholesale.

**D8. Object-store access for engines**
- **Options.** STS credential vending; remote request signing; both.
- **Evidence.** Nessie: signing costs one REST call per S3 request, and vending is "faster and less resource intensive". The 2026 bugs: GCS vending broken, prefix over-grant (§3).
- **Recommendation.** Vend credentials by default; use signing only for stores without STS, and capacity-plan it. Write adversarial tests for prefix scoping (`orders` vs `orders2`) and for object-storage (randomized-prefix) layouts.

**D9. Multi-region HA**
- **Options.** Active-active writes across regions; single write region with read replicas.
- **Evidence.** Nessie servers are stateless, with no inter-node consensus. Correctness rests entirely on the database CAS. Reference caching across instances needs explicit invalidation (§1).
- **Recommendation.** Stateless catalog nodes. Cache immutable objects freely. Cache references only with invalidation or a short TTL. Prefer a single MongoDB primary region with `w:majority` for references (CAS latency is the commit latency). Make the cross-region RPO/RTO trade-off an explicit decision rather than an emergent one.

## Open questions for the enterprise
1. Is the real requirement **atomic multi-table publish** plus **catalog-wide as-of snapshots** (tags), or do teams truly need long-lived branches with merges? Who has a funded use case?
2. What peak commit rate per catalog or namespace is expected (streaming micro-batches, many engines), and what p99 commit latency is acceptable? This sets pointer partitioning.
3. What MongoDB topology is approved and supported internally: on-prem Enterprise Advanced or Atlas, replica-set regions, sharding? Will DBAs sign off on `w:majority` cross-region latency?
4. Which retention obligations (for example SEC 17a-4-style books-and-records retention) apply to catalog history and table snapshots, and how do they interact with GC and history compaction?
5. Which engines must be supported (Spark, Trino, Flink, Snowflake, Dremio, pyiceberg, …), and does each support REST `/transactions/commit`, credential vending, remote signing and idempotency keys?
6. Which object stores are in use, and do they offer STS (vending) or require request signing?
7. What authorization granularity is needed (catalog, namespace, table, column), and is it enforced in the catalog or in an external policy decision point?
8. What is the source of truth if `metadata.json` and the catalog database disagree, and what is the disaster-recovery and exit path (re-registration from metadata files)?
9. Is there appetite to evaluate Polaris's NoSQL/MongoDB persistence modules as a component, even if the Polaris server as a whole is rejected?

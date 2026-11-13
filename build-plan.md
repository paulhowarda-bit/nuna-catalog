# Build plan: Iceberg REST catalog

Drafted 2026-11-06 from `decision-ledger.md` (D1–D37). Where this plan and the ledger disagree, the ledger wins; where the ledger and `reports/Enterprise Iceberg REST catalog plan.md` disagree, the ledger wins. Decision IDs in brackets (e.g. [D9]) point to the ledger row that justifies each item.

**Ground rules for the whole build**
- A milestone is done when its gate runs green, not when the code is written. Every integrity test gets a bite run: break the check it guards, watch the test fail, restore.
- Build and harden on the personal machine; anything that needs work hardware (Oracle RAC/Exadata, real Entra/Ping, real SLOs, DBA-run topologies) is listed explicitly as a work-machine gate [D1, D31].
- Research, the vendored spec and test fixtures live in the repo so the work machine needs no open internet [D1].
- One deployable container image that runs on-prem or in AWS against a self-hosted database; no managed-only services [D36, D36a].
- Greenfield: no legacy-table migration, no federation, no Delta/Hudi generic tables [D34].
- Our own names throughout. No package, class or module mirrors Polaris, Nessie or `org.apache.iceberg`; iceberg-core is used as a library behind one adapter module [D2, D3].

## 1. Choices this plan makes (veto any)

These were not asked during the grilling; they are routine calls made to unblock the plan.

| # | Choice | Why |
|---|---|---|
| P1 | **Gradle multi-module build** (Kotlin build files), with the Gradle wrapper pinned and its checksum set. Amended 2026-11-11 by Paul ("ok then lets switch"); until then it was a Maven build. | Work builds Java with Gradle, and this project is carried there at M9. Paul knows Gradle and does not know Maven. Maven re-ran every module's tests on every build; Gradle skips a module whose inputs did not change, and the bite loop no longer restarts the build tool and rebuilds every upstream module for each patch. Libraries and plugins come from the Maven-format repository work mirrors; only the Gradle distribution does not. The original reason for Maven was wrong: it assumed Maven through the corporate mirror was the easiest path at work, which Paul corrected on 2026-11-10. Gradle does not make the heavy database suites faster: they are slow because of what they do. |
| P2 | **JDK 25 (Amazon Corretto 25)**. | Matches the Corretto 21 already installed. JDK 25 is not installed yet: prerequisite for M0, Paul installs it. |
| P3 | **Spring MVC on virtual threads**, not WebFlux. | Blocking I/O with JDBC/Mongo sync drivers [D4]. |
| P4 | **Hand-written controllers against the vendored OpenAPI spec**, plus a test that every advertised endpoint matches a spec operation. No code generator. | Generated code would add bloat and foreign names; the RCK proves conformance [D31]. |
| P5 | **Iceberg JSON is written by iceberg-core's own parsers inside `catalog-iceberg`**; controllers return the JSON text, so Spring never serializes an Iceberg type. | Keeps the wire format identical to the reference implementation, and keeps it clear of Spring Boot 4's JSON stack: Jackson 3 is Boot's default and Spring is removing Jackson 2 support (M0 finding). |
| P6 | **Flyway for the three SQL targets** (PostgreSQL, CockroachDB, Oracle; YugabyteDB was a fourth until it was dropped on 2026-11-11 [D7]); a small versioned-step runner of our own for MongoDB. | DBAs know Flyway. M0 verifies that the community edition covers every target at the pinned version (F4); if it does not, we write the same small runner for SQL too. |
| P7 | **PostgreSQL is the default development backend.** No in-memory store. | An in-memory store would drift from the invariants [D8]. Unit tests of the commit loop mock the storage interface. |
| P8 | **Testcontainers** for every backend, IdP, object store and Kafka in tests; **kind** (Kubernetes in Docker) for the nightly engine matrix, which deploys the catalog with its Helm chart (docker compose until 2026-11-12). | Docker Desktop 27.1.1 is installed here. |
| P9 | **Package root `keel.catalog`**, group `keel`. | Follows the repo name. |
| P10 | Defaults that the ledger left to the plan: vended credential lifetime 30 min (bounds 15–60 [D21]); idempotency key lifetime PT24H [D10]; server-side commit retry: at most 4 attempts or 2 s [D9]; multi-table cap 100 [D15]; soft-drop window 30 days [D25]; clock-skew guard 60 s [D9, D11]. | Each is configuration, changeable without code. |
| P11 | Branch and tag defaults [D33]: at most 50 open branches per catalog; a branch expires 30 days after its last commit unless renewed (max 180 days); tags never expire, at most 1,000 per catalog; a branch or tag blocks file cleanup for everything it references; merging or deleting a branch releases it. | The ledger asked the plan to set these. |

## 2. Architecture

### 2.1 Modules

| Module | Holds | Depends on |
|---|---|---|
| `catalog-core` | Domain types (names, principals, privileges, pointers, commit records, errors), the commit loop, idempotency, rules layer, authorization decisions, retention/hold logic. No Spring. | `catalog-iceberg`, `catalog-store-api` |
| `catalog-iceberg` | The only module that imports `org.apache.iceberg`. Parses requests, applies updates (TableMetadata.Builder, MetadataUpdate.applyTo), validates requirements (UpdateRequirement.validate), builds view metadata, serializes responses. Exposes our own interfaces. | pinned iceberg-core |
| `catalog-store-api` | The narrow storage interface (2.3) and the shared invariant/fault-injection suite, handed to each backend as a test jar. | — |
| `catalog-store-sql` | PostgreSQL and CockroachDB: one PG-wire implementation with dialect switches [D7]. It refuses a YugabyteDB server, which is not supported (dropped 2026-11-11). Its tests cover PostgreSQL; the cluster harness is handed to `catalog-store-sql-raft` as a test jar. | `catalog-store-api` |
| `catalog-store-sql-raft` | Tests only: the SQL store's CockroachDB suite. Nothing depends on it. | `catalog-store-sql` |
| `catalog-store-oracle` | Oracle including Exadata/RAC: UCP + FAN, Transaction Guard, service affinity [D7]. | `catalog-store-api` |
| `catalog-store-mongo` | MongoDB: multi-document transactions, `w:majority`, primary reads [D7]. | `catalog-store-api` |
| `catalog-objectstore` | Storage profiles; metadata.json write/read with SHA-256; credential vending through STS AssumeRole for S3, MinIO and Ceph; location guardrails [D21, D26, D36a]. | `catalog-core` |
| `catalog-security` | Multi-issuer JWT validation, per-issuer claim mapping to one principal, trusted-engine registry [D16, D17, D17a]. | `catalog-core` |
| `catalog-audit` | Outbox relay, synchronous Kafka publisher, CloudEvents change stream, hash-chained archive writer [D23]. | `catalog-core`, `catalog-store-api` |
| `catalog-server` | Spring Boot app: REST routes, `/v1/config`, error mapping, management APIs (grants, holds, undrop, classification push, engines), startup self-checks. | all of the above |
| `catalog-tests-conformance` | REST Compatibility Kit run plus a response diff against `apache/iceberg-rest-fixture` [D31]. | `catalog-server` |
| `catalog-tests-engines` | Nightly engine matrix in a kind cluster, the catalog deployed by its Helm chart [D31]. | built server image |
| `catalog-tests-load` | Contention, large-metadata and throughput tests [D31]. | built server image |

### 2.2 Commit path

One request, start to finish [D3, D5, D8, D9, D10, D11]:

1. **Authenticate.** Validate the JWT against its issuer's JWKS. Map claims to one normalized principal. If the caller is a registered trusted engine, take the asserted end user too; record both [D16, D17, D17a].
2. **Idempotency.** If an `Idempotency-Key` is present, look it up. Replay a stored success or definite 4xx. If another request holds the key, wait for it. Never replay a 5xx [D10].
3. **Authorize** with one batched check [D18].
4. **Read the pointer** from the primary for (table, branch). Get metadata from the cache keyed by its immutable location; on a cache miss, read the file and verify its SHA-256 (fail closed on mismatch for regulated tables) [D5, D8.3, D26].
5. **Validate and apply.** Check the client's requirements, apply its updates with the builder, then run the rules layer: approved prefixes, property policy, encryption.key-id immutable, retention-hold guard, clock skew. A rule rejection is 400/403, never 409 [D11]. A no-op returns current metadata without writing [D9].
6. **Write the new metadata.json** to a catalog-assigned location and compute its SHA-256 [D5, D28].
7. **One atomic store write.** `commit_id` was generated before the transaction begins. CAS the pointer on (location, version); write the projection, hash, journal entry (principal, engine, requirements, updates), outbox rows and idempotency result in the same transaction [D8.1].
8. **Lost the CAS:** re-read. If the client's requirements still hold, retry from step 5 within the P10 bound; otherwise return 409 [D9].
9. **Ambiguous outcome** (timeout, failover mid-commit): run the backend's ambiguity protocol (2.4). Resolve to success or a definite failure. Only if it is truly unknown return 500 CommitStateUnknown. Never 503, never 409 [D8.4, D9]. A file from an unknown outcome is left for cleanup.
10. **Respond** with the new metadata and, if asked, vended credentials. Credential issuance is published to Kafka before responding; if audit is unavailable, credential issuance fails closed [D23].

### 2.3 Storage interface

Narrow by design [D7, D8.8]. Every operation is one atomic unit on every backend.

- `readPointer(objectId, branch)` — primary-only.
- `commit(CommitBatch)` — CAS on one or more (object, branch) pointers in fixed ID order, plus projection, hash, journal, outbox and idempotency. The same call serves single-table commits (v1), multi-table commits (`/transactions/commit`, built 2026-11-12) and merges (v1.x) [D15, D33].
- `findCommit(commitId)` — the ambiguity lookup; locks the pointer row before looking [D8.4].
- Namespaces: create, drop, load, list children (paged), update properties (CAS on a namespace version).
- Objects (tables and views): create-if-absent, soft drop, undrop, rename, register, list (paged), purge-eligible scan; unregister, and revive by registering an object's own file again (v1.x, built 2026-11-12) [D13].
- Grants: put and revoke (with an outbox row), batch check, list by principal, list by object.
- Idempotency: claim, complete, read, wait, expire.
- Outbox: claim the next ordered batch, mark relayed.
- Governance records: retention classes, legal holds, classifications with provenance, trusted engines, storage profiles and allowed locations.
- Journal: range read by object and time; tags and branches (tables exist from v1, APIs ship in v1.x) [D33].
- `selfCheck()` — refuses to start on unsafe durability settings [D8.2]. `schemaVersion()` and migrate [D8.7].

### 2.4 Logical data model

Mapped to tables (SQL) or collections (MongoDB) per backend. Names are stored twice: normalized for uniqueness, as given for display [D8.6].

| Record | Key fields |
|---|---|
| catalog | id, name, storage profile, KMS key set, legal entity [D28] |
| namespace | catalog id, id, parent id, name (normalized, display), properties, version, dropped-at |
| object | catalog id, id (UUID), namespace id, name (normalized, display), kind (table/view), state (active/dropped; also purged, and unregistered from 2026-11-12), dropped-at, purge-after, location root [D25, D28] |
| pointer | object id, **branch** (`main` in v1), metadata location, version, SHA-256, projection (chunked above the size budget), last commit id [D6, D8.5, D33] |
| journal | commit id, object id, branch, sequence, time, principal, end user, engine, requirements, updates, old and new location; partitioned by time [D8.5] |
| outbox | sequence, time, type, payload (versioned schema) [D23] |
| idempotency | key, principal, request hash, state (in-flight/done), status, body, expires-at [D10] |
| grant | catalog id, securable (catalog/namespace/table/view), group, privilege, granted-by, granted-at [D18, D19] |
| trusted engine | client id, issuer, allowed end-user claim, flags [D17] |
| storage profile | id, store kind (S3/MinIO/Ceph), endpoint, STS role, allowed prefixes [D21, D36a] |
| governance | retention class, legal hold, classification with provenance, regulated flag [D24, D27] |
| ref | catalog id, name, kind (branch/tag), base journal position, expires-at [D33] |
| schema version | backend, version, checksum, applied-at [D8.7] |

### 2.5 Backend specifics

Detail and sources are in `research_notes/Catalog database evaluation/`. What each implementation must get right:

| Backend | Commit | Ambiguity protocol | Self-check refuses to start when |
|---|---|---|---|
| PostgreSQL | `SELECT … FOR UPDATE` on the pointer row, conditional update, inserts, one transaction. | Durability barrier, and no COMMIT timeouts. After a broken connection, look up `commit_id` on a fresh connection once the barrier confirms. The pool never replays a commit (cf. Iceberg JdbcCatalog #18743). | Synchronous quorum replication is not configured, or `synchronous_commit` is off for the catalog role. |
| CockroachDB | Same SQL under SERIALIZABLE; retry 40001 inside the bound. | 40003 (ambiguous result) → `findCommit`. | Replication factor < 3 for the catalog ranges. |
| Oracle / Exadata RAC | Same logical steps; UCP with FAN; route each table's pointer row to one RAC service (affinity by object-ID hash) so hot rows don't move between instances. | Transaction Guard: on a recoverable error take the LTXID and call `DBMS_APP_CONT.GET_LTXID_OUTCOME`. | Data Guard is not in maximum-availability mode with zero apply delay where required, or Transaction Guard is off for the service. |
| MongoDB | Multi-document transaction; `writeConcern: majority`, `readConcern: snapshot`, primary reads. | `UnknownTransactionCommitResult` → retry `commitTransaction`, then `findCommit`. | Not a replica set, default write concern below majority, or journaling off. |

YugabyteDB had a row here until support for it was dropped on 2026-11-11 [D7]. The SQL store refuses a YugabyteDB server.

### 2.6 Shared invariant and fault-injection suite

Lives in `catalog-store-api` as a test jar. Every backend runs all of it; passing it is what "supported" means [D8.9]. Faults are injected with container stop/pause and a TCP proxy between server and database.

1. **CAS linearizability**: N writers from one base, exactly one wins; repeated 1,000 times, only in the overnight run.
2. **Atomicity**: break the connection at every step boundary of `commit`; pointer, journal, outbox and idempotency never disagree. Repeated for every mutation type only in the overnight run.
3. **Ambiguity**: drop the link after COMMIT is sent; the outcome resolves correctly, is never reported as 409, and no acknowledged commit is lost. Repeated for every mutation type only in the overnight run.
4. **Failover**: stop the primary mid-load; every acknowledged commit is present afterwards.
5. **Self-check**: boot against each unsafe setting in 2.5; startup fails with a clear message.
6. **Read-after-commit**: inject replica lag; a load after an acknowledged commit always sees it.
7. **Size**: projections above the size budget chunk and reassemble; journal partitions roll over.
8. **Names**: case-insensitive collisions, display case preserved, deep namespace paths.
9. **Idempotency**: concurrent same-key requests, replay of success and 4xx, no replay of 5xx, expiry.
10. **Upgrade**: migrate schema N→N+1 while nodes on N keep serving.

Each test has a recorded bite run that proves it fails when the check it guards is removed.

## 3. Milestones

Each milestone lists its scope, its gate, and what must wait for work hardware.

### M0 — Bootstrap

- Prerequisites: install JDK 25 (Paul); Docker, Maven, git are present.
- `git init` (private; the machine-wide estate guard will scan every commit, so the first commit of the research files is where any hit surfaces).
- Module skeleton (2.1), Maven wrapper, version pins in one parent POM with checksums recorded.
- Vendor into `spec/`: the REST OpenAPI YAML for the pinned release, the table spec and view spec text.
- Resolve the facts in section 5 and pin versions.
- `catalog-server` boots on PostgreSQL and answers `/v1/config` with an explicit endpoints list [D13].
- **Gate:** `mvnw verify` green; the RCK harness runs end to end (most tests failing is expected here). **Done 2026-11-06.**

### M1 — Storage interface and shared suite (first half of D7b)

- Design the interface (2.3) against all three families at once, so no single database shapes it.
- Write the shared suite (2.6).
- Spike the three hard primitives in each family: pointer CAS with row lock, the ambiguity protocol, the self-check.
- Design review of v1 storage for branches (pointer keyed by (object, branch); ref records) so v1.x needs no migration [D33].
- **Gate:** the suite runs against all three spikes; bite runs recorded.

### M2 — Backends in parallel (second half of D7b)

Three worktree sessions, each owning one module:
- **M2a** `catalog-store-sql`: PostgreSQL, CockroachDB, YugabyteDB. (YugabyteDB was built and passed, then dropped on 2026-11-11 [D7].)
- **M2b** `catalog-store-oracle`: Oracle Free container locally.
- **M2c** `catalog-store-mongo`: three-node replica set.
- **Gate:** each backend passes the full suite with bite runs.
- **Work hardware:** Oracle RAC/Exadata (FAN events, service affinity, Transaction Guard under real failover), DBA-run topologies.

### M3 — Iceberg adapter and commit engine (in parallel with M2, on PostgreSQL)

- `catalog-iceberg` over pinned iceberg-core: all 25 update and 9 requirement types; format v1–v3, including v3 `next-row-id` and encryption keys; new tables default to v3, v2 creatable on request, v4 upgrades rejected [D12].
- The commit loop (2.2) with copied CatalogHandlers behaviour: server retry, stale value → 409, no-op skip [D3].
- Rules layer, with Gravitino's backstop list tested; a backstop stays only if a test shows it catches something the builder misses [D11]. Schema/partition changes on regulated namespaces journaled and alerted, not blocked [D11a].
- Idempotency [D10].
- Golden round-trip tests: request JSON → our path → metadata JSON compared byte-for-byte with the reference output; these are the upgrade ratchet for iceberg-core [D32].
- **Gate:** unit and property tests; RCK table-commit paths pass on PostgreSQL.

### M4 — REST surface v1

- Every v1 route in D13: config, namespaces (paged, HEAD, properties), tables (staged create, register (kept for DR reconcile and tests [D34]), rename, HEAD, drop as soft delete), all view routes, loadCredentials, metrics (204, forwarded to telemetry). Table-load 404 is NoSuchTableException. `/v1/oauth/tokens` returns a fixed error naming the IdP.
- ETag/304 against the pointer (hash of location, snapshot mode, view scope, credential epoch); no 304 near credential expiry; correct `snapshots=refs` including statistics files; parsed-metadata cache keyed by location; alerts on metadata size and snapshot count [D14].
- The full error contract [D9].
- **Gate:** RCK passes and the fixture diff is clean on PostgreSQL, with a stated reason for every RCK test that does not pass. The other four backends are checked in the after-M9 heavy pass.

### M5 — Security

- Multi-issuer JWT with a per-issuer resolver; two Keycloak realms stand in for Entra and Ping; per-issuer claim mapping to one principal [D16, D17a].
- Trusted-engine registry with asserted end users; the identity-less-commit refusal exists as a switch, off by default [D17].
- RBAC: the ladder, inheritance, SoD roles, no superuser, no DENY; list-by-principal, list-by-object, batch checks; grant export for certification [D18, D19].
- Flagged tables (row/column policy) get storage access only for trusted engines; others get 403 [D20].
- Unclassified tables in regulated namespaces denied by default [D27].
- **Gate:** security suite: both issuers, wrong audience/expired/unknown issuer, privilege inheritance, SoD, flagged tables, every denial audited.
- **Work hardware:** real Entra and Ping tenants, the group-overage check.

### M6 — Object storage and credential vending

- Storage profiles; one catalog per profile, KMS key set and legal entity; catalog-assigned short table locations [D28, D36a].
- metadata.json I/O with SHA-256 verification; TLS on every hop including internal; the catalog never calls KMS and stores only wrapped keys [D26].
- STS vending per table, prefix-scoped by session policy, session name/tags carrying principal and request ID, cached for most of its lifetime, refresh endpoint in config; honour delegation `none` for trusted engines [D21].
- Guardrails: allowed locations per profile, overlap index, no client-supplied endpoints, no delete permission except maintenance principals, session policy under 2,048 characters [D21, D25].
- **Gate:** adversarial prefix tests (`orders` vs `orders2`, `..`, encoded paths) against MinIO and Ceph containers; S3 verified in an AWS sandbox account (see F6).
- **AWS sandbox (decided 2026-11-07):** Paul's personal AWS account, opened 2026-11-07 on the Free plan with a USD 100 credit. The plan ends by about 2027-05-07, or sooner if the credit runs out. Tests use synthetic data only. The laptop authenticates through a named CLI profile with short-lived credentials, never root and never long-lived access keys. Paul does the sign-in, and the tests read the profile through the SDK's default provider chain.
  - **The Free plan constrains the setup** (AWS `free-tier-plans.md`, read 2026-11-07). It closes the account six months after sign-up, or when the credits run out, whichever comes first. Joining AWS Organizations upgrades the account to the Paid plan automatically, so a separate member account means accepting Paid.
  - **While on Free:** isolate the tests in one dedicated bucket and one role inside the account.
  - **Decided: stay on Free.** The project moves to work well before the Free plan ends (Paul, 2026-11-07), so the personal account only bridges until then. After the move, the AWS checks run in a work sandbox account (PLT-5).

### M7 — Governance and audit

- Transactional outbox and its ordered relay; synchronous Kafka (`acks=all`) for reads, denials, auth failures and credential issuance, failing closed on credentials [D23].
- SIEM feed and the hash-chained archive (Object Lock on MinIO in tests), ≥5-year retention; grant changes archived too so they can be replayed after a DR event [D23, D37]; CloudEvents change stream.
- Maintenance policy in the catalog (expiry, orphan removal, compaction, manifest rewrite) with inheritance and guardrails: minimum ages, holds, DR freeze, orphan age greater than the DB PITR window. The worker that runs the jobs is v1.x [D30].
- Retention classes, legal hold, commit-time rejection of snapshot removal on held tables, refusal to drop/purge/unregister held tables, snapshot+journal export [D24]. Unregister itself is v1.x; it was built on 2026-11-12 and refuses held and frozen tables.
- Soft drop with 30-day undrop; purge only by the maintenance principal, after the window, rate-limited [D25].
- Classification push API with provenance [D27].
- **Gate:** every mutating route emits its audit record (checked route by route); a Kafka outage blocks credential issuance and nothing else; held tables cannot lose snapshots.
- **Who may call the management routes (decided 2026-11-09).** Each route asks once its target is found, before reading the body; a refusal is a 403 and a `Denial`, like every M5 refusal [D18]. Privileges are M5's and inherit down from the catalog; the two roles that are not grants are checked as M5 checks the engine registry.

  | Route | Needs | Why |
  |---|---|---|
  | `GET .../governance`, `GET .../maintenance-policy`, `GET .../dropped` | describe on the target | Reading tags, holds and policy is seeing the object, as listing it is. |
  | `PUT`/`DELETE .../attributes/*`, `POST .../holds`, `DELETE .../holds/{id}` | manage-tags on the target | The tag steward's duty [D18, D27]. Separation of duties keeps stewards from data changes, so whoever lifts a hold cannot then expire or drop what it protected. |
  | `PUT`/`DELETE .../maintenance-policy` | modify on the target | The policy is the catalog's form of Iceberg's expiry and compaction table properties, which `updateTable` changes with modify. The guardrails (retention class, holds, freeze, PITR) still bound it. |
  | `PUT`/`DELETE .../dr-freeze` | a platform operator | A step of the DR runbook [D37]: running the deployment, not data access. Operators hold no grant, so freezing gives them nothing to read or write. |
  | `POST .../objects/{id}/undrop` | create on the object's namespace | It brings a name back into the namespace, as create, register and rename-into do. |
  | `GET .../objects/{id}/export` | select on the object | It returns the metadata JSON and the commit journal, which `loadTable` gives with select; it vends no credentials. |
  | `POST .../objects/{id}/purge`, `GET /management/v1/maintenance/purge-eligible` | a maintenance principal | Only the maintenance tier purges [D25], and the queue lists objects in every catalog. |

  - D27 now allows manage-tags on an unclassified table in a regulated area, beside describe; otherwise nobody could classify the table that the rule keeps closed.
  - One setting, `keel.security.maintenance-principals`, names the maintenance tier for both purge and deleting vended credentials. It replaces `keel.governance.maintenance-principals` and `keel.storage.maintenance-principals`. A maintenance principal counts only when acting as itself, not through a trusted engine for an end user.

### M8 — Hardening gates

- Nightly engine matrix: Spark, Flink, Trino, PyIceberg, DuckDB, SingleStore — create, write, read, evolve, drop; deep namespaces; v2 and v3 tables [D31, D35]. On 2026-11-12 (Paul) the catalog moved to Kubernetes: the matrix now deploys it with its Helm chart (`deploy/helm/keel-catalog`) into a kind cluster, and runs Spark, Kafka Connect (the Iceberg sink, fed from a Kafka topic), PyIceberg and SingleStore. Kafka Connect replaced Flink; Trino and DuckDB were dropped. Also on 2026-11-12 (Paul): row-level DELETE, UPDATE and MERGE INTO under copy-on-write and merge-on-read, on v2 and v3, written by Spark (which reports the delete files and snapshot operations) and read back by PyIceberg and SingleStore.
- Load: a 10k+-snapshot table; 8–32 concurrent writers on one table with zero 5xx, deadlocks, lost or duplicated commits.
- Resilience: kill the primary mid-commit; rolling upgrade across a schema change; reconcile-from-storage DR drill (freeze cleanup and writes, fast-forward pointers along metadata-log chains) [D37].
- SLO runs (load p99 < 50 ms, 304 p99 < 10 ms, commit p99 < 300 ms, ≥ 500 commits/s); the laptop shows relative behaviour only.
- **Gate:** all of the above green on the personal machine.
- **Work hardware:** absolute SLOs, three-site quorum latency, async DR region.

Since 2026-11-11 every M8 suite is tagged `overnight` and runs only in the overnight job (M9), not in the heavy build.

**Status (2026-11-09): gate met on the personal machine, PostgreSQL only.** Every M8 suite is tagged `heavy`, so `./mvnw verify` stays the everyday fast build. Each suite was run on its own with `-Pheavy -Dtest=…`; the whole `-Pheavy` pass and `bites/run.sh` wait for the after-M9 pass. Bites are recorded in `bites/M8/` (12 patches), not run.

- **What M8 changed in the server.**
  - The server migrates through the store (`SqlCatalogStore.migrate()`), so PostgreSQL reaches V4 (journal partitions) on every start; Spring's Flyway starter is gone.
  - Reconcile from storage [D37]: `POST /management/v1/catalogs/{catalog}/dr-reconcile` `{"apply", "accept"}`, platform operators only. A file counts as committed when a later file names it as predecessor. The pointer moves one version per swap, only through proven files and the tips the operator names in `accept`, and only under a DR freeze. Two proven files at one version, a gap, a foreign UUID, or storage missing the pointer's file stop the walk. Table directories the database does not know are reported as `unknown-locations`.
  - A store outage answers 503 with Retry-After, in the Iceberg error shape. The routes, the idempotency paths and the authentication filter all do this; before, the filter fell through to Spring's error page. It covers a database that cannot be reached, is failing over or is out of connections, when nothing was applied. A commit whose COMMIT was sent and whose outcome the store cannot tell stays `CommitStateUnknownException` (500).
- **Engine matrix** (`catalog-tests-engines`, docker compose, about 11 min; versions, timings and settings in its README). All six pass. Every engine got vended credentials and no storage keys.

  | Engine | v2 and v3 in `m8.deep.<engine>`: create, write, read, evolve, drop | Reads Spark's v2 / v3 / v3 with a deletion vector |
  |---|---|---|
  | Spark 4.1.4 + Iceberg 1.12.0 | pass | writes them |
  | Trino 484 | pass | pass |
  | Flink 2.3.0 + Iceberg 1.12.0 | pass | pass |
  | DuckDB 2.0.0 | pass | pass |
  | PyIceberg 0.12.0 | pass, except v3 data writes, which the client refuses ("Cannot write manifest for table version: 3"; the test asserts it) | pass |
  | SingleStore 10.9.0 | n/a: read-only client [D35] | pass, by pipeline; v3 reads too, though its docs say v1/v2 only |

- **Load and resilience** (`catalog-tests-load`): two server processes from the exec jar, on PostgreSQL 18 with a synchronous standby. Results are in `target/m8/results/`.

  | Suite | Result |
  |---|---|
  | 8, 16 and 32 writers on one table, v2 and v3 | Every commit acknowledged exactly once (200 to 800 per run). Zero 5xx, zero deadlocks, nothing lost or doubled. |
  | 10,120 snapshots, v2 and v3 | History whole; loads, `?snapshots=refs` and 304 work. Metadata is 3.1 MB (v2) and 3.5 MB (v3). Full load p99 186 / 654 ms, refs-only p99 177 / 117 ms, 304 p99 84 / 65 ms. |
  | Kill the primary mid-commit | Standby promoted at 3 s; the first commit landed about 11 s after the kill. Only 503s with Retry-After during the outage, no 500. Every acknowledged commit present exactly once. |
  | Rolling upgrade | Two previous-release servers (75df4ff, schema V3) replaced one at a time, the first new one migrating to V4 under traffic. 296 commits, zero 5xx, every request reached a server, history and journal whole. |
  | DR drill | DR standby cut off, writers kept committing, region lost, DR promoted. Then freeze (commits 403, cleanup off), dry run, apply with the acknowledged tips accepted (30 steps per table), lift the freeze, register the table DR never knew. All 134 acknowledged commits recovered; commits resume. |

- **SLO run (laptop, relative only).** Clients, two servers, PostgreSQL, Kafka and Silo share one machine.

  | Measure | Laptop | Target |
  |---|---|---|
  | Load p99 (16 readers, 32 tables) | 295 ms (p50 135) | < 50 ms |
  | 304 p99 | 164 ms (p50 77) | < 10 ms |
  | Commit p99 (32 writers, a table each) | 1,764 ms (p50 653) | < 300 ms |
  | Commits per second | 43 | ≥ 500 |

  Every load and 304 waits for the read audit's Kafka acknowledgement [D23], which sets a floor on the laptop. The absolute SLOs stay a work-hardware gate.

- **Findings for decision:**
  - **ETags do not survive a change of server.** The ETag includes the credential epoch, a hash of the vended access key. Each server mints and caches its own credentials, so a conditional load answered by another server is always a 200: 0 of 32 in the SLO run. Behind a non-sticky balancer, the 304 path [D14] mostly misses. Two ways to fix it:
    - derive the epoch from a shared issuance window, so servers agree on it;
    - accept sticky routing.
  - **One hot table manages about 4–5 commits a second under contention on the laptop.** Fairness is poor: at 32 writers, p99 time to acknowledgement passes 2 minutes. The server's own retries (P10) each write a metadata file before losing the swap. This is not a correctness problem, but work hardware should measure it.
- **Follow-ups:**
  - The server does not run the store's `selfCheck()` at startup (D8.2).
  - The journal horizon is extended only when migrations run, not by a job.
  - `accept` is assembled by hand; the audit archive could prove tips instead.
  - A register is refused under a DR freeze, so the runbook registers unknown tables after the thaw.
  - Oracle and MongoDB stores do not raise `StoreUnavailableException` yet, so an outage there still answers 500.
  - `findCommit` answers "unknown" at once when the store is down, where it could wait within the retry budget.

### M9 — Carry to work

- Artifact approval in the curated repository (PLT-6).
- RAC/Exadata, real IdPs, real object stores, DBA review of the self-check thresholds.
- Bucket naming per region and replication SLA (PLT-4) for the DR runbook [D29].
- The build at work [P1]: point `settings.gradle.kts` (both `mavenCentral()` lines) at the internal Maven-format repository; every library and plugin resolves from that one repository. The Gradle distribution is the one download that does not: `gradle/wrapper/gradle-wrapper.properties` fetches `gradle-9.9.1-bin.zip` from `services.gradle.org` and checks it against `distributionSha256Sum`, so work needs that zip from an internal location (change `distributionUrl`, keep the checksum). JDK 25 must be installed: Gradle finds it and never downloads one.
- A scheduled overnight job in work CI running `./gradlew build -Pnightly`: the tests tagged `overnight` and the CAS test, nothing else. The `overnight` tests (Paul, 2026-11-11, to shorten the heavy build) are every M8 suite (`catalog-tests-load`: concurrent writers, rolling upgrade, SLO runs, ten thousand snapshots, kill the primary, DR drill), the engine matrix, and the two fault sweeps that repeat a lost answer and a link cut before COMMIT for every mutation type, on the four backends. The CAS test runs at 1,000 rounds on the four supported backends (YugabyteDB, measured below, was dropped on 2026-11-11 [D7]). On 2026-11-11 Paul cut the rounds from 10,000 to 1,000: 10,000 took 23 min on PostgreSQL and 42 min on CockroachDB and never finished on YugabyteDB, and nothing in the plan or design gave a reason for 10,000. On 2026-11-10 Paul took the CAS test out of every build, fast and heavy, because it takes too long; it runs only in this overnight job. It left the heavy pass that day: per round it cost 0.14 s on PostgreSQL, 0.25 s on CockroachDB and 2.2–2.4 s on YugabyteDB, so 10,000 rounds on five backends ran past 10 hours. PostgreSQL and CockroachDB passed all 10,000 rounds then; YugabyteDB passed 5,157 before the run was stopped; Oracle and MongoDB were not reached.

### UI v1 — web console

A browser console for people: browse catalogs, read governance, manage grants, and read an object's recent audit records. The server stays the only place permissions are decided; the console shows every refusal as the server answered it.

**Decisions**

- **Hosting.** `catalog-server` serves the console from `classpath:/keel/ui/` under `/ui/`. There is no separate module or build step: plain HTML, CSS and ES modules, no npm and no third-party code.
- **Public files, private data.** Only the console's flat files (`/ui/`, `/ui/<name>.(html|js|css|json|svg)`) skip authentication. Every API call they make carries a bearer token and goes through the same filter, guards and audit as an engine's.
- **Headers.** Every `/ui` response carries a strict Content-Security-Policy: `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'` plus the IdP's origin when OIDC is set; `base-uri 'none'; form-action 'none'; frame-ancestors 'none'`. It also carries nosniff, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY` and COOP. The scripts build the page with text nodes only; no catalog data reaches `innerHTML`.
- **Sign-in.**
  - Production uses the OIDC authorization code flow with PKCE (S256) against `keel.ui.oidc.{issuer,client-id,scopes,redirect-uri}`. The settings are published, with nothing secret, at the unauthenticated `/ui/config.json`.
  - The access token lives in `sessionStorage` only, never `localStorage` or a cookie, and a 401 signs the console out.
  - Token paste is a development aid, `keel.ui.dev-token-login=true`, off by default.
  - The console reads the token's claims for display only.
- **Browsing uses the Iceberg REST API**, with no second read path. Tables load with `snapshots=all`.
  - Exception: one small route, `GET /management/v1/catalogs`, lists the declared catalog names to any signed-in caller, unfiltered. These are the warehouse names every engine is configured with, and `/v1/config` already tells any signed-in caller whether a name exists.
- **Vended credentials reach the browser.** D21 vends to every caller who loads a table; only a trusted engine may decline with `X-Iceberg-Access-Delegation: none`, and a bite guards that rule. The console asks for `none`, then drops `storage-credentials` and `config` from each load before keeping it, and never shows or stores them. Whether non-engine callers may decline is open (below).
- **Audit trail read.**
  - Route: `GET .../audit?limit=` on a catalog, namespace, table or view (default 50, at most 200). It needs `manage-grants` on the object or above. The trail shows who used the object and who was refused, which is the evidence an access review needs (D19), and separation of duties keeps grant admins away from the data itself (D18). A refusal is audited as a `Denial`, and each read as a new event, `AuditRead` (`keel.catalog.audit-read`, v1: target, number returned, actor).
  - Source: the audit topic itself. Records are keyed by subject, so the route works out the subject's partition with Kafka's default partitioner, reads that partition backwards from its end, at most 20,000 offsets in chunks of 2,000, and keeps the subject's records. The answer is sorted by record time, newest first: the outbox relay publishes changes after the synchronous records made meanwhile, so topic order is not time order.
  - Limits: the answer reports the window it scanned and whether it reached the partition's start. Older records, and any search across objects or people, are the SIEM's job (D23), later joined by the v1.x queryable audit tables. There is no second store.
- **Demo.** `demo/demo.sh up` starts namespace `keel-demo` on Docker Desktop's Kubernetes: PostgreSQL, Kafka, Silo, the catalog (its Helm chart) with the console on 8181, kafbat on 8182, and SingleStore with Studio on 8183. SingleStore replaced Trino on 2026-11-11 (D35's amendment). On 2026-11-12 the demo moved from compose to Kubernetes and took the engine matrix in: one stack (`demo/k8s`), which the matrix deploys on its own or, with `demo/demo.sh engines`, runs its engines against.
  - It loads sample data, written through the catalog by `demo/LakeWriter.java`: deep namespaces, v2 and v3 tables, evolved schemas, a second partition spec, a tag and a branch, a view with two versions, classifications, a regulated namespace, a legal hold and a DR freeze.
  - It syncs both ways: SingleStore's Iceberg pipelines load Iceberg tables into its `lake` database, and the change feed of `ops.orders` (`singlestore-change-feed`, a connector pipeline) commits to `markets.equities.eu.xetra.orders`.
  - It mints a local test key and 7-day tokens for five users in five groups; see `demo/README.md`.

**In v1**

- Catalog picker and a lazy namespace tree with tables and views, a filter box, and deep links (`#c=…&k=…&ns=…&n=…&tab=…`).
- Tables: overview (format version, location, metadata location, UUID, properties), current schema and every earlier one (field ids, types, required or optional, nested types, defaults, fields added since the previous schema), every partition spec and sort order, snapshots newest first with the current one marked and refs beside them, and the metadata log.
- Views: versions newest first with their SQL per dialect, and the schema.
- Governance: DR freeze, hold status, holds, attributes with the level each comes from, and effective values.
- Grants: by object and by group; grant and revoke. Errors, including separation-of-duties refusals, are shown verbatim.
- Audit: an object's recent records, each with its raw JSON.
- Light and dark themes, following the system with a manual override.

**Out of v1**

- Writing governance (tags, holds, freeze) from the console: it reads them only. The management API already does all three.
- Table maintenance, schema changes, drops and any data preview.
- Engine registry and storage profiles.

**Tests and bites**

- `ConsoleTest`, `ConsoleConfiguredTest` and `AuditReadRouteTest` cover the fast-build guards:
  - `/ui` files are served without a token, under the CSP;
  - only console files skip authentication;
  - `config.json` holds no secret, and dev mode is off by default;
  - the files hold no inline code and never write markup;
  - the audit route needs `manage-grants`, a refusal is a `Denial`, each read is an `AuditRead`, and the answer is limited and newest first.
- Five bites in `bites/UI/`, checked with `git apply --check`, not run.

**Open**

- **D21 for people:** should a non-engine caller (the console) be allowed to decline vending, or get no credentials at all? Today it is vended to and discards the credentials client-side. Paul's decision.
- **OIDC against a real IdP:** the PKCE flow works against Keycloak 26.8: the demo signs people in with it by username and password (`demo/k8s/keycloak.yaml`), checked by hand in a browser on 2026-11-12. It is not yet tested against Entra ID or Ping, and no automated test covers it. The IdP's token endpoint must allow CORS from the console's origin. The console's "Sign out" does not end the IdP's session.
- **Catalog names** are listed unfiltered to any signed-in caller (see above). Filter them if a deployment treats names as secret.
- **Audit read scaling:** each read opens a short-lived Kafka consumer. That is fine for people at console rates; it needs a cap if scripts start using it. A quiet object on a busy partition can fall outside the 20,000-offset window, and the console then points to the SIEM.
- **Browser end-to-end tests:** none yet. The screens were checked by hand against the demo (see Status).

### v1.x milestones

- `/transactions/commit`: one bounded transaction, fixed lock order, cap 100, advertised only when truly atomic [D15]. **Done 2026-11-12** (see Status).
- Branches, tags and merge over the v1 storage: REST prefix selects the branch (`trading@dev`), Nessie merge semantics per table, cleanup protects every ref, defaults from P11 [D33].
- Maintenance worker: its own identity, sole delete permission, commits through the catalog; expiry and orphan removal in Java, compaction on Spark [D30]. Design decided 2026-11-12 (D30's amendment), not built.
- unregister, register-view, labels [D13, D27]. **Done 2026-11-12**, with review fixes the same night (see Status).
- queryable audit tables [D23]; DR replication-watermark verifier.

## 4. What runs in parallel

Writers never share files: each parallel stream owns its module.

| When | Here (main session) | Separate worktree sessions |
|---|---|---|
| M0–M1 | Bootstrap, interface, shared suite (sequential: the interface is the contract) | — |
| M2 + M3 | M3 commit engine on PostgreSQL | M2a SQL family, M2b Oracle, M2c MongoDB |
| after M4 | Wiring and review | M5 `catalog-security`, M6 `catalog-objectstore`, M7 `catalog-audit`; each adds its own route package to `catalog-server`; merge order M5 → M6 → M7 |
| M8 | Hardening gates | — |

Each branch is deleted as soon as its work is on main.

## 5. Facts checked at M0

Checked 2026-11-06 against primary sources; details and links in `research_notes/Build plan facts/`.

| # | Result | Consequence |
|---|---|---|
| F1 | Iceberg 1.12.0 (2026-09-29). The RCK is on Maven Central (`iceberg-open-api` `tests` and `test-fixtures` jars). Delegation `none` is only on main. `apache/iceberg-rest-fixture` stops at 1.10.1. | Pinned 1.12.0; spec vendored in `spec/`. The reference diff [D31] runs the 1.12.0 test-fixtures `RESTCatalogServer` in process instead of the stale image. |
| F2 | Spring Boot 4.1.2 (Framework 7.0.10, Security 7.1.2) supports JDK 25. Jackson 3 is the default; Jackson 2 converters are deprecated for removal. iceberg-core 1.12.0 needs Jackson 2.22.2. | Pinned Boot 4.1.2 and Jackson 2.22.2; P5 revised. |
| F3 | JEP 491 removed `synchronized` pinning in JDK 24. pgjdbc 42.7.14, MongoDB driver 5.14.0, Oracle JDBC/UCP 23.26.4.0.0 and HikariCP 7.1.0 are virtual-thread safe. | HikariCP pinned to 7.1.0; the others pin when their backends start. Oracle on JDK 25 is certified only on Oracle's download page: watch in M2b. |
| F4 | Flyway 12.4.0 (Boot-managed) covers PostgreSQL, CockroachDB and Oracle in the open-source edition. YugabyteDB's module is community-built for Flyway 11 only. Liquibase 5 is FSL-licensed. | P6 stands for three SQL targets; YugabyteDB migrations need a spike in M2a. |
| F5 | MinIO community edition is dead: repository archived 2026-02-12, source-only since 2025-10-15, public images gone. CVE-2025-62506 (session credential escalation) was fixed only in the last release. | On-prem MinIO at work means AIStor (commercial) or a fork; Ceph RGW is the open on-prem path. Tests use the `pgsty/silo` fork plus a regression test for the CVE. |
| F6 | LocalStack needs an auth token and enforces IAM only on paid plans; moto ignores session policies. | S3 guardrail tests need a real AWS sandbox account (M6). |
| F7 | Ceph RGW supports AssumeRole for plain users with enforced session policies; `ghcr.io/clyso/ceph-test` runs RGW in one container. | Ceph test container for M6. |
| F8 | Testcontainers 2.0.5 renamed modules (`testcontainers-postgresql`, …) and classes (`org.testcontainers.postgresql.PostgreSQLContainer`). Docker Desktop 27.1.1 is compatible. | Used in M0. |
| F9 | Spark, Flink, Trino 484 and DuckDB 2.0 read and write v3. **PyIceberg 0.12 reads v3 but cannot write it.** SingleStore reads v1/v2 only. Trino nested namespaces: `iceberg.rest-catalog.nested-namespace-enabled`. | See R5. |
| F10 | The Java REST client always asks the server to purge; Spark needs `rest-catalog-purge=true` (Iceberg 1.12.0+) or it deletes files itself. | D25: engine configs set it. |

M0 also found:
- iceberg-core omits an empty `endpoints` list, and the Java client then assumes a legacy default set of 13 endpoints. An explicit list only exists once the server has routes (M4).
- iceberg-core's config builder rejects immutable maps; the adapter copies them.

## 6. Open questions still owned by work

From the report's question list, reduced to those the ledger did not settle, with the milestone that needs each. None blocks M0–M4.

| ID | Still open | Needed by |
|---|---|---|
| CMP-1 | Which legal entities and regimes apply (sets retention lengths beyond the 5-year floor). | M7 |
| CMP-2 | Compliance confirmation of D24 (no table is a required record). | M7 |
| CMP-5 | Who approves a physical purge. | M7 |
| CMP-7 | Whether identity-less commits must be refused on regulated tables (the switch's default). | M5 |
| CMP-8 | Confirmation that engines, not the catalog, may enforce row/column policy [D20]. | M5 |
| SEC-1 | RFC 8693 support at each IdP; claims that tell humans from services; Kubernetes workload identity. | M5, M9 |
| SEC-2, SEC-5 | Which engines go in the trusted-engine registry and who attests to them. | M5, M9 |
| SEC-4 | Access-review export format. | M5 |
| SEC-6 | Maximum vended-credential lifetime; network endpoint restrictions. | M6 |
| SEC-7 | Whether any data class needs a record of every object read (would bring back signing [D22]). | v1.x |
| SEC-8 | KMS key-policy owners per domain. | M6 |
| SEC-9 | Which SIEM and archive; whether notarisation is required beyond hash chaining. | M7 |
| SEC-10 | Whether Snowflake may receive vended credentials, and over which path. | M6 |
| PLT-2 | DBA support commitments for each of the four backends at tier 1. | M9 |
| PLT-3 | On-prem MinIO/Ceph versions and STS support at work. | M6, M9 |
| PLT-4 | Bucket naming per region; replication SLA. | M9 [D29] |
| PLT-5 | AWS accounts and regions holding table storage; STS call spreading. | M6 |
| PLT-6 | Approved artifacts and versions in the curated repository; lead time to add one. | M0, M9 |
| PLT-7 | Who funds and operates maintenance compute. | v1.x |
| ENG-1 | Engines and exact versions in scope; which write; v3 readers. | M8 |
| ENG-2 | Real table counts, load and commit rates. | M8 |
| ENG-4 | Views shared across SQL dialects. | M4 |
| ENG-9 | Which governance catalog, and whether it can push classification events. | M7 |
| PO-1 | A standing team to own the catalog for its life. | before production |

## 7. Risks

| # | Risk | Response |
|---|---|---|
| R1 | Four backends cost roughly three implementations' worth of work (Polaris's pluggable persistence is ~26% of its code). | The shared suite is the gate: a backend that cannot pass it is not supported, and v1 ships without it rather than with a weaker guarantee [D8.9]. |
| R2 | Oracle RAC/Exadata behaviour cannot be tested on the laptop. | Oracle Free covers the logic; RAC items are listed as work gates in M2 and M9. |
| R3 | JSON library conflict between Spring Boot 4 and iceberg-core (F2). | Settled at M0: P5 keeps Iceberg JSON out of Spring; Jackson 2 pinned to 2.22.2 and the dependency tree converges. |
| R4 | MinIO community edition is unmaintained (F5). | Every test that needs S3 runs on one store, chosen per build with `-Ps3`: `silo` (the default, MinIO's model), `ceph` (single container, AWS's role-based model) or `aws` (Paul, 2026-11-12; the 2026-11-07 decision put the tests on Ceph). RustFS was assessed as an alternative (F8). The production on-prem store still depends on what work runs (PLT-3). |
| R5 | Defaulting new tables to v3 breaks v2-only readers (SingleStore) and PyIceberg writers (F9). | Clients can create v2 explicitly [D12]; the engine matrix tests both. Revisit the default if PyIceberg writers are common. |
| R6 | v1 storage commits to branch-ready keys before branches ship. | Design review in M1; the shared suite covers (object, branch) keys from the start. |
| R7 | Laptop performance is not representative. | Relative SLO tracking here; absolute SLOs are a work gate. |
| R8 | Unanswered work questions (section 6). | They change configuration and M5–M7 details, not the architecture. |
| R9 | Oracle's Transaction Guard is not licensed in Free and may need confirming on the work estate. | Without it, findCommit's lock-and-journal path still decides correctly (tested); Transaction Guard only shortens the wait. Confirm licensing before M9. |

## 8. Status

- **M0 done 2026-11-06.** `mvnw clean verify` passes on JDK 25 / Maven 3.10.0: 16 tests, including the config route on PostgreSQL 18, the advertised-endpoints check, the iceberg-core import boundary, a stock Iceberg client probe and the RCK ratchet (149 RCK tests discovered, all NOT_RUN until M4 adds namespace routes). Each guard test was bite-tested: broken on purpose, seen to fail, restored.
- **M1 done 2026-11-07.** The storage interface, the shared suite and the commit core pass on all three families, each rerun independently:
  - PostgreSQL 18 (primary plus synchronous standby): 27 suite tests, 25 bite runs.
  - Oracle Free 23.26.3 with Transaction Guard: 26 tests, 3 skipped by capability (no Data Guard on Free); 23 of 24 bite runs bite, and the one that does not is explained.
  - MongoDB 8.0.34 three-member replica set: 27 tests, none skipped; 27 of 28 bite runs bite, and the one that does not is the expected evidence for the open-create gap.
  - Main finding: every backend can report a commit as visible before it is durable (PostgreSQL's in-flight create, Oracle's Transaction Guard, MongoDB's snapshot reads), so findCommit on all three answers COMMITTED only after a durability barrier. Deviations and the suite changes M2 starts with are in `docs/storage-design.md`.
  - Not run: the nightly CAS profile (then 10,000 rounds).
- **Landed 2026-11-07: M2 prep and the first parts of M3, M5, M6 and M7.** `mvnw install` passes on the merged tree: 729 tests, 3 skipped by capability.
  - M2 prep: the whole storage contract (nine group interfaces, `docs/storage-design.md` section 9) on PostgreSQL, 149 tests; 70 of 71 bite runs bite, and the one that does not showed a redundant barrier, now removed. Oracle (28 tests, 3 skipped) and MongoDB (27) still pass the commit suite.
  - M3 part: the Iceberg metadata adapter in `catalog-iceberg`, 160 tests including 80 golden cases. The commit loop, rules layer and idempotency are not built yet.
  - M5 part: token validation in `catalog-security` and principal types in `catalog-core`, 46 tests, 28 bite runs.
  - M6 part: metadata file IO and location guardrails in `catalog-objectstore`, 156 tests, 19 bite runs.
  - M7 part: audit events, the Kafka publisher and the hash-chained archive in `catalog-audit`, 144 tests.
- **M3 engine landed 2026-11-08, on PostgreSQL; the RCK part of its gate waits for M4.** The commit loop of 2.2, the rules layer and idempotency are in `catalog-core` (`keel.catalog.core.commit`). The loop reaches object storage only through a `TableFiles` port, which `catalog-objectstore` implements; the AWS SDK pin moved to the parent pom.
  - Tests: catalog-core 103. They are unit tests, tests on PostgreSQL through the storage interface with injected lost and failed commits, and seeded property tests of concurrent writers. catalog-iceberg has 193 tests, 31 of them for the backstops. 48 of 48 bite runs bite.
  - Rules: each of the five D11 rules and eleven correctness backstops has a test that the builder alone fails. Three more checks from the backstop list are left to the builder, which already makes them. iceberg-core already refuses a snapshot more than a minute ahead on main, so the clock-skew rule matters for snapshots added off main.
  - Not done: the RCK table-commit paths run through REST routes, so they wait for M4; the baseline is still 149 NOT_RUN. Three RCK view tests that move a view's location will conflict with D28.
- **M2c done 2026-11-08: the whole storage contract on MongoDB 8.0.34** (three-member replica set, schema step 3). 155 suite tests, none skipped; 43 of 50 bite runs bite. Of the 7 that do not, 2 showed redundant code, now removed, and 5 are explained in the M2c deviations of `docs/storage-design.md`. With no shared locks, a write marks the records it depends on; outbox order is a Lamport clock.
- **M2b done 2026-11-08.** The whole storage contract runs on Oracle Free 23.26.3 in `catalog-store-oracle` (Flyway V3 to V5): 168 suite tests, 3 skipped by capability (no Data Guard on Free).
  - 56 of 69 bite runs bite in the first batch. Four that do not are explained by Oracle's behaviour (a rollback after a row lock waits for the redo; short previous-release transactions).
  - The other nine showed checks no shared-suite test reached. Each now has a shared-suite test, which PostgreSQL, MongoDB and Oracle pass, and a second batch bites 13 of 13.
  - Main findings:
    - Interval partitioning creates each month's journal partition inside its first commit (0.5–2.6 s here). V5 adds a daily Scheduler job that creates this month's and the next two ahead.
    - `NLS_SORT` follows the JVM locale and breaks byte-ordered paging; a new self-check finding catches it.
  - Deviations and the suite gaps are in `docs/storage-design.md`.
- **M2a done 2026-11-08.** The whole storage contract runs on CockroachDB v25.4 and YugabyteDB 2025.2 (three-node clusters) in `catalog-store-sql`, and PostgreSQL gained journal partitioning (V4): PostgreSQL 163 suite tests, CockroachDB 159, YugabyteDB 156, none skipped, M2b's follow-up suite tests included.
  - 36 of 39 bite runs bite. Three do not: the piece width is never read, the test cluster's nodes share one clock, and YugabyteDB's default collation is already C.
  - Main findings:
    - A COMMIT stalled by a lost quorum can be lost on both, so the shared suite gained `stalledCommitCanBeLost()` (additive; every backend passes).
    - An unlicensed CockroachDB cluster attaches a license notice to COMMIT, then fails statements after seven days.
    - YugabyteDB schema steps run outside a transaction, with Flyway's session lock, so indexes build online.
    - A yugabyted cluster needs deployment checks before it survives a node loss (every master known, no under-replicated tablet).
  - Deviations 1–10 are in `docs/storage-design.md`.
- **M4 done 2026-11-08, on PostgreSQL; the other four backends wait for the after-M9 pass.** Every v1 route of D13 is in `catalog-server` (`keel.catalog.server.rest`): config, namespaces (paged, HEAD, properties), tables (staged create, register, rename, HEAD, soft drop), all view routes, loadCredentials, metrics (204, to telemetry) and a fixed `/v1/oauth/tokens` error naming the IdP. M5, M6 and M7 replace one bean each (callers, credential vending, metrics and alerts) and add their own route packages.
  - Loads (D14): the ETag hashes the metadata location, snapshot mode, view scope and credential epoch, and a 304 is decided on the pointer alone. No 304 inside the credential refresh margin. `snapshots=refs` drops unreferenced snapshots with their statistics files, where the reference fixture answers 400. A parsed-metadata cache is keyed by location, and alerts fire on metadata size and snapshot count.
  - Errors (D9) and idempotency (D10): 400s from iceberg-core keep the reference's error type, so the Iceberg client rethrows IllegalArgumentException as itself. A reused Idempotency-Key with a different request is a 400 and is not recorded. A failed regulated hash is a 500. An unknown commit outcome is read back, and a 500 says the change may or may not have been applied.
  - RCK on PostgreSQL and Silo: 133 of 160 pass, up from 149 NOT_RUN. Each of the other 27 has a reason in `catalog-tests-conformance/rck-reasons.txt`:
    - 10 are aborted or skipped by the kit itself: it hard-codes support for empty namespaces, nested namespaces and names with slashes to off, and one test is @Disabled upstream. Nesting works here (D28) and RestRoutesTest covers it.
    - 7 concurrent replaces fail on v3 tables (D12): iceberg-core refuses the losing replace's stale first-row-id. All 7 pass in a diagnostic run with v2 tables.
    - 5 follow from D28: three view tests move a view's location, and two replaces ask for a file: location that the client writes to before the catalog can refuse.
    - 4 follow from D13 and soft drop: register-view is v1.x, and a dropped table's file cannot be registered again (undrop brings it back).
    - 1 test deletes the metadata file through java.nio, which cannot reach s3://.
  - D28 decision on the three RCK view tests: keep refusing location moves (400); the tests are recorded as failing by decision, not skipped.
  - Fixture diff (D31): one scripted run against our server and the 1.12.0 reference `RESTCatalogServer` in process, both on Silo, compares statuses, error types and response keys. The 7 differences are each tied to a decision: v3 metadata (D12), a 400 where the reference answers a 500 HTML page (D9), the oauth error (D13), and a table's namespace must exist.
  - Found and fixed on the way: the D11 backstop refused a new required field without a default even in a replace that starts main afresh, where the reference accepts it.
  - Bites: 7 recorded in `bites/M4/`, not run.
  - Not verified: the four other backends, the heavy and nightly profiles, the bites, and real credential vending (M6) behind the expiry rule, which a test stand-in covers.
- **M5 done 2026-11-08, on PostgreSQL.** Every request but `/v1/oauth/tokens` needs a bearer token from a configured issuer, and every route asks the authorizer before its work. Token validation, the authorizer and separation of duties are in `catalog-security`; the filter, route guards and the management API are in `catalog-server` (`keel.catalog.server.security`, `rest.Guard`).
  - Issuers (D16, D17a): each issuer has its own JWKS, audiences, algorithms and claim mapping, chosen by the token's allow-listed `iss`. Two Keycloak 26.8.2 realms stand in for Entra ID (`oid`, `groups`, `azp`) and Ping (`uid`, `memberOf`, `client_id`) and name the same directory user as one principal.
  - Trusted engines (D17): a registered engine may name its end user in two headers, and authorization then decides for the end user. The identity-less-write refusal is `keel.security.refuse-identity-less-commits`, off by default; when on, it covers write and modify in regulated chains. The registry is at `/management/v1/engines`, for platform operators only.
  - RBAC (D18, D19): the ladder (modify > write > select > describe; create, manage-grants and manage-tags apart), inheritance from catalog through namespaces to objects, grants to groups only, no superuser and no DENY. Separation of duties: grants conflict with every other duty, data with tags, nobody grants to their own group, and configured grant-admin and platform-operator groups receive no grant. The management API grants, revokes, lists by group and by object, checks in batch and exports for certification, on top of the store's grant group.
  - Governance rules: a flagged table (row/column policy) gives storage access (load, credentials, commit) only to trusted engines (D20); a table in a regulated chain with no classification of its own is refused anything above describe (D27).
  - Gate: `SecurityGateTest` (local test issuer) covers wrong audience, expired, unknown issuer and missing tokens, inheritance, SoD, flagged and unclassified tables, the switch, end users and the grant and engine APIs; `TwoIssuersTest` covers both Keycloak issuers, another audience and an untrusted realm. Every 401 and 403 is audited: denials and authentication failures go through the `AuditPublisher` seam, a test sink in these tests (round-tripped through `EventCodec`), and the suite checks one event per refusal. `catalog-audit` gained `EngineChange`, and `Denial.securable` became nullable for registry refusals.
  - RCK: 133 of 160 pass with authentication on, unchanged from M4. `./mvnw verify` passes: 854 tests. The conformance server runs a local test issuer, and the RCK and Iceberg client send its token (the `token` catalog property); the fixture diff sends it to our side only.
  - Bites: 19 recorded in `bites/M5/`, not run.
  - Open: real Entra and Ping tenants and the group-overage check (work hardware); SEC-1 (the end-user headers are a stopgap), SEC-2, SEC-4 (export format), CMP-7, CMP-8; the SoD check and the grant write are not atomic; HEAD and loads answer 404 before 403, so names leak to authenticated callers; allowed-read audit (ObjectRead) is M7's.
- **M6 done 2026-11-08, on PostgreSQL, Ceph, Silo and real AWS S3/STS (2026-11-09).** Object storage and credential vending are in `catalog-objectstore`, wired in `catalog-server` (`keel.catalog.server.storage`).
  - Profiles and catalogs (D21, D28): `keel.storage.profiles.<name>` declares each store (kind, endpoint, region, table root, allowed prefixes, STS role) and `keel.storage.catalogs.<name>` binds a catalog to one profile, KMS key set and legal entity. At startup the server refuses overlapping prefixes and plain-http endpoints, records profiles and creates declared catalogs through the store with a `storage-setup` audit event, and refuses to start if the store binds a declared catalog differently. Each catalog's commits write through its own profile.
  - Overlap index (D36a): prefixes are disjoint across profiles, and every new or registered table sits at `<table root>/<id>`, so no two tables' locations nest. Clients may not set or change file IO properties (`s3.*`, `client.*`, `io-impl` and the like), so no client-supplied endpoint, proxy or credential reaches another engine.
  - Vending (D21): AssumeRole per table with a session policy on `<location>/*` and listing on `<location>/`, at most 2,048 characters; session name and tags carry the principal, end user, engine, request id, catalog and table. Lifetime 30 minutes by default (15 to 60). Credentials are cached per caller, engine, access level and table, and renewed a minute before the refresh margin, so a load normally carries credentials a client may cache. The load config carries the endpoint, region, path style and `client.refresh-credentials-endpoint`; the same credentials are in `storage-credentials`. Writers get get/put/multipart; only principals in `keel.security.maintenance-principals` (`keel.storage.maintenance-principals` until the M7 authorization follow-up) get delete. A trusted engine that sends `X-Iceberg-Access-Delegation: none` gets nothing; anyone else is vended to.
  - Audit (D23): each new credential set is published as a CredentialIssue before it is first handed out; if that fails it is thrown away and the load, credentials route or staged create answers 503. A commit that already succeeded answers 200 without credentials. It goes through M5's `AuditPublisher`, which logs until M7 wires Kafka; the tests use a recording publisher that can be made to fail.
  - Expiry and ETags (D14): the credential epoch is a hash of the access key id, and the expiry rule now runs on real STS expiry. `VendingNearExpiryTest` backs the M4 stand-in: with credentials that always expire inside the margin, no load carries an ETag or answers 304.
  - KMS and TLS (D26): the catalog has no KMS client and sends no encryption header (a boundary test checks both); encryption at rest is the bucket's default key, and the catalog records only the key set's name. Profile endpoints must be https unless `keel.storage.require-tls=false`, which only local tests set; STS for on-prem stores goes to the same endpoint.
  - Gate: the same 11 attacks pass on Ceph RGW (STS role allowing `s3:*`, so the session policy alone confines), on Silo, and on real S3 and STS (`AwsVendingTest`, run 2026-11-09 in Paul's personal account 397916376085 through profile `keel-sandbox`; it removed its two buckets and role): sibling with a common stem, the bare prefix object, wider listings, copy from a sibling or another bucket, bucket actions, delete without maintenance, write with read access, and 14 raw paths (dot segments, encoded and double-encoded dots and slashes, backslashes, overlong UTF-8, full-width dots, NUL), each signed as sent and re-signed for the decoded path when the store decodes first. Nothing escapes: Ceph keeps `..` as literal key text inside the prefix, Silo refuses dot segments with 400. On MinIO an object named exactly `t/orders` hides every key under `t/orders/` from listings, even for root.
  - RCK on PostgreSQL and Silo, now with vended credentials: 133 of 160 pass, unchanged.
  - Bites: 18 recorded in `bites/M6/`, not run.
  - AWS sign-in: `AwsVendingTest` is heavy and runs only with `-Dkeel.aws.profile`. Profile `keel-sandbox` uses `credential_process` over an `aws login` session (`keel-login`) of IAM user `paul-admin`; IAM Identity Center would need Organizations and the Paid plan. Sign in with `aws login --profile keel-login --remote` and open the link in a private window, or the browser's other AWS session (another Organization's account) is used instead.
  - Not verified: the four other backends, the heavy and nightly profiles, and the bites.
  - Open: per-request SSE-KMS keys and a KMS condition in the session policy (SEC-8); a per-profile CA bundle (the JVM trust store serves today); TLS from clients to the catalog and from the catalog to the database is deployment configuration; read-only vending for callers with only SELECT (done 2026-11-12: see the entry at the end of Status); nothing administers profiles or catalogs at runtime beyond startup declarations.
- **M7 done 2026-11-09, on PostgreSQL.** Governance and audit are in `catalog-audit` (relay, change stream, archive, grant replay) and `catalog-server` (`keel.catalog.server.governance`: audit runtime, audit trail, management API under `/management/v1`). Hand-over: `m7-audit.md` in the session notes.
  - Audit (D23): every change's event goes to the outbox in its own transaction; one relay at a time drains it in order to the audit topic (acks=all), and object and namespace changes also to the CloudEvents change topic. Reads, denials and auth failures are published before the response but never fail it (fallback log while Kafka is down); credential issues fail closed with 503.
  - Archive: one hash chain per audit partition in an Object Lock bucket (COMPLIANCE, at least 1827 days, refused otherwise), written with If-None-Match and resumed exactly once from the last segment's offset; the Kafka offset metadata anchors the head outside the bucket. Verified on Silo. Grant changes replay from the archive through `GrantReplay` (library only, no route).
  - Governance: holds, retention classes, the classification push API with required provenance (D27), the maintenance policy with key-by-key inheritance and guardrails (D30), and the DR freeze (D37), stored as two new `GovernanceAttribute` kinds with no schema step. A retention class's minimum snapshot age and the freeze are commit rules; the freeze also refuses drop, rename, undrop and purge.
  - Lifecycle (D25): undrop until purge-after; purge only by `keel.security.maintenance-principals` (`keel.governance.maintenance-principals` until the follow-up below), after the window, at most `purges-per-minute` per server, never held or frozen; snapshot+journal export.
  - Gate, on PostgreSQL, Silo and Kafka: `MutatingRoutesAuditTest` reads every POST/PUT/DELETE mapping from the server and fails for one without a case; each case's record is found on the audit topic by request id (metrics is exempt: never stored). `KafkaOutageTest` pauses the broker: credential loads answer 503, while plain loads, commits, creates, drops and governance changes succeed and their records arrive once it is back. `HeldTablesTest`: a hold on the table or its namespace refuses snapshot removal, drop and purge.
  - Merged with M5 and M6: their `AuditPublisher` seam publishes through M7's audit trail (a credential issue fails closed, M6's vending hands nothing out); the route-by-route test also covers M5's grant and engine routes; M7's tests sign in with M5's test issuer and use M6's vending on Silo. The conformance server now runs a Kafka broker, so the RCK runs with audit on.
  - `./mvnw verify` green: 978 tests. RCK still 133 of 160.
  - Bites: 14 recorded in `bites/M7/`, checked with `git apply --check`, not run.
  - Not verified: the four other backends, the heavy profile, the bites, and real S3 Object Lock. Closed by the follow-up below: the management routes ran no authorization check, and there were two maintenance-principal settings.
- **M7 authorization follow-up done 2026-11-09, on PostgreSQL.** Every M7 management route now asks M5's authorizer, or checks the platform-operator or maintenance role, per the table in the M7 section; the two maintenance-principal settings are one.
  - Tests: `ManagementAuthorizationTest` sends each route first as a caller that holds something but not what the route needs (403), then as one that does. `MutatingRoutesAuditTest` now also refuses every mutating management route, M5's grant and engine routes included, and finds each `Denial` on the audit topic.
  - `./mvnw verify` green: 995 tests. RCK still 133 of 160.
  - Bites: 14 new in `bites/M7/`, one per new guard; `purge-by-anyone` and `provenance-optional`, M5's `unclassified-open` and M6's `delete-for-everyone`, `cache-shared-across-callers` and `renewed-inside-margin` regenerated for the changed code; `bites/M4/near-expiry-304.patch` removed, since M6's `real-near-expiry-304` covers its rule on the rewritten code. Checked with `git apply --check`, not run.
- **Heavy pass done 2026-11-10, after M8 and before M9.** `./mvnw install -Pheavy`: 1,742 tests on all five backends, 0 failures, 1 h 52 min (SQL stores 50 min, load 21, MongoDB 19). Skipped: Oracle 3 (no Data Guard on Free) and `AwsVendingTest` 11, run separately against the `keel-sandbox` account: 11 of 11.
  - Bites: all 85 in `bites/M2`–`bites/M8` bite, each in its named test. Four needed fixes: `bites/run.sh` now runs to `verify`, since below package the load tests copied the unpatched server jar from `~/.m2` (both M8 load bites then bit); `auth-filter-outage-escapes` was regenerated (it did not compile); `change-stream-unfiltered` now names `ChangeStreamTest#fansOut`; `grants-reach-across` needed a new test, `AuthorizerTest#aBatchCheckGivesEachTargetOnlyTheGrantsOnItsOwnChain`, since the chain filter matters only in a batch check.
  - The CAS test (then 10,000 rounds; 1,000 since 2026-11-11) left the pass for M9's overnight CI job (see M9).
  - Finding: on YugabyteDB, writers queued on one pointer row are released about 200 ms apart (PostgreSQL: under 2 ms), so 8 contending writers take 2.3 s a round. Not the store's backoff (no backoff sleeps) and not fsync (the winning COMMIT takes 10 ms); lowering `wait_queue_poll_interval_ms` and `wait_for_relock_unblocked_txn_keys_ms` to 10 ms cut it only to 2.1 s. Cause open.
- **UI v1 done 2026-11-10, on PostgreSQL** (section 3, UI v1). The console is served at `/ui/` by `catalog-server` (`keel.catalog.server.ui`, files in `src/main/resources/keel/ui`), with one new read route, `GET .../audit`, and one new audit event, `AuditRead`.
  - Tests: `ConsoleTest` 6, `ConsoleConfiguredTest` 2 and `AuditReadRouteTest` 6 pass; `EventCodecTest` covers `AuditRead`.
  - Checked by hand against the demo (`demo/demo.sh up`), signed in as `ana` and `gary`, light and dark:
    - the deep tree;
    - the v2 and v3 table tabs: three schemas with added fields marked, two partition specs and the sort order, seven snapshots with the current one, a tag and a branch, the metadata log;
    - the view's two versions with their SQL;
    - governance: a hold, the DR freeze, an inherited owner;
    - the D27 refusal of `accounts`;
    - grant and revoke, grants by group;
    - a separation-of-duties refusal shown verbatim;
    - `ana`'s 403s on grants and audit, and `gary`'s 403 on loading data;
    - the audit trail with reads, denials, a credential issue and commits.
  - Bites: 5 recorded in `bites/UI/`, not run.
  - Not verified: OIDC sign-in against a real IdP, the other four backends, and the heavy profile.
- **Heavy build speed-up landed 2026-11-11, not proven green.** Changes, each measured:
  - The CAS test runs in no build, fast or heavy: only `-Pnightly`, on its own, at 1,000 rounds (Paul, 2026-11-10 and 2026-11-11). In the heavy pass it cost 81 s on CockroachDB and 11 min on YugabyteDB at 200 rounds.
  - Raft harness: the connection a lost-answer, lost-COMMIT or stall fault struck waits 5 s, not 15. CockroachDB suite 745.7 s → 561.9 s (CAS dropped included); its 21 lost-answer cases 15.3 s → 5.4 s each.
  - `-T 2`, and the CockroachDB and YugabyteDB suites moved to `catalog-store-sql-raft`, which nothing depends on. Peak Docker memory 5.5 GB of 8.
  - Flyway's migration lock wait is 5 min, not 50 s: YugabyteDB's `migrateWhileThePreviousReleaseServes` failed twice under load without it (bite `bites/M8/migration-lock-no-wait.patch`). It passed in the next run.
  - Raft harness: a cluster counts as started once its catalog database answers through the link.
  - Not proven: none of the three `-Pheavy` runs on the new layout finished green, so whoever runs `-Pheavy` next is testing it. Their wall clock was 47 min and 51 min (1 h 52 min before), but both stopped early on a failure, so `catalog-tests-load` (21 min serially) never ran.
    - Run 1: the Flyway lock (since fixed).
    - Run 2: overlapped the keel-demo stack starting. CockroachDB's first connection and Keycloak's start (`TwoIssuersTest`) timed out.
    - Run 3: YugabyteDB's `exactlyOnePropertiesUpdateWins` could not connect (`StoreUnavailableException`), then the log stops partway through the YugabyteDB suite.
  - Open: YugabyteDB connections fail under `-T 2` load; the next heavy run should check it is not the layout.
- **YugabyteDB dropped 2026-11-11** (D7 amended; asked whether to drop it, Paul answered "Drop it"). The supported backends are four: PostgreSQL, CockroachDB, Oracle and MongoDB.
  - Why: it took 33 of the heavy build's 112 minutes; writers queued on one pointer row are released about 200 ms apart, and the cause was never found; it failed in all three `-T 2` heavy runs. The cost: CockroachDB, under a proprietary licence since 24.3, is the only distributed-SQL backend left.
  - Removed: the store's YugabyteDB entry and its switches, the `yb.*` self-check, `YugabyteCluster`, `YugabyteStoreSuiteTest`, `JournalTabletSplits` and the image property. They are in git history, and the shared suite could re-qualify the backend later.
  - Flyway's `flyway.postgresql.transactional.lock` setting went too. Only YugabyteDB needed the session lock (M2a deviation 10): PostgreSQL always got Flyway's default, and Flyway's CockroachDB support takes a lock row and does not read the setting.
  - The SQL store refuses a YugabyteDB server (a `version()` text holding `-YB-`) before any schema step or write, where it would otherwise run on it as PostgreSQL: `UnsupportedBackendTest`, bite `bites/M8/yugabyte-taken-for-postgresql.patch` (recorded, not run).
  - Landed on the fast build, which tests PostgreSQL only. The CockroachDB suite compiles against the trimmed harness and has not run since; the next `-Pheavy` run tests it.
  - The YugabyteDB results and findings in the entries above stand as measured. This closes the open item on YugabyteDB connections under `-T 2`; whether the `-T 2` layout itself runs green is still not proven.
- **Build moved from Maven to Gradle 2026-11-11** (P1 amended; Paul: "ok then lets switch"). Checked on the fast build only: no heavy test, no CAS test and one bite were run.
  - For a reader: `./gradlew build` is the fast build, `./gradlew build -Pheavy` the heavy one, `./gradlew build -Pnightly` the overnight CAS run. One module's tests: `./gradlew :catalog-core:test`; one class: add `--tests CommitEngineTest`. Output is in `build/` where it was in `target/`. A test option is `-Pname=value` where it was `-Dname=value` (`rck.record`, `golden.record`, `engines.keep-stack`, `keel.aws.profile`). `bites/run.sh` and `demo/demo.sh` run Gradle; patch headers are unchanged. No `pom.xml`, `mvnw` or `.mvn/` is left. The entries above name Maven commands as they were run then.
  - Gradle 9.9.1 through the wrapper, with `distributionSha256Sum` set to the checksum gradle.org publishes; a wrapper given a wrong checksum refused the download. Libraries and plugins come from Maven Central alone: a build from an empty Gradle home asked `repo.maven.apache.org` for everything and `services.gradle.org` only for the distribution.
  - Same tests: 1,044 tests in 107 classes, with the same count in every class as `./mvnw verify` on the same commit, and the RCK outcomes line for line (133 of 160 pass). Maven took 7 min 49 s; Gradle from clean 6 min 20 s.
  - Same tag rules, checked without running a test (Gradle's `--test-dry-run`, which lists a parameterized test once): the fast build selects 594 test methods, `-Pheavy` 967, `-Pnightly` 4 (the CAS test on the four backends, at 1,000 rounds) out of 971, and each list is exactly what the `@Tag`s in the source say. Every recorded bite's `Test:` line selects tests under Gradle (85 distinct lines for 92 patches).
  - Same server: the runnable jar holds the same 111 libraries. The 104 from outside are byte for byte the same files, and every class of ours is byte for byte what Maven compiled. No class is in two libraries, so their order in the jar cannot matter.
  - Test classpaths differ in four test-only libraries, where Gradle takes the highest version asked for and Maven took the nearest: `error_prone_annotations` 2.49.0 for 2.41.0 in `catalog-audit`, and `commons-io` 2.20.0 for 2.16.1, `commons-compress` 1.28.0 for 1.26.1 and `snappy-java` 1.1.10.7 for 1.1.10.4 in `catalog-tests-conformance`. JUnit's `apiguardian-api` (and `jspecify` in the store modules) is on the compile classpath only.
  - Dependency convergence is checked on the compile and runtime classpaths, as the Maven enforcer rule did: that rule leaves test scope out unless told otherwise.
  - Java 25 comes from an installed JDK that Gradle finds itself; it downloads none. Nothing has to be set: from a shell with Java 21 first and no `JAVA_HOME`, every module compiled with JDK 25.
  - Two tasks at a time (`org.gradle.workers.max=2`), so two test JVMs at most: sampled every 3 s through a clean build, never more than two.
  - The local build cache is on, and a module whose inputs did not change is not tested again. Measured: clean build 6 min 20 s; again with no change 13 s; after one added line in `catalog-server` 3 min 6 s (its tests and the conformance tests ran, ten modules' did not); the same commit in a second, fresh checkout 11 s (every test result came from the cache). The CAS run is never reused.
  - Under OneDrive Gradle needed one fix. OneDrive marks new folders read-only and Java cannot delete those, so the second test run in a checkout, a result reused from the cache, and `clean` all failed with "Unable to delete directory" (on JDK 21 as on 25). The root build file clears the mark before each task. It uses a hook Gradle has deprecated; nothing else runs early enough.
  - Seen once and not reproduced: a build failed with "Cannot snapshot …: not a regular file" on a class file OneDrive was taking in at that moment. The next run passed, and 40 rebuilds straight after a clean compile all passed.
  - Not run under Gradle: `-Pheavy`, `-Pnightly`, the engine matrix, the demo stack, and every bite but `bites/M8/yugabyte-taken-for-postgresql.patch` (it bites: 2 of 3 tests fail). So the load and engine tests' paths to the server jar, and the 4 GB test heap under heavy load, are untested. The rolling-upgrade test still builds the previous release (`75df4ff`) with that release's own Maven wrapper.
- **Load, engine matrix and per-mutation fault sweeps moved to the overnight run 2026-11-11** (Paul: "Can you plan to move more tests to the overnight run", then "Go ahead"). A new tag, `overnight`, on every `catalog-tests-load` suite, `EngineMatrixTest`, and the two `MutationSuite` sweeps that repeat a lost answer and a link cut before COMMIT for every mutation type. `-Pnightly` runs them with the CAS test; the fast and heavy builds do not. The heavy build keeps the single lost-answer, lost-COMMIT, in-flight and failover tests on every backend.
  - Selection, by `--test-dry-run`: fast 594 test methods (unchanged), `-Pheavy` 947 (was 967), `-Pnightly` 24 (the 20 moved and the CAS test on four backends). No test is in two of the three lists and together they hold all 971.
  - `-Pheavy` from clean, without the build cache: green, 15 min 49 s, 1,458 tests, 0 failures; skipped as before: Oracle 3 (no Data Guard on Free) and `AwsVendingTest` 11. This is the first green `-Pheavy` under Gradle and since YugabyteDB was dropped, so the two-at-a-time layout is now shown to run green. The longest modules: MongoDB 405 s, CockroachDB 355 s, server 206 s, Oracle 201 s.
  - `bites/run.sh` passes `-Pbites` (every test but CAS) instead of `-Pheavy`, so a bite can select an `overnight` test. Both load bites still bite: `auth-filter-outage-escapes` (KillPrimaryTest, 1 of 1 failed) and `cas-lost-update-under-load` (ConcurrentWritersTest, 5 of 5 failed), tree restored, 7 min 31 s.
  - Not run: `-Pnightly`, so the moved tests have not run under Gradle; the paths to the server jar in the load tests were exercised only by those two bites.
- **Kubernetes deployment added 2026-11-11** (Paul: "setup the deployment so that the catalog runs in Kubernetes. Create helm charts etc."). `deploy/README.md` has the details.
  - Added: an image (`deploy/image/Dockerfile`, the runnable jar in Spring Boot's layers, non-root, base image a build argument for PLT-6), a Helm chart for the servers only (`deploy/helm/keel-catalog`), and a local stack (`deploy/local/run.sh`).
  - How settings reach the server: plain settings are Spring properties in a ConfigMap; secret ones come from a Secret read as a Spring config tree, whose key names are property names. No server code changed.
  - Checked on Docker Desktop's Kubernetes 1.30 with Helm 4.3.1 (installed with winget for this, at Paul's choice), two servers:
    - `helm lint` passes.
    - Both servers start in about 14 s. Each reads its database password and storage key from the mounted Secret; PostgreSQL there demands a password for pod connections.
    - `run.sh smoke` passes through each server separately: 401 without a token, config, create a namespace on one server and read it on the other, create a table, load it with vended credentials, drop both.
    - A rolling restart under steady requests answered 550 of 550 with 200. With the preStop pause removed, 3 of 583 failed to connect, so the pause is what closes the gap.
  - Not checked: an Ingress, IRSA or Pod Identity on EKS, OpenShift, more than one node or zone, and resource sizes under load. Before production, the ETag finding from M8 needs deciding: behind the default non-sticky Service, a conditional load answered by another server is a 200, not a 304. The server still wires only the PostgreSQL-wire store.
- **Multi-table transactions done 2026-11-12, on PostgreSQL** (D15, the first v1.x item; Paul: "We need to build support for Multi-table transactions"). `POST /v1/{prefix}/transactions/commit` is served and advertised.
  - The route refuses more tables than `keel.rest.max-transaction-tables` (default 100, P10) and a table named twice (400), finds every table (404 if one is missing), and asks of each the right its table commit asks (WRITE for data only, else MODIFY).
  - The commit engine applies every change with iceberg-core and judges every rule, then writes every metadata file, then swaps every pointer in one store transaction, in the fixed order `CommitBatch` already gave, with a journal entry and events per table. A failed requirement, a refused rule or a lost race leaves every table as it was. A lost swap is retried within P10's bound; an unknown outcome is settled as for one table; an Idempotency-Key replays the answer (204). No store changed: each already took several swaps in one transaction.
  - Tests: `TransactionTest` 17 (the engine, with injected faults); `RestRoutesTest#transactionsCommitEveryTableOrNone`; `SecurityGateTest#aTransactionNeedsTheRightOnEveryTable`; the route in `MutatingRoutesAuditTest`; and the stock `RESTCatalog.commitTransaction` in `IcebergClientConfigTest`, which commits two tables and, when another writer moved one of them, changes neither.
  - Fast build green after merging main: 1,067 tests in 114 classes, 0 failures. The first run failed at startup, before any request: the one Spring context that `ConfigRouteTest`, `ConsoleTest` and `SchemaAtStartupTest` share got no PostgreSQL connection for Flyway within 30 s (11 tests). The three classes passed alone and in the next full run.
  - Bites: 5 recorded in `bites/D15/`, not run.
  - Limits. A table whose part of the transaction changes nothing (requirements only, a read guard) is checked against the metadata read at the start of the attempt and is not swapped, so a commit to it can land between that check and the transaction's swap; holding it would need a check-only swap in all three stores (built the same day: see the next entry). Readers get no view of the tables at one instant (D15). A staged create cannot join a transaction: a missing table is a 404.
  - Not verified: CockroachDB, Oracle and MongoDB (the heavy build), and the bites.
- **Read guards held to COMMIT 2026-11-12** (D15; Paul: "can we support this then? I mean the catalog side"). A table in a transaction whose change changes nothing (requirements alone, a read guard) is now held: the store locks its pointer in key order with the swapped ones, compares it with the pointer its requirements were checked against, and writes nothing to it. Two transactions that each guard what the other changes can no longer both commit (write skew). A client opts in by listing a guard; a transaction that lists none pays nothing. The stock Java client lists none: its `TableCommit.create` asserts only on what a table's updates touch (iceberg-core 1.12.0).
  - Store API: `PointerHold` beside `PointerSwap` in `CommitBatch`, locked together in one key order (`locks()`). PostgreSQL, CockroachDB and Oracle hold with `SELECT … FOR UPDATE`. MongoDB, which has no read locks, marks the held pointer's clock, as its other dependent writes mark what they depend on. No schema change.
  - Tests: the shared suite adds `heldPointerIsComparedNotWritten`, `staleHoldInABatchWritesNothing` and `holdLastsUntilCommit`, tagged `heavy` because it waits on purpose: two batches each hold what the other swaps; the second waits for the first, then conflicts. Run with `-Pbites` on all four backends, with the two existing batch tests: 5 of 5 pass on each. `TransactionTest` adds `guardMovedBeforeTheSwap` (the guarded table moves between the check and the swap: 409, nothing written) and `guardMovedWithinItsRequirement` (it moves in a way its requirement allows: retried, committed).
  - Fast build green: 1,073 tests in 114 classes, 0 failures. The first run lost 3 `catalog-server` audit tests: their Kafka consumer read nothing in 30 s while the keel-demo stack ran at full CPU in the same Docker. They passed alone and in the next full run.
  - Bites: 5 recorded in `bites/D15/`, not run.
  - A transaction that changes no table still writes nothing and so holds nothing: each of its requirements is checked when its table is read.
- **Read-only vending done 2026-11-12, on PostgreSQL and Silo** (D21, M6's open item). Found in the demo: DuckDB, signed in as the select-only `ana`, wrote a data file, a manifest and a manifest list under `markets.fx.rates` with ana's vended keys before its commit was refused (`ana holds no privilege that gives write for updateTable`). The three orphans from 20:28:52 UTC are still in the demo's bucket.
  - A load or credentials request now asks the authorizer, in the same batch as its own check, whether the caller may commit data: `updateTable` at `write`, the least a table commit needs, with the same governance rules. A caller who may gets read-write; a maintenance principal acting as itself, read-write-delete, as before; anyone else, read-only (`s3:GetObject` under the table's prefix and `s3:ListBucket` on it). The response to a create, commit or registration asks the same. A staged create vends read-write: its caller may create the table, and the create commit may carry data files written first.
  - Tests: `VendingRouteTest#selectOnlyCallersGetReadOnlyCredentials` (a select-only caller's keys, from the load and the credentials route, read and list the table, but a PutObject of a data file or a manifest, a multipart upload and a delete are each refused by Silo with 403; a writer's keys write; each issue is audited as READ or READ_WRITE) and `#stagedCreateVendsReadWrite` (a caller with only `create` stages a table and writes a data file).
  - Fast build green: 1,075 tests in 114 classes, 0 failures.
  - Bites: 1 recorded, `bites/M6/select-vended-read-write.patch`, checked with `git apply --check`, not run.
  - Credentials vended before a privilege is revoked keep working until they expire (30 minutes by default), as before.
  - Checked in the demo after `demo/demo.sh up` rolled the catalog: the same INSERT as `ana` from DuckDB now fails at its first upload (`HTTP PUT error uploading to '…/data/as_of_month=682/01a1d1a0-….parquet' (HTTP 403 Forbidden) AccessDenied`). The table's folder still holds the same 17 objects, and her reads work.
  - Not verified: Ceph and AWS, the heavy build, and the bite.
- **Heavy pass done 2026-11-12, before M9** (Paul: "We will run heavy tests today"). `./heavy.sh` ran in a worktree, with the keel-demo stack scaled to zero. It covers `main` at c3a7062, everything landed on 2026-11-12. The engine matrix's new cases are `overnight`, so they wait for the nightly.
  - `./gradlew build -Pheavy` passed in 21 min 46 s on all four backends. Every test task ran; none came from the build cache.
  - Bites: all 111 bite.
  - Eight bites had gone stale and were re-recorded against today's code:
    - Moved by the D15 read-guard holds: `transaction-commit-per-table`, `transaction-partial`, `sql-cas-ignores-version`, `cas-lost-update-under-load` and `unknown-commit-answers-503`.
    - Moved by read-only vending: `cache-shared-across-callers` and `real-near-expiry-304`.
    - Never compiled: `lost-receipts-not-read-back`, because javac rejects `while (false)` as unreachable.
  - `bites/run.sh` stopped twice on a false HASH MISMATCH. On OneDrive a tracked file was briefly busy (`sha256sum: … Device or resource busy`). The tree hash now retries a file it cannot read.
- **Unregister, register-view and labels done 2026-11-12, on PostgreSQL** (D13, D27; phase 1 of the v1.x plan, with Paul's decisions of that day, quoted in the ledger's amendments to D11, D13, D14, D20, D23, D24, D25, D27 and D30). Two fixes went with it: a commit that lands after its table's drop, and CREATE OR REPLACE changing a column's type.
  - Unregister: `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/unregister`, advertised. It leaves the table in a new final state, `UNREGISTERED`, on all four stores (schema steps: PostgreSQL V5, CockroachDB V4, Oracle V6, MongoDB step 4 with nothing to build): the name is free, the UUID, pointer, journal and grants stay, and it is never undropped or purged. The store locks the table's pointers, then the table, compares the main pointer with the one the engine read, and refuses a hold or a DR freeze (403). The route asks for modify and refuses a regulated table or one flagged for row/column policy with 403 and an audited denial (reasons `regulated` and `flagged_table`). The answer is the spec's `metadata-location` and `metadata`, built in catalog-iceberg (iceberg-core 1.12 has no class for it, and its client no call, so the RCK cannot test it); an Idempotency-Key replays it. An UNREGISTER event goes to the change stream.
  - Register-view: `POST /v1/{prefix}/namespaces/{namespace}/register-view`, advertised. It needs create on the namespace, checks the name before reading the file, and takes a file only at the catalog-assigned location, as register-table does (D28).
  - Registering a dropped or unregistered table's or view's own metadata file brings back the same row, UUID and grants as active, the file becoming the next pointer version in the same store transaction (`reviveObject`). For a dropped one it is an undrop: it needs the undrop right on the namespace the object was dropped from, only within the window, and is reported as an UNDROP. Any other holder of the UUID is a 409.
  - Labels: table and view loads, and the answers to a create, commit or register, carry `labels.object-labels` with the effective classification, owner and retention class; no provenance, no flags, no field labels. The table ETag hashes them, so a label change ends a 304 (view loads carry no ETag). Setting or removing one of the three also writes a `keel.catalog.label` event (target, label, new value) for the change stream; the governance change on the audit topic keeps the provenance. The SingleStore change feed reads no catalog events, so neither new type reaches it; what it reads, table loads through its pinned client, parse with labels and with fields it does not know.
  - Commit after drop (problem 1 of the plan). A commit that read a table before its drop swapped the dropped table's pointer and answered 200: the new store-suite test `aCommitAfterTheDropIsRefused` and two engine tests (`dropWinsTheRace` in `CommitEngineTest` and `TransactionTest`) failed that way before the fix. Every store now checks, after locking the pointers, that each swap's object is still active (`CommitResult.Inactive`, answered 404). Lock order: docs/storage-design.md 9 (f) 8.
  - CREATE OR REPLACE (D11's amendment): a replace that starts main afresh is exempt from the type-promotion check; a change in place is not. `EngineMatrixTest` now expects `RESULT spark replace.retype 4 1 string`.
  - RCK: four more tests pass, `testRegisterTable`, `registerView`, `registerExistingView` and `registerViewThatAlreadyExistsAsTable`. `-Prck.record=true` changed only their four lines, and their reasons are gone.
  - Tests: `RegisteringTest` 18 (the engine: unregister, revive, register-view, labels); the shared store suite's `unregisterIsFinal`, `unregisterRefusals`, `reviveBringsTheObjectBack` and `aCommitAfterTheDropIsRefused`, and, tagged `heavy`, `commitRacingTheUnregisterExactlyOneApplies` and `lostReviveAnswerResolvesToCommitted`; `MutationSuite` drives `unregisterObject` (its sweeps are `overnight`); `RestRoutesTest`, `SecurityGateTest`, `HeldTablesTest`, `GovernanceRoutesTest` and `MutatingRoutesAuditTest` cases for both routes, the labels and the label events; a golden `view-register` case; two `BackstopTest` cases; `IcebergClientConfigTest#stockClientRegistersAViewAndReadsTableLabels`; `ChangeStreamTest` and the golden event lines; `CatalogResponsesTest` in the change feed.
  - Fast build green after merging main (already up to date at 2b1af36): 1,147 tests in 120 classes, 0 failures, 6 min 42 s. Five test tasks were up to date from runs earlier the same evening on the same inputs. One earlier run of the changed server tests lost 2 `GovernanceRoutesTest` and 1 `HeldTablesTest` cases to state left by `MutatingRoutesAuditTest`, which Gradle ran first because it had failed before: a catalog-level classification it set and kept, and the shared clock it moves 31 days. It now removes the classification, and the retention test dates its snapshots by that clock.
  - Bites: 21 recorded, 20 in `bites/D13/` and 1 in `bites/D11/`, each checked with `git apply --check`, not run. Six older bites no longer applied and were recorded again: `etag-ignores-location`, `register-dropped-uuid` (which now skips the UUID lookup), `credentials-not-storage-access`, `real-near-expiry-304`, `cas-lost-update-under-load` and `reconcile-apply-ignores-moved-pointer`. All 132 apply.
  - Not verified: CockroachDB, Oracle and MongoDB (their unregister, revive and active-object checks, and their schema steps), the two `heavy` store tests, the `overnight` sweeps, `RollingUpgradeTest` (now expecting schema 5), the engine matrix's retype line, and the bites. All wait for the next heavy pass or the nightly.
- **Review fixes to unregister, register-view and labels, the same night** (D11, D13; 11 findings from the review of phase 1, each checked against the code and each fixed; the ledger's amendments say what changed).
  - Unregister is bound to the table the route checked. The route hands the engine that table's id, and the engine unregisters only it: a table that takes the name after the check (a rename swap, a drop and recreate) is left alone with a 404. The store checks the regulated flag (the table or any ancestor) and the row/column-policy flag (the table) again under the table's lock, beside the hold and freeze, on all four stores (`Refused(FLAGGED)`, answered 403), so a flag a steward sets after the route's check still refuses it.
  - Reviving by register restores in place, as the management undrop does. The object comes back only in the namespace it left (elsewhere is 409: a move would hand it that namespace's grants and governance) and only from the file its main pointer names (an older file is 409; that file changed since it was recorded raises the metadata-integrity alert and is refused). So it returns as it left, and no rule a commit is held to is skipped. With revive in place, the route's undrop check asks what its register check already asks, create on the same namespace. An unregistered object brought back needs that, as any register there does; the ledger marks this reading, and the refusal to revive elsewhere, as awaiting Paul's OK.
  - CREATE OR REPLACE (D11): a replace is now a commit after which main is a snapshot the table did not have, or is gone where it was there. Pointing main back at a snapshot the table already had is not one (it counted before, so a rollback could retype the rows it reads), and neither is a table with no main snapshot before or after. The exemption covers only the type-promotion check, which Paul approved: a primitive turned nested, or an optional field made required, is refused in a replace too.
  - Docs: D14 no longer says view loads carry an ETag; D30 (7) says "tagged heavy", as Paul wrote; docs/storage-design.md 9 (f) 3 lists `Refused(FLAGGED)`.
  - Tests: `RegisteringTest` 22, adding `Unregistering#onlyTheAuthorizedTable` and `#flaggedMeanwhile`, and `Reviving#onlyInItsOwnNamespace` and `#onlyTheFileItLeftAt`; the store suite's `unregisterRefusals` sets each flag in turn (all four stores); `SecurityGateTest#unregisterNeedsModifyAndATableComesBackOnlyInItsOwnNamespace` (widened from the unregister test) and the new `#registerViewNeedsCreateAndADroppedViewComesBackOnlyInItsOwnNamespace`; three more `BackstopTest` cases (a replace still may not make a field required nor turn a primitive into a struct; the replace of a table with no snapshot may retype; a rollback of main that also retypes is refused).
  - Fast build green after merging main (already up to date at 2b1af36): 1,155 tests in 120 classes, 0 failures, 10 min 10 s, with the keel-demo stack running in the same Docker. One earlier run of the changed server tests lost `HeldTablesTest` and `MutatingRoutesAuditTest` at startup, when the Kafka container exited with code 126; both passed on the next run.
  - Bites: 14 recorded, 12 in `bites/D13/` (the moved-pointer compare and the flag check on each store family, the engine's binding to the checked table, the revive's window, namespace, file and hash checks, and register-view's create check) and 2 in `bites/D11/`. Three were recorded again because the code they break moved: `replace-exemption-in-place`, `unregister-ignores-freeze-sql` and `-oracle`. `register-undrop-unguarded` is removed: the check it breaks now asks what the register check asks, so no test can fail. All 145 apply. Eleven were run by hand on PostgreSQL and each fails as it should: the five engine bites (four applied together, each one's named test failing), the three in `bites/D11/`, `unregister-ignores-flag-sql`, `unregister-ignores-moved-pointer-sql` and `register-view-unguarded`.
  - Not verified: the flag check and the moved-pointer and flag bites on CockroachDB, Oracle and MongoDB, the other bites, the `heavy` and `overnight` tests, and the engine matrix. All wait for the next heavy pass or the nightly. Not guarded: an object undropped, renamed into another namespace and dropped again by other callers between the engine's read and the store's revive would come back in the namespace it first left, since the store does not compare the namespace.
- **Next:** M9, carried to work.

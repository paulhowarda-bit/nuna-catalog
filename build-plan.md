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
| P1 | **Maven multi-module build**, with the Maven wrapper pinned. | Maven 3.9.8 is already installed here; Gradle is not. Work's package index is blocked, and Maven with a corporate mirror (`settings.xml`) is the lowest-friction path there. |
| P2 | **JDK 25 (Amazon Corretto 25)**. | Matches the Corretto 21 already installed. JDK 25 is not installed yet: prerequisite for M0, Paul installs it. |
| P3 | **Spring MVC on virtual threads**, not WebFlux. | Blocking I/O with JDBC/Mongo sync drivers [D4]. |
| P4 | **Hand-written controllers against the vendored OpenAPI spec**, plus a test that every advertised endpoint matches a spec operation. No code generator. | Generated code would add bloat and foreign names; the RCK proves conformance [D31]. |
| P5 | **Iceberg JSON is written by iceberg-core's own parsers inside `catalog-iceberg`**; controllers return the JSON text, so Spring never serializes an Iceberg type. | Keeps the wire format identical to the reference implementation, and keeps it clear of Spring Boot 4's JSON stack: Jackson 3 is Boot's default and Spring is removing Jackson 2 support (M0 finding). |
| P6 | **Flyway for the four SQL targets** (PostgreSQL, CockroachDB, YugabyteDB, Oracle); a small versioned-step runner of our own for MongoDB. | DBAs know Flyway. M0 verifies that the community edition covers all four targets at the pinned version (F4); if it does not, we write the same small runner for SQL too. |
| P7 | **PostgreSQL is the default development backend.** No in-memory store. | An in-memory store would drift from the invariants [D8]. Unit tests of the commit loop mock the storage interface. |
| P8 | **Testcontainers** for every backend, IdP, object store and Kafka in tests; **docker compose** for the nightly engine matrix. | Docker Desktop 27.1.1 is installed here. |
| P9 | **Package root `fidice.catalog`**, Maven groupId `fidice`. | Follows the repo name. |
| P10 | Defaults that the ledger left to the plan: vended credential lifetime 30 min (bounds 15–60 [D21]); idempotency key lifetime PT24H [D10]; server-side commit retry: at most 4 attempts or 2 s [D9]; multi-table cap 100 [D15]; soft-drop window 30 days [D25]; clock-skew guard 60 s [D9, D11]. | Each is configuration, changeable without code. |
| P11 | Branch and tag defaults [D33]: at most 50 open branches per catalog; a branch expires 30 days after its last commit unless renewed (max 180 days); tags never expire, at most 1,000 per catalog; a branch or tag blocks file cleanup for everything it references; merging or deleting a branch releases it. | The ledger asked the plan to set these. |

## 2. Architecture

### 2.1 Modules

| Module | Holds | Depends on |
|---|---|---|
| `catalog-core` | Domain types (names, principals, privileges, pointers, commit records, errors), the commit loop, idempotency, rules layer, authorization decisions, retention/hold logic. No Spring. | `catalog-iceberg`, `catalog-store-api` |
| `catalog-iceberg` | The only module that imports `org.apache.iceberg`. Parses requests, applies updates (TableMetadata.Builder, MetadataUpdate.applyTo), validates requirements (UpdateRequirement.validate), builds view metadata, serializes responses. Exposes our own interfaces. | pinned iceberg-core |
| `catalog-store-api` | The narrow storage interface (2.3) and the shared invariant/fault-injection suite, published as a test-jar. | — |
| `catalog-store-sql` | PostgreSQL, CockroachDB and YugabyteDB: one PG-wire implementation with dialect switches [D7]. | `catalog-store-api` |
| `catalog-store-oracle` | Oracle including Exadata/RAC: UCP + FAN, Transaction Guard, service affinity [D7]. | `catalog-store-api` |
| `catalog-store-mongo` | MongoDB: multi-document transactions, `w:majority`, primary reads [D7]. | `catalog-store-api` |
| `catalog-objectstore` | Storage profiles; metadata.json write/read with SHA-256; credential vending through STS AssumeRole for S3, MinIO and Ceph; location guardrails [D21, D26, D36a]. | `catalog-core` |
| `catalog-security` | Multi-issuer JWT validation, per-issuer claim mapping to one principal, trusted-engine registry [D16, D17, D17a]. | `catalog-core` |
| `catalog-audit` | Outbox relay, synchronous Kafka publisher, CloudEvents change stream, hash-chained archive writer [D23]. | `catalog-core`, `catalog-store-api` |
| `catalog-server` | Spring Boot app: REST routes, `/v1/config`, error mapping, management APIs (grants, holds, undrop, classification push, engines), startup self-checks. | all of the above |
| `catalog-tests-conformance` | REST Compatibility Kit run plus a response diff against `apache/iceberg-rest-fixture` [D31]. | `catalog-server` |
| `catalog-tests-engines` | Nightly engine matrix in docker compose [D31]. | built server image |
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
- `commit(CommitBatch)` — CAS on one or more (object, branch) pointers in fixed ID order, plus projection, hash, journal, outbox and idempotency. The same call serves single-table commits (v1) and multi-table commits and merges (v1.x) [D15, D33].
- `findCommit(commitId)` — the ambiguity lookup; locks the pointer row before looking [D8.4].
- Namespaces: create, drop, load, list children (paged), update properties (CAS on a namespace version).
- Objects (tables and views): create-if-absent, soft drop, undrop, rename, register, list (paged), purge-eligible scan.
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
| object | catalog id, id (UUID), namespace id, name (normalized, display), kind (table/view), state (active/dropped), dropped-at, purge-after, location root [D25, D28] |
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
| YugabyteDB | Same SQL; retry serialization errors. | No ambiguity code: every connection error mid-commit → `findCommit`. | Replication factor < 3. |
| Oracle / Exadata RAC | Same logical steps; UCP with FAN; route each table's pointer row to one RAC service (affinity by object-ID hash) so hot rows don't move between instances. | Transaction Guard: on a recoverable error take the LTXID and call `DBMS_APP_CONT.GET_LTXID_OUTCOME`. | Data Guard is not in maximum-availability mode with zero apply delay where required, or Transaction Guard is off for the service. |
| MongoDB | Multi-document transaction; `writeConcern: majority`, `readConcern: snapshot`, primary reads. | `UnknownTransactionCommitResult` → retry `commitTransaction`, then `findCommit`. | Not a replica set, default write concern below majority, or journaling off. |

### 2.6 Shared invariant and fault-injection suite

Lives in `catalog-store-api` as a test-jar. Every backend runs all of it; passing it is what "supported" means [D8.9]. Faults are injected with container stop/pause and a TCP proxy between server and database.

1. **CAS linearizability**: N writers from one base, exactly one wins; repeated 10,000 times.
2. **Atomicity**: break the connection at every step boundary of `commit`; pointer, journal, outbox and idempotency never disagree.
3. **Ambiguity**: drop the link after COMMIT is sent; the outcome resolves correctly, is never reported as 409, and no acknowledged commit is lost.
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
- **M2a** `catalog-store-sql`: PostgreSQL, CockroachDB, YugabyteDB.
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
- **Gate:** RCK passes and the fixture diff is clean on all five backends.

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
- Retention classes, legal hold, commit-time rejection of snapshot removal on held tables, refusal to drop/purge/unregister held tables, snapshot+journal export [D24].
- Soft drop with 30-day undrop; purge only by the maintenance principal, after the window, rate-limited [D25].
- Classification push API with provenance [D27].
- **Gate:** every mutating route emits its audit record (checked route by route); a Kafka outage blocks credential issuance and nothing else; held tables cannot lose snapshots.

### M8 — Hardening gates

- Nightly engine matrix in docker compose: Spark, Flink, Trino, PyIceberg, DuckDB, SingleStore — create, write, read, evolve, drop; deep namespaces; v2 and v3 tables [D31, D35].
- Load: a 10k+-snapshot table; 8–32 concurrent writers on one table with zero 5xx, deadlocks, lost or duplicated commits.
- Resilience: kill the primary mid-commit; rolling upgrade across a schema change; reconcile-from-storage DR drill (freeze cleanup and writes, fast-forward pointers along metadata-log chains) [D37].
- SLO runs (load p99 < 50 ms, 304 p99 < 10 ms, commit p99 < 300 ms, ≥ 500 commits/s); the laptop shows relative behaviour only.
- **Gate:** all of the above green on the personal machine.
- **Work hardware:** absolute SLOs, three-site quorum latency, async DR region.

### M9 — Carry to work

- Artifact approval in the curated repository (PLT-6).
- RAC/Exadata, real IdPs, real object stores, DBA review of the self-check thresholds.
- Bucket naming per region and replication SLA (PLT-4) for the DR runbook [D29].

### v1.x milestones

- `/transactions/commit`: one bounded transaction, fixed lock order, cap 100, advertised only when truly atomic [D15].
- Branches, tags and merge over the v1 storage: REST prefix selects the branch (`trading@dev`), Nessie merge semantics per table, cleanup protects every ref, defaults from P11 [D33].
- Maintenance worker: its own identity, sole delete permission, commits through the catalog; expiry and orphan removal in Java, compaction on Spark [D30].
- unregister, register-view, labels [D13]; queryable audit tables [D23]; DR replication-watermark verifier.

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
| PLT-2 | DBA support commitments for each of the five backends at tier 1. | M9 |
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
| R1 | Five backends cost roughly three implementations' worth of work (Polaris's pluggable persistence is ~26% of its code). | The shared suite is the gate: a backend that cannot pass it is not supported, and v1 ships without it rather than with a weaker guarantee [D8.9]. |
| R2 | Oracle RAC/Exadata behaviour cannot be tested on the laptop. | Oracle Free covers the logic; RAC items are listed as work gates in M2 and M9. |
| R3 | JSON library conflict between Spring Boot 4 and iceberg-core (F2). | Settled at M0: P5 keeps Iceberg JSON out of Spring; Jackson 2 pinned to 2.22.2 and the dependency tree converges. |
| R4 | MinIO community edition is unmaintained (F5). | Tests run on Ceph (single container, AWS's role-based credential model; Paul, 2026-11-07), with Silo for MinIO's model. RustFS was assessed as an alternative (F8). The production on-prem store still depends on what work runs (PLT-3). |
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
  - Not run: the nightly 10,000-round CAS profile.
- **Next: M2**, the backends in full, starting with the suite changes listed in `docs/storage-design.md`.

# Other Iceberg REST catalog implementations (beyond Polaris and Nessie) — feature convergence, as of 2026-11-06

Provenance key: **[read: URL]** = I fetched and read the primary source in this session (raw Markdown/source via curl where exact text mattered, otherwise WebFetch, which paraphrases); **[secondary: URL]** = search-engine summary or third-party page I did not read in full; **[inference]** = my reasoning, not stated by a source. Raw downloads are in `C:/Users/paulh/AppData/Local/Temp/claude/d9ac3f89f5ec51f6/2eb8cba6-c503-4baa-9e18-b3df89b0f733/scratchpad/downloads/others/`.

Lens throughout: (a) what the Iceberg REST spec and clients require, (b) what an enterprise operator (Fidelity: regulated, thousands of tables, many engines, multi-region HA) needs, (c) what exists for a vendor's commercial model.

---

## 1. Lakekeeper (in depth)

### Takeaway
Lakekeeper is the most complete *open-source, non-Java* Iceberg REST catalog: Rust single binary on PostgreSQL, with OpenFGA ReBAC, CloudEvents, soft-delete/undrop, protection, vended credentials **and** S3 remote signing, and multi-table commits done as one Postgres transaction. But it is **open-core and single-vendor**: snapshot expiration, compaction ("optimize"), orphan-file removal, Cedar policy authz, admission gates and IdP-managed roles are **Lakekeeper Plus (commercial) only**, it has not moved to a foundation, and commits are heavily concentrated in one maintainer. "Lightweight" is true of the binary, not of a production deployment (Postgres + OpenFGA + its DB + Kafka/NATS + KEDA workers + reverse proxy).

### Cited Findings
**Identity, license, governance, activity**
- Apache-2.0 implementation of the Iceberg REST Catalog spec "based on apache/iceberg-rust"; "Written in Rust: Single all-in-one binary - no JVM or Python env required"; "There is no local state - the catalog can be scaled horizontally" — [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/README.md]
- "Lakekeeper is maintained by Vakamo, which also offers Lakekeeper Plus — an edition adding Cedar-based authorization … audit and an SLA" — [read: README above]
- I found no evidence Lakekeeper joined a foundation (ASF/LF); search results describe it as standalone, maintained by Vakamo — [secondary: https://apis.io/providers/lakekeeper/] plus the README above. Treat "no foundation" as current as of 2026-11-06.
- GitHub (queried 2026-11-06): 1,501 stars, 172 forks, 108 open issues, created 2024-04-05, pushed 2026-11-06; ~64 contributors (incl. anonymous); 632 commits in the last 52 weeks, 243 in the last 12 — [read: https://api.github.com/repos/lakekeeper/lakekeeper and /stats/participation]
- Commit concentration: top contributor `c-thiel` 938 commits vs. next human `twuebi` 163 and `brgr-s` 55 (`renovate[bot]` 371) — [read: https://api.github.com/repos/lakekeeper/lakekeeper/contributors]
- Release cadence: v0.15.0 (2026-11-06), v0.14.2 and v0.13.8 (2026-10-25), v0.14.1 (2026-10-16), v0.14.0 (2026-10-07) — two maintained release lines in parallel — [read: https://api.github.com/repos/lakekeeper/lakekeeper/releases]
- Pre-1.0: 0.x versioning; every upgrade requires `lakekeeper migrate` before `serve` (Postgres + OpenFGA model migrations, single transaction) — [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/concepts.md]

**Architecture / storage backend / commit mechanism**
- Only Postgres (>=15) is supported as catalog backend; secret store is Postgres (encrypted) or HashiCorp Vault KV2; event stores NATS and Kafka — [read: README]
- Commit: a Postgres transaction that first runs `SELECT tabular_id, metadata_location FROM tabular WHERE warehouse_id=$1 AND tabular_id = ANY($2) … ORDER BY tabular_id FOR NO KEY UPDATE`; the source comment says commits to one table "queue here," a stale commit "fails before its writes can collide," and "Id order keeps commits to several tables from crossing" (deadlock avoidance for multi-table commits). The final update filters on `(warehouse_id, table_id, metadata_location)` as a compare-and-set backstop; a missing update raises `ConcurrentUpdateError` — [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/crates/lakekeeper-storage-postgres/src/tabular/table/commit.rs]
- Multi-table commit size is bounded by Postgres bind-parameter limits (`validate_commit_count`, `MAX_PARAMETERS / PER_COMMIT_TABLE_BINDS`) — [read: same file]
- Lock contention on one table now returns 409 (retryable) instead of 503 (v0.14.1) — [read: GitHub releases API above]
- No-op commits (e.g., Spark's pre-`mergeSchema` commit) write no new metadata file and publish no event (v0.15) — [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/table-maintenance.md]
- Clock guard: Lakekeeper refuses a new main-branch snapshot from a writer whose clock is >1 minute ahead of Lakekeeper's — [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/production.md]
- HA guidance: separate read/write Postgres URLs; read replicas must lag less than `LAKEKEEPER__CACHE__INVALIDATION__REEVICT_GRACE` (default 10 s); write URL must hit the primary because cross-instance cache invalidation reads change records there; instances sync caches "usually within a second" (v0.15) — [read: production.md; v0.15.0 release notes]
- TLS is not terminated natively ("use a reverse proxy like Nginx or Envoy") — [read: production.md]
- Identifiers are case-insensitive but case-preserving via PostgreSQL ICU collation `und-u-ks-level2`, justified by a table showing Spark/Trino lowercase, Snowflake uppercase, PyIceberg verbatim — [read: concepts.md]
- Conditional table loads: answers `304 Not Modified` to Iceberg Java ≥1.11 clients, but stops answering 304 before vended credentials expire — [read: concepts.md]

**Warehouse / project tenancy model**
- Hierarchy Server → Project → Warehouse → (nested) Namespace → Table/View/Generic table; Project, Server, User, Role are Lakekeeper-only entities. Single-project is recommended for single-company setups — [read: concepts.md]
- Each warehouse = one storage location + storage profile + optional credential; "Never share locations between Warehouses to ensure no data is leaked via vended credentials." Lakekeeper validates table locations are inside the warehouse and that no table nests inside another table's location, so vended creds for one table cannot reach another's files — [read: concepts.md]
- Two APIs: Iceberg REST at `/catalog`, S3 remote-signing at `/<prefix>/v1/aws/s3/sign`, management API at `/management` — [read: concepts.md]

**Authorization (OpenFGA ReBAC, Cedar, OPA bridge)**
- Authorizers: OpenFGA (open source, extra service with its own DB, runtime grants by admins *and* owners), Cedar (Lakekeeper Plus, policy-as-code with attribute conditions), AllowAll (dev), Custom (implement the `Authorizer` Rust trait). "Neither engine expresses row filters or column masks" — [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/authorization.md]
- OpenFGA grants: server {admin, operator}; project {project_admin, security_admin, data_admin, role_creator, tag_creator, describe, select, write, modify, create}; warehouse/namespace/table/view {ownership, pass_grants, manage_grants, manage_tags, describe, select, write, modify(, create)}. `describe ⊂ select ⊂ write ⊂ modify`, grants inherit down the hierarchy, creators become owners, "Permissions are additive" (no deny). Requires OpenFGA ≥ v1.11 — [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/authorization-openfga.md]
- Separation of duties built in: server `admin` can administer projects but has no data access; self-grant of `project_admin` is tracked for audit; `pass_grants` delegates one hop only, revocation needs `manage_grants` — [read: authorization-openfga.md]
- New in v0.15 (2026-11-06): `write` privilege so pipelines/dbt can change tables without being able to drop/rename them — [read: v0.15.0 release notes]
- OPA bridge "exposes Lakekeeper permissions via Open Policy Agent so engines like Trino enforce them" — [read: README]
- Production advice: co-locate OpenFGA with Lakekeeper (same node; Helm uses PodAffinity) and enable OpenFGA caching — [read: production.md]
- Plus-only: provider-managed roles synced from the IdP, role-provider chain, admission gates (coarse allow/deny via an external entitlement service before any handler) — [read: authorization.md; https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/admission.md]
- Honest scope statement: "Reading and writing files in storage does not go through Lakekeeper… A writer can therefore overwrite or delete any file of the table… Revoking access stops Lakekeeper from handing out new credentials. Credentials it already handed out stay valid until they expire" (default 1 h); "Remote signing checks access for every request it signs" — [read: concepts.md]

**Credential vending and remote signing**
- Storage support: S3 (AWS; vended creds + remote signing, role assumption, session tags), S3-compatible (MinIO/Ceph), Cloudflare R2, Alibaba OSS, ADLS Gen2, OneLake (SAS vending), GCS — [read: README]
- v0.14.1 hardening: S3 signer "signs only what it knows" (refuses unknown query params/headers and writes with a non-warehouse KMS key); fixes for Iceberg 1.12 batch-delete signatures and ranged reads — [read: releases API]
- `push-s3-delete-disabled` pushes `s3.delete-enabled=false` to clients to protect soft deletion — but the docs call it "a request a client can ignore" — [read: concepts.md]

**CloudEvents, change approval, soft-delete/undrop, protection**
- "Built-in support to emit change events (CloudEvents)"; backends NATS (`LAKEKEEPER__NATS_TOPIC`) and Kafka (rust-rdkafka); can also log CloudEvents — [read: README; https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/configuration.md]
- "Change Approval": external systems can veto changes (e.g., data contracts) through the `ContractVerification` trait — [read: README]
- Soft deletion per warehouse: dropped tables/views/generic tables are marked deleted with an expiry fixed at drop time and are restorable until then; table UUID is retained — [read: concepts.md]
- Spark < Iceberg 1.12 deletes files itself on `DROP TABLE … PURGE`, undermining soft delete; Iceberg 1.12 adds `rest-catalog-purge=true` — [read: concepts.md]
- Protection on warehouses/namespaces/tables/views; recursive namespace drop; `force=true` override; in soft-delete warehouses, skipping soft delete needs warehouse `modify` (OpenFGA) — [read: concepts.md]
- Bug fixed 2026-10-25: soft-deleted tables in inactive warehouses never expired "and their files stayed forever" — [read: v0.14.2 release notes]

**Task queues (drop/purge, expiration, maintenance)**
- Background task queues run in every pod by default or as dedicated workers; OSS purges files of dropped tables (`tabular_purge`); "in Lakekeeper Plus, expiring snapshots, removing orphan files and optimizing tables" — [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/task-workers.md]
- v0.15: KEDA ScaledJob per queue driven by a `/task-queue/<queue>/demand` metrics endpoint; retries with growing backoff — [read: task-workers.md; v0.15.0 release notes]
- OSS maintenance is limited to metadata-file cleanup honoring `write.metadata.delete-after-commit.enabled` (default on since v0.10) and `previous-versions-max` (default 100) — [read: table-maintenance.md]
- Plus-only expire snapshots (warehouse defaults: off; 5-day max age; ≥24 h floor unless overridden; skips `gc.enabled=false` and natively encrypted tables), triggered after commits rather than cron — [read: table-maintenance.md]
- Plus-only optimize: compaction to 512 MiB default + manifest rewrite, at most once per hour per table, skips format-v3 tables, encrypted tables, WAP tables, and compaction on ADLS — [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/optimize.md]
- Plus-only orphan-file removal with an adaptive scheduler (1-day floor, 90-day ceiling, 7-day min age default) — [read: table-maintenance.md]

**Statistics, monitoring, audit**
- "Endpoint statistics": per-request counts aggregated in memory and flushed to Postgres every 30 s, queryable via `POST /management/v1/endpoint-statistics`; warehouse table/view counts via `/management/v1/warehouse/{id}/statistics`; Prometheus metrics for HTTP, PG pool, caches, task queues — [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/monitoring.md]
- OSS audit: `LAKEKEEPER__AUDIT__TRACING__ENABLED` logs every authorization check (allowed and denied) as `event_source="audit"`; audit records carry a versioned `audit_format` (1.0) — [read: configuration.md; v0.14.2 release notes]
- Runtime read-only maintenance mode per process (v0.14.2) — [read: releases API]

**Performance numbers**
- I found no published Lakekeeper throughput/latency benchmark; search surfaced only monitoring docs — [secondary: https://docs.lakekeeper.io/docs/nightly/monitoring/]. The docs' only quantitative production guidance is pool sizing and replica-lag bounds — [read: production.md]

### Inferences
- [inference] Lakekeeper's commit design (row lock ordered by id + CAS on `metadata_location` in one SQL transaction) is the cleanest public reference for a server-side multi-table commit, and maps naturally to any store with multi-row transactions; in MongoDB it would need multi-document transactions for multi-table commits, while single-table commits can be a single conditional `findOneAndUpdate` on `metadata_location`.
- [inference] The open-core line falls exactly where an enterprise operator's day-2 needs start (maintenance, IdP group sync, policy-as-code, admission control). Adopting OSS Lakekeeper means either paying Vakamo or building those pieces anyway.
- [inference] The bus factor (one maintainer authors most code) and pre-1.0 status are material risks for a regulated firm's system of record, even though Apache-2.0 makes a fork legally possible. Forking a Rust codebase only makes sense if Fidelity has, or will build, Rust capacity.
- [inference] Lakekeeper's explicit statement that a writer can corrupt any file of a table and that revocation only takes effect when tokens expire is true of *every* vending catalog; it is not a Lakekeeper weakness, but Fidelity's design must state its own revocation latency (token TTL) and file-level recovery story (bucket versioning / object lock).

### Gaps
- No published performance/scale numbers; no stated max tables per warehouse.
- Exact Lakekeeper Plus pricing and whether the Plus audit differs materially from OSS audit tracing.
- Whether Vakamo has outside funding or customer references in regulated finance (not researched).

---

## 2. Apache Gravitino (incl. Iceberg REST server)

### Takeaway
Gravitino is a federated "metadata lake" (Java, Apache TLP since 2025) whose Iceberg REST server is a **facade over a pluggable Iceberg catalog backend** (JDBC/Hive/REST/custom). It has the broadest scope of any OSS catalog (Iceberg, Hive, relational DBs, filesets, Lance, models), RBAC with explicit DENY, credential vending, events, and geo-distributed IRC proxying — but authorization only works when the IRC runs embedded in the full Gravitino server, and it does **not** implement multi-table transactions.

### Cited Findings
- IRC implements the Iceberg 1.11 REST API for "most namespace, table, and view operations"; hierarchical namespaces; Hive/JDBC/REST backends; vending for S3/GCS/OSS/ADLS; event listeners and audit logging; OAuth2/HTTPS; "Access control in auxiliary mode"; pluggable metrics storage; "Table metadata and scan plan caches." **Not implemented: "Multi-table transactions" and "View registration."** — [read: https://raw.githubusercontent.com/apache/gravitino/main/docs/iceberg-rest-service.md]
- Deployment modes: standalone IRC (no access control) vs. auxiliary service inside the Gravitino server (access control: yes). "Authorization features are only available when running in auxiliary mode." — [read: same]
- Backend: `catalog-backend` = `memory` (default) | `hive` | `jdbc` (MySQL/PostgreSQL drivers) | `rest` | `custom` (any Iceberg `Catalog` class); multiple backends via `static-config-provider` or `dynamic-config-provider` — [read: same]
- Commit mechanism is therefore the backend Iceberg catalog's (e.g., JDBC conditional update, Hive metastore) — [read: same] + [inference]
- Access control: ownership comes with creation and reaches down; privileges are collected into roles granted to users/groups; grants reach downward incl. future objects; default-deny; "An explicit deny overrides everything" — [read: https://raw.githubusercontent.com/apache/gravitino/main/docs/security/access-control.md]
- Credential vending: path-scoped token providers (`s3-token`, `oss-token`, `gcs-token`, `adls-token`, `cos-token`, `aws-irsa`) only for Fileset and Iceberg catalogs; other catalogs vend static keys only — [read: https://raw.githubusercontent.com/apache/gravitino/main/docs/security/credential-vending.md]
- Events: pre-event and post-event for table operations with a pluggable listener — [read: iceberg-rest-service.md]
- Geo-distribution: "a local IRC catalog proxying requests to a remote IRC catalog so users get a global view of metadata across regions or clouds" — [read: https://raw.githubusercontent.com/apache/gravitino/main/docs/overview.md]
- Maintenance: a built-in `system_iceberg_compaction` policy type used by an optimizer to generate compaction strategies at catalog/schema/table level (doc dated 2026-03-05) — [read: https://raw.githubusercontent.com/apache/gravitino/main/docs/iceberg-compaction-policy.md]
- Activity: 3,348 stars, 980 forks, 1,051 open issues, ~356 contributors; 1,701 commits in the last 52 weeks; releases v1.3.1 (2026-09-30), v1.3.0 (2026-06-29), v1.0.0 (2025-09-30); top contributors spread across ≥10 people — [read: https://api.github.com/repos/apache/gravitino]
- Graduated to Apache Top-Level Project on 2025-06-03 (announced 2025-06-04) — [secondary: https://news.apache.org/foundation/entry/the-apache-software-foundation-announces-two-new-top-level-projects]

### Inferences
- [inference] Gravitino is the "vendor-neutral platform" analogue of Unity Catalog; most of its surface (Lance, models, filesets, relational DB federation) serves an AI/metadata-lake product vision (Datastrato), not the Iceberg REST contract.
- [inference] Missing multi-table transactions and the auxiliary-mode-only authz make it a weak fit as Fidelity's *authoritative* Iceberg catalog, but its federation and geo-proxy patterns are worth studying.

### Gaps
- No published scale limits or benchmarks found.
- Whether credential vending works in standalone mode without authz (likely yes, unverified).

---

## 3. Unity Catalog OSS and Databricks Unity Catalog Iceberg REST

### Takeaway
Unity Catalog OSS (LF AI & Data sandbox, Java/Hibernate) only gained **full Iceberg REST table write support in v0.7.0 (2026-10-13)**; it is primarily a multi-asset (Delta, Iceberg, volumes, functions, models) governance catalog. Databricks' hosted UC exposes `/api/2.1/unity-catalog/iceberg-rest` with read/write for managed Iceberg tables, credential vending (1-hour default), and hard published resource quotas, but **blocks external clients from running table maintenance**.

### Cited Findings
**UC OSS**
- v0.7.0 (2026-10-13): "any engine that supports the Apache Iceberg REST protocol can create, write to, read from, rename, and drop Iceberg tables"; endpoint `http://host:8080/api/2.1/unity-catalog/iceberg`; native Iceberg tables enabled by default — [read: https://github.com/unitycatalog/unitycatalog/releases/tag/v0.7.0 via https://api.github.com/repos/unitycatalog/unitycatalog/releases]
- v0.7.0: dropped managed storage is permanently deleted after `server.storage-cleanup.initial-delay` (default 7 days); "The delay is not a recovery window" — [read: same]
- v0.7.0: multi-instance deployments — authorization changes propagate between instances (`server.authorization.policy-refresh=true`); Delta API reports which maintenance operations the catalog permits; lock contention reported as unknown commit state — [read: same]
- Persistence via Hibernate on H2 (default), MySQL (binary collation required), PostgreSQL; Hibernate and Casbin share one HikariCP pool — [read: https://raw.githubusercontent.com/unitycatalog/unitycatalog/main/docs/server/deployment.md]
- Authorization decisions use an in-memory Casbin policy loaded from a shared `casbin_rule` table, refreshed every `PT1M` by default; Iceberg credentials from `loadTable` require `USE CATALOG`, `USE SCHEMA`, and `SELECT` (or ownership) at each level; read/write creds also need `MODIFY` — [read: https://raw.githubusercontent.com/unitycatalog/unitycatalog/main/docs/server/auth.md]
- "Unity Catalog is currently a sandbox project with LF AI and Data Foundation" — [read: https://raw.githubusercontent.com/unitycatalog/unitycatalog/main/README.md]
- Activity: 3,571 stars, ~138 contributors, 463 commits in 52 weeks; top committer `yili-db` (166) — [read: https://api.github.com/repos/unitycatalog/unitycatalog]

**Databricks hosted UC Iceberg REST**
- Endpoint `/api/2.1/unity-catalog/iceberg-rest`; managed Iceberg = read+write; foreign Iceberg, managed/external Delta with Iceberg reads = read-only; vending not supported for foreign Iceberg; vended creds default lifetime 1 hour; PAT or OAuth; requires `EXTERNAL USE SCHEMA` — [read: https://docs.databricks.com/aws/en/external-access/iceberg]
- "External Iceberg clients can't run table maintenance operations, such as expiring snapshots and removing orphan files"; equality deletes rejected on managed v3 tables; external clients can't create tables with UUID/Fixed/TIME types — [read: same]
- UC quotas: 1,000 catalogs/metastore; 10,000 schemas/catalog; 10,000 tables/schema; **1,000,000 tables/metastore (fixed)**; 32,768 columns/table; privileges 4,000 on parent objects; no Iceberg REST request-rate limit published — [read: https://docs.databricks.com/aws/en/resources/limits]

### Inferences
- [inference] Blocking external maintenance and the read-only treatment of Delta/foreign tables are commercial-control choices (Databricks runs predictive optimization itself); they are not spec requirements.
- [inference] UC OSS's Iceberg write path is one month old; it is not a credible production Iceberg catalog for Fidelity in 2026.

### Gaps
- Databricks page did not state GA/preview status for Iceberg REST writes or list view/multi-table-transaction support.
- Databricks UNDROP / retention semantics for managed Iceberg not read.

---

## 4. AWS Glue Iceberg REST endpoint and Amazon S3 Tables

### Takeaway
AWS offers two IRC surfaces: Glue (broad catalog, Lake Formation authz, SigV4 *and* OAuth token exchange, vended S3 creds) and S3 Tables (purpose-built table buckets with **default-on compaction, snapshot expiry and unreferenced-file removal**). Both support only single-level namespaces; S3 Tables also refuses views, CTAS (`stage-create`), non-purge drops and metadata.json > 50 MB. Published quotas give the clearest real-world scale envelope of any catalog.

### Cited Findings
**Glue IRC**
- Auth: SigV4 or OAuth 2.0 bearer tokens from AWS Sign-In via RFC 8693 token exchange; authorization via IAM, Lake Formation, or LF hybrid mode, enforced on every request; vended S3 creds "bounded by the assumed IAM role and the Lake Formation grants on that role" — [read: https://docs.aws.amazon.com/glue/latest/dg/iceberg-rest-apis.md; https://docs.aws.amazon.com/glue/latest/dg/connect-glu-iceberg-rest.md]
- Each IRC operation maps to an IAM action, LF permission and a CloudTrail event (e.g., LoadNamespaceMetadata → `glue:GetDatabase`) — [read: iceberg-rest-apis.md]
- "AWS Glue only supports single-level namespaces"; prefix follows `/catalogs/{catalog}` with nested multi-catalog hierarchy (e.g., `rmscatalog1:db1`) — [read: connect-glu-iceberg-rest.md]
- Glue Data Catalog quotas (per region, adjustable): 1,000,000 tables/account; 200,000 tables/database; 10,000 databases/catalog and /account; 100,000 table versions/table; 1,000,000 table versions/account; 10,000,000 partitions/table — [read: https://docs.aws.amazon.com/general/latest/gr/glue.md]

**S3 Tables**
- Quotas: **100 table buckets per region per account** (adjustable), 10,000 namespaces/bucket, 10,000 tables/bucket — [read: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-regions-quotas.md]. The bucket quota was raised from 10 to 100 on 2026-10-01 — [secondary: https://aws-news.com/article/2026-10-01-amazon-s3-tables-now-support-up-to-100-table-buckets-per-aws-region-in-an-aws-account]; tables/bucket was raised to 10,000 in Jan 2025 — [secondary: https://aws.amazon.com/about-aws/whats-new/2025/01/amazon-s3-tables-10000-tables-bucket]
- IRC endpoint limitations: `stage-create` unsupported (no CTAS); drop only with purge; maintenance only via S3 Tables APIs; "Multilevel namespaces are not supported"; "OAuth-based authentication is not supported"; only `owner` namespace property; "View-related APIs … are not supported"; "Running operations on a table with a `metadata.json` file over 50MB is not supported" — [read: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-integrating-open-source.md]
- Commit maps to `s3tables:UpdateTableMetadataLocation` — [read: same]
- Maintenance enabled by default: compaction (512 MB default target, 64–512 MB; auto/binpack/sort/z-order; extra cost); snapshot management (`MinimumSnapshots` 1, `MaximumSnapshotAge` 120 h); bucket-level unreferenced file removal (`unreferencedDays` 3, `nonCurrentDays` 10); maintenance runs logged in CloudTrail — [read: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-maintenance.md; https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-table-buckets-maintenance.md]
- Table buckets "provide higher transactions per second (TPS)… compared to self-managed tables in S3 general purpose buckets" (no number given); access managed by IAM and SCPs in the `s3tables` namespace — [read: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables.md]

### Inferences
- [inference] Glue's "table versions per table: 100,000" quota is relevant for high-frequency Iceberg commits if every commit creates a Glue table version; a streaming table committing every minute would reach it in ~70 days unless versions are pruned. Verify before relying on Glue for streaming tables.
- [inference] AWS's default envelope (≈1M tables per account-region in either service) comfortably exceeds "many thousands of tables"; hosted vendors design for 10^5–10^6 tables per tenant.

### Gaps
- No published per-table commit rate or requests-per-second quota for either IRC endpoint.
- Whether the S3 Tables IRC endpoint vends credentials (not stated in the pages read).
- Glue IRC support for views and multi-table transactions not confirmed.

---

## 5. Other hosted catalogs (lighter)

### Takeaway
Every hyperscaler and warehouse vendor now exposes an IRC, but all restrict it: single-level namespaces (Glue, S3 Tables, OneLake), no external CTAS (S3 Tables, Snowflake), no external maintenance (Databricks), tiny metadata limits (BigLake: 1 MB), read-only tables (Confluent, Databricks foreign/Delta). The universal core is CRUD + optimistic commit + vended credentials + OAuth-family auth.

### Cited Findings
**Google BigLake / "Lakehouse runtime catalog" Iceberg REST**
- Page now names the product the Iceberg REST catalog endpoint of the "Lakehouse runtime catalog"; Iceberg V2 GA, V3 Preview; multiple-bucket catalogs recommended; credential vending via `X-Iceberg-Access-Delegation: vended-credentials` (auto-provisioned service account needs Storage Object User); IAM roles BigLake Admin/Editor/Viewer; Google auth via `GoogleAuthManager` in Iceberg 1.10+ — [read: https://docs.cloud.google.com/bigquery/docs/blms-rest-catalog]
- Limitations: "Iceberg V1 tables aren't supported"; tables "don't support fine-grained access control (FGAC)"; only one catalog per bucket for single-bucket catalogs; with vending, don't nest namespaces; Parquet only; **`metadata.json` limited to 1 MB** (increase via account team); no maintenance features described — [read: same]

**Snowflake Horizon Catalog Iceberg REST (and Open Catalog)**
- Horizon IRC is "built on Apache Polaris" at `https://<account>.snowflakecomputing.com/polaris/api/catalog`; read and write of Snowflake-managed Iceberg v2/v3 tables labeled GA; auth via External OAuth, key-pair JWT, PAT, or workload identity (OIDC); Snowflake RBAC privileges map to IRC operations; tables with masking/row-access policies "can be accessed over the Horizon Iceberg REST API and by using Apache Spark" — [read: https://docs.snowflake.com/en/user-guide/tables-iceberg-access-using-external-query-engine-snowflake-horizon]
- Limitations: "Only Snowflake-managed Iceberg tables are supported"; "Equality deletes aren't supported"; "CREATE TABLE AS SELECT (CTAS) from an external engine is not supported"; "You can't roll back a table to a previous snapshot"; "Writing to shared Iceberg tables isn't supported" — [read: same]
- Billing: 0.5 credit per million API calls (Cloud Services), "scheduled to begin in the second half of 2026," subject to change — [read: same]
- Snowflake Open Catalog (managed Polaris) — not researched here; covered by the Polaris researcher.

**Microsoft OneLake / Fabric**
- Endpoint `https://onelake.table.fabric.microsoft.com/iceberg`; operations: config, list/get/head namespaces, list/get/head tables, **create table, commit table updates, drop table, load credentials**; commits use IRC requirements for optimistic concurrency; Delta tables are exposed as virtual Iceberg metadata, read-only; one namespace level (schemas), no `parent` parameter; doc dated 2026-10-09 — [read: https://learn.microsoft.com/en-us/fabric/onelake/table-apis/onelake-iceberg-table-apis]
- Earlier (2025) Fabric blog announced the table APIs as preview — [secondary: https://blog.fabric.microsoft.com/en-us/blog/now-in-preview-onelake-table-apis]

**Dremio**
- Enterprise Catalog "built on Apache Polaris (Incubating)" (2025-04-08); Dremio adds RBAC with row filters and column masks, automatic compaction and vacuum, clustering keys, natural-language search, lineage; any IRC engine can read/write — [read: https://www.dremio.com/blog/introducing-the-enterprise-catalog-powered-by-apache-polaris-incubating/]

**Cloudera**
- Cloudera Runtime 7.3.1 "REST Catalog" (data-sharing context) stress tests: ~30 req/s with 8 GB heap → 5.74% timeouts over ~17 h; ~30 req/s, 16 GB → 1.73%; ~15 req/s, 16 GB → 0.89%; ~15 req/s, 32 GB → 0.21%; guidance: raise heap as throughput rises — [read: https://docs.cloudera.com/runtime/7.3.1/data-sharing-reference/topics/cr-ds-rest-catalog-sizing-guide.html]

**Starburst / Trino Galaxy**
- Starburst documentation positions Apache Polaris as the supported REST catalog across Starburst Enterprise, Galaxy and Trino; I did not find a Galaxy-hosted IRC endpoint for external engines — [secondary: https://docs.starburst.io/introduction/polaris-intro.html]

**Confluent Tableflow**
- Built-in Iceberg REST catalog; "doesn't support credential vending for customer-managed storage buckets"; tables exposed via catalog sync are read-only; Snowflake Horizon can federate to it via a catalog-linked database; Tableflow automates compaction/cleanup of small streaming files — [read: https://docs.confluent.io/cloud/current/topics/tableflow/how-to-guides/catalog-integration/overview.md; https://docs.confluent.io/cloud/current/topics/tableflow/overview.md]

### Inferences
- [inference] BigLake's 1 MB metadata.json cap vs. S3 Tables' 50 MB cap shows hosted vendors push metadata size discipline onto users; a Fidelity catalog must decide whether to store metadata JSON in the DB (size limits bite) or in object storage (pointer only).
- [inference] Per-call billing (Snowflake) and blocked external maintenance (Databricks) show the IRC is becoming a monetized/controlled access point, not just a protocol.
- [inference] The Cloudera numbers are a longevity test, not a capacity ceiling, but they show that a JVM catalog fronting a metastore can be heap-bound at tens of req/s; Fidelity should load-test its own design at 100× its forecast.

### Gaps
- No published request-rate quotas for Glue, S3 Tables, BigLake, Snowflake, OneLake or Databricks IRC endpoints.
- Starburst Galaxy and Snowflake Open Catalog primary docs not read.
- Cloudera page does not say what backs the REST Catalog or the hardware used.

---

## 6. Self-hosted and contrasting designs (Hive, JDBC, Amoro, DuckLake)

### Takeaway
The Iceberg JDBC catalog shows the irreducible core of an Iceberg catalog: one conditional `UPDATE … WHERE metadata_location = ?`. Hive 4.1+ exposes a full IRC (incl. views and multi-table transactions) over the existing HMS RDBMS with Ranger authz. Amoro proves maintenance can live *outside* the catalog. DuckLake inverts the design: all metadata in a SQL DB, no REST server at all.

### Cited Findings
- Iceberg JDBC catalog commit: `UPDATE <catalog table> SET metadata_location = ?, previous_metadata_location = ? WHERE catalog_name = ? AND table_namespace = ? AND table_name = ? AND metadata_location = ? AND (record_type = 'TABLE' OR …)`; success iff `updatedRecords == 1`, else `CommitFailedException` — [read: https://raw.githubusercontent.com/apache/iceberg/main/core/src/main/java/org/apache/iceberg/jdbc/JdbcUtil.java; https://raw.githubusercontent.com/apache/iceberg/main/core/src/main/java/org/apache/iceberg/jdbc/JdbcTableOperations.java]
- Hive Metastore IRC (HIVE-28059, fix version 4.1.0): config `metastore.catalog.servlet.port`; auth OAuth2, JWT (default), simple, none; authorization via HMS database/table authorization, e.g. Apache Ranger via `HiveMetaStoreAuthorizer` pre-event listener; the Docker image's `/v1/config` advertises namespaces, tables, register, rename, metrics, **`POST /v1/{prefix}/transactions/commit`**, and full view endpoints; page last updated 2025-11-14 — [read: https://hive.apache.org/docs/latest/admin/iceberg-rest-catalog/]
- Hive releases: 4.1.0 (2025-07-31), 4.2.0 (2025-11-23), 4.2.1 (2026-08-24) — [read: https://hive.apache.org/general/downloads/]
- Hive 4.1.0's REST server reportedly works from the standalone HMS package but failed from the common package, fixed in 4.2.0 — [secondary: https://www.mail-archive.com/issues@hive.apache.org/msg250770.html]
- Apache Amoro: "Lakehouse management system"; AMS provides "self-optimizing, data expiration" plus a unified catalog service that "can also be combined with existing metadata services"; optimizers run asynchronously; README still says "(incubating)"; latest release v0.8.1-incubating (2025-09-11); ~148 contributors — [read: https://raw.githubusercontent.com/apache/amoro/master/README.md; https://api.github.com/repos/apache/amoro/releases]
- DuckLake: all catalog and table metadata in a SQL database that "supports ACID operations and primary keys" (DuckDB, PostgreSQL, … Spanner); one SQL transaction per commit, ACID across tables; "There is no additional catalog server or additional API to integrate with"; claims "a PostgreSQL-backed DuckLake will already be able to scale to hundreds of terabytes and thousands of compute nodes"; data/positional delete files are "fully compatible with Apache Iceberg" — [read: https://ducklake.select/manifesto/]
- DuckLake repo: MIT license, 3,355 stars, ~98 contributors, created 2025-03-03, no GitHub releases — [read: https://api.github.com/repos/duckdb/ducklake]

### Inferences
- [inference] Every self-hosted design surveyed (JDBC, Hive, Lakekeeper, Gravitino-JDBC, UC, DuckLake) puts catalog state in a relational database; I found none using MongoDB. (Whether Polaris' NoSQL persistence supports MongoDB is for the Polaris researcher to confirm.)
- [inference] DuckLake is not an IRC alternative for Fidelity (engines would need DuckLake support), but it is evidence that a SQL database can carry far more metadata than a pointer, and that commit contention is minimized by keeping the critical section to one DB transaction.

### Gaps
- Hive IRC credential vending and its scale behavior not documented in the page read.
- Amoro graduation status beyond the README (may have graduated; not verified).

---

## 7. Feature-convergence matrix

### Takeaway
Converged (nearly everyone): table/namespace CRUD with optimistic commit, vended short-lived credentials, OAuth/OIDC-family auth, hierarchical privileges with inheritance, per-operation audit, and *some* maintenance owner. Divergent and spec-optional: views, multi-table transactions, multi-level namespaces, CTAS staging, remote signing. Vendor-driven: row/column enforcement for external engines, multi-format/AI assets, federation into a platform, per-call billing, and restrictions that keep maintenance or writes inside the vendor's engine.

### Cited Findings
Legend: Y = documented; N = documented absent; P = partial/conditional; Plus = commercial edition only; ? = not found in sources read. Cell sources are the sections above.

| Feature | Spec/client role | Lakekeeper | Gravitino IRC | UC OSS | Databricks UC | Glue IRC | S3 Tables | BigLake | Snowflake Horizon | OneLake | Hive IRC | Iceberg JDBC |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Namespace/table CRUD + optimistic commit (requirements/CAS) | Spec core; all clients | Y | Y (via backend) | Y (since 0.7.0) | Y (managed Iceberg) | Y | Y (no stage-create) | Y | Y (no external CTAS) | Y | Y | Y |
| Multi-level namespaces | Spec allows | Y | Y | ? | ? | N | N | P | ? | N | ? | ? |
| Multi-table transactions | Spec optional | Y | N | ? | ? | ? | not listed | ? | ? | N | Y (advertised) | N/A |
| Views | Spec optional | Y | Y (no register) | ? | ? (separate page) | ? | N | ? | ? | N | Y | Y (schema V1) |
| Credential vending | Spec mechanism; engines rely on it | Y | Y | Y | Y (1 h) | Y (LF-bounded) | ? | Y | Y | Y | ? | N |
| S3 remote signing | Spec-adjacent | Y | ? | ? | ? | ? | ? | ? | ? | ? | ? | N |
| OAuth/OIDC/JWT auth | Spec auth hooks | Y | Y | Y | Y | Y (+SigV4) | N (SigV4 only) | Y (Google) | Y | ? | Y | N |
| Hierarchical grants + inheritance | Operator | Y (OpenFGA) | Y | Y (Casbin) | Y | Y (LF) | P (IAM) | P (IAM roles) | Y | ? | Y (Ranger) | N |
| Ownership / self-service delegation | Operator | Y | Y | Y | Y | ? | ? | ? | Y | ? | ? | N |
| Explicit DENY | Operator | N (additive) | Y | ? | ? | ? | ? (IAM deny likely; not read) | ? | ? | ? | ? (Ranger deny likely; not read) | N |
| Row/column enforcement for external engines | Vendor | N (explicit) | ? | ? | P (separate ABAC page) | ? | ? | N (explicit) | P | ? | ? | N |
| Server-side maintenance (compaction/expiry/orphans) | Operator (not spec) | Plus (OSS: metadata cleanup + purge) | P (optimizer policy) | N (drop cleanup only) | internal; external blocked | ? | Y (default on) | ? | ? | ? | N | N |
| Soft-delete / undrop | Operator | Y | ? | N (not a recovery window) | ? | ? | ? | ? | ? | ? | ? | N |
| Change events | Operator | Y (CloudEvents → Kafka/NATS) | Y (listeners) | ? | ? | P (CloudTrail) | P (CloudTrail) | ? | ? | ? | P (listeners) | N |
| Per-request authz audit | Operator/regulator | Y | Y | ? | ? | Y (CloudTrail per op) | Y | ? | ? | ? | ? | N |
| Multi-tenant unit | Operator | project → warehouse | metalake → catalog | metastore → catalog | metastore → catalog | account → catalog | account → table bucket | project → catalog | account → database | workspace → item | HMS catalog | catalog_name column |
| Multi-region | Operator | N (single PG primary) | Y (IRC proxying) | ? | ? | ? | ? | P (regional endpoints) | ? | ? | ? | N |
| Federation to other catalogs | Vendor/platform | N | Y | ? | Y (foreign Iceberg, read-only) | Y (federated catalogs) | N | ? | Y (catalog-linked DB) | N | N | N |
| Multi-format / AI assets | Vendor | P (generic tables) | Y (Lance, filesets, models) | Y (Delta, volumes, models) | Y | Y | N | ? | N | P (Delta as virtual Iceberg) | Y (Hive tables) | N |
| Published metadata size cap | — | ? | ? | ? | ? | ? | 50 MB | 1 MB | ? | ? | ? | ? |
| Per-call billing | Vendor | N | N | N | ? | ? | ? | ? | 0.5 credit / M calls | ? | N | N |

**Published scale envelopes**
| Catalog | Tables | Other limits | Source |
|---|---|---|---|
| Glue Data Catalog | 1,000,000/account-region; 200,000/database | 10,000 databases/catalog; 100,000 table versions/table | [read: https://docs.aws.amazon.com/general/latest/gr/glue.md] |
| S3 Tables | 10,000/table bucket × 100 buckets/region | 10,000 namespaces/bucket; metadata.json ≤ 50 MB via IRC | [read: s3-tables-regions-quotas.md; s3-tables-integrating-open-source.md] |
| Databricks UC | 10,000/schema; 1,000,000/metastore (fixed) | 1,000 catalogs; 10,000 schemas/catalog | [read: https://docs.databricks.com/aws/en/resources/limits] |
| BigLake | not published | metadata.json ≤ 1 MB (raisable) | [read: https://docs.cloud.google.com/bigquery/docs/blms-rest-catalog] |
| Cloudera REST Catalog | — | ~15–30 req/s sustained in longevity tests; timeouts 0.2–5.7% depending on heap | [read: Cloudera sizing guide] |
| Lakekeeper, Gravitino, UC OSS, Hive | none published | — | (searched) |

### Inferences
- [inference] **Essential (spec + clients):** config endpoint, namespaces, table load/create/commit with requirement checks, rename/drop/purge semantics, vended credentials with `expires-at-ms`, OAuth2/OIDC bearer auth. Every catalog surveyed has these; clients break without them.
- [inference] **Essential for an enterprise operator (converged across serious catalogs, though not spec):** hierarchical privileges with ownership and inheritance; per-request audit; a tenancy unit that is also a storage boundary (warehouse/bucket/catalog); a maintenance owner (in-catalog or adjacent); events or a log for downstream lineage/governance; location-overlap enforcement so vended creds cannot reach another table.
- [inference] **Spec-optional features hosted vendors omit:** views (S3 Tables, OneLake), multi-table transactions (Gravitino, OneLake), multi-level namespaces (Glue, S3 Tables, OneLake), CTAS staging (S3 Tables, Snowflake external). These are real gaps for multi-engine users; Lakekeeper and Hive support them.
- [inference] **Vendor-driven:** row/column enforcement for external engines (tied to the vendor's engine), multi-format/AI assets, platform federation, per-call billing, blocking external maintenance, read-only treatment of the vendor's native format. A from-scratch Fidelity catalog can skip these.
- [inference] **Unique to one implementation:** case-insensitive/case-preserving identifiers, ContractVerification veto hook, OPA bridge, remote signing hardening (Lakekeeper); explicit DENY and geo-proxy IRC (Gravitino); z-order compaction by default (S3 Tables).

### Gaps
- Many "?" cells: the docs read did not state the feature either way; do not read "?" as "N".

---

## Decisions this implies

### Takeaway
The evidence supports building a **thin, Java, relational-backed core** that copies Lakekeeper's design patterns and keeps maintenance, policy and FGAC as separate services, rather than adopting Lakekeeper (Rust, open-core, single-vendor) or forking it. Polaris remains the "adopt" alternative to beat; Gravitino and UC are poor fits as Fidelity's authoritative Iceberg catalog.

### D1. Build vs adopt vs fork
- **Options:** (a) adopt OSS Lakekeeper (+ optionally buy Plus); (b) fork Lakekeeper; (c) adopt Polaris; (d) adopt Gravitino or UC OSS; (e) build from scratch.
- **Evidence:** Lakekeeper OSS covers the spec, multi-table commits, vending, remote signing, soft delete, CloudEvents and OpenFGA authz (§1); day-2 features are Plus-only (§1); one maintainer dominates commits, 0.x versions, Rust, Postgres-only (§1). Gravitino lacks multi-table transactions and has authz only in auxiliary mode (§2). UC OSS gained Iceberg writes in Oct 2026 (§3). Hosted catalogs all restrict the spec (§4–5).
- **Recommendation:** (e) build, scoped strictly to the converged core (§7 "Essential" rows), with Lakekeeper as the reference design and as a conformance/benchmark baseline in a PoC. Reject (b) unless Fidelity commits to Rust ownership; reject (d). Keep (c) Polaris as the explicit fallback, and require the build plan to beat it on scope and operability. [inference]

### D2. Metadata store (PostgreSQL vs MongoDB)
- **Options:** PostgreSQL (or Aurora/AlloyDB-class managed PG); MongoDB; distributed SQL (CockroachDB/Spanner-class).
- **Evidence:** Every self-hosted catalog surveyed uses an RDBMS; Lakekeeper relies on row locks, multi-table transactions, ICU collation and replica-lag-bounded reads; the JDBC catalog needs only a conditional UPDATE; DuckLake requires "ACID operations and primary keys" (§1, §6).
- **Recommendation:** PostgreSQL unless Fidelity's MongoDB platform team can guarantee multi-document transactions, majority write concern and case-insensitive unique indexes at the required HA level. MongoDB can do single-table CAS cleanly; multi-table commits and cross-entity invariants are where it costs more. [inference]

### D3. Commit protocol
- **Evidence:** the converged mechanism is CAS on `metadata_location` (JDBC, Lakekeeper backstop, S3 Tables `UpdateTableMetadataLocation`, OneLake requirements) (§1, §4–6); Lakekeeper adds ordered row locks for multi-table commits and returns 409 under contention.
- **Recommendation:** CAS per table plus ordered locking for `transactions/commit`; return 409 (retryable) on contention; skip no-op commits; enforce a clock-skew guard. [inference]

### D4. Authorization model
- **Options:** embedded ReBAC (OpenFGA-style), RBAC tables in the catalog DB, external PDP (OPA/Cedar/Ranger/enterprise entitlement service).
- **Evidence:** all serious catalogs converge on hierarchical privileges + ownership + inheritance; Gravitino adds DENY; Lakekeeper's OpenFGA is additive-only and needs a co-located extra service; no OSS catalog enforces row/column filters for external engines (§1, §2, §5).
- **Recommendation:** RBAC with inheritance, ownership and explicit DENY stored in the catalog DB, behind a pluggable decision interface so Fidelity's entitlement system can be the PDP. Leave row/column filtering to engines (or a later FGAC layer) rather than the catalog. [inference]

### D5. Storage access: vending vs remote signing
- **Evidence:** vending is near-universal; remote signing is documented only in Lakekeeper; revocation latency = token TTL (1 h default in Lakekeeper and Databricks) (§1, §3).
- **Recommendation:** vending with short, configurable TTLs and per-table prefix scoping, plus location-overlap checks; consider remote signing only for on-prem S3-compatible storage where per-request checks matter more than throughput. [inference]

### D6. Table maintenance ownership
- **Evidence:** S3 Tables does it by default; Lakekeeper monetizes it; Gravitino and Amoro run it as an optimizer; Databricks blocks external maintenance (§1–4, §6).
- **Recommendation:** keep it out of the catalog's request path: a separate maintenance service (build, or adopt Amoro) that acts through the catalog with its own identity and is triggered by commit events. [inference]

### D7. Events and audit
- **Evidence:** Lakekeeper emits CloudEvents to Kafka/NATS; Gravitino has listeners; AWS logs every IRC call to CloudTrail (§1, §2, §4).
- **Recommendation:** transactional outbox in the metadata DB → CloudEvents on Kafka; a separate, immutable per-request authz audit stream. [inference]

### D8. Tenancy and multi-region
- **Evidence:** the converged unit is a two-level tenant → warehouse/bucket/catalog that is also a storage boundary; only Gravitino documents geo proxying and no OSS catalog documents active-active multi-region writes (§7).
- **Recommendation:** tenant (line of business) → warehouse (storage + region boundary); one write region per warehouse with async DR, unless the business case for active-active justifies a distributed SQL store. [inference]

---

## Open questions for the enterprise

### Takeaway
The build decision depends less on catalog features than on Fidelity's entitlement source of truth, its RPO/RTO, its retention obligations, and which engines must write.

- Which system is the entitlement source of truth (AD/Entra groups, SailPoint, Ranger, an internal service), and must the catalog defer to it per request (as in Lakekeeper Plus admission gates) or sync from it?
- Is row/column-level security required for *external* engines, or can FGAC stay in the engines (Trino/Snowflake/Databricks) that support it?
- Required RPO/RTO per warehouse, and is active-active multi-region a hard requirement or is single-writer + DR acceptable?
- Which engines must **write** (Spark, Flink, Trino, Snowflake, Databricks, Dremio)? Each hosted vendor restricts external writes differently (§5).
- How do SEC 17a-4 / FINRA retention and legal hold interact with soft delete, purge, snapshot expiry and orphan-file removal? Who may skip soft deletion?
- Acceptable credential-revocation latency (vended-token TTL), and is per-request remote signing needed for any data class?
- Expected peak commit rate per table (streaming) and total catalog request rate; is metadata.json size (1 MB BigLake, 50 MB S3 Tables caps) a concern for wide/high-churn tables?
- Is there an existing PostgreSQL or MongoDB platform team with HA SLAs, and which offers multi-document/multi-row transactions at Fidelity's required availability?
- Is Rust an acceptable language for a critical service at Fidelity (determines whether a Lakekeeper fork is even an option)?
- Would Fidelity buy commercial support (Vakamo, Snowflake/Dremio for Polaris) for a catalog, or must it be fully in-house?

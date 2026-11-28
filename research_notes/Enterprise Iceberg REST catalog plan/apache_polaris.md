# Apache Polaris: architecture, persistence, security, operations, and a vendor-bloat assessment (as of 2026-11-06)

**How to read the provenance tags.**
- `[read: URL]` means I read the primary source myself: raw file, API JSON, or the full issue or email text.
- `[read via WebFetch summary: URL]` means a model-paraphrased summary of the page. Treat exact wording as approximate.
- `[secondary: URL]` means a search-engine snippet or an aggregator. I did not read the page.
- `[inference]` is my own reasoning.

**Source snapshot.** Code and docs were read from a shallow clone of `apache/polaris` `main` at commit `6c268f62252a7d074190215cb693cb81cd00d3b5` (committed 2026-11-06; `version.txt` = `1.9.0-SNAPSHOT`). `polaris-tools` `main` was cloned the same day. Short forms used below:
- `SRC/` = `https://github.com/apache/polaris/blob/6c268f62252a7d074190215cb693cb81cd00d3b5/`
- `DOCS/` = `SRC/site/content/in-dev/unreleased/` (the unreleased docs on `main`, which track 1.8.0 plus whatever has landed since)

Line counts are non-blank lines that I measured locally with a script. They are not `cloc` output.

---

## 1. History, governance, and releases

### Takeaway
Snowflake open-sourced Polaris on 2024-07-30. It entered the Apache Incubator on 2024-08-09 and graduated to a Top-Level Project on 2026-02-15. Twelve releases have shipped; the latest is 1.8.0 (2026-09-28). The PMC is split evenly between Dremio and Snowflake, four members each, and Dremio holds the chair. In commit volume, Dremio-affiliated engineers dominate. Dremio is now part of SAP.

### Cited Findings
**Founding and incubation**
- Snowflake announced on 2024-07-30 that "Polaris Catalog" was open source under Apache 2.0, alongside a managed Snowflake service then in public preview. The announcement said Dremio was helping bring Project Nessie capabilities into Polaris, and that Confluent, Google Cloud, Microsoft and Salesforce, among others, "intend to add integrations or make contributions". It did not state an intent to donate to the ASF. [read via WebFetch summary: https://www.snowflake.com/en/blog/polaris-catalog-open-source/]
- The managed service was renamed Snowflake Open Catalog and reached GA around October 2024. [secondary: search snippet; I did not read a primary source for the rename date]
- Incubator status page: "2024-08-09 Project enters incubation. 2026-02-15 Graduation as TLP." The champion was JB Onofré. Mentors were François Papon, Holden Karau, JB Onofré, Kent Yao and Ryan Blue. [read: https://incubator.apache.org/projects/polaris.html]
- The ASF committee record shows `established: 02/2026`, chair `jbonofre`, 19 PMC members, and quarterly board reports in Feb/May/Aug/Nov. [read: https://whimsy.apache.org/public/committee-info.json]
- The project announced graduation in a blog post dated 2026-02-19. [read: SRC/site/content/blog/2026/02/19/graduation-tlp.md]

**Releases** (GitHub releases API) [read: https://api.github.com/repos/apache/polaris/releases]

| Version | Date | Notes |
|---|---|---|
| 0.9.0-incubating | 2025-02-25 | flagged prerelease |
| 1.0.0-incubating | 2025-07-11 | |
| 1.0.1-incubating | 2025-08-20 | |
| 1.1.0-incubating | 2025-09-19 | |
| 1.2.0-incubating | 2025-10-28 | |
| 1.3.0-incubating | 2026-01-16 | |
| 1.4.0 | 2026-04-21 | first non-incubating release |
| 1.4.1 | 2026-05-01 | |
| 1.5.0 | 2026-05-18 | |
| 1.6.0 | 2026-07-09 | |
| 1.7.0 | 2026-08-02 | |
| 1.8.0 | 2026-09-28 | latest |

- A `0.10.0-beta-incubating` line exists only as rc tags, with no GitHub release. [read: https://api.github.com/repos/apache/polaris/tags]
- Every 1.x release from 1.2.0 to 1.8.0 lists "Breaking changes" in the CHANGELOG. Examples:
  - 1.3.0 removed EclipseLink entirely and renamed the request-ID header.
  - 1.6.0 began rejecting entity names that had previously been accepted.
  - 1.8.0 stopped creating the JDBC schema during bootstrap and changed authorizer SPI inputs.

  [read: SRC/CHANGELOG.md]

**Repository size and activity** (2026-11-06)
- 2,111 stars, 568 forks, 428 open issues, created 2024-05-29. [read: https://api.github.com/repos/apache/polaris]

**PMC composition** (self-declared affiliations on the project's community page) [read: SRC/site/content/community/_index.adoc]

| Affiliation | PMC members |
|---|---|
| Dremio (4, incl. the chair) | Dutra, Bourlatchkov, Onofré (chair), Stupp |
| Snowflake (4) | Huo, Collado, Spitzer, Gu |
| Databricks (2) | Anoop Johnson, Ryan Blue |
| Microsoft (1) | Agrawal |
| LanceDB (1) | Jack Ye |
| Confluent (1) | Roesler |
| Bloomberg (1) | Sung Yun |
| Redpanda (1) | Akidau |
| No affiliation listed (4) | Papon, Karau, Yao, Zheng |

- Committers listed with an affiliation are mostly Dremio (Bhat, Laporte) and Snowflake (Filippova, Jiang, Singh), plus Starburst (Ebihara). The same page lists Anoop Johnson under Databricks, not Google. [read: SRC/site/content/community/_index.adoc]

**Commit volume** (all-time contributors API)
- `renovate-bot` 1,391 (dependency bumps). Then `snazy` (Robert Stupp, Dremio) 545, `adutra` (Dremio) 321, `MonkeyCanCode` (Yong Zheng) 297, `jbonofre` (Dremio) 197, `dimas-b` (Dremio) 177, `flyrain` (Yufei Gu, Snowflake) 138. [read: https://api.github.com/repos/apache/polaris/contributors]

**Who wrote which subsystem** (GitHub commits API, per path)
- `persistence/nosql`: `snazy` 95 commits; the next human is `adutra` at 13.
- The policy package: `HonahX` (Snowflake) 7, `flyrain` (Snowflake) 4.
- `persistence/relational-jdbc`: `XN137` 17, `adutra` 11.

[read: https://api.github.com/repos/apache/polaris/commits?path=persistence/nosql (and the same endpoint with the other paths)]

**Vendor products built on Polaris**
- Dremio's commercial product is described as "Dremio's Open Catalog, built on Apache Polaris". Dremio frames itself as having co-created Polaris with Snowflake and as "spearheading its development". Its roadmap items include fine-grained cross-engine policies, Volumes/unstructured data, Generic Tables, NoSQL persistence and a UI. [read via WebFetch summary: https://www.dremio.com/blog/why-dremio-co-created-apache-polaris-and-where-its-headed/ (2025-06-25)]
- SAP announced on 2026-05-04 that it would acquire Dremio. SAP said it would deliver a catalog "built on Apache Polaris and the open Apache Iceberg REST Catalog API" for SAP Business Data Cloud, with closing expected in Q3 2026. [read via WebFetch summary: https://www.dremio.com/newsroom/sap-to-acquire-dremio-to-unify-sap-and-non-sap-data-to-power-agentic-ai/]
  - Dremio's site now shows a banner reading "Dremio is now part of SAP". [read via WebFetch summary, same page]
  - Completion was reported in July 2026. [secondary: https://www.vermoegenszentrum.ch/en/stock-exchange-and-markets/news/brief-sap-completes-acquisition-dremio]

### Inferences
- The roadmap is set by two commercial operators of hosted Polaris, Snowflake (Open Catalog) and Dremio/SAP (Enterprise/Open Catalog). Both run Polaris as a multi-customer service, which explains the emphasis on realms, the internal identity provider, the catalog-of-catalogs model, federation and pluggable backends. [inference]
- Dremio engineers wrote the bulk of the code. The NoSQL persistence layer in particular is close to a one-author subsystem (95 commits from one engineer). That is a bus-factor and continuity risk now that Dremio belongs to SAP. [inference]
- Releases are frequent (about monthly in 2026), and every one carries breaking notes. Anyone running Polaris, or forking it, should budget for continuous upgrade work. [inference]

### Gaps
- I did not read the Apache board minutes or the graduation resolution text. The dates come from the incubator page and whimsy.
- I did not find a primary source for the Open Catalog rename or GA date, or for the SAP deal's completion date.
- I found no affiliation-weighted commit statistics (for example from Bitergia). My affiliation mapping uses the community page for named individuals only.

---

## 2. Architecture: runtime, modules, and entity model

### Takeaway
Polaris is a Java 21 Quarkus application with 85 Gradle modules, plus Spark-plugin variants. About 131.5k non-blank Java lines sit under `src/main`; 40 of the 85 modules (about 34.6k lines) are the NoSQL persistence stack. Everything Polaris manages is one generic "entity" type in a tree: root, principals, roles, catalogs, namespaces and table-likes. Four layers sit on that entity: a resolver, an authorizer, an entity cache and a metastore manager.

### Cited Findings
**Build and runtime**
- The build requires Java 21. Modules come from `gradle/projects.main.properties` (85 entries). Spark plugin projects are added for Spark 3.5 (Scala 2.12 and 2.13) and Spark 4.0 (Scala 2.13). [read: SRC/settings.gradle.kts, SRC/gradle/projects.main.properties, SRC/plugins/spark/spark-scala.properties]
- The pinned Quarkus platform is 3.40.1.1. [read: SRC/gradle/libs.versions.toml]
- `runtime/service` pulls about 20 Quarkus extensions, including oidc, micrometer + prometheus, opentelemetry, smallrye-fault-tolerance, smallrye-health, rest-client, jdbc-postgresql and hibernate-validator. [read: SRC/runtime/service/build.gradle.kts]

**Module layout**
- API layer: generated OpenAPI service modules `api/iceberg-service`, `api/management-service`, `api/polaris-catalog-service` and `api/openlineage-service`.
- Core: `polaris-core`, the entity model, persistence SPI, resolver, authorizer and storage integrations (33,731 lines).
- Server: `runtime/service`, the REST handlers (31,408 lines). Its largest packages are `catalog/` (13,011), `admin/` (4,175), `auth/` (3,133), `events/` (2,622) and `config/` (2,171).
- Other runtime modules: `runtime/admin` (the admin CLI), `runtime/server` and `runtime/distribution`.
- Persistence: `persistence/relational-jdbc` (4,040 lines), `persistence/dynamodb-atomic` (507, "Work in progress"), and `persistence/nosql/**` (40 modules, 34,617 lines).
- Extensions: `extensions/*`, totalling 5,646 lines:
  - OPA authorizer, 1,457
  - Ranger authorizer, 992
  - Hive, Hadoop and BigQuery federation, 241
  - Kafka events
  - semantic models, 800
  - lineage and OpenLineage, 1,090 including its API module
  - metrics-reports
- Also present: `plugins/spark` (2,170), a Python client and CLI (`client/python`, about 15.8k Python lines) and a Helm chart.

[read: local LOC count over the clone; module list from SRC/gradle/projects.main.properties]

**Entity model**
- Entity types: `ROOT, PRINCIPAL, PRINCIPAL_ROLE, CATALOG, CATALOG_ROLE, NAMESPACE, TABLE_LIKE, TASK, FILE, POLICY, SEMANTIC_MODEL`. Subtypes: `ICEBERG_TABLE, ICEBERG_VIEW, GENERIC_TABLE`. [read: SRC/polaris-core/src/main/java/org/apache/polaris/core/entity/PolarisEntityType.java, PolarisEntitySubType.java]
- The docs describe catalogs, nested namespaces (for example `a.b.c.d.e.f.g`), tables (Iceberg, plus Delta/Hudi as generic tables), views, principals, principal roles, catalog roles, policies and privileges. Every catalog has a storage type: S3, Azure, GCS, or FILE for testing. Catalog properties are returned to clients in `/config`, so they must not hold secrets. [read: DOCS/entities.md]

**Metastore manager and helpers**
- `PolarisMetaStoreManager` is the business-level SPI. It has these implementations:
  - `AtomicOperationMetaStoreManager` (1,969 lines). Its javadoc says it "only relies on one-shot atomic operations into a BasePersistence implementation without any kind of open-ended multi-statement transactions". JDBC uses this one.
  - `TransactionalMetaStoreManagerImpl`, used by the in-memory TreeMap store.
  - `NoSqlMetaStoreManager`.
  - `TransactionWorkspaceMetaStoreManager`, which buffers multi-table commits.
- Below them, `BasePersistence` is the storage SPI. Its `writeEntity(..., originalEntity)` takes "original state of the entity to use for compare-and-swap purposes".

[read: SRC/polaris-core/src/main/java/org/apache/polaris/core/persistence/AtomicOperationMetaStoreManager.java, BasePersistence.java, TransactionWorkspaceMetaStoreManager.java]

- The `Resolver` resolves the principal, its roles, the catalog and the full path (namespace chain and leaf). It then bulk-validates cached entities against current `entity_version` and `grant_records_version` with `loadEntitiesChangeTracking`. [read: SRC/polaris-core/src/main/java/org/apache/polaris/core/persistence/resolver/Resolver.java, lines ~560–630]

### Inferences
- The "everything is an entity row with JSON properties" design keeps the schema tiny (§3). The price is generic code paths: resolution walks the hierarchy entity by entity, and grants are rows keyed by entity id. That is the root of the N+1 behaviour in §7. [inference]
- About 26% of the `src/main` Java lines are NoSQL persistence. A single-backend enterprise build would carry none of it. [inference from the counts above]

### Gaps
- I did not build the project, so I have no measured jar count, startup time or memory footprint. The only footprint figure is the Docker image size in §6.

---

## 3. Persistence and the atomic table commit

### Takeaway
- **Backends.** Production-ready means relational JDBC: PostgreSQL, with H2 and CockroachDB schemas also present. MongoDB is "beta" and DynamoDB "experimental". EclipseLink was deprecated in 1.0 and removed in 1.3.0.
- **Where things live.** The database holds only a pointer (`metadata-location`) plus a small summary per table. Full `metadata.json`, manifests and data stay on object storage.
- **JDBC commit.** Polaris writes the new metadata.json, then does a conditional UPDATE on the table's entity row: `entity_version` and `grant_records_version` must still match. Multi-table commits run all those UPDATEs in one DB transaction.
- **NoSQL commit.** A git-like design: every change inside a catalog is a CAS on a single per-catalog "HEAD" pointer.

### Cited Findings
**Backend status**
- The metastores page lists in-memory ("Test only"), PostgreSQL JDBC ("Ready to use… Recommended for production"), MongoDB ("Experimental… Currently in beta") and DynamoDB ("Experimental"). [read: DOCS/metastores/_index.md]
- The MongoDB page says the backend "is currently in **beta**". Setup is: `polaris.persistence.type=nosql`, backend `MongoDb`, bootstrap with the admin tool. It "requires regular maintenance to remove stale data… recommended maintenance frequency is once per day". [read: DOCS/metastores/nosql-mongodb.md]
- EclipseLink was "deprecated since 1.0.0" and "completely removed" in 1.3.0-incubating. [read: SRC/CHANGELOG.md]
- In the EclipseLink era (March 2025), a user with 60 concurrent clients reported serializable-transaction failures under load. A maintainer replied that "the current eclipse link solution puts all operations in a Postgres transaction block, which impacts the performance a lot", and that it would be replaced with single SQL commands. [read: https://github.com/apache/polaris/issues/1202]

**JDBC schema** (version 6)
- Eight tables:
  - `version`
  - `entities`: PK `(realm_id, id)`, unique `(realm_id, catalog_id, parent_id, type_code, name)`, `properties`/`internal_properties` JSONB, `entity_version`, `grant_records_version`, `location_without_scheme`
  - `grant_records`: PK of realm, securable and grantee ids plus privilege code
  - `principal_authentication_data`: hashed client secrets
  - `policy_mapping_record`
  - `events`
  - `scan_metrics_report`
  - `commit_metrics_report`

  [read: SRC/persistence/relational-jdbc/src/main/resources/postgres/schema.sql]
- Index history: version 6 (1.8.0) corrected `idx_locations` and declared `idx_grants_realm_grantee`/`idx_grants_realm_securable`. Since 1.8.0 the JDBC backend no longer creates its schema during bootstrap. [read: SRC/CHANGELOG.md §1.8.0]
- The production guide says Polaris does not create the database schema. It must exist before bootstrapping, as a DBA task. [read: DOCS/configuration/configuring-polaris-for-production/_index.md]

**What the table entity stores** (`buildTableMetadataPropertiesMap`)
- Location properties, `format-version`, `table-uuid`, `current-schema-id`, `current-snapshot-id`, `last-column-id`, `next-row-id`, `last-sequence-number`, `last-updated-millis`, `default-sort-order-id`, `default-spec-id` and `last-partition-id`. The `metadata-location` pointer is set on the entity.
- `doRefresh()` reads the full metadata with `TableMetadataParser.read(fileIO, metadataLocation)` from object storage.

[read: SRC/runtime/service/src/main/java/org/apache/polaris/service/catalog/iceberg/LocalIcebergCatalog.java lines 2115–2170, 2534–2570]

- I found no server-side `TableMetadata` cache. A grep for `METADATA_CACHE`/`TableMetadataCache` in `polaris-core` and `runtime/service` returned nothing. [read: grep over clone; negative result]

**Single-table commit** (`BasePolarisTableOperations.doCommit`, LocalIcebergCatalog.java:2214–2400)
1. Resolve the table path once.
2. Validate requested locations against allowed locations, check for overlap with sibling tables, and require metadata files inside the table directory.
3. Load a FileIO with subscoped read/write/list credentials.
4. Pre-check that the entity's current `metadata-location` equals the client's base. A mismatch gives `CommitFailedException`.
5. Write the new `metadata.json` (vN+1).
6. Call `createTableLike`/`updateTableLike`, which performs the metastore CAS.
7. On a known failure, delete the orphan metadata file. On an ambiguous persistence outcome (for example a dropped connection), leave the file in place, per Iceberg's commit contract.

[read: same file]

**The CAS itself**
- JDBC `persistEntity` issues an UPDATE whose WHERE clause binds `id`, `catalog_id`, `entity_version`, `grant_records_version` and `realm_id`. Zero rows updated raises `RetryOnConcurrencyException("Entity … concurrently modified")`. The code comment reads: "CAS on both entity_version and grant_records_version because grant operations only bump grant_records_version". [read: SRC/persistence/relational-jdbc/src/main/java/org/apache/polaris/persistence/relational/jdbc/JdbcBasePersistenceImpl.java lines 130–245]
- As of 1.8.0, concurrent commits that hit a stale sequence number return a retryable 409 rather than a 400. [read: SRC/CHANGELOG.md]

**Multi-table commit** (`commitTransaction`)
- Swaps in `TransactionWorkspaceMetaStoreManager` so that every mutation goes "into an in-memory collection that we can commit as a single atomic unit after all validations". [read: SRC/runtime/service/src/main/java/org/apache/polaris/service/catalog/iceberg/IcebergCatalogHandler.java lines 1496–1530]
- That unit is applied by `updateEntitiesPropertiesIfNotChanged` into `ms.writeEntities(...)`. JDBC runs every per-entity CAS inside `runWithinTransaction`. [read: AtomicOperationMetaStoreManager.java lines 960–1000; JdbcBasePersistenceImpl.java lines 150–175]
- `commitTransaction` is rejected for federated and static-facade catalogs. [read: IcebergCatalogHandler.java]

**NoSQL design**
- The only database requirement is "compare and swap" on a single row. Rows are capped at 350 kB.
- The whole catalog's state is referenced by a HEAD pointer ("think: Git HEAD"). Commits build a commit log.
- Named pointers per catalog: `cat/%d/heads/main` (all catalog content), `cat/%d/roles`, plus realm-wide `catalogs`, `principals`, `principal-roles`, `grants`, `policy-mappings` and so on.
- Indexes of "potentially a few 100,000" entries spill into stripes.
- Snowflake-style 64-bit IDs need leased node IDs (max 1024 nodes).
- Old commits and unreferenced objects need a maintenance service. The README notes "realm state machine is not fully implemented yet" and "maintenance service not yet in the code base". The latter is now stale, because maintenance modules and admin-tool commands exist.

[read: SRC/persistence/nosql/persistence/README.md; SRC/site/content/in-dev/unreleased/configuration/config-sections/smallrye-polaris_node.md]

- The code confirms that catalog commits CAS `CATALOG_STATE_REF_NAME_PATTERN = "cat/%d/heads/main"`. [read: SRC/persistence/nosql/persistence/metastore-types/src/main/java/org/apache/polaris/persistence/nosql/coretypes/catalog/CatalogStateObj.java:45; SRC/persistence/nosql/persistence/metastore/src/main/java/org/apache/polaris/persistence/nosql/metastore/NoSqlMetaStore.java:538–545]
- The MongoDB backend does the pointer CAS with `refs().updateOne(and(eq(_id), eq(pointer, expected), eq(created_at)), set(pointer, …))`. Objects are written with `insertOne`/`bulkWrite`. It uses no MongoDB multi-document transactions: zero `startTransaction` hits. [read: SRC/persistence/nosql/persistence/db/mongodb/src/main/java/org/apache/polaris/persistence/nosql/mongodb/MongoDbBackend.java lines 355–385; grep]
- NoSQL retry defaults: `polaris.persistence.retry.timeout=PT15S`, `retries=10000`, sleeps of 10–250 ms, and "fair retries" (SLEEPING). The docs say this mitigates retry loops that "never making any progress" on "highly contended resources". [read: DOCS/configuration/config-sections/smallrye-polaris_persistence.md]
- NoSQL does not use `InMemoryEntityCache`; its caching sits at the `Persistence` layer. Maintainers called this "a deliberate decision, not a bug". [read: https://github.com/apache/polaris/issues/4874; https://lists.apache.org/thread/8l6435olhqcjpjhrchq0ytwm35gq0gxb]
- Multi-instance NoSQL deployments need peer discovery and cache-invalidation configuration. This uses DNS or a Kubernetes headless service, invalidation tokens, and an endpoint on management port 9000, with `initial-discovery-required=true` by default. [read: DOCS/configuration/config-sections/smallrye-polaris_persistence_distributed_cache_invalidations.md]

**Upgrades**
- Rolling upgrades are attempted only within a persistence type. Moving between persistence types means migrating with the tools and accepting downtime. [read: DOCS/evolution.md]

### Inferences
- **Fit with Iceberg.** The JDBC design is the classic Iceberg pattern: an immutable metadata file plus a conditional pointer swap. It is simple and correct, and it gives per-table concurrency, so commits to different tables touch different rows. [inference]
- **Weaknesses of the JDBC design.**
  - Every grant change also bumps the CAS fence, because the version check includes `grant_records_version`. A grant change can therefore abort an in-flight data commit on the same entity. [inference from the code]
  - Every uncached `loadTable` reads metadata.json from object storage. [inference from the code plus the negative grep]
- **Implication for a MongoDB lean.** The NoSQL design serializes all writes in a catalog through one document, `cat/<id>/heads/main`. With many busy tables, that per-catalog hot spot bounds commit throughput by single-document CAS latency times retry contention, and Polaris's own config talks about fairness for "highly contended resources". The git-like commit log is valuable mainly for features a single enterprise may not need (change polling, catalog-level history and branching; see #3469 "Catalog-level branching", open). If you use MongoDB, model **one document per table with a version field and a conditional update**, which is the JDBC semantics on Mongo, rather than Polaris's catalog-HEAD design. [inference]
- Because Polaris's MongoDB backend avoids multi-document transactions, it cannot simply carry over to per-table documents with atomic multi-table commits. A from-scratch Mongo design that wants atomic `commitTransaction` across tables must use Mongo transactions on a replica set, or a commit-intent document. [inference]

### Gaps
- No published benchmark compares JDBC with MongoDB/NoSQL commit throughput, or measures per-catalog contention.
  - A maintainer (2026-06-25) said meaningful comparisons are possible only at the REST level, and that "it does not look like anyone is available these days to run those benchmarks with scientific rigour". [read: https://lists.apache.org/thread/8l6435olhqcjpjhrchq0ytwm35gq0gxb]
  - There is a JMH benchmark module, `persistence/nosql/persistence/benchmark`, but I found no published results.

---

## 4. Security model

### Takeaway
- **Authorization.** The internal RBAC has two tiers: principal, then principal role, then catalog role, then privilege on a securable. There are 110 privilege enum values and 121 authorizable operations. OPA (preview) and Ranger can replace it.
- **Authentication.** It can be internal (a Polaris-issued JWT from client id/secret), external OIDC, or mixed.
- **Storage access.** Credential vending gives per-table least-privilege storage tokens: AWS STS AssumeRole with an inline session policy, Azure user-delegation SAS, or GCS downscoped tokens. The per-catalog storage configuration and allowed-location and overlap checks are what make that vending safe.

### Cited Findings
**RBAC**
- Model: principal, then principal role (many-to-many), then catalog role (many-to-many), then privilege. "You don't grant privileges directly to a principal role." Catalog roles belong to one catalog. Securables are catalog, namespace, Iceberg table, view and policy, plus semantic models. [read: DOCS/managing-security/access-control.md]
- `PolarisPrivilege` has 110 enum constants and `PolarisAuthorizableOperation` has 121. [read: grep over SRC/polaris-core/.../PolarisPrivilege.java and PolarisAuthorizableOperation.java]

**Authentication**
- Three modes: `internal` (the default), `external` (Quarkus OIDC; "Disables the internal token endpoint (returns HTTP 501)") and `mixed` (internal first, then OIDC). Each can be set per realm. [read: DOCS/managing-security/external-idp/_index.md]
- With OIDC, a principal mapper (claim paths) and a principal-roles mapper (regex filters and mappings) produce `PRINCIPAL_ROLE:<name>` roles.
- "External principals" can be enabled per realm. They are "authenticated entirely from the token: no metastore lookup… never required to exist in Polaris", and their roles are used verbatim.

[read: same file]

- The internal token broker signs JWTs with `rsa-key-pair` (the default, but with random keys per replica) or `symmetric-key`. The docs warn that the default "is not suitable when deploying many replicas… token validation to fail". [read: DOCS/configuration/configuring-polaris-for-production/_index.md]
- The Iceberg REST spec marks `/v1/oauth/tokens` "DEPRECATED for REMOVAL". [read: SRC/spec/iceberg-rest-catalog-open-api.yaml lines 191–195]

**External policy decision points**
- External PDP mode is `polaris.authorization.type=opa`. "OPA integration is currently a preview feature and may undergo breaking changes." [read: DOCS/managing-security/external-pdp/_index.md, opa.md]
- A Ranger authorizer extension exists (`extensions/auth/ranger`, 992 lines), but there is no Ranger page in the unreleased docs. [read: module list; grep of DOCS]
- 1.8.0 reworked the authorizer SPI to be "intent-based", with multi-intent requests evaluated one intent at a time. [read: SRC/CHANGELOG.md §1.8.0]

**Credential vending**
- S3: STS `AssumeRole` "with an inline session policy scoped to the specific table locations and operations".
- Azure: user-delegation SAS, which Azure caps at 7 days.
- GCS: "Credential Access Boundary" downscoped tokens.
- Clients opt in with `X-Iceberg-Access-Delegation: vended-credentials`, and Polaris returns a credential-refresh endpoint.

[read: DOCS/vended-credentials.md]

- Related feature flags: `STORAGE_CREDENTIAL_DURATION_SECONDS`, `STORAGE_CREDENTIAL_CACHE_DURATION_SECONDS`, `SESSION_TAGS_IN_SUBSCOPED_CREDENTIAL`, `INCLUDE_PRINCIPAL_NAME_IN_SUBSCOPED_CREDENTIAL`, `GCS_PRINCIPAL_ATTRIBUTION_*`, `SUPPORTED_S3_CREDENTIAL_VENDING_MECHANISMS`, `SKIP_CREDENTIAL_SUBSCOPING_INDIRECTION` and others. [read: DOCS/configuration/config-sections/flags-polaris_features.md]
- Known limit: with 14 nested namespaces of 32 characters each, the generated STS policy hit "Packed policy consumes 118% of allotted space" and table creation failed. The issue is still open, filed 2025-12-09. [read: https://github.com/apache/polaris/issues/3243]

**Location safety**
- The production checklist says to review location compatibility flags. `ALLOW_UNSTRUCTURED_TABLE_LOCATION`, `ALLOW_EXTERNAL_METADATA_FILE_LOCATION`, `ALLOW_TABLE_LOCATION_OVERLAP` and wildcard allowed locations "relax the default storage boundary model". [read: DOCS/configuration/configuring-polaris-for-production/_index.md]
- The commit path validates allowed locations, sibling overlap and metadata-in-table-dir. [read: LocalIcebergCatalog.java:2214–2290]

**Threat model**
- Polaris ships a 740-line threat model. It states that Polaris does not provide "Protection against authorized administrators using their legitimate privileges", network isolation, TLS termination, and similar properties; those are operator responsibilities. [read: SRC/SECURITY-THREAT-MODEL.md]

### Inferences
- The two-tier role model mirrors a SaaS account in which a service admin manages principal roles and each catalog owner manages catalog roles. For an enterprise with a central IdP and entitlement system, the principal-role tier is just "IdP group". With external principals, Polaris itself admits it can skip storing principals entirely. [inference]
- **Must-haves for a regulated firm:** credential vending with per-table scoped policies, allowed-location enforcement, overlap checks, and session-tag/principal attribution on vended credentials. These are not bloat. [inference]

### Gaps
- I did not read the OPA input schema or the Ranger module's documentation in full.

---

## 5. Other features: federation, generic tables, policies, events, operations tooling, realms

### Takeaway
On top of the Iceberg REST catalog, Polaris carries:
- catalog federation to Hive, Hadoop, BigQuery and other REST catalogs
- generic (non-Iceberg) tables, in beta
- a policy store that stores but does not execute maintenance policies
- semantic models, OpenLineage ingest, a shares API spec (beta) and a notifications API
- an event-listener framework, with JDBC, CloudWatch, Kafka and OTel listeners
- persisted Iceberg scan and commit metrics
- multi-realm tenancy
- an admin CLI, a Helm chart, a Python client and CLI, and a Spark plugin

Polaris itself says its events are not an audit trail.

### Cited Findings
**Federation**
- Disabled by default (`ENABLE_CATALOG_FEDERATION`). By default a federated catalog can be secured only at catalog level.
- `ENABLE_SUB_CATALOG_RBAC_FOR_FEDERATED_CATALOGS` creates "synthetic" local entities purely to anchor grants on remote objects.
- Guides cover Iceberg REST, Hive Metastore, Hadoop and BigQuery federation.

[read: DOCS/federation/_index.md; module list]

- An open issue reports `ClassNotFoundException: S3AFileSystem` when querying federated Hive tables. [read (title only): https://github.com/apache/polaris/issues/2978]

**Generic tables**
- Beta. They have name, format ("delta", "csv"…), an optional base location and properties, and share the namespace with Iceberg tables. Polaris does not manage their metadata. [read: DOCS/generic-table.md]

**Policy store**
- Policies are attached at catalog, namespace or table level, with inheritance. System types include compaction, snapshot expiry, orphan-file removal and row-level access.
- "Polaris stores and resolves these policies but does not run the maintenance operations itself."

[read: DOCS/policy.md; DOCS/entities.md]

**Events**
- `BEFORE_*`/`AFTER_*` events for catalog and management operations, including `BEFORE/AFTER_LOAD_CREDENTIALS`.
- "Polaris events are not a complete audit trail. They do not record authorization denials or authentication failures, and delivery is asynchronous and best-effort."

[read: DOCS/events.md]

- An open issue, "Document and complete a supported audit trail for regulated deployments" (filed 2026-09-20), lists the gaps:
  - access logs lack the principal
  - denials are logged at INFO and are not events
  - the in-memory buffer listener drops a batch after 5 failed retries

  Maintainers agreed in October and November 2026 to document the status quo, and floated an events query API. [read: https://github.com/apache/polaris/issues/5561 and its comments]

**Metrics persistence**
- `scan_metrics_report` and `commit_metrics_report` tables in the catalog database. Since 1.4.0, reporting metrics requires `TABLE_READ_DATA`/`TABLE_WRITE_DATA`. [read: schema.sql; SRC/CHANGELOG.md]

**Other API surfaces** (OpenAPI paths)

| Surface | Paths |
|---|---|
| Iceberg REST | 23 |
| Bundled Polaris catalog service (generic tables, policies, notifications, OAuth tokens, catalog discovery, semantic models) | 27 |
| Management API | 17 |
| Shares API | 3 |

- The Shares API is described as "beta… served only when the shares feature flag is enabled (off by default)".

[read: SRC/spec/*.yaml; SRC/spec/polaris-shares-api.yaml]

**Realms**
- A "logical partitioning mechanism… allows for multitenancy". `realm_id` is part of every primary key. The realm is resolved from a request header (default `Polaris-Realm`).
- If the header is missing, Polaris falls back to the first realm, which the production guide calls a potential security issue; fix it with `require-header=true`.
- Authentication type is configurable per realm.

[read: DOCS/realm.md; DOCS/configuration/configuring-polaris-for-production/_index.md]

- 231 of the 830 Java files under `src/main` of core, service and persistence mention "realm". [read: grep over clone]

**Operations tooling**
- An admin tool handles bootstrap: realm plus root credentials, printed or user-supplied. It also runs NoSQL maintenance. [read: DOCS/metastores/nosql-mongodb.md; DOCS/configuration/configuring-polaris-for-production/_index.md]
- The Helm chart's `values.yaml` is 1,337 lines long, with a JSON schema since 1.4.0. [read: SRC/helm/polaris/values.yaml; SRC/CHANGELOG.md]

**Observability**
- Quarkus micrometer and prometheus, OpenTelemetry, smallrye-health, and JSON logging. 1.x also added histogram buckets for HTTP latency. [read: runtime/service build file; issue title https://github.com/apache/polaris/issues/4802]

**Idempotency**
- Opt-in `Idempotency-Key` support (`polaris.idempotency.enabled=false` by default; TTL PT5M). Keys are embedded in the created entity's internal properties, so "the key and the operation commit atomically". [read: DOCS/configuration/config-sections/smallrye-polaris_idempotency.md]

### Inferences
- Federation, generic tables, semantic models, shares and the policy store are the feature set of a catalog product competing with Unity Catalog, Glue and Snowflake Horizon. Each is gated behind a flag and is beta or preview. For a single enterprise whose estate is Iceberg-first, they can be deferred without loss. [inference]
- The audit gap is the most material shortfall for a regulated firm, because loads, credential vends and denials must be captured durably. [inference]

### Gaps
- I did not read the admin-tool doc page in full (305 lines). Its NoSQL maintenance commands are referenced but not verified.
- I did not verify which Polaris engine-side features the Spark plugin requires.

---

## 6. Size and operational weight (quantified)

### Takeaway
A production Polaris needs:
- Postgres, with the schema pre-created by a DBA
- a bootstrapped realm and pre-generated RSA keys (or OIDC)
- the realm header enforced
- FILE storage disabled
- Kubernetes/Helm with about 1.3k lines of chart values

It exposes about 200 documented config properties, 63 feature flags and 6 behaviour-change flags, and ships a 418 MB (compressed) container image.

### Cited Findings
**Code size** (non-blank lines; local count; `src/main` vs everything else)
- 131,549 lines of Java in 1,044 `src/main` files.
  - 10,943 of those are in `integration-tests/src/main`.
  - 6,092 are build and test tooling under `tools/`.
- About 104.8k lines of Java outside `src/main` (tests).
- About 17.7k lines of Python, mostly the generated client and CLI.

[read: local count over the clone]

- GitHub's languages API reports 10.23 MB of Java and 0.87 MB of Python. [read: https://api.github.com/repos/apache/polaris/languages]

**Modules**
- 85 Gradle projects in `projects.main.properties`, plus 3 Spark plugin variants and 2 included builds.
- 40 of the 85 are NoSQL persistence modules: async, idgen, nodes, authz, realms, 14 database-agnostic persistence modules, 4 maintenance modules, and 3 database backends.

[read: SRC/gradle/projects.main.properties]

**Configuration surface**
- 200 documented `polaris.*` properties across 37 SmallRye config sections. [read: count of table rows in DOCS/configuration/config-sections/smallrye-*.md and storage-*.md]
- 63 `polaris.features.*` flags, 23 of which can be overridden per catalog. [read: DOCS/configuration/config-sections/flags-polaris_features.md]
- 6 `polaris.behavior-changes.*` flags. [read: DOCS/configuration/config-sections/flags-polaris_behavior_changes.md]
- Quarkus' own properties (datasource, OIDC, HTTP) come on top of these.

**Dependencies**
- The `gradle/libs.versions.toml` catalog has 89 library entries. [read: SRC/gradle/libs.versions.toml]
- The server distribution `LICENSE` lists 120 bundled third-party projects. [read: count of project entries in SRC/runtime/server/distribution/LICENSE]

**Container image**
- `apache/polaris:1.8.0` is 418 MB amd64 and 415 MB arm64 as reported by Docker Hub (compressed). That is up from 397 MB at 1.5.0. [read: https://hub.docker.com/v2/repositories/apache/polaris/tags?page_size=6]

**Database schema**
- 8 tables (§3). NoSQL instead uses the `refs` and `objs` collections, a commit log and daily maintenance. [read: schema.sql; MongoDbBackend.java; nosql-mongodb.md]

**Production checklist**
- The checklist reads: "Configure OAuth2 keys; Enforce realm header validation (`require-header=true`); Use a durable metastore (JDBC + PostgreSQL); Bootstrap valid realms in the metastore; Disable local FILE storage." [read: DOCS/configuration/configuring-polaris-for-production/_index.md]

**Tuning documentation**
- "Document how to tune Polaris server" has been open since 2025-10-20. [read (title): https://github.com/apache/polaris/issues/2845]
- Dremio's sizing guide (2026-10-27, for 1.8.0) proposes pool-budget math: replicas × pool + others ≤ `max_connections`, with a safety factor of 1.2–1.5. It also proposes example SLOs: p95 < 200 ms for catalog reads, < 500 ms for commits. **It publishes no measured results.** Its one anecdote: commit p95 neared 500 ms at 6 replicas, and the fix was a bigger DB instance. [read via WebFetch summary: https://www.dremio.com/blog/sizing-apache-polaris-for-production-load-testing-postgresql-pools-and-helm-resources/]

**Java API stability**
- "Changes in Java classes should be expected at any time regardless of the module name or whether the class / method is `public` or not." [read: DOCS/evolution.md]

### Inferences
- About 60–70% of the config surface (federation, NoSQL, OPA, events, metrics, realms, internal token broker) would not exist in a single-tenant, single-backend, external-IdP build. I have not counted this property by property. [inference]
- The "no Java API stability" stance means forking Polaris and tracking upstream is expensive. A fork for the firm would diverge quickly. [inference]

### Gaps
- No measured cold-start time, steady-state heap or per-request CPU. The project publishes none, and I did not run it.

---

## 7. Known performance, scale, and operability problems

### Takeaway
The recurring problems are:
- chatty database access per request: an N+1 grant-record load, up to 23 SELECTs for a cold `loadTable`-shaped resolve, and as many as 7 JDBC connections for one `getConfig`
- grant and entity scale (`create_namespace` taking more than 30 s at about 170k entities and 176k grants, before indexes were added)
- entity-cache correctness and memory (an OOM in production, now fixed)
- in-process purge tasks that can take the server down
- STS policy-size limits

Published benchmark numbers are sparse, and none states the backend.

### Cited Findings
**Grant and entity scale** (#3685, 2026-02-06)
- `create_namespace` took more than 30 s in production, with 167,637 entities and 176,103 grant records.
- 65,415 executions of the grantee lookup were 100% sequential scans, which "reads 13.7 Billion rows".
- An `IN` clause carried about 7,800 `(catalog_id, id)` tuples, about 15,000 bind parameters.
- Adding indexes cut latency from about 36 s to about 17 s. The indexes were then added to the project's schema.

[read: https://github.com/apache/polaris/issues/3685 and comments]

**Cold-path query count** (dev list, September 2026)
- A traced `loadTable`-like resolve of path N1/N2/T2 issued **23 SELECTs** without the cache: 9 full entity rows and 14 grant-record lookups. A deep 6-segment path issued 29. A warm cache needed 1 version-check SELECT.
- JB Onofré: `loadResolvedEntityById()` "issues 1 or 2 grant-record queries per entity… with no batching. That's N+1", and "grant-record loading is never actually batched, even in the 'batch' API".
- Robert Stupp questioned the cold baseline and whether a version check per cache hit is needed at all.

[read: https://lists.apache.org/thread/tl6z48gdblsko3x0b9nt2917wb2fgtor (emails dated 2026-09-04 to 2026-09-08)]

**Connections per request**
- "A simple IRC operation like loading the catalog config apparently uses as many as 7 JDBC connections." Open since 2026-07-17, and it raises questions about transactional isolation. [read: https://github.com/apache/polaris/issues/5087]

**Entity cache**
- An open issue (filed 2025-01-15) says the two-map `EntityCache` is "prone to inconsistencies & race conditions". Maintainers argued the cache is not responsible for consistency. [read: https://github.com/apache/polaris/issues/761]
- On 1.3.0 in production, the `byName` map was unbounded and led to OOM: old-gen heap climbed from 0.9 to 2.2 GB on a 4 GiB pod at about 1,500 table GETs per 5 minutes. Closed 2026-10-19. [read: https://github.com/apache/polaris/issues/5386]
- The current code holds entities "in a single cache keyed by entity id… Lookups by name go through a secondary index… validated against the primary id cache", with both indexes bounded and expiring after 1 hour without access. [read: SRC/polaris-core/src/main/java/org/apache/polaris/core/persistence/cache/InMemoryEntityCache.java]

**Purge on drop**
- Dropping a large CDC table made "the whole polaris instance… unresponsive" with S3 connection-pool timeouts in `FileCleanupTaskHandler`. It was reproduced on 1.5.0 with 2 replicas: a table with about 1,000 partitions and about 300 GB, where only a pod restart helped. Open. [read: https://github.com/apache/polaris/issues/3462]
- A related issue, closed 2026-09-09: "S3 connection pool exhausted over hours after enabling DROP_WITH_PURGE_ENABLED (1.7.0)". [read (title): https://github.com/apache/polaris/issues/5475]

**Concurrency correctness**
- "Concurrent CREATE and DELETE Operations Cause IllegalStateException" (#1389, open). [read (title)]
- "Grants version going backward during write workload" (#3836, closed). [read (title)]
- "Table commit that races with a drop returns HTTP 500 on the NoSQL backend" (#5897, open, 2026-10-28). [read (title)]

URLs: https://github.com/apache/polaris/issues/1389, /3836, /5897

**JDBC realm locking**
- 1.5-era fix (PR #4054, merged 2026-04-21): replaced coarse `synchronized` methods in the JDBC metastore factory with per-realm read/write locks, and removed a "Per-request findRootPrincipal DB query". [read: https://github.com/apache/polaris/pull/4054]
- Dremio's 1.5.0 article reportedly cites queue latency falling from 425 ms to 48 ms after this change. [secondary: search snippet of https://www.dremio.com/blog/apache-polaris-1-5-0-deep-dive-into-the-future-of-open-data-catalogs/]

**Published benchmark numbers** (Dremio, 2025-07-01, Polaris Gatling framework; Polaris version and backend not stated)
- Create-table: 135.78 req/s at concurrency 40, median 89 ms, p99 154 ms, on a tree of 16,383 namespaces, 24,576 tables and 16,384 views.
- Mixed 90/10 read/write at a 150 ops/s target: fetch-table median 7 ms, p99 12 ms (about 13.7 fetch-table req/s).
- The same post says earlier work found an EclipseLink scalability issue.

[read via WebFetch summary: https://www.dremio.com/blog/benchmarking-framework-for-the-apache-iceberg-catalog-polaris/]

**Benchmark tooling**
- The `polaris-tools` Gatling suite has `CreateTreeDataset`, `ReadTreeDataset`, `ReadUpdateTreeDataset`, `CreateCommits` and `WeightedWorkloadOnTreeDataset`. The repo publishes no results; only a sample Gatling report PNG is present. [read: https://github.com/apache/polaris-tools/blob/main/benchmarks/README.md; repo tree]

**Long-standing open requests**
- On-prem S3 and remote signing (#32, open since 2024-07-31, 64 comments). [read (title/metadata): https://github.com/apache/polaris/issues/32]
- Multi-table/multi-statement transactions umbrella (#5833, opened 2026-10-21). [read (title): https://github.com/apache/polaris/issues/5833]
- Multi-DataSource (#4032, open). [read (title): https://github.com/apache/polaris/issues/4032]

### Inferences
- Most of the performance pain comes from three choices:
  1. the generic entity/grant data model, resolved entity by entity
  2. RBAC grants stored as rows checked per path element
  3. maintenance work done in-process

  None is inherent to an Iceberg REST catalog. A purpose-built design can resolve a table path and its effective privileges in one or two queries, or from an in-memory projection. [inference]
- Benchmark evidence is thin for a project this widely used. An enterprise should plan to run its own Gatling-style tests at its own scale: thousands of tables, many principals and groups, realistic commit rates. [inference]

### Gaps
- I found no independent, third-party benchmark with stated hardware and backend, and no multi-region numbers.
- I did not read the full `dev@` archive. Only the threads linked from issues were read.

---

## 8. Bloat analysis: vendor/SaaS-driven vs enterprise-needed

### Takeaway
The thesis holds for about half the surface area and fails for the security core.
- **Vendor/SaaS-shaped:** realms, the internal token broker and client-secret store, multiple pluggable persistence stacks (NoSQL alone is about 26% of `src/main` Java), federation, generic tables, semantic models, shares, notifications, the policy store, and metrics persistence.
- **Genuinely needed by a single regulated enterprise:** credential vending, per-catalog storage config, location enforcement, fine-grained privileges or an external PDP, CAS commits, multi-table atomic commits, idempotency and events.

### Cited Findings
These restate evidence from §§1–7, with the classification each implies.
- **Realms, multi-tenancy:** `realm_id` sits in every PK, header-based resolution falls back to the first realm, the NoSQL realm lifecycle state machine is "not fully implemented yet", and 231 core/service/persistence files touch realms. [read: §3, §5 sources]
  - *Classification: SaaS.* Snowflake Open Catalog and Dremio's catalog host many customers. [inference]
- **Internal token broker:** Polaris issues JWTs from client id/secret and stores hashed secrets in `principal_authentication_data`. The Iceberg spec deprecates `/oauth/tokens`, and Polaris's `external` mode turns the endpoint off. [read: §4 sources]
  - *Classification: SaaS/standalone-product feature.*
- **Multiple persistence stacks:** JDBC (4.0k lines), NoSQL (34.6k lines, 40 modules, mostly one Dremio author, Nessie-derived according to Snowflake's launch post), DynamoDB-atomic (WIP) and in-memory. [read: §1–§3 sources]
  - *Classification: vendor-driven* (Dremio/Nessie heritage, cloud-portable SaaS). [inference]
- **Federation, generic tables, semantic models, shares, notifications, policy store:** all flag-gated, beta or preview, and pushed by vendor roadmaps (Dremio's stated plans include Generic Tables and Volumes). [read: §5 sources; Dremio blog]
  - *Classification: product-competition features.*
- **Two-tier RBAC:** principal roles and catalog roles. #3685 shows grant records reaching 176k rows with one grant row per privilege × securable × role. [read: §4, §7 sources]
  - *Classification: partly SaaS-shaped.* The concept of catalog-scoped role delegation is useful to an enterprise, but the persistence model scales poorly. [inference]
- **Credential vending, storage config, allowed locations, overlap checks:** used on every commit and load. [read: §3, §4 sources]
  - *Classification: needed by any multi-engine enterprise.*
- **Events:** best-effort, missing denials. [read: §5]
  - *Classification: needed, but under-built for regulated use.*

### Inferences
- The fair conclusion is that Polaris is not bloated in its Iceberg REST core. The whole `runtime/service/.../catalog` package is about 13k lines, and that already includes the generic-table, policy and federation handlers. It is bloated in its periphery and its abstractions:
  - two metastore-manager families
  - a generic entity model with JSON blobs
  - realm plumbing everywhere
  - a separate NoSQL stack the size of the core
  - many beta APIs

  [inference]
- Some "SaaS" features have enterprise analogues worth keeping in simpler form. **Catalogs** can map to business domains or legal entities, each with distinct storage accounts and KMS keys. **Federation** helps a time-boxed migration from Hive/Glue. Neither needs realms. [inference]

### Gaps
- I have no line-by-line attribution of which classes exist only for Snowflake Open Catalog. The open-source repo does not show Snowflake-internal extensions.
- I found no source on how Snowflake Open Catalog persists data internally; searches returned nothing primary.

---

## 9. Honest strengths a from-scratch build should copy

### Takeaway
Polaris gets the hard, security-relevant parts right, and documents them better than most open-source catalogs. Copy the semantics and the guardrails, not the abstractions.

### Cited Findings
- **Correct commit contract:**
  1. Pre-check the base location.
  2. Write the metadata file.
  3. Do a conditional pointer update.
  4. Clean up orphans only on definite failure, and leave the file in place on ambiguous outcomes.

  [read: LocalIcebergCatalog.java:2214–2400]
- **Atomic multi-table `commitTransaction`:** writes are buffered, then applied in one DB transaction with per-entity CAS. [read: IcebergCatalogHandler.java; JdbcBasePersistenceImpl.java]
- **Location security:** allowed locations per catalog; overlap detection backed by a `location_without_scheme` index; metadata required inside the table directory; caller-controlled FileIO settings not trusted ("not metadata.properties(), which can carry caller-controlled FileIO client settings (for example s3.endpoint)"). [read: LocalIcebergCatalog.java comments; schema.sql]
- **Least-privilege vending for all three clouds:** per-table STS inline policies, SAS and CAB tokens, a credential refresh endpoint, and principal attribution through session tags and names. [read: DOCS/vended-credentials.md; flags list]
- **Idempotency keys** committed atomically with the created entity. [read: idempotency config doc]
- **Retryable 409 on a stale sequence number** (1.8.0). [read: CHANGELOG]
- **Engineering practice:** a published threat model listing operator responsibilities, a dedicated config-docs generator, and Gatling benchmark tooling. [read: SRC/SECURITY-THREAT-MODEL.md; tools/config-docs; polaris-tools]

### Inferences
- A from-scratch build should treat these as acceptance tests. Polaris's integration-test suite (`integration-tests`, about 11k lines in `src/main` plus tests) and `regtests` are a useful source of behavioural cases to replicate. That applies to the behaviour, not the code. [inference]

### Gaps
- None material.

---

## Decisions this implies

Each decision lists the options, the evidence, and a recommendation.

### D1. Tenancy: realms vs. single-tenant
- **Options:**
  - (a) Keep a realm/tenant key in every row.
  - (b) Single tenant per deployment; separate environments and legal entities by deployment and database.
  - (c) Single tenant with "catalogs" as the partition unit.
- **Evidence:** realm plumbing spans 231 files; it forces header enforcement (`require-header`) and per-realm bootstrap; the realm lifecycle is incomplete in NoSQL (§3, §5).
- **Recommendation:** (c). Make catalogs the unit for business domains, legal entities and regions. Each catalog gets its own storage config, KMS and allowed locations. Do not build realms.

### D2. Identity: internal token broker vs. external IdP only
- **Options:**
  - (a) Internal client-credentials IdP, as in Polaris `internal`.
  - (b) External OIDC only, with engines using workload identity or token exchange.
  - (c) Mixed.
- **Evidence:** the Iceberg spec deprecates `/oauth/tokens`; Polaris's `external` mode returns 501 on it; internal mode needs RSA key distribution across replicas; "external principals" need no stored principals (§4).
- **Recommendation:** (b). Do not store client secrets. Map IdP groups to roles.

### D3. Authorization model
- **Options:**
  - (a) Copy Polaris's two-tier role model with grant rows.
  - (b) A single-tier group-to-role-to-privilege model with inheritance down the namespace tree, computed as a cached effective-privilege projection per role.
  - (c) An external PDP (OPA or the firm's entitlement service) behind a batched, path-aware decision interface.
- **Evidence:** 110 privileges and 121 operations; N+1 grant loading (14 grant queries per cold resolve); 176k grant rows causing more than 30 s operations; OPA support still in preview; Polaris's authorizer SPI reworked in 1.8.0 (§4, §7).
- **Recommendation:** (b) as the built-in model, with (c) as a pluggable adapter from day one.
  - Keep Polaris's privilege vocabulary where it maps to Iceberg operations: table read/write data, read/write properties, create, drop, manage grants.
  - Batch decisions per request.
  - Emit allow and deny decisions to audit.

### D4. Persistence backend (the user leans toward MongoDB)
- **Options:**
  - (a) PostgreSQL-compatible: Aurora or managed PG; CockroachDB if multi-region writes are needed. Polaris ships a CockroachDB schema.
  - (b) MongoDB with **per-table documents** plus version CAS, and Mongo transactions for multi-table commits.
  - (c) MongoDB with Polaris's catalog-HEAD commit-log design.
- **Evidence:**
  - Polaris's only production-ready backend is Postgres JDBC.
  - MongoDB is beta. Its design CASes one document per catalog for every change; it needs daily GC maintenance and peer cache-invalidation configuration; it uses no Mongo transactions; it is 34.6k lines across 40 modules; and no public benchmarks exist (§3, §6, §7).
- **Recommendation:** choose on the firm's operational platform maturity, not on Polaris. If MongoDB, use (b), not (c). Avoid any single per-catalog write pointer. Put the multi-region question (D10) first.
- **Keep from Polaris in either case:** the small schema shape (entities, grants, an audit/outbox table) and the per-entity version CAS.

### D5. Commit path and metadata reads
- **Options:**
  - (a) Polaris model: the server writes metadata.json and CASes a pointer, and every uncached load reads metadata.json from object storage.
  - (b) As (a), plus an in-memory or distributed cache of parsed `TableMetadata` keyed by the immutable `metadata-location`.
- **Evidence:**
  - Polaris stores only a pointer and summary.
  - I found no metadata cache, which is an inference from a grep (§3).
  - The CAS fence also includes `grant_records_version`, so grant changes can conflict with data commits.
- **Recommendation:** (b). The cache key is the immutable `metadata-location`, so there is no invalidation problem. Fence commits on a data version only, separate from ACL versions.

### D6. Caching and cross-node consistency
- **Options:**
  - (a) Polaris-JDBC style: cache entities, but version-check every hit with one SELECT.
  - (b) Cache immutable objects only, read the pointer row (one indexed read) per request, and accept that read.
  - (c) Change-stream or notification invalidation (Mongo change streams or PG LISTEN/NOTIFY) with a TTL backstop.
- **Evidence:** Polaris's warm path is 1 SELECT and its cold path 23. The cache caused an OOM and has a race-condition history (#761, #5386).
- **Recommendation:** (b) as the baseline, adding (c) only if measured load requires it. Bound every cache.

### D7. Credential vending and storage guardrails
- **Recommendation:** **keep and copy the semantics**:
  - per-table scoped STS, SAS and CAB credentials
  - a credential cache with a TTL below token expiry
  - session tags or principal attribution
  - allowed locations per catalog
  - an overlap index
  - metadata-in-table-dir checks
  - never trusting client-supplied FileIO endpoints
- Plan for the STS policy-size limit (#3243): consolidate paths, or use prefix-based policies with tag conditions.

### D8. Audit trail
- **Options:**
  - (a) Polaris-style async best-effort listeners.
  - (b) A transactional outbox written in the same DB transaction as each mutation, plus synchronous audit records for reads, credential vends and authorization denials, shipped to the firm's SIEM and WORM store.
- **Evidence:** Polaris says its events "are not a complete audit trail"; #5561 for regulated deployments is open (§5).
- **Recommendation:** (b), designed in from day one.

### D9. Background work: purge on drop, cleanup tasks
- **Options:**
  - (a) In-process task executor, as in Polaris.
  - (b) No data deletion in the catalog. Emit a drop event and let a separate, rate-limited maintenance service purge files.
- **Evidence:** in-process purge exhausts S3 pools and hangs the server (#3462, #5475).
- **Recommendation:** (b).

### D10. Multi-region HA
- **Evidence:** Polaris documents only single-database deployments. Its NoSQL design assumes single-row CAS within one database, and I found no multi-region guidance in the docs read (§3, §6).
- **Recommendation:** decide active-active vs. active-passive writes per catalog before choosing the database. A "home region per catalog" with replicated read pointers avoids cross-region CAS on the commit path. [inference]

### D11. Schema and upgrade management
- **Evidence:** Polaris requires manual DDL; schema versions are bumped by hand; users missed index migrations (#3685); every release has breaking changes (§1, §3).
- **Recommendation:** use versioned, automated migrations (Flyway or Liquibase, or Mongo migration scripts) run by the deploy pipeline. Provide a strict REST compatibility policy and no public Java SPI unless needed.

### D12. Runtime framework and footprint
- **Evidence:** Polaris uses Quarkus 3.40 and about 20 extensions; the image is 418 MB compressed (§2, §6).
- **Recommendation:** use the firm's standard Java service framework. Spring Boot or Quarkus are both fine; nothing in Polaris's value depends on Quarkus. Keep the dependency set small for vulnerability-management reasons.

### Keep / defer / drop: Polaris features for a firm-scale single-enterprise catalog

| Polaris feature | Verdict | Why (evidence section) |
|---|---|---|
| Iceberg REST catalog core (namespaces, tables, views, config, pagination) | **Keep** | The product itself (§2, §3) |
| Pointer CAS commit + orphan cleanup contract | **Keep** | Correct and simple (§3, §9) |
| Atomic multi-table `commitTransaction` | **Keep** | Needed for consistent multi-table writes (§3) |
| Idempotency keys | **Keep** | Safe client retries (§5) |
| Credential vending (S3/ADLS/GCS) + refresh endpoint | **Keep** | Least privilege for many engines (§4) |
| Per-catalog storage config, allowed locations, overlap checks | **Keep** | Security boundary (§4, §9) |
| Fine-grained privileges (table/view/namespace/catalog) | **Keep (simplified)** | Needed; drop the two-tier role persistence (§4, §7) |
| External PDP (OPA or enterprise entitlement) adapter | **Keep** | Central policy for a regulated firm (§4) |
| External OIDC authentication, IdP group-to-role mapping | **Keep** | Enterprise IdP (§4) |
| Events | **Keep, rebuild as a durable audit outbox** | Polaris's version is best-effort (§5) |
| Metrics (Prometheus/OTel), health, rate limiting | **Keep** | Standard operations (§5) |
| Multiple catalogs | **Keep** | Domain, LOB and region partitioning (§8) |
| Admin/bootstrap CLI | **Keep (minimal)** | Needed, but use automated migrations (D11) |
| Table maintenance policy store | **Defer** | Stores but does not run policies; decide who owns maintenance (§5) |
| Catalog federation (Hive/Glue/REST) | **Defer** | Only for a time-boxed migration (§5) |
| Generic tables (Delta/Hudi/CSV) | **Defer** | Only if non-Iceberg tables must be cataloged (§5) |
| Iceberg scan/commit metrics persisted in the catalog DB | **Defer / send to telemetry instead** | Keeps the OLTP database lean (§3, §5) |
| Remote signing (not in Polaris; open #32) | **Defer / evaluate** | Needed only for on-prem S3 without STS (§7) |
| Realms / multi-tenancy | **Drop** | SaaS-only; complexity and security footguns (§5, §8) |
| Internal token broker and client-secret store | **Drop** | Spec-deprecated endpoint; use the IdP (§4) |
| Two-tier principal-role / catalog-role grant rows | **Drop (replace with D3)** | N+1 and grant-row explosion (§7) |
| NoSQL commit-log/HEAD persistence, node leases, Snowflake IDs, maintenance GC | **Drop** | 34.6k lines; per-catalog hot pointer; beta (§3, §6) |
| Pluggable multi-backend persistence SPI | **Drop** | Pick one backend (§2, §3) |
| In-process purge/cleanup task executor | **Drop** | Destabilises the server (§7) |
| Semantic models, OpenLineage ingest, shares API, notifications API | **Drop** | Vendor product scope, beta or flag-gated (§5) |
| Spark plugin, Python CLI | **Drop** (use stock Iceberg clients) | The docs say "Users who only use Iceberg tables do not need to use this client" [read: DOCS/polaris-spark-client.md]. The CLI exists only to drive Polaris's management API (§2) |

---

## Open questions for the enterprise

1. **Scale.** What are the expected numbers for:
   - tables, views and namespaces, and namespace depth (deep paths hit STS policy-size limits, #3243)
   - peak `loadTable` QPS and commit rate per busy table and per catalog
   - target p95/p99 latencies
2. **Multi-region.** Active-active or active-passive per catalog? What RPO and RTO? Must a commit in region A be immediately visible in region B? This decides between a home-region-per-catalog design and a globally consistent database (D4, D10).
3. **Database platform.** Which does the firm's platform team operate at tier-1 with multi-region support: MongoDB (Atlas or self-managed replica sets) or Postgres (Aurora Global, or CockroachDB)? This should outweigh developer preference (D4).
4. **Engines.** Which engines must be supported (Spark, Trino/Starburst, Snowflake, Dremio, Flink, Databricks, PyIceberg)? Do they all support vended credentials? Does any require remote signing or a specific auth flow?
5. **Storage.** S3 only, or also ADLS, GCS or on-prem S3-compatible storage without STS (which would need remote signing)?
6. **Entitlements.** Is there an existing system of record (for example an enterprise entitlement platform, OPA, or Ranger) the catalog must defer to? Are row and column policies enforced in engines or in the catalog?
7. **Identity.** Which IdP? How do service and engine identities authenticate: workload identity federation, token exchange, or client credentials issued by the IdP?
8. **Audit and retention.** Which regulations and internal policies set audit scope? Must reads (`loadTable`, credential vends) and denials be retained, for how long, and in WORM storage?
9. **Formats.** Are there Delta or Hudi tables that must be cataloged? This decides whether generic tables are needed.
10. **Migration.** What legacy catalogs exist (Hive Metastore, Glue, others)? Is a federated read-through period needed, or a one-time migration with `iceberg-catalog-migrator`-style tooling?
11. **Table maintenance.** Who runs compaction, snapshot expiry and orphan removal? A catalog-hosted policy store is useful only if a maintenance service consumes it.
12. **Build vs. adopt.** Would the firm accept running upstream Polaris on JDBC in external-auth mode with everything else disabled, as a baseline to benchmark the from-scratch design against? Polaris's own benchmarks are sparse, so a side-by-side test at the firm's scale would settle the "bloat costs performance" question with data.

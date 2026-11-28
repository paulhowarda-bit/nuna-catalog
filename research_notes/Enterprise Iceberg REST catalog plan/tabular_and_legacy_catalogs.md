# Tabular and the legacy catalog generation (Hive Metastore, Glue, JDBC, Hadoop, Netflix Metacat): design lessons for an enterprise Iceberg REST catalog

Research date: 2026-11-06. Lens: a from-scratch Iceberg REST catalog for a large regulated US financial firm (thousands of tables, many engines, multi-region HA; prior lean Java + MongoDB).

**Provenance tags.**
- `[read: URL]` means I fetched and read the page myself. Tabular pages come from Wayback Machine snapshots, because tabular.io now redirects to databricks.com.
- `[secondary: URL]` means I know the claim only from a search-engine summary or a third party.
- `[inference]` is my own reasoning.

Raw downloads are under `C:/Users/paulh/AppData/Local/Temp/claude/d9ac3f89f5ec51f6/2eb8cba6-c503-4baa-9e18-b3df89b0f733/scratchpad/downloads/` in the folders `tabular/`, `asf/`, `tabular_legacy_web/`, `tabular_legacy_iceberg/` and `tabular_legacy_polaris/`.

**How to read the "who needs it" labels used throughout:**
- **(a) spec/client:** the Iceberg REST spec or the clients actually require it.
- **(b) enterprise operator:** a firm running its own catalog needs it.
- **(c) vendor model:** it exists mainly for a vendor's commercial model.

---

## Q1. What was Tabular's catalog architecture and feature set, and what happened to it after the Databricks acquisition?

### Takeaway
Tabular (2021–2024) was a hosted Iceberg REST catalog that doubled as a security boundary. Its pieces were:
- SQL-style role-based access control (RBAC) enforced at the catalog and at file access through a request-signing service.
- Built-in automated maintenance and optimization, driven by scan metrics the catalog collected.
- Ingestion add-ons: File Loader and CDC.
- Audit and telemetry exposed as Iceberg "system" tables.

Databricks announced the acquisition on 2024-06-04 and completed it by 2024-06-07. The standalone product was wound down: tabular.io became a "joined Databricks" page and later redirects to databricks.com. The founders work at Databricks, and the ideas now appear in Unity Catalog's Iceberg REST endpoint: credential vending, managed Iceberg with automatic optimization, and scan-planning-based access control.

### Cited Findings

**Company timeline**
- **2021-08-29:** Ryan Blue announced that he, Dan Weeks and Jason Reid had raised a Series A from a16z "to build a new kind of data platform around Apache Iceberg" [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/introducing-tabular/].
  - Stated motivation: Iceberg "requires running some infrastructure". Companies like Netflix can provide it, but "Iceberg shouldn't be limited to companies with big infrastructure teams".
  - Tabular would "make data maintenance and optimization problems disappear… compaction, clustering, configuring, indexing" [same source].
- **2023-03-01:** public sign-ups opened [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/announcing-tabular/].
  - Tabular described itself as "everything you need in your data platform—except for compute… a managed metastore catalog integrated with role-based access controls and a swarm of automated services".
  - Its three design principles were "Simple", "Secure" (locks down "your data, not individual tools or engines… even in Spark and Python where permissions are notoriously difficult") and "Automated".
  - Data stayed "in your own AWS bucket as 100% open source Apache Iceberg tables". It claimed optimization saved one customer "$2m over two years in S3 costs alone".
- **2023-09-19:** new funding round led by Altimeter, with a16z and Zetta participating [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/the-case-for-independent-storage/].
- **2023-11-29, "Tabular-on-AWS":** Athena, EMR and DMS-based CDC went GA [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/athena-emr-and-dms-cdc-ga/].
  - Positioning bullets: "Secure — a single point of RBAC policy enforcement for data and metadata operations" and "Efficient — automatic performance optimization makes queries 30-60% faster and cheaper".
  - Customer quote (Datto): query times "cut… in half".

**Architecture (from the archived docs; pages last modified April–May 2024)**
- **Product framing:** "a cloud-native managed storage engine… a 'headless data warehouse'" with a "Bring Your Own Compute" model and "a fully-hosted implementation of the Apache Iceberg REST catalog API" [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/introducing-tabular.html].
  - Key sentence: "Acting as the catalog interface to the data is what enables Tabular to automate table maintenance and optimization activities as well as enforce access controls."
- **Resource hierarchy:** organization → warehouse → database → table [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/understanding-tabular-role-based-access-controls--rbac-.html].
  - A warehouse is "the combination of physical storage (shared or isolated) and a logical catalog layer", and each warehouse maps to a single object store [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/creating-warehouses.html].
- **RBAC model** [read: …understanding-tabular-role-based-access-controls--rbac-.html; …rbac-privileges.html]:
  - Privileges are granted to roles, never to users. Roles can be granted to roles (nested hierarchy with inheritance).
  - Any privilege can carry GRANT, which delegates administration of that privilege.
  - FUTURE_* privileges apply to objects created later, and grants can cascade to existing tables.
  - Three system roles: ORG_ADMIN, SECURITY_ADMIN and EVERYONE.
  - "In Tabular there is no concept of a 'super-user' or 'super-role' that can bypass authorization checks" and "There is no formal concept of resource ownership."
  - Privilege vocabulary:
    - Table: SELECT, UPDATE, DROP, MANAGE_GRANTS.
    - Database: LIST_TABLES, CREATE_TABLE, MODIFY_DATABASE, MANAGE_GRANTS, plus FUTURE_* variants.
    - Warehouse: LIST_DATABASES, CREATE_DATABASE, MODIFY_WAREHOUSE, plus FUTURE_* variants.
    - Role: MODIFY_ROLE.
    - Organization: MANAGE_USERS, CREATE_ROLE, CREATE_WAREHOUSE, CREATE_LABEL.
    - Storage profile: USAGE. Label: APPLY_LABEL.
- **Column masking through labels** [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/masking-and-hiding-sensitive-data.html]:
  - A label carries a masking policy: either NULL (value returned as NULL) or "Hide column" (removed from all schema versions).
  - Roles can be exempt. Roles with UPDATE on the table are always exempt. Precedence is HIDE > MASK > NONE.
  - FAQ, verbatim: column masking "does not provide file-level access controls to the underlying data."
  - Data-lifecycle table properties could also null columns or delete rows by age [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/table-properties.html].
- **Credentials and identity** [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/understanding-credentials.html]:
  - Member credentials.
  - Service-account credentials tied to one role. A shared Trino cluster can impersonate the logged-in user per session.
  - Custom identity providers: "the calling system is responsible for authenticating the user and passing the user's identity to Tabular."
  - AWS IAM identity provider and IAM-role-to-Tabular-role mapping, authenticated through a SigV4 gateway.
  - If the user ID cannot be resolved, the request falls back to a "system user" whose rights you restrict.
  - The IAM gateway required callers to have `execute-api:Invoke` on Tabular's API Gateway (Lambda) account, and Spark connected with `rest.sigv4-enabled true` [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/mapping-aws-iam-identities-to-tabular-roles.html].
  - OAuth2 client-credentials flow at `https://api.tabular.io/ws/v1/oauth/tokens`. A separate management API was described with an OpenAPI spec [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/tabular-api.html].
  - Roles, users and policies could also be managed through Terraform and Okta SCIM [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/securing-the-data-lake-part-3/].
- **Data-access flow, which is remote signing rather than broad bucket policies** [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/data-access-flow-in-tabular.html]:
  - "The catalog service loads the table and creates a table access token."
  - "Based on the table access token, the signer service signs requests to read files in the table."
  - The engine then reads each data or metadata file directly from S3 with the signed request, "until the query completes".
  - This "renders moot the need for any pre-configured access policies to the S3 data".
- **Storage profiles** [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/understanding-storage-profiles.html]:
  - A storage profile is a reusable bucket + path + cloud role (S3/GCS/ADLS) that Tabular uses to reach customer storage.
  - The USAGE privilege on a profile gates file loading. It "has no bearing on the ability to read and write data in warehouse tables" (that is governed by table privileges).
- **Automated maintenance as table properties** [read: …table-properties.html]:
  - Snapshot expiry: default max age 5 days, keep at least 1 snapshot; non-main references kept forever.
  - Compaction on by default: binpack or sort, with partial-progress commits, max 100 GB file groups, and a delete-file threshold of 3.
  - Manifest rewrite at most every 60 minutes.
  - Optimizer on by default, with zstd compression and object-storage hashed paths.
  - `commit.allow-replace-rollback.enabled`.
  - Monitoring lists orphan-file cleanup among the event types [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/monitoring-table-activity.html].
- **Table Optimizer** [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/tabular-table-optimizer.html]:
  - "The Tabular catalog service captures these scan metrics" (the Iceberg scan reports that engines send).
  - It samples data, runs experiments on sort order and compression, sets the write order and compaction strategy, and runs "weekly on all tables that have been updated within the prior 7 days".
- **File Loader** [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/loading-data-with-tabular-file-loader.html]:
  - S3 or GCS object-create notifications go to a Tabular-owned SQS queue or Pub/Sub topic, and data is loaded "typically within 5 minutes".
  - Modes: append or replace. Optional schema evolution and a `_load_metadata` column.
  - It can load into a branch for write-audit-publish (WAP), and can parse Hive-style `dt=` path values.
  - Formats: CSV, JSON, ORC, Parquet, XML.
- **CDC:** "a fully-automated service for merging a set of change events from sources such as Debezium or AWS Database Migration Service into a Tabular-managed Iceberg table", "typically with <5-minute latency" [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/understanding-how-cdc-works-in-tabular.html].
- **Events and audit as Iceberg tables** [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/default-tabular-databases.html]:
  - Every warehouse mounted a read-only `System` database containing Catalog Events, S3 Access Logs, S3 Inventory List, Sign Events, Table Scan Events and Table Snapshot Events.
  - Catalog Events fields include `event_type` (CreateDatabase, UpdateTable…), `member_id`, `credentials_key`, `tabular_service` (whether a Tabular service performed the action) and `table_changes` (the list of Iceberg MetadataUpdate class names, e.g. AddSnapshot, AddSchema).
  - Jason Reid's description: "we… share Iceberg tables with our customers that contain rich information about everything going on in their data warehouse, including an audit of all authorization decisions" [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/securing-the-data-lake-part-1/].
- **Catalog sync to AWS Glue:** one-way mirror only. Mirrored tables "should be considered read-only", and "Glue does not leverage Tabular's permission system" [read: https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/syncing-to-aws-glue.html].

**Tabular's contributions to the open spec and tooling**
- PR #3561, "[CORE] Specification for an HTTP REST catalog", was opened 2021-11-16 by Kyle Bendickson, then at Tabular. It was never merged and the spec landed through later PRs [read: https://github.com/apache/iceberg/pull/3561 via `gh pr view`].
- Dan Weeks authored PR #9628, "Add REST spec for data access mechanisms" (opened 2024-02-02, merged). This is the access-delegation mechanism of vended credentials and remote signing [read: `gh search prs` result for apache/iceberg].
- Tabular maintained the `tabulario/iceberg-rest` reference server image and the `tabulario/spark-iceberg` demo [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/rest-catalog-docker/].
- Iceberg 0.14.0 (released 2022-07-16) "Added a common REST catalog client that uses change-based commits to resolve commit conflicts on the service side" [read: https://github.com/apache/iceberg/blob/main/site/docs/releases.md].

**Acquisition and aftermath**
- **2024-06-04:** Databricks "agreed to acquire Tabular". The stated aim was format compatibility, short-term "inside Delta Lake UniForm". UniForm "support[s] the Iceberg restful catalog interface" [read: https://www.databricks.com/company/newsroom/press-releases/databricks-agrees-acquire-tabular-company-founded-original-creators].
  - Ryan Blue was then CEO, and Blue was "Iceberg PMC Chair".
  - The Databricks blog carries "June 7, 2024 UPDATE: We're excited to share that we've completed our acquisition of Tabular!" [read: https://www.databricks.com/blog/databricks-tabular].
- **Price:** not disclosed. Press reports said more than $1B [secondary: https://www.cnbc.com/2024/06/04/databricks-is-buying-data-optimization-startup-tabular.html]. Snowflake and Confluent were reportedly also bidders [secondary: same CNBC article, via search summary].
- **Customers:** Ali Ghodsi said Tabular had "hundreds of customers" and the companies would "figure out what to do with the product" [secondary: https://techcrunch.com/2024/06/04/databricks-acquires-tabular-to-build-a-common-data-lakehouse-standard, via search summary].
- **Wind-down:**
  - By 2024-09-05 the tabular.io homepage read "Tabular has joined Databricks" (© Databricks 2024) [read: https://web.archive.org/web/20240905200957/https://www.tabular.io/].
  - By 2025-09-24 tabular.io returned 301 to databricks.com [read: Wayback response headers for https://web.archive.org/web/20250924011943/https://www.tabular.io/].
  - docs.tabular.io pages were still captured with HTTP 200 on 2025-10-09 [read: Wayback CDX http://web.archive.org/cdx/search/cdx?url=docs.tabular.io/*].
  - I found no public customer-migration notice or shutdown date (see Gaps).
- **Founders at Databricks:** Ryan Blue was posting to dev@iceberg from a databricks.com address by July 2024 [read: https://lists.apache.org/thread/343ro8ov9r62pb88otwg89ryoqjlzcpn]. Jason Reid, Ryan Blue, Daniel Weeks and Michelle Leon co-authored the Databricks blog of 2026-05-28 [read: https://www.databricks.com/blog/unity-catalog-and-next-era-apache-icebergtm].
- **Unity Catalog's current Iceberg posture (2026)**, i.e. where Tabular's ideas landed:
  - Unity Catalog implements the Iceberg REST catalog at `/api/2.1/unity-catalog/iceberg-rest`, with read/write for managed Iceberg tables. An older read-only endpoint is now "legacy" [read: https://docs.databricks.com/aws/en/external-access/iceberg].
  - Credential vending issues short-lived credentials that "inherit the privileges of the Databricks principal used to configure the integration". It requires an `EXTERNAL USE SCHEMA` grant and is not supported for foreign Iceberg tables [read: https://docs.databricks.com/aws/en/external-access/credential-vending; same Iceberg page].
  - "Databricks uses serverless compute to maintain Iceberg table metadata for managed tables". Unity Catalog "manages lifecycle tasks like snapshot expiration and file compaction", and predictive optimization runs OPTIMIZE automatically [read: https://docs.databricks.com/aws/en/iceberg/].
  - The 2026-05-28 blog announced Managed Iceberg GA, Iceberg v3 GA, Foreign Iceberg GA with credential vending, and "Cross-engine Attribute-Based Access Control (Beta)". Under ABAC, "UC evaluates the applicable policies during server-side scan planning… returns a filtered scan plan", and it needs the Iceberg 1.11 REST scan-planning client [read: https://www.databricks.com/blog/unity-catalog-and-next-era-apache-icebergtm].

### Inferences
- [inference] Tabular's core catalog had four parts:
  - REST catalog, roughly the 2022–2024 spec.
  - RBAC with delegation and future grants.
  - Identity bridging (OAuth2, impersonation, trusted IdP, IAM mapping).
  - A per-file request signer plus an audit/event stream.
- [inference] Everything else was value-added services on top of the catalog's privileged position: optimizer, compaction/expiry/orphan cleanup, File Loader, CDC, Glue sync, sample databases, billing.
- [inference] Tabular's maintenance and optimizer depended on two catalog-only signals: commit events and engine scan reports sent to the REST metrics endpoint. Collecting both centrally is the cheap structural precondition for automated maintenance.
- [inference] Unity Catalog's 2026 ABAC-via-scan-planning is a different answer to the masking gap Tabular admitted (no file-level enforcement of masks). It moves fine-grained access control (FGAC) to the server's planning step, at the cost of the "thin catalog" principle discussed under Q2.

### Gaps
- I found no public statement of Tabular's internal catalog backing store, HA design, multi-region topology, SLOs or commit-rate limits. The docs only say system tables exist "in each region".
- I found no primary source on the shutdown timeline or customer migration path for Tabular's product. Reporting is limited to "figure out what to do with the product" (secondary).
- The Wayback CDX for `tabular.io/blog/*` returned "Temporarily Offline" twice. I reconstructed the blog list from archived listing pages, so a few posts may be missed, e.g. the Iceberg Summit talk pages and the "Iceberg catalogs: how to choose" video, which is audio/video only.
- The "REST Catalog Explained" episode with Dan Weeks (2023-01-09) is audio only. I did not listen to it [read: page metadata only, https://tabular.substack.com/p/rest-catalog-explained].

---

## Q2. What design principles did Tabular's founders articulate?

### Takeaway
The founders were consistent across 2021–2026 on six principles:
1. Secure the data in the storage/catalog layer, not in each engine.
2. Engines get zero storage access by default and every access is authorized against identity.
3. The REST protocol exists so that one client fits all catalogs, commits are change-based, and the server alone writes metadata.
4. The catalog's hot path should stay minimal so it does not become a central metadata bottleneck.
5. Maintenance belongs to platform services, not to data engineers.
6. Restore the table abstraction and remove sharp edges, which means killing file-system-only tables.

On fine-grained security over REST, Ryan Blue drew a precise line: the protocol should carry *decisions* (requirements) to *trusted* engines, never *policy*.

### Cited Findings

**Secure the data, not the compute** (Jason Reid, Tabular blog, 2023)
- **Part I, 2023-04-06:** "the #1 best practice: secure the data, not the access" [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/securing-the-data-lake-part-1/].
  - Ranger-style per-engine enforcement must be repeated for every engine. Spark and Python have none.
  - The remedy: "Manage and enforce access policy in one place, at the storage layer. Instead of granting broad access to compute environments, grant them zero access by default."
  - Tabular applies "authorization decisions in the catalog and at the file-access level, much like an operating system".
- **Part II, 2023-04-28:** least privilege depends on "Identity, Identity, Identity" [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/securing-the-data-lake-part-2/].
  - The authorization point must know the real user, even on shared clusters.
  - Three mechanisms: user credential; trusted identity provider ("pass the authenticated user identity along with a verification mechanism in exchange for a secure token"); IAM role mapping.
- **Part III, 2023-07-13** [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/securing-the-data-lake-part-3/]:
  - Delegation is essential: a finance admin can grant finance data but not security data.
  - Translating SQL privileges to static IAM prefix policies "can easily spiral into thousands of file access policy changes".
  - Dynamic per-request authorization must have "incredibly high throughput and low latency", since one query may touch "hundreds of thousands of files across thousands of executors".
  - Ranger "secures compute instead of storage" and "does not support Apache Spark".

**Access control belongs in the storage layer** (Ryan Blue, 2023-09-19)
- "The status quo is to configure and enforce access policy in the query layer, but in a world of shared storage this makes no sense… In Python and Spark, there's no native enforcement at all. Moving access controls to the storage layer is the path to uniform policy configuration and enforcement. Next, open standards like OAuth2 can make it easy to authorize and connect new components." [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/the-case-for-independent-storage/]
- The same post argues storage must be "neutral" and independent of compute vendors ("the Oracle Trap"). [inference] This is partly Tabular's commercial positioning (label (c)), but the neutrality argument applies equally to a self-operated enterprise catalog.

**A thin catalog, by design** (Tabular "Apache Iceberg Cookbook", c. 2024)
- Scale problems with HMS "led Netflix and other big tech companies to develop their own more scalable private HMS forks". This shaped Iceberg so that "The metastore catalog's responsibility is minimal — it just handles the atomic swap — to avoid creating a central metadata bottleneck. Iceberg distributes metadata work to clients so that it scales along with compute resources." [read: https://web.archive.org/web/20240601000000/https://tabular.io/apache-iceberg-cookbook/getting-started-catalog-background/]
- The cookbook's list of why REST matters:
  - "New languages and engines can support any catalog with just one client implementation."
  - "change-based commits to enable server-side deconfliction and retries — fewer failures!"
  - "Metadata version upgrades are easier because root metadata is written by the catalog service."
  - "lazy snapshot loading, multi-table commits, and caching."
  - "secure table sharing using credential vending or remote signing." [same source]

**Iceberg is a library; the server is someone else's project** (Ryan Blue, dev@iceberg, 2021-12-14)
- "Iceberg doesn't provide services, it provides a library… There are so many concerns here [authn/z, database backends, deployment artifacts] that I think it would be a separate project." [read: https://lists.apache.org/thread/nyh8l6oqylwyd1cfzcpq7b28p4ct088k]

**Keep writing metadata JSON from the server** (Ryan Blue, 2022-01-08)
- Ryan recommended that a REST service still write the metadata JSON file: "It provides an important backup of table state in case anything happens to the DB, and is the most portable representation of table metadata." [read: https://lists.apache.org/thread/j7dh122l944d91vp1q7gfwc4g4y8jgyh]

**Fine-grained access over REST: pass decisions, not policy, and only to trusted engines** (Ryan Blue, 2024-02-25)
- "this extension should be used to pass requirements — the result of policy decisions — and NOT be used to pass policy itself… Policy is complicated and it is modelled and enforced differently across products." [read: https://lists.apache.org/thread/9ff0fkwbtzwrh6m3h6kpfbjmdbfo00pw]
- Trust model: "the catalog either needs to deny access (401/403 response) or have some pre-established trust in the identity that is loading a table". The trust can be signalled by "additional claims in the auth token when the client is trusted to enforce fine-grained access" [same source].
- Leak-proof projection semantics: the client "is not allowed to read certain fields… even if those fields are not part of the output". This prevents leaks through queries like `SELECT count(1) … WHERE email = ?` on a protected column.
- Row filters must be applied "immediately after loading the data, before rows… are passed to any other operator".
- He would not return a permissions list. If needed, use a narrow flag like `supports-commit: false` [same source].
- On views, 2024-02-28: pass the restrictions "directly and with a clear spec instead of trying to translate to a view in the REST catalog" [read: https://lists.apache.org/thread/q8hrx044ps7p68xv42h4jg8clx0v479t].

**Restore the table abstraction; no file-system tables** (Ryan Blue, July 2024)
- He proposed deprecating HadoopTableOperations and HadoopCatalog because:
  - they are "only safe to use hadoop tables with HDFS; most local file systems, S3, and other common object stores are unsafe";
  - "HadoopCatalog cannot implement atomic operations for rename and drop table" [read: https://lists.apache.org/thread/oohcjfp1vpo005h2r0f6gfpsp6op0qps].
- Rejecting a per-store atomic-primitive design (putIfAbsent, exclusive create): "One of the critical things that we set out to do in Iceberg was to make sharp edges go away by restoring the table abstraction… Making the spec significantly more complicated only to deliver inconsistent behavior across file storage systems is not a good idea" [read: https://lists.apache.org/thread/oyo28xlwlsj5rlvq73tm3z780f2poddg]. And "we are converging on catalog-based tables as the best practice" [read: https://lists.apache.org/thread/343ro8ov9r62pb88otwg89ryoqjlzcpn].

**Automation over toil** (2021 founding post)
- "the big data ecosystem has been pawning problems off on the wrong people… Data infrastructure should do more". Iceberg "finally makes it possible to build data services to manage tables, instead of expecting people to coordinate scheduled maintenance jobs" [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/introducing-tabular/].
- At Netflix they "built services to automate compaction, migrate across AWS regions, tidy up metadata, and enforce retention policies" [same source].

**Branching, multi-table transactions, and the REST catalog as the place for new features**
- Ryan Blue (Subsurface 2023 recap, 2023-03-09) called tags and branches the development he was "most excited about". Branches improve write-audit-publish because they "can be written to multiple times before fast-forwarding the current table state" [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/iceberg-tags-and-branches/].
- On 2023-02-10, he listed REST-only feature goals: "un-drop tables for a certain period of time, to support multi-table transactions, and to enable authentication and authorization. I don't think the right strategy is to try to adapt the Hive MetaStore for it." [read: https://lists.apache.org/thread/0tx4yslrb4z4s75hzxblsrv9tcm03qd0]
- The current REST spec has `POST /v1/{prefix}/transactions/commit` ("Commit updates to multiple tables in an atomic operation") [read: https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml].
- Multi-statement multi-table transaction proposals were still under discussion in 2025–2026 (threads dated 2025-04-22, 2025-06-17, 2025-12-10, 2026-06-30) [read: dev@iceberg list index subjects only, lists.apache.org search API].

**Credential vending vs engine IAM**
- Tabular supported both directions. Engines could authenticate to the catalog *with* their AWS IAM identity, mapped to Tabular roles [read: …mapping-aws-iam-identities-to-tabular-roles.html]. But data access still went through the catalog's signer [read: …data-access-flow-in-tabular.html].
- The data-access-mechanism spec standardized this as the `X-Iceberg-Access-Delegation` header with values `vended-credentials`, `remote-signing` and (added Oct 2026) `none` [read: rest-catalog-open-api.yaml; vote result thread subject 2026-10-28 in list index]. Dan Weeks authored it (PR #9628, Feb 2024).

**Community stance** (2021-09-21)
- "we are participants and contributors, there are no owners"; Tabular "will build products that complement Iceberg… painless infrastructure and automatic table management". The format and engine-side components "should be open" [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/tabular-and-the-iceberg-community/].

### Inferences
- [inference] There is a real tension between "thin catalog, clients do metadata work" (the scalability principle) and "catalog is the security boundary that must filter rows and columns" (FGAC). The founders' resolution was:
  - (1) keep commits and loads thin;
  - (2) put coarse enforcement in storage access (signing or vended credentials);
  - (3) push FGAC *decisions* to trusted engines.
  Unity Catalog's 2026 ABAC instead puts FGAC into server-side scan planning. That reintroduces a central metadata workload, which the cookbook warned against.
- [inference] The decisions-vs-policy line is directly useful for an enterprise. Your policy engine stays yours, and the catalog only emits per-request requirements.

### Gaps
- I did not find a transcript of a Ryan Blue or Dan Weeks Iceberg Summit talk that states "the catalog should do maintenance" in those words. The evidence for that principle is the founding post, the product docs and the cookbook.
- I did not find a founder statement on multi-region catalog design.

---

## Q3. Why was the REST catalog spec created? Problems with HMS, Glue, JDBC, Hadoop catalogs and client-side catalogs

### Takeaway
The REST spec, proposed by Tabular engineers between November 2021 and January 2022 and first shipped in Iceberg 0.14.0 in July 2022, was designed to remove four structural problems of client-side catalogs:
1. Every engine and language needed a jar or reimplementation per catalog, and hosted engines would not accept custom jars.
2. Every writer had to upgrade in lockstep because clients wrote root metadata, so older writers silently dropped new fields.
3. Commits were whole-file swaps with client-side retries, rather than server-side, change-based conflict resolution.
4. Some backends (JDBC) should not be exposed to thousands of clients.

The Hadoop catalog has a different problem: it is unsafe on object stores, and the spec now marks file-system tables deprecated, to be removed in table spec v4.

### Cited Findings

**Proposal history**
- **2021-12-13:** Ryan Murray (Dremio) asked what the long-term goal was and whether a server implies "authn/z, database backends, deployment artifacts". This responded to PR #3561 [read: https://lists.apache.org/thread/tvnr0s6rf3frvd2zp672k11olf47g13x].
- **Jack Ye (AWS), same day** [read: https://lists.apache.org/thread/42km1hyf6f6lm69lc9wlztysmw42m5j6]:
  - REST "only tells the server side the set of changes and let the server handle those changes".
  - "catalog providers can focus on the server side implementation… no new integration and maintenance is needed when Iceberg rolls out new catalog features or support for new languages."
  - "We have seen issues in streaming where the table metadata JSON file size gets too big and impact read… rewriting the entire metadata file is very inefficient."
- **Ryan Blue, 2021-12-14**, gave three motivations [read: https://lists.apache.org/thread/nyh8l6oqylwyd1cfzcpq7b28p4ct088k]:
  1. Scaling: "JDBC is a good example, where you probably don't want a ton of connections going directly to a database. An intermediate service is a great way to scale such a metastore."
  2. Hosted engines: "When I was at Netflix, we used a custom metastore to track tables… it meant that our platform was incompatible with things like AWS Athena because we'd either have to plug in a Jar or get them to implement a bespoke REST protocol just for us. Right now, catalog customization is only available if you use the Hive thrift API."
  3. Version upgrades: "Because the metadata JSON file is written by clients, upgrading to support new features is difficult… if those versions write to a table, the refs get dropped. That means we have to upgrade versions at the same time across all writers… with a change-based protocol we can update the Iceberg version of just the service that writes the metadata JSON files."
- **Kyle Bendickson (Tabular), 2021-12-15:** it is "an additional catalog", not intended as a catalog specification on the level of the table spec [read: https://lists.apache.org/thread/4c64bdnxmwn2drsrjron34zjcxptsy0t]. [inference] That framing did not last; by 2026 the REST spec is the de facto interoperability contract.
- **2022-01-03:** Ryan Blue's design doc for table updates: "send a list of requirements, like 'current snapshot must be <ID>', and a list of changes, like 'add snapshot ...'" [read: https://lists.apache.org/thread/3tfojb702l2dc7xblb5q9cwd3w1d1fxd]. In his 2022-01-08 reply [read: https://lists.apache.org/thread/j7dh122l944d91vp1q7gfwc4g4y8jgyh], the change-based API means:
  - "commits to different branches don't need to conflict with one another, nor should metadata commits";
  - "Only one writer version is producing metadata JSON files";
  - "Reads and writes send data directly to/from S3".
- **2023-02-10, three goals restated** [read: https://lists.apache.org/thread/0tx4yslrb4z4s75hzxblsrv9tcm03qd0]:
  1. Standardize the interface ("you don't need to get Dremio or Athena to put your custom catalog's Jar in their classpath… having a slightly different JDBC implementation in every language is going to lead to inconsistencies");
  2. Enable new features that are hard to fit into HMS;
  3. Fix problems such as "the time it takes to load metadata files with lots of snapshots" and per-table FileIO configuration.
- **ApacheCon NA 2022 (Sam Redai, Tabular)** listed the challenges of client-side catalogs as "Consistency across implementations", "Client is responsible for compatibility" and "New languages / ecosystems introduce more complexity" [read: https://apachecon.com/acna2022/slides/02_Redai_Apache_Icebergs_REST.pdf].
  - REST features listed: standardized interface, centralized metadata management, multiple client versions, deconflicted commit contention (diff-based commits), improved security ("authorization", "identity aware sessions"), connectivity.
  - Next steps listed: "Multi-table transactions", "Scan and commit metrics", "View catalog".
  - A REST server "can wrap any existing Iceberg catalog implementation".

**Hadoop catalog**
- "can only be used with HDFS or similar file systems that support atomic rename. Concurrent writes with a Hadoop catalog are not safe with a local FS or S3." [read: https://github.com/apache/iceberg/blob/main/docs/docs/java-api-quickstart.md]
- Table spec, current main: "This file system based scheme to commit a metadata file is **deprecated** and will be removed in version 4 of this spec. The scheme is **unsafe** in object stores and local file systems." [read: https://github.com/apache/iceberg/blob/main/format/spec.md]
- Dan Weeks (FileIO post): "In the cloud, some implementations of rename are not atomic at all and split into two operations: copy and delete." Iceberg instead tracks explicit, immutable, absolute file locations, so "a commit is adding a new branch to the metadata tree that is fully reachable and requires no rename operations" [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/iceberg-fileio-cloud-native-tables/].
- The cookbook: "Amazon S3 — by far the most popular object store — supports none of these [atomic rename / exclusive create / putIfAbsent]" (written c. 2024) [read: cookbook URL above].
- Ryan Blue noted in July 2024 that S3 directory buckets are single-zone and not a general fix [read: https://lists.apache.org/thread/343ro8ov9r62pb88otwg89ryoqjlzcpn].

**Hive Metastore commit path**
- Iceberg's HiveCatalog commits under HMS locks. It is "a 2-step process: Lock Creation… Lock Check", with defaults of 3-minute lock-acquire timeouts and 4-minute heartbeats, which "should be less than the transaction timeout of the Hive Metastore". Disabling locks (`iceberg.engine.hive.lock-enabled=false`) is safe only if *all* HiveCatalogs writing those tables also disable them [read: https://github.com/apache/iceberg/blob/main/docs/docs/catalog-properties.md].
- Version coupling with Hive:
  - HiveCatalog "supports Hive 2.3.10 or 3.1.3 or later".
  - Since Iceberg 1.8.0 the project no longer releases a Hive runtime connector. Hive 2.x/3.x users must stay on the 1.6.1 connector or move to Hive 4, which embeds Iceberg (Hive 4.0.x bundles Iceberg 1.4.3; 4.1/4.2 bundle 1.9.1) [read: https://github.com/apache/iceberg/blob/main/docs/docs/hive.md].

**Glue**
- Glue commits use optimistic locking on a table version id. With AWS SDK below 2.17.131 "only in-memory lock is used" and a DynamoDB lock manager is needed for atomicity [read: https://github.com/apache/iceberg/blob/main/docs/docs/aws.md].
- If `glue.skip-archive=false`, "streaming ingestion… will quickly create a lot of Glue table versions" [same source].
- Tabular's own Glue mirror noted "Glue has only a single catalog per region" [read: …syncing-to-aws-glue.html].

### Inferences
- Labels for what came out of this history:
  - [inference] **(a) spec/client:** change-based commits with requirement assertions, server-written metadata, and an access-delegation header.
  - [inference] **(b) enterprise operator:** a single client across engines and languages, independent upgrades of writers, protecting the backing DB from client fan-out, and audit/authorization at the server. These are exactly the pains of a firm with many engines.
  - [inference] **(c) vendor model:** none of the original motivations were vendor-specific. The hosted-engine argument ("get Athena to support it") matters to any enterprise using managed engines.
- [inference] The 2021 thread shows the community deliberately left server concerns (authn/z, persistence, deployment) out of Iceberg. Every server product (Tabular, Polaris, Unity, Lakekeeper, Gravitino) therefore fills that space differently. That is where "bloat" can enter.

### Gaps
- I did not read the 2022 Google design doc itself, only the email describing it.
- I found no published numeric comparison of commit-conflict rates for REST vs HMS/Glue.

---

## Q4. Lessons from Hive Metastore and Netflix Metacat at scale

### Takeaway
HMS failed at scale mainly because partition-level metadata lived in a single relational database behind a Thrift API:
- partition adds timed out;
- query planning over thousands of partitions overloaded the database;
- teams cut retention to cope.

Large operators worked around it with direct-to-database access (Netflix), private forks, and federation proxies (Expedia Waggle Dance, Netflix Metacat). Iceberg moved partition metadata out of the metastore into files, which shrinks the catalog database to pointers, names, grants and events. The lessons that carry over to a new catalog are about the API surface and events, not about partitions.

### Cited Findings

**Netflix Metacat** (Netflix Tech Blog, 2018-06-14) [read: https://netflixtechblog.com/metacat-making-big-data-discoverable-and-meaningful-at-netflix-56fb36a53520]
- Metacat is "a federated metadata access layer for all data stores we support". It offers "a unified REST/Thrift interface"; underlying stores stay the source of truth for schema, and Metacat stores only business and user-defined metadata.
- "The Metacat thrift service supports the Hive thrift interface for easy integration with Spark and Presto", which "enables us to funnel all metadata changes through one system… to publish notifications".
- Events go to SNS and Keystone. For example, "when a table is dropped, our S3 warehouse janitor services can subscribe to this event and clean up the data on S3".
- "The Hive metastore, backed by an RDS, does not perform well under high load… we no longer use these APIs… our Hive connector… talks directly to the backed RDS for reading and writing partitions. Before, Hive metastore calls to add a few thousand partitions usually timed out."
- Wishlist (2018): "Schema and metadata versioning to provide the history of a table… important for auditing, debugging".

**Netflix scale and catalog status, Nov 2024** (Trino Summit 2024 deck) [read: https://trino.io/assets/blog/trino-summit-2024/trino-summit-2024-netflix.pdf]
- 1+ EB warehouse; 3M+ Iceberg tables; 99.5%+ Iceberg adoption; largest table 36 PB.
- 10+ PB ingested/day and 9+ PB deleted/day; 2 PB replicated/day.
- **600 peak commits per second** and **12K peak table loads per second**.
- Trino at Netflix uses a "Metacat Connector" and "NetflixAccessControl". "Integration with Iceberg Rest Catalog (IRC)" was listed as future work.
- Third-party blogs claim Netflix runs an internal Iceberg catalog called "Polaris" on CockroachDB. I could not verify this from a Netflix source [secondary: https://dev.to/jonisar/netflix-intelligent-lakehouse-solves-iceberg-maintenance-you-can-easily-too-5a84].

**HMS forks** (Tabular cookbook)
- "Scale challenges led Netflix and other big tech companies to develop their own more scalable private HMS forks." HMS also mixes technical and federated catalog roles, because "Hive and Spark could only interact with a single HMS instance" [read: cookbook URL in Q2].

**Airbnb** (2022-09-26) [read: https://medium.com/airbnb-engineering/upgrading-data-warehouse-infrastructure-at-airbnb-a4e18f09b6d5]
- "With an increasing number of partitions, Hive's backend DBMS's load has become a bottleneck, as has the load on partition operations (e.g., querying thousands of partitions for a month's worth of data)."
- Workarounds: daily aggregation tables, and limiting "intraday Hive tables to short retention (three days)".
- S3 list-after-write consistency needed a custom HDFS-staging plus distcp commit path.
- Iceberg benefit: "Partition information is not stored in the Hive metastore, hence removing a large source of load to the metastore."
- Scale: ">35 billion Kafka event messages and 1,000+ tables per day".

**Expedia Waggle Dance** [read: https://github.com/ExpediaGroup/waggle-dance]
- "a request routing Hive metastore proxy that allows tables to be concurrently accessed across multiple Hive deployments… created to tackle the appearance of dataset silos that arose as our large organization gradually migrated from monolithic on-premises clusters to cloud based platforms."
- It maps virtual database names to federated metastores, which are read-only except the primary.

**Version-coupling and lock pain** (Iceberg docs)
- See Q3: HMS lock timeouts and heartbeats must be tuned below `hive.txn.timeout`, and Hive version support is fragmenting [read: catalog-properties.md; hive.md].

### Inferences
- [inference] The HMS database bottleneck came from partition-level rows and partition API calls. Iceberg catalogs hold one pointer per table, so with "many thousands of tables" the per-table metadata DB load is small. The hot paths become `loadTable` reads (Netflix: 12K/s peak) and conditional pointer swaps (Netflix: 600/s peak), plus credential issuance and signing if you do them.
  - [inference] Size the database for read caching and compare-and-swap throughput, not for rows.
- [inference] Metacat's durable lessons for the new catalog:
  - (1) funnel *all* metadata changes through one service so you can emit events, for janitors, lineage, audit and data-driven ETL;
  - (2) keep business/user metadata separate from technical metadata;
  - (3) table history and versioning matter for audit. Iceberg snapshots plus a catalog event log provide this natively.
- [inference] Waggle Dance shows that federation and a migration bridge are an enterprise need, label (b). Unity Catalog's "Foreign Iceberg" and Polaris federation are the modern equivalents. For a from-scratch catalog, read-only federation to legacy HMS/Glue is a migration aid, not a core feature.

### Gaps
- I did not find a primary Netflix source describing the internal catalog behind the 3M-table estate (backing DB, HA, regions). The Trino deck only says Metacat connector, with IRC as future work.
- I did not find a primary HMS high-availability write-up with numbers. HMS HA is typically several stateless Thrift servers on one RDBMS, but I have no read source to cite.

---

## Q5. Lessons from large Iceberg operators (catalog scale, commit rates, maintenance, failures)

### Takeaway
The published numbers are few but telling:
- Netflix reports 600 commits/s and 12K table loads/s at peak across 3M+ tables.
- Adobe hit about 15 commits/min per table in 2021 and had to batch writes.
- LinkedIn built its own REST "control plane" (OpenHouse) on Kubernetes with a key-value interface over MySQL, catalog-owned maintenance and replication, and a "tables, not files" security rule.
- At Iceberg Summit 2025, several enterprises (Airbnb, Bloomberg) had built their own Postgres-backed REST catalogs rather than adopt OSS catalogs, mainly to integrate internal access control.
- Streaming plus maintenance is the recurring operational pain.

### Cited Findings

**Netflix (Nov 2024)**
- The numbers in Q4: 3M+ tables, 600 peak commits/s, 12K peak table loads/s, 36 PB largest table, more than 100 million files in some tables (distributed metadata-table scan needed), 2 PB/day replicated [read: https://trino.io/assets/blog/trino-summit-2024/trino-summit-2024-netflix.pdf].
- Ryan Blue on the Netflix era: Iceberg let Netflix make Kafka data available "in minutes rather than hours" and replace "an in-memory database with a multi-petabyte Iceberg table and Trino". Netflix "built services to automate compaction, migrate across AWS regions, tidy up metadata, and enforce retention" [read: https://web.archive.org/web/20240601000000/https://tabular.io/blog/introducing-tabular/].

**LinkedIn OpenHouse** (LinkedIn Engineering blog; 2023 per URL path) [read: https://engineering.linkedin.com/blog/2023/taking-charge-of-tables--introducing-openhouse-for-big-data-mana]
- Four principles:
  - "Tables (not files/blobs) are the only API abstraction for end-users… no direct read/write is permitted to files or blobs";
  - tables live "in a protected storage namespace that the control plane has full control over";
  - tables "are governed as per agreed upon company standards";
  - tables "are maintained regularly".
- Table service: a RESTful service on Kubernetes behind Envoy, with:
  - declarative desired-state APIs reconciled by data services;
  - built-in RBAC "abstract[ing] away all the underlying FileSystem and BlobStore permissioning";
  - "horizontally scalable, prevents noisy neighbors".
- The "House database service… exposes a key-value interface that is designed to use a NoSQL DB for scale and cost optimization. However the deployed system is currently backed by a MySQL instance, for ease of development and deployment."
- Maintenance: snapshot expiration, orphan-file deletion, "quarantine zones for deleted files", manifest compaction, GDPR purge, and cross-datacenter snapshot replication. Each runs as a Spark job per table triggered by Kubernetes cronjobs, and "all our data services can be triggered almost instantaneously as Iceberg snapshots are committed".
- Before OpenHouse, "65% of tables fall under the self-managed category" and onboarding a managed table took "2 to 3 weeks". Afterwards, self-serve took "seconds".
- Press reports say more than 3,500 OpenHouse tables and BSD-2 open-sourcing in March 2024 [secondary: https://www.infoworld.com/article/2336362/linkedin-open-sources-lakehouse-tool-openhouse.html, via search summary].

**Adobe Experience Platform**
- **2021-09-21:** "Initial tests with Iceberg showed a limit of 15 commits per minute per dataset, whereas we needed to process at least 30 times more per minute per dataset". The cost of "reading the previous snapshot and then creating a new one… increases with time as more and more metadata are added". The fix was a separate buffered-writes service that consolidates files to minimize commits [read: https://experienceleaguecommunities.adobe.com/adobe-experience-platform-18/high-throughput-ingestion-with-iceberg-13687].
- **2021-04-15:** they chose merge-on-read for restatements because copy-on-write with "high frequency commits" caused write amplification and "pressure on the critical path of write operations from system tasks" [read: https://blog.developer.adobe.com/iceberg-series-acid-transactions-at-scale-on-the-data-lake-in-adobe-experience-platform-f3e8fe0cef01].

**Iceberg Summit 2025 user talks** (Tower's summary; secondary for the talk contents) [secondary: https://tower.dev/blog/seven-takeaways-from-seven-iceberg-summit-user-talks]
- "Several users built their own REST-based catalogs backed by Postgres. They did not use the Polaris, Lakekeeper, or Gravitino catalogs".
- Airbnb "Built a custom catalog to integrate with an internal access system".
- Bloomberg "Created a REST-compatible catalog using PyIceberg backed by Postgres" and implemented table-level access control "via metadata tags (e.g., users only get a list of tables that they have access to)".
- Slack still uses HMS and "suffers from orphan file bloat due to many writers and frequent commits". Airbnb's Flink writes need "aggressive compaction".
- A Microsoft panelist named row-level security, column masking and RBAC as enterprise needs. "security and governance needs are ahead of capabilities in OSS catalogs."

**Airbnb, 2022:** see Q4 (HMS partition load; Iceberg removes it) [read].

**dbt Labs' view, 2025** (vendor opinion) [read: https://www.getdbt.com/blog/iceberg-give-it-a-rest]
- On self-hosting a catalog: "largely 'No!'", except for "Enterprise security requirements (think: on-prem, self-managed data centers)".
- Open question: "What's the multi-region and/or multi-cloud story of Iceberg catalogs? Right now everything presumes the same cloud and same region".

### Inferences
- [inference] For a large financial firm with "many thousands" of tables, Netflix's peaks are a useful upper bound: three orders of magnitude more tables. Load throughput, caching and conditional-update latency will dominate design long before table count does.
- [inference] Adobe's 15 commits/min per table came from client-side metadata rewrites in 2021. REST change-based commits and server-side retries help with conflicts, but a single table's commit rate is still bounded by serial pointer swaps plus metadata writes.
  - [inference] The catalog should expose per-table commit-rate metrics and conflict counts, and streaming writers should batch.
- [inference] OpenHouse and the 2025 summit reports suggest the strongest pattern at large self-operating enterprises: a thin, in-house REST catalog integrated with the firm's own entitlement system, plus catalog-triggered maintenance. They did not adopt a vendor-shaped OSS catalog.

### Gaps
- I found no primary published catalog lessons from Apple, Stripe, Pinterest (catalog-specific), Expedia (Iceberg-era), Goldman Sachs, JPMorgan, Capital One or other banks. Searches returned only third-party or summary material. Bloomberg's details are secondary.
- I have no primary source on catalog *failures* (outages, corruption) at these operators.

---

## Q6. Synthesis: what Tabular got right that today's OSS catalogs lost, and what it lacked

### Takeaway
Tabular's catalog was small in concept and opinionated: one resource hierarchy, one role type with nesting and delegation, no superuser, identity bridging for shared engines, every file access authorized and logged, and audit and telemetry as queryable Iceberg tables. Its many value-add services were separate from the catalog's core.

Today's OSS catalogs (Polaris is the clearest example from the pages I read) carry vendor-oriented structure:
- multi-tenant realms;
- a two-tier principal-role/catalog-role model;
- non-Iceberg "generic tables";
- policy entities;
- events that are explicitly not an audit trail.

Tabular itself lacked anything an enterprise could run: it was SaaS-only, AWS-centric, with no published HA or multi-region design and no file-level masking.

### Cited Findings

**What Tabular did (all [read], citations in Q1)**
- One hierarchy, org → warehouse → database → table, with a warehouse equal to one object store.
- One role type: nested roles, WITH GRANT delegation, FUTURE grants, cascades, no super-user, no ownership.
- Remote signing for every file read via a table access token, with Sign Events logged.
- Catalog Events, Table Scan Events and Snapshot Events as Iceberg tables; "audit of all authorization decisions".
- Service-credential impersonation for shared Trino; a "custom identity provider" trust path; IAM mapping; a system-user fallback.
- Maintenance configured through table properties with safe defaults.

**Polaris as documented today** (for comparison)
- Realms: "a logical partitioning mechanism… [that] allows for multitenancy", with the realm id part of the persistence primary key [read: https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/realm.md].
- RBAC: "assigns access privileges to catalog roles and then grants access to resources to principals by assigning catalog roles to principal roles". "You don't grant privileges directly to a principal role" [read: https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/managing-security/access-control.md].
- "Generic tables are non-Iceberg tables… Delta, CSV" (beta) [read: https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/generic-table.md].
- A Policy framework for "governance, lifecycle, and operational rules" [read: https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/policy.md].
- Events: "Polaris events are not a complete audit trail. They do not record authorization denials or authentication failures, and delivery is asynchronous and best-effort" [read: https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/events.md].
- A MongoDB persistence backend exists "currently in **beta**" via Quarkus [read: https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/metastores/nosql-mongodb.md].

**Events in the open spec**
- The REST spec has no events endpoint yet. PR #12584 "REST Spec: Events endpoint" (opened 2025-03-20 by c-thiel, author of Lakekeeper) is still OPEN as of 2026-11-06 [read: `gh pr view 12584`].
- Tabular shipped catalog events as tables in 2024 [read: …default-tabular-databases.html].

**Unity Catalog** (Databricks' successor to Tabular's ideas)
- It re-implements the main ideas: managed maintenance, credential vending, centralized governance, federation [read: Q1 citations].
- Its 2026 positioning is explicitly "single pane of glass" and the "most interoperable Iceberg catalog" [read: https://www.databricks.com/blog/unity-catalog-and-next-era-apache-icebergtm]. That is label (c) language by definition.

### Classification of Tabular's features for a self-operated enterprise catalog
| Capability | (a) spec/client needs | (b) enterprise operator needs | (c) mainly vendor model | Evidence |
|---|---|---|---|---|
| REST endpoints: config, namespaces, tables, commit with requirements | Yes | Yes | | spec yaml [read] |
| Server-written metadata JSON as backup and portable state | Spec-required (Ryan, 2022) | Yes, for DR | | [read] Jan 2022 email |
| OAuth2 token endpoint and token exchange | Clients expect OAuth2-style auth | Yes; federate to the corporate IdP | | Tabular docs [read] |
| Access delegation (vended credentials / remote signing) | Optional header in spec | Yes; essential for "zero access by default" | | PR #9628, docs [read] |
| SQL-style RBAC with nesting, GRANT delegation, FUTURE grants, no superuser | No (spec silent) | Yes | | Tabular RBAC docs [read] |
| Identity bridging (impersonation for shared Trino, trusted IdP, IAM mapping) | No | Yes | | credentials doc [read] |
| Audit/events as Iceberg tables, including authorization decisions | No (events PR open) | Yes (regulated firm) | | system DB doc [read] |
| Column masking via labels (no file-level enforcement) | No | Partly; needs trusted-engine contract | | masking FAQ [read] |
| Maintenance (expire, compaction, manifest rewrite, orphan cleanup) by catalog-owned service | No | Yes (toil, cost) | Optimizer "experiments" are value-add | table-properties, optimizer docs [read] |
| Scan-metrics ingestion (REST `/metrics`) | Endpoint in spec | Yes; feeds maintenance and audit of reads | | optimizer doc [read]; spec has `/metrics` [read] |
| File Loader, CDC, Kafka connectors | No | Useful, but separate services | Product upsell / ingestion business | docs [read] |
| Organizations, billing usage stats, free tier, sample "Examples" DB | No | No | Yes | docs index (CDX) [read] |
| One-way Glue sync | No | Migration/coexistence aid only | Partly (reach into AWS-native engines) | Glue sync doc [read] |
| Multi-tenant realms (Polaris) | No | No (single enterprise) | Yes (SaaS) | realm.md [read] |
| Generic non-Iceberg tables (Polaris, Unity) | No | Maybe later (Delta coexistence) | Largely (platform breadth) | generic-table.md [read] |

### Inferences
- [inference] **What Tabular got right that OSS catalogs lost or diluted:**
  1. Security is the catalog's *purpose*, not a plugin. Every byte read is authorized and logged, which is the remote-signing plus sign-events design.
  2. One RBAC model that maps 1:1 to how DBAs and entitlement teams already think: nested roles, GRANT delegation to data owners, FUTURE grants, no superuser.
  3. Audit as data: queryable Iceberg tables of catalog, sign, scan and snapshot events.
  4. A small resource model with no tenancy layer.
  5. Maintenance is the default and configured through table properties.
  6. A thin commit and load path.
- [inference] **What Tabular lacked, or what doesn't transfer:**
  1. It could not be self-hosted. The control plane, SQS queues and SigV4 gateway lived in Tabular's AWS account.
  2. No published HA, DR or multi-region design or SLOs.
  3. Column masking without file-level enforcement, and no documented row-level security.
  4. AWS-first, with GCS in preview and ADLS mentioned only in storage profiles.
  5. Glue sync bypassed RBAC.
  6. Remote signing puts the catalog on the data path for every file GET. That is a throughput and availability risk, which Jason Reid himself flagged ("incredibly high throughput and low latency").
- [inference] **Fairness check on "bloat".** Some features that look like vendor bloat are enterprise needs at the firm's scale: federation for migration, events, policy for lifecycle, and possibly Delta coexistence. The genuinely vendor-shaped items I observed are multi-tenant realms, billing and usage, sample data, and the "single pane of glass" or "most interoperable" breadth push (generic tables, cross-vendor federation connectors). I did not audit Polaris code size or complexity; another researcher's Polaris notes should be consulted.

### Gaps
- I did not verify whether Polaris supports remote signing, or whether its policy framework executes maintenance itself or only stores rules.
- I did not compare Tabular's role model against Unity Catalog's privilege model in detail.

---

## Decisions this implies

**D1. Where authorization is enforced: catalog only, or catalog plus storage gate**
- **Options:**
  - (i) The catalog authorizes metadata operations only, and engines hold broad bucket access.
  - (ii) The catalog authorizes, and engines have zero storage access except via vended, prefix-scoped, short-lived credentials.
  - (iii) As (ii), but via remote signing of every request.
- **Evidence:**
  - Reid: "grant them zero access by default" [read].
  - Tabular used per-file signing with sign-event audit [read].
  - The spec supports both `vended-credentials` and `remote-signing` [read].
  - Reid warns that dynamic authorization needs "incredibly high throughput" [read].
  - Netflix shows 12K loads/s peak [read]; signing volume would be per file, i.e. far higher [inference].
- **Recommendation:** (ii) as the default for all warehouses. Offer (iii) only for designated high-sensitivity warehouses where per-object audit is a regulatory requirement, and load-test the signer first. Never (i) for production. [inference]

**D2. Fine-grained access control (rows and columns)**
- **Options:**
  - (i) None in v1: table-level only, with sensitive columns split into separate tables.
  - (ii) Send *requirements* (projection and filter decisions) only to engines trusted through token claims, per Ryan Blue's 2024 model.
  - (iii) Server-side scan planning that returns filtered plans (Unity Catalog ABAC, Beta, 2026).
- **Evidence:**
  - Tabular's masks had no file-level enforcement [read].
  - Blue: "pass requirements… NOT… policy"; trust via token claims [read].
  - UC ABAC requires the Iceberg 1.11 scan-planning client [read].
  - The cookbook warns against central metadata bottlenecks [read].
- **Recommendation:** (i) at launch, plus the hide-column style of masking for trusted engines only. Design the token-claim trust model now so that (ii) can be added. Treat (iii) as a later, opt-in path for untrusted engines once client support is broad. [inference]

**D3. RBAC model shape**
- **Options:**
  - (i) Tabular/Snowflake-style single role type with nesting, WITH GRANT, FUTURE grants, no superuser, no ownership.
  - (ii) Polaris-style principal roles plus catalog roles.
  - (iii) Delegate entirely to an external policy decision point.
- **Evidence:**
  - Tabular RBAC docs and the RBAC best-practices post, which gets object-access vs business-function roles through nesting alone [read].
  - The Polaris two-tier model [read].
  - Blue: catalogs should keep "the model that they choose" [read].
- **Recommendation:** (i), with roles sourced from the corporate directory via SCIM and grants managed as code (Terraform or API). Keep the authorization check behind an interface so (iii) remains possible. [inference]

**D4. How "smart" the catalog's hot path is**
- **Options:**
  - (i) Thin: atomic pointer swap, change-based commits, credential issuance, metrics ingest.
  - (ii) Thick: server-side scan planning and caching of manifests.
- **Evidence:**
  - Cookbook: "responsibility is minimal… to avoid creating a central metadata bottleneck" [read].
  - Netflix 600 commits/s, 12K loads/s [read].
- **Recommendation:** (i) for v1, with response caching for `loadTable`. Add (ii) only behind D2(iii). [inference]

**D5. Persistence and the MongoDB lean**
- **Options:** (i) MongoDB; (ii) Postgres or MySQL; (iii) a distributed SQL database (CockroachDB-style).
- **Evidence:**
  - Iceberg removes partition-level rows, so the database holds pointers, names, grants and events [inference from Airbnb and Metacat, read].
  - LinkedIn designed a KV interface "for a NoSQL DB" but ran MySQL [read].
  - The 2025 summit enterprises used Postgres [secondary].
  - The Netflix CockroachDB claim is unverified [secondary].
  - The Polaris MongoDB backend is beta [read].
  - Blue: the server should still write metadata JSON as "an important backup of table state" [read].
- **Recommendation:** choose the database on four criteria, not on popularity:
  1. single-table compare-and-swap;
  2. atomic multi-entity commit, needed for `/transactions/commit`;
  3. cross-region consistency semantics matching D7;
  4. The firm's operational expertise.
- Put persistence behind a narrow KV-plus-CAS interface (LinkedIn's pattern), and always write metadata JSON to object storage. MongoDB is acceptable *if* its multi-document transaction and write-concern semantics are verified against criteria 2–3. [inference; MongoDB semantics not researched here]

**D6. Maintenance ownership**
- **Options:**
  - (i) Leave it to each team's jobs.
  - (ii) A catalog-owned maintenance service, configured by table properties with defaults and triggered by commit events.
- **Evidence:**
  - Tabular defaults: 5-day snapshot expiry, binpack compaction, 60-minute manifest rewrite, orphan cleanup [read].
  - OpenHouse runs per-table jobs "triggered almost instantaneously as Iceberg snapshots are committed" [read].
  - Slack's orphan-file bloat and Airbnb's compaction needs [secondary].
  - Adobe's commit-rate ceiling [read].
- **Recommendation:** (ii). Run it as separate services outside the catalog process, holding their own service identity and RBAC grants, so maintenance commits are attributable in audit. Tabular tagged these with `tabular_service=true` [read]. [inference]

**D7. Multi-region HA**
- **Options:**
  - (i) Single-writer home region per warehouse, with async replica catalogs read-only elsewhere.
  - (ii) Active-active writes across regions.
- **Evidence:**
  - No Tabular design was published (gap).
  - Netflix replicates 2 PB/day and "migrate[d] across AWS regions" with services [read].
  - OpenHouse did snapshot replication for DR [read].
  - dbt flags multi-region as unsolved [read].
- **Recommendation:** (i). Pin each warehouse (or namespace) to a home region for commits, replicate pointers and metadata asynchronously, and treat failover as an explicit, audited operation. [inference]

**D8. Audit and events**
- **Options:**
  - (i) Best-effort async events (Polaris today).
  - (ii) A durable, transactional event log (outbox in the same transaction as the commit or grant) that includes denials, exposed as Iceberg tables and as a stream.
- **Evidence:**
  - Tabular's system tables and "audit of all authorization decisions" [read].
  - Polaris events doc: not an audit trail; no denials; best-effort [read].
  - The REST events PR is still open [read].
  - Metacat's event-driven janitors [read].
- **Recommendation:** (ii). [inference]

**D9. Scope exclusions**
- **Options:** include or exclude File Loader, CDC, Glue sync, multi-tenancy (realms), generic tables, billing.
- **Evidence:** the classification table above.
- **Recommendation:**
  - Exclude from the catalog core.
  - Ingestion services may be built later as ordinary clients.
  - Federation to legacy HMS/Glue should be read-only and treated as a migration aid (Waggle Dance lesson).
  - No realms. [inference]

**D10. Legacy catalogs**
- **Options:** support Hadoop/file-system tables, or not.
- **Evidence:** the spec deprecates file-system tables and will remove them in v4; Blue: unsafe outside HDFS [read].
- **Recommendation:** do not support. Provide a register-table path to adopt existing tables. The spec has `/register` [read]. [inference]

**D11. Implementation language**
- **Options:** Java, Rust, Go, Python.
- **Evidence:**
  - The REST server can "wrap any existing Iceberg catalog implementation", and translation via `CatalogHandlers` exists in the Java library [read: Redai slides; cookbook].
  - Iceberg's Java library is the reference for applying metadata updates [inference].
- **Recommendation:** Java, consistent with the prior lean. Reusing Iceberg-core's metadata builders and update application reduces spec-compliance risk. [inference]

---

## Open questions for the enterprise

1. Which engines must be supported on day one (Spark, Trino, Flink, Snowflake, Databricks, Python/DuckDB, Dremio, others)? Which of them is the firm willing to *trust* to enforce row and column requirements (D2)?
2. Is per-object read audit (remote signing) a regulatory requirement for any data class, or is per-table, credential-issuance audit enough (D1)?
3. What are the expected peaks for `loadTable` per second, commits per second overall, and commits per minute on the hottest single table (streaming/CDC)? Netflix's 12K/s and 600/s, and Adobe's 15/min/table, are reference points.
4. Multi-region: is the requirement read availability in a second region, or *write* availability during a regional outage? What are the RPO and RTO for catalog state (D7)?
5. Identity sources: Entra ID/AD groups, Kerberos, AWS IAM roles, workload identities? Do shared engine clusters need per-user impersonation (Tabular's service credential pattern)?
6. Storage: AWS S3 only, or also on-prem S3-compatible stores and Azure/GCS? Do those stores support the session-policy scoping needed for vended credentials?
7. Are the firm's MongoDB clusters operated with the multi-document transaction and cross-region write-concern guarantees that atomic multi-table commits need (D5)?
8. What audit retention and immutability rules apply to catalog events, grants and access decisions? Must denials be logged?
9. Who funds and owns maintenance compute (compaction, expiry, orphan cleanup), and what default retention is acceptable (Tabular defaulted to 5 days of snapshots)?
10. What legacy catalogs exist today (HMS versions, Glue accounts), and how many tables must be migrated or federated read-only during transition?
11. Will non-Iceberg formats (Delta, Hive) need to coexist under the same governance, or can the catalog stay Iceberg-only?
12. Is there appetite to contribute to the Iceberg REST spec (events endpoint, FGAC requirements, access-delegation behaviour) so the in-house catalog stays spec-aligned instead of growing private extensions?

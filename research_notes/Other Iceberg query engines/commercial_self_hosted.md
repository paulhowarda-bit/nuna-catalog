# Commercial and enterprise engines that can query Iceberg through an external REST catalog (self-hosted focus), as of 12 November 2026

*Provenance tags used on every line. **[P]**: I read it in a primary source (vendor docs, vendor press release, vendor GitHub) at the URL given, on 2026-11-12. **[S]**: a secondary source I opened (third-party article, competitor blog, pricing tracker). **[S-snip]**: only seen in a search-engine summary of the page; I did not open the page, so treat it as weaker than [S]. **[I]**: my inference. "Not found" means I looked and found nothing, not that the feature is absent. The nuna requirements are numbered R1 to R6 as in the brief (R1 REST protocol and OAuth2 bearer JWT, plus end-user pass-through; R2 vended credentials with vended `s3.endpoint` and `s3.path-style-access`; R3 nested namespaces; R4 v2 and v3 reads, and writes; R5 on-prem or Kubernetes, footprint; R6 ease of operation and air-gapped install).*

## Key question 1: Which of these can be deployed entirely on-prem and still connect to a third-party Iceberg REST catalog, rather than only to their own catalog?

### Takeaway
Starburst Enterprise is the only commercial product that documents the whole nuna checklist on-prem. It covers a static token or client credentials, end-user token pass-through, vended credentials, nested namespaces, v2 deletes and v3. Dell Data Lakehouse is a Starburst build and has the same features. Firebolt self-managed (Core, under the Elastic License 2.0), Vertica (now Rocket Software), Denodo's embedded Presto, EDB PGAA, Couchbase Enterprise Analytics and Exasol's Lakehouse Turbo can also run fully on-prem against a generic REST catalog, but each has gaps. Firebolt and Vertica do not read v3, and Vertica reads no delete files at all. Firebolt and Exasol do not document honouring a vended S3 endpoint. Exasol copies data into its own store. Several products cannot use nuna this way. e6data needs its SaaS control plane. Oracle is not certified for generic REST catalogs, does not support credential vending, and fails on delete files. Teradata on-prem supports Hive only. Cloudera's on-prem side is a REST catalog server, and its engine-side REST client is a cloud-only preview. IBM watsonx.data documents external REST catalogs only for SaaS. CedarDB, Ocient and Yellowbrick have no Iceberg support today.

### Cited Findings

#### Summary matrix (detail and sources in the fact sheets below)

| Product | Fully on-prem, no vendor cloud | Generic third-party REST | Static token / client creds | End-user pass-through to catalog | Vended creds | Nested ns | v2 pos / eq deletes | v3 read | Writes | Licence, free tier | Latest version (date) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Starburst Enterprise (SEP) | Yes (Helm on K8s/OpenShift) | Yes | Yes / Yes | **Yes** (`OAUTH2_PASSTHROUGH`) | Yes (opt-in) | Yes (opt-in) | Yes / Yes [I] | DVs, row lineage, variant, ns timestamps, defaults; no geo listed | Yes | Commercial licence file; no free tier for SEP | 483-e STS (16 Oct 2026); 482-e LTS (11 Sep 2026) |
| Dell Data Lakehouse | Yes (appliance, SEP) | Yes (SEP 480-e docs) | as SEP | as SEP | as SEP | as SEP | as SEP | as SEP 480-e | Yes | Dell + Starburst OEM | Docs at 480-e LTS |
| Firebolt self-managed / Core | Yes (binary, Docker, Helm, operator) | Yes | **No static token for generic REST** / Yes | Not found | Yes (fallback keys if not vended) | Dotted namespace accepted [I] | Yes / Yes (some type limits) | **No** (no DVs, variant, geo) | Export only (CTAS), no DML | ELv2, free, no usage limits | 4.32 (rollout done end Jun 2026) |
| e6data | **No**, control plane always e6data SaaS; compute on EKS/AKS | Yes | Yes / Yes | Not found | Not documented | Not found | Pos yes / **eq no** | "in progress" | Not documented | Unpublished | Release notes stop 6 Aug 2025 |
| Exasol Lakehouse Turbo | Yes (as-application on Linux, 2026.1+) | Yes | Yes / Yes | Not found | Yes (`VENDED`) | Not found | Not documented | Not documented | Caches into Exasol | Exasol Personal free, single user | 2026.1 (May 2026) |
| Vertica (Rocket) | Yes | Yes, per-table REST URL | Yes / Yes | Not found | Not found | via URL path [I] | **No delete files** | **No** (v1/v2 only) | Read-only external tables; export to Iceberg added later | Not checked | 26.1 docs read; 26.2/26.3 exist |
| Teradata VantageCore (on-prem) | Yes | **No** (Hive only on-prem) | n/a | n/a | n/a | n/a | not checked | not checked | Yes (via Hive) | Commercial | DB Engine 20.00.24.x (May 2026 doc) |
| Oracle AI Database 26ai / ADB | On-prem install of Data Studio from 23.26.2 | Generic REST not certified (ADB certifies Polaris, Unity) | Bearer, OAuth2 | Not found | **Not supported** | Not found | **Fails on any delete file** (ADB) | Not found | `INSERT ... SELECT` into Iceberg (26ai) | Oracle licence | 26ai (23.26.2) |
| IBM watsonx.data | Software edition on OpenShift exists | Documented on SaaS (Snowflake Open Catalog, Unity, Confluent Tableflow) | OAuth2 (SaaS docs) | Not found | Not found | Not found | Not found | Not found | Not found | Not found | 2.3.x |
| Cloudera (on-prem 7.3.2) | Yes | **Server side**: Cloudera's REST catalog serves others. **Client side**: Impala REST is a cloud-only, read-only tech preview | Knox OAuth tokens (server) | Knox/Ranger on server side | Server vends (Ozone STS on-prem) | Not found | Hive v2 improvements | Not found | n/a | Commercial | 7.3.2 GA Mar 2026 (LTS) |
| Denodo Embedded MPP (Presto) | Yes (Helm) | Yes [I], standard Presto `iceberg.catalog.type=rest` | OAuth2 credential or token | Not found | Not found | Not found | Presto [I] | Not found | Read-only for Snowflake Open Catalog | Commercial | MPP 9.20251127 (Presto 0.295) |
| EDB Postgres AI PGAA | Yes | Yes (`iceberg-rest`) | Yes / Yes | Not found | Yes (recommended) | Not found | Not found | Not found | CTAS writes | Commercial | not checked |
| Couchbase Enterprise Analytics | Yes (self-managed) | Yes (`SOURCE REST`) | Bearer / OAuth 2.0 | Not found | Not found | Not found | Not found | Not found | Read-only [S-snip] | Commercial | not checked |
| CedarDB / Ocient / Yellowbrick | Yes | **No Iceberg** | | | | | | | | | |

#### Starburst Enterprise (SEP): only what it adds over open-source Trino
- **Version**: the latest STS is 483-e, released 16 Oct 2026. The latest LTS is 482-e, released 11 Sep 2026. Earlier releases are 480-e LTS (29 May 2026), 479-e LTS (27 Feb 2026) and 477-e LTS (25 Nov 2025). — [P] https://docs.starburst.io/latest/release.html
- **R1, REST properties**: `iceberg.rest-catalog.security` takes `NONE`, `SIGV4`, `GOOGLE` or `OAUTH2`. OAUTH2 needs either `iceberg.rest-catalog.oauth2.token` (a static bearer token) or `...oauth2.credential` (client credentials). The other OAuth2 properties are `...oauth2.server-uri` (the token endpoint), `...oauth2.scope` and `...oauth2.token-refresh-enabled` (default true). The general properties are `iceberg.rest-catalog.session` (`NONE` or `USER`, default `NONE`), `prefix`, `warehouse`, `http-headers`, `max-retries` (default 5) and `case-insensitive-name-matching`. — [P] https://docs.starburst.io/latest/object-storage/metastores.html
- **R1, end-user pass-through (the Starburst addition)**: "The Iceberg REST catalog supports OAuth2 passthrough." It is enabled with `http-server.authentication.type = DELEGATED-OAUTH2` on the coordinator and `iceberg.rest-catalog.security = OAUTH2_PASSTHROUGH` in the catalog. — [P] https://docs.starburst.io/latest/object-storage/metastores.html
  - How it works: SEP "uses the same token as a user accessing a data source directly". The user authenticates to SEP with OAuth 2.0, and "the received token is passed through SEP and the connector to the underlying data source". `web-ui.authentication.type=DELEGATED-OAUTH2` must also be set. — [P] https://docs.starburst.io/latest/security/oauth2-passthrough.html
  - Limitation: "Each access token's remaining lifetime must be longer than the query's execution time." Also, "no token refresh mechanism is supported." — [P] same page
  - Token exchange: `iceberg.rest-catalog.oauth2.token-exchange.enabled` (default false) "Enables the OAuth2 Token Exchange flow". It is documented only for Databricks Unity on AWS, where "the IdP token cannot be passed directly" and is first exchanged for a Databricks OIDC token. `...token-exchange.extra-credentials.prefix` forwards extra credentials, for example a service-principal `client_id`. — [P] https://docs.starburst.io/latest/object-storage/metastores.html
  - The metadata cache (`iceberg.rest-catalog.metastore-cache.ttl`) "is silently disabled when set to `USER`" and is disabled for any per-user security mode. — [P] same page
  - Dynamic configuration passthrough: per-query extra credentials can supply the REST URI, warehouse, OAuth2 server URI, secret, scope, token and token-exchange flag, with `iceberg.rest-catalog.uri = iceberg-dynamic-passthrough`. — [P] same page
  - When each feature first appears in the versioned docs:
    - `vended-credentials-enabled` and `nested-namespace-enabled` first appear in the 468-e docs (Feb 2025) and are absent from 462-e. — [P] https://docs.starburst.io/468-e/object-storage/metastores.html vs https://docs.starburst.io/462-e/object-storage/metastores.html
    - `OAUTH2_PASSTHROUGH` first appears in 477-e LTS (25 Nov 2025) and is absent from 476-e. — [P] https://docs.starburst.io/477-e/object-storage/metastores.html
    - `token-exchange.enabled` first appears in 479-e (27 Feb 2026). — [P] https://docs.starburst.io/479-e/object-storage/metastores.html. The "first appears" dating is my reading of the doc diffs [I].
  - A message on the Iceberg dev list says "query engines like Starburst and StarRocks" pass the user's token through to the catalog, which "requires the catalog to accept tokens with an additional audience". The message is undated. — [S-snip] https://go.mail-archive.com/dev@iceberg.apache.org/msg13245.html
- **R2, vended credentials**: `iceberg.rest-catalog.vended-credentials-enabled` "Use credentials provided by the REST backend for file system access. Defaults to `false`." — [P] https://docs.starburst.io/latest/object-storage/metastores.html. The REST table has no S3 endpoint property. The page does not say whether SEP honours the vended `s3.endpoint` or `s3.path-style-access`, or whether `s3.endpoint` must be repeated in SEP's own S3 file system config. — [P, absence]. SEP presumably inherits open-source Trino's behaviour here, which is assessed elsewhere [I].
- **R3**: `iceberg.rest-catalog.nested-namespace-enabled` "Support querying objects under nested namespace. Defaults to `false`." — [P] same page
- **R4**:
  - "The connector supports Apache Iceberg table spec versions 1, 2, and 3. The default version is v2." The listed v3 features are deletion vectors, default column values, row lineage, nanosecond `timestamp(tz)` and `variant` (now mapped to the Trino `variant` type). Geometry and geography are not listed. — [P] https://docs.starburst.io/latest/connector/iceberg.html
  - Merge-on-read "in Iceberg v3 or later ... writes deletion vectors instead of delete files". — [P] same page
  - **Internal contradiction**: the `format_version` table-property text on the same page still says "Version 3 support is experimental; row-level updates, deletes, and OPTIMIZE are not supported." — [P] same page
  - The page documents equality-delete and position-delete metadata and "dangling" delete detection. — [P] same page
  - Writes (INSERT, UPDATE, DELETE, MERGE) are supported. — [P] same page
- **R5, Kubernetes**:
  - SEP is deployed with Helm charts (Helm 3.2.4+). No operator is mentioned. — [P] https://docs.starburst.io/latest/k8s/requirements.html
  - Supported Kubernetes versions are 1.26 to 1.36. Red Hat OpenShift is "tested regularly and supported". The supported ecosystems also include Rancher RKE2, EKS, GKE and AKS. — [P] https://docs.starburst.io/latest/k8s/requirements.html and https://docs.starburst.io/latest/k8s.html
  - Footprint: nodes need "64 to 256 GB RAM" and "16 to 64 cores". "All nodes must be identical" and each node runs one SEP coordinator or worker. — [P] https://docs.starburst.io/latest/k8s/requirements.html
- **R6, air-gap**: the images and charts are on Starburst's Harbor registry, but "You can also use your own Docker registry". The config docs describe registry overrides. I found no dedicated air-gapped install page. — [P] https://docs.starburst.io/latest/k8s/requirements.html, https://docs.starburst.io/latest/k8s/sep-configuration.html
- **Licence and pricing**:
  - SEP needs a signed `starburstdata.license` file on all nodes. Warp Speed needs the licence "regardless of the SEP deployment method". No price is published. — [P] https://docs.starburst.io/latest/license-requirements.html
  - A third-party comparison quotes "$500 per vCPU per year with a $100,000 annual minimum". This is unverified. — [S-snip] https://www.rfp.wiki/artificial-intelligence/data-lakehouse-platforms/iomete/starburst
- **Company health**:
  - Starburst reportedly passed $100M ARR in Feb 2026. — [S-snip] https://briefglance.com/articles/starburst-crosses-100m-arr-declares-end-of-the-bi-dashboard-era
  - It launched an "Enterprise Intelligence Platform" with the AIDA assistant, GA on Galaxy and SEP, on 28 May 2026. — [S-snip] https://www.starburst.io/press-releases/starburst-unveils-enterprise-intelligence-platform-giving-enterprises-a-faster-path-to-trusted-ai/
  - It announced "Starburst Torin", a GPU-native engine on NVIDIA cuDF. — [S-snip] https://www.sttinfo.fi/tiedote/72431613/starburst-introduces-starburst-torin-a-gpu-native-analytics-engine-built-on-nvidia-cudf?publisherId=58763726
  - Justin Borgman is still CEO (Mar 2026). I found no layoffs or acquisition. — [S-snip, search summary]

#### Dell Data Lakehouse (appliance; Starburst inside)
- The Dell Data Lakehouse is a "turnkey solution" made of the Dell Data Analytics Engine, "powered by Starburst", and Dell Lakehouse compute on PowerEdge. — [S-snip] https://www.delltechnologies.com/asset/en-us/products/storage/briefs-summaries/dell-data-lakehouse-solution-brief.pdf
- Dell's own SEP documentation site shows "Latest LTS (480-e)". Its metastores page contains `OAUTH2_PASSTHROUGH`, `vended-credentials-enabled` and `nested-namespace-enabled`. — [P] https://dell.starburst.io/latest/object-storage/metastores.html. So Dell's build trails upstream SEP (483-e) by a few releases but carries the REST features [I].
- On 19 May 2026, Dell AI Factory updates added GPU-accelerated SQL to the Data Analytics Engine and an ObjectScale X7700 appliance. — [S-snip] https://www.hpcwire.com/bigdatawire/this-just-in/dell-unveils-broad-ai-factory-upgrades-to-accelerate-enterprise-agentic-ai-deployment/
- Starburst was named Dell's 2026 OEM Partner of the Year. — [S-snip, search summary of Starburst news]

#### Firebolt self-managed and Firebolt Core
- **R5, deployment**:
  - The self-managed components are a Kubernetes operator (`firebolt-kubernetes-operator`), a Helm chart (`firebolt-instance-helm`) and standalone binaries. — [P] https://docs.firebolt.io/FireboltCore.md (page titled "Self-Managed")
  - The operator and the Helm chart are both Apache-2.0. — [P] https://api.github.com/orgs/firebolt-db/repos?sort=pushed&per_page=30
  - The metadata service is either embedded in the engine (SQLite) or a separate `dedicated-pensieve` process backed by Postgres for multi-engine use. — [P] https://docs.firebolt.io/self-managed/standalone-binaries/overview.md
  - Production should use the operator or the Helm chart. — [P] same page
- **Footprint**:
  - "at least 16 GB RAM and at least 25 GB SSD", amd64 with SSE 4.2 or arm64, and Linux kernel 6.1 or newer (`io_uring`). Multi-node setups should have 10 Gbit/s between nodes. The image is `ghcr.io/firebolt-db/engine:dev`. — [P] https://github.com/firebolt-db/firebolt-core (README, read via WebFetch)
  - Helm engines need a memlock limit of at least 8 GiB, which the chart sets with a sidecar by default. — [P] https://docs.firebolt.io/self-managed/helm-chart/prerequisites.md
- **R6, air-gap**: the chart pulls from `oci.firebolt.io` and `ghcr.io/firebolt-db`, and the docs say you can "mirror through your own registry". — [P] https://docs.firebolt.io/self-managed/helm-chart/prerequisites.md
- **Engine source availability (conflicting docs)**: the Self-Managed overview says to "build the Engine and Metadata Service from source". The standalone page says "prebuilt binaries". The firebolt-core repo listing shows no engine source directory. — [P] https://docs.firebolt.io/FireboltCore.md, https://docs.firebolt.io/self-managed/standalone-binaries/overview.md, https://github.com/firebolt-db/firebolt-core. Engine source availability is unconfirmed.
- **Licence**:
  - Firebolt Core is under the **Elastic License 2.0**. It forbids offering it as a hosted or managed service and forbids circumventing licence-key functionality. — [P] https://raw.githubusercontent.com/firebolt-db/firebolt-core/main/LICENSE.md
  - The README says it is "free to use, forever" and has "no usage limits". — [P] https://github.com/firebolt-db/firebolt-core
  - Managed-service list prices are S at $1.84/hour and XL at $14.72/hour (US East). No self-managed support price is published. — [P] https://www.firebolt.io/core
- **Version**: 4.32 added `CREATE ICEBERG DATABASE` for REST-shaped catalogs and a `STORAGE_CREDENTIALS` clause "as a fallback when the catalog does not vend its own storage credentials". Rollout was "expected to be completed by the end of June 2026". — [P] https://docs.firebolt.io/release-notes.md
- **R1**:
  - `CREATE LOCATION ... WITH SOURCE = ICEBERG CATALOG = REST`, with `URL`, `WAREHOUSE` and `NAMESPACE` in `CATALOG_OPTIONS`. Credentials are `OAUTH_CLIENT_ID`, `OAUTH_CLIENT_SECRET`, `OAUTH_SCOPE` and `OAUTH_SERVER_URL`. A secret-only flow is allowed. — [P] https://docs.firebolt.io/reference-sql/commands/data-definition/create-location-iceberg.md
  - **`BEARER_TOKEN` "Only supported for `DATABRICKS_UNITY` catalog"**, so a static JWT against a generic REST catalog is not documented. Client credentials are. — [P] same page
  - No end-user pass-through is documented. — [P, absence]
- **R2**:
  - Vended credentials are used when the catalog vends them. `STORAGE_CREDENTIALS` is the fallback. `MAX_STALENESS` caches "metadata and vended credentials". — [P] create-location-iceberg.md, https://docs.firebolt.io/reference-sql/functions-reference/iceberg/read_iceberg.md
  - The Iceberg capability table says data files are "Apache Parquet on Amazon S3". — [P] https://docs.firebolt.io/guides/iceberg-and-data-lake/iceberg.md
  - S3-compatible endpoints with `path_style_addressing` (MinIO) are documented only for the engine's own backing storage. — [P] https://docs.firebolt.io/self-managed/helm-chart/usage/object-storage/s3-compatible.md
  - Whether Iceberg reads honour a vended `s3.endpoint` is **not documented**. — [P, absence]
- **R3**: the Snowflake Horizon example uses `NAMESPACE = 'my_database.my_schema'`. — [P] create-location-iceberg.md. That suggests a dotted multi-level namespace is accepted [I].
- **R4**: spec versions are "Iceberg v1 and v2". The capability table says:
  - Positional deletes: supported.
  - Equality deletes: supported "except on dropped columns and REAL or DOUBLE PRECISION columns".
  - "Deletion vectors (v3): Not supported".
  - "variant, geometry, and geography are not supported".
  - Time travel: not supported.
  - Writes: "Export only, via CREATE ICEBERG TABLE AS SELECT; no DML".
  — [P] https://docs.firebolt.io/guides/iceberg-and-data-lake/iceberg.md

#### e6data
- **R5 and R6**: "In both [models], the Control Plane ... is hosted by e6data." In the "In Your VPC" model, compute runs in "your Kubernetes cluster (EKS, AKS)". It needs outbound port 443 to e6data's control plane, event sink, monitoring and container registry. Serverless is "Less ideal when ... you're air-gapped". — [P] https://docs.e6data.com/platform/deployment-models.md
- "The self-managed install guide is under construction." — [P] https://docs.e6data.com/query/get-started/self-managed.md
- A "Hybrid Data Lakehouse" mode, with ancillary clusters on-prem, is "in active development and only available to users on request". — [P] https://docs.e6data.com/query/concepts/hybrid-data-lakehouse.md
- **R1**: the catalog type is "Iceberg REST (Lakekeeper)", with Catalog URI, Warehouse and Prefix. Authentication is "Bearer Token" or "OAuth 2 Client Credentials" (Client ID, Client Secret, Token Endpoint URL). The optional Governance setting uses "Ranger, OPA, Unity, or Lake Formation". — [P] https://docs.e6data.com/query/catalogs/iceberg-rest-catalog.md
- **R2**: vended credentials are not mentioned. Storage access uses workload identity (EKS Pod Identity/IRSA, Azure Workload Identity). — [P] https://docs.e6data.com/platform/deployment-models.md
- **R4**:
  - v1 and v2 are supported. Position deletes are supported. "Equality deletes: Not supported". Time travel is not supported. "Support for Iceberg v3 spec is in progress". — [P] https://docs.e6data.com/query/table-formats/iceberg.md
  - A 30 Mar 2024 release note claims "support for deletion vectors in Iceberg tables". That predates Iceberg v3, so it is probably mislabelled [I]. — [P] https://docs.e6data.com/release-notes.md
- **Health and pricing**: the newest public release note is dated 6 Aug 2025. — [P] https://docs.e6data.com/release-notes.md. No pricing is published. — [S] https://www.g2.com/products/e6data/pricing

#### Exasol (Lakehouse Turbo)
- **What it is**: "a plug-in query engine". It mirrors and "accelerates selected tables" into Exasol, so it caches data rather than reading Iceberg in place. — [P] https://docs.exasol.com/db/latest/connect_exasol/lakehouse_turbo_as_app.htm, https://docs.exasol.com/db/latest/connect_exasol/lakehouse_turbo/lakehouse_turbo_get_started_as_app.htm
- **Availability**: Exasol SaaS and as-application deployments of "Exasol 2026.1 and later", including Exasol Personal. The as-application setup needs on-premises Exasol 2026.1.0+ with Exasol Admin, and "Podman must be installed on all nodes". — [P] same pages. Only the Innovation Release stream is supported. — [S-snip, search summary of Exasol docs]
- Exasol 2026.1 was "scheduled for May 18". — [P] https://community.exasol.com/t/whats-new-in-exasol-2026-1/296
- **R1 and R2 config**: settings are made by REST `PUT /api/v1/dlhc/databases/<id>/config`:
  - `sourceSystemType: "ICEBERG"`, `iceberg.restCatalogUri` and `iceberg.warehouseLocation`.
  - `iceberg.authType` takes "OAUTH2", "TOKEN" or "NONE", with `oauthClientId`, `oauthClientSecret`, `oauthServerUri`, `oauthScope`, `oauthAudience`, `oauthResource`, `tokenExchangeEnabled` and `token`.
  - `dataLake.credentialType` takes "VENDED" or "AWS".
  — [P] https://docs.exasol.com/db/latest/connect_exasol/lakehouse_turbo/dlhc_iceberg_rest_api_as_app.htm
- **Storage**: S3, "S3-compatible storage (as-application deployments only)", and ADLS. — [P] lakehouse_turbo_as_app.htm. Endpoint and path-style settings are not documented. — [P, absence]
- **R4**: Iceberg versions and delete handling are not documented. — [P, absence]
- **R5**: native cloud deployment on Azure and GCP is unavailable, and you "install Exasol as a Linux application" instead. — [P] dlhc_iceberg_rest_api_as_app.htm. I found no Kubernetes deployment [I].
- **Free tier**: Exasol Personal is free, has "no limits on data or features" but a single user, and runs locally (macOS, Linux, Windows) or in your own AWS, Azure, Exoscale or STACKIT account. Commercial use is not addressed. — [P] https://docs.exasol.com/db/latest/get_started/exasol_personal.htm

#### Vertica (OpenText Analytics Database, now owned by Rocket Software)
- **Ownership**:
  - Rocket Software agreed to buy Vertica from OpenText on 2 Feb 2026. — [S-snip] https://www.nasdaq.com/press-release/rocket-software-acquire-vertica-analytics-database-platform-opentext-2026-02-02
  - The deal completed on 11 May 2026. — [S-snip] https://www.dbta.com/Editorial/News-Flashes/Rocket-Software-Finalizes-Acquisition-of-Vertica-Strengthening-Analytics-AI-and-Data-Modernization-Across-Hybrid-Cloud-174739.aspx
  - It was reportedly US$150M cash, with "600 customers and 170 employees". — [S-snip] https://idm.net.au/index.php/node/15497
  - Rocket's own news page returned 403. — [P, observed]
  - `docs.vertica.com` now returns 302 redirects to `docs.rocketsoftware.com`, a JavaScript portal that I could not read, for versions 25.2 to 26.3. — [P, observed 2026-11-12]
- **R1** (26.1 docs, archived 21 Apr 2026):
  - `CREATE EXTERNAL TABLE ... STORED BY ICEBERG LOCATION 'https://host/.../namespaces/<ns>/tables/<t>' REST_AUTH '<json>'`.
  - `REST_AUTH` "can include either a bearerToken ... or OAuth2 fields": `oauthTokenUri`, `oauthClientId`, `oauthClientSecret`, `oauthAllowedScopes`.
  - The supported metastores are "AWS Glue, Hive (version 2.3.0 or later), and any metastore that complies with the Iceberg Standard Rest Open API, such as Project Nessie, Lakekeeper".
  — [P] https://web.archive.org/web/20260421095657/https://docs.vertica.com/26.1.x/en/sql-reference/statements/create-statements/create-external-table-iceberg/
- **R2**: vended credentials are not mentioned. Path remapping uses the `IcebergPathMapping` parameter. — [P] same
- **R3**: the REST location is the table URL, so a nested namespace would have to be encoded in the path [I, untested].
- **R4**: "Data files must be in Parquet format." "Metadata files must use version 1 or version 2 format." "Iceberg column defaults are not supported." "**Iceberg delete files are not supported.**" — [P] same. Version 26.1 added export to Iceberg, and 26.2 extended export to filesystem, Glue and HMS catalogs. — [S-snip] https://docs.vertica.com/26.1.x/en/new-features/26.1/

#### Teradata
- **On-prem**: VantageCore VMware with DB Engine 20.00.24.x supports OTF lakehouses, but "This release only supports connection to Hive catalog and Hive metastore services". Storage is an "on-premises object store compatible with Amazon S3-APIs" such as Dell ECS. Iceberg tables can be read and written through the Hive catalog. The page was published May 2026 (last edition 2026-05-08). — [P] https://docs.teradata.com/r/VMware/Teradata-VantageCore-VMware-Quick-Start/VantageCore-VMware-Quick-Start-Requirements/Open-Table-Format-OTF-Support
- **Cloud**: VantageCloud Enterprise 3.2.0.0 (Mar 2026) lists "Teradata Managed Native OTF" read and write with Glue and Hive catalogs. — [S-snip] https://docs.teradata.com/api/khub/documents/ZeraTIEaXBUfExACwO6PYA/content (URL as returned by search). The VantageCloud Lake and AI Unlimited datasheet names Glue, Hive Metastore and Unity. — [S-snip] https://id.teradata.com/getattachment/7c9e36c0-0d40-4727-990d-ffbde571a08c/open-table-formats-for-vantagecloud-lake-and-ai-unlimited.pdf
- I found no Polaris or generic REST catalog support. — [S-snip, absence]
- **Health**:
  - The 2024 restructuring cut about 9 to 10% of the workforce.
  - Teradata settled its litigation with SAP on 19 Feb 2026 and received $480M gross.
  - Steve McMillan is still CEO (Q1 2026 results, 5 May 2026).
  - 2026 guidance is Total ARR growth of 2 to 4%.
  — [S-snip] https://www.boardroomalpha.com/sec/tdc-8-k-2026-02-23-0001628280-26-010578, https://marketchameleon.com/PressReleases/i/2299105/TDC/teradata-reports-first-quarter-2026-financial-results

#### Oracle AI Database 26ai and Autonomous AI Database
- **On-prem enablement**: "Starting with Oracle Database 26ai (23.26.2), you can now install [Data Studio] as an option for your database." Iceberg catalogs need `ICEBERG_CREDENTIAL` ("to call Iceberg REST Catalog endpoints; typically a bearer token credential") and `DATA_STORAGE_CREDENTIAL`, mounted with `DBMS_CATALOG.MOUNT_ICEBERG`. — [P] https://docs.oracle.com/en/database/oracle/oracle-database/26/arpls/dbms_catalog.html
- **Native Iceberg tables (26ai)**: `CREATE ICEBERG TABLE` creates a table in an Iceberg catalog, where "catalog_name must identify an Iceberg REST catalog" and the bucket "must be an S3-compatible endpoint".
  - Tables must follow "the ICEBERG V2 specification".
  - The only write path is `INSERT INTO ... SELECT` from an Oracle table into a partitioned Iceberg table made with `CREATE ICEBERG TABLE`.
  - Unsupported types are TIME, UUID, FIXED and BINARY.
  - Prerequisites are DBMS_CLOUD plus, on-premises, the Data Studio package.
  — [P] https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/create-iceberg-table.html
- **ORACLE_BIGDATA driver (26 Utilities)**: supports the Glue catalog and manifest-file access only, with Parquet. — [P] https://docs.oracle.com/en/database/oracle/oracle-database/26/sutil/oracle_bigdata-accessing-apache-iceberg.html
- **ADB-Serverless restrictions**:
  - "ADB is certified with Snowflake Polaris and Databricks Unity Catalog (UniForm only) for Iceberg read access". Glue and Google are also supported, but no generic REST catalog is listed.
  - "**Catalog credential vending: Not supported.**"
  - "**If Iceberg metadata references delete files, queries will fail.**"
  - Google bearer tokens are not refreshed.
  — [P] https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/query-external-data-apache-iceberg.html
  - The Unity and Polaris REST catalogs were added on 7 Oct 2025. — [P] https://docs.oracle.com/iaas/releasenotes/autonomous-database-serverless/2025-10-broader-and-enhanced-iceberg-rest-catalog-support.htm

#### IBM watsonx.data (Presto C++/Java and Spark; software edition on OpenShift)
- v2.3 was announced 10 Dec 2025. It is "GA on IBM Power, bringing ... hybrid and on-premises setups", is available on AWS GovCloud (FedRAMP), and adds "Enhanced access control propagation". — [P] https://www.ibm.com/new/announcements/watsonx-data-v2-3-new-innovations-powering-enterprise-ai-at-scale
- **External REST catalogs (SaaS docs)**:
  - Snowflake Open Catalog can be queried from Presto using the Iceberg connector with the REST catalog type, the `/polaris/api/catalog` URI and OAuth2 credentials. — [S-snip] https://www.ibm.com/docs/en/watsonxdata/saas?topic=catalog-querying-snowflake-open-using-presto-engine
  - Databricks Unity can be queried from Spark through REST with OAuth2. — [S-snip] https://www.ibm.com/docs/en/watsonxdata/saas?topic=catalog-querying-databricks-unity-using-spark-engine
  - IBM's docs pages returned 403 to direct fetches. — [P, observed]
- A Confluent Tableflow Iceberg REST catalog was added "as a component" with REST endpoint, API key and secret, then associated with a Presto engine (17 Mar 2026). — [P] https://community.ibm.com/community/user/blogs/william-accetta/2026/03/17/aiready-data-with-open-standards-using-confluent
- watsonx.data's own Metadata Service "implements selected APIs from the Unity Catalog Open API specification and Iceberg REST Catalog Open API specification" (Jun 2025). — [S-snip] https://community.ibm.com/community/user/blogs/mrudula-madiraju/2025/06/07/support-for-unity-catalog-api-iceberg-rest-catalog
- Presto (C++) catalog property pages exist for the OpenShift (2.0.x) edition. — [S-snip] https://www.ibm.com/docs/SSDZ38_2.0.x/wxd/install/cust_parm_api_pcpp_cat.html

#### Cloudera (on-prem 7.3.2)
- **Server side**: the Cloudera Iceberg REST Catalog is "a server-side implementation based on Apache Iceberg REST Catalog Open API". It runs as an "embedded service ... within the same Java Virtual Machine (JVM) as the Hive Metastore", is secured with Knox OAuth tokens and Ranger, and "vends short-lived, scoped storage credentials". On-prem it vends credentials for Ozone S3 through the Ozone STS. The compatible third-party engines listed are Spark, Trino, Snowflake, Athena, EMR Spark and Databricks. — [P] https://docs.cloudera.com/cdp-private-cloud-base/7.3.2/overview/topics/cr-ds-cloudera-iceberg-rest-catalog.html
- **Known issues**: the client must send `X-Iceberg-Access-Delegation: vended-credentials` (CDPD-119319). Only `s3a://` tables can be shared, not `ofs://`. — [S-snip] https://docs.cloudera.com/runtime/7.3.2/public-release-notes/topics/rt-known-issues-iceberg-rest-catalog.html
- **Client side**: "Iceberg REST catalog support in Cloudera Data Warehouse Impala" is a technical preview in Impala 2025.0.21.0, in the 31 Mar 2026 release. It "currently supports the read path only", and the notes say "Do not use these features in production environments". It is Cloudera on cloud only, and I found no on-prem equivalent. — [P] https://docs.cloudera.com/data-warehouse/cloud/release-notes/topics/dw-public-cloud-tp-r44.html
- 7.3.2 reached GA in March 2026. The summary includes Hive Iceberg v2 improvements and makes no mention of v3. — [S-snip] https://docs.cloudera.com/cdp-private-cloud/latest/release-summaries/topics/announcement-202603-732.html. It is an LTS release "through 2032". — [S-snip] https://cldr-steven-matison.github.io/release/Introducing-Cloudera-Runtime-7.3.2

#### SAP and Dremio: ownership question only
- **Announced**: 4 May 2026. "The transaction is expected to close in Q3 of 2026". Terms were not disclosed. — [P] https://news.sap.com/2026/05/sap-to-acquire-dremio-unify-sap-and-non-sap-data-power-agentic-ai/
- **Completed**: SAP "has completed the acquisition of Dremio", dated 6 Jul 2026. — [P] https://news.sap.com/2026/07/sap-completes-dremio-acquisition/. One deal tracker gives 27 Jul 2026, an outlier. — [S-snip] https://www.saasrise.com/deals/sap-completes-acquisitions-of-dremio-and-prior-labs-f7cfd87b-7133-4f76-9d0a-9931f8f8d498. The "Dremio is now part of SAP" banner on dremio.com is accurate. — [P] https://www.dremio.com/newsroom/sap-to-acquire-dremio-to-unify-sap-and-non-sap-data-to-power-agentic-ai/
- **What SAP said**:
  - "SAP Business Data Cloud will become an Apache Iceberg-native enterprise lakehouse."
  - Dremio will provide "a universal open catalog built on Apache Polaris and the Apache Iceberg REST Catalog API".
  - On Iceberg, Polaris and Arrow: "SAP is fully committed to continuing to invest in and prioritize these contributions."
  — [P] SAP announcement above
- **What it did not say**: neither SAP release nor Dremio's newsroom addresses Dremio Software (self-managed), the Community Edition, standalone sales, pricing or contracts. — [P, absence] (both SAP releases and the Dremio newsroom)
- **Current editions page**:
  - "Dremio Community (26.0+)" is "Free", "Self-managed on Docker or Kubernetes. On-premises, Cloud".
  - "Dremio Enterprise (26.1)" is self-managed on Kubernetes and "Consumption-based".
  - Dremio Cloud is fully managed on AWS and Azure.
  — [P] https://docs.dremio.com/editions
- **Open-source repo**: `dremio/dremio-oss` is Apache-2.0 and not archived, but its last push was 2025-09-26. — [P] https://api.github.com/repos/dremio/dremio-oss
- **Pricing trackers**:
  - The Community Edition was removed from the main product comparison around 1 Jan 2026. — [S] https://pricingsaas.com/companies/dremio/diffs/2025Q4
  - Dremio dropped its free trial ("requires sales contact") in March 2026. — [S] https://pricingsaas.com/companies/dremio/diffs/2026Q1
- **Competitor claims** (Starburst blog, 17 Jun 2026): Dremio 26.0 Kubernetes deployments send telemetry to Dremio by default, which drives usage-based billing, and the Cloud Enterprise edition is "effectively on hold". — [S, competitor] https://www.starburst.io/blog/dremio-acquired-by-sap/

#### Appliance-style and others found
- **HPE Data Fabric 8.0** ships Apache Polaris as its Iceberg catalog. Polaris 1.3.0.0 (DEP 10.1.0, build 2604) adds "Polaris federation for external Iceberg REST catalogs". The engines listed are Spark, Flink and Dremio. — [S-snip] https://docs.ezmeral.hpe.com/datafabric-customer-managed/80/Ranger/PolarisRN-1.3.0.0-DEP-1010-2604.html, https://docs.ezmeral.hpe.com/datafabric-customer-managed/80/ReleaseNotes/whatsnew.html. HPE offers a catalog here, not a commercial SQL engine [I].
- **Denodo Embedded MPP** (Presto, deployed with Helm):
  - Snowflake Open Catalog is configured with `connector.name=iceberg`, `iceberg.catalog.type=rest`, `iceberg.rest.uri`, `iceberg.rest.auth.type` (`NONE` or `OAUTH2`; "OAUTH2 requires either a credential or token"), `iceberg.rest.auth.oauth2.credential`, `...oauth2.scope` and `iceberg.catalog.warehouse`.
  - "Iceberg tables managed by Snowflake Open Catalog are read-only when accessed via the Denodo Embedded MPP."
  — [P] https://community.denodo.com/docs/html/browse/9.2/en/mpp/configuration/external_metastore/snowflake_open_catalog
  - Because these are plain Presto REST properties, any spec-compliant REST catalog should work [I].
  - MPP 9.20251127 upgraded Presto to 0.295. — [S-snip] https://community.denodo.com/docs/html/browse/9.4/en/mpp/new_features/new_features_9.20251127
- **EDB Postgres AI, Analytics Accelerator (PGAA)**:
  - The catalog type `"iceberg-rest"` covers Lakekeeper, Tabular, Polaris, Nessie and MinIO AIStor. There is also `"iceberg-s3tables"`.
  - Authentication is a `"token"` or `"oauth2.grant_type": "client_credentials"` with `oauth2.client_id`, `oauth2.client_secret`, `oauth2.token_uri` and `oauth2.scope`.
  - The docs recommend credential vending. Otherwise `store_config` takes an `"endpoint"` (MinIO) and `"allow_http"`.
  - It reads, and writes through CTAS.
  — [P] https://www.enterprisedb.com/docs/pgaa/latest/catalog/
- **Couchbase Enterprise Analytics**: `CREATE CATALOG <name> TYPE Iceberg SOURCE <REST|NESSIE|NESSIE_REST|AWS_GLUE|AWS_GLUE_REST|S3_TABLES|BIGLAKE_METASTORE> AT <link>`. For REST it "supports basic auth, Bearer token, or OAuth 2.0". — [P] https://docs.couchbase.com/enterprise-analytics/current/sqlpp/5_ddl_iceberg_catalog.md. Iceberg access is read-only. — [S-snip] https://docs.couchbase.com/enterprise-analytics/current/sqlpp/5_ddl_iceberg.html
- **IOMETE**: a self-hosted lakehouse on Spark, Iceberg and Kubernetes or OpenShift, described as "air-gapped and zero-trust compatible", with its own Iceberg REST catalog. Whether it can attach an external REST catalog is unknown. — [S-snip] https://www.rfp.wiki/vendors/iomete. It is Spark-based, which is out of scope here [I].

#### CedarDB, Yellowbrick, Ocient (brief)
- **CedarDB**: the roadmap shows "Parquet reader ✅", "Parquet writer 🗓️" and "Iceberg support 🗓️", that is, planned and not shipped. — [P] https://cedardb.com/docs/roadmap/. The Parquet read path shipped in v2026-03-03. — [P] https://cedardb.com/blog/release_notes/early_2026/
- **Yellowbrick**: its own site shows no native Iceberg support. — [S-snip, site search]. The 2026 plan, from a post dated 18 Feb 2026, includes v7.4.1 in January, a "generic Kubernetes release" in spring and a "Dell hardware launch with Red Hat OpenShift" mid-year. CEO Neil Carson and engineers "have started Floe", which "brings data warehouse-grade SQL to the lakehouse". — [P] https://yellowbrick.com/blog/yellowbrick-product/yellowbrick-2026-product-momentum-customer-focus-and-whats-next/. Floe "will enter Beta soon", and its "FloeScan" queries "Iceberg and Delta lakehouse tables". Catalogs and deployment options are not stated. — [P] https://floedb.ai/
- **Ocient**: the docs index and the release notes (latest Version 28.0, 6 Oct 2026) contain **zero** "Iceberg" mentions. Its lake ingest is Parquet loading through data pipelines. — [P] https://docs.ocient.com/llms.txt, https://docs.ocient.com/release-notes

#### Cloud-only services (brief): can each attach an external Iceberg REST catalog with vended credentials?

| Service | External REST attach | Vended credentials | Notes |
|---|---|---|---|
| AWS Athena, Redshift, EMR (through Glue catalog federation) | Yes. GA on 24 Nov 2025 for "catalog implementations that support the Iceberg REST specifications". Engines: Redshift, EMR, Athena, Glue, Spark, SageMaker. — [P] https://aws.amazon.com/about-aws/whats-new/2025/11/aws-glue-catalog-federation-remote-apache-iceberg-catalogs | Lake Formation issues "temporary, scope-limited credentials" to engines. — [S-snip] https://aws.amazon.com/blogs/big-data/introducing-catalog-federation-for-apache-iceberg-tables-in-the-aws-glue-data-catalog | OAuth2 or custom token to the remote catalog. — [S-snip, same blog]. Lake Formation supports "trusted identity propagation". — [P, what's-new page]. "Only flat catalog.namespace.table" is supported. — [S-snip] https://docs.lakefs.io/integrations/glue_metastore/. GovCloud added Aug 2026. — [S-snip] https://aws.amazon.com/about-aws/whats-new/2026/08/aws-glue-catalog-federation-iceberg-govcloud/. EMR Spark can be pointed at any REST catalog directly [I]. |
| Google BigQuery / Lakehouse (formerly BigLake) | No generic third-party attach documented. Google's "catalog federation" means routing REST clients **into** BigQuery. "Lakehouse connects Google Cloud to remote external catalogs, such as Databricks Unity Catalog". — [P] https://docs.cloud.google.com/lakehouse/docs/use-catalog-federation | "Storage access delegation (credential vending) isn't supported when routing requests to the BigQuery catalog." — [P, same page] | BigLake was renamed "Lakehouse for Apache Iceberg" in Apr 2026. — [S-snip] https://www.snowflake.com/en/developers/guides/getting-started-with-snowflake-and-bigquery-via-iceberg/ |
| Microsoft Fabric / OneLake | Partly. A Mirrored Catalog "mirrors tables from an external Iceberg REST Catalog (IRC) API-compliant provider" through delegated shortcuts. The source types are only `DremioIcebergCatalog`, `AzureMonitorMirroredCatalog`, `AWSIcebergCatalog`, `MirroredGoogleLakehouseRuntimeCatalog` and `AvevaConnectCatalog`. There is no generic type; "Additional source types may be added over time" (page updated 29 Sep 2026). — [P] https://learn.microsoft.com/en-us/rest/api/fabric/articles/item-management/definitions/mirrored-catalog-definition | Dremio mirroring (preview, Apr 2026) uses credential vending. — [S-snip] https://learn.microsoft.com/en-us/fabric/fundamentals/whats-new | Glue mirroring needs public-internet reachability. — [S-snip] https://learn.microsoft.com/en-us/fabric/mirroring/catalog-mirroring/aws-glue-tutorial |
| Starburst Galaxy | Named REST catalogs only: S3 Tables, Apache Polaris (S3, GCS), Lakekeeper (ADLS), plus Unity. No arbitrary REST catalog is named. — [P] https://docs.starburst.io/starburst-galaxy/working-with-data/create-catalogs/ | Not stated | The navigation lists "AWS PrivateLink for on-prem data source"; otherwise Galaxy's IP ranges must be allow-listed. — [P, same page] |
| MotherDuck | Yes. `CREATE DATABASE ... (TYPE ICEBERG, "secret" ..., endpoint ..., warehouse ...)`, with secrets for OAuth2 client credentials or `TOKEN`. — [P] https://motherduck.com/docs/integrations/file-formats/apache-iceberg.md | `access_delegation_mode` defaults to `'vended_credentials'`. — [P, same page] | Reads support "S3, S3-compatible object storage (including Cloudflare R2), S3 Tables, and GCS". Queries run on MotherDuck's cloud compute, so nuna and Silo or Ceph would have to be internet-reachable [I]. Writes use merge-on-read. — [P, same page] |

### Inferences
- **Fully on-prem against nuna, best fit first**:
  1. Starburst Enterprise, or the Dell Data Lakehouse build of it.
  2. Denodo Embedded MPP, which is PrestoDB underneath, so Presto's REST client limits apply.
  3. Firebolt self-managed: v1/v2 read-only, and nuna would have to issue client credentials, since static tokens are not supported for generic REST.
  4. Vertica: no delete files.
  5. EDB PGAA and Couchbase Enterprise Analytics: niche, Postgres or JSON analytics.
  6. Exasol Lakehouse Turbo: a cache, and it needs a Linux cluster with Podman.
  7. No longer self-hostable without vendor egress: e6data, because its control plane is SaaS.
- **Unknown for every on-prem product, Starburst included**: whether the engine uses the vended `s3.endpoint` and `s3.path-style-access`. EDB PGAA only shows an `endpoint` in its non-vended `store_config`. Firebolt only documents S3-compatible endpoints for its own storage. This is the main item to test with nuna and Silo/Ceph before buying anything.
- **Oracle and Teradata** do not work with nuna's model on-prem today. Oracle has no credential vending, fails on delete files and does not certify generic REST catalogs. Teradata on-prem supports Hive only.
- **Cloudera** is mainly a competing catalog (its HMS-embedded REST server) rather than a client of nuna.
- **IBM watsonx.data** probably can register nuna through Presto's REST connector, since its SaaS docs show this for other REST catalogs. On-prem parity, vended credentials and nested namespaces are unconfirmed.

### Gaps
- No product documents honouring a vended `s3.endpoint` or `s3.path-style-access`, or says whether the endpoint must be repeated in engine config. This covers SEP, Firebolt, Exasol, Vertica, Denodo, PGAA and Couchbase. It needs a test against nuna.
- Vertica 26.2 and 26.3 docs could not be read (Rocket's JavaScript portal), so any REST, vended-credential or delete-file changes after 26.1 are unknown. Vertica Community Edition status under Rocket was not checked.
- IBM watsonx.data software docs (ibm.com/docs) returned 403. The on-prem external-catalog procedure, vended credentials, v3, footprint and licensing are all unconfirmed.
- Starburst air-gapped install procedure: only "use your own Docker registry" was found. SEP list pricing is unverified.
- e6data pricing, funding and health after Aug 2025: not found. Exasol, Ocient and e6data company health (layoffs, funding): not found.
- Dell Data Lakehouse 2026 hardware node specs and release notes: not found.
- Cloudera on-prem client support for a third-party REST catalog (Impala, Hive, Spark): not found. Only the cloud preview is documented.
- Firebolt Core: when it was first released, and whether engine source is published (the docs conflict).

## Key question 2: Which add identity pass-through, fine-grained access control or operations tooling that would justify paying over open-source Trino or DuckDB?

### Takeaway
For nuna the deciding feature is end-user identity reaching the catalog, and only Starburst Enterprise documents it. It forwards the logged-in user's OAuth2 access token to an Iceberg REST catalog (`OAUTH2_PASSTHROUGH`, documented since 477-e, Nov 2025), with per-user `session=USER`. Starburst adds built-in RBAC with masks, filters and audit, Ranger and Immuta integration, Helm charts and a licence-gated cache. Firebolt adds a real Kubernetes operator, e6data and Exasol add governance or caching, and AWS Lake Formation adds trusted identity propagation (cloud only). All of these call the catalog with one service identity.

### Cited Findings
- **Starburst identity**:
  - With `DELEGATED-OAUTH2`, "the received token is passed through SEP and the connector to the underlying data source"; there is "no token refresh mechanism", so token lifetime must exceed query runtime. — [P] https://docs.starburst.io/latest/security/oauth2-passthrough.html
  - Catalog side: `iceberg.rest-catalog.security = OAUTH2_PASSTHROUGH`, plus RFC 8693-style token exchange (`oauth2.token-exchange.enabled`), documented for Databricks only. — [P] https://docs.starburst.io/latest/object-storage/metastores.html
  - Per-query "dynamic configuration passthrough" of REST URI, secret and token through JDBC extra credentials. — [P] same page
  - Other SEP pass-through options include password pass-through and Kerberos credential pass-through. — [P] https://docs.starburst.io/latest/security.html
- **Starburst access control**: "SEP provides a built-in, role-based access control system", with roles, privileges, "masks and filters" and an "audit log". It also integrates with Apache Ranger and Immuta, and supports user impersonation. — [P] https://docs.starburst.io/latest/security.html
- **Starburst operations**: Helm charts for SEP, HMS, Ranger and the cache service, and a Starburst Harbor registry. — [P] https://docs.starburst.io/latest/k8s.html. Warp Speed caching is licence-gated. — [P] https://docs.starburst.io/latest/license-requirements.html
- **Dell Data Lakehouse**: the same SEP pass-through, sold as a Dell appliance with Dell support. — [P] https://dell.starburst.io/latest/object-storage/metastores.html
- **Firebolt operations**:
  - A Kubernetes operator with CRDs (`FireboltInstance`, `FireboltEngine`, `FireboltEngineClass`, `FireboltEnginePreset`) and an Envoy gateway for zero-downtime engine cutovers.
  - Auto-stop and wake-up, Prometheus metrics, and a `kubectl firebolt` plugin.
  — [P] https://docs.firebolt.io/llms.txt (Self-Managed section)
  - In 4.32, GRANT and REVOKE on built-in roles became restricted. — [P] https://docs.firebolt.io/release-notes.md
  - No catalog identity pass-through is documented. — [P, absence]
- **e6data governance**: an optional "Governance" setting per REST catalog, "using Ranger, OPA, Unity, or Lake Formation". — [P] https://docs.e6data.com/query/catalogs/iceberg-rest-catalog.md. Column masking and row filtering arrived on 30 Mar 2024. — [P] https://docs.e6data.com/release-notes.md
- **Exasol OAuth knobs**: `oauthAudience`, `oauthResource` and `tokenExchangeEnabled` exist, but they belong to one service-level configuration. — [P] https://docs.exasol.com/db/latest/connect_exasol/lakehouse_turbo/dlhc_iceberg_rest_api_as_app.htm
- **Cloudera** places identity and FGAC on the **catalog** side (Knox tokens, Ranger policies, IDBroker vending), not in a client engine for third-party catalogs. — [P] https://docs.cloudera.com/cdp-private-cloud-base/7.3.2/overview/topics/cr-ds-cloudera-iceberg-rest-catalog.html
- **IBM watsonx.data** v2.3 advertises "Enhanced access control propagation". — [P] https://www.ibm.com/new/announcements/watsonx-data-v2-3-new-innovations-powering-enterprise-ai-at-scale
- **AWS (cloud only)**: Lake Formation provides "fine-grained access controls, cross-account sharing, and trusted identity propagation" for federated remote Iceberg catalogs. — [P] https://aws.amazon.com/about-aws/whats-new/2025/11/aws-glue-catalog-federation-remote-apache-iceberg-catalogs
- **MotherDuck (paid DuckDB)**: persisted REST catalogs with secrets, writes and vended credentials, but only on MotherDuck cloud compute. — [P] https://motherduck.com/docs/integrations/file-formats/apache-iceberg.md

### Inferences
- **Starburst is the only paid option that would let nuna enforce per-user authorization on reads through Trino-family SQL**:
  - nuna must accept access tokens whose audience is SEP's, or SEP must exchange them. Generic exchange is documented only for Databricks.
  - The tokens must outlive the longest query.
  - `session=USER` disables the REST metadata cache, so expect more catalog load.
- **For DuckDB users**: MotherDuck is not a self-hosted upgrade path for an on-prem nuna. Its compute is in MotherDuck's cloud.
- **If identity pass-through is not needed**: Firebolt's operator, which is Apache-2.0, is the most complete Kubernetes-native operations tooling. However, Firebolt's Iceberg support (v1/v2 read, no DML, no static token) is weaker than open-source Trino's, and that is assessed elsewhere.

### Gaps
- Whether SEP's `OAUTH2_PASSTHROUGH` works together with `vended-credentials-enabled` and nested namespaces against a non-Databricks catalog. It is untested and undocumented as a combination.
- Whether Starburst's built-in access control masks and filters apply to Iceberg REST catalogs when pass-through is on, and how they combine with catalog-side authorization: not found.
- No list or discounted pricing found for SEP, Dell Data Lakehouse, e6data, Exasol or watsonx.data.

## Key question 3: What changed in 2025 and 2026 (acquisitions, new free editions, end of life)?

### Takeaway
2026 brought two ownership changes among the engines that matter here. SAP bought Dremio (announced 4 May, closed 6 Jul 2026), and Rocket Software bought Vertica from OpenText (announced 2 Feb, closed 11 May 2026). New self-hosted or free options appeared: Firebolt's free self-managed edition under ELv2, with an operator and Helm chart; Exasol Personal plus Lakehouse Turbo on 2026.1; Dremio's free Community Edition; and Oracle 26ai's on-prem Iceberg support. Starburst added end-user pass-through to REST catalogs. The cloud providers built federation to remote REST catalogs, led by AWS. I found no end-of-life notices for these engines.

### Cited Findings
- **SAP and Dremio**: announced 4 May 2026, completed 6 Jul 2026, terms undisclosed. — [P] https://news.sap.com/2026/05/sap-to-acquire-dremio-unify-sap-and-non-sap-data-power-agentic-ai/, https://news.sap.com/2026/07/sap-completes-dremio-acquisition/. SAP made no statement on Dremio's self-managed or Community editions. — [P, absence]. The Community Edition was de-emphasised (Jan 2026) and the free trial was dropped (Mar 2026). — [S] https://pricingsaas.com/companies/dremio/diffs/2025Q4, https://pricingsaas.com/companies/dremio/diffs/2026Q1. The OSS repo's last push was 2025-09-26. — [P] https://api.github.com/repos/dremio/dremio-oss
- **Rocket Software and Vertica**: announced 2 Feb 2026 and closed 11 May 2026, for a reported $150M. — [S-snip] https://www.nasdaq.com/press-release/rocket-software-acquire-vertica-analytics-database-platform-opentext-2026-02-02, https://www.dbta.com/Editorial/News-Flashes/Rocket-Software-Finalizes-Acquisition-of-Vertica-Strengthening-Analytics-AI-and-Data-Modernization-Across-Hybrid-Cloud-174739.aspx, https://idm.net.au/index.php/node/15497. The docs moved to docs.rocketsoftware.com. — [P, observed]
- **Starburst**:
  - REST `OAUTH2_PASSTHROUGH` arrived in 477-e (25 Nov 2025), and Unity token exchange in 479-e (27 Feb 2026). — [P] versioned docs above
  - SEP now supports v3 (DVs, row lineage, variant). — [P] https://docs.starburst.io/latest/connector/iceberg.html
  - $100M ARR (Feb 2026), the Enterprise Intelligence Platform (28 May 2026) and the Torin GPU engine. — [S-snip] links above
- **Firebolt**: the self-managed edition ships with an Apache-2.0 operator and Helm chart, and Core is under ELv2. Version 4.32 (mid-2026) added `CREATE ICEBERG DATABASE` and vended-credential fallback. — [P] https://docs.firebolt.io/release-notes.md, https://raw.githubusercontent.com/firebolt-db/firebolt-core/main/LICENSE.md
- **Exasol**: 2026.1 (18 May 2026) brought Lakehouse Turbo on-prem as-application with Iceberg REST. Exasol Personal is a free single-user edition, and its 1.4.0 release (24 Apr 2026) added Azure, Exoscale and "deploy-anywhere" support. — [P] https://community.exasol.com/t/whats-new-in-exasol-2026-1/296 (the related-topics title), https://docs.exasol.com/db/latest/get_started/exasol_personal.htm
- **Oracle**:
  - From 23.26.2, 26ai can install Data Studio and DBMS_CATALOG on-prem. — [P] https://docs.oracle.com/en/database/oracle/oracle-database/26/arpls/dbms_catalog.html
  - `CREATE ICEBERG TABLE` with v2-only writes. — [P] https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/create-iceberg-table.html
  - Unity and Polaris REST catalogs on ADB-S from 7 Oct 2025. — [P] https://docs.oracle.com/iaas/releasenotes/autonomous-database-serverless/2025-10-broader-and-enhanced-iceberg-rest-catalog-support.htm
- **Teradata**: on-prem OTF (Hive only) on VantageCore VMware, May 2026. — [P] https://docs.teradata.com/r/VMware/Teradata-VantageCore-VMware-Quick-Start/VantageCore-VMware-Quick-Start-Requirements/Open-Table-Format-OTF-Support. It settled with SAP for $480M on 19 Feb 2026. — [S-snip] https://www.boardroomalpha.com/sec/tdc-8-k-2026-02-23-0001628280-26-010578
- **Cloudera**: Base on-prem 7.3.2 GA (Mar 2026) includes the HMS-embedded REST catalog with vending. — [S-snip] https://docs.cloudera.com/cdp-private-cloud/latest/release-summaries/topics/announcement-202603-732.html. The Impala REST client is a cloud-only preview (31 Mar 2026). — [P] https://docs.cloudera.com/data-warehouse/cloud/release-notes/topics/dw-public-cloud-tp-r44.html. Cloudera agreed to acquire Taikun, after Verta (May 2024) and Octopai (Nov 2024). — [S-snip] https://www.channelinsider.com/channel-business/mergers-and-acquisitions/cloudera-data-ai-solutions-multi-cloud/
- **IBM**: watsonx.data v2.3 (10 Dec 2025) brought GA on IBM Power for on-prem, plus GovCloud. — [P] https://www.ibm.com/new/announcements/watsonx-data-v2-3-new-innovations-powering-enterprise-ai-at-scale
- **Yellowbrick**: CEO Neil Carson spun out Floe, a pre-beta lakehouse engine that reads Iceberg and Delta. Yellowbrick itself planned generic Kubernetes and Dell/OpenShift hardware launches in 2026. — [P] https://yellowbrick.com/blog/yellowbrick-product/yellowbrick-2026-product-momentum-customer-focus-and-whats-next/, https://floedb.ai/
- **CedarDB**: Parquet reads shipped (v2026-03-03). Iceberg is still on the roadmap. — [P] https://cedardb.com/blog/release_notes/early_2026/, https://cedardb.com/docs/roadmap/
- **HPE**: Polaris 1.3.0.0 in Data Fabric adds federation to external REST catalogs (build 2604). — [S-snip] https://docs.ezmeral.hpe.com/datafabric-customer-managed/80/Ranger/PolarisRN-1.3.0.0-DEP-1010-2604.html
- **Cloud**:
  - AWS Glue catalog federation to remote REST catalogs: GA 24 Nov 2025, GovCloud Aug 2026. — [P] https://aws.amazon.com/about-aws/whats-new/2025/11/aws-glue-catalog-federation-remote-apache-iceberg-catalogs; [S-snip] https://aws.amazon.com/about-aws/whats-new/2026/08/aws-glue-catalog-federation-iceberg-govcloud/
  - AWS Clean Rooms gained remote REST catalogs in Feb 2026. — [S-snip] https://aws.amazon.com/about-aws/whats-new/2026/02/aws-clean-rooms-remote-iceberg-catalogs
  - Fabric Mirrored Catalog source types (Dremio, Glue, Google, AVEVA). — [P] https://learn.microsoft.com/en-us/rest/api/fabric/articles/item-management/definitions/mirrored-catalog-definition
  - Google renamed BigLake to Lakehouse in Apr 2026. — [S-snip] https://www.snowflake.com/en/developers/guides/getting-started-with-snowflake-and-bigquery-via-iceberg/
  - MotherDuck added persisted Iceberg REST catalogs (DuckDB 1.5.2+). — [P] https://motherduck.com/docs/integrations/file-formats/apache-iceberg.md; the version requirement is [S-snip]

### Inferences
- **Dremio self-managed**: under SAP its future is undefined in the public record. The open-source code drop appears to have stopped in Sept 2025, and the free trial and Community Edition were de-emphasised before the deal. Treat Dremio Community as a risky long-term dependency for an on-prem nuna deployment.
- **Vertica under Rocket** (a mainframe and modernisation vendor) is likely to stay on-prem friendly. Its Iceberg reader is still basic as of 26.1: no delete files, no v3.
- **Free self-hosted commercial engines that can sit on nuna**:
  - Firebolt Core (ELv2, unlimited) is the only new one that is truly unlimited.
  - Exasol Personal is single-user.
  - Dremio Community is free but under new ownership.

### Gaps
- No end-of-life or deprecation notices were found for any product reviewed. That absence is from searches, not from an exhaustive check of each lifecycle page.
- Firebolt Core's first release date was not verified. The "Introducing Firebolt Core" blog was not opened.
- No 2025 or 2026 layoff data was found for Exasol, e6data, Ocient, Yellowbrick or Firebolt.

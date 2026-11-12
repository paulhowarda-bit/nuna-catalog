# PuppyGraph and other graph, search and specialized engines over Iceberg (as of 2026-11-12)

Evidence tags used on every line:
- **[P]** read in a primary source. "(verbatim)" means I read the raw text with curl. "(WebFetch)" means I read it through WebFetch's summariser, so the wording is paraphrased.
- **[S]** secondary: a search-engine summary, a vendor blog or a third party, not opened or not primary.
- **[I]** my inference.

Out of scope, mentioned only as comparison points: Dremio, Trino, DuckDB, StarRocks, Doris, ClickHouse, Spark, Flink, PyIceberg, SingleStore, Snowflake, Databricks and Kafka Connect.

---

## Summary: does each engine work with keel, and how hard is it to run?

### Takeaway
PuppyGraph is the only engine here that queries Iceberg tables in place through a generic Iceberg REST catalog with OAuth2 client credentials and an external token endpoint. Its gaps for keel:
- It documents that "vended credentials do not include a storage endpoint", so the Silo/Ceph endpoint has to be repeated in its own config (the same failure class as StarRocks 4.1.7).
- It has no end-user token passthrough to an Iceberg catalog.
- It does not document v2 or v3 delete support, v3 types or nested namespaces.
- It is proprietary and heavy (default Helm cluster about 50 vCPU and 292 GiB).

None of the other graph, search or vector engines meets the REST, vended-credential and in-place requirements together:
- TigerGraph and NebulaGraph copy the data in.
- LadybugDB and Milvus read Iceberg by file path, not by catalog.
- Elasticsearch reads only Parquet/CSV/NDJSON files today.
- LanceDB uses an Iceberg REST catalog only to register Lance-format tables.

### Cited Findings (matrix; details and sources in the sections below)

| Engine | In place? | Iceberg REST + OAuth2 | Vended creds + custom endpoint | Nested namespaces | v2/v3 read; writes | K8s / footprint | Licence; latest |
|---|---|---|---|---|---|---|---|
| PuppyGraph | Yes; optional local copies and caches | Yes: client credentials + `oauthServerUri`; static token not documented; no user passthrough to Iceberg | Vended yes, but the endpoint must be set in PuppyGraph; path-style in that mode not documented | Not documented (only OSI import) | Not documented; no Iceberg writes | Helm chart; default 7 pods / ~50 vCPU / 292 GiB | Proprietary; free Developer edition (1 node, Docker); 1.18.0, 2026-11-04 |
| TigerGraph 4.3 loader | No (loads into TigerGraph) | REST only; OAuth2 keys incl. static token, but the doc also says REST auth is unsupported in 4.3 | Not mentioned; static S3 keys; `aws.s3.endpoint` + path-style key | `namespace.table` only | Not mentioned | Not researched | Commercial |
| GraphLake (TigerGraph research) | Yes | Unknown | Unknown | Unknown | Unknown | Research paper only | arXiv 2026-03-04 |
| Neo4j | No evidence of in-place Iceberg | — | — | — | — | — | — |
| NebulaGraph | No (SeaTunnel copy at best) | — | — | — | — | — | — |
| Kuzu | Archived 2025-10-10 | — | — | — | — | — | — |
| LadybugDB (Kuzu fork) | Scan by file path, or copy | No REST catalog | Static keys; `s3_endpoint` and `s3_url_style=path` | n/a (paths) | Not documented; no write; no STRUCT | Embedded library, no server | v0.22.3, 2026-11-07 |
| Spark GraphFrames | Via Spark | Same as Spark (assessed elsewhere) | Same as Spark | Same as Spark | Same as Spark | Spark | v0.12.3, 2026-10-05 |
| Milvus 3.0 External Collection | Rows stay in the lake; Milvus builds indexes | No catalog: `metadata.json` path + `snapshot_id` | No vended creds; static/IAM keys | n/a | Not documented; read-only | Not researched here | v3.0.6, 2026-11-11 |
| Elasticsearch ES\|QL Data Federation | Yes, but files only | No Iceberg support released (in development) | `s3` type only | — | Parquet/NDJSON/CSV/TSV only | — | Experimental in 9.5; Enterprise subscription |
| OpenSearch | Managed AWS only; Spark tables in Glue | No evidence | — | — | — | — | — |
| LanceDB / Lance Namespace | Not for Iceberg data | Registers Lance tables in an IRC as placeholders | — | — | — | — | — |
| Vespa | No evidence found | — | — | — | — | — | — |
| StarTree (commercial Pinot) | Yes (External Tables) | Glue, Unity, Polaris/Open Catalog (OAuth2), Hive; generic REST not verified | Not verified | Not verified | v2 eq/pos deletes + v3 DVs (preview, 0.16.0+) | SaaS/BYOC | Commercial |

### Inferences
- [I] For keel, PuppyGraph is the only candidate worth a hands-on test, and the test should start with three things:
  1. vended credentials against Silo/Ceph with a path-style-only endpoint;
  2. a table in a 4-level namespace;
  3. a v2 table with equality deletes and a v3 table with deletion vectors.
- [I] Every vector or search engine here would need keel to hand out the table's `metadata.json` location (Milvus, LadybugDB) or would need a copy. None uses keel's REST protocol to read Iceberg data.

### Gaps
- No independent (non-vendor) evaluation of any of these engines against a self-hosted IRC with vended credentials was found.

---

## PuppyGraph: which catalogs, REST auth, and end-user identity passthrough

### Takeaway
PuppyGraph supports a generic Iceberg REST catalog with OAuth2 client credentials (`credential` = `client_id:client_secret`, `scope`, `oauthServerUri` pointing at an external IdP token endpoint) and AWS SigV4. It does not document a static bearer-token field for Iceberg REST. It cannot forward the signed-in user's identity to an Iceberg catalog: identity propagation exists only for Snowflake and Elasticsearch.

### Cited Findings
- [P verbatim] Metastore types on the Iceberg page:
  - Iceberg REST, with presets or notes for Nessie (≥0.95.0), Polaris/Snowflake Open Catalog, Snowflake Horizon, Tabular, Databricks Unity (REST), Google Cloud Lakehouse (BigLake REST) and Microsoft OneLake;
  - AWS Glue;
  - Amazon S3 Tables;
  - Hive Metastore.
  — [Connecting to Iceberg](https://docs.puppygraph.com/connecting/connecting-to-iceberg/)
- [P verbatim] Generic REST fields are `RestUri`, `Warehouse`, `Security` ("Security authentication mode of the REST catalog"), `Credential` ("The Oauth2 client authentication credential") and `Scope` ("Authorization scope for client credentials or token exchange"). — [Connecting to Iceberg](https://docs.puppygraph.com/connecting/connecting-to-iceberg/)
- [P verbatim] Schema-file keys from worked examples:
  - OneLake: `"security": "oauth2"`, `"credential": "<client_id>:<client_secret>"`, `"scope": "https://storage.azure.com/.default"`, `"oauthServerUri": "https://login.microsoftonline.com/<tenant_id>/oauth2/v2.0/token"`. This is exactly the client-credentials-against-an-external-IdP pattern keel needs.
  - Polaris: `"credential": "root:s3cr3t"`, `"oauthServerUri": "http://polaris:8181/api/catalog/v1/oauth/tokens"`.
  — [llms-full.txt, "Querying OneLake Data as a Graph" and "Querying Polaris Data as a Graph"](https://docs.puppygraph.com/llms-full.txt); [Polaris tutorial](https://docs.puppygraph.com/getting-started/querying-polaris-data-as-a-graph/)
- [P verbatim] For Snowflake Horizon, a programmatic access token goes in `credential` with `"authType": "oauth2"` and an `oauthServerUri`. The docs warn: "Do not use a key-pair JWT as the credential for a lasting connection… it expires one hour after it is created… and PuppyGraph does not create a new one." — [Connecting to Iceberg](https://docs.puppygraph.com/connecting/connecting-to-iceberg/)
- [P verbatim] Release 1.0.0 (2026-06-29) added "AWS SigV4 authentication for Iceberg REST catalogs". 1.11.0 (2026-09-16) fixed "an Iceberg REST catalog using OAuth2 becoming unqueryable a few hours after startup". — [Releases](https://docs.puppygraph.com/releases/)
- [P verbatim] Release 1.11.0 added "authentication with a token from your own identity provider", and "propagating the signed-in user's identity to Snowflake and Elasticsearch with the new catalog-level identityPropagation setting". No Iceberg catalog is named. — [Releases](https://docs.puppygraph.com/releases/)
- [P verbatim] Snowflake impersonation works through "one shared Snowflake service account while asking Snowflake to evaluate queries for the current SSO user": a proxy-user stored procedure, or the user's verified JWT claims set as Snowflake session variables. — [Snowflake SSO impersonation tutorial](https://docs.puppygraph.com/getting-started/querying-snowflake-with-sso-impersonation/)
- [P verbatim] SSO login is OIDC Authorization Code with PKCE. `SSO_CLAIM_AS_USER_ID` becomes the user identity, and a groups claim drives RBAC. PuppyGraph "does not auto-discover endpoints from the OIDC discovery document". Bolt and Gremlin accept bearer-token login checked against trusted issuers' JWKS. — [SSO](https://docs.puppygraph.com/security/sso/)
- [P verbatim] Row-level security comes from entitlement tables inside PuppyGraph and is applied at every traversal hop. It fills in "the authenticated PuppyGraph username, not the database session user". — [llms-full.txt, Row-Level Security](https://docs.puppygraph.com/llms-full.txt); [RLS page](https://docs.puppygraph.com/security/row-level-security/)

### Inferences
- [I] Keel's client-credentials mode works: set `security=oauth2`, `credential=<id>:<secret>`, `scope`, and `oauthServerUri=<IdP token endpoint>`.
- [I] Keel's static-token mode is unsupported as documented. There is no `token` key. Passing a pre-minted JWT as `credential` would make it be used as a client secret, not as a bearer token.
- [I] Per-user catalog authorisation in keel cannot be used. Every PuppyGraph user reaches keel as one service principal, so access control must be rebuilt in PuppyGraph RBAC and RLS.

### Gaps
- The allowed values of `Security` beyond `oauth2` (for example `none` or `sigv4`) are not listed.
- I found no documented `token`, `token-exchange` or `X-Iceberg-Access-Delegation` setting.
- Whether `identityPropagation` will extend to Iceberg REST catalogs was not found.

---

## PuppyGraph: vended credentials and custom S3 endpoints (MinIO/Ceph/Silo)

### Takeaway
PuppyGraph accepts vended credentials ("Get from metastore"), but its docs state that vended credentials "do not include a storage endpoint". For a non-AWS endpoint you select "Amazon S3", set the auth type to "Credentials vended by the catalog", and repeat Region and Endpoint in PuppyGraph's own config. That mode does not document path-style access. Static-key S3-compatible storage (MinIO/RustFS) does support `endpoint` plus `enablePathStyleAccess`.

### Cited Findings
- [P verbatim] "Get from metastore" applies to "Iceberg REST Catalog with credential vending". Then: "Vended credentials do not include a storage endpoint. If the catalog vends credentials for Amazon S3 but the bucket is reachable only through a private endpoint (for example AWS PrivateLink), select Amazon S3 instead of Get from metastore, set S3 Authentication Type to Credentials vended by the catalog, and set Region and Endpoint for the private endpoint." — [Connecting to Iceberg](https://docs.puppygraph.com/connecting/connecting-to-iceberg/)
- [P verbatim] The S3-compatible storage type ("e.g. RustFS or MinIO AIStor") takes Endpoint, Access key, Secret key, Enable SSL and Enable path style access. Example keys: `"endpoint": "http://127.0.0.1:9000"`, `"enablePathStyleAccess": "true"`. — [Connecting to Iceberg](https://docs.puppygraph.com/connecting/connecting-to-iceberg/)
- [P verbatim] Vended-credential fixes in the release notes:
  - 1.16.0 (2026-10-21): fixed "ExpiredToken" errors by retrying "with renewed credentials… for Iceberg REST catalogs that vend credentials".
  - 1.17.0 (2026-10-28): "Further fix queries on Iceberg REST catalogs that vend credentials failing when a credential expires".
  - 1.18.0 (2026-11-04): fixed vended credentials on GCS.
  - 1.7.0: fixed "Iceberg catalogs that reach S3 through an interface VPC endpoint".
  — [Releases](https://docs.puppygraph.com/releases/)
- [P verbatim] For an Iceberg REST catalog, "cached table versions are kept for at most 3000 seconds even when the TTL is longer". — [Connecting to Iceberg](https://docs.puppygraph.com/connecting/connecting-to-iceberg/)
- [P verbatim] Query-result export works to "any S3-compatible storage service, with or without path-style access" (1.12.0). — [Releases](https://docs.puppygraph.com/releases/)

### Inferences
- [I] PuppyGraph ignores the `s3.endpoint` and `s3.path-style-access` that keel vends. This is the StarRocks 4.1.7 behaviour, and it fits the evidence below that PuppyGraph's SQL layer comes from StarRocks or Doris.
- [I] For Silo or Ceph RGW, the endpoint must be repeated in PuppyGraph. If the store needs path-style addressing (typical for Ceph RGW without wildcard DNS), the vended-credentials-plus-endpoint mode may fail. Test this before anything else.
- [I] The 3000 s cap on cached table versions for REST looks designed to stay under a typical 1-hour vended-credential lifetime.
- [I] Evidence that PuppyGraph's engine comes from StarRocks or Doris (not stated by the vendor):
  - a `PRIORITY_IP_CIDR` env var (like `priority_networks`);
  - leader/follower metadata election;
  - an AVX2 and `ulimit -n 65536` requirement;
  - `DUPLICATE_KEY`/`PRIMARY_KEY`/`UNIQUE_KEY` local-table models;
  - REST keys `security`/`credential`/`scope`/`oauthServerUri`, which mirror StarRocks `iceberg.catalog.*` properties.
  Sources: [Cluster deployment](https://docs.puppygraph.com/installation/cluster-deployment/), [Installation](https://docs.puppygraph.com/installation/), [llms-full.txt local tables](https://docs.puppygraph.com/llms-full.txt).

### Gaps
- No "path-style" option is documented for the "Credentials vended by the catalog" mode.
- It was not checked whether the S3-compatible type can be combined with vended credentials.
- No public statement on PuppyGraph's engine lineage was found.

---

## PuppyGraph: Iceberg format versions, delete files, v3 features, nested namespaces, writes, in-place vs copy

### Takeaway
PuppyGraph queries Iceberg in place by default, with metadata and data caches and optional "local tables" that copy data into its compute nodes. Its docs and release notes do not state which format versions or delete types it reads. Its type map has no v3 types. It does not write Iceberg.

### Cited Findings
- [P verbatim] The metadata cache holds "each table's current version (its snapshot and schema), partitions, and the lists of data and delete files". The default TTL is 86400 s and the refresh interval is 60 s. Background refresh (1.15.0+) is off by default. "This is not read-after-write consistency." — [Connecting to Iceberg](https://docs.puppygraph.com/connecting/connecting-to-iceberg/)
- [P verbatim] Type mapping lists boolean, int, long, float, double, decimal, string, date, time, timestamp→DateTime, list→Array and struct→Struct. "Unlisted types require contacting PuppyGraph." Map support was added in a 0.x release. Variant, timestamp_ns/tz_ns, geometry/geography, uuid, binary and fixed are not listed. — [Connecting to Iceberg](https://docs.puppygraph.com/connecting/connecting-to-iceberg/); [Releases](https://docs.puppygraph.com/releases/)
- [P verbatim] I grepped the full docs and all release notes (back to 0.x, 2025) for "format version", "v3", "deletion vector", "equality delete" and "position delete". The only hits were Delta Lake deletion vectors and Iceberg "delete files" in the cache text. — [llms-full.txt](https://docs.puppygraph.com/llms-full.txt); [Releases](https://docs.puppygraph.com/releases/)
- [P verbatim] The schema `schema` field is "Schema (database) name within the catalog". Release 1.4.0 (2026-07-31) added "Support nested Iceberg namespaces when importing Open Semantic Interchange (OSI) semantic models". — [llms-full.txt](https://docs.puppygraph.com/llms-full.txt); [Releases](https://docs.puppygraph.com/releases/)
- [P verbatim] Local tables:
  - They are defined in `localTable[]`, loaded via "Enable Local Replication", partitioned, and use key types `DUPLICATE_KEY`, `PRIMARY_KEY` and `UNIQUE_KEY`.
  - Compute nodes keep them on PVs with replication factor `DATAACCESS_DATA_REPLICATIONNUM` (default 3).
  - There is also a separate adaptive data cache.
  — [llms-full.txt, Local tables](https://docs.puppygraph.com/llms-full.txt); [Cluster deployment](https://docs.puppygraph.com/installation/cluster-deployment/)
- [P verbatim] llms.txt states: "There is no CSV or Parquet catalog". It also says PuppyGraph "queries the data already in your databases, warehouses and data lakes as a graph, without ETL". — [llms.txt](https://docs.puppygraph.com/llms.txt)
- [P verbatim] Since 1.10.0, query-result exports go only to admin-registered locations. No Iceberg write path is documented. — [Releases](https://docs.puppygraph.com/releases/)

### Inferences
- [I] Caching delete-file lists suggests v2 merge-on-read deletes are applied at read time. That is unconfirmed.
- [I] If the engine is StarRocks/Doris-derived, its v2 position and equality delete and v3 support would track that lineage. Treat it as unknown until tested.
- [I] Nested namespaces probably work in the `schema` field as a dotted path, given the OSI import note. This is unverified.
- [I] PuppyGraph is a read-only consumer of keel.

### Gaps
- Nothing found on: deletion vectors, row lineage, variant, nanosecond timestamps, geometry/geography, the maximum format version, or nested-namespace addressing in `externalDataSource`.

---

## PuppyGraph: what it is for, query languages, lineage/traversal, performance claims and limits

### Takeaway
PuppyGraph maps existing tables to nodes and edges through a JSON schema and serves openCypher (Bolt) and Gremlin. It supports multi-hop, variable-length and shortest-path traversals plus graph algorithms. Lineage or relationship traversal over Iceberg tables works when the relationships are stored as rows (edge tables). The performance claims are vendor-only. A TigerGraph-affiliated paper claims to beat it.

### Cited Findings
- [P verbatim] Schema: top-level `catalog`, `node` and `edge` arrays. An edge has `fromNodeLabel`, `toNodeLabel`, `fromKey[]` and `toKey[]`. Ports are 8081 (Web UI/REST), 8182 (Gremlin) and 7687 (Bolt/Cypher). — [llms.txt](https://docs.puppygraph.com/llms.txt)
- [P verbatim] Graph algorithm pages exist for PageRank, WCC, connected components, label propagation, Louvain and Leiden. There is `CALL algo.paral.pagerank(...)`. Cypher `shortestPath()` and `allShortestPaths()` and variable-length paths are supported (1.11.0 fixes). PuppyGraph AI does natural-language querying (`AI_ENABLED`). — [Graph algorithms](https://docs.puppygraph.com/graph-algorithms/); [Releases](https://docs.puppygraph.com/releases/); [llms.txt](https://docs.puppygraph.com/llms.txt)
- [P verbatim, vendor claim] Claims include: "transform existing relational data stores into a unified graph model in under 10 minutes", "Capable of scaling with petabyte…", and "handling complex multi-hop queries like 10-hop neighbors in seconds". — [llms-full.txt intro](https://docs.puppygraph.com/llms-full.txt)
- [S] PuppyGraph's own flight-network benchmark (12.3M records) claims it beat Neo4j Community on all four query patterns, reading from ClickHouse or Iceberg. This is from a search summary. — [PuppyGraph benchmarks](https://www.puppygraph.com/benchmarks)
- [P WebFetch] *GraphLake: A Purpose-Built Graph Compute Engine for Lakehouse* (Liu, Chen, Qin, Wu, Wang; arXiv 2026-03-04) is built on TigerGraph. It reports that it "significantly outperforms PuppyGraph" in startup and query time. No numbers are given in the abstract. — [arXiv 2603.03705](https://arxiv.org/abs/2603.03705)
- [P verbatim] Limits seen in release notes:
  - 1.15.0 introduced a breaking edge `STRICT`/`TRUST` endpoint-integrity mode; 1.15.1 reverted the default to `TRUST`. Under `TRUST`, `count(*)` can count edges whose endpoints are missing.
  - Repeated fixes for queries "using up the server's memory" (1.18.0, 1.15.0).
  - Table freshness lags the catalog by up to the refresh interval, and up to 3000 s for REST unless background refresh is on.
  — [Releases](https://docs.puppygraph.com/releases/); [Connecting to Iceberg](https://docs.puppygraph.com/connecting/connecting-to-iceberg/)

### Inferences
- [I] To traverse lineage, keel or a pipeline would need to publish lineage or relationship data as Iceberg tables (for example `dataset` nodes and `derives_from` edges). PuppyGraph does not graph the catalog's own metadata.
- [I] Performance depends heavily on caches or local tables. Cold in-place traversals over object storage will be slower than the headline claims.

### Gaps
- No independent benchmark with numbers.
- No published hop or depth limits or result-size limits.

---

## PuppyGraph: deployment, footprint, operations, air-gap

### Takeaway
PuppyGraph is one ~3.4 GB container image (amd64/arm64) deployed with Docker or a Helm chart.
- **Default cluster:** 1 controlplane, 3 leaders and 3 compute StatefulSets, all with persistent volumes. Requests total about 50 vCPU and 292 GiB RAM.
- **Single-node Helm mode:** 4 vCPU and 16 GiB by default, 2 vCPU and 8 GiB minimum.
- **Upgrades:** ordered by role, and the vendor asks you to contact support first.

### Cited Findings
- [P verbatim] Helm repo `https://puppygraph.github.io/puppygraph-helm-chart/`. Image `docker.io/puppygraph/puppygraph`, with `imagePullSecret` and per-role `image.repository` overrides. Modes are `cluster` (default) and `single-node`, and a release cannot switch modes on `helm upgrade`. — [Helm chart](https://docs.puppygraph.com/installation/helm-chart/)
- [P verbatim] Default resources:

  | Role | Replicas | CPU | Memory | Storage |
  |---|---|---|---|---|
  | controlplane | 1 | 2 | 4Gi | 10Gi PV |
  | leader | 3 | 8 | 32Gi | 10Gi ephemeral |
  | compute | 3 | 8 | 64Gi | 10Gi ephemeral |
  | single-node | 1 | 4 | 16Gi | PV 50Gi (min 20Gi) |

  Single-node minimums enforced by the chart are 2 CPU and 8Gi. A `storage` section defaults to 200Gi. — [Helm chart](https://docs.puppygraph.com/installation/helm-chart/)
- [P verbatim] System requirements:
  - dev: ≥8 GB RAM and 10 GB disk;
  - production per node: ≥16 vCPU, 64 GB RAM and 50 GB disk;
  - AVX2 required on x86_64;
  - open-files hard limit ≥65536, set on the host;
  - a browser with GPU acceleration for the Web UI.
  — [Installation](https://docs.puppygraph.com/installation/)
- [P verbatim] Architecture and state:
  - The controlplane holds the registry, Web UI and gRPC discovery.
  - Leaders run leader election (2n+1) and keep cluster metadata on PVs.
  - Compute nodes keep the local cache on PVs with replication factor 3.
  - Uninstalling does not delete the PVs.
  - Upgrades: "Before any upgrade, contact PuppyGraph support"; then upgrade controlplane, then compute, then leaders.
  - Manual manifests are offered for environments without Helm.
  — [Cluster deployment](https://docs.puppygraph.com/installation/cluster-deployment/)
- [P verbatim] Image tag 1.18.0: amd64 compressed size 3,402,835,009 bytes, arm64 3,419,450,933 bytes, updated 2026-11-04. — [Docker Hub API](https://hub.docker.com/v2/repositories/puppygraph/puppygraph/tags/1.18.0)
- [P verbatim] Chart version 1.2.0 was created 2026-11-09, with `appVersion: latest`. — [Helm index.yaml](https://puppygraph.github.io/puppygraph-helm-chart/index.yaml)
- [P verbatim] Pin a full `X.Y.Z` tag. "There is no `:1.0`, `:1` or `:1.15` tag." `:stable` is a deprecation stub. — [llms.txt](https://docs.puppygraph.com/llms.txt)
- [P verbatim] 1.0.0 changed the schema format, local-table APIs and cluster architecture from 0.x ("custom automation and deployment configuration do not migrate automatically"). Breaking changes appear in point releases (1.10.0, 1.15.0, 1.15.1). — [Releases](https://docs.puppygraph.com/releases/)

### Inferences
- [I] An air-gapped install looks feasible:
  - mirror one image and the chart tarball;
  - set `image.repository` and `imagePullSecret`;
  - no external database or package index is needed at runtime.
  PuppyGraph AI needs an LLM endpoint but is optional.
- [I] Operational weight is high: 7 stateful pods by default, 3 classes of PVs, vendor-gated upgrades and a weekly release cadence with breaking changes.

### Gaps
- No licence-key or activation mechanism is documented. I could not establish whether Enterprise activation needs internet access.
- No published air-gap guide.
- Chart `appVersion: latest` means the chart does not pin an image by default.

---

## PuppyGraph: licence, editions, pricing, version, maturity

### Takeaway
PuppyGraph is proprietary and comes in two editions:
- **Developer:** free, a single node in Docker, at most 2 data sources, no SSO, no cluster.
- **Enterprise:** priced per machine (CPU and memory), with a 30-day trial.

The latest version is 1.18.0 (2026-11-04). The 1.x line went GA on 2026-06-29 and releases roughly weekly.

### Cited Findings
- [P WebFetch] Developer edition: "$0", "Forever free", "No card, no expiry".
  - Limits: "Single instance/Single node", up to "2" connected data sources, "Basic" graph explorer, "Docker on prem only", basic username/password only (no SSO), Slack community support.
  - Not included: multi-instance cluster, health/monitoring sensors, Datadog.
  - Enterprise is "Priced on the machine" (CPU and memory) with no seat, query or storage fees and no published dollar figures, a 30-day trial, and AWS, Azure and GCP marketplaces.
  — [Pricing](https://www.puppygraph.com/pricing)
- [P verbatim] Recent releases: 1.18.0 on 2026-11-04, 1.17.0 on 2026-10-28, 1.16.0 on 2026-10-21. 1.0.0 on 2026-06-29 was "the first stable release in the 1.0 series". The 0.x line ran to 0.118 (2026-04-12) and 0.100 (2025-11-11). — [Releases](https://docs.puppygraph.com/releases/)
- [S] Integration partners publishing joint material include Dremio, Starburst, StreamNative and a Databricks managed-Iceberg integration press release. — [Starburst blog](https://www.starburst.io/blog/starburst-and-puppygraph/); [Dremio developer post](https://developer.dremio.com/run-graph-queries-on-apache-iceberg-tables-with-dremio-puppygraph); [press release](https://www.silicon.co.uk/press-release/puppygraph-announces-new-native-integration-to-support-databricks-managed-iceberg-tables)

### Inferences
- [I] Any Kubernetes cluster, SSO or more than 2 sources needs Enterprise. A production deployment for keel is therefore a paid licence.
- [I] The pricing page says nothing about source availability. No public source repository was found, so it is closed source.

### Gaps
- No customer count or adoption figures.
- Licence agreement (MSA) terms not read.
- Unclear whether the Developer edition permits the Helm single-node mode.

---

## Other graph engines that read Iceberg (Neo4j, TigerGraph, NebulaGraph, Kuzu and successors, GraphFrames)

### Takeaway
No mainstream graph database other than PuppyGraph queries Iceberg in place through a REST catalog.
- TigerGraph 4.3 has a built-in REST-catalog loader, which copies the data in. Its own research prototype, GraphLake, works in place.
- Kuzu is archived. Its fork LadybugDB scans Iceberg by file path only: no catalog, no STRUCT columns, no writes.
- No Neo4j or NebulaGraph in-place path was found.
- GraphFrames inherits Spark's Iceberg support.

### Cited Findings
- [P WebFetch] TigerGraph 4.3 "adds Apache Iceberg" as a built-in connector. Behaviour:
  - It loads into vertices and edges through loading jobs (Kafka Connect based): one-time (`EOF="true"`), or continuous (`EOF="false"`, `poll.interval.ms`), or pinned with `iceberg.snapshot.id`.
  - Catalog keys: `iceberg.catalog.type` (commonly `rest`), `iceberg.catalog.uri`, and OAuth2 keys `iceberg.catalog.oauth2.token`, `.token.expires`, `.token.refresh.enabled`, `.token.endpoint.url`, `.client.id`, `.client.secret` and `.token.scopes`.
  - Storage keys: `aws.s3.endpoint` (required for MinIO), `aws.s3.access_key` and `aws.s3.secret_key` (required), and `iceberg.s3.enable.path.style.access` (default true).
  - Vended credentials are not mentioned. Tables are referenced as `namespace.table`.
  - The page also says "Authentication for REST Catalogs isn't supported in TigerGraph 4.3", which contradicts its own OAuth2 section.
  — [TigerGraph: Load from Apache Iceberg](https://www.tigergraph.com/docs/tigergraph-server/4.3/data-loading/load-from-iceberg)
- [S] TigerGraph's Spark Connector can also read Iceberg, Delta and Hudi into TigerGraph. — [TigerGraph: Load from Data Lakehouse](https://www.tigergraph.com/docs/tigergraph-server/4.3/data-loading/load-from-lakehouse)
- [P WebFetch] GraphLake maps lakehouse tables to vertex and edge types and runs GSQL. It loads only the topology and adds a graph-aware cache. It is described only in a research paper, with no product or open-source status stated. — [arXiv 2603.03705](https://arxiv.org/abs/2603.03705)
- [S] Searches found no Neo4j feature that queries Iceberg in place. Results showed only PuppyGraph positioning against Neo4j. — search summary, sources incl. [PuppyGraph graph lakehouse blog](https://www.puppygraph.com/blog/graph-lakehouse)
- [S] No NebulaGraph–Iceberg connector was found. Apache SeaTunnel has an Iceberg source and a NebulaGraph sink (NebulaGraph ≥3.5, writes vertices under one tag), which could be chained as a copy pipeline. — [SeaTunnel NebulaGraph sink](https://seatunnel.apache.org/docs/connectors/sink/NebulaGraph); [SeaTunnel Iceberg source](https://seatunnel.apache.org/docs/connectors/source/Iceberg)
- [P verbatim] The `kuzudb/kuzu` repository is `"archived": true`, last pushed 2025-10-10, with 4,022 stars. — [GitHub API](https://api.github.com/repos/kuzudb/kuzu)
- [S] Kuzu's Iceberg extension scanned and copied tables, could not write, and did not support STRUCT columns. — [Kuzu docs: Iceberg](https://docs.kuzudb.com/extensions/iceberg)
- [P WebFetch] LadybugDB (community fork of Kuzu) Iceberg extension:
  - Reads by path, e.g. `LOAD FROM '/tmp/iceberg_tables/university' (file_format='iceberg', allow_moved_paths=true)`.
  - S3 is set with `s3_access_key_id`, `s3_secret_access_key`, `s3_endpoint`, `s3_region` and `s3_url_style` (`vhost` or `path`).
  - No REST catalog. Metadata is found via `version-hint.txt` or an explicit `version`.
  - No STRUCT columns and no export to Iceberg. `LOAD FROM` scans without copying, and `COPY FROM` copies.
  — [LadybugDB: Iceberg](https://docs.ladybugdb.com/extensions/attach/iceberg)
- [P verbatim] LadybugDB released v0.22.3 on 2026-11-07 and v0.22.1 on 2026-10-28. — [GitHub releases API](https://api.github.com/repos/LadybugDB/ladybug/releases)
- [P verbatim] GraphFrames released v0.12.3 on 2026-10-05. — [GitHub releases API](https://api.github.com/repos/graphframes/graphframes/releases)

### Inferences
- [I] TigerGraph's loader can take keel's static token or client credentials, if the "not supported" sentence is stale. It still needs static S3 keys and repeats the endpoint, and it copies data. It is not an in-place engine.
- [I] LadybugDB and Kuzu are embedded in-process libraries with no server, Helm chart or catalog client. With keel they work only if given raw `metadata.json` locations and static keys.
- [I] GraphFrames meets exactly what Spark meets, which is already assessed. Its footprint is a Spark cluster.

### Gaps
- Not researched within the budget: TigerGraph licence/Community Edition terms and its Kubernetes operator footprint; other Kuzu successors (RyuGraph, Kineviz Bighorn); DuckDB's DuckPGQ graph extension over DuckDB-Iceberg; Neo4j's Spark connector as an ETL path; Apache AGE and Memgraph.
- Delete-file and v3 support were not documented for any of these graph engines.

---

## Search and vector engines that query Iceberg directly (Milvus, Elasticsearch, OpenSearch, LanceDB, Vespa) and other specialized engines

### Takeaway
- **Milvus 3.0:** External Collections index an Iceberg snapshot in place but address it by `metadata.json` path plus snapshot ID, not through a catalog. Read-only, explicit refresh, public beta on Zilliz.
- **Elasticsearch:** ES|QL Data Federation (experimental in 9.5, Enterprise subscription) reads only Parquet/NDJSON/CSV/TSV on S3. An Iceberg adapter is still in development.
- **OpenSearch:** has only AWS-managed direct query over Spark tables in Glue.
- **LanceDB:** uses an Iceberg REST catalog only to register Lance tables.
- **Vespa:** nothing found.
- **StarTree** (commercial Pinot) is the strongest specialised in-place reader: v2 and v3 deletes in preview. It is SaaS/BYOC and not self-hosted open source.

### Cited Findings
- [P verbatim] Milvus/Zilliz external collection, `iceberg-table` format: "Set `external_source` to the `metadata.json` file of the Iceberg table, and pass in the snapshot ID". Example: `"format": "iceberg-table", "snapshot_id": "473984310232959286"`. Other formats are parquet, lance-table and vortex. A refresh writes manifest files and "usually takes 150-250 ms". The page is marked `beta: PUBLIC` and requires clusters "compatible with Milvus v3.0.x". — [Zilliz: Create an External Collection](https://docs.zilliz.com/docs/create-external-collection.md)
- [P WebFetch] Milvus 3.0 blog (2026-08-24):
  - "External Collections are read-only". Refresh is explicit and incremental, not streaming. Snapshot pinning is supported.
  - Indexes, manifests and caches live in Milvus.
  - "Storage V3 is required" and must be enabled in open-source 3.0.
  - Iceberg-specific credential keys and delete-file handling are not stated.
  — [Milvus blog](https://milvus.io/blog/milvus-3-0-external-collection.md)
- [P verbatim] Milvus v3.0.6 was released 2026-11-11, and v2.6.27 on 2026-11-10. — [GitHub releases API](https://api.github.com/repos/milvus-io/milvus/releases)
- [P verbatim] Elasticsearch ES|QL Data Federation:
  - "Experimental in 9.5" and off by default (`esql.federation.enabled`).
  - Only data source type: `s3` (Amazon S3). "Federated data sources support Parquet, NDJSON, CSV, and TSV files."
  - Self-managed clusters need an "Enterprise subscription".
  - `LOOKUP JOIN` and `TS` fail on datasets.
  — [ES|QL Data Federation](https://www.elastic.co/docs/reference/query-languages/esql/esql-data-federation.md); [Data sources](https://www.elastic.co/docs/reference/query-languages/esql/esql-data-federation-sources.md)
- [S] An Elasticsearch PR designs an Iceberg "datalake adapter with catalog integration and filter pushdown", and a 9.6.0-SNAPSHOT javadoc lists a source operator for "Iceberg tables, Parquet files". Unreleased. — [elastic/elasticsearch PR #141678](https://github.com/elastic/elasticsearch/pull/141678); [9.6.0 snapshot javadoc](https://snapshots.elastic.co/9.6.0-606eef5e/javadoc/org/elasticsearch/plugin/x-pack-esql/9.6.0-SNAPSHOT/allclasses-index.html)
- [S] Amazon OpenSearch Service direct query on S3 uses "Spark tables in the AWS Glue Data Catalog" created from Query Workbench, needs domain ≥2.13 and an S3 checkpoint bucket, and is AWS-managed. No Iceberg REST support was found for open-source OpenSearch. — [AWS: direct query S3](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/direct-query-s3-creating.html)
- [S] The Lance Namespace "Iceberg" implementation stores each Lance table in an Iceberg REST catalog as an entry with a placeholder schema and `table_type=lance`. The data stays in Lance format. That page now returns 404. — [Lance namespace Iceberg impl (search result)](https://lancedb.github.io/lance-namespace/spec/impls/iceberg); [Polaris + Lance blog](https://polaris.apache.org/blog/2026/01/06/apache-polaris-and-lance-bringing-ai-native-storage-to-the-open-multimodal-lakehouse/)
- [P verbatim] LanceDB connects to remote catalogs through a REST namespace (`connect_namespace("rest", ...)`). LanceDB Enterprise uses an `x-api-key` header. The page does not mention Iceberg. — [LanceDB namespaces](https://docs.lancedb.com/namespaces.md)
- [S] No Vespa feature for querying or feeding Iceberg was found in search. — search summary (no Vespa result)
- [P verbatim] StarTree External Tables: "Query Iceberg catalogs and raw Parquet in place — no copy, no ETL".
  - Catalogs: AWS Glue, Unity (incl. UniForm), "Apache Polaris & Snowflake Open Catalog… using OAuth2 principal credentials", and Hive.
  - Deletion-vector support is a preview requiring StarTree 0.16.0+, enabled by support. It applies "Iceberg v2 equality/position deletes and v3 Puffin deletion vectors at query time". v2 deletes are materialised at snapshot sync.
  - Time travel is limited by `iceberg.snapshotProcessing.retention.maxActiveVersions` (default 2).
  — [StarTree External Tables](https://docs.startree.ai/integrations/external-tables.md); [StarTree deletion vectors](https://docs.startree.ai/corecapabilities/external-table/deletion-vectors.md)

### Inferences
- [I] None of these meets keel's requirements 1 and 2 together.
  - Milvus and LanceDB/Ladybug bypass the catalog protocol, so there are no vended credentials and keel's authorisation is not enforced.
  - Elasticsearch cannot read Iceberg tables yet. It would also ignore deletes, because it reads raw Parquet.
- [I] With keel, Milvus would need its own static S3 credentials and a pipeline that resolves the current `metadata.json` and snapshot ID from keel, then calls refresh.
- [I] StarTree's Polaris path (OAuth2 principal credentials) suggests a generic IRC might work. This is unverified, and StarTree is not an on-prem open-source option.

### Gaps
- Not documented or not found:
  - Milvus Iceberg delete-file handling, supported format versions, and `extfs` keys for S3-compatible endpoints or path-style.
  - Whether StarTree supports a generic Iceberg REST catalog, vended credentials or custom S3 endpoints.
  - Apache Pinot open-source Iceberg external tables.
  - Vespa and OpenSearch open-source Iceberg plans.
- Milvus Kubernetes footprint (operator or Helm) was not researched here.

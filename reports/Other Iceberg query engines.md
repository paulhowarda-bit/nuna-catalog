# Five engines earn a nuna test drive

Five of the engines not yet assessed deserve a hands-on run against nuna, and only one of them documents every requirement. **Spice.ai OSS should be tested first.** It is one Apache-2.0 binary or container that serves Arrow Flight SQL and HTTP, reads v1 to v3 tables including deletion vectors, and uses the credentials nuna vends. It has two drawbacks: it refuses a vended endpoint on a private address until you repeat that endpoint in its own config, and it reaches nuna under a single identity. **Starburst Enterprise is the only engine that documents the whole checklist.** That includes passing the signed-in user's own OAuth2 token through to the catalog. It is a licensed Trino, needs Trino-sized nodes, and has no published price. **Polars is the easiest to run** (a handful of Python wheels) and carries each user's own token by construction, but it cannot read equality deletes. **Kyuubi in front of Spark is the open-source route to per-user identity.** It inherits Spark's clean pass against nuna, at the cost of ZooKeeper and per-user Spark pods. **PuppyGraph earns a place only if you want graph queries over nuna's tables.** Its documentation states that vended credentials "do not include a storage endpoint", which is the gap that broke StarRocks 4.1.7 against nuna, and it documents nothing about deletes, format v3 or nested namespaces. Every other engine surveyed either fails a hard requirement on paper or documents too little to rank. The best predictor of the endpoint failure is the Iceberg library an engine embeds: Java Iceberg and iceberg-rust build their file access from the `config` map that nuna returns, while engines with their own storage layer ignore it.

*Provenance: this report synthesises four research notes from 2026-11-12 covering more than 50 engines and services, plus a reading of nuna's own source on the same day. No engine named here was run. The comparison results for Spark, Trino, Flink, PyIceberg, DuckDB, SingleStore and StarRocks come from earlier hands-on runs against nuna. Claims the researchers inferred rather than read are marked as inferences.*

## Nuna supplies the endpoint, and failing engines never read it

Nuna returns the vended STS credentials twice on every table load:
- **In the response's `config` map**, alongside `client.region`, `s3.endpoint` (for any store that is not AWS) and `s3.path-style-access`.
- **In a `storage-credentials` entry** scoped to the table's prefix. This entry carries the keys only.

Silo/MinIO and Ceph always get path-style addressing, because "their endpoints rarely carry a wildcard DNS name per bucket" ([StsCredentialVending.java](../catalog-server/src/main/java/nuna/catalog/server/storage/StsCredentialVending.java), [StoreKind.java](../catalog-objectstore/src/main/java/nuna/catalog/objectstore/StoreKind.java)). **Nuna vends whether or not the client sends `X-Iceberg-Access-Delegation`.** Only a registered trusted engine that sends exactly `none` gets nothing. That has three consequences:
- Clients that never send the header, such as iceberg-rust and Spice, still receive credentials.
- A client that reads only `storage-credentials` gets keys but no endpoint.
- A client that does everything right needs no storage settings at all. Spark, Trino, Flink, PyIceberg, DuckDB and SingleStore showed exactly that: all of them created or read nuna's tables with vended credentials alone ([engine matrix](../catalog-tests-engines/README.md)).

StarRocks 4.1.7 is the counter-example. Configured as its documentation describes, it sent nuna's Silo credentials to `s3.us-east-1.amazonaws.com`, and every read failed with an S3 400 error ([StarRocks note](C:/Users/paulh/.claude/notes/starrocks-iceberg-facts.md)).

Authentication narrows the field further. **Nuna issues no tokens:** its `/v1/oauth/tokens` route only returns an error that names the identity provider (IdP). It also requires `exp` and checks `aud` against a per-issuer list ([Authentication.java](../catalog-server/src/main/java/nuna/catalog/server/security/Authentication.java), [TokenAuthenticator.java](../catalog-security/src/main/java/nuna/catalog/security/TokenAuthenticator.java)). An engine therefore needs one of two things:
- a static JWT, which suits tests but stops working when it expires;
- a client-credentials grant against an external token endpoint.

An engine that can only call the catalog's own token route cannot connect. End-user identity can reach nuna in two ways:
- the user's own JWT, sent as the bearer token;
- a registered trusted engine's token, plus `X-Nuna-End-User` and `X-Nuna-End-User-Groups` headers.

Either way, the user appears in nuna's credential-issue audit event. Every engine below sets its catalog headers once per catalog, not per user, so **the trusted-engine path has no off-the-shelf client today**. In practice, per-user identity means the user's own token reaches nuna.

The library each engine embeds sorts the field before any test is run:

| Embedded Iceberg client | Where it gets storage settings | Engines in this survey | Expected against nuna |
|---|---|---|---|
| Java Iceberg (`S3FileIO`) | The load-table `config` | Spark, Trino, Flink, Kafka Connect (all passed); Kyuubi, Starburst, Comet's write path | Honours the vended endpoint; proven for the first four |
| iceberg-rust | `config`, with user settings layered on top; `storage-credentials` is parsed but not used | Spice (fork of 0.11), Databend (fork of 0.8), RisingWave (`rest_rust`), Comet's reader, DataFusion | Honours nuna's endpoint and path-style, because nuna puts both in `config` |
| PyIceberg plus the engine's own reader | PyIceberg's FileIO properties, translated | Polars, Daft | Endpoint mapped; path-style key ignored, but path-style is used whenever an endpoint is set |
| The engine's own storage layer | The engine's own S3 settings | StarRocks (observed), PuppyGraph (documented), Presto (`hive.s3.*`), Sail (AWS environment), TigerGraph loader, Druid | Vended endpoint or vended keys ignored |

The iceberg-rust row rests on its REST client code. It merges `response.config` with the user's settings and does not merge `storage-credentials` into FileIO, and since 0.10.0 it has defaulted S3 to virtual-host addressing ([catalog.rs](https://github.com/apache/iceberg-rust/blob/main/crates/catalog/rest/src/catalog.rs), [releases](https://api.github.com/repos/apache/iceberg-rust/releases)). Nuna's explicit `s3.path-style-access=true` is what keeps those clients off virtual-host addressing on Silo and Ceph.

## Spice and Polars are the easy wins, each with one sharp edge

### Spice.ai OSS

**Spice 2.4.2 (9 November 2026) is the easiest shared SQL server in the survey that meets the format requirements.** It runs on a fork of iceberg-rust 0.11.0 and a forked DataFusion 55.2 ([Cargo.toml](https://github.com/spiceai/spiceai/blob/trunk/Cargo.toml)).

It authenticates with `iceberg_token`, or with `iceberg_oauth2_credential` against an external `iceberg_oauth2_server_url` ([Spice Iceberg docs](https://github.com/spiceai/docs/blob/trunk/website/docs/components/catalogs/iceberg.md)). That covers both of nuna's modes.

Its documentation states the one trap: **Spice "refuses a catalog-supplied endpoint that resolves to a private, loopback, or link-local address"**. You must set `iceberg_s3_endpoint` and `iceberg_s3_region`, or turn the check off with `SPICE_URL_TABLE_ALLOW_PRIVATE_HOSTS`. Vended credentials themselves "are passed through unchanged" ([iceberg.rs](https://github.com/spiceai/spiceai/blob/trunk/crates/runtime/src/catalogconnector/iceberg.rs)). This fails closed, where StarRocks fails by leaking credentials to AWS. Repeating one endpoint is a fair price for that. Path-style access defaults to true.

**Format support:**
- Spice reads format versions 1, 2 and 3, and 2.4.0 "applies deletion vectors from Puffin files" ([v2.4.0 notes](https://github.com/spiceai/spiceai/blob/trunk/docs/release_notes/v2.4.0.md)).
- Nested namespaces work, with parts separated by `%1F`. A deep table is exposed under the name of its last namespace level, so a table `t` in `a.x` and a table `t` in `b.x` would collide (inference).
- Writes are append-only `INSERT` and `DELETE FROM`, which Spice writes as equality-delete files. A v3 table still accepts equality deletes: StarRocks failed on v3 because it wrote position deletes. `UPDATE` and `MERGE` are not documented.

**Operations:**
- The `spiced` runtime is a 308.9 MB Linux tarball or a `ghcr.io/spiceai/spiceai` image, and it does not build for native Windows ([DISTRIBUTIONS.md](https://github.com/spiceai/spiceai/blob/trunk/docs/DISTRIBUTIONS.md)). Run it in the cluster, not on the work laptop.
- It serves Flight SQL and HTTP, and has a runtime-auth module whose documentation was not read ([flight source](https://github.com/spiceai/spiceai/tree/trunk/crates/runtime/src/flight)).

**Two unknowns decide whether it survives production:**
- **Token refresh.** Upstream iceberg-rust still carries "TODO: Support automatic token refreshing" and has no retry on a 401 ([oauth2.rs](https://github.com/apache/iceberg-rust/blob/main/crates/catalog/rest/src/auth/oauth2.rs)). A long-running Spice server that uses either a static JWT or a once-fetched client-credentials token will fail when that token expires, unless Spice's fork fixed this. Nobody has checked.
- **STS renewal.** Whether Spice re-requests STS credentials during long accelerations is not documented either.

Every user of a Spice server reaches nuna as the server.

### Polars

**Polars 2.1.0 (25 October 2026, MIT) is the lightest option and gives per-user identity for free.** It hands catalog access to PyIceberg, which has already passed nuna's matrix with a static token, nested namespaces and vended credentials ([catalog resolver](https://github.com/pola-rs/polars/blob/main/py-polars/src/polars/io/iceberg/_catalog_resolver.py)).

Polars translates a table's FileIO properties into object-store options. `s3.endpoint` becomes `aws_endpoint_url`, and the session token is carried over. **`s3.path-style-access` is not mapped.** Path-style then depends on object_store's virtual-host setting defaulting to off; the researcher recalled this rather than read it ([_dataset.py](https://github.com/pola-rs/polars/blob/main/py-polars/src/polars/io/iceberg/_dataset.py)).

**Deletes are the sharp edge:**
- The native reader applies position deletes, limited to one data file each, and since py-1.44.0 it applies v3 deletion vectors ([releases](https://api.github.com/repos/pola-rs/polars/releases)).
- Equality deletes make the native planner raise `NotImplementedError` and fall back to PyIceberg. The Iceberg status page lists PyIceberg as unable to read equality deletes ([status.md](https://github.com/apache/iceberg/blob/main/site/docs/status.md)).
- So **Polars cannot read tables written as upserts by Flink or Kafka Connect, or nuna's demo change-feed table.**

**Writes:**
- Supported: append and overwrite through `sink_iceberg`, schema merge, dynamic partition overwrite, `CREATE`/`DROP` in SQL, and an unstable `delete_iceberg` that calls PyIceberg.
- Not supported: `UPDATE` and `MERGE`.
- Since the catalog side is PyIceberg 0.12.0, which refuses to write v3 manifests, appends to v3 tables need checking.
- The native planning plugin, `polars-iceberg`, is version 0.1.0, first published 15 October 2026 ([PyPI](https://pypi.org/project/polars-iceberg/)).

**Air-gap install:** mirror `polars` (0.9 MB), `polars-runtime-32` (53.9 MB), `polars-iceberg` (1.2 MB) and PyIceberg's dependencies ([pyproject.toml](https://github.com/pola-rs/polars/blob/main/py-polars/pyproject.toml)).

### Daft

**Daft 0.7.26 is the weaker sibling.** It has the same PyIceberg front end, and path-style follows from `force_virtual_addressing` defaulting to false when an endpoint is set ([`__init__.pyi`](https://github.com/Eventual-Inc/Daft/blob/main/daft/daft/__init__.pyi)). However, it hands every delete file to its native reader without checking the delete type, and its source tree has no Puffin or deletion-vector code ([iceberg_scan.py](https://github.com/Eventual-Inc/Daft/blob/main/daft/io/iceberg/iceberg_scan.py)). A table with equality deletes or a deletion vector could therefore return deleted rows instead of failing. That is untested, but it is the worst failure class, so Daft belongs on the list only if Ray-distributed dataframes are needed.

## Per-user identity costs either a licence or a Spark cluster

### Starburst Enterprise

**Starburst Enterprise (SEP) is the only shared engine that documents sending the end user's identity to an Iceberg REST catalog.** The latest releases are 483-e STS (16 October 2026) and 482-e LTS (11 September 2026) ([releases](https://docs.starburst.io/latest/release.html)).

**What it documents:**
- **Pass-through setup:** set `http-server.authentication.type=DELEGATED-OAUTH2` on the coordinator and `iceberg.rest-catalog.security=OAUTH2_PASSTHROUGH` in the catalog. Then "the received token is passed through SEP and the connector to the underlying data source" ([OAuth2 pass-through](https://docs.starburst.io/latest/security/oauth2-passthrough.html)). It first appears in the 477-e docs (November 2025).
- **Vended credentials and nested namespaces** are opt-in flags, both defaulting to false ([metastores](https://docs.starburst.io/latest/object-storage/metastores.html)).
- **Format v3:** the connector lists v3 deletion vectors, row lineage, `variant`, nanosecond timestamps and column defaults ([Iceberg connector](https://docs.starburst.io/latest/connector/iceberg.html)).

The SEP docs do not say how it treats a vended `s3.endpoint`. SEP is Trino underneath, and Trino passed nuna's matrix with vended credentials only, so it should work. That is an inference, not a test.

**The pass-through has hard edges:**
- "No token refresh mechanism is supported", so every token must outlive the longest query.
- The REST metadata cache switches off for per-user modes, which adds load on nuna.
- The only documented token exchange is for Databricks Unity.

For nuna, this means its issuer policy must accept the audience of tokens issued to SEP, or the IdP must add nuna's audience to them. The Iceberg connector page also contradicts itself: it lists v3 row-level writes as deletion vectors, yet still says "Version 3 support is experimental; row-level updates, deletes, and OPTIMIZE are not supported."

**Operations:**
- SEP is deployed with Helm on Kubernetes 1.26 to 1.36 or OpenShift.
- It wants identical nodes of **16 to 64 cores and 64 to 256 GB RAM**, and a signed licence file on every node ([requirements](https://docs.starburst.io/latest/k8s/requirements.html), [licence](https://docs.starburst.io/latest/license-requirements.html)).
- For air-gapped installs, the docs say only that you can use your own registry.
- No price is published. A third-party figure of $500 per vCPU per year with a $100,000 minimum is unverified ([rfp.wiki](https://www.rfp.wiki/artificial-intelligence/data-lakehouse-platforms/iomete/starburst)).
- Dell Data Lakehouse ships the same features, built from 480-e LTS ([Dell SEP docs](https://dell.starburst.io/latest/object-storage/metastores.html)).

### Kyuubi in front of Spark

**Kyuubi 1.12.0 (28 July 2026, Apache-2.0) turns Spark into a multi-user SQL service.** It inherits Spark's Iceberg client unchanged, so it meets every requirement Spark met against nuna ([Kyuubi Iceberg doc](https://github.com/apache/kyuubi/blob/master/docs/connector/spark/iceberg.md)).

**Identity.** The Spark engine runs as the session user, because `kyuubi.engine.doAs.enabled` defaults to true. The share level, `CONNECTION` or `USER` (the default), gives each connection or each user its own engine ([share levels](https://github.com/apache/kyuubi/blob/master/docs/deployment/engine_share_level.md)). That structure makes real pass-through possible.

The researcher's suggested route, which has not been tested:
- Use share level `CONNECTION`.
- The client puts `spark.sql.catalog.nuna.token=<its own JWT>` in the JDBC URL.
- That private Spark engine then talks to nuna as the user, and nuna's own token check stops anyone from claiming to be someone else.
- Use `kyuubi.session.conf.restrict.list` to stop clients from overriding the catalog URI ([settings](https://github.com/apache/kyuubi/blob/master/docs/configuration/settings.md)).

Two limits apply:
- Token expiry inside a long session is not handled.
- Under the `USER` share level, the first session's token serves every later session of that user.

**Costs:**
- Bearer-token login to Kyuubi itself needs a custom `TokenAuthenticationProvider` class. The built-in options are LDAP, Kerberos, JDBC and NOSASL ([authentication](https://github.com/apache/kyuubi/blob/master/docs/security/authentication.md)).
- High availability needs an external ZooKeeper ([HA guide](https://github.com/apache/kyuubi/blob/master/docs/deployment/high_availability_guide.md)).
- On top of the two-replica Kyuubi StatefulSet, you get Spark driver and executor pods per engine ([chart values](https://github.com/apache/kyuubi/blob/master/charts/kyuubi/values.yaml)).

### Speeding up Kyuubi's Spark

**DataFusion Comet 1.2.0 (3 November 2026) can accelerate that Spark without changing its catalog client.** Its native Iceberg reader is on by default. It handles v1 to v3, position and equality deletes, and deletion vectors, and it is tested against REST catalogs ([Comet Iceberg guide](https://github.com/apache/datafusion-comet/blob/main/docs/source/user-guide/latest/iceberg.md)). It builds its own object-store client from the catalog's `s3.*` properties. Only the write path is documented to forward REST-vended credentials, so the read path needs the endpoint test too.

Gluten's Velox backend is not a substitute. It offloads only unpartitioned tables without equality deletes and does not mention deletion vectors ([Gluten Iceberg](https://github.com/apache/gluten/blob/main/docs/backends/velox/get-started/VeloxIceberg.md)).

## PuppyGraph queries nuna in place but repeats the StarRocks gap

**PuppyGraph 1.18.0 (4 November 2026) is the only graph engine in this survey that queries Iceberg in place through a generic REST catalog.** It maps tables to nodes and edges and serves openCypher over Bolt and Gremlin. That pays off only if relationships, lineage edges for example, are already stored as rows in nuna ([llms.txt](https://docs.puppygraph.com/llms.txt)).

**Requirement by requirement, it is weak:**
- **Authentication:** OAuth2 client credentials (`credential`, `scope`, `oauthServerUri`) against an external IdP. There is no documented static-token key ([Connecting to Iceberg](https://docs.puppygraph.com/connecting/connecting-to-iceberg/)).
- **Vended endpoint:** the same page says "Vended credentials do not include a storage endpoint". It tells you to pick Amazon S3, set "Credentials vended by the catalog", and repeat the Region and Endpoint. That mode documents no path-style option, which is exactly the setting Silo and Ceph need. The static-key S3-compatible mode does have one.
- **Formats:** a search of the full docs and every release note found nothing on format versions, equality deletes or deletion vectors. The type map has no v3 types.
- **Nested namespaces:** mentioned only for semantic-model import.
- **Writes:** PuppyGraph writes no Iceberg ([releases](https://docs.puppygraph.com/releases/)).
- **Identity:** propagation of the signed-in user exists only for Snowflake and Elasticsearch, so every PuppyGraph user reaches nuna as one service principal.

The researcher inferred that PuppyGraph's SQL layer descends from StarRocks or Doris. The evidence is a `PRIORITY_IP_CIDR` setting, leader/follower metadata election, an AVX2 requirement, StarRocks-style key models and REST property names that mirror StarRocks'. If that is right, nuna should expect StarRocks' behaviour until a test proves otherwise.

**Operations are heavy for what it does:**
- The image is 3.4 GB ([Docker Hub](https://hub.docker.com/v2/repositories/puppygraph/puppygraph/tags/1.18.0)).
- The default Helm cluster runs 7 stateful pods requesting **about 50 vCPU and 292 GiB**. Single-node mode needs 4 vCPU and 16 GiB ([Helm chart](https://docs.puppygraph.com/installation/helm-chart/)).
- Upgrades start with "contact PuppyGraph support" ([cluster deployment](https://docs.puppygraph.com/installation/cluster-deployment/)).
- Releases come roughly weekly, and point releases have carried breaking changes.
- The free Developer edition is a single node in Docker with at most two data sources and no SSO. Anything on Kubernetes needs Enterprise, priced per machine with no published rate ([pricing](https://www.puppygraph.com/pricing)).
- Air-gapping looks feasible (one image plus the chart), but whether Enterprise licence activation needs the internet is undocumented.

No other graph, search or vector engine fits:
- **TigerGraph 4.3** copies Iceberg into the graph, using static S3 keys ([TigerGraph](https://www.tigergraph.com/docs/tigergraph-server/4.3/data-loading/load-from-iceberg)).
- **Kuzu** is archived. Its fork **LadybugDB** reads Iceberg by file path, with no catalog ([LadybugDB](https://docs.ladybugdb.com/extensions/attach/iceberg)).
- **Milvus 3.0** indexes a snapshot by its `metadata.json` path ([Zilliz](https://docs.zilliz.com/docs/create-external-collection.md)).
- **Elasticsearch 9.5** reads only Parquet, NDJSON, CSV and TSV ([ES|QL federation](https://www.elastic.co/docs/reference/query-languages/esql/esql-data-federation.md)).

## Most of the field fails a hard requirement on paper

The engines below fail at least one non-negotiable requirement in their own documentation or source. Several are actively developed, so the reason matters more than the verdict.

| Engine | Fails on | Evidence |
|---|---|---|
| PrestoDB 0.300, Denodo Embedded MPP | Vended credentials: `PrestoRESTFileIO` stores them, but all file access goes through static `hive.s3.*` settings. Its `session=USER` sends an unsigned JWT minted by Presto. The Helm chart is stuck at 0.295 | [PrestoRESTFileIO.java](https://github.com/prestodb/presto/blob/master/presto-iceberg/src/main/java/com/facebook/presto/iceberg/PrestoRESTFileIO.java), [IcebergRestCatalogFactory.java](https://github.com/prestodb/presto/blob/master/presto-iceberg/src/main/java/com/facebook/presto/iceberg/rest/IcebergRestCatalogFactory.java), [Denodo](https://community.denodo.com/docs/html/browse/9.2/en/mpp/configuration/external_metastore/snowflake_open_catalog) |
| Sail 0.7.3 | Vended credentials are fingerprinted, not applied ("TODO: Convert preserved REST table-session credentials…"). No client-credentials option | [provider.rs](https://github.com/lakehq/sail/blob/main/crates/sail-catalog-iceberg/src/provider.rs), [iceberg-rest.md](https://github.com/lakehq/sail/blob/main/docs/guide/catalog/iceberg-rest.md) |
| Apache Impala | 4.5.3 has no REST catalog and embeds Iceberg 1.4.3. Unreleased 5.0 requires `vended-credentials-enabled=false` and `nested-namespace-enabled=false` | [REST catalog doc](https://github.com/apache/impala/blob/master/docs/topics/impala_iceberg_rest_catalog.xml) |
| Databend | Single-level namespaces. Its iceberg-rust fork still has "TODO: Delete Vector loader". Its own vended-credential refresh is a strength | [catalog.rs](https://github.com/databendlabs/databend/blob/main/src/query/storages/iceberg/src/catalog.rs), [fork loader](https://github.com/databendlabs/iceberg-rust/blob/dev/crates/iceberg/src/arrow/caching_delete_file_loader.rs) |
| Hive 4.2 | The REST client builds single-level namespaces. HiveServer2 is required and there is no official chart | [HiveRESTCatalogClient.java](https://github.com/apache/hive/blob/master/iceberg/iceberg-rest-catalog-client/src/main/java/org/apache/iceberg/hive/rest/catalog/client/HiveRESTCatalogClient.java) |
| Firebolt self-managed | No v3 or deletion vectors. No static token for generic REST. Writes are export-only | [Firebolt Iceberg](https://docs.firebolt.io/guides/iceberg-and-data-lake/iceberg.md), [CREATE LOCATION](https://docs.firebolt.io/reference-sql/commands/data-definition/create-location-iceberg.md) |
| Vertica 26.1 (Rocket) | "Iceberg delete files are not supported"; v1/v2 metadata only | [Vertica 26.1 (archived)](https://web.archive.org/web/20260421095657/https://docs.vertica.com/26.1.x/en/sql-reference/statements/create-statements/create-external-table-iceberg/) |
| Oracle 26ai / ADB | "Catalog credential vending: Not supported"; queries fail on any delete file | [Oracle ADB Iceberg](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/query-external-data-apache-iceberg.html) |
| e6data | Control plane is always e6data's SaaS; no equality deletes; v3 "in progress" | [deployment models](https://docs.e6data.com/platform/deployment-models.md), [Iceberg](https://docs.e6data.com/query/table-formats/iceberg.md) |
| RisingWave 3.1 | Deletion vectors documented only for its own streaming tables. Four or more node roles plus PostgreSQL | [feature support](https://github.com/risingwavelabs/risingwave-docs/blob/main/iceberg/iceberg-feature-support.mdx) |
| Teradata on-prem, Cloudera, IBM watsonx.data | Hive catalogs only on-prem; a cloud-only read preview; external REST documented for SaaS only | [Teradata](https://docs.teradata.com/r/VMware/Teradata-VantageCore-VMware-Quick-Start/VantageCore-VMware-Quick-Start-Requirements/Open-Table-Format-OTF-Support), [Cloudera](https://docs.cloudera.com/data-warehouse/cloud/release-notes/topics/dw-public-cloud-tp-r44.html) |
| Exasol Lakehouse Turbo, Druid | Both copy data rather than query in place; Druid lists only "live data files", so it ignores deletes | [Exasol](https://docs.exasol.com/db/latest/connect_exasol/lakehouse_turbo_as_app.htm), [Druid](https://github.com/apache/druid/blob/master/docs/development/extensions-contrib/iceberg.md) |
| StarTree / Pinot, Drill, Kylin, GlareDB, Ballista, MotherDuck, chDB | No generic-REST vended credentials (StarTree); no Iceberg or no catalog; dormant; cloud compute only; same as ClickHouse | [StarTree matrix](https://docs.startree.ai/corecapabilities/external-table/support-matrix.md), [MotherDuck](https://motherduck.com/docs/integrations/file-formats/apache-iceberg/) |

Two niche Postgres- and JSON-facing products, **EDB PGAA** and **Couchbase Enterprise Analytics**, support REST with a token or client credentials. PGAA recommends vended credentials. Neither documents nested namespaces or v3, so neither can be ranked without a test ([EDB PGAA](https://www.enterprisedb.com/docs/pgaa/latest/catalog/), [Couchbase](https://docs.couchbase.com/enterprise-analytics/current/sqlpp/5_ddl_iceberg_catalog.md)).

**Sail is the one to watch.** It already has:
- a Spark Connect and Flight SQL server with Kubernetes cluster mode;
- a 65 MB wheel with no dependencies;
- v3 deletion-vector reads and writes, and `MERGE`;
- a bearer-token file that it re-reads, with a retry on 401.

Once that TODO is closed, Sail would be the strongest open-source shared service in this survey ([features](https://github.com/lakehq/sail/blob/main/docs/guide/sources/iceberg/features.md), [PyPI](https://pypi.org/project/pysail/)).

**DataFusion with iceberg-rust 0.11** reads every delete type and exposes the `contextual_session` hook a custom per-user server would need. Its integration, though, has just moved to `apache/datafusion-iceberg`, which has no release yet, and its catalog provider lists only top-level namespaces ([datafusion catalog.rs](https://github.com/apache/iceberg-rust/blob/v0.11.0/crates/integrations/datafusion/src/catalog.rs)).

## Ranked shortlist and the tests that settle it

Ease and fit pull in opposite directions. The ranking below puts ease first, because end-user identity is a nice-to-have rather than a requirement. If per-user identity becomes mandatory, Starburst and Kyuubi move to the top.

| Overall | Engine | Role | Ease rank | Fit rank | Deciding fact |
|---|---|---|---|---|---|
| 1 | Spice.ai OSS 2.4.2 | Shared SQL server (Flight SQL, HTTP) | 2 | 3 | One binary, v3 deletion vectors; must repeat the private endpoint; one identity; token refresh unknown |
| 2 | Starburst Enterprise 482-e LTS | Shared SQL server (Trino) | 3 | 1 | Only documented end-user pass-through; licence, large nodes |
| 3 | Polars 2.1.0 | Per-user library | 1 | 4 | Easiest install, user's own token; no equality deletes |
| 4 | Kyuubi 1.12.0 + Spark | Shared SQL gateway | 4 | 2 | Spark's proven pass plus per-connection user tokens; ZooKeeper and Spark pods |
| 5 | PuppyGraph 1.18.0 | Graph queries | 5 | 5 | In-place Cypher/Gremlin; documented endpoint gap; proprietary |

Run every engine against the demo stack, on Silo first and then on Ceph, with nuna's URI and a token and **no S3 endpoint or keys in the engine's own config**. Run the tests in this order and stop an engine at the first failure that cannot be configured around:

| # | Test | Engines | Pass looks like |
|---|---|---|---|
| 1 | **Vended endpoint and path-style.** Read `m8.shared.spark.t_v2` with no storage settings | All five; Comet's reader too | The store's access log shows path-style requests at the Silo or Ceph Service. No AWS-format request IDs. Spice: expect a refusal, then a pass with `iceberg_s3_endpoint` set. PuppyGraph: try "Get from metastore", then Amazon S3 with "Credentials vended by the catalog" plus the endpoint |
| 2 | **Token lifetime.** Keep the engine up past the JWT's `exp` and past nuna's STS lifetime | Spice (static and client credentials), Starburst (pass-through), Kyuubi | Queries keep working, or fail with a clear 401 that a restart fixes. No silent hang. Starburst: a query that outlives its token |
| 3 | **Deep namespace.** Read and, where the engine writes, create in `m8.deep.<engine>` and a four-level namespace | All five | Same rows as Spark. Spice: two tables with the same leaf namespace name |
| 4 | **Deletes.** Read Spark's v3 table with a deletion vector, a v2 table with a position delete, and the demo change-feed table with equality deletes | All five | Deleted rows absent. Polars: refuses the equality-delete table instead of returning its rows |
| 5 | **v3 writes.** Run `DELETE` on a v3 table | Spice (equality deletes), Starburst, Kyuubi; Polars append | Commit succeeds, and Spark and SingleStore read the result. Polars: whether `sink_iceberg` appends to v3 |
| 6 | **End-user identity.** Connect as two different users | Starburst (`OAUTH2_PASSTHROUGH`), Kyuubi (`CONNECTION` plus a token in the URL), Polars (the user's token) | Nuna's credential-issue audit event names each user; nuna's `aud` list accepts Starburst's tokens |
| 7 | **Air-gapped install.** Build from a mirrored registry or wheelhouse with egress blocked | All five | Starts and passes tests 1 to 4 with no outbound connection. PuppyGraph: licence activation offline |

## Conclusion

Whether an engine works with nuna is decided less by the engine than by the Iceberg client inside it. Every engine whose storage access comes from Java Iceberg or iceberg-rust should pick up nuna's endpoint without help, because nuna places it in `config`. Every engine with its own storage layer should be presumed to fail until test 1 passes. That turns "evaluate a new engine" into a five-minute triage: find the embedded client first. It also gives nuna a cheap safeguard, because it already returns `s3.endpoint` and `s3.path-style-access` in the place both client families read.

The identity requirement has an awkward answer. Nuna's trusted-engine headers have no off-the-shelf client, because every engine fixes its catalog headers per catalog, and every shared server except Starburst reaches nuna under a single identity. Per-user identity therefore comes from one of three architectures:
- per-user processes, such as Polars, DuckDB and PyIceberg;
- per-user engines, such as Kyuubi at share level `CONNECTION`;
- a licensed pass-through, in Starburst.

The second hidden risk is token lifetime. Starburst's pass-through and iceberg-rust's client both document no token refresh, so the shortlisted servers can fail when a token expires (test 2), not when they first connect.

The already-assessed list has moved as well. SAP completed its purchase of Dremio on 6 July 2026 without saying anything about the self-managed editions ([SAP](https://news.sap.com/2026/07/sap-completes-dremio-acquisition/)). That makes Dremio Community a riskier on-prem dependency than it was when it was assessed.

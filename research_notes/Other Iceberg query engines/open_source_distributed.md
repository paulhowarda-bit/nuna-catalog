# Open-source distributed SQL engines over an Iceberg REST catalog (not yet assessed), November 2026

Provenance markers used on every line:
- **[P]** read in a primary source in this session (official docs, release notes, source code, GitHub API), URL given.
- **[S]** read in a secondary source (search-result summary, vendor page, news), URL given.
- **[I]** my inference; not read anywhere.

"master" means the project's default branch on 2026-11-12, which can be ahead of the latest release. Each claim says whether it comes from a release tag or from master. Raw files were fetched from raw.githubusercontent.com; the citations below give the equivalent github.com/blob URL.

Nuna requirements are abbreviated R1 (REST + OAuth2, end-user pass-through), R2 (vended credentials + vended `s3.endpoint`/path style), R3 (nested namespaces), R4 (v2/v3 reads, writes), R5 (Kubernetes), R6 (ease of operation).

## Q1. Per engine: generic Iceberg REST catalog or not, embedded Iceberg library, v3/deletion-vector implications, and the R1–R6 fact sheet

### Takeaway
Only three of these engines are real candidates for a shared SQL service over nuna today: **PrestoDB** (Java/C++, REST with OAuth2 and nested namespaces, but no vended credentials), **Kyuubi + Spark** (it inherits Spark's full Iceberg REST client, and per-user Spark engines give impersonation), and **RisingWave** (REST + OAuth2 + vended credentials + nested namespaces, but it is a streaming database whose batch path and v3 delete support are narrow). **Databend** is light to run, and its REST client can do vended credentials, but its nested namespaces and v3 deletion vectors are missing. **Impala** has a good REST/v3 story only on unreleased master (5.0); released 4.5.x embeds Iceberg 1.4.3 and has no REST catalog. **Hive 4.2** has a REST client but is heavy to run. Gluten and Comet are Spark add-ons. Druid only ingests. Apache Pinot has no Iceberg support; StarTree's support is proprietary. Drill reads only Iceberg folders on a file system, and Kylin is not relevant.

### Cited Findings

#### PrestoDB (Java) and Presto C++ (Prestissimo)
- **Latest releases:** 0.300 on 2026-11-04, 0.299 on 2026-08-28, 0.298 on 2026-06-12, 0.297 on 2026-04-14, 0.296 on 2025-12-08, so roughly every two to three months. [P] — [GitHub releases API](https://api.github.com/repos/prestodb/presto/releases)
- **Licence and activity:** Apache-2.0, 16,773 GitHub stars, last push 2026-11-12. [P] — [GitHub repo API](https://api.github.com/repos/prestodb/presto)
- **Embedded Iceberg Java:** 0.299 and 0.300 both pin `dep.iceberg.version` 1.10.1; master pins 1.11.0. [P] — [pom.xml at 0.300](https://github.com/prestodb/presto/blob/0.300/pom.xml), [pom.xml on master](https://github.com/prestodb/presto/blob/master/pom.xml)
- **Generic REST catalog (R1):** set with `iceberg.catalog.type=rest` and `iceberg.rest.uri`. `iceberg.rest.auth.type` takes `NONE`, `BASIC` or `OAUTH2`; OAuth2 needs either `iceberg.rest.auth.oauth2.credential` (client credentials, `key:secret`) or `iceberg.rest.auth.oauth2.token` (static bearer). Optional keys are `iceberg.rest.auth.oauth2.uri` (an external token endpoint) and `...oauth2.scope`. TLS/mTLS and proxy keys also exist. [P] — [Iceberg connector doc, master](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/connector/iceberg.rst)
- **When REST features arrived:** 0.299 added REST proxy support, Basic auth and TLS. 0.300 fixed "Invalid view JSON" when reading REST views created by Spark or Trino. [P] — [release-0.299](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/release/release-0.299.rst), [release-0.300](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/release/release-0.300.rst)
- **End-user identity (`session.type=USER`):** `iceberg.rest.session.type` takes `NONE` (the default) or `USER`, and the property is present in the 0.300 doc. [P] — [connector doc at 0.300](https://github.com/prestodb/presto/blob/0.300/presto-docs/src/main/sphinx/connector/iceberg.rst)
  - In the code, `USER` builds an **unsigned** JWT (`Jwts.builder().setSubject(user)...compact()`, with no signing call) holding the Presto user and source. It passes that JWT as the `JWT_TOKEN_TYPE` credential, together with the session's extra credentials, in Iceberg's `SessionContext`, and it sets `CatalogProperties.USER`. The catalog cache key includes the user and the extra credentials.
  - So Presto does not forward the user's IdP token on its own. It sends a Presto-minted identity token in Trino's old `USER` style, which the catalog would have to accept through a token exchange. [P] — [IcebergRestCatalogFactory.java, master](https://github.com/prestodb/presto/blob/master/presto-iceberg/src/main/java/com/facebook/presto/iceberg/rest/IcebergRestCatalogFactory.java)
- **Vended credentials (R2):** neither doc mentions vended credentials or `X-Iceberg-Access-Delegation`. [P] — [connector doc, master](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/connector/iceberg.rst)
  - `PrestoRESTFileIO`, added 2026-10-09 (#28343), implements `SupportsStorageCredentials` and stores credentials the server returns. Every `newInputFile`, `newOutputFile` and `delete` call goes through Presto's `HdfsEnvironment`, which is configured by `hive.s3.*` and S3 security mappings. The stored vended credentials are not applied. [P] — [PrestoRESTFileIO.java, master](https://github.com/prestodb/presto/blob/master/presto-iceberg/src/main/java/com/facebook/presto/iceberg/PrestoRESTFileIO.java)
  - The S3 endpoint is configured statically with `hive.s3.endpoint=...` and `hive.s3.path-style-access=true`, as in the doc's MinIO-style example. [P] — [connector doc](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/connector/iceberg.rst)
- **Nested namespaces (R3):** `iceberg.rest.nested.namespace.enabled` defaults to `true`, and it appears in both the 0.300 doc and master. [P] — [connector doc](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/connector/iceberg.rst)
- **v2 reads (R4):** "Presto supports reading delete files, including Position Delete Files and Equality Delete Files" (Java worker). [P] — [connector doc](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/connector/iceberg.rst)
  - In the doc's property tables, Presto C++ reads positional deletes but has "No, Equality delete read is not supported". The SQL table lists C++ `INSERT INTO` and CTAS as "No". [P] — same doc
- **v3 (R4):**
  - **History:** 0.298 added reads of the row-lineage columns `_row_id` and `_last_updated_sequence_number`, and reads of initial-default values. 0.299 added "Iceberg V3 deletion vectors and UPDATE/MERGE statements in Presto Native" (#28058) and the V3 field-id protocol in native. 0.300 added reading `VARIANT` as `JSON`. [P] — [release-0.298](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/release/release-0.298.rst), [release-0.299](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/release/release-0.299.rst), [release-0.300](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/release/release-0.300.rst)
  - **Master doc:** `format-version` accepts 1, 2 or 3 (default 2). Row-level DELETE, UPDATE and MERGE on v3 are committed as Puffin deletion vectors, "which only a Presto C++ (Prestissimo) worker can write". A Java worker fails with "Iceberg V3 deletion-vector writes (DELETE, UPDATE, MERGE) require the native (Prestissimo) worker". Row lineage is read-only. [P] — [connector doc, master](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/connector/iceberg.rst)
  - **Type mapping (master):** `GEOMETRY`, `VARIANT`→`JSON`, `GEOGRAPHY`→`SphericalGeography` (read and write on v3, Parquet only, write on the Java worker only) and `UNKNOWN`. The table has no nanosecond timestamp type and states "No other types are supported". [P] — same doc
  - The deletion-vector paragraphs are absent from the 0.300 doc (0 matches against 7 on master), so parts of the master v3 text may describe unreleased behaviour. [P] — [connector doc at 0.300](https://github.com/prestodb/presto/blob/0.300/presto-docs/src/main/sphinx/connector/iceberg.rst)
- **Writes:** the Java worker supports CREATE, INSERT, CTAS, ALTER, DELETE, UPDATE and MERGE (merge-on-read on v2). [P] — [connector doc](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/connector/iceberg.rst)
- **Kubernetes (R5):** the official chart lives in the separate `prestodb/presto-helm-charts` repo. Chart 0.4.0 has `appVersion` "0.295"; its last commit, on 2025-10-28, was "Upgrade default Presto version to 0.295". It offers `mode: cluster` with HA-cluster and resource-manager options, `coordinator.replicas: 1` and `worker.replicas: 2`. [P] — [presto-helm-charts](https://github.com/prestodb/presto-helm-charts), [values.yaml](https://github.com/prestodb/presto-helm-charts/blob/main/charts/presto/values.yaml)
- **Presto C++ catalogs:** "HIVE, NESSIE, REST, and HADOOP Iceberg catalogs are supported in Presto C++". [P] — [connector doc](https://github.com/prestodb/presto/blob/master/presto-docs/src/main/sphinx/connector/iceberg.rst)
- **Governance:** the Presto Foundation describes Presto C++ as the Velox-based replacement for Java workers, and its September 2025 workshop page shows an IBM logo among users. [S] — [prestodb.io workshop page](https://prestodb.io/events/presto-c-workshop-sept-2025/)

#### Databend
- **Releases:** GitHub's "latest" non-prerelease is v1.2.881 (2026-04-17). Nightly builds (`v1.2.963-nightly` on 2026-11-12) are published every few days, and patch tags such as `v1.2.925-patch-15` exist. [P] — [GitHub releases API](https://api.github.com/repos/databendlabs/databend/releases)
  - The Helm charts were bumped to v1.2.881 on 2026-05-15. [P] — [databendlabs/helm-charts](https://github.com/databendlabs/helm-charts)
- **Licence:** the core is Apache-2.0. Code under `src/query/ee` and `src/meta/ee` is under the Elastic License 2.0, with free and paid (license-key) tiers. [S] — [Databend licence page, via search summary](https://docs.databend.com/guides/self-hosted/editions/enterprise/license); the repo licence reads Apache-2.0 [P] — [repo API](https://api.github.com/repos/databendlabs/databend)
  - Databend is a single-vendor project (Databend Labs); 9,493 stars. [P] — same API
- **Embedded Iceberg library:** a fork, `databendlabs/iceberg-rust` on branch `dev`, with crate version "0.8.0". Its last upstream merge was on 2026-10-19 ("Merge remote-tracking branch 'origin/main' into dev"). [P] — [databend Cargo.toml](https://github.com/databendlabs/databend/blob/main/Cargo.toml), [fork commits](https://github.com/databendlabs/iceberg-rust/commits/dev)
- **Generic REST catalog (R1):**
  - **Documented:** `CREATE CATALOG x TYPE=ICEBERG CONNECTION=(TYPE='rest' ADDRESS='...' WAREHOUSE='...' "s3.endpoint"=... "s3.path-style-access"=... ...)`, plus HMS, Glue and S3 Tables catalog types. The documented parameter table lists only `s3.*` and `client.*` keys, with no OAuth2 keys. [P] — [Databend Iceberg doc](https://github.com/databendlabs/databend-docs/blob/main/docs/en/sql-reference/00-sql-reference/30-table-engines/02-iceberg.md)
  - **In the code:** `catalog.rs` passes every CONNECTION property through to `RestCatalogBuilder`. The fork's REST client reads `token`, `credential` (a client-credentials POST to `oauth2-server-uri`, or else `/v1/oauth/tokens`), `scope`, `audience`, `resource` and any `header.*`. [P] — [catalog.rs](https://github.com/databendlabs/databend/blob/main/src/query/storages/iceberg/src/catalog.rs), [fork rest/catalog.rs](https://github.com/databendlabs/iceberg-rust/blob/dev/crates/catalog/rest/src/catalog.rs)
  - **End-user pass-through:** none found; the catalog is defined once per Databend catalog object. [P] — same files
- **Vended credentials (R2):**
  - Databend's `credential.rs` implements `VendedCredential`. It reads `s3.access-key-id`, `s3.secret-access-key`, `s3.session-token` and `expires-at-ms` from the table's FileIO properties, and refreshes them by reloading the table, five minutes (or a tenth of the lifetime) before expiry. [P] — [credential.rs](https://github.com/databendlabs/databend/blob/main/src/query/storages/iceberg/src/credential.rs)
  - **Merge order:** the fork's `load_table` builds FileIO from `response.config` chained with the user's catalog properties, so user-set keys override server-vended keys. [P] — [fork rest/catalog.rs](https://github.com/databendlabs/iceberg-rust/blob/dev/crates/catalog/rest/src/catalog.rs)
- **Nested namespaces (R3):** `catalog.rs` lists namespaces with `list_namespaces(None)` (top level only) and builds every database identifier with `NamespaceIdent::new(name)`, a single level. [P] — [catalog.rs](https://github.com/databendlabs/databend/blob/main/src/query/storages/iceberg/src/catalog.rs)
- **v2 reads (R4):** Databend maps Iceberg `PositionDeletes` and `EqualityDeletes` to its own Parquet `DeleteTask`s (`DeleteType::Position` and `DeleteType::Equality`, with `equality_ids`). [P] — [partition.rs](https://github.com/databendlabs/databend/blob/main/src/query/storages/iceberg/src/partition.rs)
- **v3 deletion vectors:**
  - The fork's delete loader still carries `// TODO: Delete Vector loader from Puffin files`. [P] — [fork caching_delete_file_loader.rs](https://github.com/databendlabs/iceberg-rust/blob/dev/crates/iceberg/src/arrow/caching_delete_file_loader.rs)
  - Upstream iceberg-rust added deletion-vector reading, "A V3 deletion vector arrives as a PositionDeletes entry whose deletes live in a Puffin blob", and the feature is present in v0.11.0 (released 2026-11-03) but not v0.10.1. [P] — [iceberg-rust v0.11.0 caching_delete_file_loader.rs](https://github.com/apache/iceberg-rust/blob/v0.11.0/crates/iceberg/src/arrow/caching_delete_file_loader.rs), [iceberg-rust releases](https://api.github.com/repos/apache/iceberg-rust/releases)
- **Types:** the mapping covers BOOLEAN, INT, LONG, DATE, TIMESTAMP/TIMESTAMPTZ, FLOAT, DOUBLE, STRING/BINARY, DECIMAL, ARRAY, MAP and STRUCT, and "Databend does not currently support Iceberg data types that are not listed". UUID, TIME, nanosecond timestamps, variant and geo types are not listed. [P] — [Databend Iceberg doc](https://github.com/databendlabs/databend-docs/blob/main/docs/en/sql-reference/00-sql-reference/30-table-engines/02-iceberg.md)
- **Writes:** `CREATE TABLE ... ENGINE = ICEBERG` with `PARTITION BY`, and `INSERT INTO`. No DELETE, UPDATE or MERGE is documented. [P] — same doc
- **Kubernetes (R5):** two charts, `databend-meta` (`replicaCount: 1` by default, persistence `size: 10Gi`) and `databend-query` (`replicaCount: 1`, configured with meta endpoints). [P] — [helm-charts repo](https://github.com/databendlabs/helm-charts)

#### Apache Impala
- **Releases:** 4.5.0 (tagged 2025-03-07), 4.5.2 (2026-09-14) and 4.5.3 (2026-10-06); master is `IMPALA_VERSION=5.0.0-SNAPSHOT`. [P] — [GitHub releases API](https://api.github.com/repos/apache/impala/releases), [impala-config.sh on master](https://github.com/apache/impala/blob/master/bin/impala-config.sh)
- **Released 4.5.3:** embeds `APACHE_ICEBERG_VERSION=1.4.3`. It has no `impala_iceberg_rest_catalog.xml` doc page (HTTP 404 at the 4.5.3 and 4.5.0 tags) and no REST catalog classes in its source tree. Master adds `IcebergRESTCatalog.java`, `RESTCatalogProperties.java` and `IcebergRestDdlRouter.java`. [P] — [impala-config.sh at 4.5.3](https://github.com/apache/impala/blob/4.5.3/bin/impala-config.sh), [tree at 4.5.3](https://github.com/apache/impala/tree/4.5.3), [IcebergRESTCatalog.java on master](https://github.com/apache/impala/blob/master/fe/src/main/java/org/apache/impala/catalog/iceberg/IcebergRESTCatalog.java)
- **Master's Iceberg version:** `APACHE_ICEBERG_VERSION=1.11.0`; the CDP build is 1.10.1.7.3.2. [P] — [impala-config.sh on master](https://github.com/apache/impala/blob/master/bin/impala-config.sh)
- **REST catalog on master (R1):**
  - **Standalone mode:** a "Standalone REST Catalog (No HMS/Catalogd)" mode uses `--use_local_catalog=true --catalogd_deployed=false --catalog_config_dir=...`, with one `.properties` file per catalog. A hybrid mode with HMS also exists.
  - **OAuth2:** `iceberg.rest-catalog.security=OAUTH2`, `iceberg.rest-catalog.oauth2.credential` and `iceberg.rest-catalog.oauth2.server-uri`, with `${ENV:VAR}` substitution.
  - **Documented limitations:** "Vended Credentials: The vended-credentials-enabled property must be set to false". "User Sessions: Only NONE session type is supported". "Nested Namespaces: The nested-namespace-enabled property must be set to false".
  - **Writes and DDL:** writes are limited to INSERT, INSERT OVERWRITE, UPDATE, DELETE, MERGE and OPTIMIZE, and need a unique `iceberg.rest-catalog.name`. DDL is limited to DROP TABLE. Views are not supported.
  - [P] — [impala_iceberg_rest_catalog.xml, master](https://github.com/apache/impala/blob/master/docs/topics/impala_iceberg_rest_catalog.xml)
- **v2 and v3 on master (R4):**
  - **v2:** Impala reads position and equality deletes, with restrictions on equality deletes, and writes only position deletes.
  - **v3:** "Starting in Impala 5.0" Impala reads v3 tables, and its DELETE, UPDATE and MERGE write deletion vectors. It also reads deletion vectors from other engines.
  - **Not supported:** it "cannot load tables with timestamp_ns, timestamptz_ns, unknown, geometry or geography columns". It reads unshredded VARIANT but cannot write it, and has no row-lineage support for Avro files.
  - **Upgraded tables:** a v3 table upgraded from v2 that still holds v2 position-delete files rejects DELETE and UPDATE until `OPTIMIZE TABLE` runs.
  - [P] — [impala_iceberg.xml, master](https://github.com/apache/impala/blob/master/docs/topics/impala_iceberg.xml)
- **Kubernetes (R5):** master has `helm/impala` ("Impala on Kubernetes (Helm and Operator)"), with core templates `statestored-*`, `catalogd-*`, `impalad-*` and `hms-*`, an `ImpalaCluster` operator workflow, optional Kudu, Ranger and LDAP, and a ReadWriteMany warehouse PVC by default. It is not present at the 4.5.3 tag (404). [P] — [helm/impala/README.md, master](https://github.com/apache/impala/blob/master/helm/impala/README.md)
- **Cloudera's version:** Cloudera Data Warehouse ships REST catalog support in its Impala 2025.0.21.0 build as a technical preview, "read path only". [S] — [Cloudera DW preview features](https://docs.cloudera.com/data-warehouse/cloud/release-notes/topics/dw-public-cloud-tp-r44.html)
- **Licence and activity:** Apache-2.0, 1,295 stars, last push 2026-11-12. [P] — [repo API](https://api.github.com/repos/apache/impala)

#### Apache Hive 4.x
- **Releases (dated on the website):**
  - 4.1.0 (31 Jul 2025) shipped "a REST-based catalog server backed by the Hive Metastore", that is, Hive as a REST catalog server.
  - 4.2.0 (23 Nov 2025) requires JDK 21 and adds deletion vectors, auto compaction, ViewCatalog, column defaults, an "Iceberg REST Catalog client", Z-ordering and the variant type.
  - 4.2.1 (24 Aug 2026) contains security fixes.
  - [P] — [hive.apache.org downloads](https://hive.apache.org/general/downloads/)
- **Embedded Iceberg Java:** 1.9.1 in 4.1.0, 4.2.0 and 4.2.1; master (4.3.0-SNAPSHOT) pins 1.12.0. [P] — [iceberg/pom.xml at rel/release-4.2.1](https://github.com/apache/hive/blob/rel/release-4.2.1/iceberg/pom.xml), [iceberg/pom.xml on master](https://github.com/apache/hive/blob/master/iceberg/pom.xml)
- **REST client configuration (R1):** set `metastore.client.impl=org.apache.iceberg.hive.client.HiveRESTCatalogClient`, `iceberg.catalog.<name>.type=rest`, `iceberg.catalog.<name>.uri`, `...rest.auth.type=oauth2`, `...oauth2-server-uri` (Keycloak in the example) and `...credential=client:secret`, with `...scope` optional.
  - In the quickstart only HiveServer2 runs; there is no separate metastore process.
  - The page has no vended-credential or S3 endpoint settings.
  - [P, read via WebFetch] — [Hive 4.2.0 REST Catalog quickstart](https://hive.apache.org/docs/latest/quickstart-rest-catalog/)
  - `HiveRESTCatalogClient` is in `iceberg/iceberg-catalog` at 4.2.1; it moved to its own module on master on 2026-07-24 (HIVE-29750). [P] — [tree at rel/release-4.2.1](https://github.com/apache/hive/tree/rel/release-4.2.1/iceberg/iceberg-catalog), [module history](https://github.com/apache/hive/commits/master/iceberg/iceberg-rest-catalog-client)
- **Vended credentials (R2):** master's `RestCatalogAccessDelegation` reads `iceberg.catalog.<name>.header.X-Iceberg-Access-Delegation` and recognises `vended-credentials` and `remote-signing`. [P] — [RestCatalogAccessDelegation.java, master](https://github.com/apache/hive/blob/master/iceberg/iceberg-rest-catalog-client/src/main/java/org/apache/iceberg/hive/rest/catalog/RestCatalogAccessDelegation.java)
- **Recent work:** HIVE-30056, "client side support for REST catalog server-side scan planning", landed 2026-11-04. [P] — [module history](https://github.com/apache/hive/commits/master/iceberg/iceberg-rest-catalog-client)
- **Nested namespaces (R3):** `HiveRESTCatalogClient` lists `listNamespaces(Namespace.empty())`, filters on `namespace.levels()[0]`, and builds tables and namespaces with `Namespace.of(dbName)`, a single level. [P] — [HiveRESTCatalogClient.java, master](https://github.com/apache/hive/blob/master/iceberg/iceberg-rest-catalog-client/src/main/java/org/apache/iceberg/hive/rest/catalog/client/HiveRESTCatalogClient.java)
- **Kubernetes (R5):** the repo has no official Helm chart, only `itests/test-docker/k8s/*` and `itests/test-docker/helm/ozone` test manifests. [P] — [apache/hive tree on master](https://github.com/apache/hive/tree/master/itests/test-docker)
- **Licence and activity:** Apache-2.0, 6,039 stars. [P] — [repo API](https://api.github.com/repos/apache/hive)

#### Apache Kyuubi (multi-user gateway in front of Spark)
- **Releases:** 1.12.0 (2026-07-28), 1.11.1 (2026-04-13), 1.11.0 (2026-02-14) and 1.10.3 (2025-12-26). [P] — [GitHub releases API](https://api.github.com/repos/apache/kyuubi/releases)
- **Licence and activity:** Apache-2.0, 2,379 stars. [P] — [repo API](https://api.github.com/repos/apache/kyuubi)
- **Iceberg:** Kyuubi has no Iceberg client of its own. Iceberg comes from the Spark engine and the `iceberg-spark-runtime` you bundle, so R1–R4 equal Spark's (assessed elsewhere). [P] — [Kyuubi Spark Iceberg connector doc](https://github.com/apache/kyuubi/blob/master/docs/connector/spark/iceberg.md)
- **Client authentication:** `kyuubi.authentication` takes NOSASL, NONE, KERBEROS, CUSTOM, JDBC or LDAP. HTTP bearer authentication works only through a user-supplied `TokenAuthenticationProvider` class (`kyuubi.authentication.custom.bearer.class`). The settings reference has no built-in JWT or OIDC validator. [P] — [settings.md](https://github.com/apache/kyuubi/blob/master/docs/configuration/settings.md), [authentication.md](https://github.com/apache/kyuubi/blob/master/docs/security/authentication.md)
- **Impersonation:** `kyuubi.engine.doAs.enabled=true` by default, so Spark engines launch as the session user. [P] — [settings.md](https://github.com/apache/kyuubi/blob/master/docs/configuration/settings.md)
- **Share levels:** CONNECTION (one engine per session), USER (the default; one per user), GROUP, SERVER_LOCAL and SERVER. [P] — [engine_share_level.md](https://github.com/apache/kyuubi/blob/master/docs/deployment/engine_share_level.md)
- **Per-user configuration:** per-user defaults use `___{username}___.{key}`, and they are overridden by settings in the JDBC URL or by SET. `kyuubi.session.conf.restrict.list` and `ignore.list` can block or strip keys sent by clients. [P] — [settings.md](https://github.com/apache/kyuubi/blob/master/docs/configuration/settings.md)
- **Client protocols:** THRIFT_BINARY and THRIFT_HTTP (HiveServer2-compatible JDBC/ODBC), REST (experimental) and TRINO (Trino-compatible HTTP, experimental). [P] — [settings.md](https://github.com/apache/kyuubi/blob/master/docs/configuration/settings.md)
- **Kubernetes (R5):** an in-repo chart `charts/kyuubi` (version 0.1.0, `appVersion` 1.12.0) runs a StatefulSet with `replicaCount: 2`. Resources are empty by default; the commented example uses 2 CPU/4Gi requests and 4 CPU/10Gi limits. [P] — [values.yaml](https://github.com/apache/kyuubi/blob/master/charts/kyuubi/values.yaml), [Chart.yaml](https://github.com/apache/kyuubi/blob/master/charts/kyuubi/Chart.yaml)
- **HA:** HA uses ZooKeeper. Without `kyuubi.ha.addresses`, each server starts an embedded ZooKeeper; "For production deployment purpose, an external zookeeper cluster is required". [P] — [high_availability_guide.md](https://github.com/apache/kyuubi/blob/master/docs/deployment/high_availability_guide.md)

#### Spark accelerators (add-ons)
- **Apache Gluten (Velox):** graduated to an ASF top-level project in March 2026 (the ASF news release is dated 2026-03-05). Releases: 1.5.0 (2025-10-29), 1.6.0 (2026-03-10) and 1.7.0 (2026-08-26). Apache-2.0. [S] — [ASF news](https://news.apache.org/foundation/entry/the-apache-software-foundation-graduates-two-open-source-projects-from-incubator); [P] — [releases API](https://api.github.com/repos/apache/incubator-gluten/releases)
  - It builds against Iceberg 1.10.0 (main and v1.7.0 `pom.xml`). [P] — [gluten pom.xml](https://github.com/apache/gluten/blob/main/pom.xml)
  - **Iceberg on Velox:**
    - The doc says "Now only read is supported", although the same page describes Parquet native writes behind `spark.gluten.sql.columnar.iceberg.enableNativeWrite`.
    - Unpartitioned tables offload with no deletes or with position deletes. Equality deletes fall back to Spark, and partitioned tables "Fallback mostly".
    - Avro is not supported, timestamptz in ORC throws an exception, and only SELECT is supported.
    - Catalog handling and table creation fall back to Spark, so the REST catalog is Spark's.
    - Deletion vectors are not mentioned.
    - [P] — [VeloxIceberg.md](https://github.com/apache/gluten/blob/main/docs/backends/velox/get-started/VeloxIceberg.md)
- **Apache DataFusion Comet:** releases 1.0.0 (2026-08-07), 1.1.0 (2026-10-06) and 1.2.0 (2026-11-03). [P] — [releases API](https://api.github.com/repos/apache/datafusion-comet/releases)
  - **Native reader:** Comet's native Iceberg reader uses iceberg-rust (pinned by git revision in `native/Cargo.toml`) and is on by default. It supports table spec v1, v2 and v3, positional and equality deletes, mixed delete types and "Deletion vectors (v3)". It is tested with Hadoop, Hive and REST catalogs and with Iceberg 1.5–1.11. [P] — [Comet iceberg.md](https://github.com/apache/datafusion-comet/blob/main/docs/source/user-guide/latest/iceberg.md), [native/Cargo.toml at 1.2.0](https://github.com/apache/datafusion-comet/blob/1.2.0/native/Cargo.toml)
  - **Storage access:** the reader has its own Rust object-store client, configured from the catalog's `s3.*` properties or `fs.s3a.*`. A scan whose files span more than one bucket falls back to Spark. The native write path (off by default) passes "the table's FileIO properties, e.g. REST-vended credentials". [P] — same doc

#### Apache Pinot and StarTree
- **Apache Pinot:** 1.6.0 was released 2026-10-23. The master source tree has 0 paths containing "iceberg", so Apache Pinot itself has no Iceberg connector. [P] — [releases API](https://api.github.com/repos/apache/pinot/releases), [apache/pinot tree](https://github.com/apache/pinot)
- **StarTree external tables** (StarTree Cloud, proprietary):
  - Every catalog is reached through one connector, `catalogType=iceberg-rest`. The generic Iceberg REST catalog is "API" onboarding from version 0.16.0.
  - Catalog authentication is a bearer token or OAuth2 client credentials.
  - "Catalog-vended credentials" are ✅ for Unity Catalog only and ❌ for Generic REST.
  - Iceberg v1 and v2 are supported; "v2/v3 row deletes (deletion vectors)" are a ⚠️ Preview.
  - [P] — [StarTree support matrix](https://docs.startree.ai/corecapabilities/external-table/support-matrix.md)

#### Apache Druid
- **Releases:** 38.0.0 was released 2026-10-01; 37.0.0 on 2026-05-08. [P] — [releases API](https://api.github.com/repos/apache/druid/releases)
- **Iceberg:** `druid-iceberg-extensions` (contrib) is an ingestion input source only. It supports Hive, local, REST and Glue catalogs, and "Security credentials may be provided in the `catalogProperties` object".
  - It "extracts all the underlying live data files up to the latest snapshot". S3 reads use static Hadoop S3A keys (`fs.s3a.access.key`, `fs.s3a.endpoint`).
  - Known limitations: it "does not fully utilize the Iceberg features such as snapshotting or schema evolution", and it does not track the last ingested snapshot.
  - [P] — [Druid iceberg.md](https://github.com/apache/druid/blob/master/docs/development/extensions-contrib/iceberg.md)

#### RisingWave
- **Releases:** v3.1.1 (2026-11-05), v3.0.5 (2026-10-12) and v3.1.0 (2026-09-21). Apache-2.0, 9,411 stars. [P] — [releases API](https://api.github.com/repos/risingwavelabs/risingwave/releases), [repo API](https://api.github.com/repos/risingwavelabs/risingwave)
- **Iceberg libraries:** a fork, `risingwavelabs/iceberg-rust`, pinned at revision 6e974ce (a commit dated 2026-10-29). Java Iceberg runs through a JNI connector node for the `rest` (default), `glue`, `hive`, `jdbc` and `snowflake` catalogs. With `vended_credentials` set, or `catalog.type='rest_rust'`, RisingWave uses the native Rust REST client instead. [P] — [Cargo.toml](https://github.com/risingwavelabs/risingwave/blob/main/Cargo.toml), [connector_common/iceberg/mod.rs](https://github.com/risingwavelabs/risingwave/blob/main/src/connector/src/connector_common/iceberg/mod.rs)
- **REST catalog (R1):** options are `catalog.credential` (OAuth2), `catalog.token` (bearer), `catalog.oauth2_server_uri`, `catalog.scope`, `catalog.security` (none, oauth2 or google) and custom headers. There is no per-end-user pass-through; credentials are set per SOURCE, SINK or CONNECTION. [P] — [catalogs.mdx](https://github.com/risingwavelabs/risingwave-docs/blob/main/iceberg/catalogs.mdx)
- **Vended credentials (R2):** `vended_credentials = true` was "Added in v2.7.0" and sends `X-Iceberg-Access-Delegation: vended-credentials`. [P] — [catalogs/rest.mdx](https://github.com/risingwavelabs/risingwave-docs/blob/main/iceberg/catalogs/rest.mdx), [mod.rs](https://github.com/risingwavelabs/risingwave/blob/main/src/connector/src/connector_common/iceberg/mod.rs)
  - The documented vended examples still set `s3.endpoint`, `s3.region` and `s3.path.style.access` explicitly. In the code, `s3.endpoint` and path style are added to the Iceberg configuration only when the user sets them. [P] — same files
- **Nested namespaces (R3):** `identifier_parts()` splits `database.name` on `.`, so `database.name='a.b.c'` addresses a multi-level namespace. [P] — [mod.rs](https://github.com/risingwavelabs/risingwave/blob/main/src/connector/src/connector_common/iceberg/mod.rs)
- **Batch reads and deletes (R4):** ad hoc snapshot queries on an Iceberg SOURCE are supported, and one-time or full-reload reads apply equality and position deletes. Deletion vectors are documented only for "streaming updates", and only for tables written by RisingWave's own PK-index sink. [P] — [iceberg-feature-support.mdx](https://github.com/risingwavelabs/risingwave-docs/blob/main/iceberg/iceberg-feature-support.mdx)
- **Writes:** append-only and upsert sinks, and internal `ENGINE = iceberg` tables. "Iceberg format v2" is the default format version. Automatic compaction needs a dedicated Iceberg compactor node. [P] — same page
- **Kubernetes (R5):** a Helm chart (`risingwavelabs/helm-charts`) and an Operator with CRDs. [P] — [risingwave-k8s-helm.mdx](https://github.com/risingwavelabs/risingwave-docs/blob/main/deploy/risingwave-k8s-helm.mdx), [risingwave-kubernetes.mdx](https://github.com/risingwavelabs/risingwave-docs/blob/main/deploy/risingwave-kubernetes.mdx)
  - It needs a meta store (PostgreSQL) and a state store (object storage); the chart can bundle PostgreSQL and MinIO. [P] — same pages
  - **Sizing:** memory-intensive, with a 1:4 CPU-to-memory ratio. Compute nodes need at least 2 CPU and 8 GiB (≥4 CPU and ≥16 GiB recommended); compactors need at least 1 CPU and 4 GiB. Requests should equal limits. [P] — [hardware-requirements.mdx](https://github.com/risingwavelabs/risingwave-docs/blob/main/deploy/hardware-requirements.mdx)
- **Client protocol:** PostgreSQL wire; the deploy docs require `psql`. [P] — [risingwave-kubernetes.mdx](https://github.com/risingwavelabs/risingwave-docs/blob/main/deploy/risingwave-kubernetes.mdx)

#### Apache Drill and Apache Kylin
- **Drill:** 1.22.0 was released 2025-06-29 and 1.21.2 on 2024-06-23. [P] — [releases API](https://api.github.com/repos/apache/drill/releases)
  - Its Iceberg format plugin treats "the Iceberg table [as] a folder with data and metadata files" on a storage plugin path, and checks for the `metadata` folder. The README describes no catalog, REST or otherwise. [P] — [format-iceberg README](https://github.com/apache/drill/blob/master/contrib/format-iceberg/README.md)
- **Kylin:** the last release is 5.0.2 (2025-04-06). The `kylin5` branch tree has no Iceberg files. [P] — [releases API](https://api.github.com/repos/apache/kylin/releases), [kylin5 tree](https://github.com/apache/kylin/tree/kylin5)

### Inferences
- **R1 summary.** Only PrestoDB has any per-user mechanism, and it is Trino's old `session=USER` design: a Presto-minted unsigned JWT that nuna would have to accept through a token exchange.
  - Presto also forwards extra credentials into the Iceberg `SessionContext`. Iceberg's OAuth2 session logic treats a `token` credential as a bearer token, so `--extra-credential token=<user JWT>` from the client could make Presto send the user's own JWT to nuna. Untested; it depends on Iceberg 1.10.1/1.11 `OAuth2Manager` behaviour.
  - Kyuubi can approximate pass-through with share level CONNECTION. A client puts `spark.sql.catalog.nuna.token=<JWT>` in the JDBC URL, so its private Spark engine talks to nuna as that user.
    - Token expiry during a long session is not handled.
    - Under share level USER, the first session's token is used by every later session of that user.
  - Impala (REST: NONE only), Databend, RisingWave, Hive and StarTree have no pass-through.
- **R2 summary.**
  - **Presto would fail with vended-only setups.** It needs static `hive.s3.*` keys plus the endpoint. The credentials nuna returns are stored but not used.
  - **Databend should use nuna's vended `s3.endpoint` and `s3.path-style-access`** if CREATE CATALOG omits them, because server config is merged first and user keys override it.
    - It has to send the delegation header, presumably via `"header.X-Iceberg-Access-Delegation"='vended-credentials'` in CONNECTION. That is undocumented and untested.
    - The fork only reads the `config` map of `LoadTableResult`. Credentials returned only in `storage-credentials` may be ignored.
  - **RisingWave probably honours nuna's vended endpoint when `s3.endpoint` is omitted,** through the iceberg-rust merge. Its docs always repeat the endpoint, so this is the exact StarRocks trap to test.
  - **Impala and StarTree (generic REST) do not do vended credentials at all.**
  - **Hive 4.2.x is unknown;** master detects the header.
  - **Kyuubi, Gluten and Comet follow Spark.** Comet's native reader configures its own object store; its write path forwards `table.io().properties()`. Whether its read path uses the vended `s3.endpoint` needs a test.
- **R3 summary.** Nested namespaces work in Presto (on by default) and RisingWave (the dotted `database.name`), and via Spark in Kyuubi, Gluten and Comet. They do not work in Impala (documented "must be false"), and in Databend and Hive (single-level code paths).
- **R4: v3 deletion vectors are the sharpest divider.**
  - Comet (iceberg-rust with DVs), Impala master (5.0) and Hive 4.2 (its release note lists DVs) read them, and Spark via Kyuubi does too.
  - Presto writes DVs only on C++ workers. Its Java worker's DV read path is not documented.
  - Databend's fork predates upstream iceberg-rust's DV reader (v0.11.0, 2026-11-03), so it will likely fail on, or mishandle, a table with deletion vectors. This is untested.
  - RisingWave documents DVs only for its own sink's tables in streaming mode.
  - Druid's input source lists "live data files" and never mentions delete files. It probably ingests deleted rows from merge-on-read tables, which would be a silent-correctness risk.
- **Embedded Iceberg version implications.**
  - Presto 0.300 (Iceberg 1.10.1), Impala master (1.11.0) and Hive master (1.12.0) are new enough for v3 metadata. Hive 4.2.x (1.9.1) supports v3 DVs in the Java library, but 1.9.1 predates Iceberg 1.10's v3 type and row-lineage completeness.
  - Impala 4.5.x (1.4.3) cannot read v3 at all.
  - The Rust engines depend on fork revisions: Databend's is older than upstream 0.11.0; RisingWave's revision date is 2026-10-29.

### Gaps
- **Presto:**
  - Not determined: whether 0.300 (released 2026-11-04) contains `PrestoRESTFileIO` (#28343, merged 2026-10-09), and whether Presto's Java worker reads v3 deletion vectors.
  - The master doc is internally inconsistent: C++ `INSERT INTO` is listed as "No" while v3 DV writes "require" C++. Presto C++ Docker image names, sizes and air-gapped steps were not checked.
- **Databend:**
  - Client protocols were not verified in this session. MySQL wire, HTTP handler and Arrow Flight SQL are believed supported from prior knowledge only.
  - The metadata service's Raft footprint and the upgrade procedure were not read. Whether `header.*` really reaches the HTTP client from CREATE CATALOG was not tested.
- **Impala:** there is no evidence of a 5.0 release date or vote. A search found nothing, and the Apache board minutes only mention REST catalog work.
- **Hive:**
  - Not checked: whether 4.2.x's `HiveRESTCatalogClient` honours vended credentials or the vended endpoint, and how Hive executes queries on Kubernetes without YARN (Tez local mode or LLAP).
  - An official Hive Helm chart was not found.
- **Kyuubi:** not checked whether the Kyuubi Trino-protocol frontend supports bearer tokens end to end.
- **Gluten:** whether Gluten handles v3 deletion vectors or falls back is not documented.
- **Comet:** whether the native read path picks up the per-table vended `s3.endpoint` is stated only for writes.
- **RisingWave:**
  - Not checked: whether batch, ad hoc queries apply deletion vectors from tables written by other engines, and whether RisingWave can create v3 tables.
  - The Java Iceberg version in its JNI connector node was not found in `java/pom.xml`.
- **StarTree:** BYOC or self-hosted Kubernetes availability, licence terms and pricing were not researched.
- **Out of scope here:** other engines (Sail, Daft, DataFusion Ballista and similar) appear to be covered by a parallel researcher, judging by the shared scratchpad, and are not assessed here.

## Q2. Which are easier to operate than Trino, and why?

### Takeaway
None is clearly easier than Trino while also meeting nuna's requirements. Databend is the lightest to run: two Rust services and no JVM, though the metadata service holds state. It gives that up in nested namespaces, deletion vectors and documented OAuth. PrestoDB has the same coordinator-and-worker shape as Trino, plus a native-worker variant. Impala master can now run without HMS or catalogd, but that is unreleased. Hive, Kyuubi+Spark and RisingWave all need more moving parts than Trino.

### Cited Findings
- **Presto:** a coordinator plus workers, with optional HA mode and a resource manager. The official chart still defaults to 0.295 and was last touched 2025-10-28. [P] — [presto-helm-charts values.yaml](https://github.com/prestodb/presto-helm-charts/blob/main/charts/presto/values.yaml)
- **Databend:** a `databend-meta` chart (persistent volume, 10Gi default) and a stateless-style `databend-query` chart. [P] — [databendlabs/helm-charts](https://github.com/databendlabs/helm-charts)
- **Impala:** core services are statestored, catalogd, impalad and HMS. The chart defaults to a ReadWriteMany shared warehouse PVC across HMS, catalogd and impalad. [P] — [helm/impala/README.md](https://github.com/apache/impala/blob/master/helm/impala/README.md)
  - The REST "standalone" mode removes HMS and catalogd, but only on master. [P] — [impala_iceberg_rest_catalog.xml](https://github.com/apache/impala/blob/master/docs/topics/impala_iceberg_rest_catalog.xml)
- **Hive:** the REST client still runs HiveServer2, and there is no official Helm chart. [P] — [Hive REST quickstart](https://hive.apache.org/docs/latest/quickstart-rest-catalog/), [hive tree](https://github.com/apache/hive/tree/master/itests/test-docker)
- **Kyuubi:** a StatefulSet of Kyuubi servers (2 replicas by default), plus an external ZooKeeper for production HA, plus Spark driver and executor pods per engine. Engine count depends on the share level: one per connection, user or group. [P] — [values.yaml](https://github.com/apache/kyuubi/blob/master/charts/kyuubi/values.yaml), [high_availability_guide.md](https://github.com/apache/kyuubi/blob/master/docs/deployment/high_availability_guide.md), [engine_share_level.md](https://github.com/apache/kyuubi/blob/master/docs/deployment/engine_share_level.md)
- **RisingWave:** meta, compute, compactor (and frontend) nodes, a PostgreSQL meta store and an object-store state store. An extra dedicated Iceberg compactor is needed for automatic Iceberg maintenance. There is a 1:4 CPU-to-memory sizing rule, and requests should equal limits. [P] — [risingwave-k8s-helm.mdx](https://github.com/risingwavelabs/risingwave-docs/blob/main/deploy/risingwave-k8s-helm.mdx), [hardware-requirements.mdx](https://github.com/risingwavelabs/risingwave-docs/blob/main/deploy/hardware-requirements.mdx), [iceberg-feature-support.mdx](https://github.com/risingwavelabs/risingwave-docs/blob/main/iceberg/iceberg-feature-support.mdx)

### Inferences
- **Databend** is easier than Trino in footprint and tuning: Rust binaries with no JVM heap or GC tuning, and query nodes that scale freely.
  - It adds a stateful Raft metadata cluster that Trino does not need.
  - Its nightly-first release model (the latest stable GitHub release is April 2026) makes upgrade pinning harder.
- **PrestoDB** is about the same as Trino: the same architecture, Java config files and a JVM.
  - Presto C++ adds a second worker image built on Velox, and requires the coordinator-side catalog config.
  - The stale chart means you would maintain your own manifests.
- **Impala 5.0 standalone REST mode** could be as simple as "impalad only", but it is unreleased and the chart still ships HMS.
  - Air-gapped builds are heavy: a C++ backend plus a Java frontend.
- **Kyuubi+Spark** is harder than Trino: ZooKeeper, engine lifecycle and TTLs, Spark images, and RBAC for pod creation.
  - In return it gives Kerberos-style per-user engines (impersonation) and Spark's complete Iceberg client.
- **RisingWave** is harder than Trino for a pure batch-SQL service: four or more node roles, PostgreSQL and an object-store state store. It makes sense only if streaming into or out of Iceberg is also wanted.
- **Hive** is the hardest: HiveServer2, Tez, optionally HMS, and no chart.
- **Air-gapped install.** Everything here ships as container images. All but Databend and RisingWave pull JVM jars at build time. None needs a package index at runtime if the images are mirrored.

### Gaps
- No primary benchmark of memory per node or idle footprint was found for any of these engines except RisingWave's sizing page.
- Upgrade procedures (rolling upgrades, metadata migrations for Databend meta and the RisingWave meta store) were not read.

## Q3. Which are actively maintained as of November 2026, and which are fading?

### Takeaway
Active, with a release in the last three months: PrestoDB, Databend (nightly), Comet, RisingWave, Pinot, Druid, Impala (4.5.x maintenance, 5.0 under development) and Gluten. Kyuubi and Hive are slower but alive (July and August 2026 releases, with active master branches). Fading: Drill (last release June 2025, about one a year) and Kylin (last release April 2025, no Iceberg). The Presto Helm chart repo is also lagging, a year behind the engine.

### Cited Findings
- **Releases in the last three months:**
  - PrestoDB 0.300 on 2026-11-04. [P] — [releases API](https://api.github.com/repos/prestodb/presto/releases)
  - Databend nightly v1.2.963 on 2026-11-12; latest stable v1.2.881 on 2026-04-17. [P] — [releases API](https://api.github.com/repos/databendlabs/databend/releases)
  - Comet 1.2.0 on 2026-11-03. [P] — [releases API](https://api.github.com/repos/apache/datafusion-comet/releases)
  - RisingWave v3.1.1 on 2026-11-05. [P] — [releases API](https://api.github.com/repos/risingwavelabs/risingwave/releases)
  - Pinot 1.6.0 on 2026-10-23. [P] — [releases API](https://api.github.com/repos/apache/pinot/releases)
  - Druid 38.0.0 on 2026-10-01. [P] — [releases API](https://api.github.com/repos/apache/druid/releases)
  - Impala 4.5.3 on 2026-10-06. [P] — [releases API](https://api.github.com/repos/apache/impala/releases)
  - Gluten 1.7.0 on 2026-08-26 (top-level project since March 2026). [P] — [releases API](https://api.github.com/repos/apache/incubator-gluten/releases); [S] — [ASF news](https://news.apache.org/foundation/entry/the-apache-software-foundation-graduates-two-open-source-projects-from-incubator)
- **Slower but alive:**
  - Kyuubi 1.12.0 on 2026-07-28. [P] — [releases API](https://api.github.com/repos/apache/kyuubi/releases)
  - Hive 4.2.1 on 24 Aug 2026; REST catalog commits on master up to 2026-11-04. [P] — [Hive downloads](https://hive.apache.org/general/downloads/), [commit history](https://github.com/apache/hive/commits/master/iceberg/iceberg-rest-catalog-client)
- **Fading:**
  - Drill 1.22.0 on 2025-06-29, after 1.21.2 on 2024-06-23. [P] — [releases API](https://api.github.com/repos/apache/drill/releases)
  - Kylin 5.0.2 on 2025-04-06. [P] — [releases API](https://api.github.com/repos/apache/kylin/releases)
- **Presto Helm chart:** last commit 2025-10-28; `appVersion` 0.295. [P] — [presto-helm-charts](https://github.com/prestodb/presto-helm-charts)
- **Repo activity:** every repo above except RisingWave, whose push date was not fetched, showed a push on 2026-11-11 or 2026-11-12 in the GitHub repo API. Stars: Presto 16,773, Druid 14,097, Databend 9,493, RisingWave 9,411, Pinot 6,175, Hive 6,039, Kylin 3,785, Kyuubi 2,379, Drill 2,027, Gluten 1,623, Comet 1,321, Impala 1,295. [P] — GitHub repo API, e.g. [apache/drill](https://api.github.com/repos/apache/drill)

### Inferences
- **Iceberg-relevant momentum is highest** in Presto (v3 work in every release since 0.298), Comet (DVs, REST, native writes; reached 1.0 in August 2026), Impala master (REST plus v3, but unreleased) and Hive master (REST client, server-side scan planning, Iceberg 1.12.0).
- **Governance:**
  - Databend and RisingWave are single-vendor (Databend Labs, RisingWave Labs).
  - StarTree's Iceberg support is proprietary on top of Apache Pinot.
  - Presto is governed by the Presto Foundation; IBM is a visible backer.
  - The rest are ASF projects. Impala's direction is driven largely by Cloudera, whose own build is ahead of Apache releases.
- **Drill is effectively irrelevant** for nuna: it has no catalog support, and a yearly or slower cadence.
- **Kylin is irrelevant:** it has no Iceberg code.

### Gaps
- Contributor counts and commit volume per project were not measured; push dates and release cadence are the only activity signals used here.
- Presto Foundation membership and the production users of Presto C++ in 2026 were not confirmed from a primary source; the search returned only a 2025 workshop page.

# SingleStore and Apache Iceberg REST catalogs: what SingleStore calls, in both directions

Researched 2026-11-06 for decision D35 (SingleStore bidirectional pipeline). Documentation research only; nothing was run against the `singlestoredb-dev` container.

## Bottom line

- **Ingest (Iceberg → SingleStore) is real and fully documented.** It works through `CREATE PIPELINE ... LOAD DATA S3 '<ns>.<table>' CONFIG '{"catalog_type":"REST", "catalog.uri": ...}' ... FORMAT ICEBERG`. It is continuous: `MERGE` pipelines handle append, overwrite, replace and delete snapshots, including position and equality deletes. It reads Iceberg v1 and v2 tables with Parquet data files, on S3 or S3-compatible storage only. It supports vended credentials for REST catalogs. The feature is still labelled **Preview** on both Helios and self-managed. [read: v10 and Helios Iceberg Ingest pages]
- **SingleStore passes `catalog.*` keys straight to the Apache Iceberg Java SDK.** [read: v10 page, "passed directly to the Iceberg SDK"] Our catalog therefore sees an ordinary Iceberg Java `RESTCatalog` client. The calls are `GET /v1/config`, an optional OAuth2 token call, `GET .../tables/{t}` (repeated polling), and possibly `POST .../metrics`. [inference, grounded in the Iceberg Java source I read]
- **Egress (SingleStore → Iceberg through a catalog) is not a documented feature as of November 2026.** No docs page, SQL statement or release note shows SingleStore creating or committing an Iceberg table through any catalog. Vendor marketing still describes write-to-Iceberg as "preview" and **Glue-only**. The only documented egress path is `SELECT ... INTO S3 ... FORMAT PARQUET` followed by an *external* registration step; the docs name PyIceberg `add_files`. [read: SELECT page; product page; 2024 blog]
- **Consequence for D35:** the decision ledger assumes "SingleStore-written Iceberg tables are committed through our catalog". That has no documented SingleStore mechanism behind it. For the egress leg, the committer will be our own service or PyIceberg/Iceberg-Java, not SingleStore. Its calls are `loadTable` plus `updateTable` with `add-snapshot`/`set-snapshot-ref`, under `assert-table-uuid`/`assert-ref-snapshot-id`. Ask the SingleStore account team about the private "Data sharing to Iceberg" preview before designing around it.
- **Helios is ahead in one respect.** A Helios-only "Iceberg external tables" feature (query in place) gained Hive and REST catalog support in August 2026. It has no public documentation. [read: Helios release notes, Aug 2026]
- **The 10.9.0 dev image should support ingest without a license change.** Java is pre-wired there. You still have to `SET GLOBAL enable_iceberg_ingest = ON`. [read: dev-image init.sh; inference for "should"]

## Provenance

Read in full (raw `.md` via curl, saved under `%TEMP%\claude\...\scratchpad\downloads\singlestore_iceberg\`):

- https://docs.singlestore.com/db/v10/load-data/data-sources/iceberg-ingest.md, modified 2026-09-09. Diffed line by line against https://docs.singlestore.com/cloud/load-data/data-sources/iceberg-ingest.md. `beta.docs.singlestore.com` serves a byte-identical copy of the Helios page.
- https://docs.singlestore.com/db/v10/release-notes/singlestore-memsql/10-release-notes.md, 9-0, 8-9 and 8-7 release notes, and https://docs.singlestore.com/cloud/release-notes.md. All were grepped for Iceberg, and the 10 notes were read in full.
- https://docs.singlestore.com/db/v10/reference/sql-reference/pipelines-commands/create-pipeline.md, …/data-manipulation-language-dml/select.md, …/information-schema-reference/data-ingest/pipelines-iceberg-files.md, …/load-data/about-singlestore-pipelines/pipeline-concepts/schema-and-pipeline-inference.md, …/pipelines-commands/infer-pipeline.md, …/engine-variables/list-of-engine-variables.md (v10 and Helios), …/configuration-options-for-different-sources.md, …/introduction/singlestore-editions.md
- https://docs.singlestore.com/sitemap-0.xml: 19,227 URLs. The only Iceberg pages for `/cloud/` and `/db/v10/` are `iceberg-ingest` and `pipelines-iceberg-files`. There is no external-table, egress or export page.
- Blogs, as HTML converted to text: https://www.singlestore.com/blog/bidirectional-integration-for-apache-iceberg/ (2024-06-26), https://www.singlestore.com/blog/open-intelligence-open-data-singlestore-delivers-zero-copy-compute-on-apache-iceberg/ (2026-07-02), https://www.singlestore.com/blog/iceberg-needs-an-open-catalog-not-a-walled-garden/ (2026-07-22), https://www.singlestore.com/platform/data-integration/ (current product page), https://www.singlestore.com/blog/unlocking-data-interoperability-iceberg-snowflake-singlestore/ (2025-03-27)
- GitHub: https://github.com/singlestore-labs/iceberg-lakehouse-docker (README, compose files) and https://github.com/singlestore-labs/singlestoredb-dev-image (README, CHANGELOG, Dockerfile, `scripts/init.sh`, `scripts/start.sh`, `config.json`)
- Apache Iceberg Java `main`, latest release 1.12.0 (2026-09-30): `RESTSessionCatalog.java`, `Endpoint.java`, `RESTUtil.java`, `OAuth2Properties.java`, `OAuth2Manager.java`, `OAuth2Util.java`, `AuthConfig.java`, `RESTCatalogProperties.java`, `HTTPClient.java`, `open-api/rest-catalog-open-api.yaml`. PyIceberg `main`: `pyiceberg/table/__init__.py`, `pyiceberg/table/update/snapshot.py`.
- Secondary sources, summarised by a tool and not read verbatim: ITNEXT article by Hlib Pylypets (SingleStore), 2025-02-26, https://itnext.io/fast-track-iceberg-lakehouse-deployment-docker-for-hive-rest-minio-spark-singlestore-90bdf9f960d7. A dev.to article was read in full via the dev.to API: https://dev.to/singlestore/quick-tip-using-singlestore-for-iceberg-catalog-storage-28pb. A Scribd document titled "SingleStore External Table" was not readable.

Tags below: **[read: …]** means read verbatim; **[secondary: …]** means a summary only; **[inference]** means my reasoning.

---

## 1. Ingest: Iceberg → SingleStore

### 1.1 Syntax (verbatim, v10 and Helios identical)

```sql
CREATE [OR REPLACE] PIPELINE <pipeline_name> AS
LOAD DATA S3 '[<table_identifier>]'
CONFIG '{"catalog_type": "GLUE|SNOWFLAKE|REST|JDBC|HIVE|HADOOP",
         <configuration_json>
        [, "endpoint_url": "<s3_compatible_storage_endpoint>"]
        [, "ingest_mode": "append|upsert|one_time"]
        [, "catalog_name": "<your_catalog_name>" ]
        [, “catalog.<property>” : “property_value” [, …]]
     [, "iceberg_vended_credentials":true|false]
}'
CREDENTIALS '<credentials_json>’
[REPLACE | MERGE] INTO TABLE <table_name>     
<iceberg_subvalue_mapping>
FORMAT ICEBERG;

<iceberg_subvalue_mapping>:(
{<singlestore_col_name> | @<variable_name>}<- <iceberg_subvalue_path> [, ... ])

<iceberg_subvalue_path>: {ident [::ident ...]}
```
[read: https://docs.singlestore.com/db/v10/load-data/data-sources/iceberg-ingest.md]

The curly quotes in that block are a docs defect; use straight quotes. Other pipeline clauses apply too: `BATCH_INTERVAL`, `BATCH_MATCHES_SNAPSHOT ON|OFF`, `ENABLE OFFSETS METADATA GC`, `WHERE`, `SET`. [read: CREATE PIPELINE page]

Minimal generic REST example (verbatim):
```sql
CREATE PIPELINE addresses_pipe AS
LOAD DATA S3 'db_name.table_name'
CONFIG '{"region": "us-west-2",
        "catalog_type": "REST",
        "catalog.uri": "http://host.address:8181"}'
CREDENTIALS '{"aws_access_key_id": "<your_access_key_id>",
             "aws_secret_access_key": "<your_secret_access_key>"}'
INTO TABLE addresses
  (Id <- id,
  Name <- name,
  Street <- address::street,
  City <- address::city,
  Country <- address::country)
FORMAT ICEBERG;
```
Polaris, as a REST catalog with OAuth2 (verbatim):
```sql
CREATE PIPELINE addresses_pipe AS
LOAD DATA S3 'db_name.table_name'
CONFIG '{"catalog_type": "REST",
        "catalog.warehouse": "<polaris_catalog_name>",
        "table_id": "db_name.table_name",
        "region":"us-east-1",
        "catalog.uri":"https://tpq12345.snowflakecomputing.com/polaris/api/catalog",
        "catalog.credential":"catalog.credential",
        "catalog.scope": "PRINCIPAL_ROLE:ALL"}'
CREDENTIALS '{"aws_access_key_id": "<your_access_key_id>",
           "aws_secret_access_key": "<your_secret_access_key>"}'
INTO TABLE addresses
(Id <- id,
Name <- name,
Street <- address::street,
City <- address::city,
Country <- address::country)
FORMAT ICEBERG;
```
[read: same page]

The prose under the Polaris example describes a `catalog.connection` key "formatted as `<ClientID>:<Secret>`". That is a docs error. The Iceberg SDK property is `credential` (`OAuth2Properties.CREDENTIAL = "credential"`, split on the first `:` into client id and secret), so the key to use is **`catalog.credential`**, as the example shows. [read: OAuth2Properties.java, OAuth2Util.java; inference for "docs error"]

### 1.2 `catalog_type` values

- The syntax enum lists `GLUE|SNOWFLAKE|REST|JDBC|HIVE|HADOOP`. [read]
- The docs say Polaris and Unity "are REST catalogs and are supported", so they use `"catalog_type": "REST"`. There is no `POLARIS`, `UNITY` or `NESSIE` value. [read]
- Nessie is not mentioned anywhere. It would have to work through its Iceberg REST endpoint with `REST`. [inference]
- `catalog_name` is used internally for logging and metrics. It is required only for JDBC. [read]
- The table can be named in `LOAD DATA S3 '<id>'` or with `"table_id"` in CONFIG; one example uses `LOAD DATA S3 ''` plus `"table_id"`. The identifier is "typically" `database_name.table_name`, and Snowflake uses three parts. [read]

### 1.3 Configuration keys relevant to a generic REST catalog

| Key | Where | Meaning / source |
|---|---|---|
| `catalog_type: "REST"` | CONFIG | required [read] |
| `catalog.uri` | CONFIG | base URI; the SDK appends `v1/...` [read; SDK behaviour read in ResourcePaths/RESTSessionCatalog] |
| `catalog.warehouse` | CONFIG | sent as `?warehouse=` on `GET /v1/config` [read: RESTSessionCatalog.fetchConfig] |
| `catalog.credential` | CONFIG | `client_id:client_secret` for OAuth2 client_credentials [read: Polaris example + OAuth2Util] |
| `catalog.scope` | CONFIG | OAuth2 scope; the SDK default is `catalog` [read: Polaris example; AuthConfig.java] |
| `catalog.token` | CONFIG | static bearer token; takes precedence over `credential` [inference that SingleStore passes it: the docs say all `catalog.<property>` are passed through. The SDK behaviour is read in OAuth2Manager] |
| `catalog.oauth2-server-uri` | CONFIG | external IdP token endpoint. The default is `{uri}/v1/oauth/tokens`, and the SDK warns if unset [read: OAuth2Manager/ResourcePaths; pass-through is inference] |
| `catalog.header.<Name>` | CONFIG | extra HTTP headers, e.g. `catalog.header.X-Iceberg-Access-Delegation: vended-credentials` [read: `RESTUtil.configHeaders` uses the `header.` prefix; SingleStore pass-through is inference] |
| `catalog.prefix` | — | not set by the client. The server returns `prefix` in the `/v1/config` defaults/overrides [read: SDK `ResourcePaths.forCatalogProperties(mergedProps)`] |
| `iceberg_vended_credentials: true` | CONFIG | enables use of catalog-vended S3 credentials (REST + S3 only) [read] |
| `region` | CONFIG | S3 region [read] |
| `endpoint_url` | CONFIG | S3-compatible endpoint such as MinIO or Ceph, "for all catalog types" [read] |
| `compatibility_mode` | CONFIG | S3 downloader uses calls better supported by third parties [read: configuration-options page] |
| `creds_mode: "eks_irsa"` | CONFIG | EKS IRSA, self-managed on the K8s operator only; needs `SET GLOBAL enable_eks_irsa = ON` [read] |
| `ref` | CONFIG/CREDENTIALS | AWS Secrets Manager ARN whose JSON keys are spliced in, e.g. `catalog.credential`. Use `secretsmanager_role_arn` or `"role|secret"`. Credentials used only for the secrets manager must be prefixed `secretsmanager_` [read] |
| `ignore_unsupported_modifications: true` | CONFIG | skip snapshot types the ingest mode cannot handle; the docs warn of inconsistency [read] |
| `aws_access_key_id`, `aws_secret_access_key`, `aws_session_token`, `role_arn` | CREDENTIALS | static S3 credentials or a role to assume. CREDENTIALS is optional with vended credentials or IRSA [read] |

### 1.4 Vended credentials

- The docs say SingleStore "supports vended credentials for Iceberg REST catalogs with S3 storage". Catalog-specific settings go in as `"catalog.<property>"`, plus `"iceberg_vended_credentials": true`. [read]
- The docs do not say which catalog property triggers the `X-Iceberg-Access-Delegation` header. In the Iceberg Java SDK the header is sent only if configured via `header.X-Iceberg-Access-Delegation` (`RESTUtil.configHeaders`). [read: SDK] The expected SingleStore form is therefore `"catalog.header.X-Iceberg-Access-Delegation": "vended-credentials"`. [inference; verify in testing]
- Dates: self-managed **9.0.15 (2025-12-09)**, carried into 10; Helios **January 2026**. The fix "Fixed an issue with vending credentials in Iceberg pipelines" shipped in 10 and Helios August 2026. [read: release notes]
- Static credentials in CREDENTIALS remain supported and are what all examples use. [read]

### 1.5 Ingest modes, deletes, format versions

- **Format:** "Iceberg Version 1 tables and Iceberg Version 2 tables with data files in Parquet format." **v3 is not listed.** That implies no deletion vectors, row lineage, variant, geo or nanosecond-timestamp types. [read; the v3 consequence is inference]
- **`MERGE INTO`** is recommended and continuous. Per the docs: "Append, Overwrite, Replace, and Delete (positional and equality) Iceberg V2 snapshots are supported."
  - The destination needs `` `$_file` VARCHAR(2048) COMMENT 'ICEBERG_FILE_PATH' ``, `` `$_row` BIGINT COMMENT 'ICEBERG_FILE_POS' `` and `` `$_delete` JSON DEFAULT '{}' COMMENT 'ICEBERG_DELETED_BY' ``, with ``SHARD KEY(`$_file`, `$_row`)``.
  - The pipeline must set ``SET `$_file` = pipeline_source_file(), `$_row` = pipeline_source_file_row()``.
  - Deletes are applied merge-on-read through a view with ``WHERE JSON_LENGTH(`$_delete`) = 0``.
  - You must not modify the destination table yourself.
  - Replace snapshots are **not** a no-op: data files are re-ingested after compaction.
  - `MERGE` cannot be combined with `ingest_mode`.
  [read]
- **`"ingest_mode":"append"`** handles only APPEND snapshots; DELETE or OVERWRITE raises an error. **`"upsert"`** handles APPEND and OVERWRITE as upserts, requires `REPLACE INTO` and a PK/unique key, and fails on delete snapshots. **`"one_time"`** loads the latest snapshot once. [read]
- **Snapshot transactionality:** `BATCH_MATCHES_SNAPSHOT ON` per pipeline, or the global `pipelines_batch_matches_snapshot`, makes only fully ingested snapshots visible. Do not enable it at creation on large tables, because the initial load becomes one transaction. [read]
- **File filtering:** pipeline `WHERE` comparisons of a column against a literal prune files using manifest column stats. Shipped in 9.0 (9.0.3 RC 2025-06-12) and Helios October 2025. [read]
- **Monitoring:** `information_schema.PIPELINES_ICEBERG_FILES` shows FILE_STATE (`Data Unloaded` / `Data Loaded` / `Pending Data Removal` / …), `SNAPSHOT_ID` and `SNAPSHOT_TIMESTAMP`. The last two were added in 9.0.22 (2026-03-05). [read]
- **Inference of DDL:** `INFER PIPELINE AS LOAD DATA ... FORMAT ICEBERG` and `CREATE INFERRED PIPELINE ... OPTIONS = 'merge'` exist. Map and list columns are skipped, and the Iceberg partition key becomes the SingleStore shard key. Shipped in 9.0 and Helios October 2025. [read]
- **Type mapping:** date, time, timestamp and timestamptz arrive as integers. The docs recommend conversions such as `DATE_ADD('1970-01-01', INTERVAL @timestamp_value MICROSECOND)`, and timestamptz maps to `DATETIME(6)`, which carries no zone. Other mappings: uuid to `BINARY(16)`, string to `LONGTEXT (utf8mb4_bin)`. [read]

### 1.6 Where the catalog calls come from

- The aggregator's JVM ("Iceberg metadata workers", `pipelines_iceberg_heap_size`, default 96 MB) connects to the catalog and downloads and parses manifests. The leaves ("data workers", `pipelines_iceberg_data_workers_heap_size`, default 500 MB) read the Parquet files. [read: engine-variable list; 2024 blog]
- So **catalog HTTP traffic comes from aggregator nodes**, while **S3 reads of metadata, manifests and data come from both aggregators and leaves**. Vended credentials must therefore work from every node. [inference]
- Extractor timeouts are raised to at least 5 minutes for Iceberg (`pipelines_extractor_get_offsets_timeout_ms`, `pipelines_extractor_idle_timeout_ms`). [read]
- The default `BATCH_INTERVAL` is 2500 ms. [read] Expect a `loadTable` roughly that often per running pipeline. [inference; verify]

### 1.7 Feature dates

| Capability | Self-managed | Helios |
|---|---|---|
| Iceberg ingest, `enable_iceberg_ingest` (catalog not stated; Glue first is an inference from the 8.7.2 entry) | 8.7 | not in notes |
| Snowflake, REST, JDBC catalogs | 8.7.2 | June 2024 |
| Continuous ingest (append/upsert), `CREATE OR REPLACE`; Snowflake/REST/JDBC/Hive/Polaris listed as "new catalogs" | 8.9 | January 2025 |
| v2 merge-on-read ingest (now `MERGE` pipelines; the name change is an inference) | 8.9 | January 2025 section |
| `pipelines_iceberg_heap_size`, `pipelines_iceberg_data_workers_heap_size` | 8.9 / 8.9.5 | Jan 2025 |
| `INFER PIPELINE` for Iceberg, auto column mappings, `WHERE` file filtering | 9.0 (9.0.3 RC) | October 2025 |
| EKS IRSA with assumeRole | — | October 2025 |
| Vended credentials (REST + S3) | 9.0.15 (2025-12-09); 10 | January 2026 |
| `SNAPSHOT_ID`/`SNAPSHOT_TIMESTAMP` in `PIPELINES_ICEBERG_FILES` | 9.0.22 (2026-03-05) | March 2026 |
| `parquet_emit_field_ids` (egress helper) | 8.9.54, 9.0.36 (2026-07-16), 10 | — |
| Iceberg external tables: Hive and REST catalogs | not in self-managed notes | **August 2026** |
| Batch-history detail for merge-pipeline deletes | 9.0.39 (2026-08-11), 10 | September 2026 |

The 8.9 continuous-ingest release note lists REST, Hive and Polaris as "new", but 8.7.2 already lists REST. I take 8.7.2 as REST's first appearance and 8.9 as GA of the broader set. [read; reconciliation is inference]

Unity and Hadoop appear in the docs but in no release note, so they are undated. [read]

---

## 2. Egress: SingleStore → Iceberg

> **Update 2026-11-11: built.** The committer this section calls for now exists: `singlestore-change-feed`, a
> connector pipeline (SingleStore's Kafka Connect runtime, no Kafka) that reads a table's change feed
> (`OBSERVE`) and commits it to Iceberg through our catalog with vended credentials. See D35's amendment and
> `demo/README.md`. Found by running SingleStore 10.9.0 (the `singlestoredb-dev:0.2.88` image):
>
> - `OBSERVE` sends, per partition, `BeginSnapshot`, the rows as `Insert`s, `CommitSnapshot`, then
>   transactions (`BeginTransaction`, row events, `CommitTransaction`); partitions interleave. An update
>   carries the whole new row, a delete only the key. `BEGIN AT ('<offset>', ...)`, one offset per partition,
>   resumes after them. An offset older than the oldest snapshot SingleStore keeps (`snapshots_to_keep`, 2)
>   fails with error 2851. A shard-key column cannot be updated. `ALTER` and `DROP` wait while `OBSERVE` runs.
> - Connector pipelines need `experimental_features_config = 'connector_pipelines_enabled=true'`. The
>   extractor runs on `java_path` (JDK 25) with `-Xmx` from `pipelines_cdc_java_heap_size` (default 128 MB).
>   Every jar in `extractors/lib-extractor-connect/` is on one class path with SingleStore's own (AWS SDK
>   2.25.3, Jackson 2.22.3, Debezium, Kafka Connect 3.9.1), so the connector loads Iceberg 1.12 from inside
>   its own jar, in a child-first class loader. The Iceberg pipelines' extractor bundles Iceberg 1.9.0.
> - `CREDENTIALS` are merged into the connector's configuration. `information_schema.PIPELINES.CONFIG_JSON`
>   keeps each pipeline's `CONFIG` as a JSON string under `"config"`; an Iceberg pipeline names its table in
>   `table_id` there.
> - `pipelines_stop_on_error` is ON: a connector error stops the pipeline after
>   `pipelines_max_retries_per_batch_partition` (4) retries.
> - The connect extractor implements Kafka Connect's `OffsetStorageReader` (`TaskOffsetAccessor`) and
>   restarts the task when SingleStore asks for a batch from offsets other than the ones it last sent, as after
>   a failed batch. `information_schema.PIPELINES_CURSORS` shows only a counter for a connector pipeline.
> - Each Java extractor's `-Xmx` is reserved from the leaf's memory (`Alloc_pipelines`), and idle extractors
>   stay pooled. An Iceberg pipeline's data workers get `pipelines_iceberg_data_workers_heap_size` (500 MB)
>   each; with two pooled one-time loads and the connector (1,384 MB in all) a 3 GB container's leaf refused
>   every write. `ALTER TABLE` on a pipeline's target cannot rename it, nor change a columnstore column's type.
>
> The text below is the November 2026 research as it was.

**No public SingleStore feature writes Iceberg tables or commits through a catalog.**

- The docs sitemap has no Iceberg page other than ingest and `PIPELINES_ICEBERG_FILES`. [read]
- The v10 and Helios release notes contain no Iceberg-write item. [read]
- `SELECT`'s grammar has `INTO OUTFILE | FS | S3 | HDFS | GCS | KAFKA | AZURE | LINK` with `FORMAT PARQUET`, and no Iceberg target. [read]

**What is documented is "export Parquet, then register it elsewhere":**

```sql
SET GLOBAL parquet_emit_field_ids = false;

SELECT * FROM my_table
INTO S3 's3://my-bucket/export/'
CONFIG '{"region":"us-east-1"}'
CREDENTIALS '{"aws_access_key_id":"...", "aws_secret_access_key":"..."}'
FORMAT PARQUET;
```
[read: https://docs.singlestore.com/db/v10/reference/sql-reference/data-manipulation-language-dml/select.md]

- By default the Parquet output carries `field_id`s. The docs say PyIceberg's `add_files` "may reject" such files, hence `parquet_emit_field_ids` (default `true`).
- When registering files without field IDs, the docs say to set `schema.name-mapping.default`.
- They also note that SingleStore writes date and time values **in the engine time zone without converting to UTC**. That is a correctness hazard for `timestamptz` columns. [read]

**Vendor statements about write-to-Iceberg** (none documented in the product docs):

- 2024-06-26 blog: "Bi-directional integration (private preview). SingleStore supports write-to-iceberg tables with support for Glue catalog". It also promised "Egress data to Iceberg" and "External Iceberg tables" "later this year". [read: https://www.singlestore.com/blog/bidirectional-integration-for-apache-iceberg/]
- Current product page: "Data sharing to Iceberg (preview): Write-to-Iceberg tables with support for Glue catalog"; "External tables from Iceberg (soon)". [read: https://www.singlestore.com/platform/data-integration/]
- 2025-02-26 ITNEXT, written by a SingleStore engineer: external tables, ingest/CDC and export are "still in preview". [secondary]
- 2026-07-02 blog: applications "can … Write updates and operational changes" to open Iceberg tables. No mechanism, syntax or catalog list is given. [read]

**Therefore:**

- We have no evidence that SingleStore ever calls `createTable`, stage-create or `updateTable` on a REST catalog.
- The only known write preview is Glue-only and private.
- The format version written, and which updates and requirements SingleStore would send, are **unknown**.

If such a feature exists in private preview, it is most likely built on the same Iceberg Java SDK used for ingest. It would then commit through the standard `POST /v1/{prefix}/namespaces/{ns}/tables` and `POST .../tables/{t}` (`CommitTableRequest`) paths that any Iceberg Java writer uses. [inference]

---

## 3. A SingleStore-managed catalog, or SingleStore "Iceberg tables"

- **There is no SingleStore-operated Iceberg catalog service** in docs, release notes or blogs. The 2026-07-22 CEO post argues for open, vendor-neutral catalogs and announces no SingleStore catalog. [read]
- **"Iceberg external tables" (Helios only)** query Iceberg in place. They were promised in 2024 and gained "Hive and REST catalog support" in August 2026. There is no docs page, and nothing in the self-managed 10 notes. That points to a private preview whose catalog list was previously something else, probably Glue/Snowflake. [read: release note; inference for the rest] If Helios customers use it against REST catalogs, it will issue at least the same read calls as ingest. [inference]
- **SingleStore as a JDBC-catalog backing database:** the Iceberg JDBC catalog can store its tables in SingleStore through the SingleStore JDBC driver (`jdbc:singlestore://...`). Spark does the catalog work; SingleStore is only the RDBMS. [read: dev.to 2024]

---

## 4. Self-managed vs Helios, and the 10.9 dev image

Differences between the two Iceberg Ingest pages (only these): [read: line diff]

1. EKS IRSA: self-managed needs the K8s operator on EKS plus IAM roles. Helios uses Cloud Workload Identity / delegated entities instead.
2. Only self-managed documents `SET GLOBAL java_pipelines_java11_path = <path to JRE 11+ java binary>;` (all nodes).
3. Only self-managed allows extra JDBC drivers via `java_pipelines_class_path`.
4. Only self-managed has the "Hadoop Catalog on FS" example (`LOAD DATA FS`).

In addition, Helios has Iceberg external tables (§3), and the Helios engine-variable list marks the Iceberg heap variables as "configurable on Helios". [read]

Version 10 changes Java: "SingleStore now requires JRE 25" and adds a universal `java_path`, migrating per-feature paths over time. The Iceberg page still documents `java_pipelines_java11_path` (JRE 11+). [read]

**Dev image `ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88`:**

- CHANGELOG: `0.2.88 - 2026-11-03 - SingleStoreDB Version 10.9.0`. [read]
- The 10.9.0 release notes contain no Iceberg change beyond those in the 10 GA list. [read]
- `scripts/init.sh` (last changed 2026-02-26, so identical in 0.2.88) sets, for engine ≥ 9.1, `java_path=/usr/local/jdk-25/bin/java` and `java_pipelines_java11_path=/usr/local/jdk-21/bin/java` on all nodes. The Iceberg JVM prerequisite is therefore already met. [read]
- `enable_iceberg_ingest` is **not** set by the image. Set it with `SET GLOBAL enable_iceberg_ingest = ON;` or the image's env-var mechanism, `-e SINGLESTORE_SET_GLOBAL_ENABLE_ICEBERG_INGEST=1`. SingleStore's own lakehouse repo did exactly that with dev image 8.9.10 to read Iceberg via Hive. [read: start.sh loop; iceberg-lakehouse-docker compose]
- License: the image boots with a built-in free license and needs no license key. The editions page lists no Iceberg restriction; Enterprise-only items are PITR, Unlimited Storage and Audit Logging. [read] So Iceberg ingest should work in the dev image. [inference]
- Limits: one master aggregator plus one leaf, up to 8 cores and 64 GB, and databases capped at two partitions (images ≥ 0.2.40). [read]
- No preview flag beyond `enable_iceberg_ingest` is documented. [read]

---

## 5. Known limitations, bugs and caveats

- **Preview status:** Iceberg Ingest and `PIPELINES_ICEBERG_FILES` are both labelled Preview, and the view's schema "is subject to change". [read]
- **Storage:** S3 or S3-compatible only, plus FS for the Hadoop catalog on self-managed. No GCS or Azure for Iceberg. Vended credentials only for REST + S3. [read]
- **Format:** only v1/v2 tables with Parquet data files; no ORC or Avro data. Subvalue paths cannot address list or map types. [read]
- **Append/upsert semantics:** both error on snapshot types they cannot handle. `ignore_unsupported_modifications` can override that, at the risk of inconsistency. Upsert cannot process deletes, and the docs say these limits "are expected to be addressed in future releases". [read]
- **MERGE costs:** three extra columns, a slower initial load, a scan-time penalty through the view, full re-ingest of rewritten files after compaction, and the destination table must not be touched. [read]
- **`CREATE OR REPLACE`** on an existing Iceberg pipeline can ingest files twice; use it only with REPLACE or upsert. [read]
- **Fixed bugs worth knowing** (all in 10; in 9.0.x as dated):
  - vended-credentials issue (10; Helios Aug 2026)
  - `pipelines_batch_matches_snapshot` syntax error (9.0.37)
  - partitions skipped after repeated failures (9.0.39)
  - `retry_options` ignored in some Iceberg cases (10; Helios Sep 2026)
  - `PIPELINES_ICEBERG_FILES` crash with pre-upgrade partitions (9.0.27). Known issue in 9.0.22: drop and re-create pre-upgrade Iceberg pipelines.
  - "Added proper refresh handling for STS tokens during long-running operations" (10), which is relevant to vended or STS credentials.
  [read]
- **Troubleshooting table:** Java heap OOM, so raise `pipelines_iceberg_heap_size` (96 → 128 → 256 MB). A wrong `java_pipelines_java11_path` causes "Process died unexpectedly". `Unsupported snapshot with operation %s` means the ingest mode does not fit. [read]
- **Timestamps:** both directions are timezone-naive. Ingest stores timestamptz as `DATETIME(6)`; egress Parquet is written in the engine time zone. [read]
- **Docs defects:** curly quotes in the syntax block; `catalog.connection` vs `catalog.credential`; the Hive example has invalid JSON (a missing comma and single quotes). [read]

---

## 6. What our catalog must support for SingleStore

### Ingest leg (SingleStore → our catalog): v1 must-haves

1. **`GET /v1/config`**, accepting the `warehouse` query parameter.
   - Return `defaults`/`overrides` including `prefix` if we use one.
   - If we return an `endpoints` list, it must include `GET /v1/{prefix}/namespaces/{namespace}/tables/{table}`.
   - If we omit `endpoints`, the SDK assumes the legacy default set (namespaces CRUD, tables CRUD, rename, register, `POST .../metrics`, `POST /transactions/commit`).
   [read: RESTSessionCatalog `DEFAULT_ENDPOINTS`]
2. **Authentication the Iceberg Java client can do unaided:**
   - a static bearer via `catalog.token`, and/or
   - OAuth2 `client_credentials` via `catalog.credential` plus `catalog.scope` (default `catalog`). The SDK posts form fields `grant_type=client_credentials`, `client_id`, `client_secret` and `scope` to either our `POST /v1/oauth/tokens` or an external IdP named by `catalog.oauth2-server-uri`.
   - Token refresh is on by default (`token-refresh-enabled`), and token exchange is enabled by default (`token-exchange-enabled`). We must either support the token-exchange grant or tolerate the fallback.
   [read: OAuth2Properties/AuthConfig/OAuth2Util/OAuth2Manager]
   For the firm, the external-IdP path (`oauth2-server-uri`) plus JWT validation in the catalog is the natural fit. [inference]
3. **`GET /v1/{prefix}/namespaces/{namespace}/tables/{table}`** (loadTable).
   - The SDK sends `?snapshots=all` by default, or `refs` if configured.
   - It must return full v1/v2 metadata, `metadata-location` and the `config` map.
   - This call is polled continuously, probably every batch interval (≈2.5 s default) per pipeline. Size rate limits and caching for that, and consider ETag (D14).
   [read: SDK; polling cadence is inference]
4. **Vended credentials:**
   - Honour `X-Iceberg-Access-Delegation: vended-credentials`.
   - Return read-scoped S3 credentials covering the table location (metadata, manifests, data and delete files). Return them in **both** `storage-credentials` and the legacy `config` keys `s3.access-key-id`, `s3.secret-access-key` and `s3.session-token`, because we do not know which one SingleStore's leaves consume.
   - Include `client.region`, and `s3.endpoint` / `s3.path-style-access` for S3-compatible stores.
   - Set the TTL comfortably above SingleStore's 5-minute extractor timeouts.
   - Optionally offer `GET .../tables/{table}/credentials` for refresh.
   [read: OpenAPI spec; the "both" advice is inference]
5. **`POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics`:** accept and return 204. If SingleStore uses the SDK scan API, it will post ScanReports because metrics reporting defaults to on. Otherwise leave it out of `endpoints`. [read: SDK default; whether SingleStore triggers it is inference]
6. **Namespace and identifier handling:**
   - The `LOAD DATA S3 'ns.table'` / `table_id` value is parsed by the SDK into a namespace and table.
   - Multi-level namespaces are encoded with the namespace separator (`%1F` by default).
   - Support at least single-level namespaces, and test multi-level ones.
   [read: RESTCatalogProperties `namespace-separator`; SingleStore behaviour is inference]
7. **Table content that SingleStore can read:**
   - Format version 1 or 2, Parquet data files, `s3://` locations.
   - Accurate snapshot `operation` values (append/overwrite/replace/delete) and summaries, because SingleStore's modes branch on them.
   - Position and equality delete files are fine for `MERGE`. No v3 for SingleStore-consumed tables.
   [read: format and modes; the location-scheme advice is inference]
8. **Optional extras:** `HEAD .../tables/{table}` (table exists), the scan-planning endpoints and views are **not** needed for SingleStore. Advertise them only if implemented. [inference]

### Egress leg (SingleStore data → Iceberg tables in our catalog)

SingleStore will not call our catalog for this. A committer we control (a small service, or PyIceberg/Iceberg Java) does:

1. `POST /v1/{prefix}/namespaces` and `POST /v1/{prefix}/namespaces/{ns}/tables` to create the target table, with the format version chosen by us (v2 if SingleStore must read it back).
2. Run SingleStore `SELECT ... INTO S3|LINK ... FORMAT PARQUET`, with `parquet_emit_field_ids = false` if using PyIceberg `add_files`.
3. `GET` loadTable.
4. `POST /v1/{prefix}/namespaces/{ns}/tables/{table}` (updateTable). PyIceberg `add_files` sends:
   - `set-properties` with `schema.name-mapping.default`, only if absent
   - `add-snapshot`
   - `set-snapshot-ref` (branch `main`)
   - requirements `assert-ref-snapshot-id` and `assert-table-uuid`
   It retries on `409 CommitFailedException`. [read: PyIceberg `Transaction.add_files`, `_SnapshotProducer._commit`, `commit_transaction`]
5. For updates or deletes coming out of SingleStore, the committer has to build overwrite or delete snapshots itself. `add_files` is append-only. [inference]

This is the same commit surface Spark and Trino need, so it adds no catalog scope beyond a normal v1. The new work is the committer and the Parquet/timezone hygiene.

---

## 7. Open questions

1. **Which Iceberg Java SDK version does SingleStore 10.9.0 bundle?** It governs OAuth behaviour, `endpoints` negotiation, ETag, `storage-credentials` parsing and the credentials-refresh endpoint. Our catalog can read it from the `X-Client-Version` header the SDK sends on every request. [read: HTTPClient.java]
2. **What exactly does `iceberg_vended_credentials: true` do?** Does it add `X-Iceberg-Access-Delegation` itself, or must `catalog.header.X-Iceberg-Access-Delegation` be set? Does it read `storage-credentials` or `config` `s3.*`? How are credentials handed to leaves and refreshed?
3. Does a running pipeline call `loadTable` every batch interval? Does it use `snapshots=refs`, `HEAD`, metrics, or REST scan planning?
4. **TLS to `catalog.uri`.** How do we make SingleStore's extractor JVM trust an internal CA? Is mTLS client auth possible? Is there an HTTP proxy setting? None of this is documented except Hive truststore keys. This matters for a bank's internal PKI.
5. Are multi-level namespaces in the identifier supported?
6. Do on-prem S3-compatible stores work with vended credentials (STS-like credentials from a non-AWS store)?
7. What is "Data sharing to Iceberg (preview)": syntax, Glue-only?, a REST-catalog timeline, the format version written, and Helios vs self-managed availability? **Only SingleStore can answer this.** Ask the account team, together with the status of "Iceberg external tables" on self-managed.
8. Is there a roadmap for v3 (deletion vectors) support?
9. Will Helios customers' external tables (REST, August 2026) hit our catalog with different call patterns than pipelines?

---

## 8. How to test against the local 10.9 dev container (proposed, not executed)

Use a **second** container from the same image so the other project's `singlestoredb-dev` is untouched.

1. **Infrastructure on one Docker network** (`iceberg_net`):
   - MinIO with `MINIO_DOMAIN=minio` and a network alias `warehouse.minio`, mirroring SingleStore's own lakehouse compose.
   - Our catalog.
   - As a baseline, `apache/iceberg-rest-fixture`, to get SingleStore working against a known-good catalog before swapping in ours.
   - `docker run -d --name s2-iceberg-test --network iceberg_net -e ROOT_PASSWORD=... -e SINGLESTORE_SET_GLOBAL_ENABLE_ICEBERG_INGEST=1 -p 3316:3306 ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88`
2. **Check prerequisites:** `SELECT @@enable_iceberg_ingest, @@java_pipelines_java11_path, @@java_path;`. Expect ON, `/usr/local/jdk-21/bin/java` and `/usr/local/jdk-25/bin/java`.
3. **Seed tables through our catalog** with PyIceberg or Spark: `ns1.books`, v2, Parquet. Make it partitioned if possible.
4. **Turn on full request logging in our catalog:** method, path, query, `X-Client-Version`, `X-Iceberg-Access-Delegation`, `Authorization` scheme only, and the response code. Capturing this call trace is the main deliverable of the test.
5. **Run these cases and record the call trace for each:**
   - a. Static credentials:
     ```sql
     CREATE PIPELINE p AS LOAD DATA S3 'ns1.books'
     CONFIG '{"catalog_type":"REST","catalog.uri":"http://catalog:8181","catalog.warehouse":"<wh>",
              "region":"us-east-1","endpoint_url":"http://minio:9000"}'
     CREDENTIALS '{"aws_access_key_id":"...","aws_secret_access_key":"..."}'
     INTO TABLE books (...) FORMAT ICEBERG;
     ```
     Then `TEST PIPELINE p;`.
   - b. Auth variants: `catalog.token`; `catalog.credential` + `catalog.scope`; `catalog.oauth2-server-uri` pointed at a local IdP. Also test expired and invalid tokens, expecting 401 handling.
   - c. Vended credentials: `"iceberg_vended_credentials": true`, both with and without `"catalog.header.X-Iceberg-Access-Delegation":"vended-credentials"`, with MinIO STS AssumeRole minting session credentials and **no** static CREDENTIALS. Check which response field SingleStore uses by returning credentials in only one of `storage-credentials` / `config` at a time.
   - d. Ingest modes: `one_time`; `append` plus a later PyIceberg append (measure pickup latency and `loadTable` frequency); `upsert` with an overwrite; `MERGE` with a Spark MoR position delete, an equality delete, and a compaction (`rewrite_data_files`, which should re-ingest). Watch `information_schema.PIPELINES_ICEBERG_FILES` and `PIPELINES_ERRORS`.
   - e. Negative cases: a v3 table (expect an error); a 404 table; the catalog down mid-run; multi-level namespace `a.b.books`; an HTTPS catalog with a self-signed CA (expect failure; explore JVM truststore options).
   - f. `INFER PIPELINE AS LOAD DATA S3 'ns1.books' CONFIG '{...}' CREDENTIALS '{...}' FORMAT ICEBERG;`
6. **Egress round trip:**
   - `SET GLOBAL parquet_emit_field_ids = false;` then `SELECT * FROM books INTO S3 'warehouse/export/books/' CONFIG '{"region":"us-east-1","endpoint_url":"http://minio:9000"}' CREDENTIALS '{...}' FORMAT PARQUET;`
   - PyIceberg `table.add_files([...])` against our catalog, and confirm the commit request shape matches §6.
   - Read the table back with a SingleStore pipeline.
   - Compare row counts and timestamp values to expose the engine-timezone issue.
7. **Record the Iceberg SDK version** from `X-Client-Version` (open question 1) and update this note.

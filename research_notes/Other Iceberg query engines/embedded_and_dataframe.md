# Embedded, single-node and dataframe engines over an Iceberg REST catalog (keel), as of 2026-11-12

Provenance tags used on every line: **[P]** read in a primary source (repo source code, repo docs read as raw Markdown, official docs, GitHub/PyPI/crates.io APIs) at the URL given; **[S]** secondary (search-result summary or a page read only through a summarising fetch); **[I]** my inference. GitHub source files were read on their default branch on 2026-11-12 unless a tag is named in the URL. Release dates come from the GitHub releases API, PyPI JSON API or crates.io API, read 2026-11-12. Nothing was installed or run.

## Key question 1: for each engine, which Iceberg library does it use, and how does it meet keel's six requirements (REST + OAuth2 JWT, vended credentials with on-prem endpoint, nested namespaces, v2/v3 reads, writes, on-prem/air-gapped running)?

### Takeaway
Only three engines are real candidates today: **Spice.ai** (a Rust server on a fork of iceberg-rust; the most complete end to end, but it refuses a vended endpoint on a private IP unless you repeat the endpoint in its config), **Polars** and **Daft** (Python libraries that use PyIceberg for the catalog, so they inherit PyIceberg's auth and vended-credential handling; both pass the vended `s3.endpoint` through, but neither reads equality deletes). **DataFusion with iceberg-rust** works as a library, with the best v2/v3 delete support. It has no token refresh, no built-in access-delegation header, and its DataFusion glue moved to a new repository (`apache/datafusion-iceberg`) after 0.11.x. **Sail** has the richest Iceberg feature set (v3 deletion vectors, MERGE) and a multi-user Spark Connect/Flight SQL endpoint. But its main branch still does not *apply* vended credentials: it fingerprints them and uses its own configured S3 credentials, so it fails requirement 2 as stated. **chDB** inherits ClickHouse's DataLakeCatalog. **GlareDB** is dormant and has only a stub REST catalog. **Ballista** has no Iceberg support. **MotherDuck** is cloud-only.

### Cited Findings

#### Library baseline: Apache Iceberg implementation-status page
- [P] The status page has rows for data types, file formats, File IO, and table read, write and catalog operations. It has **no rows for REST auth, vended credentials or deletion vectors**. — [iceberg.apache.org/status](https://iceberg.apache.org/status/) (raw: [site/docs/status.md](https://github.com/apache/iceberg/blob/main/site/docs/status.md))
- [P] V2 reads: Rust = Y for plan/read with position deletes and equality deletes. PyIceberg = Y for read with position deletes and **N for read with equality deletes**. — [status.md](https://github.com/apache/iceberg/blob/main/site/docs/status.md)
- [P] Types: Rust and PyIceberg are both Y for `timestamp_ns`, `timestamptz_ns` and `variant`. Both are **N for geometry and geography** (only Java has them). `unknown` is N for Rust and Y for PyIceberg. Puffin format is N for both Rust and PyIceberg. — [status.md](https://github.com/apache/iceberg/blob/main/site/docs/status.md)
- [P] Writes: Rust V2 = append Y, write equality deletes Y, write position deletes N, row delta N, overwrite/delete files N. PyIceberg V2 = append, overwrite and delete files Y, row delta N, position-delete and equality-delete writes N. — [status.md](https://github.com/apache/iceberg/blob/main/site/docs/status.md)
- [I] The page lags the code. It shows Rust "Update schema = N" for V2, yet iceberg-rust v0.10.0 added an `update_schema` action, and v0.11.0 reads v3 deletion vectors that the page has no row for (see below). Treat the page as a floor, not the current state.

#### Apache DataFusion with iceberg-rust (`iceberg-datafusion`, now `apache/datafusion-iceberg`) and the `iceberg-playground` CLI
- [P] Library: apache/iceberg-rust. Latest release **v0.11.0 published 2026-11-03**; v0.10.1 2026-08-01; v0.10.0 2026-07-21; v0.9.0 2026-03-18; v0.8.0 2026-01-16. Apache-2.0, 1,445 stars. — [GitHub releases API](https://api.github.com/repos/apache/iceberg-rust/releases), [repo](https://github.com/apache/iceberg-rust)
- [P] **The DataFusion integration has left iceberg-rust.** "The 0.11.x releases are the last releases of `iceberg-datafusion` from the Apache Iceberg project… future releases are maintained by the Apache DataFusion project at apache/datafusion-iceberg." This followed votes in both communities. — [crates/integrations/datafusion/README.md @v0.11.0](https://github.com/apache/iceberg-rust/blob/v0.11.0/crates/integrations/datafusion/README.md)
- [P] apache/datafusion-iceberg was created 2026-09-09, last pushed 2026-11-11, has 35 stars and **no GitHub releases yet**. It contains `crates/datafusion` (the library), `crates/sqllogictest` and `crates/playground` (a command-line SQL playground). It pins DataFusion 55.0.0 and an iceberg-rust **git revision** (`5b82ab99…`). — [repo API](https://api.github.com/repos/apache/datafusion-iceberg), [README](https://github.com/apache/datafusion-iceberg/blob/main/README.md), [Cargo.toml](https://github.com/apache/datafusion-iceberg/blob/main/Cargo.toml)
- [P] crates.io: `iceberg-datafusion` latest is 0.11.0 (2026-11-03), with 224,770 downloads in total. `iceberg-playground` is **not published** on crates.io (404). — [crates.io/crates/iceberg-datafusion](https://crates.io/crates/iceberg-datafusion)
- [P] The `iceberg-playground` CLI wraps `datafusion-cli`'s REPL. It reads catalogs from `~/.icebergrc` (or `-r <file>`). — [crates/playground/src/main.rs](https://github.com/apache/datafusion-iceberg/blob/main/crates/playground/src/main.rs)
- [P] The `.icebergrc` file is TOML. Each catalog has `type = "rest"` or `"memory"`, and every other key is passed verbatim as a prop to `RestCatalogBuilder`. — [playground/src/catalog.rs @v0.11.0](https://github.com/apache/iceberg-rust/blob/v0.11.0/crates/integrations/playground/src/catalog.rs)
- [P] REST auth props: `token` (static bearer), `credential` (`client_id:secret`, exchanged at the token endpoint), `oauth2-server-uri` (external IdP token endpoint), `scope` (default `catalog`), plus optional `audience` and `resource`. `rest.auth.type` = `none`/`oauth2`. Arbitrary headers come from `header.<name>` props. — [crates/catalog/rest/src/catalog.rs](https://github.com/apache/iceberg-rust/blob/main/crates/catalog/rest/src/catalog.rs) (lines ~60-80, 211-217, 263-265, 428, 443-475, 503-510)
- [P] **No automatic token refresh.** The OAuth2 session carries the comment "`# TODO: Support automatic token refreshing.`" It uses a configured token, or exchanges the credential once and caches the result. There is no 401 retry: no `UNAUTHORIZED` handling was found in the REST catalog, client or oauth2 sources. — [auth/oauth2.rs](https://github.com/apache/iceberg-rust/blob/main/crates/catalog/rest/src/auth/oauth2.rs) (line 233)
- [P] **End-user token passthrough is possible in code, not from config.** The pluggable `AuthManager` (modelled on Java's AuthManager/AuthSession) has `contextual_session(&SessionContext, …)`, so a custom manager can return a per-user session keyed by session id. `RestSessionCatalog` and the `SessionCatalog` trait arrived in v0.11.0. — [auth/mod.rs](https://github.com/apache/iceberg-rust/blob/main/crates/catalog/rest/src/auth/mod.rs), [v0.11.0 release notes](https://api.github.com/repos/apache/iceberg-rust/releases)
- [P] **Vended credentials:**
  - The `loadTable` response's `config` map is merged into the table's FileIO properties, and user props override it: `response.config.into_iter().chain(self.user_config.props.clone())`.
  - The newer `storage-credentials` array is parsed, but it is used only to skip the ETag table cache. Its entries are **not** merged into FileIO. v0.11.0's catalog.rs does not reference `storage_credentials` at all.
  - The client **does not send `X-Iceberg-Access-Delegation`**: no match in catalog.rs or client.rs. It can be added with `header.X-Iceberg-Access-Delegation=vended-credentials`.

  — [catalog.rs](https://github.com/apache/iceberg-rust/blob/main/crates/catalog/rest/src/catalog.rs) (lines 1369-1450, 986-1024, 97-100), [types.rs](https://github.com/apache/iceberg-rust/blob/main/crates/catalog/rest/src/types.rs) (line 231)
- [P] S3 FileIO keys: `s3.endpoint`, `s3.path-style-access`, `s3.session-token`, `s3.region` and `client.region` are all read from the props. — [io/storage/config/s3.rs](https://github.com/apache/iceberg-rust/blob/main/crates/iceberg/src/io/storage/config/s3.rs) (lines 32-44, 168-187)
- [P] v0.10.0 "Defaults S3 to virtual-host-style addressing". — [release notes](https://api.github.com/repos/apache/iceberg-rust/releases)
- [P] **Nested namespaces:** the REST client encodes multipart namespaces with the unit separator (a test uses `"parent\u{1f}child"`). — [catalog.rs](https://github.com/apache/iceberg-rust/blob/main/crates/catalog/rest/src/catalog.rs) (line 2100)
- [P] The DataFusion `IcebergCatalogProvider` builds its schemas from `list_namespaces(None)`, so only top-level namespaces. It notes "schemas might become stale" (no cache eviction). — [datafusion/src/catalog.rs @v0.11.0](https://github.com/apache/iceberg-rust/blob/v0.11.0/crates/integrations/datafusion/src/catalog.rs)
- [P] Delete files and v3 in the release history:
  - v0.8.0: position and equality deletes on the same task, plus fixes.
  - v0.11.0: decodes deletion-vector-v1 Puffin blobs and applies V3 deletion vectors; Variant support; row-lineage metadata columns `_row_id` and `_last_updated_sequence_number`; history metadata table; encryption work.
  - v0.9.0: v2-to-v3 upgrades.

  — [releases](https://api.github.com/repos/apache/iceberg-rust/releases)
- [P] Writes via DataFusion: `INSERT INTO` (including partitioned tables, from v0.8.0); `CREATE TABLE` and `DROP TABLE` (v0.9.0). v0.10.0 "rejects non-append inserts". The core crate gained `update_schema`, `ExpireSnapshotsAction` and `purge_table` (v0.10.0). — [releases](https://api.github.com/repos/apache/iceberg-rust/releases)
- [P] Main has a `crates/iceberg/src/cow_rewrite/` module. It is absent from v0.11.0's catalog code paths; its release status is unverified. — [repo tree](https://github.com/apache/iceberg-rust/tree/main/crates/iceberg/src)
- [P] The `pyiceberg-core` Python bindings on main contain only data_file, encryption, manifest and transform modules: no DataFusion table-provider module. pyiceberg-core 0.11.0 was uploaded to PyPI 2026-11-03; the manylinux x86_64 wheel is 14.7 MB. — [bindings/python/src](https://github.com/apache/iceberg-rust/tree/main/bindings/python/src), [PyPI](https://pypi.org/project/pyiceberg-core/)
- [P] The python `datafusion` package is at 55.0.0 (2026-10-12), with ~43 MB manylinux x86_64 wheels. — [PyPI datafusion](https://pypi.org/project/datafusion/)
- [P] The modularisation RFC moves runtime, storage and DataFusion glue out of the `iceberg` crate into companion crates. Expect API churn. — [docs/rfcs/0001](https://github.com/apache/iceberg-rust/blob/main/docs/rfcs/0001_modularize_iceberg_implementations.md)

#### Sail (LakeSail)
- [P] Latest release **v0.7.3, 2026-10-30**. Earlier: v0.7.2 2026-09-29, v0.7.1 2026-08-24, v0.7.0 2026-08-03. Apache-2.0, 3,496 stars. — [releases API](https://api.github.com/repos/lakehq/sail/releases), [repo](https://github.com/lakehq/sail)
- [P] Sail is a Spark Connect-compatible engine (Spark SQL plus the DataFrame API). Install is `pip install pysail` plus `pyspark-client`. — [README](https://github.com/lakehq/sail/blob/main/README.md)
- [P] Library: Sail uses **its own Iceberg implementation** (`sail-iceberg`, `sail-catalog-iceberg` with a REST client generated from `spec/iceberg-rest-catalog.yaml`), not iceberg-rust. — [repo tree](https://github.com/lakehq/sail/tree/main/crates/sail-iceberg), [sail-catalog-iceberg](https://github.com/lakehq/sail/tree/main/crates/sail-catalog-iceberg)
- [P] REST options:
  - `uri`, `warehouse`, `prefix`, `namespace_separator`.
  - `access_delegation`: the value of `X-Iceberg-Access-Delegation`, **default `vended-credentials`**.
  - `oauth_access_token`, `bearer_access_token`.
  - `bearer_access_token_file`: re-read on every request, with one reload-and-retry on 401. Built for rotated tokens such as kubelet-projected service-account tokens.
  - `username`/`password` (Basic).
  - **There is no OAuth2 client-credentials option** (no token-endpoint or client-id setting).

  — [docs/guide/catalog/iceberg-rest.md](https://github.com/lakehq/sail/blob/main/docs/guide/catalog/iceberg-rest.md)
- [P] Catalogs are configured process-wide through the `SAIL_CATALOG__LIST` environment variable. Server `/v1/config` overrides win over client settings. — [iceberg-rest.md](https://github.com/lakehq/sail/blob/main/docs/guide/catalog/iceberg-rest.md), [catalog/index.md](https://github.com/lakehq/sail/blob/main/docs/guide/catalog/index.md)
- [P] Nested namespaces: Sail encodes multipart namespaces with the server's `namespace-separator` (default: the unit separator), with a client-side fallback option. — [iceberg-rest.md](https://github.com/lakehq/sail/blob/main/docs/guide/catalog/iceberg-rest.md)
- [P] **Vended credentials are requested but not applied.** In `begin_table_access`: "`// TODO: Convert preserved REST table-session credentials into operation-scoped FileIO/object-store access instead of only fingerprinting the session.`" The `storage-credentials` entries are only hashed into a session fingerprint and counted. For create+write, Sail logs "returned … vended credentials; using configured object-store credentials". — [crates/sail-catalog-iceberg/src/provider.rs](https://github.com/lakehq/sail/blob/main/crates/sail-catalog-iceberg/src/provider.rs) (lines ~392-490, ~1761-1810)
- [P] S3 access uses the standard AWS credential chain and environment variables. For MinIO-style stores the docs set `AWS_ENDPOINT="http://localhost:9000"`. — [docs/guide/storage/s3.md](https://github.com/lakehq/sail/blob/main/docs/guide/storage/s3.md)
- [P] Iceberg feature matrix:
  - Format versions 1, 2 and 3 for read and write; new tables default to v2.
  - V2 position- and equality-delete **reads**.
  - V3 deletion vectors read **and write**; row lineage; Variant read and write, including shredding.
  - `timestamp_ns` partial: "Spark Connect result-schema conversion does not support nanosecond timestamps".
  - **Geometry and geography not supported.**
  - DELETE, UPDATE and MERGE INTO in copy-on-write (v1-v3) and merge-on-read (v2: position and equality deletes; v3: deletion vectors).
  - Schema evolution on write (`mergeSchema`).
  - Time travel; branch and tag DDL; metadata tables; `rewrite_data_files` binpack.
  - Not supported: snapshot expiry, orphan cleanup, multi-table transactions and streaming.

  — [docs/guide/sources/iceberg/features.md](https://github.com/lakehq/sail/blob/main/docs/guide/sources/iceberg/features.md)
- [P] Recent Iceberg churn: v0.7.3 alone has about 20 Iceberg fixes and features (position deletes for MOR DELETE, branches and tags, TRUNCATE, metadata contracts). v0.7.0 added the bearer token file. v0.7.1 added "retry Iceberg REST bootstrap authentication" and basic MERGE. — [releases API](https://api.github.com/repos/lakehq/sail/releases)
- [P] Deployment options: a PyPI wheel; a Docker image you must build from source ("recommended for production"); a standalone binary (build from source); Kubernetes cluster mode. — [installation](https://github.com/lakehq/sail/blob/main/docs/introduction/installation/index.md), [docker production](https://github.com/lakehq/sail/blob/main/docs/guide/deployment/docker-images/production.md), [kubernetes.md](https://github.com/lakehq/sail/blob/main/docs/guide/deployment/kubernetes.md)
- [P] Footprint: the pysail 0.7.3 manylinux x86_64 wheel is 65.2 MB and declares **no Python dependencies** (`requires_dist` empty). — [PyPI pysail](https://pypi.org/project/pysail/)

#### Daft (Eventual)
- [P] Latest stable **0.7.26, 2026-10-06**; v0.8.0-rc.1 pre-release 2026-11-04. Apache-2.0, 5,846 stars. The wheel is 56.3 MB (manylinux x86_64) and depends on pyarrow, tqdm and packaging. — [releases API](https://api.github.com/repos/Eventual-Inc/Daft/releases), [PyPI daft](https://pypi.org/project/daft/)
- [P] Library: Daft "supports all the catalogs that PyIceberg does". It uses PyIceberg for catalog access and scan planning, then Daft's native Parquet reader. — [docs/connectors/iceberg.md](https://github.com/Eventual-Inc/Daft/blob/main/docs/connectors/iceberg.md), [iceberg_scan.py](https://github.com/Eventual-Inc/Daft/blob/main/daft/io/iceberg/iceberg_scan.py)
- [P] **Vended credentials:** the table's PyIceberg FileIO properties are converted to a Daft `IOConfig`, including `s3.endpoint`, keys, `s3.session-token` and region. **`s3.path-style-access` is not mapped.** — [daft/io/iceberg/_iceberg.py](https://github.com/Eventual-Inc/Daft/blob/main/daft/io/iceberg/_iceberg.py) (lines 25-75)
- [P] Daft's `S3Config.force_virtual_addressing` is documented as: "If False, virtual addressing will only be used if `endpoint_url` is empty, defaults to False". So with a vended endpoint, requests are path-style. — [daft/daft/__init__.pyi](https://github.com/Eventual-Inc/Daft/blob/main/daft/daft/__init__.pyi) (line 573)
- [P] Delete files: "Positional delete files are passed through to the native reader". The scan collects `task.delete_files` paths with no check of delete-file content type. The repo tree has no Puffin or deletion-vector code. — [iceberg_scan.py](https://github.com/Eventual-Inc/Daft/blob/main/daft/io/iceberg/iceberg_scan.py) (lines 185-200, 320-340), [repo tree](https://github.com/Eventual-Inc/Daft)
- [P] Writes: `write_iceberg` in `append` and `overwrite` modes; `overwrite_filter` (static partition overwrite, added in 0.7.25); idempotent commits with `checkpoint=`. Commits go through PyIceberg, with copy-on-write fallback for non-aligned filters. — [iceberg.md](https://github.com/Eventual-Inc/Daft/blob/main/docs/connectors/iceberg.md)
- [P] The type map lists no `timestamp_ns`, variant or geometry. — [iceberg.md](https://github.com/Eventual-Inc/Daft/blob/main/docs/connectors/iceberg.md)

#### Polars
- [P] **py-2.1.0, 2026-10-25** (2.0.0 was 2026-10-06). MIT, 40,334 stars. The wheels are `polars` 0.9 MB plus `polars-runtime-32` 53.9 MB. The `iceberg` extra is `pyiceberg >= 0.12.0` plus `polars-iceberg >= 0.1.0, < 1.0.0`. `polars-iceberg` 0.1.0 (1.2 MB) was uploaded 2026-10-15 and is described as "the native Iceberg scan planning plugin". — [releases API](https://api.github.com/repos/pola-rs/polars/releases), [pyproject.toml](https://github.com/pola-rs/polars/blob/main/py-polars/pyproject.toml), [PyPI polars-iceberg](https://pypi.org/project/polars-iceberg/)
- [P] Library: catalogs are always PyIceberg. The default resolver is `pyiceberg.catalog.load_catalog(name)`, and `IcebergCatalogConfig` is "Configuration for constructing a PyIceberg catalog" (marked unstable). — [_catalog_resolver.py](https://github.com/pola-rs/polars/blob/main/py-polars/src/polars/io/iceberg/_catalog_resolver.py), [_dataset.py](https://github.com/pola-rs/polars/blob/main/py-polars/src/polars/io/iceberg/_dataset.py) (line 213)
- [P] `scan_iceberg(..., catalog=, reader_override='native'|'pyiceberg', fast_deletion_count=…)`. — [scan_iceberg API docs, py-polars 2.1.0](https://docs.pola.rs/api/python/stable/reference/api/polars.scan_iceberg.html) (read via WebFetch)
- [P] **Vended credentials:** the table's Iceberg FileIO properties are translated to object_store options: `s3.endpoint` to `aws_endpoint_url`, `s3.session-token` to `aws_session_token`, and `s3.force-virtual-addressing` to `aws_virtual_hosted_style_request`. **`s3.path-style-access` is not in the map.** — [_dataset.py](https://github.com/pola-rs/polars/blob/main/py-polars/src/polars/io/iceberg/_dataset.py) (lines 1148-1220)
- [P] Deletes:
  - Native reader: position delete files (limited to one data file each) and, since py-1.44.0, "V3 deletion vectors in native `scan_iceberg`".
  - Any other delete content raises "unsupported deletion file type".
  - The native plugin raises NotImplementedError on "e.g. equality deletes" and falls back to PyIceberg planning.

  — [_dataset.py](https://github.com/pola-rs/polars/blob/main/py-polars/src/polars/io/iceberg/_dataset.py) (lines 560-580, 656-760), [release py-1.44.0](https://api.github.com/repos/pola-rs/polars/releases)
- [P] py-1.43.0 removed the hard block on native scans of V3 tables and supports field `initial-default`. — [releases](https://api.github.com/repos/pola-rs/polars/releases)
- [P] Writes:
  - `sink_iceberg` with mode `append` or `overwrite`, `schema_mode` `merge` or `overwrite`, `overwrite_filter` and dynamic partition overwrite (2.1.0); partitioned sinks (2.0.0-rc.1); idempotent commits (2.0.0).
  - `pl.delete_iceberg` (unstable; calls PyIceberg `table.delete`).
  - SQL `CREATE TABLE` and `DROP TABLE [PURGE]` on catalogs attached with `SQLContext.register_catalog` (2.1.0).
  - **No UPDATE or MERGE.**

  — [_sink.py](https://github.com/pola-rs/polars/blob/main/py-polars/src/polars/io/iceberg/_sink.py) (lines 1165-1199), [functions.py](https://github.com/pola-rs/polars/blob/main/py-polars/src/polars/io/iceberg/functions.py) (lines 327-360), [py-2.1.0 notes](https://api.github.com/repos/pola-rs/polars/releases)
- [P] GPU angle: py-2.0.0 "Expose more `scan_iceberg/delta`-related attributes in the visitor for cudf_polars". — [releases](https://api.github.com/repos/pola-rs/polars/releases)

#### chDB / clickhouse-local
- [P] chDB **4.4.0, 2026-09-11** (1.7 MB wheel), which depends on `chdb-core>=26.7.0`. chdb-core latest is 26.9.0 (2026-09-28): a **181.5 MB** manylinux x86_64 wheel (133 MB on macOS) that also needs pandas and pyarrow. Apache-2.0, 2,927 stars. — [PyPI chdb](https://pypi.org/project/chdb/), [PyPI chdb-core](https://pypi.org/project/chdb-core/), [releases API](https://api.github.com/repos/chdb-io/chdb/releases)
- [P] "chDB supports Python 3.9+ on macOS and Linux (x86_64 and ARM64)", so no Windows. It is "mainly based on ClickHouse". — [README](https://github.com/chdb-io/chdb/blob/main/README.md)
- [P] ClickHouse `DataLakeCatalog` settings:
  - `catalog_type='rest'`, `warehouse`.
  - `catalog_credential` (e.g. `<client_id>:<client_secret>`), `oauth_server_uri`, `auth_scope`, `auth_header`.
  - `storage_endpoint`.
  - `vended_credentials` ("supports AWS S3 and Azure ADLS Gen2").
  - Enabled with `SET allow_database_iceberg = 1`.
  - For OneLake bearer tokens the docs say "ClickHouse does not refresh the token".

  — [clickhouse.com/docs/engines/database-engines/datalakecatalog](https://clickhouse.com/docs/engines/database-engines/datalakecatalog)
- [S] A third-party copy of the 26.3 docs calls the engine experimental and opt-in; ClickHouse 25.8 notes list "Writes for Iceberg Rest catalogs". — [EDB docs mirror](https://www.enterprisedb.com/docs/clickhouse/latest/reading_iceberg/), [ClickHouse 25.8 notes](https://clickhouse.com/docs/resources/changelogs/cloud/release-notes/25_08)

#### GlareDB
- [P] Last release **v25.6.3, 2025-06-19**. Repo last pushed **2025-11-14**, so about 12 months without activity. MIT, 1,024 stars. — [releases API](https://api.github.com/repos/GlareDB/glaredb/releases), [repo](https://github.com/GlareDB/glaredb)
- [P] The Iceberg extension takes table **paths** only (`iceberg.metadata`, `iceberg.snapshots`, `iceberg.manifest_list`, …), and the docs say "under heavy development, and functionality may be missing or incomplete". — [docs/integrations/tables-and-catalogs/iceberg.md](https://github.com/GlareDB/glaredb/blob/main/docs/integrations/tables-and-catalogs/iceberg.md)
- [P] The REST catalog module is a stub: 21 not-implemented methods, and no auth code. — [glaredb_ext_iceberg/src/catalog/rest.rs](https://github.com/GlareDB/glaredb/blob/main/crates/glaredb_ext_iceberg/src/catalog/rest.rs)

#### Spice.ai OSS
- [P] **v2.4.2, 2026-11-09** (v2.4.0 2026-10-27). Apache-2.0, 3,150 stars. The single `spiced` binary is a 308.9 MB tar.gz for linux x86_64; CUDA builds are about 365 MB; the CLI is about 23-32 MB. Docker images: `ghcr.io/spiceai/spiceai`. The runtime daemon does not build for native Windows. — [releases API](https://api.github.com/repos/spiceai/spiceai/releases), [docs/DISTRIBUTIONS.md](https://github.com/spiceai/spiceai/blob/trunk/docs/DISTRIBUTIONS.md)
- [P] Library: a **fork of iceberg-rust 0.11.0**: `spiceai/iceberg-rust`, branch `spiceai-0.11.0-df-55`, used for `iceberg`, `iceberg-catalog-rest` and `iceberg-datafusion`. It runs on a forked DataFusion 55.2.0 and Arrow 59.3.2. — [Cargo.toml](https://github.com/spiceai/spiceai/blob/trunk/Cargo.toml) (lines 341-346, 599, 681-693)
- [P] REST auth params: `iceberg_token`, `iceberg_oauth2_credential` (`client_id:secret`), `iceberg_oauth2_server_url`, `iceberg_oauth2_scope` (default `catalog`), SigV4. — [Spice docs, Iceberg catalog connector](https://github.com/spiceai/docs/blob/trunk/website/docs/components/catalogs/iceberg.md), [catalogconnector/iceberg.rs](https://github.com/spiceai/spiceai/blob/trunk/crates/runtime/src/catalogconnector/iceberg.rs) (lines 211-330)
- [P] **Vended endpoints are guarded.** "Spice refuses a catalog-supplied endpoint that resolves to a private, loopback, or link-local address, so a REST catalog that points tables at a local or private S3-compatible store, such as MinIO, needs `iceberg_s3_endpoint` and `iceberg_s3_region` set in the Spicepod."
  - In code, `VendedEndpointGuard` treats operator-configured endpoints as trusted. "Vended credentials are passed through unchanged".
  - The env var `SPICE_URL_TABLE_ALLOW_PRIVATE_HOSTS` disables the check.

  — [Spice docs](https://github.com/spiceai/docs/blob/trunk/website/docs/components/catalogs/iceberg.md), [catalogconnector/iceberg.rs](https://github.com/spiceai/spiceai/blob/trunk/crates/runtime/src/catalogconnector/iceberg.rs) (lines 700-870, 940-1075)
- [P] `iceberg_s3_path_style_access` defaults to `true` (path-style). — [Spice docs](https://github.com/spiceai/docs/blob/trunk/website/docs/components/catalogs/iceberg.md)
- [P] Nested namespaces: the catalog `from:` URL takes `%1F`-separated parts. A table in `unity/very/nested/namespace` is exposed as `<name>.namespace.foobar`, i.e. under the leaf name. v2.4.0 fixed "keep nested child namespaces when loading an Iceberg catalog". — [Spice docs](https://github.com/spiceai/docs/blob/trunk/website/docs/components/catalogs/iceberg.md), [v2.4.0 notes](https://github.com/spiceai/spiceai/blob/trunk/docs/release_notes/v2.4.0.md)
- [P] Formats V1, V2 and V3 are supported. v2.4.0 "reader applies deletion vectors from Puffin files" and carries row ids and sequence numbers. — [Spice docs](https://github.com/spiceai/docs/blob/trunk/website/docs/components/catalogs/iceberg.md), [v2.4.0 notes](https://github.com/spiceai/spiceai/blob/trunk/docs/release_notes/v2.4.0.md)
- [P] Writes need `access: read_write`. `INSERT INTO` is **append-only**, including partitioned tables. "`DELETE FROM` is supported on Iceberg v2+ tables, written as equality delete files". UPDATE and MERGE are not mentioned. — [Spice docs](https://github.com/spiceai/docs/blob/trunk/website/docs/components/catalogs/iceberg.md)

#### Apache DataFusion Ballista
- [P] Ballista **55.0.0, 2026-10-08**. Apache-2.0, 2,173 stars. It offers SQL and DataFrames from Rust and Python, plus an optional Arrow Flight SQL frontend (`flight-sql` feature). The README **does not mention Iceberg**. — [README](https://github.com/apache/datafusion-ballista/blob/main/README.md), [releases API](https://api.github.com/repos/apache/datafusion-ballista/releases)

#### GPU engines (brief)
- [S] Sirius: an open-source GPU-native SQL engine that runs as a DuckDB extension on cuDF and RMM. Unsupported operators fall back to CPU. CIDR 2026 paper. The DuckDB docs page says the extension is "not yet available", which conflicts with the GitHub README. No Iceberg-specific support was found. — [CIDR 2026](https://vldb.org/cidrdb/2026/rethinking-analytical-processing-in-the-gpu-era.html), [sirius-db/sirius](https://github.com/sirius-db/sirius), [duckdb.org Sirius page](https://duckdb.org/docs/2.0/core_extensions/sirius)
- [S] Theseus: Voltron Data's commercial distributed GPU engine on libcudf. The vendor claims Iceberg in-place querying, a Kubernetes-native control plane and air-gapped support. These are vendor marketing claims, not verified. — [voltrondata.com](https://www.voltrondata.com), [arXiv 2508.05029](https://arxiv.org/html/2508.05029v1)

#### MotherDuck (comparison only)
- [P] "Iceberg REST catalog reads and writes run on MotherDuck's cloud compute. Attaching an Iceberg REST catalog directly in a local DuckDB session without MotherDuck is not recommended." Catalogs attach with `CREATE DATABASE … TYPE ICEBERG`, and a secret holds a token or `CLIENT_ID`/`CLIENT_SECRET`/`OAUTH2_SERVER_URI`. — [MotherDuck Iceberg docs](https://motherduck.com/docs/integrations/file-formats/apache-iceberg/)

#### Others worth a line
- [P] Bodo (2026.1 docs): REST catalogs use a connection string `iceberg+http(s)://<rest-uri>?warehouse=…&token=…`, where "token or credential is required". — [docs.bodo.ai 2026.1 read_write](https://docs.bodo.ai/2026.1/guides/iceberg/read_write/)
- [S] Bodo "does not support delete files or merge-on-read yet". — [docs.bodo.ai latest intro, via search; the direct fetch returned 404](https://docs.bodo.ai/latest/iceberg/intro/)

### Inferences
- **Synthesis matrix** (from the findings above):

| Engine | Iceberg lib | REST auth: static JWT / client-creds at external IdP / auto-refresh | Vended creds and vended `s3.endpoint` used? | Nested ns | v2 pos / eq deletes | v3 DV | Writes | Runs as |
|---|---|---|---|---|---|---|---|---|
| DataFusion + iceberg-rust 0.11 | iceberg-rust | yes / yes (`oauth2-server-uri`) / **no** | `config` map yes, `storage-credentials` no; no delegation header unless `header.*` set | client yes; DF catalog lists top level only | yes / yes | yes (0.11) | append, CREATE/DROP | Rust lib, `iceberg-playground` built from source |
| Sail 0.7.3 | own (Rust) | yes, plus a token file / **no** / file re-read | **header sent, creds not applied**; uses own env S3 config | yes | yes / yes | read+write | full DML, MERGE, schema evolution | pip wheel, Spark Connect and Flight SQL server, K8s |
| Spice 2.4.2 | iceberg-rust 0.11 fork | yes / yes / no evidence | yes, but **a private-IP vended endpoint is refused** unless repeated in the Spicepod | yes, via `%1F` | yes / yes (iceberg-rust) | yes | append, DELETE as eq-deletes | single binary or Docker server |
| Daft 0.7.26 | PyIceberg + native reader | as PyIceberg | yes (endpoint and STS mapped; path-style implicit) | as PyIceberg | pos yes / **eq unclear** | no evidence | append, overwrite, filtered overwrite | Python lib, Ray for distribution |
| Polars 2.1.0 | PyIceberg + `polars-iceberg` planner | as PyIceberg | yes (endpoint and STS mapped) | as PyIceberg | pos yes / **eq no** (PyIceberg cannot read them) | yes (native) | append, overwrite, delete (CoW via PyIceberg), CREATE/DROP | Python lib |
| chDB 4.4 | ClickHouse's own | `catalog_credential` + `oauth_server_uri` | `vended_credentials` setting | see ClickHouse assessment | per ClickHouse | per ClickHouse | per ClickHouse | in-process lib (Linux/macOS) |
| GlareDB 25.6.3 | own | none (stub) | n/a | n/a | n/a | n/a | none | dormant |
| Ballista 55 | none | n/a | n/a | n/a | n/a | n/a | n/a | no Iceberg |

- [I] Against the StarRocks 4.1.7 failure mode (vended endpoint ignored, keys sent to AWS):
  - **iceberg-rust/DataFusion, Daft, Polars and Spice** honour the vended `s3.endpoint` when the catalog returns it in `config`.
  - **Spice** additionally refuses it when it resolves to a private address, failing closed rather than sending to AWS. The fix is to repeat `iceberg_s3_endpoint`/`iceberg_s3_region`, or set `SPICE_URL_TABLE_ALLOW_PRIVATE_HOSTS`.
  - **Sail** ignores vended credentials entirely. With no `AWS_ENDPOINT` set it would use its ambient AWS chain and default AWS endpoints, so the endpoint and static keys must be repeated in its environment.
  - **chDB/ClickHouse** has a separate `storage_endpoint` setting; whether vended `s3.endpoint` overrides it should be taken from the ClickHouse assessment.
- [I] iceberg-rust uses only `LoadTableResult.config`, not `storage-credentials`. If keel returns STS keys only in `storage-credentials`, then DataFusion/iceberg-rust and Spice will not use them, and they will fall back to ambient credentials. Keel should put `s3.access-key-id`, `s3.secret-access-key`, `s3.session-token`, `s3.endpoint` and `s3.path-style-access=true` in `config` as well.
- [I] iceberg-rust and Spice do not send `X-Iceberg-Access-Delegation` by default. If keel vends only when that header is present, iceberg-rust users must add `header.X-Iceberg-Access-Delegation=vended-credentials` (possible in `.icebergrc` because props pass verbatim). Spice's documented parameter list has no generic header option, so with Spice keel would have to vend by default.
- [I] iceberg-rust defaults to virtual-host addressing (since 0.10.0). Keel must vend `s3.path-style-access=true` (or the client must set it) for Silo or Ceph without wildcard DNS. Spice defaults to path-style. Daft and Polars use path-style whenever an endpoint is set, because the Daft `force_virtual_addressing` and object_store `virtual_hosted_style_request` defaults are false. The object_store default is recalled, not read.
- [I] With a client-credentials JWT, iceberg-rust (and so the playground and probably Spice) fetches a token once and never refreshes it. Long-lived processes will start failing with 401 when the IdP's token expires. Sail's token-file option suits Kubernetes, where an external sidecar or kubelet rotates the file.
- [I] DataFusion's `IcebergCatalogProvider` builds schemas only from top-level namespaces, so a table in `a.b.c.d` is not reachable through `catalog.schema.table` SQL. You would register `IcebergTableProvider`s per table in code. Spice exposes nested tables under the leaf namespace name, which can collide when two branches share a leaf name.
- [I] Air-gapped install:
  - Sail (one 65 MB wheel, no dependencies, plus `pyspark-client`) and Spice (one 309 MB binary or an image) are the easiest to mirror.
  - Polars needs about 4 wheels; Daft needs pyarrow plus PyIceberg and its about 12 dependencies; chDB needs a 181 MB core plus pandas and pyarrow.
  - DataFusion/iceberg-rust needs a Rust toolchain and vendored crates, since the playground is not published.
  - None of these engines has a runtime extension autoloader of the kind DuckDB has (comparison point). This comes from the absence of any such mechanism in the docs read, not from a positive statement.

### Gaps
- Exact PyIceberg behaviour (does it send `X-Iceberg-Access-Delegation` by default, auth-manager options, token refresh) was not re-checked here, because PyIceberg is assessed elsewhere. Daft's and Polars' REST auth inherit it entirely.
- Whether Daft returns wrong rows, raises, or applies equality deletes correctly when PyIceberg plans equality-delete files: the code passes all delete-file paths without a type check, and I found no test or doc either way.
- Whether Spice's Iceberg path refreshes OAuth2 tokens, or re-requests vended STS keys when they expire during long accelerations: not found.
- Whether vended `s3.endpoint` overrides `storage_endpoint` in chDB's embedded ClickHouse, and which ClickHouse version chdb-core 26.9.0 embeds. The 26.x numbering suggests ClickHouse 26.9, but I found no statement of it.
- Runtime memory and CPU footprint numbers: no engine publishes them in the pages read; only package and binary sizes were found.
- `apache/datafusion-iceberg` has no release yet, so it is unknown when a post-0.11 `iceberg-datafusion` (with or without nested-namespace or token-refresh fixes) will ship.
- Bodo's licence and current open-source status were not verified. The Bodo docs intro page returned 404 when fetched directly.

## Key question 2: which engines can serve many users (a SQL endpoint over Postgres wire, Arrow Flight SQL or Spark Connect) versus a single process?

### Takeaway
**Sail** (Spark Connect server, plus an Arrow Flight SQL server and Kubernetes cluster mode) and **Spice** (an Arrow Flight SQL and HTTP SQL server with an mTLS and auth module) are the only in-scope engines with a built-in multi-user endpoint over Iceberg. DataFusion/iceberg-rust, Polars, Daft and chDB are in-process libraries or CLIs. Ballista has a Flight SQL frontend but no Iceberg support. None of the in-scope engines offers a Postgres-wire endpoint in the material read. Neither multi-user server passes each end user's own catalog token through to keel: both use one catalog identity per server configuration.

### Cited Findings
- [P] Sail: `sail flight server --ip … --port 32010`, or `pysail.flight.FlightSqlServer`, starts an Arrow Flight SQL server, and any Flight SQL client (e.g. ADBC) can connect. — [docs/guide/integrations/flight-sql.md](https://github.com/lakehq/sail/blob/main/docs/guide/integrations/flight-sql.md)
- [P] Sail is "Compatible with the Spark Connect protocol" and can be deployed "on Kubernetes and run … in cluster mode for distributed processing". — [README](https://github.com/lakehq/sail/blob/main/README.md), [kubernetes.md](https://github.com/lakehq/sail/blob/main/docs/guide/deployment/kubernetes.md)
- [P] Sail catalogs are process-level (`SAIL_CATALOG__LIST`). Listing caches can be `global` or per-`session`. — [catalog/index.md](https://github.com/lakehq/sail/blob/main/docs/guide/catalog/index.md)
- [P] Spice: the runtime has an Arrow Flight / Flight SQL server (`crates/runtime/src/flight/flightsql.rs`, `mtls.rs`, `handshake.rs`), an HTTP API (`crates/runtime/src/http/…`) and a `runtime-auth` crate. — [spiceai repo tree](https://github.com/spiceai/spiceai/tree/trunk/crates/runtime/src/flight)
- [P] Spice also serves its own Iceberg REST API (`/v1/namespaces/{ns}/tables/{t}`), which returns 400 for accelerated datasets. — [v2.4.0 notes](https://github.com/spiceai/spiceai/blob/trunk/docs/release_notes/v2.4.0.md)
- [P] Ballista: clients submit plans over gRPC or "SQL over Arrow Flight SQL" (`flight-sql` feature). — [README](https://github.com/apache/datafusion-ballista/blob/main/README.md)
- [P] iceberg-rust's `AuthManager::contextual_session(&SessionContext, …)` is the hook a custom multi-user server would use for per-user tokens. — [auth/mod.rs](https://github.com/apache/iceberg-rust/blob/main/crates/catalog/rest/src/auth/mod.rs)
- [P] chDB is "In-process SQL OLAP Engine… no need to install ClickHouse server". — [clickhouse.com/docs/chdb](https://clickhouse.com/docs/chdb)
- [P] MotherDuck serves many users, but only from its own cloud. — [MotherDuck Iceberg docs](https://motherduck.com/docs/integrations/file-formats/apache-iceberg/)

### Inferences
- [I] Per-user token passthrough:
  - In Sail and Spice, every user of the server reaches keel with the server's single configured token or credential. keel's per-user authorisation would collapse to one service identity unless a gateway or proxy does token exchange.
  - In the library engines (Polars, Daft, chDB, DataFusion), each user's own process can hold that user's JWT, so passthrough is natural.
  - A custom DataFusion Flight SQL server could implement per-user passthrough with iceberg-rust's `contextual_session`, but no shipped server does.
- [I] Authentication on Sail's own Spark Connect and Flight SQL endpoints is not documented in the pages read. Plan to front them with a proxy or network policy.

### Gaps
- Sail Spark Connect / Flight SQL server authentication and TLS: not found in the docs read.
- Spice's endpoint authentication (API keys, mTLS) was inferred from file names only. The auth docs were not read.
- No in-scope engine with a Postgres-wire server was found. GlareDB's old Postgres-protocol server was not checked because the project is dormant.

## Key question 3: which are production-ready versus experimental for Iceberg reads and writes as of November 2026?

### Takeaway
- **Most production-shaped for keel:** Spice 2.4.x reads (v1-v3 including deletion vectors), with append and equality-delete DELETE writes. It is a versioned 2.x product with weekly releases, and has the private-endpoint caveat.
- **Production-usable as libraries, with caveats:**
  - Polars 2.x reads: native position deletes and DVs, but no equality deletes; several Iceberg APIs are flagged unstable.
  - Daft 0.7.x reads and appends: position deletes only, no DV evidence.
- **Capable but pre-1.0 and churning:** Sail 0.7.x. It has the broadest v3 and DML feature set, but vended credentials are not wired in, and every release carries many Iceberg fixes.
- **Experimental or in transition:** DataFusion + iceberg-rust 0.11. The library is solid on deletes and v3 reads, but the integration just changed repository, it has no token refresh, and writes are append-only.
- **Not viable:** GlareDB (dormant, stub) and Ballista (no Iceberg).
- **chDB:** follows ClickHouse's DataLakeCatalog maturity, which is gated behind an opt-in setting.

### Cited Findings
- [P] Spice releases: v2.4.2 2026-11-09, v2.4.1 2026-10-30, v2.3.4 2026-10-28 (maintained branches). Iceberg writes are documented: append-only INSERT, plus DELETE as equality deletes. — [releases API](https://api.github.com/repos/spiceai/spiceai/releases), [Spice docs](https://github.com/spiceai/docs/blob/trunk/website/docs/components/catalogs/iceberg.md)
- [P] Polars marks `IcebergCatalogConfig`, `delete_iceberg` and `reader_override` as **unstable**. Its native planning plugin `polars-iceberg` is 0.1.0, first uploaded 2026-10-15. — [_dataset.py](https://github.com/pola-rs/polars/blob/main/py-polars/src/polars/io/iceberg/_dataset.py), [functions.py](https://github.com/pola-rs/polars/blob/main/py-polars/src/polars/io/iceberg/functions.py), [PyPI](https://pypi.org/project/polars-iceberg/)
- [P] Daft: the latest stable is 0.7.26; 0.8.0-rc.1 is a pre-release (2026-11-04) that adds name-mapping reads. — [releases API](https://api.github.com/repos/Eventual-Inc/Daft/releases)
- [P] Sail v0.7.3 notes list about 20 Iceberg fixes and features, e.g. "fix: write position deletes for Iceberg merge-on-read DELETE", "fix: preserve unknown Iceberg REST commit outcomes", "fix: enforce Iceberg metadata contracts". — [releases API](https://api.github.com/repos/lakehq/sail/releases)
- [P] Sail's own matrix marks commit conflict handling as partial ("limited metadata publication retries"); snapshot expiry, orphan cleanup and multi-table transactions are unsupported. — [features.md](https://github.com/lakehq/sail/blob/main/docs/guide/sources/iceberg/features.md)
- [P] iceberg-rust is pre-1.0 (0.11.0). `iceberg-datafusion` 0.11.x is the last release from the Iceberg project, and the successor repo has no release yet. — [datafusion README @v0.11.0](https://github.com/apache/iceberg-rust/blob/v0.11.0/crates/integrations/datafusion/README.md), [datafusion-iceberg API](https://api.github.com/repos/apache/datafusion-iceberg)
- [P] GlareDB Iceberg: "under heavy development". Last release June 2025. — [iceberg.md](https://github.com/GlareDB/glaredb/blob/main/docs/integrations/tables-and-catalogs/iceberg.md), [releases API](https://api.github.com/repos/GlareDB/glaredb/releases)
- [P] ClickHouse DataLakeCatalog requires `SET allow_database_iceberg = 1`. — [ClickHouse docs](https://clickhouse.com/docs/engines/database-engines/datalakecatalog)

### Inferences
- [I] For keel's tables written by other engines with **equality deletes** (Flink/streaming upserts), only the iceberg-rust family (DataFusion, Spice) and Sail read them correctly. Polars cannot, and Daft is unproven. For **v3 deletion vectors**, Sail, Spice, iceberg-rust 0.11 and Polars read them. **Geometry and geography** are unsupported everywhere in scope.
- [I] Rough maturity ranking for read-only analytics against keel today: Spice ≈ Polars > Daft > DataFusion/iceberg-rust (library-grade, DIY server) > Sail (blocked on vended credentials) > chDB (depends on ClickHouse findings) >> GlareDB and Ballista (not usable).

### Gaps
- No public adoption data (named production users) was found for any engine's *Iceberg* path specifically. Stars and release cadence are the only adoption signals collected.
- Whether Sail plans to finish vended-credential wiring (the TODO in `provider.rs`): no issue or roadmap was checked.

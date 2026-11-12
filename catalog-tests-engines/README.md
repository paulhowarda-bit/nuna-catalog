# catalog-tests-engines: the nightly engine matrix (M8, D31)

Spark, Trino, Flink, PyIceberg, DuckDB and SingleStore run against the catalog server in docker compose.
Each engine creates, writes, reads, evolves (adds a column, then writes and reads with it) and drops v2 and v3
tables in a three-level namespace `m8.deep.<engine>`, then reads three tables Spark left in
`m8.shared.spark`: v2 and v3 (already evolved), and v3 with a deletion vector.

## Running it

```
./mvnw verify -pl catalog-tests-engines -am -Pheavy -Dtest='EngineMatrixTest*' -Dsurefire.failIfNoSpecifiedTests=false
```

The test is tagged `heavy`, so the fast build compiles it and starts nothing. It needs Docker, the internet
the first time (images, Maven Central jars and PyPI packages are baked into three local images), and the
`singlestoredb-dev` container already running. `-Dengines.keep-stack=true` leaves the stack up afterwards;
`target/engines-run/` then holds the token, `compose.log` (every command and its output) and `results.txt`.

## How it works

- `compose/compose.yaml`, project `fidice-engines`: PostgreSQL 18, Kafka 4.2.2 (256 MB heap), the S3 store
  `-Ds3` chooses (Silo or Ceph as compose service `s3`, or real AWS), and the catalog server (the
  `catalog-server` exec jar on `eclipse-temurin:25.0.5_8-jre-noble`, configured by
  `compose/catalog/engines.properties`, the shape of `ConformanceServer`, plus the `storage.properties` the
  harness writes for the store). One profile per engine. No host port is published: every client runs in the
  compose network, so the storage profile's endpoint, `S3_ENDPOINT` (`http://s3:9000` on Silo,
  `http://s3:8080` on Ceph), is the one engines use with vended credentials. On Ceph the harness creates the
  STS role after the stack is up; on AWS the bucket and role are the run's own and are deleted at the end.
- The harness makes an RS256 key and token with `TestIdp` (issuer `https://idp.test/engines`, group
  `engine-clients`). A one-shot `seed` container creates the bucket on Silo or Ceph. A small `idp` file
  server serves the JWKS inside the network (AWS blocks public buckets), so the catalog never calls back into
  the Windows host. Grants (`create`, `modify` on catalog
  `engines`) are inserted by SQL, as `ConformanceServer` does.
- Every engine authenticates with the static bearer token and asks for `X-Iceberg-Access-Delegation:
  vended-credentials`; none is given storage keys. Each prints `RESULT <engine> <check> <value>` lines (and
  `LIMIT ...` for an expected refusal); the test asserts the whole set, so a skipped step fails like a wrong
  answer. Flink SQL shows neither a table's format version nor whether a namespace exists, so its `run.sh`
  asks the catalog's REST API for those between the two SQL scripts. The Flink SQL client stops at a failed
  statement but exits 0, so `run.sh` fails on its `[ERROR]` output.
- Engines run one at a time and are removed after their test; the whole stack is removed (`down -v`) at the
  end. SingleStore is joined to the network for its test only; Iceberg ingest is off in the dev image, so the
  test switches `enable_iceberg_ingest` on, records the previous value and sets it back, then drops its
  database and detaches.

## Versions

| Engine | Image / package | Iceberg |
|---|---|---|
| Spark 4.1.4 (Scala 2.13, Java 21) | `apache/spark:4.1.4-scala2.13-java21-python3-ubuntu` + `iceberg-spark-runtime-4.1_2.13`, `iceberg-aws-bundle` | 1.12.0 |
| Trino 484 | `trinodb/trino:484` (native S3 file system) | 1.12.0 (bundled) |
| Flink 2.3.0 (Java 21) | `flink:2.3.0-scala_2.12-java21` + `iceberg-flink-runtime-2.3`, `iceberg-aws-bundle`, `hadoop-client-api`/`-runtime` 3.4.3 | 1.12.0 |
| PyIceberg 0.12.0 | `python:3.12-slim-bookworm` + `pyiceberg[pyarrow]==0.12.0` | — |
| DuckDB 2.0.0 | same image + `duckdb==2.0.0`, extensions `iceberg`, `httpfs`, `avro` installed at build | — |
| SingleStore 10.9.0 | the running `ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88` | (its own) |

Catalog stack: `postgres:18`, `apache/kafka:4.2.2`, `pgsty/silo:RELEASE.2026-10-26T00-00-00Z` or
`ghcr.io/clyso/ceph-test:v20.2.5`, `busybox:1.37.0`, `eclipse-temurin:25.0.5_8-jre-noble`.

## Results (2026-11-09)

All six tests pass (`Tests run: 6, Failures: 0`, 653 s). "pass" means the engine did the step and the value
it read back was the expected one: three rows summing to 6 after the first write, four rows with one value
in the added column after evolution, the table gone after the drop, the format version reported back.

| Engine | create | write | read | evolve | drop | deep namespace | v2 | v3 | reads Spark's v2 / v3 / v3+DV |
|---|---|---|---|---|---|---|---|---|---|
| Spark | pass | pass | pass | pass | pass (purge requested) | pass, `m8.deep.spark` | pass | pass | (writes them) |
| Trino | pass | pass | pass | pass | pass | pass, with `nested-namespace-enabled` | pass | pass | pass / pass / pass |
| Flink | pass | pass | pass | pass | pass (no purge requested) | pass, through `base-namespace` | pass | pass | pass / pass / pass |
| PyIceberg | pass | v2 pass; **v3 refused by the client** | pass (v3: the empty table) | v2 pass; v3 schema only (no data to write) | pass (purge requested) | pass | pass | create, read, schema change, drop; no data writes | pass / pass / pass |
| DuckDB | pass | pass | pass | pass | pass (purge requested) | pass, with `SUPPORT_NESTED_NAMESPACES` | pass | pass | pass / pass / pass |
| SingleStore | n/a (read-only client, D35) | n/a | pass, by pipeline | n/a; ingests the evolved tables | n/a | pass, `table_id` `m8.shared.spark.t_v2` | pass | **pass** (docs say v1/v2 only) | pass / pass / pass |

Limitations, each shown rather than skipped:

- **PyIceberg 0.12.0 writes no v3 data.** The catalog creates the v3 table (PyIceberg reads back format 3),
  the append fails in the client with `ValueError: Cannot write manifest for table version: 3`, and the test
  asserts that exact refusal. The empty v3 table still reads, takes a new column and drops; Spark's v3 tables,
  with and without a deletion vector, read correctly.
- **SingleStore** creates, writes, evolves and drops nothing in Iceberg (D35); it reads by pipeline. Its docs
  promise v1/v2 Parquet only, but 10.9.0 ingested Spark's v3 table and the v3 table with a deletion vector
  correctly (three rows, the deleted one gone). That is observed behaviour of this build, not a documented
  promise.

Run times on the laptop, shared with other Docker work (the second full run; in the first, the base stack
took 102 s, Spark 146 s, Flink 207 s and SingleStore 105 s):

| Step | Time |
|---|---|
| Base stack up (PostgreSQL, Kafka, Silo, seed, catalog) and grants | 77 s |
| Spark (image already built) | 110 s |
| Trino (server start included) | 101 s |
| Flink (local cluster start included) | 240 s |
| PyIceberg | 26 s |
| DuckDB | 22 s |
| SingleStore | 57 s |
| Whole class, teardown included | 653 s |

The first build of the three local images (Iceberg runtimes, the Hadoop client, Python wheels, DuckDB
extensions) adds a few minutes; later runs reuse them.

## What the engines showed about the catalog

- No catalog defect turned up. In a full run whose stack was kept and inspected, the catalog log had no
  errors (two Kafka "unknown topic" warnings while the audit topics were auto-created) and the audit topic
  held no denial and no authentication failure.
- The scripts create each namespace level explicitly, because the catalog refuses a namespace whose parent
  does not exist (D28).
- Drops are always soft (D25): Spark, PyIceberg and DuckDB ask for a purge, Flink does not, and the catalog
  answers both the same way. Files stay until the maintenance worker (v1.x).
- Vended credentials (READ_WRITE, no delete) were enough for every engine; no engine was given storage keys.

## Engine settings that matter

- **Spark:** `type=rest`, `token`, `header.X-Iceberg-Access-Delegation=vended-credentials`,
  `io-impl=S3FileIO`, `rest-catalog-purge=true` (F10: `DROP TABLE ... PURGE` asks the catalog to purge rather
  than deleting files itself; vended credentials cannot delete anyway). Nested namespaces work as
  `fid.m8.deep.spark`.
- **Trino:** `iceberg.rest-catalog.nested-namespace-enabled=true` (schemas are dotted names such as
  `"m8.deep.trino"`), `vended-credentials-enabled=true`, and
  `server-assigned-table-location-enabled=true`: by default Trino derives a table location from the namespace,
  but this catalog assigns every location (D28). `s3.endpoint` and path-style access are still set in the
  catalog file. The image's health check gives up after about 40 s, so compose gives it a 5-minute start period.
- **Flink:** a Flink database is one namespace level. Deep namespaces come from the Iceberg catalog option
  `base-namespace`: three catalogs (`base-namespace` unset, `m8`, `m8.deep`) create the levels, and in the
  last one database `flink` is namespace `m8.deep.flink`. The Iceberg Flink catalog needs Hadoop classes on
  the classpath (the shaded `hadoop-client-*` jars). `DROP TABLE` does not request a purge.
- **PyIceberg:** nested namespaces are tuples; `purge_table` sends `purgeRequested=true`.
- **DuckDB:** `ATTACH ... (TYPE iceberg, SUPPORT_NESTED_NAMESPACES true, ACCESS_DELEGATION_MODE
  'vended_credentials', PURGE_REQUESTED true)`; schemas are dotted names. With a nested namespace,
  `iceberg_load_table_response` needs the table name as a string, and `iceberg_metadata`,
  `iceberg_snapshots` and `iceberg_table_properties` refuse the name outright ("Too many identifiers", or a
  version-hint lookup on storage); plain SQL on the tables is unaffected.
- **SingleStore:** `CREATE PIPELINE ... LOAD DATA S3 '' CONFIG '{"catalog_type": "REST", "catalog.uri",
  "catalog.warehouse", "table_id": "m8.shared.spark.t_v2", "catalog.token",
  "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
  "endpoint_url": "${S3_ENDPOINT}", "region", "ingest_mode": "one_time"}' CREDENTIALS '{}' ... FORMAT
  ICEBERG`. Needs `SET GLOBAL enable_iceberg_ingest = 1` first (off in the dev image).

# catalog-tests-engines: the nightly engine matrix (M8, D31)

Spark, Kafka Connect, PyIceberg and SingleStore against the catalog server, on the Kubernetes stack the matrix
shares with the demo (`demo/k8s`, see `demo/README.md`): the catalog deployed by its Helm chart
(`deploy/helm/keel-catalog`), PostgreSQL, Kafka, kafbat, the S3 store and SingleStore. Spark and PyIceberg create, write, read,
evolve (add a column, then write and read with it) and drop v2 and v3 tables in a three-level namespace
`m8.deep.<engine>`. Kafka Connect's Iceberg sink consumes a Kafka topic and commits its records into a table it
creates. PyIceberg and SingleStore read seven tables Spark left in `m8.shared.spark`: v2 and v3 (already
evolved), v3 with a deletion vector, and four that took DELETE, UPDATE and MERGE INTO under copy-on-write or
merge-on-read, on v2 and v3 (see "Row-level writes" below). (Trino, Flink and DuckDB were dropped on
2026-11-12; the 2026-11-09 results below still show them.)

## Running it

```
./gradlew :catalog-tests-engines:test -Pnightly --tests EngineMatrixTest
```

The test is tagged `overnight`: the fast and heavy builds compile it and start nothing; the overnight run
(`-Pnightly`) runs it. It needs Docker, `kubectl` and
`helm` on the PATH (and `kind` for the default cluster), and the internet the first time (images, Maven
Central jars and PyPI packages are baked into local images). On its own, everything runs in namespace
`keel-engines`.

| Switch | Effect |
|---|---|
| `-Ps3=silo\|ceph\|aws` | The S3 store, as for every other test (default `silo`). |
| `-Pengines.cluster=<context>` | Deploy into an existing cluster, for example `docker-desktop`, instead of a kind cluster. The namespace is recreated at the start and deleted at the end; the cluster's nodes must see locally built images, as Docker Desktop's do. |
| `-Pengines.keep-stack=true` | Leave everything up afterwards. |
| `-Pengines.skip-build=true` | Use the images already built instead of building them. |
| `-Pengines.stack=<namespace>` | Run the engines against the stack already up in that namespace of `-Pengines.cluster` instead of deploying one; nothing but the engines' Jobs is created or deleted. Needs `-Pengines.token-file=<file>`, a token the stack's issuer signed for group `engine-clients`. `demo/demo.sh engines` runs it against the demo. |

By default the harness creates kind cluster `keel-engines` with its own kubeconfig,
`build/engines-run/kubeconfig`, so the current kubectl context is never changed, and deletes it at the end.
`build/engines-run/` holds the token, `commands.log` (every command and its output), the rendered manifests
and `results.txt`.

With the stack kept, the kafbat UI shows the Kafka topics (the Kafka Connect source topic `m8-kafka-events`, the
sink's control topic, the catalog's audit and change topics), their messages and consumer groups:

```
KUBECONFIG=build/engines-run/kubeconfig kubectl -n keel-engines port-forward svc/kafka-ui 8080:8080
```

then http://localhost:8080 (with `-Pengines.cluster=docker-desktop`, use `--context docker-desktop` instead of
`KUBECONFIG`).

## How it works

- On its own, the harness creates kind cluster `keel-engines`, builds the catalog image (`deploy/image/Dockerfile`, from the runnable jar this build makes)
  and the three engine images (`images/`), pulls the infrastructure images, and loads them all into the
  cluster: nothing is pulled from inside it.
- The stack is the demo's manifests. `demo/k8s/stack.yaml`: PostgreSQL 18, Kafka 4.2.2 (256 MB heap), `idp`,
  a busybox file server for the issuer's JWKS, and `kafka-ui` (kafbat). The S3 store follows `-Ps3`:
  `demo/k8s/store-silo.yaml` or `demo/k8s/store-ceph.yaml` as Service `s3`
  (`http://s3:9000` on Silo, `http://s3:8080` on Ceph), and a `seed` Job (`demo/k8s/seed.yaml`) creates the
  bucket; on AWS there is no
  store in the cluster and the bucket and role are the run's own, deleted at the end. On Ceph the harness
  creates the STS role once the store is up. No pod gets Service environment variables: the Kafka image
  reads every `KAFKA_*` variable as broker configuration.
- The catalog is installed with `helm upgrade --install keel-catalog deploy/helm/keel-catalog`, the chart
  `deploy/` documents, with one server: engines reach it as Service `keel-catalog`. Its values are the
  stack's (`demo/k8s/catalog-values.yaml`: the database, catalogs `lakehouse` and `engines`), plus this run's:
  the issuer in `k8s/catalog.properties` and storage profile `s3` for the store; the store's secret key goes
  in the chart's Secret.
  The harness makes an RS256 key and token with `TestIdp` (issuer `https://idp.test/engines`, group
  `engine-clients`); grants (`create`, `modify` on catalog `engines`) are inserted by SQL, as
  `ConformanceServer` does.
- Each engine runs as a Job (`k8s/engine-job.yaml`), authenticates with the static bearer token and asks for
  `X-Iceberg-Access-Delegation: vended-credentials`; none is given storage keys. Each prints
  `RESULT <engine> <check> <value>` lines (and `LIMIT ...` for an expected refusal) to its log; the test asserts
  the whole set, so a skipped step fails like a wrong answer. Jobs are deleted after their test, and the whole
  cluster at the end.
- SingleStore (`demo/k8s/singlestore.yaml`, the plain dev image) starts for its test only; the harness switches
  `enable_iceberg_ingest` on (off in the dev image) and runs `k8s/singlestore/ingest.sql` in it, into database
  `keel_m8`. Against the demo's stack it uses the demo's SingleStore, which also holds `ops` and `lake`.

## Versions

| Engine | Image / package | Iceberg |
|---|---|---|
| Spark 4.1.4 (Scala 2.13, Java 21) | `apache/spark:4.1.4-scala2.13-java21-python3-ubuntu` + `iceberg-spark-runtime-4.1_2.13`, `iceberg-aws-bundle` | 1.12.0 |
| Kafka Connect 4.2.2 | `apache/kafka:4.2.2` + the Iceberg sink (`iceberg-kafka-connect`, `-parquet`, `-orc`, `-aws`, `-aws-bundle`, `hadoop-client-api`/`-runtime` 3.4.3), resolved by `images/kafka-connect/pom.xml` | 1.12.0 |
| PyIceberg 0.12.0 | `python:3.12-slim-bookworm` + `pyiceberg[pyarrow]==0.12.0` | — |
| SingleStore 10.9.0 | `ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88` | (its own) |

Cluster: kind (or `-Pengines.cluster`), `postgres:18`, `apache/kafka:4.2.2`, `kafbat/kafka-ui:v1.5.0`, `pgsty/silo:RELEASE.2026-10-26T00-00-00Z` or
`ghcr.io/clyso/ceph-test:v20.2.5`, `busybox:1.37.0`; the catalog image is `eclipse-temurin:25.0.5_8-jre-noble`
with the exec jar unpacked into Spring Boot's layers.

## Kafka Connect (2026-11-12)

Checked on Silo against the catalog image on a plain Docker network (the cluster run is still to do): the sink
created namespace `m8.deep.kafka` and v3 table `t_events` through the catalog, committed the three records
produced to `m8-kafka-events`, added column `note` when a fourth record carried it, and the run script dropped
both through the REST API. The settings that matter:

- `iceberg.tables.auto-create-enabled=true` and `iceberg.tables.evolve-schema-enabled=true`, schemaless JSON
  values (`value.converter.schemas.enable=false`); the sink creates missing namespaces itself.
- `iceberg.catalog.type=rest`, `token`, `header.X-Iceberg-Access-Delegation=vended-credentials`,
  `io-impl=S3FileIO`; the control topic is `m8-kafka-control`, committing every 2 s.
- `iceberg-orc` is on the plugin path although the sink writes Parquet: Iceberg 1.12's generic writers register
  Parquet, Avro and ORC together, and without ORC none registers, so nothing is ever written.
- The worker sets `offset.flush.interval.ms=1000`. Connect polls an idle sink task about once per offset flush
  (60 s by default), and the sink polls its control topic only then; at that pace the control consumer misses
  its group join and every commit times out with nothing written.
- Connect only writes, so `run.sh` reads the table back through the catalog's REST API (`Catalog.java`, a
  small Java client: the Kafka image has no curl).

## Row-level writes (2026-11-12)

Spark makes four tables in `m8.shared.spark`, one per format version and write mode: `t_v2_cow`, `t_v2_mor`,
`t_v3_cow` and `t_v3_mor`, with `write.delete.mode`, `write.update.mode` and `write.merge.mode` all
`copy-on-write` or all `merge-on-read`. Each gets four rows in one data file
(`INSERT ... SELECT /*+ COALESCE(1) */ ...`, and the test asserts one file: with a file per row, Spark could
meet `DELETE WHERE id = 2` by dropping a whole file from metadata, and no row-level path would run). Then:

1. `DELETE FROM t WHERE id = 2`
2. `UPDATE t SET note = 'u' WHERE id = 3`
3. `MERGE INTO t USING (VALUES (4, 'd', 'm'), (5, 'e', 'n')) s ON t.id = s.id WHEN MATCHED THEN UPDATE SET
   note = s.note WHEN NOT MATCHED THEN INSERT *`

Four rows are left, ids summing to 13, notes `u,m,n` in id order; a MERGE that missed id 4 would leave `x`,
which count and sum alone would not notice. Spark also reports the mechanism: the current snapshot's delete
files (`<table>.delete_files`: kinds, files, rows deleted) and the snapshot operations from first to current
(`<table>.snapshots`, following each parent). PyIceberg and SingleStore read the four tables back: rows, sum
and notes.

On the demo stack (Silo), 2026-11-12, every value as predicted before the run:

| Table | Spark: format, rows, sum, notes | Delete files in the current snapshot | Snapshot operations | PyIceberg 0.12.0 / SingleStore 10.9.0 |
|---|---|---|---|---|
| `t_v2_cow` | 2, 4, 13, `u,m,n` | none | append, overwrite, overwrite, overwrite | 4, 13, `u,m,n` / same |
| `t_v2_mor` | 2, 4, 13, `u,m,n` | one position-delete file (Parquet), 3 rows | append, delete, overwrite, overwrite | 4, 13, `u,m,n` / same |
| `t_v3_cow` | 3, 4, 13, `u,m,n` | none | append, overwrite, overwrite, overwrite | 4, 13, `u,m,n` / same |
| `t_v3_mor` | 3, 4, 13, `u,m,n` | one deletion vector (Puffin), 3 rows | append, delete, overwrite, overwrite | 4, 13, `u,m,n` / same |

- Copy-on-write rewrites the data file at each statement; a commit that removes and adds data files is an
  `overwrite`, and no delete file is ever written.
- Merge-on-read leaves the data file and adds delete files against it. The DELETE adds only a delete file,
  which Iceberg records as `delete`; UPDATE and MERGE also add the changed rows, so `overwrite`.
- One delete file is left holding all three deleted rows: Spark (Iceberg 1.12, file-granularity deletes by
  default) merges a data file's earlier deletes into the new delete file and removes the old one, and a v3
  data file may have only one deletion vector.
- No client refused any of the four tables, so there is no `LIMIT` line for them. SingleStore's docs promise
  v1/v2 only; 10.9.0 applied v2 position deletes and v3 deletion vectors alike.

## Results (2026-11-09, docker compose, before the 2026-11-12 changes)

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
  answers both the same way (2026-11-09 run). Files stay until the maintenance worker (v1.x).
- Vended credentials (READ_WRITE, no delete) were enough for every engine; no engine was given storage keys.

## Engine settings that matter

- **Spark:** `type=rest`, `token`, `header.X-Iceberg-Access-Delegation=vended-credentials`,
  `io-impl=S3FileIO`, `rest-catalog-purge=true` (F10: `DROP TABLE ... PURGE` asks the catalog to purge rather
  than deleting files itself; vended credentials cannot delete anyway). Nested namespaces work as
  `fid.m8.deep.spark`.
- **PyIceberg:** nested namespaces are tuples; `purge_table` sends `purgeRequested=true`.
- **SingleStore:** `CREATE PIPELINE ... LOAD DATA S3 '' CONFIG '{"catalog_type": "REST", "catalog.uri",
  "catalog.warehouse", "table_id": "m8.shared.spark.t_v2", "catalog.token",
  "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
  "endpoint_url": "${S3_ENDPOINT}", "region", "ingest_mode": "one_time"}' CREDENTIALS '{}' ... FORMAT
  ICEBERG`. Needs `SET GLOBAL enable_iceberg_ingest = 1` first (off in the dev image).

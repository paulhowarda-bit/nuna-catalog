# catalog-tests-engines: the nightly engine matrix (M8, D31), the demo

The catalog server, deployed by its Helm chart (`deploy/helm/fidice-catalog`) into a throwaway kind cluster,
with Spark, Kafka Connect, PyIceberg and SingleStore against it. Spark and PyIceberg create, write, read,
evolve (add a column, then write and read with it) and drop v2 and v3 tables in a three-level namespace
`m8.deep.<engine>`. Kafka Connect's Iceberg sink consumes a Kafka topic and commits its records into a table it
creates. PyIceberg and SingleStore read three tables Spark left in `m8.shared.spark`: v2 and v3 (already
evolved), and v3 with a deletion vector. (Trino, Flink and DuckDB were dropped on 2026-11-12; the 2026-11-09
results below still show them.)

## Running it

```
./mvnw verify -pl catalog-tests-engines -am -Pheavy -Dtest='EngineMatrixTest*' -Dsurefire.failIfNoSpecifiedTests=false
```

The test is tagged `heavy`, so the fast build compiles it and starts nothing. It needs Docker, `kind`,
`kubectl` and `helm` on the PATH, and the internet the first time (images, Maven Central jars and PyPI
packages are baked into local images). `-Ds3=silo|ceph|aws` picks the S3 store, as for every other test.
The cluster gets its own kubeconfig, `target/engines-run/kubeconfig`, so the current kubectl context is never
changed. `-Dengines.keep-stack=true` leaves the cluster up afterwards
(`KUBECONFIG=target/engines-run/kubeconfig kubectl get pods`); `target/engines-run/` holds the token,
`commands.log` (every command and its output), the rendered manifests and `results.txt`.

## How it works

- The harness creates kind cluster `fidice-engines`, builds the catalog image (`catalog-server/Dockerfile`)
  and the three engine images (`images/`), pulls the infrastructure images, and loads them all into the
  cluster: nothing is pulled from inside it.
- `k8s/infra.yaml`: PostgreSQL 18, Kafka 4.2.2 (256 MB heap) and `idp`, a busybox file server for the test
  issuer's JWKS. The S3 store follows `-Ds3`: `k8s/store-silo.yaml` or `k8s/store-ceph.yaml` as Service `s3`
  (`http://s3:9000` on Silo, `http://s3:8080` on Ceph), and a `seed` Job creates the bucket; on AWS there is no
  store in the cluster and the bucket and role are the run's own, deleted at the end. On Ceph the harness
  creates the STS role once the store is up. No pod gets Service environment variables: the Kafka image
  reads every `KAFKA_*` variable as broker configuration.
- The catalog is installed with `helm upgrade --install catalog deploy/helm/fidice-catalog`, its config being
  `k8s/catalog.properties` plus the database settings and storage profile `s3` the harness adds for the store.
  The harness makes an RS256 key and token with `TestIdp` (issuer `https://idp.test/engines`, group
  `engine-clients`); grants (`create`, `modify` on catalog `engines`) are inserted by SQL, as
  `ConformanceServer` does.
- Each engine runs as a Job (`k8s/engine-job.yaml`), authenticates with the static bearer token and asks for
  `X-Iceberg-Access-Delegation: vended-credentials`; none is given storage keys. Each prints
  `RESULT <engine> <check> <value>` lines (and `LIMIT ...` for an expected refusal) to its log; the test asserts
  the whole set, so a skipped step fails like a wrong answer. Jobs are deleted after their test, and the whole
  cluster at the end.
- SingleStore runs as a pod (`k8s/singlestore.yaml`) for its test only; the harness switches
  `enable_iceberg_ingest` on (off in the dev image) and runs `k8s/singlestore/ingest.sql` in it.

## Versions

| Engine | Image / package | Iceberg |
|---|---|---|
| Spark 4.1.4 (Scala 2.13, Java 21) | `apache/spark:4.1.4-scala2.13-java21-python3-ubuntu` + `iceberg-spark-runtime-4.1_2.13`, `iceberg-aws-bundle` | 1.12.0 |
| Kafka Connect 4.2.2 | `apache/kafka:4.2.2` + the Iceberg sink (`iceberg-kafka-connect`, `-parquet`, `-orc`, `-aws`, `-aws-bundle`, `hadoop-client-api`/`-runtime` 3.4.3), resolved by `images/kafka-connect/pom.xml` | 1.12.0 |
| PyIceberg 0.12.0 | `python:3.12-slim-bookworm` + `pyiceberg[pyarrow]==0.12.0` | — |
| SingleStore 10.9.0 | `ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88` | (its own) |

Cluster: kind, `postgres:18`, `apache/kafka:4.2.2`, `pgsty/silo:RELEASE.2026-10-26T00-00-00Z` or
`ghcr.io/clyso/ceph-test:v20.2.5`, `busybox:1.37.0`; the catalog image is `eclipse-temurin:25.0.5_8-jre-noble`
with the exec jar.

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

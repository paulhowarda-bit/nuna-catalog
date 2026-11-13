# catalog-tests-engines: the nightly engine matrix (M8, D31)

Spark, Kafka Connect, PyIceberg and SingleStore against the catalog server, on the Kubernetes stack the matrix
shares with the demo (`demo/k8s`, see `demo/README.md`): the catalog deployed by its Helm chart
(`deploy/helm/keel-catalog`), PostgreSQL, Kafka, kafbat, the S3 store and SingleStore. Spark and PyIceberg create, write, read,
evolve (add a column, then write and read with it) and drop v2 and v3 tables in a three-level namespace
`m8.deep.<engine>`. Kafka Connect's Iceberg sink consumes a Kafka topic and commits its records into a table it
creates. PyIceberg and SingleStore read the tables Spark left in `m8.shared.spark`: v2 and v3 (already
evolved), v3 with a deletion vector, four that took DELETE, UPDATE and MERGE INTO under copy-on-write or
merge-on-read, on v2 and v3 (see "Row-level writes" below), and those of the cases after it: schema and
partition evolution, time travel, branches and write-audit-publish, maintenance procedures, the v2 to v3
upgrade, v3 features, CREATE OR REPLACE, a view and table statistics. PyIceberg also deletes, overwrites and
upserts in a v2 table of its own, which SingleStore reads. (Trino, Flink and DuckDB were dropped on
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

Checked first on Silo against the catalog image on a plain Docker network, then on 2026-11-12 in the cluster,
against the demo's stack (`demo/demo.sh engines`, 104 s): the sink
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

## Schema evolution (2026-11-12)

Spark makes `t_schema` (v2) as `id INT, name STRING, old_note STRING, gone STRING, info STRUCT<a: INT>` and
writes two rows. Then, one commit each: `RENAME COLUMN old_note TO note`, `DROP COLUMN gone`,
`ALTER COLUMN id TYPE BIGINT`, `ADD COLUMN info.b STRING`, `ALTER COLUMN name FIRST`; and a third row in the
new layout. Iceberg reads columns by field ID, so the old rows keep their notes and struct values under the new
names and types.

| Reader | Columns | Rows, sum of ids, notes, sum of `info.a`, `info.b` |
|---|---|---|
| Spark | `struct<name:string,id:bigint,note:string,info:struct<a:int,b:string>>` | 3, 6, `n1,n2,n3`, 60, `x` |
| PyIceberg | `name,id,note,info`; field IDs kept: `note` 3, `info.b` 7 (new), `id` long | same |
| SingleStore | its pipeline maps the current names (`note <- note`, `info_a <- info::a`) | same |

SingleStore maps columns by name, but only the pipeline's mapping: its Iceberg reader resolves the old files
by field ID, so the renamed column was not lost. No refusal, so no `LIMIT` line.

## Partition evolution and sort order (2026-11-12)

`t_partition` (v2) takes two rows at each step: unpartitioned (spec 0); `ADD PARTITION FIELD cat` (spec 1);
`REPLACE PARTITION FIELD cat WITH bucket(4, id)` (spec 2); `DROP PARTITION FIELD bucket(4, id)`, which leaves
a v2 spec with no fields, spec 0's twin, so Iceberg makes spec 0 the default again; and
`WRITE ORDERED BY id`, with the rows sent as 10, 9.

- Spark: ten rows, ids summing to 55; rows per spec `0:6,1:2,2:2`; `sort-order` `id ASC NULLS FIRST`, the two
  rows written after it in files with sort order 1, 9 before 10 within the file.
- PyIceberg: ten rows, 55; three specs, default spec 0, sort order 1. SingleStore: ten rows, 55.

## Time travel (2026-11-12)

`t_travel` (the catalog's default format, v3): ids 1-3 in the first snapshot, id 4 in the second, and
`CREATE TAG first AS OF VERSION <first>`. In Spark, `VERSION AS OF <first snapshot id>`,
`TIMESTAMP AS OF timestamp_millis(<its commit time>)` and `VERSION AS OF 'first'` each read three rows (sum 6);
the table reads four (sum 10). PyIceberg reads three rows through `snapshot_by_name("first")` and through
`snapshot_as_of_timestamp(<first commit time>)`, and four from the current snapshot.

## Branches and write-audit-publish (2026-11-12)

- `t_branch`: three rows on main, `CREATE BRANCH audit`, `CREATE TAG v1`, then
  `INSERT INTO t_branch.branch_audit` one row. Main still reads three, the branch four.
  `CALL fid.system.fast_forward('m8.shared.spark.t_branch', 'main', 'audit')` moves main to the branch's
  snapshot (main reads four; tag `v1` still three). Refs: `audit` and `main` branches, `v1` a tag.
  PyIceberg: main four rows, `v1` three, `audit` and `main` on the same snapshot.
- `t_wap` with `write.wap.enabled=true`: three rows, then one with `spark.wap.id=wap-1`, which Spark stages (a
  snapshot main does not reference, `wap.id` in its summary): main still reads three.
  `cherrypick_snapshot` publishes it; its parent is main's snapshot, so Iceberg fast-forwards main to the staged
  snapshot rather than writing a new one: four rows, two snapshots. PyIceberg reads the same.

## Maintenance procedures (2026-11-12)

`t_maint` is v2 merge-on-read: three appends of one file each (ids 1-2, 3-4, 5-6), then `DELETE ... WHERE id
IN (1, 3)`, one position-delete file per data file. Then the procedures, each with the vended credentials Spark
always gets (READ_WRITE, no `s3:DeleteObject`, D25):

| Procedure | What it did | Data and delete files after |
|---|---|---|
| `rewrite_position_delete_files` (`rewrite-all`) | rewrote 2 delete files into 2 (still one per data file) | 3 data; 2 position deletes, 2 rows |
| `rewrite_data_files` (`rewrite-all`) | rewrote 3 data files into 1 with the deletes applied; its commit removed the 2 file-scoped deletes (`removed-delete-files` 2 in the snapshot summary; the procedure's `removed_delete_files_count` is 0, as it counts only its optional `remove-dangling-deletes` pass); no failures | 1 data; none |
| `rewrite_manifests` | before: 4 data manifests (the new file's and three holding only the replaced files as deleted entries) and 1 delete manifest (the two removed deletes); rewrote the 4 into 1, left the lone delete manifest | 1 data manifest, 1 delete manifest |
| `expire_snapshots` (`older_than` now + 1 s, `retain_last` 1) | the catalog accepted the commit: 7 snapshots to 1. Deleted 0 data files, 0 position deletes, 0 manifests, 0 manifest lists: every delete was refused, and the three replaced data files are still in storage (`exists()` through the table's FileIO) | unchanged |
| `remove_orphan_files` | default: fails, it lists through Hadoop's `FileSystem` and the image has no `s3` scheme (`LIMIT`); with `prefix_listing => true` it lists through S3FileIO and finds 0 orphans, as it only takes files older than 3 days; an `older_than` within 24 hours is refused (`LIMIT`) | unchanged |

Four rows (2, 4, 5, 6, sum 17) throughout. PyIceberg reads 4 rows, 17, one snapshot; SingleStore 4 rows, 17.
So engine-side maintenance works against the catalog for everything that is a commit; physical clean-up is the
maintenance worker's (D25), and files an engine expires stay until it runs.

## v2 to v3 upgrade (2026-11-12)

`t_upgrade`: v2 merge-on-read, four rows in one file, `DELETE ... WHERE id = 2` (one Parquet position-delete
file, 1 row), then `SET TBLPROPERTIES ('format-version'='3')` and `DELETE ... WHERE id = 3`. A v3 data file has
at most one deletion vector, so Spark merges the old position delete into the new vector and removes the Parquet
file: one deletion vector (Puffin) with 2 rows, ids 1 and 4 left (sum 5), operations append, delete, delete.
PyIceberg reads format 3, 2 rows, 5; SingleStore 2 rows, 5.

## v3 features (2026-11-12)

| Feature | Spark 4.1.4 / Iceberg 1.12.0 | PyIceberg 0.12.0 |
|---|---|---|
| Row lineage (`t_lineage`) | three rows in one file: `_row_id` 0,1,2, `_last_updated_sequence_number` 1,1,1; after `UPDATE ... WHERE id = 2` (copy-on-write) row IDs 0,1,2 kept, sequence numbers 1,2,1 | `next-row-id` 6 (3 appended + 3 rewritten), first row IDs 0 and 3 |
| Default values | refused both ways (`LIMIT`): `CREATE TABLE` with `DEFAULT` ("does not support column default value") and `ADD COLUMN ... DEFAULT` ("setting default values in Spark is currently unsupported") | — |
| `variant` (`t_variant`) | two JSON documents written with `parse_json`, read with `variant_get`: 2 rows, `$.a` summing to 3, `$.b` `x,y` | cannot load the table (`LIMIT`): `Unsupported field type: 'variant'` |
| `geometry(4326)`, `geography(4326)` (`t_geo`) | only with `spark.sql.geospatial.enabled=true` (refused without it, `LIMIT`); `POINT (1 2)` as WKB written to both and read back unchanged, SRID 4326 | cannot load the table (`LIMIT`): Iceberg Java writes the type as `geometry(OGC:CRS84)`, and PyIceberg 0.12 parses only a quoted CRS |

## CREATE OR REPLACE TABLE (2026-11-12)

`t_replace` (v3): two snapshots (ids 1-3, then 4), then `CREATE OR REPLACE TABLE ... AS SELECT` two rows. The
table keeps its history: three snapshots, the new one an `overwrite` (Spark writes the query as an overwrite)
with no parent, as main starts afresh; two rows (sum 30); the old snapshot still reads four rows. PyIceberg:
three snapshots, two rows, the current one a root.

A second replace whose query changes a column's type (`id` from bigint to string) was refused by the catalog
until 2026-11-12: `Cannot change field 1 (id) from long to string: not a valid type promotion`, reported as a
`LIMIT` line (`replace.retype`). A replace starts main afresh, and Iceberg's reference handler accepts the new
schema (it keeps field IDs by name, whatever the type), but `Backstops.schemas` judged a replace's schema
against the replaced one as if it were evolved in place. Paul asked for the fix (D11, amended 2026-11-12): a
replace of a table with rows is now exempt from the type and required checks, and a change in place is not.
Spark prints `RESULT spark replace.retype 4 1 string`, which `EngineMatrixTest` expects; a refusal would still
print the `LIMIT` line, and fail the test.

## Views (2026-11-12)

Spark creates view `v_rows` on `t_v2` (`id <= 2`: 2 rows, sum 3). Iceberg's Spark extensions refuse
`ALTER VIEW ... AS` ("Use CREATE OR REPLACE VIEW instead", a `LIMIT` line), so `CREATE OR REPLACE VIEW` changes
the query (rows with a note: 1 row, id 4) and `ALTER VIEW ... SET TBLPROPERTIES` sets its comment. A second view
is created and dropped; `SHOW VIEWS` lists `v_rows` only. PyIceberg loads `v_rows`: current version 2 of 2,
columns `id,name,note`, the comment, and the Spark SQL with its `WHERE note IS NOT NULL`.

## Table statistics (2026-11-12)

Iceberg 1.12's Spark runtime has `compute_table_stats`. On `t_v2` it writes a Puffin statistics file into the
table's metadata folder and commits it (`set-statistics`). PyIceberg sees one statistics file, for the current
snapshot, with one `apache-datasketches-theta-v1` blob per column and its distinct count: `id` 4, `name` 4,
`note` 1.

## PyIceberg writes (2026-11-12)

PyIceberg creates `m8.shared.pyiceberg.t_writes` (v2), appends ids 1-4 (`a`-`d`), then `delete("id = 2")`,
`overwrite` of id 3 with `c2` (`overwrite_filter="id = 3"`) and `upsert` of id 4 (to `d2`) and id 5 (`e`) with
`join_cols=["id"]`. PyIceberg 0.12 rewrites files (copy-on-write; it has no merge-on-read): a delete that
matches part of a file commits an `overwrite`; `overwrite` is that delete plus an `append`; `upsert` (1 row
updated, 1 inserted) overwrites the changed row away, then appends it and the new row. Result: 4 rows, sum 13,
`a,c2,d2,e`; operations append, overwrite, overwrite, append, overwrite, append, append. SingleStore runs after
PyIceberg and reads the same 4 rows.

## Kafka Connect upsert: not supported (2026-11-12)

The Apache Iceberg 1.12.0 Kafka Connect sink has no upsert or equality-delete mode: its configuration
(`IcebergSinkConfig`) has no upsert or CDC setting (the Tabular sink's `iceberg.tables.upsert-mode-enabled` and
`iceberg.tables.cdc-field` were not carried over), and it writes through append-only writers
(`UnpartitionedWriter`, `PartitionedAppendWriter`). `iceberg.tables.default-id-columns` only sets identifier
fields on tables it creates. So the matrix has no Kafka Connect upsert case.

## Run time (2026-11-12)

Against the demo's stack on Docker Desktop (`demo/demo.sh engines`), shared with other Docker work: 4 tests,
0 failures, 508 s for the class in one run and 462 s in the next (341 s before the cases above were added).
In the first, Spark 202 s, Kafka Connect 104 s, PyIceberg 100 s, SingleStore 82 s. Spark prints a `TIME` line per case (seconds, ignored by the test): lifecycle
37, the shared tables 13, row-level writes 36, schema 4, partition 6, time travel 3, branches and WAP 5,
maintenance 27, upgrade 3, v3 features 5, replace 3, views 1, statistics 1. The rest of Spark's time is
starting the pod and the session. Times vary by about a factor of two between runs on the shared laptop.

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

- Until 2026-11-12 no catalog defect turned up. In a full run whose stack was kept and inspected, the catalog
  log had no errors (two Kafka "unknown topic" warnings while the audit topics were auto-created) and the
  audit topic held no denial and no authentication failure.
- Defect found 2026-11-12 (`LIMIT spark replace.retype`) and fixed the same day: the catalog refused a
  `CREATE OR REPLACE TABLE` whose new schema changes a column's type (HTTP 400, `Cannot change field 1 (id)
  from long to string: not a valid type promotion`). `Backstops.schemas` judged every added schema against the
  current one, which is right for in-place evolution, but a replace starts main afresh and Iceberg's reference
  handler accepts it. See "CREATE OR REPLACE TABLE" above. The matrix has not been run since the fix.
- Every commit an engine-side maintenance procedure makes is accepted: rewrites of data files, position
  deletes and manifests, `expire_snapshots`, `fast_forward`, `cherrypick_snapshot`, statistics. The file
  deletes those procedures attempt are refused by the vended credentials (D25), so expired files stay in
  storage for the maintenance worker.
- The scripts create each namespace level explicitly, because the catalog refuses a namespace whose parent
  does not exist (D28).
- Drops are always soft (D25): Spark, PyIceberg and DuckDB ask for a purge, Flink does not, and the catalog
  answers both the same way (2026-11-09 run). Files stay until the maintenance worker (v1.x).
- Vended credentials (READ_WRITE, no delete) were enough for every engine; no engine was given storage keys.

## Engine settings that matter

- **Spark:** `type=rest`, `token`, `header.X-Iceberg-Access-Delegation=vended-credentials`,
  `io-impl=S3FileIO`, `rest-catalog-purge=true` (F10: `DROP TABLE ... PURGE` asks the catalog to purge rather
  than deleting files itself; vended credentials cannot delete anyway). Nested namespaces work as
  `fid.m8.deep.spark`. `geometry` and `geography` columns need `spark.sql.geospatial.enabled=true`.
  `remove_orphan_files` needs `prefix_listing => true`: by default it lists through Hadoop's `FileSystem`,
  which has no `s3` scheme in the image.
- **PyIceberg:** nested namespaces are tuples; `purge_table` sends `purgeRequested=true`. 0.12 cannot load a
  table with a `variant`, `geometry` or `geography` column (see "v3 features").
- **SingleStore:** `CREATE PIPELINE ... LOAD DATA S3 '' CONFIG '{"catalog_type": "REST", "catalog.uri",
  "catalog.warehouse", "table_id": "m8.shared.spark.t_v2", "catalog.token",
  "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
  "endpoint_url": "${S3_ENDPOINT}", "region", "ingest_mode": "one_time"}' CREDENTIALS '{}' ... FORMAT
  ICEBERG`. Needs `SET GLOBAL enable_iceberg_ingest = 1` first (off in the dev image).

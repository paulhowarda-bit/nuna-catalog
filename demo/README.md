# Local demo: the catalog, its console, SingleStore both ways, and the engine matrix, on Kubernetes

One stack on Docker Desktop's Kubernetes, shared with the engine matrix: PostgreSQL, Kafka, kafbat, Silo, the
catalog server (with the console) and SingleStore. `up` writes sample Iceberg tables, syncs SingleStore and
Iceberg in both directions and prints sign-in tokens; `engines` runs Spark, Kafka Connect, PyIceberg and
SingleStore's ingest against the same stack. Everything is local and uses test keys; nothing here is fit for
production.

## Start

```bash
demo/demo.sh up
```

- Needs bash (Git Bash on Windows), Docker Desktop with Kubernetes on, `kubectl`, `helm`, `curl`, `unzip`,
  and JDK 25 (`JAVA_HOME`, or `java` on the path). Every `up` builds the server jar and the change-feed
  connector jar from this checkout (no tests run) and the images below, so the stack never runs code older
  than the checkout; when nothing changed, Gradle and Docker do nothing. The cluster never pulls an image: it
  runs them from the local Docker engine, and `up` pulls the public ones there.
- Everything runs in namespace `fidice-demo` (`FIDICE_DEMO_NAMESPACE`) of context `docker-desktop`
  (`FIDICE_DEMO_CONTEXT`); `kubectl` and `helm` never use your current context. The stack is `demo/k8s`, the
  same manifests the engine matrix deploys when it runs on its own:
  - `stack.yaml`: PostgreSQL, Kafka, kafbat, and `idp`, which serves the demo issuer's keys
    (`demo/Mint.java`).
  - `store-silo.yaml` and `seed.yaml`: Silo as Service `s3`, and the job that makes the warehouse bucket.
  - The catalog: the Helm chart `deploy/helm/fidice-catalog`, release `fidice-catalog`, one server, image
    `fidice-catalog:demo` (`deploy/image/Dockerfile`), values `catalog-values.yaml` (both catalogs:
    `lakehouse` for the demo's data, `engines` for the matrix's) and `demo-values.yaml` (the demo's issuer and
    Silo).
  - `singlestore.yaml`: image `fidice-demo-singlestore:<connector hash>`, the dev image with the change-feed
    connector built in (`singlestore.Dockerfile`). A changed connector replaces the pod, and `up` loads the
    new one again.
  - `ports.yaml`: the ports below.
  - The lake writer runs as a job when needed (`lake-job.yaml`, image `fidice-demo-lake:local`).
- Ports on localhost, from Docker Desktop's LoadBalancer Services: **8181** (catalog and console), **8182**
  (kafbat) and **8183** (SingleStore Studio). `up` stops if something else holds one of them, such as a
  `kubectl port-forward`.
- Memory: about 5 GB of requests (SingleStore 2 GB, up to 3 GB; the catalog and kafbat 768 MB each; Kafka
  640 MB; Silo 512 MB; PostgreSQL 384 MB), plus one job at a time: the lake writer 768 MB, or an engine up to
  2 GB (Spark). Docker Desktop's Kubernetes has about 7.5 GB, so nothing else of that size fits beside it.
- No volumes: the data lives as long as the pods.

At the end it prints the console URL, <http://localhost:8181/ui/>, and the tokens.

## Sign in

The demo turns on the console's token box (`fidice.ui.dev-token-login=true`, off by default). Paste a token:

| User | Group | What the catalog lets them do |
|---|---|---|
| `ana` | `demo-analysts` | select on the whole catalog: browse, load tables and views, read governance |
| `gary` | `demo-grant-admins` | grant admin: list, grant and revoke, and read the audit trail; no data access, so loading a table is refused |
| `stella` | `demo-stewards` | manage-tags: classifications, owners, legal holds |
| `oscar` | `demo-operators` | platform operator: DR freeze |
| `singlestore` | `demo-loaders` | create and modify; SingleStore's pipelines use it, both ways |
| `lake` | `demo-loaders` | create and modify; the lake writer, standing in for another engine |
| `engine-matrix` | `engine-clients` | create and modify on catalog `engines`; `demo.sh engines` runs the engines with it |

`ana` can also browse catalog `engines`, where the engine matrix's tables land.

Separation of duties means no one group holds data rights and grant rights, so try the console as `ana` and as
`gary`. Every refusal is the server's, shown as it answered.

Tokens are valid for 7 days. Print them again (re-minted if they are about to expire; SingleStore's running
pipelines are then replaced so they carry the new one):

```bash
demo/demo.sh token
```

Tokens are in `~/.fidice-demo/tokens/<user>.jwt`. A curl call as a user:

```bash
curl -H "Authorization: Bearer $(cat ~/.fidice-demo/tokens/ana.jwt)" "http://localhost:8181/v1/lakehouse/namespaces"
```

## What is loaded

Catalog `lakehouse`, written by `demo/LakeWriter.java` (the Iceberg Java library, through the catalog with
vended credentials, as any engine would):

- `markets.equities.eu.xetra.trades`: v2, partitioned by day, sorted by symbol; five appends, a row-level
  delete, two added columns (one a nested struct), a second partition spec, a tag `eod-2026-11-09` and a
  branch `surveillance-review`; classified `internal` and under a legal hold.
- `markets.fx.rates` (v3, evolved), `markets.equities.us.quotes` (v3), `reference.countries` (v2, under a DR
  freeze), `finance.ledger.journal` (v3, classified `confidential`) and `finance.ledger.accounts` (v2).
- `finance` is marked regulated, and `accounts` has no classification of its own, so the catalog refuses to
  load it for anyone (D27): the console shows that refusal.
- View `markets.equities.eu.xetra_daily`, with two versions, in SingleStore's dialect.
- Grants by SQL for the groups, plus two made through the API by `gary`.

Written by SingleStore's change feed: `markets.equities.eu.xetra.orders`, the copy of `ops.orders`.

## SingleStore

```bash
demo/demo.sh sql
```

Or Studio on <http://localhost:8183> (user `root`, password `fidice-test`). Two databases, and a third once
the engines have run:

- `ops`: SingleStore's own tables. `ops.orders` is copied into Iceberg as it changes.
- `lake`: tables loaded from Iceberg: `xetra_trades` and `countries` once, `fx_rates` continuously, and a
  view `xetra_daily`.
- `fidice_m8`: the engine matrix's SingleStore check, Spark's shared tables loaded from catalog `engines`.

### SingleStore to Iceberg: the change feed

The pipeline `ops.orders_to_lake` is a connector pipeline running `singlestore-change-feed` (no Kafka). It
reads `OBSERVE` on `ops.orders` and commits to `markets.equities.eu.xetra.orders` every 5 seconds while
there are changes: new rows as data files, and equality deletes on the primary key for updated and deleted
rows. Each commit records SingleStore's offsets in the Iceberg snapshot, so the feed resumes from Iceberg
after any failure, with nothing copied twice or skipped. Each commit's receipt lands in `ops.lake_commits`.

```sql
UPDATE ops.orders SET status = 'FILLED', updated_at = NOW(6) WHERE order_id = 1005;
-- a few seconds later
SELECT kind, rows_written, keys_deleted, committed_at FROM ops.lake_commits ORDER BY committed_at;
```

Then open the `orders` table in the console: each receipt is a snapshot there.

- If SingleStore no longer holds its log from where the copy ended (the feed was stopped across several
  `SNAPSHOT DATABASE`s), the feed takes a new snapshot of the table and replaces the copy. The commit is
  `resnapshot`, and `gap_after` (in `ops.lake_commits` and in the Iceberg snapshot summary) holds the offsets
  it could not continue from.
- No pipeline may load `markets.equities.eu.xetra.orders`: its rows would come back into SingleStore. The
  feed checks every minute and stops with an error naming the pipeline if one does.
- `ALTER` and `DROP` on `ops.orders` wait while the feed runs (`OBSERVE` holds the table). Stop the pipeline
  first; a column added at the end is added to the Iceberg copy when the feed starts again.
- If the feed fails (the loop guard, a table change it cannot follow), the pipeline stops in `Error`.
  `START PIPELINE ops.orders_to_lake` resumes it from the offsets in Iceberg.
- If SingleStore fails to write a batch of receipts (it ran out of memory, say), the commits are already in
  Iceberg. SingleStore starts the feed again from the last receipt it stored, and the feed sends the later
  ones again, read from the snapshots, so `ops.lake_commits` still lists every commit.

### Iceberg to SingleStore: Iceberg pipelines

```bash
demo/demo.sh rate
```

appends an EURUSD rate to `markets.fx.rates` through the catalog; the pipeline `lake.fx_rates_feed` loads it
into `lake.fx_rates` within a few seconds.

SingleStore reserves each pipeline extractor's Java heap from its own memory, and keeps idle extractors
pooled. An Iceberg pipeline's data workers default to 500 MB each, so two loads and a feed are enough to stop
all writes in the demo's 3 GB pod. `seed.sql` caps them at 128 MB
(`pipelines_iceberg_data_workers_heap_size`).

## The engines

```bash
demo/demo.sh engines
```

runs the engine matrix (`catalog-tests-engines`, `EngineMatrixTest`) against this stack instead of one of its
own: Spark, Kafka Connect and PyIceberg as Kubernetes jobs, then SingleStore's ingest in the demo's SingleStore,
all into catalog `engines` as `engine-matrix`. It takes about 4 minutes once the engine images exist; the first
run builds them, which takes longer. It leaves Spark's shared tables in `m8.shared.spark`, which the console
shows, and SingleStore's copies in database `fidice_m8`.
Results: `catalog-tests-engines/build/engines-run/results.txt`, and every command in `commands.log` there.

## Kafka

kafbat on <http://localhost:8182> shows the stack's Kafka: the catalog's audit and change topics, and once the
engines have run, Kafka Connect's source topic `m8-kafka-events` and the sink's control topic.

## Stop

```bash
demo/demo.sh down
```

This deletes the namespace and everything in it. The signing key and tokens stay in `~/.fidice-demo`; the
next `up` loads the sample data again.

```bash
demo/demo.sh status
```

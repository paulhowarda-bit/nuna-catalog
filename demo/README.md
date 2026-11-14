# Local demo: the catalog, its console, SingleStore both ways, DuckDB, Trino, CDC, and the engine matrix, on Kubernetes

One stack on Docker Desktop's Kubernetes, shared with the engine matrix: PostgreSQL, Kafka, kafbat, Silo, the
catalog server (with the console) and SingleStore, plus Keycloak for signing in by username and password, and
DuckDB with its UI, and Trino with CloudBeaver as its UI. `up` writes sample Iceberg tables, syncs SingleStore
and Iceberg in both directions and prints how to sign in; `cdc` captures changes in PostgreSQL and Oracle into
Iceberg through Debezium and Kafka Connect; `engines` runs Spark, Kafka Connect, PyIceberg and SingleStore's
ingest against the same stack. Everything is local and uses test keys; nothing here is fit for production.

## Start

```bash
demo/demo.sh up
```

- Needs bash (Git Bash on Windows), Docker Desktop with Kubernetes on, `kubectl`, `helm`, `curl`, `unzip`,
  and JDK 25 (`JAVA_HOME`, or `java` on the path). Every `up` builds the server jar and the change-feed
  connector jar from this checkout (no tests run) and the images below, so the stack never runs code older
  than the checkout; when nothing changed, Gradle and Docker do nothing. The cluster never pulls an image: it
  runs them from the local Docker engine, and `up` pulls the public ones there.
- Everything runs in namespace `keel-demo` (`KEEL_DEMO_NAMESPACE`) of context `docker-desktop`
  (`KEEL_DEMO_CONTEXT`); `kubectl` and `helm` never use your current context. The stack is `demo/k8s`, the
  same manifests the engine matrix deploys when it runs on its own:
  - `stack.yaml`: PostgreSQL, Kafka, kafbat, and `idp`, which serves the demo issuer's keys
    (`demo/Mint.java`). The demo patches kafbat with `kafka-ui-signin.yaml`, its sign-in through Keycloak;
    the engine matrix on its own leaves kafbat open.
  - `store-silo.yaml` and `seed.yaml`: Silo as Service `s3`, and the job that makes the warehouse bucket.
  - The catalog: the Helm chart `deploy/helm/keel-catalog`, release `keel-catalog`, one server, image
    `keel-catalog:demo` (`deploy/image/Dockerfile`), values `catalog-values.yaml` (both catalogs:
    `lakehouse` for the demo's data, `engines` for the matrix's) and `demo-values.yaml` (the demo's two
    issuers, the console's sign-in, and Silo).
  - `keycloak.yaml`: Keycloak in development mode, with realm `keel` (`keycloak-realm.json`): the people
    below, their groups, and the clients of the console and kafbat. Only the demo runs it; the engine matrix
    on its own does not.
  - `singlestore.yaml`: image `keel-demo-singlestore:<connector hash>`, the dev image with the change-feed
    connector built in (`singlestore.Dockerfile`). A changed connector replaces the pod, and `up` loads the
    new one again.
  - `duckdb.yaml`: DuckDB and its UI, image `keel-demo-duckdb:<DuckDB version>` (`duckdb.Dockerfile`).
  - `trino.yaml`: Trino, image `trinodb/trino:484`.
  - `cloudbeaver.yaml`: CloudBeaver Community with its connection to Trino, image
    `keel-demo-cloudbeaver:<hash>`: `dbeaver/cloudbeaver:26.2.4` without its version check, and with
    `demo/CloudBeaverUsers.java`, which adds the people below as the pod starts (`cloudbeaver.Dockerfile`).
  - `ports.yaml`: the ports below.
  - The lake writer runs as a job when needed (`lake-job.yaml`, image `keel-demo-lake:local`).
  - `cdc.yaml`, only while `demo.sh cdc` runs: a second PostgreSQL, Oracle Database Free, and Kafka Connect,
    image `keel-demo-connect:<hash>` (`connect.Dockerfile`).
- Ports on localhost, from Docker Desktop's LoadBalancer Services: **8180** (Keycloak), **8181** (catalog and
  console), **8182** (kafbat), **8183** (SingleStore Studio), **8184** (the DuckDB UI), **8185**
  (CloudBeaver) and **8186** (Kafka Connect's REST API, while the CDC leg runs). `up` stops if something else
  holds one of them, such as a `kubectl port-forward`.
- Memory: about 6.2 GB of requests (SingleStore 1.5 GB, using about 2.2 GB of up to 3 GB; the catalog and
  Trino 768 MB each, Trino using about 1 GB of up to 1.25 GB; Kafka 640 MB; Keycloak and Silo 512 MB each;
  PostgreSQL, kafbat and CloudBeaver 384 MB each, kafbat and CloudBeaver each using about 350 MB of up to
  768 MB; DuckDB 256 MB, about 70 MB idle, up to 1 GB), plus one job at a time: the lake writer 768 MB, or an
  engine up to 2 GB (Spark). Docker Desktop's Kubernetes has about 7.5 GB, so nothing else of that size fits
  beside it, and `engines` stops Trino and CloudBeaver while it runs to make room for Spark. The CDC leg asks
  for 1.9 GB (Oracle 1.25 GB, Kafka Connect 512 MB, its PostgreSQL 128 MB), so `cdc` stops Trino, leaving
  about 130 MB, and `engines` and `rate` refuse to run beside it.
- No volumes: the data lives as long as the pods. After Docker Desktop restarts, every pod starts empty: run
  `up` again and it writes the sample data again (it checks the catalog, not a marker).

At the end it prints the console URL, <http://localhost:8181/ui/>, the passwords and the tokens.

## Sign in

The console offers two ways in:

- **Sign in with localhost:8180**: Keycloak's login page, by username and password, standing in for a
  directory (AD, Entra ID) until one is configured. The console signs in as it would with any IdP (OIDC
  authorization code with PKCE, `keel.ui.oidc`), and the catalog trusts Keycloak's realm as a second issuer.
  It names a user by `preferred_username`, so `ana` from Keycloak is the same user as `ana`'s token below.
- **The token box** (`keel.ui.dev-token-login=true`, off by default): paste a token minted by `demo/Mint.java`.
  Machine users (`singlestore`, `lake`, `cdc`, `engine-matrix`) have tokens only.

| User | Password | Group | What the catalog lets them do |
|---|---|---|---|
| `ana` | `ana-demo` | `demo-analysts` | select on the whole catalog: browse, load tables and views, read governance |
| `gary` | `gary-demo` | `demo-grant-admins` | grant admin: list, grant and revoke, and read the audit trail; no data access, so loading a table is refused |
| `stella` | `stella-demo` | `demo-stewards` | manage-tags: classifications, owners, legal holds |
| `oscar` | `oscar-demo` | `demo-operators` | platform operator: DR freeze |
| `singlestore` | token only | `demo-loaders` | create and modify; SingleStore's pipelines use it, both ways |
| `lake` | token only | `demo-loaders` | create and modify; the lake writer, standing in for another engine |
| `cdc` | token only | `demo-loaders` | create and modify; Kafka Connect's Iceberg sinks in the CDC leg |
| `engine-matrix` | token only | `engine-clients` | create and modify on catalog `engines`; `demo.sh engines` runs the engines with it |

`ana` can also browse catalog `engines`, where the engine matrix's tables land.

Separation of duties means no one group holds data rights and grant rights, so try the console as `ana` and as
`gary`. Every refusal is the server's, shown as it answered. The console's "Sign out" forgets its token but
not Keycloak's session, so signing in again goes straight back in as the same person: to change user, also
sign out at <http://localhost:8180/realms/keel/account>, or use a private window. Keycloak's users and
sessions go with its pod.

### The same people in the other UIs

The four people sign in to every UI that has users, with the same passwords. Outside the console, the
catalog sees them only through Trino, and there everyone is `ana`.

| UI | How they sign in | `ana`, `gary`, `stella` | `oscar` |
|---|---|---|---|
| kafbat, <http://localhost:8182> | through Keycloak, like the console (client `kafbat`) | read topics, messages, consumer groups and settings | administers Kafka: topics, messages, consumer offsets, ACLs |
| SingleStore Studio, <http://localhost:8183> | as SingleStore users (`demo/singlestore/users.sql`) | `SELECT` on every database | all privileges, with grant option |
| CloudBeaver, <http://localhost:8185> | as CloudBeaver users (`demo/CloudBeaverUsers.java`) | the connections: Trino (`ana` to the catalog) and, read-only, the CDC leg's databases | CloudBeaver's administrator, and the same connections |
| DuckDB UI, <http://localhost:8184> | no sign-in: one DuckDB for everyone, `ana` to the catalog | | |

kafbat's roles go by Keycloak group: `demo-operators` administers, and `demo-analysts`, `demo-grant-admins`
and `demo-stewards` read. Like the console, kafbat's sign-out leaves Keycloak's session.

Accounts that run the demo rather than people in it:

| Account | Password | For |
|---|---|---|
| Keycloak `admin` | `keel-test` | Keycloak's admin console, <http://localhost:8180/admin> |
| SingleStore `root` | `keel-test` | `demo/demo.sh sql` and the demo's setup; Studio accepts it too |
| Silo `keeltest` | `keeltest-secret-key` | the S3 store's root; not published outside the cluster |
| PostgreSQL `catalog` | `catalog` | the catalog's database; not published outside the cluster |
| `reader` | `reader-demo` | CloudBeaver's read-only account in both CDC databases, while the leg runs |
| PostgreSQL `backoffice` | `keel-test` | the CDC leg's PostgreSQL, its owner; `demo/demo.sh psql` |
| Oracle `treasury` | `keel-test` | the CDC leg's Oracle schema; `demo/demo.sh sqlplus` |
| Oracle `SYS`, `SYSTEM` | `keel-test` | the CDC leg's Oracle (`ORACLE_PASSWORD`) |
| Oracle `c##dbzuser` | `dbz` | Debezium's LogMiner user |

Tokens are valid for 7 days. Print them again (re-minted if they are about to expire; SingleStore's running
pipelines are then replaced and DuckDB, Trino and the CDC leg's Kafka Connect are restarted, so they carry
the new ones):

```bash
demo/demo.sh token
```

Tokens are in `~/.keel-demo/tokens/<user>.jwt`. A curl call as a user:

```bash
curl -H "Authorization: Bearer $(cat ~/.keel-demo/tokens/ana.jwt)" "http://localhost:8181/v1/lakehouse/namespaces"
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

Or Studio on <http://localhost:8183>, as one of the people above or as `root` (password `keel-test`). Two
databases, and a third once the engines have run:

- `ops`: SingleStore's own tables. `ops.orders` is copied into Iceberg as it changes.
- `lake`: tables loaded from Iceberg: `xetra_trades` and `countries` once, `fx_rates` continuously, and a
  view `xetra_daily`.
- `keel_m8`: the engine matrix's SingleStore check, Spark's shared tables loaded from catalog `engines`.

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

## DuckDB

The DuckDB UI is on <http://localhost:8184>: a SQL notebook in the browser. Its DuckDB is signed in to the
catalog as `ana`, with both catalogs attached as `lakehouse` and `engines`, so it can do what `ana` can: read
every table she may load, with the storage credentials the catalog vends, and nothing else.

- Every namespace is listed at every depth (`SUPPORT_NESTED_NAMESPACES true`), as a schema named by its dotted
  path; quote it in SQL:

  ```sql
  SELECT * FROM lakehouse."markets.equities.eu.xetra".trades;
  SELECT * FROM engines."m8.shared.spark".t_v3_dv;   -- Spark's v3 table with a deletion vector
  ```

- `finance.ledger.accounts` is listed, but reading it gets the catalog's refusal (D27); the other tables still
  work.
- A write is refused: `ana` holds select only, so the catalog vends her read-only storage credentials, and
  DuckDB's first upload of a data file gets `HTTP 403 Forbidden ... AccessDenied` from the store. Nothing
  reaches the table's folder.
- The UI downloads its web page from `ui.duckdb.org` each time it opens, so it needs internet access; DuckDB
  and its extensions are in the image, and automatic extension installs are off, so a query that needs an
  extension not in the image fails instead of downloading it. Notebooks last as long as the pod.
- It is a single DuckDB for whoever opens the page, with no sign-in of its own. A DuckDB on your own machine
  would attach the same way with your own token, but needs the vended storage address (`http://s3:9000`
  here) to resolve where it runs.

## Trino and CloudBeaver

CloudBeaver is on <http://localhost:8185>: a database navigator and SQL editor in the browser, where the
people above sign in. Its one connection, "keel (Trino, as ana)", is a Trino whose catalogs `lakehouse` and
`engines` are keel's, signed in to the catalog as `ana` with a static token. So it reads what `ana` may load,
with the storage credentials the catalog vends, and nothing else.

- The navigator lists every namespace at every depth, as a schema named by its dotted path; quote it in SQL:

  ```sql
  SELECT * FROM lakehouse."markets.fx".rates;            -- format v3
  SELECT * FROM lakehouse."markets.equities.eu.xetra".trades;
  ```

- `finance.ledger.accounts` is listed, but reading it fails with "Failed to load table 'accounts'". Trino
  keeps the catalog's reason (D27) in the error's cause, which `trino --debug` prints:

  ```bash
  kubectl --context docker-desktop -n keel-demo exec deployment/trino -- trino --user ana --debug \
    --execute 'SELECT * FROM lakehouse."finance.ledger".accounts'
  ```

- A write is refused: `CREATE TABLE` by the catalog (`ana holds no privilege that gives create`), and
  `INSERT` by the store (`AccessDenied`, 403), as `ana`'s vended credentials are read-only.
- Every Trino user is `ana` to the catalog: Trino sends its token, not its own user. The user name in the
  CloudBeaver connection only labels the query in Trino.
- The first query after Trino starts takes about 10 seconds; after that, about 2.
- CloudBeaver's administrator is `oscar`: Settings, then Administration. Scripts and settings last as long
  as its pod, and `engines` and `cdc` restart it; each new pod adds the users again.
- Its page loads only from localhost:8185. The image drops CloudBeaver's version check, which had the
  Administration page fetch dbeaver.com; its Version Update tab now says "Version is up to date" without
  checking. Two features would still reach outside, and the demo uses neither: the map view of a geometry
  value fetches tiles from openstreetmap.org, and the AI assistant calls whichever AI service an
  administrator configures; none is.

## CDC: PostgreSQL and Oracle into Iceberg history tables

```bash
demo/demo.sh cdc
```

starts the CDC leg (`demo/k8s/cdc.yaml`, `demo/cdc`) and stops Trino to make room for it:

- `backoffice`: a PostgreSQL with logical WAL, table `settlement.instructions` (`demo/cdc/backoffice.sql`).
- `oracle`: Oracle Database Free in archive log mode with supplemental logging, table `TREASURY.PAYMENTS`
  in PDB `FREEPDB1` (`demo/cdc/oracle.sql`, run as the database starts; it restarts the database once).
- `connect`: Kafka Connect 4.2.2 in distributed mode, on the stack's Kafka, with four connectors
  (`demo/cdc/connectors`): Debezium 3.7.1's PostgreSQL connector (`pgoutput`) and Oracle connector
  (LogMiner), and an Apache Iceberg 1.12.0 sink for each, writing through the catalog as `cdc` with vended
  credentials.

| Source | Kafka topic | Iceberg table in `lakehouse` |
|---|---|---|
| `settlement.instructions` | `backoffice.settlement.instructions` | `history.postgres.settlement.instructions` |
| `TREASURY.PAYMENTS` | `oracle.TREASURY.PAYMENTS` | `history.oracle.treasury.payments` |

Each table is a history: one row per change, appended, never updated. The sink's `DebeziumTransform` adds a
struct column `_cdc`: `op` (`I`, `U` or `D`), `ts` (when Debezium read the change), `offset` (its Kafka
offset), `source` and `target` (the source table and the Iceberg table) and `key`. A deleted row is written
as it was before the delete. `cdc` waits for each
snapshot (4 rows in each table), then updates, deletes and inserts in both databases and waits for those
changes: 8 rows in the PostgreSQL history, 7 in Oracle's. In DuckDB, oldest change first:

```sql
SELECT _cdc.op, _cdc.ts, instruction_id, status FROM lakehouse."history.postgres.settlement".instructions
ORDER BY _cdc.offset;
SELECT _cdc.op, _cdc.ts, payment_id, status FROM lakehouse."history.oracle.treasury".payments
ORDER BY _cdc.offset;
```

Make more changes and watch them arrive within about 10 seconds (the sinks commit every 5 seconds):

```bash
demo/demo.sh psql      # database backoffice, user backoffice
demo/demo.sh sqlplus   # user treasury in FREEPDB1; COMMIT, or Debezium sees nothing
```

CloudBeaver (<http://localhost:8185>) queries both databases while the leg runs, through connections
"backoffice (PostgreSQL, CDC source)" and "treasury (Oracle, CDC source)". They sign in as `reader`
(password `reader-demo`, made by `backoffice.sql` and `oracle.sql`), which may select the source table and
change nothing, so changes still go through `psql` and `sqlplus` above:

```sql
SELECT * FROM settlement.instructions ORDER BY instruction_id;   -- backoffice
SELECT * FROM treasury.payments ORDER BY payment_id;             -- treasury
```

Outside the leg the two connections fail to connect: their hosts are gone.

- History only: the Apache Iceberg sink appends, so there is no table of the rows as they are now. A view
  over the history (the newest row per key that is not a `D`) gives that.
- Types: the sink maps only Kafka Connect's own logical types, so both connectors send times as those
  (`time.precision.mode=connect`, milliseconds), and every timestamp lands as `timestamptz`. A PostgreSQL
  `timestamptz` column would arrive as a string instead, so the source table uses `timestamp(3)`. Numbers
  with a scale land as `decimal(38, s)`, and Oracle's `NUMBER(10)` as `long`.
- Oracle's upper-case column names are renamed to lower case in the sink (`oracle-history.json`), column by
  column, the key's too: a column added later arrives in upper case.
- The history tables are written as `cdc`, group `demo-loaders`: `ana` reads them like any other table.
- Each `cdc` drops the history tables of the run before and starts both databases empty. `demo.sh cdc` while
  the leg runs only brings it up to date (a changed connector config, image or manifest).
- The first `cdc` pulls Oracle's image (about 5 GB) and builds Kafka Connect's image from Maven Central.
- Kafka Connect's REST API is on <http://localhost:8186>: `curl -s 'http://localhost:8186/connectors?expand=status'`.

```bash
demo/demo.sh cdc stop
```

deletes the leg, its Kafka topics and consumer groups, and starts Trino again. The history tables stay, for
Trino to read.

## The engines

```bash
demo/demo.sh engines
```

runs the engine matrix (`catalog-tests-engines`, `EngineMatrixTest`) against this stack instead of one of its
own: Spark, Kafka Connect and PyIceberg as Kubernetes jobs, then SingleStore's ingest in the demo's SingleStore,
all into catalog `engines` as `engine-matrix`. Trino and CloudBeaver stop for the run and start again after
it. It takes about 4 minutes once the engine images exist; the first
run builds them, which takes longer. It leaves Spark's shared tables in `m8.shared.spark`, which the console
shows, and SingleStore's copies in database `keel_m8`.
Results: `catalog-tests-engines/build/engines-run/results.txt`, and every command in `commands.log` there.

## Kafka

kafbat on <http://localhost:8182>, where the people above sign in through Keycloak, shows the stack's Kafka:
the catalog's audit and change topics, and once the engines have run, Kafka Connect's source topic
`m8-kafka-events` and the sink's control topic. While the CDC leg runs: Debezium's change topics, the sinks'
control topics, Oracle's schema history and the worker's own `cdc-connect-*` topics.
## Stop

```bash
demo/demo.sh down
```

This deletes the namespace and everything in it. The signing key and tokens stay in `~/.keel-demo`; the
next `up` loads the sample data again.

```bash
demo/demo.sh status
```

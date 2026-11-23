# 2026-11-22, brokerage world 1: the ledger world streaming into Iceberg (item 150)

Phase 1 of D48. Paul turned it into a live event source partway through (D48 amended: "real-time ... through both kafka
and singlestore"), so the first plan's batch writer and backfill were never built.

- `demo-datagen`, a new module. The demo kit (forked from ledger kit v14: the chart and the 22 booking schemes as JSON,
  checked against the estate guard) and its posting engine ported to Java (`nuna.datagen.kit`). The world
  (`nuna.datagen.world`): the NYSE calendar with T+1, 300 synthetic securities (CUSIP issuers 990 to 999, real check
  digits), a factor-model price walk in five-minute bars, 1,000 accounts across four personas, deposits, withdrawals,
  orders, fills, settlements, expiries and month-end margin interest, each booked through the engine. The live side
  (`nuna.datagen.live`, `Main`): `bootstrap` makes the topics, namespaces, tables and views; `run` plays the world on a
  compressed clock to Kafka and SingleStore, tags each table `eod-YYYY-MM-DD` once the sink has committed the day, adds
  `locate_id` before the first short sale, and resumes after a restart from the topics' end offsets.
- The demo (`demo/README.md`, "The brokerage world"). Kafka Connect moves from the CDC leg into the base stack
  (`k8s/connect.yaml`; its group and internal topics renamed so `cdc stop` keeps them; `cdc stop` now stops each CDC
  connector, resets its offsets and deletes it). `up` runs the bootstrap Job, sets the world's governance once per
  lake as `stella`, registers the Iceberg sink `brokerage-lake` (`demo/brokerage/connector.json`, writing as `cdc`, the
  worker's identity) and starts the Deployment `brokerage` last. SingleStore gets `brokerage.orders` and the change
  feed `brokerage_orders_to_lake` (`singlestore/brokerage.sql`, run before `feeds.sql`, so a pod made before this
  branch gets them too). Kafka Connect and the generator ask for 768 MB, which leaves about 390 MB, so `up`, `rate`
  and `engines` stop both while they run, and `cdc` pauses the generator. A new Mint user `brokerage`. The toy
  `finance.ledger` tables and their governance are gone from `LakeWriter.java` and `demo.sh`.

Checked in the fast build (`./gradlew :demo-datagen:test`, 60 tests, 20 s): the port posts exactly the journal lines
and ledger account ids the kit's Python posted for `events_v8.json` and matches the workbook's balances per block
(`GoldenRunTest`, 3); all 46 of the kit's own checks (`KitChecksTest`); the same seed gives the same events and another
seed others; a 1,000-account month (22 days) posts with no halt and only the one seeded rejection, 40,148 financial
transactions and 346,048 journal lines; every value a month of the world sends fits a column of its table and every
column is filled (`LakeSchemasTest`); a run stopped mid-day, with its last unacknowledged records lost, resumes to the
same topics, orders and day tags as an unbroken run (`PublisherTest`); against Iceberg's in-memory catalog, the
bootstrap makes every table and both views once and keeps the world's settings on a second run, `locate_id` is added
once, and a day's end tags each table holding its rows and leaves a short or overfull one untagged
(`IcebergLakeTest`). The demo's scripts were checked statically only: `bash -n demo/demo.sh` and
`kubectl apply --dry-run=client` on the new and changed manifests.

Not covered: nothing here has run against Kafka, SingleStore or the catalog. The demo is shared, so the first run is
the overnight rebuild, or `demo/demo.sh up` when Paul chooses.

# 2026-11-26, brokerage world 2: the OMS, clearing, reconciliation and custody (backlog 151)

The work is on branch `worktree-backlog-151`.

## What changed

- **The world** (demo-datagen):
  - It has an order management system (`world/Oms.java`): orders, order events, routes and executions drawn from the
    ledger's fills.
  - It has a clearing house (`world/Clearing.java`): CNS, settlements, fails, the stock record and locates.
  - One fail is seeded, aged and closed out under Reg SHO 204(a).
  - Neither draws at random, so the other feeds are unchanged.
- **Recon**: `brokerage.recon` has views for ties 1, 3 and 4 (`live/Recon.java`).
- **Custody**: `Main custody` loads the clearing books into CockroachDB `custody.clearing` (`live/Custody.java`).
- **Item 155 merged**: `main` (item 155) is merged in. The stock record counts ACATS 83 and 84 and split 86 with cash
  in lieu, and locates follow the world's hard-to-borrow rule.
- **The demo**:
  - SingleStore: `brokerage_oms_to_lake` replaces `ops.orders_to_lake`, and the world's SingleStore feeds are now
    sixteen.
  - Kafka: 19 topics (`brokerage.oms.*` and the clearing topics added).
  - CDC leg: CockroachDB `custody.clearing` with Debezium into `history.cockroach.clearing.*`.
  - Governance for `brokerage.clearing`, `brokerage.recon` and `brokerage.kafka`.
  - `demo/README.md` and `docs/design/brokerage-world.md` are updated.

## Checked

- `./gradlew :demo-datagen:test`: 111 tests, all pass.
  - `ClearingTest` (7) checks:
    - each execution is one ledger fill;
    - routes, and each order's last event is its state;
    - locates, with source and rate by the hard-to-borrow rule;
    - CNS equals the fills on a clean world;
    - the seeded fail's exact rows;
    - tie 1 through ACATS and a split.
  - `ReconTest` (2) runs the views in H2: all empty on a clean world, and only the close-out in `cns_vs_fills` with
    the seeded fail.
  - `CustodyTest` (2) holds `demo/cdc/cockroach.sql` and `demo/cdc/custody-history/*.json` to `Custody`, and checks
    the books it loads.
- `./gradlew secretShapes` passes.
- `bites/run.sh bites/WORLD-151`: 12 bites, all BITES and restored (`bites/WORLD-151/`: the OMS's executions and order events, locates, CNS, the close-out deadline, the stock record's DTC location, ACATS in and out and splits, tie 4's money, and custody's fail and decimal comparison).

## Not checked live

The running demo was not touched. After it is rebuilt from `main` with this branch:

- **SingleStore** (`demo/demo.sh up`, or `ss` with SingleStore on):
  - `SHOW PIPELINES IN brokerage` lists `brokerage_oms_to_lake` as Running;
  - database `ops` is gone;
  - `brokerage.trading.order_events`, `routes` and `executions` fill.
- **The orders' change feed**: an `UPDATE` to a row of SingleStore `brokerage.orders` reaches
  `brokerage.trading.orders` as before.
- **Recon**, in Trino as `ana`:
  - `SELECT * FROM lakehouse."brokerage.recon".executions_vs_fills` and `stock_record_vs_ledger` return no rows;
  - `cns_vs_fills` returns one row, the close-out, once its day is past;
  - unverified: whether `ana` may read the confidential views.
- **CDC leg** (`demo/demo.sh cdc`):
  - the bootstrap job's `custody` step completes;
  - `SELECT count(*) FROM custody.clearing.stock_record` (and the other three) in CockroachDB matches the row count of
    `history.cockroach.clearing.<table>`, which `cdc` waits for;
  - unverified: the route-regex `.*[.]<table>` against Debezium's `_cdc.source`, and the decimals arriving as strings
    into decimal(38, s).
- **Routes**: `demo/demo.sh ss off` sends the OMS rows to `brokerage.kafka.*`. `kafka off` sends clearing to
  `brokerage.singlestore.*`.
- **Upgrade from world 1**:
  - unverified: `DROP PIPELINE IF EXISTS` on SingleStore;
  - unverified: the purge of world 1's `brokerage.trading.order_events`;
  - a CDC leg begun before this item starts anew once.

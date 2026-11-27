-- The brokerage world's side of SingleStore (D48), run by demo.sh as root before each run of feeds.sql, and so made
-- if missing: a SingleStore pod made before the world has no brokerage database, and seed.sql runs only on a new
-- pod.
--   brokerage  the world's orders, which its generator writes (demo/k8s/brokerage.yaml). The change feed
--              brokerage_orders_to_lake (feeds.sql) copies each change into Iceberg (brokerage.trading.orders)
--              and records each commit in brokerage.lake_commits.

-- The change feed names a deleted row by its key, so the table needs a primary key, and it must be the shard key:
-- SingleStore never changes a shard key, so a row keeps its key for life. The feed refuses TIMESTAMP, unsigned
-- BIGINT and decimals over 38 digits, hence DATETIME(6) and DECIMAL(18, s).
CREATE DATABASE IF NOT EXISTS brokerage;
CREATE TABLE IF NOT EXISTS brokerage.orders (
  order_id BIGINT NOT NULL,
  order_ref VARCHAR(32) NOT NULL,
  brokerage_account BIGINT NOT NULL,
  security_id BIGINT NOT NULL,
  symbol VARCHAR(16) NOT NULL,
  side VARCHAR(16) NOT NULL,
  order_type VARCHAR(16) NOT NULL,
  limit_price DECIMAL(18, 6),
  quantity DECIMAL(18, 5) NOT NULL,
  filled_quantity DECIMAL(18, 5) NOT NULL,
  average_price DECIMAL(18, 6),
  status VARCHAR(32) NOT NULL,
  locate_id VARCHAR(32),
  accepted_financial_transaction_id BIGINT NOT NULL,
  trade_date DATE NOT NULL,
  accepted_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (order_id),
  SHARD KEY (order_id));

-- One row per Iceberg commit the brokerage world's change feeds make (feeds.sql, brokerage-feeds-pipelines.sql);
-- gap_after is set when one had to take a new snapshot.
CREATE TABLE IF NOT EXISTS brokerage.lake_commits (
  snapshot_id BIGINT NOT NULL,
  source_table VARCHAR(256) NOT NULL,
  iceberg_table VARCHAR(512) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  rows_written BIGINT NOT NULL,
  keys_deleted BIGINT NOT NULL,
  offsets TEXT NOT NULL,
  gap_after TEXT,
  committed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (snapshot_id));

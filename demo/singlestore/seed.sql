-- SingleStore's side of the demo, run once by demo.sh as root.
--   ops   SingleStore's own tables. The change feed copies each change to ops.orders into Iceberg
--         (markets.equities.eu.xetra.orders) and records each commit in ops.lake_commits.
--   lake  Tables loaded from Iceberg by Iceberg pipelines (load.sql, feeds.sql).
-- The brokerage world's database, brokerage, is made by brokerage.sql, before feeds.sql.

-- Connector pipelines are a preview feature. The change feed's extractor gets 384 MB of heap, not the
-- default 128 MB: it holds a batch of changes and writes Parquet. SingleStore reserves each extractor's heap
-- from its own memory, and a running Iceberg pipeline keeps a data worker per partition at 500 MB by
-- default: 1.4 GB for these two pipelines, enough to stop writes in a 3 GB container. The demo's tables are
-- small, so the data workers get 128 MB.
SET GLOBAL experimental_features_config = 'connector_pipelines_enabled=true';
SET GLOBAL pipelines_cdc_java_heap_size = 384;
SET GLOBAL pipelines_iceberg_data_workers_heap_size = 128;

CREATE DATABASE ops;
CREATE DATABASE lake;

-- The change feed names a deleted row by its key, so the table needs a primary key, and it must be the
-- shard key: SingleStore never changes a shard key, so a row keeps its key for life.
CREATE TABLE ops.orders (
  order_id BIGINT NOT NULL,
  symbol VARCHAR(12) NOT NULL,
  side ENUM('BUY', 'SELL') NOT NULL,
  qty INT NOT NULL,
  limit_price DECIMAL(18, 6),
  status VARCHAR(16) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (order_id),
  SHARD KEY (order_id));
INSERT INTO ops.orders VALUES
  (1001, 'SAP', 'BUY', 300, 182.100000, 'NEW', '2026-11-10 08:59:58'),
  (1002, 'SIE', 'SELL', 120, 171.050000, 'NEW', '2026-11-10 09:00:01'),
  (1003, 'ALV', 'BUY', 75, NULL, 'NEW', '2026-11-10 09:01:00'),
  (1004, 'DTE', 'BUY', 2000, 23.120000, 'NEW', '2026-11-10 09:00:05');

-- One row per Iceberg commit the change feed makes; gap_after is set when it had to take a new snapshot.
CREATE TABLE ops.lake_commits (
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

-- Loaded from Iceberg. Iceberg timestamps arrive as microseconds since 1970 and are stored in UTC.
CREATE TABLE lake.xetra_trades (
  trade_id BIGINT NOT NULL,
  symbol VARCHAR(12),
  price DECIMAL(18, 6),
  qty BIGINT,
  trade_ts DATETIME(6),
  venue VARCHAR(8),
  PRIMARY KEY (trade_id));
CREATE TABLE lake.fx_rates (
  pair VARCHAR(6) NOT NULL,
  rate DOUBLE,
  as_of DATETIME(6) NOT NULL,
  source VARCHAR(16),
  PRIMARY KEY (pair, as_of));
CREATE TABLE lake.countries (
  iso2 CHAR(2) NOT NULL,
  name VARCHAR(64),
  PRIMARY KEY (iso2));

-- The Iceberg view markets.equities.eu.xetra_daily holds this query too.
CREATE VIEW lake.xetra_daily AS
  SELECT symbol, DATE(trade_ts) AS trade_date, SUM(qty) AS volume, AVG(price) AS avg_price
  FROM lake.xetra_trades GROUP BY symbol, DATE(trade_ts);

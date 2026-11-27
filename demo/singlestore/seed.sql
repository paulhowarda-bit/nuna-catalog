-- SingleStore's side of the demo, run once by demo.sh as root.
--   lake  Tables loaded from Iceberg by Iceberg pipelines (load.sql, feeds.sql).
-- The brokerage world's database, brokerage, is made by brokerage.sql and brokerage-feeds.sql, before feeds.sql;
-- its tables are SingleStore's own, which the change feeds copy into Iceberg. (Before item 151 a toy ops.orders
-- was copied to markets.equities.eu.xetra.orders; the brokerage world's order management system replaced it.)

-- Connector pipelines are a preview feature. The change feed's extractor gets 384 MB of heap, not the
-- default 128 MB: it holds a batch of changes and writes Parquet. SingleStore reserves each extractor's heap
-- from its own memory, and a running Iceberg pipeline keeps a data worker per partition at 500 MB by
-- default: 1.4 GB for these two pipelines, enough to stop writes in a 3 GB container. The demo's tables are
-- small, so the data workers get 128 MB.
SET GLOBAL experimental_features_config = 'connector_pipelines_enabled=true';
SET GLOBAL pipelines_cdc_java_heap_size = 384;
SET GLOBAL pipelines_iceberg_data_workers_heap_size = 128;

CREATE DATABASE lake;

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

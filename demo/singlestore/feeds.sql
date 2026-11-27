-- The three running pipelines: one from Iceberg, and two change feeds into it, both of the brokerage world. demo.sh
-- runs this after seed.sql, brokerage.sql and brokerage-feeds.sql, and again whenever it mints new tokens, since each
-- pipeline carries the singlestore user's catalog token (${SINGLESTORE_TOKEN}). Replacing any of them loses nothing:
-- a change feed resumes from the offsets in Iceberg, and the rates pipeline replaces rows by key if it reads a file
-- again.

-- Iceberg to SingleStore: each append to markets.fx.rates is loaded into lake.fx_rates.
USE lake;
CREATE OR REPLACE PIPELINE fx_rates_feed AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "lakehouse",
         "table_id": "markets.fx.rates", "catalog.token": "${SINGLESTORE_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "http://s3:9000", "region": "us-east-1", "ingest_mode": "append"}'
CREDENTIALS '{}'
REPLACE INTO TABLE fx_rates (pair <- pair, rate <- rate, @as_of <- as_of, source <- source)
FORMAT ICEBERG
SET as_of = DATE_ADD('1970-01-01', INTERVAL @as_of MICROSECOND);

-- SingleStore to Iceberg: the change feed of brokerage.orders, the brokerage world's orders, committed to
-- brokerage.trading.orders (namespace and table created by the connector on its first start). Each commit's receipt
-- is loaded into brokerage.lake_commits. No pipeline may load that Iceberg table: the connector stops if one does,
-- since its rows would come back.
USE brokerage;
CREATE OR REPLACE PIPELINE brokerage_orders_to_lake AS LOAD DATA CONNECTOR 'ChangeFeedConnector'
CONFIG '{"singlestore.url": "jdbc:mariadb://localhost:3306/brokerage", "singlestore.user": "root",
         "singlestore.database": "brokerage", "singlestore.table": "orders",
         "iceberg.catalog.uri": "http://nuna-catalog:8181", "iceberg.catalog.warehouse": "lakehouse",
         "iceberg.catalog.header.X-Iceberg-Access-Delegation": "vended-credentials",
         "iceberg.namespace": "brokerage.trading", "iceberg.table": "orders",
         "iceberg.partition-by": "bucket(order_id, 8)", "commit.interval.ms": "15000"}'
CREDENTIALS '{"singlestore.password": "${SINGLESTORE_ROOT_PASSWORD}", "iceberg.catalog.token": "${SINGLESTORE_TOKEN}"}'
BATCH_INTERVAL 1000
REPLACE INTO TABLE lake_commits
FORMAT JSON (
  snapshot_id <- value::snapshot_id, source_table <- value::source_table, iceberg_table <- value::iceberg_table,
  kind <- value::kind, rows_written <- value::rows_written, keys_deleted <- value::keys_deleted,
  offsets <- value::offsets, gap_after <- value::gap_after, @committed_at <- value::committed_at)
SET committed_at = FROM_UNIXTIME(@committed_at / 1000);

-- SingleStore to Iceberg: the order management system's tables (brokerage-feeds.sql), the orders' events, routes
-- and executions (item 151), each copied into brokerage.trading under its own name, in one pipeline so the three
-- share one extractor's memory. Append-only: the generator only ever inserts them, so the feed stops at an update.
-- The connector makes the tables on its first start; demo.sh drops world 1's brokerage.trading.order_events, which
-- was the Kafka sink's, before it does. When SingleStore is off, the same rows go through Kafka to brokerage.kafka.
CREATE OR REPLACE PIPELINE brokerage_oms_to_lake AS LOAD DATA CONNECTOR 'ChangeFeedConnector'
CONFIG '{"singlestore.url": "jdbc:mariadb://localhost:3306/brokerage", "singlestore.user": "root",
         "singlestore.database": "brokerage", "singlestore.append-only": "true",
         "singlestore.tables": "order_events,routes,executions",
         "iceberg.catalog.uri": "http://nuna-catalog:8181", "iceberg.catalog.warehouse": "lakehouse",
         "iceberg.catalog.header.X-Iceberg-Access-Delegation": "vended-credentials",
         "iceberg.namespace": "brokerage.trading", "commit.interval.ms": "15000"}'
CREDENTIALS '{"singlestore.password": "${SINGLESTORE_ROOT_PASSWORD}", "iceberg.catalog.token": "${SINGLESTORE_TOKEN}"}'
BATCH_INTERVAL 1000
REPLACE INTO TABLE lake_commits
FORMAT JSON (
  snapshot_id <- value::snapshot_id, source_table <- value::source_table, iceberg_table <- value::iceberg_table,
  kind <- value::kind, rows_written <- value::rows_written, keys_deleted <- value::keys_deleted,
  offsets <- value::offsets, gap_after <- value::gap_after, @committed_at <- value::committed_at)
SET committed_at = FROM_UNIXTIME(@committed_at / 1000);

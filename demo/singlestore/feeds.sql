-- The two running pipelines, one each way. demo.sh runs this after seed.sql, and again whenever it mints
-- new tokens, since each pipeline carries the singlestore user's catalog token (${SINGLESTORE_TOKEN}).
-- Replacing either one loses nothing: the change feed resumes from the offsets in Iceberg, and the rates
-- pipeline replaces rows by key if it reads a file again.

-- Iceberg to SingleStore: each append to markets.fx.rates is loaded into lake.fx_rates.
USE lake;
CREATE OR REPLACE PIPELINE fx_rates_feed AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://fidice-catalog:8181", "catalog.warehouse": "lakehouse",
         "table_id": "markets.fx.rates", "catalog.token": "${SINGLESTORE_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "http://s3:9000", "region": "us-east-1", "ingest_mode": "append"}'
CREDENTIALS '{}'
REPLACE INTO TABLE fx_rates (pair <- pair, rate <- rate, @as_of <- as_of, source <- source)
FORMAT ICEBERG
SET as_of = DATE_ADD('1970-01-01', INTERVAL @as_of MICROSECOND);

-- SingleStore to Iceberg: the change feed of ops.orders, committed to markets.equities.eu.xetra.orders
-- (created by the connector on its first start). Each commit's receipt is loaded into ops.lake_commits.
-- No pipeline may load that Iceberg table: the connector stops if one does, since its rows would come back.
USE ops;
CREATE OR REPLACE PIPELINE orders_to_lake AS LOAD DATA CONNECTOR 'ChangeFeedConnector'
CONFIG '{"singlestore.url": "jdbc:mariadb://localhost:3306/ops", "singlestore.user": "root",
         "singlestore.database": "ops", "singlestore.table": "orders",
         "iceberg.catalog.uri": "http://fidice-catalog:8181", "iceberg.catalog.warehouse": "lakehouse",
         "iceberg.catalog.header.X-Iceberg-Access-Delegation": "vended-credentials",
         "iceberg.namespace": "markets.equities.eu.xetra", "iceberg.table": "orders",
         "iceberg.partition-by": "bucket(order_id, 4)", "commit.interval.ms": "5000"}'
CREDENTIALS '{"singlestore.password": "${SINGLESTORE_ROOT_PASSWORD}", "iceberg.catalog.token": "${SINGLESTORE_TOKEN}"}'
BATCH_INTERVAL 1000
REPLACE INTO TABLE lake_commits
FORMAT JSON (
  snapshot_id <- value::snapshot_id, source_table <- value::source_table, iceberg_table <- value::iceberg_table,
  kind <- value::kind, rows_written <- value::rows_written, keys_deleted <- value::keys_deleted,
  offsets <- value::offsets, gap_after <- value::gap_after, @committed_at <- value::committed_at)
SET committed_at = FROM_UNIXTIME(@committed_at / 1000);

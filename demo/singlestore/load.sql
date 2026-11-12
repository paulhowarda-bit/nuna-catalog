-- One-time loads from Iceberg into SingleStore, run once by demo.sh after seed.sql. demo.sh puts the
-- singlestore user's catalog token in for ${SINGLESTORE_TOKEN}. Each pipeline reads its table through the
-- catalog with vended credentials, the way an Iceberg Java client does.
USE lake;

CREATE PIPELINE xetra_trades_load AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://catalog:8181", "catalog.warehouse": "lakehouse",
         "table_id": "markets.equities.eu.xetra.trades", "catalog.token": "${SINGLESTORE_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "http://silo:9000", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
REPLACE INTO TABLE xetra_trades (trade_id <- trade_id, symbol <- symbol, price <- price, qty <- qty,
                                 @trade_ts <- trade_ts, venue <- venue)
FORMAT ICEBERG
SET trade_ts = DATE_ADD('1970-01-01', INTERVAL @trade_ts MICROSECOND);
START PIPELINE xetra_trades_load FOREGROUND;

CREATE PIPELINE countries_load AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://catalog:8181", "catalog.warehouse": "lakehouse",
         "table_id": "reference.countries", "catalog.token": "${SINGLESTORE_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "http://silo:9000", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
REPLACE INTO TABLE countries (iso2 <- iso2, name <- name)
FORMAT ICEBERG;
START PIPELINE countries_load FOREGROUND;

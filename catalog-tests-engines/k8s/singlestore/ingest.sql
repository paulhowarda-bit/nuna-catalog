-- SingleStore 10.9 ingests Spark's shared tables (namespace m8.shared.spark, three levels) through Iceberg
-- pipelines, as a stock Iceberg Java REST client using the catalog's vended credentials. Its docs promise
-- v1/v2 only (F9); the v3 tables, one with a deletion vector, show what 10.9.0 actually does.
-- The database stays from run to run: in the demo's SingleStore, DROP DATABASE waits for as long as the change
-- feed's pipeline (ops.orders_to_lake) runs. Emptying the tables and remaking the pipelines does not wait.
CREATE DATABASE IF NOT EXISTS fidice_m8;
USE fidice_m8;

CREATE TABLE IF NOT EXISTS spark_v2 (id BIGINT, name TEXT, note TEXT);
DELETE FROM spark_v2;
DROP PIPELINE IF EXISTS spark_v2_pipe;
CREATE PIPELINE spark_v2_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://fidice-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v2", "catalog.token": "${FIDICE_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_v2 (id <- id, name <- name, note <- note)
FORMAT ICEBERG;
START PIPELINE spark_v2_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.v2' AS c, COUNT(*) AS n, COUNT(note) AS k FROM spark_v2;

CREATE TABLE IF NOT EXISTS spark_v3 (id BIGINT, name TEXT, note TEXT);
DELETE FROM spark_v3;
DROP PIPELINE IF EXISTS spark_v3_pipe;
CREATE PIPELINE spark_v3_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://fidice-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v3", "catalog.token": "${FIDICE_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_v3 (id <- id, name <- name, note <- note)
FORMAT ICEBERG;
START PIPELINE spark_v3_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.v3' AS c, COUNT(*) AS n, COUNT(note) AS k FROM spark_v3;

CREATE TABLE IF NOT EXISTS spark_v3_dv (id BIGINT, name TEXT, note TEXT);
DELETE FROM spark_v3_dv;
DROP PIPELINE IF EXISTS spark_v3_dv_pipe;
CREATE PIPELINE spark_v3_dv_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://fidice-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v3_dv", "catalog.token": "${FIDICE_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_v3_dv (id <- id, name <- name, note <- note)
FORMAT ICEBERG;
START PIPELINE spark_v3_dv_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.v3dv' AS c, COUNT(*) AS n, SUM(id) AS s FROM spark_v3_dv;

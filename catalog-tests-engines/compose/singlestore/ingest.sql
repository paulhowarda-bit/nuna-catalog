-- SingleStore 10.9 ingests Spark's shared tables (namespace m8.shared.spark, three levels) through Iceberg
-- pipelines, as a stock Iceberg Java REST client using the catalog's vended credentials. Its docs promise
-- v1/v2 only (F9); the v3 tables, one with a deletion vector, show what 10.9.0 actually does.
DROP DATABASE IF EXISTS fidice_m8;
CREATE DATABASE fidice_m8;
USE fidice_m8;

CREATE TABLE spark_v2 (id BIGINT, name TEXT, note TEXT);
CREATE PIPELINE spark_v2_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v2", "catalog.token": "${FIDICE_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "http://silo:9000", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_v2 (id <- id, name <- name, note <- note)
FORMAT ICEBERG;
START PIPELINE spark_v2_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.v2' AS c, COUNT(*) AS n, COUNT(note) AS k FROM spark_v2;

CREATE TABLE spark_v3 (id BIGINT, name TEXT, note TEXT);
CREATE PIPELINE spark_v3_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v3", "catalog.token": "${FIDICE_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "http://silo:9000", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_v3 (id <- id, name <- name, note <- note)
FORMAT ICEBERG;
START PIPELINE spark_v3_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.v3' AS c, COUNT(*) AS n, COUNT(note) AS k FROM spark_v3;

CREATE TABLE spark_v3_dv (id BIGINT, name TEXT, note TEXT);
CREATE PIPELINE spark_v3_dv_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v3_dv", "catalog.token": "${FIDICE_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "http://silo:9000", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_v3_dv (id <- id, name <- name, note <- note)
FORMAT ICEBERG;
START PIPELINE spark_v3_dv_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.v3dv' AS c, COUNT(*) AS n, SUM(id) AS s FROM spark_v3_dv;

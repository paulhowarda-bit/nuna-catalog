-- SingleStore 10.9 ingests Spark's shared tables (namespace m8.shared.spark, three levels) through Iceberg
-- pipelines, as a stock Iceberg Java REST client using the catalog's vended credentials. Its docs promise
-- v1/v2 only (F9); the v3 tables, one with a deletion vector, show what 10.9.0 actually does. Four tables
-- took DELETE, UPDATE and MERGE INTO, copy-on-write or merge-on-read: their notes, in id order, show whether
-- each change was read back. Then Spark's evolved, partition-evolved, compacted and upgraded tables, and
-- PyIceberg's own v2 table.
-- The database stays from run to run: in the demo's SingleStore, DROP DATABASE waits for as long as the change
-- feed's pipeline (ops.orders_to_lake) runs. Emptying the tables and remaking the pipelines does not wait.
CREATE DATABASE IF NOT EXISTS nuna_m8;
USE nuna_m8;

CREATE TABLE IF NOT EXISTS spark_v2 (id BIGINT, name TEXT, note TEXT);
DELETE FROM spark_v2;
DROP PIPELINE IF EXISTS spark_v2_pipe;
CREATE PIPELINE spark_v2_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v2", "catalog.token": "${NUNA_TOKEN}",
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
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v3", "catalog.token": "${NUNA_TOKEN}",
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
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v3_dv", "catalog.token": "${NUNA_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_v3_dv (id <- id, name <- name, note <- note)
FORMAT ICEBERG;
START PIPELINE spark_v3_dv_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.v3dv' AS c, COUNT(*) AS n, SUM(id) AS s FROM spark_v3_dv;

CREATE TABLE IF NOT EXISTS spark_v2_cow (id BIGINT, name TEXT, note TEXT);
DELETE FROM spark_v2_cow;
DROP PIPELINE IF EXISTS spark_v2_cow_pipe;
CREATE PIPELINE spark_v2_cow_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v2_cow", "catalog.token": "${NUNA_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_v2_cow (id <- id, name <- name, note <- note)
FORMAT ICEBERG;
START PIPELINE spark_v2_cow_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.v2cow' AS c, COUNT(*) AS n, SUM(id) AS s,
       GROUP_CONCAT(note ORDER BY id SEPARATOR ',') AS notes FROM spark_v2_cow;

CREATE TABLE IF NOT EXISTS spark_v2_mor (id BIGINT, name TEXT, note TEXT);
DELETE FROM spark_v2_mor;
DROP PIPELINE IF EXISTS spark_v2_mor_pipe;
CREATE PIPELINE spark_v2_mor_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v2_mor", "catalog.token": "${NUNA_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_v2_mor (id <- id, name <- name, note <- note)
FORMAT ICEBERG;
START PIPELINE spark_v2_mor_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.v2mor' AS c, COUNT(*) AS n, SUM(id) AS s,
       GROUP_CONCAT(note ORDER BY id SEPARATOR ',') AS notes FROM spark_v2_mor;

CREATE TABLE IF NOT EXISTS spark_v3_cow (id BIGINT, name TEXT, note TEXT);
DELETE FROM spark_v3_cow;
DROP PIPELINE IF EXISTS spark_v3_cow_pipe;
CREATE PIPELINE spark_v3_cow_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v3_cow", "catalog.token": "${NUNA_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_v3_cow (id <- id, name <- name, note <- note)
FORMAT ICEBERG;
START PIPELINE spark_v3_cow_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.v3cow' AS c, COUNT(*) AS n, SUM(id) AS s,
       GROUP_CONCAT(note ORDER BY id SEPARATOR ',') AS notes FROM spark_v3_cow;

CREATE TABLE IF NOT EXISTS spark_v3_mor (id BIGINT, name TEXT, note TEXT);
DELETE FROM spark_v3_mor;
DROP PIPELINE IF EXISTS spark_v3_mor_pipe;
CREATE PIPELINE spark_v3_mor_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_v3_mor", "catalog.token": "${NUNA_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_v3_mor (id <- id, name <- name, note <- note)
FORMAT ICEBERG;
START PIPELINE spark_v3_mor_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.v3mor' AS c, COUNT(*) AS n, SUM(id) AS s,
       GROUP_CONCAT(note ORDER BY id SEPARATOR ',') AS notes FROM spark_v3_mor;

-- Schema evolution: the pipeline maps the table's current column names (and info::a, info::b inside the struct);
-- the two rows written before the rename, drop, widening and struct change must keep their notes.
CREATE TABLE IF NOT EXISTS spark_schema (name TEXT, id BIGINT, note TEXT, info_a INT, info_b TEXT);
DELETE FROM spark_schema;
DROP PIPELINE IF EXISTS spark_schema_pipe;
CREATE PIPELINE spark_schema_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_schema", "catalog.token": "${NUNA_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_schema (name <- name, id <- id, note <- note, info_a <- info::a, info_b <- info::b)
FORMAT ICEBERG;
START PIPELINE spark_schema_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.schema' AS c, COUNT(*) AS n, SUM(id) AS s,
       GROUP_CONCAT(note ORDER BY note SEPARATOR ',') AS notes, SUM(info_a) AS a,
       GROUP_CONCAT(info_b SEPARATOR ',') AS b FROM spark_schema;

-- Partition evolution: files under three partition specs (and a sort order), all ten rows.
CREATE TABLE IF NOT EXISTS spark_partition (id BIGINT, cat TEXT);
DELETE FROM spark_partition;
DROP PIPELINE IF EXISTS spark_partition_pipe;
CREATE PIPELINE spark_partition_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_partition", "catalog.token": "${NUNA_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_partition (id <- id, cat <- cat)
FORMAT ICEBERG;
START PIPELINE spark_partition_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.partition' AS c, COUNT(*) AS n, SUM(id) AS s FROM spark_partition;

-- After rewrite_position_delete_files, rewrite_data_files, rewrite_manifests and expire_snapshots.
CREATE TABLE IF NOT EXISTS spark_maint (id BIGINT, name TEXT);
DELETE FROM spark_maint;
DROP PIPELINE IF EXISTS spark_maint_pipe;
CREATE PIPELINE spark_maint_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_maint", "catalog.token": "${NUNA_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_maint (id <- id, name <- name)
FORMAT ICEBERG;
START PIPELINE spark_maint_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.maint' AS c, COUNT(*) AS n, SUM(id) AS s FROM spark_maint;

-- Upgraded from v2 to v3: a deletion vector that holds the old position delete too.
CREATE TABLE IF NOT EXISTS spark_upgrade (id BIGINT, name TEXT);
DELETE FROM spark_upgrade;
DROP PIPELINE IF EXISTS spark_upgrade_pipe;
CREATE PIPELINE spark_upgrade_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.spark.t_upgrade", "catalog.token": "${NUNA_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE spark_upgrade (id <- id, name <- name)
FORMAT ICEBERG;
START PIPELINE spark_upgrade_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.upgrade' AS c, COUNT(*) AS n, SUM(id) AS s FROM spark_upgrade;

-- PyIceberg's own v2 table (it runs before SingleStore): delete, overwrite and upsert, all copy-on-write.
CREATE TABLE IF NOT EXISTS pyiceberg_writes (id BIGINT, name TEXT);
DELETE FROM pyiceberg_writes;
DROP PIPELINE IF EXISTS pyiceberg_writes_pipe;
CREATE PIPELINE pyiceberg_writes_pipe AS LOAD DATA S3 ''
CONFIG '{"catalog_type": "REST", "catalog.uri": "http://nuna-catalog:8181", "catalog.warehouse": "engines",
         "table_id": "m8.shared.pyiceberg.t_writes", "catalog.token": "${NUNA_TOKEN}",
         "catalog.header.X-Iceberg-Access-Delegation": "vended-credentials", "iceberg_vended_credentials": true,
         "endpoint_url": "${S3_ENDPOINT}", "region": "us-east-1", "ingest_mode": "one_time"}'
CREDENTIALS '{}'
INTO TABLE pyiceberg_writes (id <- id, name <- name)
FORMAT ICEBERG;
START PIPELINE pyiceberg_writes_pipe FOREGROUND;
SELECT 'RESULT' AS r, 'singlestore' AS e, 'cross.pyiceberg-writes' AS c, COUNT(*) AS n, SUM(id) AS s,
       GROUP_CONCAT(name ORDER BY id SEPARATOR ',') AS names FROM pyiceberg_writes;

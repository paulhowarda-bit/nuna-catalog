-- Iceberg pipelines are off by default in the dev image; the harness records the value and restores it.
SELECT @@global.enable_iceberg_ingest AS before_value;
SET GLOBAL enable_iceberg_ingest = 1;

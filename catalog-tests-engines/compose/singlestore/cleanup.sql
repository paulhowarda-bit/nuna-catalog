-- Leaves the long-lived SingleStore container as the test found it.
DROP DATABASE IF EXISTS fidice_m8;
SET GLOBAL enable_iceberg_ingest = ${ICEBERG_INGEST_BEFORE};

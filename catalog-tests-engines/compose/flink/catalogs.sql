-- Init script for every Flink SQL session. A Flink database is one namespace level, so deep namespaces
-- come from the Iceberg catalog option 'base-namespace': fid_m8 sees the children of m8, fid those of
-- m8.deep (database "flink" is namespace m8.deep.flink), fid_shared those of m8.shared.
SET 'execution.runtime-mode' = 'batch';
SET 'table.dml-sync' = 'true';
SET 'sql-client.execution.result-mode' = 'tableau';

CREATE CATALOG fid_root WITH ('type' = 'iceberg', 'catalog-type' = 'rest', 'uri' = 'http://catalog:8181/',
  'warehouse' = 'engines', 'token' = '${FIDICE_TOKEN}', 'header.X-Iceberg-Access-Delegation' = 'vended-credentials',
  'io-impl' = 'org.apache.iceberg.aws.s3.S3FileIO');
CREATE CATALOG fid_m8 WITH ('type' = 'iceberg', 'catalog-type' = 'rest', 'uri' = 'http://catalog:8181/',
  'warehouse' = 'engines', 'token' = '${FIDICE_TOKEN}', 'header.X-Iceberg-Access-Delegation' = 'vended-credentials',
  'io-impl' = 'org.apache.iceberg.aws.s3.S3FileIO', 'base-namespace' = 'm8');
CREATE CATALOG fid WITH ('type' = 'iceberg', 'catalog-type' = 'rest', 'uri' = 'http://catalog:8181/',
  'warehouse' = 'engines', 'token' = '${FIDICE_TOKEN}', 'header.X-Iceberg-Access-Delegation' = 'vended-credentials',
  'io-impl' = 'org.apache.iceberg.aws.s3.S3FileIO', 'base-namespace' = 'm8.deep');
CREATE CATALOG fid_shared WITH ('type' = 'iceberg', 'catalog-type' = 'rest', 'uri' = 'http://catalog:8181/',
  'warehouse' = 'engines', 'token' = '${FIDICE_TOKEN}', 'header.X-Iceberg-Access-Delegation' = 'vended-credentials',
  'io-impl' = 'org.apache.iceberg.aws.s3.S3FileIO', 'base-namespace' = 'm8.shared');

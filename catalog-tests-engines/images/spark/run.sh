#!/usr/bin/env bash
# Spark against the catalog: REST catalog "fid", vended credentials, purge delegated to the server (F10).
# Runs the engine matrix's script, or the script and arguments given (compaction_matrix.py setup|check).
set -euo pipefail
if [ $# -eq 0 ]; then
  set -- /scripts/spark_matrix.py
fi
exec /opt/spark/bin/spark-submit --master 'local[2]' --driver-memory 1g \
  --conf spark.ui.enabled=false \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --conf spark.sql.catalog.fid=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.fid.type=rest \
  --conf spark.sql.catalog.fid.uri=http://keel-catalog:8181/ \
  --conf spark.sql.catalog.fid.warehouse=engines \
  --conf "spark.sql.catalog.fid.token=${KEEL_TOKEN}" \
  --conf spark.sql.catalog.fid.header.X-Iceberg-Access-Delegation=vended-credentials \
  --conf spark.sql.catalog.fid.io-impl=org.apache.iceberg.aws.s3.S3FileIO \
  --conf spark.sql.catalog.fid.rest-catalog-purge=true \
  "$@"

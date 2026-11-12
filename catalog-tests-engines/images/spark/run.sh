#!/usr/bin/env bash
# Spark against the catalog: REST catalog "fid", vended credentials, purge delegated to the server (F10).
set -euo pipefail
exec /opt/spark/bin/spark-submit --master 'local[2]' --driver-memory 1g \
  --conf spark.ui.enabled=false \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --conf spark.sql.catalog.fid=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.fid.type=rest \
  --conf spark.sql.catalog.fid.uri=http://fidice-catalog:8181/ \
  --conf spark.sql.catalog.fid.warehouse=engines \
  --conf "spark.sql.catalog.fid.token=${FIDICE_TOKEN}" \
  --conf spark.sql.catalog.fid.header.X-Iceberg-Access-Delegation=vended-credentials \
  --conf spark.sql.catalog.fid.io-impl=org.apache.iceberg.aws.s3.S3FileIO \
  --conf spark.sql.catalog.fid.rest-catalog-purge=true \
  /scripts/spark_matrix.py

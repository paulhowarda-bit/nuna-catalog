"""Compaction of one table (D30 (3)), started by the keel maintenance worker as a Kubernetes Job.

Runs Iceberg's rewrite_data_files on the table KEEL_TABLE (its namespace levels and name, as a JSON list) in the
catalog "keel", signed in as the worker (KEEL_AUTH, from the Job's Secret). KEEL_CONF holds the Spark settings and
KEEL_OPTIONS the procedure's options. The files it replaces stay where they are: snapshot expiry takes the
snapshots that use them, and the worker deletes them through the catalog's pending-deletion list.
Prints one COMPACTED line with the procedure's counts.
"""
import json
import os

from pyspark.sql import SparkSession

builder = SparkSession.builder.appName("keel-compaction")
for key, value in json.loads(os.environ["KEEL_CONF"]).items():
    builder = builder.config(key, value)
for key, value in json.loads(os.environ["KEEL_AUTH"]).items():
    builder = builder.config("spark.sql.catalog.keel." + key, value)
spark = builder.getOrCreate()
spark.sparkContext.setLogLevel("WARN")


def literal(value):
    return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'"


table = ".".join("`" + part.replace("`", "``") + "`" for part in json.loads(os.environ["KEEL_TABLE"]))
options = json.loads(os.environ["KEEL_OPTIONS"])
call = "CALL keel.system.rewrite_data_files(table => " + literal(table)
if options:
    call += ", options => map(" + ", ".join(literal(k) + ", " + literal(v) for k, v in options.items()) + ")"
row = spark.sql(call + ")").collect()[0]
print("COMPACTED", " ".join(f"{k}={v}" for k, v in row.asDict().items()), flush=True)
spark.stop()

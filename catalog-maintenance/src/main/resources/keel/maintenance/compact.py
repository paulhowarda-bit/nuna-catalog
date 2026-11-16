"""Compaction of one table (D30 (3)), started by the keel maintenance worker as a Kubernetes Job.

Runs Iceberg's rewrite_data_files on the table KEEL_TABLE (its namespace levels and name, as a JSON list) in the
catalog "keel", signed in as the worker (KEEL_AUTH, from the Job's Secret). KEEL_CONF holds the Spark settings and
KEEL_OPTIONS the procedure's options, partial progress among them: Kubernetes stops the Job at the cleanup window's
end, and the file groups it finished by then stay committed. The files it replaces stay where they are: snapshot
expiry takes the snapshots that use them, and the worker deletes them through the catalog's pending-deletion list.

The pod may start long after the worker decided to compact: the table may have been dropped, renamed or unregistered
meanwhile and another taken its name. So the table is loaded first and the Job stops, exit 3 and a TABLE CHANGED
line, unless its id is KEEL_TABLE_UUID. The procedure then gets that same table from Spark's catalog cache, and
Iceberg's REST client refuses to refresh or commit it as a table of another id.
Prints one COMPACTED line with the procedure's counts.
"""
import json
import os
import sys

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
expected = os.environ["KEEL_TABLE_UUID"]
found = spark._jvm.org.apache.iceberg.spark.Spark3Util.loadIcebergTable(spark._jsparkSession, "keel." + table) \
    .uuid().toString()
if found != expected:
    print("TABLE CHANGED", table, "is", found, "not", expected, flush=True)
    spark.stop()
    sys.exit(3)
options = json.loads(os.environ["KEEL_OPTIONS"])
call = "CALL keel.system.rewrite_data_files(table => " + literal(table)
if options:
    call += ", options => map(" + ", ".join(literal(k) + ", " + literal(v) for k, v in options.items()) + ")"
row = spark.sql(call + ")").collect()[0]
print("COMPACTED", " ".join(f"{k}={v}" for k, v in row.asDict().items()), flush=True)
spark.stop()

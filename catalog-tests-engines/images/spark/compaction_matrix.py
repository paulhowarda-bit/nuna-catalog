"""Compaction in the engine matrix (D30 (3)): the table before and after the maintenance worker's compaction Job.

Run by run.sh as the matrix's own Spark (catalog "fid", the matrix's token), in one of two modes:

setup  makes m8.compaction.t (v2, identity-partitioned by part, 0 to 3) from eight inserts of one row per
       partition: 32 rows in 32 small data files. Prints the file and row counts, the sum of the ids, and the
       table's id and location, which the harness hands to the worker's Job.
check  after the Job: the files, rows and sum now, the current snapshot's operation, and the rows and sum the
       snapshot before it still reads, which needs every replaced file still in storage.
"""
import sys

from pyspark.sql import SparkSession

ENGINE = "compaction"
TABLE = "fid.m8.compaction.t"
spark = SparkSession.builder.appName("keel-engine-matrix-compaction").getOrCreate()
spark.sparkContext.setLogLevel("WARN")


def result(check, *values):
    print("RESULT", ENGINE, check, *values, flush=True)


def sql(query):
    return spark.sql(query).collect()


def counts(source):
    rows, total = sql(f"SELECT count(*), sum(id) FROM {source}")[0]
    return rows, total


def files():
    return sql(f"SELECT count(*) FROM {TABLE}.files")[0][0]


mode = sys.argv[1]
if mode == "setup":
    for namespace in ("fid.m8", "fid.m8.compaction"):
        sql(f"CREATE NAMESPACE IF NOT EXISTS {namespace}")
    sql(f"DROP TABLE IF EXISTS {TABLE}")
    sql(f"CREATE TABLE {TABLE} (id BIGINT, part INT) USING iceberg PARTITIONED BY (part)"
        " TBLPROPERTIES ('format-version' = '2')")
    for insert in range(8):
        sql(f"INSERT INTO {TABLE} VALUES " + ", ".join(f"({insert * 4 + part}, {part})" for part in range(4)))
    result("setup", files(), *counts(TABLE))
    table = spark._jvm.org.apache.iceberg.spark.Spark3Util.loadIcebergTable(spark._jsparkSession, TABLE)
    result("table", table.uuid().toString(), table.location())
elif mode == "check":
    current = sql(f"SELECT snapshot_id, parent_id, operation FROM {TABLE}.snapshots ORDER BY committed_at DESC"
                  " LIMIT 1")[0]
    result("check", files(), *counts(TABLE), current[2])
    result("before", *counts(f"{TABLE} VERSION AS OF {current[1]}"))
else:
    raise SystemExit("mode is setup or check, not " + mode)
spark.stop()

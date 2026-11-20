"""The queryable audit table in the engine matrix (D23): Spark reads nuna_audit.audit.events as the readers' group.

Run by run.sh with this script; it adds catalog "audit" (warehouse nuna_audit) on the readers' token in
NUNA_AUDIT_TOKEN, beside run.sh's "fid". Prints RESULT lines that EngineMatrixTest#auditTables asserts, the same
checks PyIceberg's audit_tables_matrix.py makes:

format   the table's format version
seeded   the privileges the server granted on the audit catalog at startup, as its grant records show them
commits  whether the matrix's own commits in catalog engines are there
own      how many rows the writer's own requests made (it leaves them out)
offsets  whether every row has a place in the topic of its own
"""
import os

from pyspark.sql import SparkSession

ENGINE = "audit"
TABLE = "audit.audit.events"
spark = (SparkSession.builder.appName("nuna-engine-matrix-audit")
         .config("spark.sql.catalog.audit", "org.apache.iceberg.spark.SparkCatalog")
         .config("spark.sql.catalog.audit.type", "rest")
         .config("spark.sql.catalog.audit.uri", "http://nuna-catalog:8181/")
         .config("spark.sql.catalog.audit.warehouse", "nuna_audit")
         .config("spark.sql.catalog.audit.token", os.environ["NUNA_AUDIT_TOKEN"])
         .config("spark.sql.catalog.audit.header.X-Iceberg-Access-Delegation", "vended-credentials")
         .config("spark.sql.catalog.audit.io-impl", "org.apache.iceberg.aws.s3.S3FileIO")
         .getOrCreate())
spark.sparkContext.setLogLevel("WARN")


def result(check, *values):
    print("RESULT", ENGINE, "spark." + check, *values, flush=True)


def sql(query):
    return spark.sql(query).collect()


properties = {row[0]: row[1] for row in sql(f"SHOW TBLPROPERTIES {TABLE}")}
result("format", properties.get("format-version"))
seeded = sql(f"SELECT DISTINCT privilege FROM {TABLE} WHERE type = 'nuna.catalog.grant'"
             " AND principal = 'nuna-configuration' AND catalog = 'nuna_audit' ORDER BY privilege")
result("seeded", ",".join(row[0] for row in seeded))
commits = sql(f"SELECT count(*) FROM {TABLE} WHERE type = 'nuna.catalog.commit' AND principal = 'engine-matrix'"
              " AND catalog = 'engines'")[0][0]
result("commits", "yes" if commits > 0 else "no")
result("own", sql(f"SELECT count(*) FROM {TABLE} WHERE principal = 'nuna-audit-tables'")[0][0])
rows, places = sql(f"SELECT count(*), count(DISTINCT topic_partition, topic_offset) FROM {TABLE}")[0]
result("offsets", "unique" if rows > 0 and rows == places else f"{rows} rows at {places} places")
spark.stop()

"""Catalog branches and tags (D33) in the engine matrix: Spark on a tag and a branch of catalog engines (backlog 112).

Run by run.sh with this script and a mode; it adds catalogs "tag" (warehouse engines@m8-tag) and "dev" (warehouse
engines@m8-dev) beside run.sh's "fid", each with fid's settings: a stock client names a ref by its warehouse, and
/v1/config answers that prefix and, for a tag, only the read endpoints. Prints RESULT lines that EngineMatrixTest#refs
asserts, on the tables and refs PyIceberg's refs_matrix.py setup made:

branch  reads the tag; is refused an INSERT there (by the store: the tag's credentials only read) and a change of
        properties (by Iceberg's REST client itself: the tag's /v1/config leaves out table commits); inserts a row
        into each table on the branch; reads the branch and main
main    after the merge: reads main, and the tag, which the merge left as it was

Each count is "<rows in a> <sum of a's ids> <rows in b> <sum of b's ids>".
"""
import sys

from pyspark.sql import SparkSession

ENGINE = "refs"
MODE = sys.argv[1]
TABLES = ("a", "b")
spark = SparkSession.builder.appName("nuna-engine-matrix-refs").getOrCreate()
spark.sparkContext.setLogLevel("WARN")
for name, ref in (("tag", "m8-tag"), ("dev", "m8-dev")):
    for key, value in spark.sparkContext.getConf().getAll():
        if key == "spark.sql.catalog.fid" or key.startswith("spark.sql.catalog.fid."):
            spark.conf.set("spark.sql.catalog." + name + key[len("spark.sql.catalog.fid"):], value)
    spark.conf.set(f"spark.sql.catalog.{name}.warehouse", "engines@" + ref)


def result(check, *values):
    print("RESULT", ENGINE, check, *values, flush=True)


def sql(query):
    return spark.sql(query).collect()


def counts(catalog):
    values = []
    for name in TABLES:
        rows, total = sql(f"SELECT count(*), coalesce(sum(id), 0) FROM {catalog}.m8.refs.{name}")[0]
        values += [rows, total]
    return values


def error_text(error):
    """The error's message and every cause's from the JVM: Spark reports a write the store refused as a failed task,
    with the store's answer among the causes."""
    texts = [f"{type(error).__name__}: {error}"]
    cause = getattr(error, "_origin", None) or getattr(error, "java_exception", None)
    for _ in range(20):
        if cause is None:
            break
        texts.append(str(cause.toString()))
        cause = cause.getCause()
    return "\n".join(texts)


def refused(check, query):
    """Runs a statement the tag must refuse and prints who refused it: "store" when the object store denied the data
    file (the tag's credentials only read), "endpoint" when the client would not send the commit (the tag's
    /v1/config leaves table commits out). Any other error ends the run, and success prints a RESULT the test does not
    expect."""
    try:
        sql(query)
    except Exception as error:  # noqa: BLE001 - the error text is the evidence
        text = error_text(error)
        lowered = text.lower()
        if "server does not support endpoint" in lowered:
            result(check, "refused endpoint")
        elif "status code: 403" in lowered or "access denied" in lowered or "accessdenied" in lowered:
            result(check, "refused store")
        else:
            raise
        print("REFUSED", check, " ".join(text.split())[:500], flush=True)
        return
    result(check, "accepted")


if MODE == "branch":
    result("spark.tag.read", *counts("tag"))
    refused("spark.tag.write", "INSERT INTO tag.m8.refs.a VALUES (99, 'refused')")
    refused("spark.tag.alter", "ALTER TABLE tag.m8.refs.a SET TBLPROPERTIES ('nuna.matrix'='refused')")
    for name in TABLES:
        sql(f"INSERT INTO dev.m8.refs.{name} VALUES (10, 'spark')")
    result("spark.branch.write", *counts("dev"))
    result("spark.branch.main", *counts("fid"))
elif MODE == "main":
    result("spark.main", *counts("fid"))
    result("spark.main.tag", *counts("tag"))
else:
    raise SystemExit("mode: branch or main")
spark.stop()

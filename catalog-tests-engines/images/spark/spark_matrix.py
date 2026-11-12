"""Spark 4.1 + Iceberg 1.12.0 against the catalog (catalog "fid").

Prints RESULT lines that EngineMatrixTest asserts. Any unexpected error ends the run non-zero.
Also leaves m8.shared.spark.t_v2 and t_v3 (evolved, four rows), t_v3_dv (a deletion vector) and t_v2_cow,
t_v2_mor, t_v3_cow and t_v3_mor (DELETE, UPDATE and MERGE INTO, copy-on-write or merge-on-read) for the other
engines to read.
"""
from pyspark.sql import SparkSession

ENGINE = "spark"
spark = SparkSession.builder.appName("keel-engine-matrix").getOrCreate()
spark.sparkContext.setLogLevel("WARN")


def result(check, *values):
    print("RESULT", ENGINE, check, *values, flush=True)


def sql(query):
    return spark.sql(query).collect()


def ensure_namespace(*levels):
    # The catalog requires each parent level to exist (D28); create every level.
    for depth in range(1, len(levels) + 1):
        sql("CREATE NAMESPACE IF NOT EXISTS fid." + ".".join(levels[:depth]))


def format_version(table):
    properties = {row[0]: row[1] for row in sql(f"SHOW TBLPROPERTIES {table}")}
    return properties.get("format-version")


def build(table, version):
    """Create, write, read, add a column, write and read with it. Returns the format version seen."""
    sql(f"CREATE TABLE {table} (id BIGINT, name STRING) USING iceberg "
        f"TBLPROPERTIES ('format-version'='{version}')")
    seen = format_version(table)
    sql(f"INSERT INTO {table} VALUES (1, 'a'), (2, 'b'), (3, 'c')")
    read = sql(f"SELECT count(*), sum(id) FROM {table}")[0]
    sql(f"ALTER TABLE {table} ADD COLUMN note STRING")
    sql(f"INSERT INTO {table} VALUES (4, 'd', 'x')")
    evolved = sql(f"SELECT count(*), count(note) FROM {table}")[0]
    return seen, read, evolved


ensure_namespace("m8", "deep", "spark")
listed = [row[0] for row in sql("SHOW NAMESPACES IN fid.m8.deep")]
result("ns.create", "m8.deep.spark" if "m8.deep.spark" in listed else listed)

for version in ("2", "3"):
    table = f"fid.m8.deep.spark.t_v{version}"
    seen, read, evolved = build(table, version)
    result(f"v{version}.create", seen)
    result(f"v{version}.read", read[0], read[1])
    result(f"v{version}.evolve", evolved[0], evolved[1])
    sql(f"DROP TABLE {table} PURGE")
    left = [row[1] for row in sql("SHOW TABLES IN fid.m8.deep.spark")]
    result(f"v{version}.drop", "ok" if f"t_v{version}" not in left else left)

sql("DROP NAMESPACE fid.m8.deep.spark")
left = [row[0] for row in sql("SHOW NAMESPACES IN fid.m8.deep")]
result("ns.drop", "ok" if "m8.deep.spark" not in left else left)

# The shared tables the other engines read.
ensure_namespace("m8", "shared", "spark")
for version in ("2", "3"):
    table = f"fid.m8.shared.spark.t_v{version}"
    sql(f"DROP TABLE IF EXISTS {table} PURGE")
    seen, _, evolved = build(table, version)
    result(f"shared.v{version}", seen, evolved[0], evolved[1])

# A v3 table with a deletion vector (v3's merge-on-read delete): ids 1, 3 and 4 remain.
table = "fid.m8.shared.spark.t_v3_dv"
sql(f"DROP TABLE IF EXISTS {table} PURGE")
sql(f"CREATE TABLE {table} (id BIGINT, name STRING, note STRING) USING iceberg "
    "TBLPROPERTIES ('format-version'='3', 'write.delete.mode'='merge-on-read')")
sql(f"INSERT INTO {table} VALUES (1, 'a', NULL), (2, 'b', NULL), (3, 'c', NULL), (4, 'd', 'x')")
sql(f"DELETE FROM {table} WHERE id = 2")
rows = sql(f"SELECT count(*), sum(id) FROM {table}")[0]
vectors = sql(f"SELECT count(*) FROM {table}.delete_files WHERE file_format = 'PUFFIN'")[0][0]
result("shared.v3dv", format_version(table), rows[0], rows[1], vectors)


def delete_files(table):
    """The current snapshot's delete files: their kinds, how many, and how many rows they delete."""
    files = sql(f"SELECT content, file_format, record_count FROM {table}.delete_files")
    if not files:
        return "none", 0, 0
    names = {(1, "PARQUET"): "position-parquet", (1, "PUFFIN"): "dv-puffin", (2, "PARQUET"): "equality-parquet"}
    kinds = sorted({names.get((content, fmt), f"content{content}-{fmt.lower()}") for content, fmt, _ in files})
    return ",".join(kinds), len(files), sum(count for _, _, count in files)


def operations(table):
    """The snapshot operations from the first commit to the current one, following each snapshot's parent."""
    snapshots = {row[0]: (row[1], row[2])
                 for row in sql(f"SELECT snapshot_id, parent_id, operation FROM {table}.snapshots")}
    current = sql(f"SELECT snapshot_id FROM {table}.refs WHERE name = 'main'")[0][0]
    chain = []
    while current is not None:
        parent, operation = snapshots[current]
        chain.append(operation)
        current = parent
    return ",".join(reversed(chain))


# Row-level writes, one table per format version and write mode, left for the other engines to read. The four
# rows go into one data file, so each statement below must rewrite that file (copy-on-write) or add delete
# files against it (merge-on-read): with a file per row, DELETE WHERE id = 2 could drop a whole file from
# metadata and run neither path. Final rows: (1, a, NULL), (3, c, u), (4, d, m), (5, e, n).
MODES = {"cow": "copy-on-write", "mor": "merge-on-read"}
for version in ("2", "3"):
    for short, mode in MODES.items():
        name = f"v{version}{short}"
        table = f"fid.m8.shared.spark.t_v{version}_{short}"
        sql(f"DROP TABLE IF EXISTS {table} PURGE")
        sql(f"CREATE TABLE {table} (id BIGINT, name STRING, note STRING) USING iceberg "
            f"TBLPROPERTIES ('format-version'='{version}', 'write.delete.mode'='{mode}', "
            f"'write.update.mode'='{mode}', 'write.merge.mode'='{mode}')")
        sql(f"INSERT INTO {table} SELECT /*+ COALESCE(1) */ * FROM VALUES "
            "(1, 'a', CAST(NULL AS STRING)), (2, 'b', NULL), (3, 'c', NULL), (4, 'd', 'x') AS v(id, name, note)")
        result(f"shared.{name}.files", sql(f"SELECT count(*) FROM {table}.data_files")[0][0])
        sql(f"DELETE FROM {table} WHERE id = 2")
        sql(f"UPDATE {table} SET note = 'u' WHERE id = 3")
        sql(f"MERGE INTO {table} t "
            "USING (SELECT * FROM VALUES (4, 'd', 'm'), (5, 'e', 'n') AS s(id, name, note)) s ON t.id = s.id "
            "WHEN MATCHED THEN UPDATE SET note = s.note WHEN NOT MATCHED THEN INSERT *")
        rows = sql(f"SELECT id, note FROM {table} ORDER BY id")
        notes = ",".join(row[1] for row in rows if row[1] is not None)
        result(f"shared.{name}", format_version(table), len(rows), sum(row[0] for row in rows), notes)
        result(f"shared.{name}.deletes", *delete_files(table))
        result(f"shared.{name}.ops", operations(table))

spark.stop()

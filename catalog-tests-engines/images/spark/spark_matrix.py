"""Spark 4.1 + Iceberg 1.12.0 against the catalog (catalog "fid").

Prints RESULT lines that EngineMatrixTest asserts, and LIMIT lines for refusals it expects. Any unexpected error
ends the run non-zero. TIME lines give each case's seconds (not asserted).
Also leaves m8.shared.spark.t_v2 and t_v3 (evolved, four rows), t_v3_dv (a deletion vector), t_v2_cow,
t_v2_mor, t_v3_cow and t_v3_mor (DELETE, UPDATE and MERGE INTO, copy-on-write or merge-on-read), and the tables
and view of the later cases (schema and partition evolution, time travel, branches, write-audit-publish,
maintenance, the v2 to v3 upgrade, v3 types, CREATE OR REPLACE, a view, statistics) for the other engines.
"""
import time

from pyspark.sql import SparkSession

ENGINE = "spark"
spark = SparkSession.builder.appName("nuna-engine-matrix").getOrCreate()
spark.sparkContext.setLogLevel("WARN")
STARTED = time.monotonic()


def result(check, *values):
    print("RESULT", ENGINE, check, *values, flush=True)


def limit(check, reason):
    print("LIMIT", ENGINE, check, " ".join(str(reason).split()), flush=True)


def timed(case):
    """Prints the seconds since the previous call (or the start): the cost of the case just finished."""
    global STARTED
    now = time.monotonic()
    print("TIME", ENGINE, case, round(now - STARTED), flush=True)
    STARTED = now


def sql(query):
    return spark.sql(query).collect()


def refused(check, query, fragment, reason):
    """Runs a statement expected to fail with an error containing fragment; prints LIMIT check reason if it does.
    Any other error ends the run, and success prints a RESULT the test does not expect."""
    try:
        sql(query)
    except Exception as error:  # noqa: BLE001 - the error text is the evidence
        if fragment not in str(error):
            raise
        limit(check, reason)
        return
    result(check, "accepted")


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
timed("lifecycle")

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
timed("shared")


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
timed("row-level")

SHARED = "fid.m8.shared.spark"


def fresh(table, columns, properties=""):
    """Drops the table left by the previous run and creates it again."""
    sql(f"DROP TABLE IF EXISTS {table} PURGE")
    sql(f"CREATE TABLE {table} ({columns}) USING iceberg" + (f" TBLPROPERTIES ({properties})" if properties else ""))


def count_sum(query_from):
    return sql(f"SELECT count(*), sum(id) FROM {query_from}")[0]


def one_file(table, rows):
    """Inserts rows (a VALUES list of (id, name)) as one data file."""
    sql(f"INSERT INTO {table} SELECT /*+ COALESCE(1) */ * FROM VALUES {rows} AS v(id, name)")


def snapshots(table):
    return sql(f"SELECT count(*) FROM {table}.snapshots")[0][0]


def main_snapshot(table):
    return sql(f"SELECT snapshot_id FROM {table}.refs WHERE name = 'main'")[0][0]


# 1. Schema evolution beyond add-column (v2). Two rows, then: rename old_note to note, drop gone, widen id from int
# to bigint, add info.b inside the struct, move name first; then a row in the new layout. Iceberg reads columns by
# field ID, so the renamed column keeps its first two values and the widened ints read as bigint: three rows,
# ids summing to 6, notes n1,n2,n3, info.a 10 + 20 + 30, info.b only on the new row.
table = f"{SHARED}.t_schema"
fresh(table, "id INT, name STRING, old_note STRING, gone STRING, info STRUCT<a: INT>", "'format-version'='2'")
sql(f"INSERT INTO {table} VALUES (1, 'a', 'n1', 'g1', named_struct('a', 10)), (2, 'b', 'n2', 'g2', named_struct('a', 20))")
sql(f"ALTER TABLE {table} RENAME COLUMN old_note TO note")
sql(f"ALTER TABLE {table} DROP COLUMN gone")
sql(f"ALTER TABLE {table} ALTER COLUMN id TYPE BIGINT")
sql(f"ALTER TABLE {table} ADD COLUMN info.b STRING")
sql(f"ALTER TABLE {table} ALTER COLUMN name FIRST")
sql(f"INSERT INTO {table} VALUES ('c', 3, 'n3', named_struct('a', 30, 'b', 'x'))")
result("shared.schema.columns", spark.table(table).schema.simpleString())
result("shared.schema", *sql(f"SELECT count(*), sum(id), concat_ws(',', sort_array(collect_list(note))), "
                             f"sum(info.a), concat_ws(',', collect_list(info.b)) FROM {table}")[0])
timed("schema")

# 2. Partition evolution and sort order (v2), two rows per step: unpartitioned (spec 0); partitioned by cat
# (spec 1); bucket(4, id) in its place (spec 2); the field dropped, which leaves a v2 spec with no fields, spec 0's
# twin, so Iceberg reuses spec 0; then WRITE ORDERED BY id, with the rows sent as 10, 9. All ten rows read (ids
# summing to 55): 6 in spec 0, 2 each in specs 1 and 2. The last write's file carries sort order 1, 9 before 10.
table = f"{SHARED}.t_partition"
fresh(table, "id BIGINT, cat STRING", "'format-version'='2'")
sql(f"INSERT INTO {table} VALUES (1, 'x'), (2, 'y')")
sql(f"ALTER TABLE {table} ADD PARTITION FIELD cat")
sql(f"INSERT INTO {table} VALUES (3, 'x'), (4, 'y')")
sql(f"ALTER TABLE {table} REPLACE PARTITION FIELD cat WITH bucket(4, id)")
sql(f"INSERT INTO {table} VALUES (5, 'x'), (6, 'y')")
sql(f"ALTER TABLE {table} DROP PARTITION FIELD bucket(4, id)")
sql(f"INSERT INTO {table} VALUES (7, 'x'), (8, 'y')")
sql(f"ALTER TABLE {table} WRITE ORDERED BY id")
sql(f"INSERT INTO {table} VALUES (10, 'x'), (9, 'y')")
result("shared.partition", *count_sum(table))
specs = sql(f"SELECT spec_id, sum(record_count) FROM {table}.data_files GROUP BY spec_id ORDER BY spec_id")
result("shared.partition.specs", ",".join(f"{spec}:{rows}" for spec, rows in specs))
order = {row[0]: row[1] for row in sql(f"SHOW TBLPROPERTIES {table}")}.get("sort-order")
sorted_rows = sql(f"SELECT sum(record_count) FROM {table}.data_files WHERE sort_order_id = 1")[0][0]
physical = ",".join(str(row[0]) for row in sql(f"SELECT id FROM {table} WHERE id >= 9 ORDER BY _file, _pos"))
result("shared.partition.sorted", order, sorted_rows, physical)
timed("partition")

# 3. Time travel (the catalog's default format, v3): ids 1-3 in the first snapshot, id 4 in the second, tag first
# on the first. VERSION AS OF its id, TIMESTAMP AS OF its commit time and VERSION AS OF 'first' each read the
# first snapshot's three rows; the table reads four.
table = f"{SHARED}.t_travel"
fresh(table, "id BIGINT, name STRING")
sql(f"INSERT INTO {table} VALUES (1, 'a'), (2, 'b'), (3, 'c')")
sql(f"INSERT INTO {table} VALUES (4, 'd')")
first, first_ms = sql(f"SELECT snapshot_id, unix_millis(committed_at) FROM {table}.snapshots "
                      "ORDER BY committed_at LIMIT 1")[0]
sql(f"ALTER TABLE {table} CREATE TAG first AS OF VERSION {first}")
result("shared.travel.version", *count_sum(f"{table} VERSION AS OF {first}"))
result("shared.travel.timestamp", *count_sum(f"{table} TIMESTAMP AS OF timestamp_millis({first_ms})"))
result("shared.travel.tag", *count_sum(f"{table} VERSION AS OF 'first'"))
result("shared.travel.current", *count_sum(table))
timed("time-travel")

# 4. Branches and write-audit-publish. Branch audit and tag v1 start at main's first snapshot (three rows); a
# write to the branch leaves main at three; fast_forward moves main to the branch's snapshot (four rows) and the
# tag stays at three.
table = f"{SHARED}.t_branch"
fresh(table, "id BIGINT, name STRING")
sql(f"INSERT INTO {table} VALUES (1, 'a'), (2, 'b'), (3, 'c')")
sql(f"ALTER TABLE {table} CREATE BRANCH audit")
sql(f"ALTER TABLE {table} CREATE TAG v1")
sql(f"INSERT INTO {table}.branch_audit VALUES (4, 'd')")
result("shared.branch.write", count_sum(table)[0], count_sum(f"{table}.branch_audit")[0])
moved = sql(f"CALL fid.system.fast_forward('m8.shared.spark.t_branch', 'main', 'audit')")[0]
refs = {row[0]: (row[1], row[2]) for row in sql(f"SELECT name, type, snapshot_id FROM {table}.refs")}
advanced = moved.previous_ref != moved.updated_ref and refs["main"][1] == refs["audit"][1] == moved.updated_ref
result("shared.branch.ff", moved.branch_updated, "same" if advanced else refs, count_sum(table)[0],
       count_sum(f"{table} VERSION AS OF 'v1'")[0])
result("shared.branch.refs", ",".join(f"{name}:{kind}" for name, (kind, _) in sorted(refs.items())))

# Write-audit-publish: with write.wap.enabled and spark.wap.id set, the insert is staged (a snapshot main does not
# reference). Its parent is main's snapshot, so cherrypick_snapshot fast-forwards main to it: no new snapshot.
table = f"{SHARED}.t_wap"
fresh(table, "id BIGINT, name STRING", "'write.wap.enabled'='true'")
sql(f"INSERT INTO {table} VALUES (1, 'a'), (2, 'b'), (3, 'c')")
spark.conf.set("spark.wap.id", "wap-1")
sql(f"INSERT INTO {table} VALUES (4, 'd')")
spark.conf.unset("spark.wap.id")
staged = [row[0] for row in sql(f"SELECT snapshot_id FROM {table}.snapshots WHERE summary['wap.id'] = 'wap-1'")]
result("shared.wap.staged", count_sum(table)[0], len(staged))
picked = sql(f"CALL fid.system.cherrypick_snapshot('m8.shared.spark.t_wap', {staged[0]})")[0]
result("shared.wap.publish", count_sum(table)[0],
       "fast-forward" if picked.current_snapshot_id == staged[0] else "new-snapshot", snapshots(table))
timed("branches-wap")

# 5. Maintenance procedures on a v2 merge-on-read table: three appends of one file each (ids 1-2, 3-4, 5-6), then
# DELETE ids 1 and 3, one position-delete file per data file (file-scoped, Iceberg 1.12's default granularity).
# rewrite_position_delete_files rewrites both, file-scoped again. rewrite_data_files compacts the three data files
# into one with the deletes applied, and its commit removes the file-scoped deletes of the files it replaced (the
# snapshot summary's removed-delete-files; the procedure's removed_delete_files_count counts only dangling deletes
# removed by its remove-dangling-deletes option, off by default). That commit leaves four data manifests (the new
# file's, and three holding only the replaced files as deleted entries) and one delete manifest (the two removed
# deletes). rewrite_manifests rewrites the four data manifests into one and leaves the lone delete manifest (it skips
# a content type with a single manifest). expire_snapshots keeps only the current of the seven snapshots; the
# catalog accepts the commit, but vended credentials carry no s3:DeleteObject (D25), so no file is deleted and the
# three replaced data files stay in storage. Four rows (2, 4, 5, 6) remain throughout.
table = f"{SHARED}.t_maint"
name = "m8.shared.spark.t_maint"
fresh(table, "id BIGINT, name STRING", "'format-version'='2', 'write.delete.mode'='merge-on-read'")
for rows in ("(1, 'a'), (2, 'b')", "(3, 'c'), (4, 'd')", "(5, 'e'), (6, 'f')"):
    one_file(table, rows)
sql(f"DELETE FROM {table} WHERE id IN (1, 3)")
data_files = f"SELECT count(*) FROM {table}.data_files"
result("shared.maint.setup", sql(data_files)[0][0], *delete_files(table))
moved = sql(f"CALL fid.system.rewrite_position_delete_files(table => '{name}', options => map('rewrite-all', 'true'))")[0]
result("shared.maint.position-deletes", moved.rewritten_delete_files_count, moved.added_delete_files_count,
       *delete_files(table))
moved = sql(f"CALL fid.system.rewrite_data_files(table => '{name}', options => map('rewrite-all', 'true'))")[0]
summary = sql(f"SELECT summary FROM {table}.snapshots WHERE snapshot_id = {main_snapshot(table)}")[0][0]
result("shared.maint.data-files", moved.rewritten_data_files_count, moved.added_data_files_count,
       moved.failed_data_files_count, moved.removed_delete_files_count, summary.get("removed-delete-files"),
       sql(data_files)[0][0], *delete_files(table))


def manifests(table):
    """The current snapshot's data manifests and delete manifests."""
    kinds = {row[0]: row[1] for row in sql(f"SELECT content, count(*) FROM {table}.manifests GROUP BY content")}
    return kinds.get(0, 0), kinds.get(1, 0)


before = manifests(table)
moved = sql(f"CALL fid.system.rewrite_manifests('{name}')")[0]
result("shared.maint.manifests", *before, moved.rewritten_manifests_count, moved.added_manifests_count,
       *manifests(table))
before = snapshots(table)
expired = [row[0] for row in sql(f"SELECT DISTINCT file_path FROM {table}.all_data_files "
                                 f"WHERE file_path NOT IN (SELECT file_path FROM {table}.data_files)")]
cutoff = sql("SELECT date_format(current_timestamp() + INTERVAL 1 SECOND, 'yyyy-MM-dd HH:mm:ss.SSS')")[0][0]
gone = sql(f"CALL fid.system.expire_snapshots(table => '{name}', older_than => TIMESTAMP '{cutoff}', "
           "retain_last => 1)")[0]
result("shared.maint.expire", before, snapshots(table), gone.deleted_data_files_count,
       gone.deleted_position_delete_files_count, gone.deleted_manifest_files_count, gone.deleted_manifest_lists_count)
io = spark._jvm.org.apache.iceberg.spark.Spark3Util.loadIcebergTable(spark._jsparkSession, table).io()
result("shared.maint.expired-files", len(expired), sum(1 for path in expired if io.newInputFile(path).exists()))
refused("shared.maint.orphans-hadoop", f"CALL fid.system.remove_orphan_files(table => '{name}', dry_run => true)",
        'No FileSystem for scheme "s3"', "remove_orphan_files lists the table location through Hadoop's FileSystem, "
        "which has no s3 scheme here (S3FileIO only); prefix_listing => true lists through S3FileIO")
orphans = sql(f"CALL fid.system.remove_orphan_files(table => '{name}', prefix_listing => true, dry_run => true)")
result("shared.maint.orphans", len(orphans))
refused("shared.maint.orphans-recent", f"CALL fid.system.remove_orphan_files(table => '{name}', "
        f"prefix_listing => true, dry_run => true, older_than => TIMESTAMP '{cutoff}')",
        "interval less than 24 hours", "the procedure refuses an older_than within 24 hours (the default is 3 days "
        "back), so files this run left unreferenced, the expired ones above, are never candidates")
result("shared.maint", *count_sum(table))
timed("maintenance")

# 6. v2 to v3 upgrade: a v2 merge-on-read table, four rows in one file, DELETE id 2 (a Parquet position-delete
# file), then format-version 3 and DELETE id 3. A v3 data file's deletes live in one deletion vector, so Spark
# merges the old position delete into the new vector (two rows) and removes the Parquet file: ids 1 and 4 remain.
table = f"{SHARED}.t_upgrade"
fresh(table, "id BIGINT, name STRING", "'format-version'='2', 'write.delete.mode'='merge-on-read'")
one_file(table, "(1, 'a'), (2, 'b'), (3, 'c'), (4, 'd')")
sql(f"DELETE FROM {table} WHERE id = 2")
result("shared.upgrade.v2", format_version(table), *delete_files(table))
sql(f"ALTER TABLE {table} SET TBLPROPERTIES ('format-version'='3')")
sql(f"DELETE FROM {table} WHERE id = 3")
result("shared.upgrade", format_version(table), *count_sum(table), *delete_files(table))
result("shared.upgrade.ops", operations(table))
timed("upgrade")

# 7. v3 features. Row lineage: three rows in one file get row IDs 0-2 at sequence number 1; an UPDATE of id 2
# (copy-on-write, a new file) keeps every row ID and moves only id 2's last-updated sequence number to 2.
table = f"{SHARED}.t_lineage"
fresh(table, "id BIGINT, name STRING", "'format-version'='3'")
one_file(table, "(1, 'a'), (2, 'b'), (3, 'c')")


def lineage():
    rows = sql(f"SELECT _row_id, _last_updated_sequence_number FROM {table} ORDER BY id")
    return ",".join(str(row[0]) for row in rows), ",".join(str(row[1]) for row in rows)


result("v3.lineage.insert", *lineage())
sql(f"UPDATE {table} SET name = 'u' WHERE id = 2")
result("v3.lineage.update", *lineage())

# Column default values: Iceberg 1.12's Spark catalog refuses both ways of declaring one.
sql(f"DROP TABLE IF EXISTS {SHARED}.t_default PURGE")
refused("v3.default.create", f"CREATE TABLE {SHARED}.t_default (id BIGINT, c INT DEFAULT 7) USING iceberg "
        "TBLPROPERTIES ('format-version'='3')", "does not support column default value",
        "CREATE TABLE with a DEFAULT: the Iceberg table does not support column default values")
fresh(f"{SHARED}.t_default", "id BIGINT", "'format-version'='3'")
refused("v3.default.add", f"ALTER TABLE {SHARED}.t_default ADD COLUMN d STRING DEFAULT 'z'",
        "setting default values in Spark is currently unsupported",
        "Cannot add column d since setting default values in Spark is currently unsupported")
sql(f"DROP TABLE {SHARED}.t_default PURGE")

# variant: two JSON documents, read back by path.
table = f"{SHARED}.t_variant"
fresh(table, "id BIGINT, v VARIANT", "'format-version'='3'")
sql(f"""INSERT INTO {table} SELECT 1, parse_json('{{"a": 1, "b": "x"}}') UNION ALL """
    f"""SELECT 2, parse_json('{{"a": 2, "b": "y"}}')""")
result("v3.variant", *sql(f"SELECT count(*), sum(variant_get(v, '$.a', 'int')), "
                          f"concat_ws(',', sort_array(collect_list(variant_get(v, '$.b', 'string')))) FROM {table}")[0])

# geometry and geography (SRID 4326): Spark 4.1 has the types behind spark.sql.geospatial.enabled. One point,
# POINT (1 2) as WKB, written to both and read back unchanged with its SRID.
POINT = "X'0101000000000000000000F03F0000000000000040'"
refused("v3.geo.disabled", f"CREATE TABLE {SHARED}.t_geo_refused (id BIGINT, g GEOMETRY(4326)) USING iceberg "
        "TBLPROPERTIES ('format-version'='3')", "GEOSPATIAL_DISABLED",
        "Spark 4.1 refuses geometry and geography columns unless spark.sql.geospatial.enabled=true")
spark.conf.set("spark.sql.geospatial.enabled", "true")
table = f"{SHARED}.t_geo"
fresh(table, "id BIGINT, g GEOMETRY(4326), h GEOGRAPHY(4326)", "'format-version'='3'")
sql(f"INSERT INTO {table} SELECT 1, st_setsrid(st_geomfromwkb({POINT}), 4326), st_geogfromwkb({POINT})")
result("v3.geo", *sql(f"SELECT id, hex(st_asbinary(g)), st_srid(g), hex(st_asbinary(h)), st_srid(h) FROM {table}")[0])
timed("v3")

# 8. CREATE OR REPLACE TABLE ... AS SELECT on a v3 table with two snapshots. The replace keeps the table and its
# history: a third snapshot, an overwrite (Spark writes the new rows as one) with no parent, as main starts afresh;
# two rows; the old snapshot still reads its four.
table = f"{SHARED}.t_replace"
fresh(table, "id BIGINT, name STRING", "'format-version'='3'")
sql(f"INSERT INTO {table} VALUES (1, 'a'), (2, 'b'), (3, 'c')")
sql(f"INSERT INTO {table} VALUES (4, 'd')")
before = main_snapshot(table)
sql(f"CREATE OR REPLACE TABLE {table} USING iceberg TBLPROPERTIES ('format-version'='3') "
    "AS SELECT * FROM VALUES (10L, 'x'), (20L, 'y') AS v(id, name)")
result("replace", format_version(table), snapshots(table), *count_sum(table), operations(table))
result("replace.history", *count_sum(f"{table} VERSION AS OF {before}"))
# A replace whose query changes a column's type (id becomes a string). A replace starts main afresh, and Iceberg's
# reference handler accepts the new schema (it keeps field IDs by name, whatever the type): four snapshots, one
# row, id a string. The catalog accepts it since 2026-11-12 (D11); a refusal is still reported as a suspected
# catalog bug rather than a failure of the run.
try:
    sql(f"CREATE OR REPLACE TABLE {table} USING iceberg TBLPROPERTIES ('format-version'='3') "
        "AS SELECT * FROM VALUES ('k', 'z') AS v(id, name)")
    result("replace.retype", snapshots(table), sql(f"SELECT count(*) FROM {table}")[0][0],
           spark.table(table).schema["id"].dataType.simpleString())
except Exception as error:  # noqa: BLE001 - the catalog's answer is the evidence
    if "not a valid type promotion" not in str(error):
        raise
    limit("replace.retype", "suspected catalog bug: the catalog refuses a CREATE OR REPLACE that changes a "
          "column type (Cannot change field 1 (id) from long to string: not a valid type promotion), which "
          "Iceberg's reference handler accepts")
# A replace whose query makes a nullable column required: the literal 1 is never null. Iceberg's Spark catalog
# makes every CTAS and RTAS column optional unless use-nullable-query-schema is false, so the replace goes through
# fidstrict, a second handle on the same catalog with that set. Main starts afresh, so the catalog accepts it
# since 2026-11-13 (D11), as Iceberg's reference handler does: two snapshots, one row, id required. A refusal
# is reported as a suspected catalog bug, as above.
for key, value in spark.sparkContext.getConf().getAll():
    if key == "spark.sql.catalog.fid" or key.startswith("spark.sql.catalog.fid."):
        spark.conf.set("spark.sql.catalog.fidstrict" + key[len("spark.sql.catalog.fid"):], value)
spark.conf.set("spark.sql.catalog.fidstrict.use-nullable-query-schema", "false")
table = f"{SHARED}.t_replace_required"
fresh(table, "id INT", "'format-version'='3'")
sql(f"INSERT INTO {table} VALUES (1), (NULL)")
try:
    sql(f"CREATE OR REPLACE TABLE fidstrict.{table[len('fid.'):]} USING iceberg TBLPROPERTIES ('format-version'='3') "
        "AS SELECT 1 AS id")
    spark.catalog.refreshTable(table)
    result("replace.required", snapshots(table), sql(f"SELECT count(*) FROM {table}")[0][0],
           "optional" if spark.table(table).schema["id"].nullable else "required")
except Exception as error:  # noqa: BLE001 - the catalog's answer is the evidence
    if "Cannot make optional field" not in str(error):
        raise
    limit("replace.required", "suspected catalog bug: the catalog refuses a CREATE OR REPLACE that makes a "
          "nullable column required (Cannot make optional field 1 (id) required), which Iceberg's reference "
          "handler accepts")
sql(f"DROP TABLE {table} PURGE")
timed("replace")

# 9. A view on t_v2: created (ids 1 and 2), then replaced (the row with a note, id 4) and given a comment, while
# a second view is created and dropped. Iceberg's Spark extensions refuse ALTER VIEW ... AS; CREATE OR REPLACE
# VIEW is the way to change the query. v_rows stays for PyIceberg to load.
view = f"{SHARED}.v_rows"
sql(f"DROP VIEW IF EXISTS {view}")
sql(f"CREATE VIEW {view} AS SELECT id, name FROM {SHARED}.t_v2 WHERE id <= 2")
result("view.create", *count_sum(view))
refused("view.alter-as", f"ALTER VIEW {view} AS SELECT id, name, note FROM {SHARED}.t_v2 WHERE note IS NOT NULL",
        "Use CREATE OR REPLACE VIEW instead", "ALTER VIEW ... AS is not supported. Use CREATE OR REPLACE VIEW instead")
sql(f"CREATE OR REPLACE VIEW {view} AS SELECT id, name, note FROM {SHARED}.t_v2 WHERE note IS NOT NULL")
sql(f"ALTER VIEW {view} SET TBLPROPERTIES ('comment' = 'rows with a note')")
result("view.replace", *count_sum(view))
sql(f"DROP VIEW IF EXISTS {SHARED}.v_gone")
sql(f"CREATE VIEW {SHARED}.v_gone AS SELECT 1 AS one")
sql(f"DROP VIEW {SHARED}.v_gone")
result("view.list", ",".join(sorted(row[1] for row in sql(f"SHOW VIEWS IN {SHARED}"))))
timed("views")

# 10. compute_table_stats on t_v2: a Puffin statistics file in the table's metadata folder (PyIceberg reads it).
written = sql("CALL fid.system.compute_table_stats(table => 'm8.shared.spark.t_v2')")[0][0]
result("stats", "metadata" if written and "/metadata/" in written else written)
timed("stats")

spark.stop()

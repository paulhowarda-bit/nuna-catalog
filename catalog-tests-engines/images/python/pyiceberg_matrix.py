"""PyIceberg 0.12.0 against the catalog. Prints RESULT/LIMIT lines that EngineMatrixTest asserts.

Writes and drops its own tables in m8.deep.pyiceberg, reads Spark's shared tables and view in m8.shared.spark, and
leaves m8.shared.pyiceberg.t_writes (delete, overwrite and upsert on v2) for SingleStore to read.
"""
import os

import pyarrow as pa
import pyarrow.compute as pc
from pyiceberg.catalog import load_catalog
from pyiceberg.exceptions import NamespaceAlreadyExistsError
from pyiceberg.schema import Schema
from pyiceberg.types import LongType, NestedField, StringType

ENGINE = "pyiceberg"


def result(check, *values):
    print("RESULT", ENGINE, check, *values, flush=True)


def limit(check, reason):
    print("LIMIT", ENGINE, check, " ".join(str(reason).split())[:300], flush=True)


catalog = load_catalog("fid", **{
    "type": "rest",
    "uri": "http://keel-catalog:8181",
    "warehouse": "engines",
    "token": os.environ["KEEL_TOKEN"],
    "header.X-Iceberg-Access-Delegation": "vended-credentials",
})


def ensure_namespace(*levels):
    # The catalog requires each parent level to exist (D28).
    for depth in range(1, len(levels) + 1):
        try:
            catalog.create_namespace(levels[:depth])
        except NamespaceAlreadyExistsError:
            pass


def counts(table):
    data = table.scan().to_arrow()
    notes = data.column("note").drop_null() if "note" in data.column_names else []
    total = pc.sum(data.column("id")).as_py() if data.num_rows else 0
    return data.num_rows, total, len(notes)


SCHEMA = Schema(NestedField(1, "id", LongType(), required=False),
                NestedField(2, "name", StringType(), required=False))
FIRST = pa.Table.from_pylist([{"id": 1, "name": "a"}, {"id": 2, "name": "b"}, {"id": 3, "name": "c"}],
                             schema=pa.schema([("id", pa.int64()), ("name", pa.string())]))
SECOND = pa.Table.from_pylist([{"id": 4, "name": "d", "note": "x"}],
                              schema=pa.schema([("id", pa.int64()), ("name", pa.string()), ("note", pa.string())]))

namespace = ("m8", "deep", "pyiceberg")
ensure_namespace(*namespace)
listed = catalog.list_namespaces(("m8", "deep"))
result("ns.create", ".".join(namespace) if namespace in listed else listed)

for version in (2, 3):
    identifier = namespace + (f"t_v{version}",)
    table = catalog.create_table(identifier, SCHEMA, properties={"format-version": str(version)})
    result(f"v{version}.create", table.metadata.format_version)
    try:
        table.append(FIRST)
    except Exception as error:  # noqa: BLE001 - PyIceberg 0.12 does not write v3 (F9)
        if version != 3:
            raise
        limit("v3.write-refused", f"{type(error).__name__}: {error}")
    table = catalog.load_table(identifier)
    rows, total, _ = counts(table)
    result(f"v{version}.read", rows, total)
    with table.update_schema() as update:
        update.add_column("note", StringType())
    table = catalog.load_table(identifier)
    result(f"v{version}.evolve-schema", ",".join(field.name for field in table.schema().fields))
    if version == 2:
        table.append(SECOND)
        rows, _, notes = counts(catalog.load_table(identifier))
        result("v2.evolve", rows, notes)
    catalog.purge_table(identifier)
    result(f"v{version}.drop", "ok" if not catalog.table_exists(identifier) else "still-listed")

catalog.drop_namespace(namespace)
result("ns.drop", "ok" if namespace not in catalog.list_namespaces(("m8", "deep")) else "still-listed")

for version in (2, 3):
    shared = catalog.load_table(("m8", "shared", "spark", f"t_v{version}"))
    rows, _, notes = counts(shared)
    result(f"cross.v{version}", rows, notes)
rows, total, _ = counts(catalog.load_table(("m8", "shared", "spark", "t_v3_dv")))
result("cross.v3dv", rows, total)

# Spark's row-level tables (DELETE, UPDATE and MERGE INTO, copy-on-write or merge-on-read): the rows, their ids'
# sum and the notes left, in id order.
for name in ("v2_cow", "v2_mor", "v3_cow", "v3_mor"):
    data = catalog.load_table(("m8", "shared", "spark", f"t_{name}")).scan().to_arrow()
    rows = sorted(data.select(["id", "note"]).to_pylist(), key=lambda row: row["id"])
    notes = ",".join(row["note"] for row in rows if row["note"] is not None)
    result("cross." + name.replace("_", ""), len(rows), sum(row["id"] for row in rows), notes)

SPARK = ("m8", "shared", "spark")


def spark_table(name):
    return catalog.load_table(SPARK + (name,))


def count_sum(scan):
    data = scan.to_arrow()
    return data.num_rows, (pc.sum(data.column("id")).as_py() if data.num_rows else 0)


def operations(table):
    """Snapshot operations from the first commit to the current one, following each snapshot's parent."""
    chain = []
    snapshot = table.current_snapshot()
    while snapshot is not None:
        chain.append(snapshot.summary.operation.value)
        snapshot = table.snapshot_by_id(snapshot.parent_snapshot_id) if snapshot.parent_snapshot_id else None
    return ",".join(reversed(chain))


# Schema evolution: rows written before the rename, drop, widening and struct change read by field ID.
table = spark_table("t_schema")
schema = table.schema()
rows = table.scan().to_arrow().to_pylist()
result("cross.schema", ",".join(field.name for field in schema.fields), len(rows), sum(row["id"] for row in rows),
       ",".join(sorted(row["note"] for row in rows)), sum(row["info"]["a"] for row in rows),
       ",".join(row["info"]["b"] for row in rows if row["info"]["b"] is not None))
result("cross.schema.ids", f"note={schema.find_field('note').field_id}",
       f"info.b={schema.find_field('info.b').field_id}", f"id={schema.find_field('id').field_type}")

# Partition evolution and sort order: every row, three specs, the default back to spec 0, sort order 1.
table = spark_table("t_partition")
result("cross.partition", *count_sum(table.scan()), len(table.specs()), table.spec().spec_id,
       table.sort_order().order_id)

# Time travel: tag first and the snapshot current at the first commit's time read three rows; the table four.
table = spark_table("t_travel")
first = table.snapshot_by_name("first")
earliest = min(table.snapshots(), key=lambda snapshot: snapshot.timestamp_ms)
at_time = table.snapshot_as_of_timestamp(earliest.timestamp_ms)
result("cross.travel", count_sum(table.scan(snapshot_id=first.snapshot_id))[0],
       count_sum(table.scan(snapshot_id=at_time.snapshot_id))[0], count_sum(table.scan())[0])

# Branches: main fast-forwarded to branch audit (four rows), tag v1 at three; WAP published by cherry-pick.
table = spark_table("t_branch")
refs = table.metadata.refs
result("cross.branch", count_sum(table.scan())[0], count_sum(table.scan(snapshot_id=refs["v1"].snapshot_id))[0],
       "same" if refs["audit"].snapshot_id == refs["main"].snapshot_id else "differ")
table = spark_table("t_wap")
result("cross.wap", count_sum(table.scan())[0], len(table.snapshots()))

# After compaction and expiry: four rows (2, 4, 5, 6), one snapshot.
table = spark_table("t_maint")
result("cross.maint", *count_sum(table.scan()), len(table.snapshots()))

# Upgraded from v2 to v3: the old position delete and the new deletion vector both applied.
table = spark_table("t_upgrade")
result("cross.upgrade", table.metadata.format_version, *count_sum(table.scan()))

# Row lineage: next-row-id after an append of three rows and a copy-on-write update that rewrote all three, and
# each snapshot's first row ID.
table = spark_table("t_lineage")
result("cross.lineage", count_sum(table.scan())[0], table.metadata.next_row_id,
       ",".join(str(snapshot.first_row_id) for snapshot in sorted(table.snapshots(),
                                                                  key=lambda snapshot: snapshot.sequence_number)))

# v3 types PyIceberg 0.12 cannot load: the table's metadata does not parse.
for name, check in (("t_variant", "cross.variant"), ("t_geo", "cross.geo")):
    try:
        table = spark_table(name)
        result(check, *count_sum(table.scan()))
    except Exception as error:  # noqa: BLE001 - the refusal is the result
        message = str(error)
        if "Unsupported field type: 'variant'" in message:
            limit(check, "PyIceberg 0.12 has no variant type: Unsupported field type: 'variant'")
        elif "Could not parse geometry(OGC:CRS84) into a GeometryType" in message:
            limit(check, "PyIceberg 0.12 parses only a quoted CRS, geometry('...'); Iceberg Java writes "
                         "geometry(OGC:CRS84): Could not parse geometry(OGC:CRS84) into a GeometryType")
        else:
            raise

# CREATE OR REPLACE kept the table's history: three snapshots, the current one without a parent, two rows.
table = spark_table("t_replace")
result("cross.replace", len(table.snapshots()), *count_sum(table.scan()),
       "root" if table.current_snapshot().parent_snapshot_id is None else "child")

# The view Spark left: two versions (created, then replaced), its columns, its comment and its Spark SQL.
view = catalog.load_view(SPARK + ("v_rows",))
sql_text = view.sql_for("spark").sql
result("cross.view", view.current_version().version_id, len(view.versions),
       ",".join(field.name for field in view.schema().fields), view.properties.get("comment"),
       "note-filter" if "WHERE note IS NOT NULL" in sql_text else sql_text)

# compute_table_stats on t_v2: one statistics file for the current snapshot, one theta sketch per column with its
# distinct count (ids 1-4, names a-d, one note).
table = spark_table("t_v2")
files = table.metadata.statistics
names = {field.field_id: field.name for field in table.schema().fields}
blobs = files[0].blob_metadata if files else []
result("cross.stats", len(files), "current" if files and files[0].snapshot_id == table.metadata.current_snapshot_id
       else "other", ",".join(sorted({blob.type for blob in blobs})),
       ",".join(f"{names[blob.fields[0]]}:{blob.properties.get('ndv')}" for blob in sorted(blobs, key=lambda b: b.fields)))

# PyIceberg's own writes on a v2 table it creates (left for SingleStore): append four rows, delete id 2, overwrite
# id 3 with c2, upsert id 4 (to d2) and id 5. PyIceberg rewrites files (copy-on-write): a partial delete commits an
# overwrite; overwrite is that delete plus an append; upsert is an overwrite of the changed row plus appends of it
# and the new row.
ensure_namespace("m8", "shared", "pyiceberg")
identifier = ("m8", "shared", "pyiceberg", "t_writes")
if catalog.table_exists(identifier):
    catalog.purge_table(identifier)
table = catalog.create_table(identifier, SCHEMA, properties={"format-version": "2"})
ROWS = pa.schema([("id", pa.int64()), ("name", pa.string())])
table.append(pa.Table.from_pylist([{"id": i, "name": n} for i, n in ((1, "a"), (2, "b"), (3, "c"), (4, "d"))],
                                  schema=ROWS))
table.delete("id = 2")
table.overwrite(pa.Table.from_pylist([{"id": 3, "name": "c2"}], schema=ROWS), overwrite_filter="id = 3")
upserted = table.upsert(pa.Table.from_pylist([{"id": 4, "name": "d2"}, {"id": 5, "name": "e"}], schema=ROWS),
                        join_cols=["id"])
table = catalog.load_table(identifier)
rows = sorted(table.scan().to_arrow().to_pylist(), key=lambda row: row["id"])
result("writes", table.metadata.format_version, len(rows), sum(row["id"] for row in rows),
       ",".join(row["name"] for row in rows))
result("writes.upsert", upserted.rows_updated, upserted.rows_inserted)
result("writes.ops", operations(table))

"""PyIceberg 0.12.0 against the catalog. Prints RESULT/LIMIT lines that EngineMatrixTest asserts."""
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
    "uri": "http://catalog:8181",
    "warehouse": "engines",
    "token": os.environ["FIDICE_TOKEN"],
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

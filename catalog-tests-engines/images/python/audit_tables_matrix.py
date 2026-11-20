"""The queryable audit table in the engine matrix (D23): PyIceberg 0.12.0 reads nuna_audit.audit.events as the readers'
group, on the token in NUNA_AUDIT_TOKEN.

Prints RESULT lines that EngineMatrixTest#auditTables asserts, the same checks Spark's audit_tables_matrix.py makes:
the format version, the privileges the server granted on the audit catalog at startup, whether the matrix's own
commits in catalog engines are there, how many rows the writer's own requests made, and whether every row has a place
in the topic of its own.
"""
import os

from pyiceberg.catalog import load_catalog

ENGINE = "audit"


def result(check, *values):
    print("RESULT", ENGINE, "pyiceberg." + check, *values, flush=True)


catalog = load_catalog("audit", **{
    "type": "rest",
    "uri": "http://nuna-catalog:8181",
    "warehouse": "nuna_audit",
    "token": os.environ["NUNA_AUDIT_TOKEN"],
    "header.X-Iceberg-Access-Delegation": "vended-credentials",
})
table = catalog.load_table(("audit", "events"))
result("format", table.metadata.format_version)
rows = table.scan(selected_fields=("type", "principal", "catalog", "privilege", "topic_partition",
                                   "topic_offset")).to_arrow().to_pylist()
seeded = sorted({row["privilege"] for row in rows if row["type"] == "nuna.catalog.grant"
                 and row["principal"] == "nuna-configuration" and row["catalog"] == "nuna_audit"})
result("seeded", ",".join(seeded))
commits = [row for row in rows if row["type"] == "nuna.catalog.commit" and row["principal"] == "engine-matrix"
           and row["catalog"] == "engines"]
result("commits", "yes" if commits else "no")
result("own", sum(1 for row in rows if row["principal"] == "nuna-audit-tables"))
places = {(row["topic_partition"], row["topic_offset"]) for row in rows}
result("offsets", "unique" if rows and len(rows) == len(places) else f"{len(rows)} rows at {len(places)} places")

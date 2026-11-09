"""DuckDB 2.0 (iceberg extension) against the catalog. Prints RESULT/LIMIT lines that EngineMatrixTest asserts."""
import os

import duckdb

ENGINE = "duckdb"


def result(check, *values):
    print("RESULT", ENGINE, check, *values, flush=True)


con = duckdb.connect()
con.execute("LOAD iceberg")
con.execute("LOAD httpfs")
con.execute(f"CREATE SECRET fid_token (TYPE iceberg, TOKEN '{os.environ['FIDICE_TOKEN']}')")
con.execute("""
    ATTACH 'engines' AS fid (
        TYPE iceberg,
        SECRET fid_token,
        URI 'http://catalog:8181',
        ACCESS_DELEGATION_MODE 'vended_credentials',
        SUPPORT_NESTED_NAMESPACES true,
        PURGE_REQUESTED true
    )""")


def one(query):
    return con.execute(query).fetchone()


def schemas():
    return [row[0] for row in con.execute(
        "SELECT schema_name FROM duckdb_schemas() WHERE database_name = 'fid'").fetchall()]


# The catalog requires each parent level to exist (D28).
for name in ("m8", "m8.deep", "m8.deep.duckdb"):
    con.execute(f'CREATE SCHEMA IF NOT EXISTS fid."{name}"')
result("ns.create", "m8.deep.duckdb" if "m8.deep.duckdb" in schemas() else schemas())

NS = 'fid."m8.deep.duckdb"'
for version in ("2", "3"):
    table = f"{NS}.t_v{version}"
    con.execute(f"DROP TABLE IF EXISTS {table}")  # a rerun against a stack kept up by hand
    con.execute(f"CREATE TABLE {table} (id BIGINT, name VARCHAR) WITH ('format-version' = '{version}')")
    # With a nested namespace the table must be named as a string; the bare name is refused (DuckDB 2.0.0).
    seen = one(f"SELECT metadata['format-version'] FROM iceberg_load_table_response('{table}')")[0]
    result(f"v{version}.create", seen)
    con.execute(f"INSERT INTO {table} VALUES (1, 'a'), (2, 'b'), (3, 'c')")
    result(f"v{version}.read", *one(f"SELECT count(*), sum(id) FROM {table}"))
    con.execute(f"ALTER TABLE {table} ADD COLUMN note VARCHAR")
    con.execute(f"INSERT INTO {table} VALUES (4, 'd', 'x')")
    result(f"v{version}.evolve", *one(f"SELECT count(*), count(note) FROM {table}"))
    con.execute(f"DROP TABLE {table}")
    left = one(f"SELECT count(*) FROM duckdb_tables() WHERE database_name = 'fid' "
               f"AND schema_name = 'm8.deep.duckdb' AND table_name = 't_v{version}'")[0]
    result(f"v{version}.drop", "ok" if left == 0 else "still-listed")

con.execute(f"DROP SCHEMA {NS}")
result("ns.drop", "ok" if "m8.deep.duckdb" not in schemas() else "still-listed")

for version in ("2", "3"):
    result(f"cross.v{version}", *one(f'SELECT count(*), count(note) FROM fid."m8.shared.spark".t_v{version}'))
result("cross.v3dv", *one('SELECT count(*), sum(id) FROM fid."m8.shared.spark".t_v3_dv'))

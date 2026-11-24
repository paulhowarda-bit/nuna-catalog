"""Catalog branches and tags (D33) in the engine matrix: PyIceberg on a tag and a branch of catalog engines, and the
merge of the branch into main through the management API (backlog 112).

A stock client names a ref by its warehouse, engines@<ref>: /v1/config answers that prefix and, for a tag, only the
read endpoints. Run with a mode; prints RESULT lines that EngineMatrixTest#refs asserts:

setup    on main: tables m8.refs.a (three rows) and b (one row); tag m8-tag; a fourth row in a; branch m8-dev, so
         main has moved past the tag but not past the branch's cut
branch   after Spark's turn (images/spark/refs_matrix.py): reads the tag; is refused an append there (by the store:
         the tag's credentials only read) and a change of properties (by PyIceberg itself: the tag's /v1/config
         leaves out table commits); appends a row to each table on the branch; reads the branch and main
merge    the merge's dry run and the merge through the management API, the branch after it (gone), and main
cleanup  deletes the tag (the merge ended the branch), purges both tables and drops the namespace

Each count is "<rows in a> <sum of a's ids> <rows in b> <sum of b's ids>". Every management call's answer is printed
(CALL lines, not asserted), so a refusal shows its reason.
"""
import os
import sys

import pyarrow as pa
import pyarrow.compute as pc
import requests
from pyiceberg.catalog import load_catalog
from pyiceberg.exceptions import NamespaceAlreadyExistsError, NoSuchNamespaceError

ENGINE = "refs"
MODE = sys.argv[1]
URI = "http://nuna-catalog:8181"
REFS = URI + "/management/v1/catalogs/engines/refs"
TOKEN = os.environ["NUNA_TOKEN"]
NAMESPACE = ("m8", "refs")
TABLES = ("a", "b")
TAG = "m8-tag"
BRANCH = "m8-dev"
ARROW = pa.schema([("id", pa.int64()), ("name", pa.string())])


def result(check, *values):
    print("RESULT", ENGINE, check, *values, flush=True)


def catalog(ref=None):
    """Catalog engines, or its ref through the warehouse engines@<ref>, as any stock client names one."""
    warehouse = "engines" if ref is None else "engines@" + ref
    return load_catalog(warehouse, **{
        "type": "rest",
        "uri": URI,
        "warehouse": warehouse,
        "token": TOKEN,
        "header.X-Iceberg-Access-Delegation": "vended-credentials",
    })


def call(method, path="", **kwargs):
    """A management API call on the catalog's refs, its answer printed."""
    response = requests.request(method, REFS + path, headers={"Authorization": "Bearer " + TOKEN}, timeout=120,
                                **kwargs)
    print("CALL", method, path or "/", response.status_code, response.text[:2000], flush=True)
    return response


def rows(*values):
    return pa.Table.from_pylist([{"id": id_, "name": name} for id_, name in values], schema=ARROW)


def counts(on):
    values = []
    for name in TABLES:
        data = on.load_table((*NAMESPACE, name)).scan().to_arrow()
        values += [data.num_rows, pc.sum(data.column("id")).as_py() if data.num_rows else 0]
    return values


def refused(check, write):
    """Runs a write the tag must refuse and prints who refused it: "store" when the object store denied the data file
    (the tag's credentials only read), "endpoint" when the client would not send the commit (the tag's /v1/config
    leaves table commits out). Any other error ends the run, and success prints a RESULT the test does not expect."""
    try:
        write()
    except Exception as error:  # noqa: BLE001 - the error text is the evidence
        text = f"{type(error).__name__}: {error}"
        lowered = text.lower()
        if "server does not support endpoint" in lowered:
            result(check, "refused endpoint")
        elif any(word in lowered for word in ("access denied", "accessdenied", "access_denied", "403")):
            result(check, "refused store")
        else:
            raise
        print("REFUSED", check, " ".join(text.split())[:500], flush=True)
        return
    result(check, "accepted")


def clean():
    """Deletes the tag and the branch, then purges the tables (a ref's cut would keep them) and the namespace.
    Returns the two deletions' statuses."""
    statuses = [call("DELETE", "/" + ref).status_code for ref in (TAG, BRANCH)]
    main = catalog()
    for name in TABLES:
        if main.table_exists((*NAMESPACE, name)):
            main.purge_table((*NAMESPACE, name))
    try:
        main.drop_namespace(NAMESPACE)
    except NoSuchNamespaceError:
        pass
    return statuses


if MODE == "setup":
    clean()
    main = catalog()
    for depth in range(1, len(NAMESPACE) + 1):
        try:
            main.create_namespace(NAMESPACE[:depth])
        except NamespaceAlreadyExistsError:
            pass
    # v2: the catalog makes v3 tables by default, and PyIceberg 0.12 cannot write a v3 manifest.
    a = main.create_table((*NAMESPACE, "a"), ARROW, properties={"format-version": "2"})
    b = main.create_table((*NAMESPACE, "b"), ARROW, properties={"format-version": "2"})
    a.append(rows((1, "a"), (2, "b"), (3, "c")))
    b.append(rows((1, "x")))
    tag = call("POST", json={"name": TAG, "kind": "tag"})
    # After the tag and before the branch: main moves past the tag, and the branch's cut has the row.
    a.append(rows((4, "d")))
    branch = call("POST", json={"name": BRANCH, "kind": "branch"})
    result("setup", tag.status_code, branch.status_code)
elif MODE == "branch":
    tag, dev, main = catalog(TAG), catalog(BRANCH), catalog()
    result("pyiceberg.tag.read", *counts(tag))
    on_tag = tag.load_table((*NAMESPACE, "a"))
    refused("pyiceberg.tag.write", lambda: on_tag.append(rows((99, "refused"))))
    refused("pyiceberg.tag.alter",
            lambda: on_tag.transaction().set_properties(**{"nuna.matrix": "refused"}).commit_transaction())
    for name in TABLES:
        dev.load_table((*NAMESPACE, name)).append(rows((20, "pyiceberg")))
    result("pyiceberg.branch.write", *counts(dev))
    result("pyiceberg.branch.main", *counts(main))
elif MODE == "merge":
    dry = call("POST", "/" + BRANCH + "/merge", params={"dry-run": "true"})
    plan = dry.json()
    moving = sorted(table["name"] for table in plan.get("tables", []) if table.get("moves"))
    result("merge.dry-run", dry.status_code, plan.get("answer", {}).get("code"), plan.get("changed"),
           str(plan.get("may-merge")).lower(), ",".join(moving))
    merged = call("POST", "/" + BRANCH + "/merge")
    result("merge", merged.status_code, ",".join(sorted(table["name"] for table in merged.json().get("tables", []))))
    result("merge.branch", call("GET", "/" + BRANCH).status_code)
    result("pyiceberg.main", *counts(catalog()))
elif MODE == "cleanup":
    result("cleanup", *clean())
else:
    raise SystemExit("mode: setup, branch, merge or cleanup")

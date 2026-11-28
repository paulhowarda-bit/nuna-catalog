# Branches and tags

A catalog's branches and tags (D33, item 26) are refs of the whole catalog, for engine users and operators. A **tag** is
a named point: every table's and view's metadata file, and its name, as they were when the tag was made. A **branch**
is a point plus pointers of its own: a table or view committed on the branch has its own metadata file there, and every
other one reads as at the point. Main is the catalog itself. Nothing is copied: a ref names metadata files main already
has, and a branch's commits write files of their own.

Built by items 102 to 111, 156 and 157. The milestone closes on the first green overnight run with them landed
(`build-plan.md`).

## Naming a ref

An engine names a ref by its REST prefix, `<catalog>@<ref>`: `trading@dev` is branch `dev` of catalog `trading`, and
`trading@main` is main, the same as `trading`. A stock client gets the prefix by asking `/v1/config` for the warehouse
`trading@dev`. The catalog answers that prefix, with the endpoints the ref allows, and the client sends every request
under it (`/v1/trading@dev/namespaces/...`). One catalog configuration in the engine is one ref; to read two refs, or
a ref and main, configure two.

```properties
# Spark (Iceberg 1.12): a second catalog, "dev", on the branch
spark.sql.catalog.dev=org.apache.iceberg.spark.SparkCatalog
spark.sql.catalog.dev.type=rest
spark.sql.catalog.dev.uri=https://nuna.example/
spark.sql.catalog.dev.warehouse=trading@dev
spark.sql.catalog.dev.header.X-Iceberg-Access-Delegation=vended-credentials
```

```python
# PyIceberg (0.12): a tag
tag = load_catalog("eod", type="rest", uri="https://nuna.example", warehouse="trading@eod-2026-11-20",
                   token=token, **{"header.X-Iceberg-Access-Delegation": "vended-credentials"})
```

A ref's name is matched without regard to case and shown as it was given. It may not contain `@` or `/`, may not be
`main`, and is at most 1,000 characters. A ref the catalog does not have, or a branch past its expiry, answers 404
`NoSuchWarehouseException` ("The given warehouse does not exist: trading@dev (catalog trading has no branch or tag
dev)"), on `/v1/config` and every other route.

The engine matrix (`catalog-tests-engines`, `EngineMatrixTest#refs`, overnight) runs Spark and PyIceberg this way.

## What a tag and a branch allow

| | main | branch `catalog@branch` | tag `catalog@tag` |
|---|---|---|---|
| Read a table or view | its current version | the branch's own version if it committed one, else as at the point | as at the point |
| Read a namespace's properties | live | live (main's) | live (main's) |
| Commit to a table, replace a view, `POST /transactions/commit` | yes | yes, on the branch's own pointers | 400 |
| Create, drop, rename, register or unregister a table or view; create or drop a namespace; change its properties | yes | 400 | 400 |
| Vended credentials | write, for a caller who may write | write, for a caller who may write, while main still has the table | read only |
| `/v1/config` advertises | every endpoint | the reads, table commits, view replaces and transactions | the eleven read endpoints |

A ref keeps names as well as contents: a table dropped or renamed on main after the point still reads through the
ref under the name it had, and a table created on main after the point is not there (404). Grants are main's: a
table's grants hold on every ref, and reading through a ref needs what reading main needs.

**A grant of write or modify may name one branch** (backlog 113). It holds on that branch alone: the group may commit
there and nowhere else, and a commit on the branch is allowed by such a grant or by one on every ref. Only write and
modify may name a branch, since a commit is all a branch grant is for; reads and every other change ask through main.
A grant cannot name a tag (400). The grants routes take `"branch": "<name>"` on grant, revoke, preview and check, and
list a branch grant with its branch's name; the audit record names the branch by name and id (`grant`, version 2). A
branch-only writer's vended credentials read the whole table but write only beneath `<table>/branches/<ref id>/`, and
the load response points `write.data.path` and `write.metadata.path` there, so its files never land among main's. A
branch grant ends with its branch: deleting or merging the branch deletes it.

**A tag is read-only, three times over.** The catalog refuses a write on a tag with 400 "updateTable is refused on tag
eod of catalog trading: a tag is read-only", naming the operation. Stock clients never get that far.
- Iceberg Java 1.12 and PyIceberg 0.12 check the endpoints `/v1/config` advertised before they commit. A tag
  advertises no commit endpoint, so a property change or any other metadata-only commit fails in the client:
  "Server does not support endpoint: POST /v1/{prefix}/namespaces/{namespace}/tables/{table}". Iceberg Java throws
  `UnsupportedOperationException`, PyIceberg `NotImplementedError`.
- A write that adds data writes its data files first, and the tag's credentials only read: the store refuses the
  PutObject (403).

**On a branch**, a commit is checked by main's rules (D11, holds, the minimum snapshot age, the file-IO property ban)
and writes a metadata file of the branch's own, `<version>-<uuid>.branch-<ref id>.metadata.json`. The table's
version on the branch starts at 1. Main does not move.
- A branch changes at most `nuna.refs.max-merge-tables` tables and views (100), so that it can merge in one
  transaction. A commit that would change one more answers 400 naming the setting.
- A commit to a table main has dropped or unregistered since the point answers 404, saying so. A commit on a branch
  that is gone (merged, deleted or past its expiry) answers 404 "Branch dev is gone: merged, deleted or past its
  expiry".
- A DR freeze (D37) does not refuse a commit on a branch: it binds the merge.
- An Idempotency-Key is bound to the branch it was sent on. The `Commit` audit event names the branch, and
  `nuna.catalog.commits` counts the commit with `ref.kind="branch"`.
- Each commit that lands moves the branch's expiry `nuna.refs.branch-idle-expiry` ahead, once the move gains an hour.

## Making, reading and deleting refs

Under `/management/v1/catalogs/{catalog}/refs`. Making, renewing, deleting and merging need `create` on the catalog.
Listing, reading and a merge's dry run need any holding in it. Each write takes an `Idempotency-Key` and writes a
`ref` audit record (`nuna.catalog.ref`: created, renewed, deleted, merged, expiring, expired, object-removed) to the
audit topic with the change.

| Route | Does |
|---|---|
| `GET …/refs` | The refs by name, paged (`pageToken`, `pageSize` up to 1,000). Each branch has its `changed-count`. The answer carries the catalog's `limits` and what the caller `may` do. `?expired=true` also lists branches past their expiry that still hold their name. Who made a ref is shown only to a caller holding `create`; its `description` to every caller. |
| `POST …/refs` | Makes `{"name": "dev", "kind": "branch" \| "tag", "from": "main" \| <ref>, "description": "…"}` (`from` defaults to main; `description`, the ref's purpose, is optional): 201. The description is one line of plain text, its ends stripped, at most 200 characters (`limits.max-description`); a longer one, or one holding a control, line-breaking or bidirectional formatting character, answers 400. Every read of the ref carries it to anyone who may list the refs, a renewal keeps it, and the `created` record holds it (schema `ref:2`; a record without one stays `ref:1`). A name taken answers 409 `AlreadyExistsException`; a limit reached, 409 `RefLimitException` naming the setting; an unknown `from`, 404 `NoSuchRefException`. |
| `GET …/refs/{ref}` | One ref; a branch also lists the tables and views it changed. |
| `POST …/refs/{ref}/renew` | Moves a branch's expiry `branch-idle-expiry` ahead, never past `branch-max-life` from its creation and never back: 200. A tag: 400. |
| `DELETE …/refs/{ref}` | Deletes a branch or tag, a branch's own pointers with it: 204. Its files are cleanup's once no ref names them. A DR freeze does not refuse it. |
| `POST …/refs/{ref}/merge` | Merges a branch into main (below). `?dry-run=true` writes nothing. |

The console has the same on its **Branches and tags** page in the rail (the catalog's address with `tab=refs`): the
list with the limits, **Make a branch or tag**, and on each line **Read**, **Merge…**, **Renew** and **Delete**,
offered only when the caller may. Each catalog, namespace, table and view page reads through a ref from the
**Reading main** selector in the header (the page's address carries `r=`); the band under the title says "Reading the
tag …" or "Reading the branch …", with **Read main instead**. A ref has a page of its own with its changes and audit
records. A table's own Iceberg refs are apart: on its Overview, **Engine-level refs in this table's metadata**, and
on its History tab, **Snapshots and Iceberg refs**.

## Merging a branch

`POST /management/v1/catalogs/{catalog}/refs/{ref}/merge` moves every table and view the branch changed into main in
one transaction, and ends the branch. Per table:
- **Main has not moved since the point:** main takes the branch's version.
- **Main moved only by its own maintenance** (compaction and manifest rewrites, which add only `replace` snapshots,
  and snapshot expiry): this does not count (Paul, 2026-11-22). The table moves over it, marked `rebased`. Main takes
  the branch's version, so the snapshots main's compaction added since the point go from main. They held no rows of
  their own, the files the point named are kept while a ref names them, and the next night compacts again.
- **Main moved in any other way:** data, a schema, partitioning, sort order, properties, the location, the format
  version, or an Iceberg ref made or moved. The table conflicts. So do a view main replaced since the point, a table
  main dropped, unregistered or purged, and a regulated table whose metadata file fails its hash.

**Conflicts.** If any table conflicts, nothing moves: 409 `RefMergeConflictException`, with a message naming each
conflict ("desk.orders (cut version 2, main version 3): main committed snapshot … (append)") and a `conflicts` array.
Main's change and the branch's are both kept. Nothing rebases a branch onto main's change: to go on, make a new branch
from main and commit the change again there.

**Rules and rights.** The merge is judged as a commit on main of the whole change, from main's metadata to the
branch's. Every rule of main applies to it: legal holds, a DR freeze, the minimum snapshot age, the immutable key id
and the file-IO property ban.
- A held table that main compacted since the point cannot merge while the hold stands: under a hold the rules see the
  snapshots the merge would drop.
- Besides `create` on the catalog, the caller needs, on each table it moves, `write` for a change of data alone, or
  `modify` for a change of definition (schema, partitioning, properties and the like). A view always needs `modify`.
  Flagged tables (D20), D27's attribute policies and the D17 switch apply as on a commit; a flagged table merges only
  for a trusted engine, and the console is not one. A maintenance principal's exemption does not apply.
- A refusal answers 403 and is audited as a denial.

**Limits.** A branch over `nuna.refs.max-merge-tables` answers 409 `RefLimitException`. A merge that keeps losing to
concurrent commits on main tries again within P10's bound, then answers 409 ("lost to concurrent commits"). A tag
answers 400; a branch that is gone, 404.

**What main gets.** For each table, main writes its own copy of the branch's metadata at its next version, an
ordinary main file whose metadata log is main's, so a DR reconcile never walks into a branch's files. What main's
snapshot expiry removed since the point stays removed. Main's journal records one `merge-branch` entry per table,
naming the branch, its version, the point's version, how many of main's commits it was rebased over and any change of
definition. Each table gets a `Commit` event on main, and the branch a `ref` record, merged. A branch that changed
nothing ends at once.

The merge answers 200 with each table moved: `cut-version`, `main-version`, `branch-version`, `version` (main's new
one), `rebased`, `definition-change` and `metadata-location`.

**The dry run**, `?dry-run=true`, writes nothing and answers 200 with:
- `answer`: what the merge would answer now, `{"code": 200}` or the error;
- `changed` and `max-tables`;
- `tables`: each with the fields above, its `conflict` (and `dropped-at`), main's commits since the point
  (`main-commits.count`, and `by` for a caller holding `create`), main's `verdict` on its change and the `right` it
  needs and whether the caller holds it;
- `may-merge`: whether this caller may merge now;
- `hidden`: tables and views the caller may not describe, counted, never named.

## Limits and expiry (P11)

| Setting | Default | |
|---|---|---|
| `nuna.refs.max-branches` | 50 | open branches a catalog may hold |
| `nuna.refs.max-tags` | 1000 | tags a catalog may hold |
| `nuna.refs.branch-idle-expiry` | 30 days | how far ahead making, renewing or committing on a branch sets its expiry |
| `nuna.refs.branch-max-life` | 180 days | from a branch's creation; no renewal passes it |
| `nuna.refs.expiry-warning` | 7 days | when the maintenance worker warns of a branch about to expire |
| `nuna.refs.max-merge-tables` | 100 | tables and views a branch may change, so one transaction merges them |

Tags never expire. A branch past its expiry is gone at once for every route, but keeps its name until the maintenance
worker ends it, so a new branch of that name waits for the worker's next scan. The worker warns of each branch once,
with a `ref` record (expiring) on the audit topic and the change stream, and ends it after its expiry (expired). The
gauges `nuna.catalog.refs` and `nuna.catalog.refs.expiring` show each catalog's count against its limits
(`docs/observability/catalog-metrics.md`). Deployment notes: `deploy/README.md`, "Branches and tags".

## Files, purges and namespaces

Every file a ref names is kept: the maintenance worker's snapshot expiry and orphan scan read every ref's metadata, a
branch past its expiry included until the worker ends it. Keep the replicas' clocks within an hour of each other
(`deploy/README.md`, "Clocks").
- A table dropped on main that a ref still names stays readable through the ref, and its purge waits until no ref
  names it.
- A namespace holding an object a ref names cannot be dropped: 409 `NamespaceNotEmptyException` "… is not empty: a
  branch or tag names a table or view in it" (item 157).

## Taking a table out of refs

`POST /management/v1/catalogs/{catalog}/objects/{id}/refs/remove` takes one table or view, by id, dropped or not, out
of refs (item 156), so that its data can leave a tag:
- `{"refs": ["eod", "dev"]}` names live refs;
- `{"all-refs": true}` takes it out of every ref whose point names it, a branch past its expiry included.

It leaves each ref's point, and a branch's own pointer for it goes too, in one transaction. Reads of it through those
refs then answer 404, and once no ref names a dropped table its purge goes through as usual.
- It needs `manage-tags` on the object, the tag steward's duty, kept apart from data (D18), so a data writer cannot
  erase history with it.
- A legal hold or a DR freeze on the object, a namespace above it or the catalog refuses it, audited as a denial.
- It answers 200 with the object and the refs it left, each with a `ref` record (object-removed). It answers 404 for
  an object the catalog does not hold and for a named ref that is gone or does not name it. Refs that keep changing
  under it answer 409 after three tries.

## After a regional failover

The DR reconcile (`deploy/README.md`, "Recovering in the DR region", step 6) reports every branch and tag whose point,
or a branch's own pointer, names a metadata file the DR store lacks (item 111): `refs`, each with the tables and views
under `missing` (`id`, `kind`, `path`, `pointer` `cut` or `head`, `version`, `metadata-location`) or a `problem`, and
`refs-checked`. Nothing walks or rewinds a ref (D33: a report only). Delete each listed ref, which the freeze allows,
or keep it for the primary region's return; until then a read through it that needs a listed file fails.

## Catalog refs and Iceberg's refs inside a table

An Iceberg branch or tag is a named snapshot inside one table's metadata file: an engine makes it with `ALTER TABLE …
CREATE TAG` or `manageSnapshots()` and reads it with `VERSION AS OF 'x'` or `t.branch_x`. A catalog ref chooses which
metadata file every table's pointer names, for all of them at once. The two nest:
- On `trading@dev`, each table's metadata has its own Iceberg refs. An engine's write goes to that metadata's Iceberg
  `main`, or to an Iceberg branch it names.
- Iceberg refs made on a catalog branch reach main only by merge, which takes the branch's whole metadata file.
- An Iceberg ref made or moved on main since a catalog branch's point (a write-audit-publish branch, say) is a
  change on main, so that table conflicts.
- The same name in both means two unrelated things: `VERSION AS OF 'dev'` reads the table's Iceberg ref `dev`,
  never catalog branch `dev`.

The catalog never uses Iceberg's refs for its own. `manage-tags` (D18) is about governance tags (D27) and taking a
table out of refs, not about making either kind of ref.

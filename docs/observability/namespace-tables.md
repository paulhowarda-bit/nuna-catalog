# A namespace's tables: format, snapshots and last data change

The console's namespace **Contents** (D45; `docs/design/console/prototype.html`, the namespace page) gives each table
row its format, its snapshot count and its last data change (backlogs 137 and 330). One read gives them for a namespace's tables, a
page at a time, from what the catalog keeps of each table's current metadata. It reads no metadata file and loads no
table, so it writes no audit record per table: a load through Iceberg REST would read one file from the object store
and write one audit record for every row, each time someone opens the namespace.

A child namespace's row has totals over the tables beneath it (backlog 147): how many, their snapshots summed, and
the newest data change among them. A second read gives them for every child of a namespace, or of the catalog, from the
same reads (below, "A child namespace's totals").

## The read

`GET /management/v1/catalogs/{catalog}/namespaces/{namespace}/tables?pageSize=100&pageToken=...`

```json
{
  "tables": [
    {
      "id": "6c1d...",
      "name": "trades",
      "format-version": 2,
      "snapshots": 3,
      "last-commit": {
        "version": 7,
        "committed-at": "2026-11-22T06:41:12.345Z",
        "principal": "trino",
        "end-user": "ana",
        "engine": "trino"
      },
      "last-data-change": {
        "version": 7,
        "committed-at": "2026-11-22T06:41:12.345Z",
        "principal": "trino",
        "end-user": "ana",
        "engine": "trino"
      }
    }
  ],
  "next-page-token": "..."
}
```

- The rows are the namespace's active tables, by name; views and dropped tables are not listed.
- `format-version` and `snapshots` come from the projection the catalog stores beside each table's main pointer at
  every commit (D5): the format version and the number of snapshots in the metadata the pointer names.
- `last-commit` is the commit journal's entry for the pointer's current version: when it was committed, the principal
  that committed it, and the end user a trusted engine asserted and the engine, when there were any (D17).
- `last-data-change` is the newest commit, at or below that version on the same journal, that changed the table's
  rows (backlog 330), in the same form. The console's column shows it, since on a table the maintenance worker keeps,
  most commits are its snapshot expiries. A data change is a commit whose updates add a snapshot that appends,
  overwrites or deletes rows (Iceberg's `append`, `overwrite` and `delete`, or a snapshot that names no operation);
  one that merges a catalog branch; one that moves `main` to another snapshot without adding one (a rollback, or a
  cherry-pick that fast-forwards); and a registration or revive of a metadata file. Not one: a `replace` snapshot,
  which compaction and manifest rewrites add, holding the same rows in other files; a commit that adds no snapshot (an
  expiry, a schema, spec, sort order or property change, a ref other than main moved); and a create. The read looks
  at the newest 200 commits at most, 50 a read, and leaves `last-data-change` out when none of them changed data, or
  the table has none. Through a branch's own pointer, it looks at the branch's own journal only.
- `maintenance` is `true` on a commit a maintenance principal (`nuna.security.maintenance-principals`) made acting
  as itself, and left out otherwise; the console names such a committer "nuna maintenance", with the principal in the
  title. The table's metadata log (`GET …/objects/{id}/metadata-log`) marks its entries the same way.
- `pageSize` is 100 by default, at most 200. `next-page-token` is there while more tables may follow; pass it back as
  `pageToken`. A page may hold fewer than `pageSize` rows, even none, when the caller may describe few of the tables
  it looked at (it looks at 1,000 at most), so keep reading while there is a token. A token the catalog did not issue
  is 400 `BadRequestException`.

## Who sees what

- A table is listed only to a caller who may `describe` it, through a grant on it or above it (D18). Any other table
  is skipped: it is neither named nor counted, and skipping it is not a refusal, so nothing is audited for it.
- The namespace is answered to a caller who holds something on it, above it or in it, as the Iceberg routes answer
  it. Any other caller is refused, the refusal audited as `listNamespaceTables`, and answered 404
  `NoSuchNamespaceException`, the answer for a namespace that does not exist. A caller who holds nothing in the
  catalog gets 404 `NoSuchWarehouseException`, as on every management route (backlog 68).

## A child namespace's totals

`GET /management/v1/catalogs/{catalog}/namespaces?pageSize=100&pageToken=...` for the catalog's top namespaces, and
`GET /management/v1/catalogs/{catalog}/namespaces/{namespace}/namespaces?...` for a namespace's children.

```json
{
  "namespaces": [
    {
      "name": "eu",
      "tables": 2,
      "snapshots": 7,
      "complete": true,
      "newest-commit": {
        "table": ["xetra", "quotes"],
        "version": 2,
        "committed-at": "2026-11-22T06:59:50Z",
        "principal": "spark",
        "end-user": "bo",
        "engine": "spark"
      },
      "newest-data-change": {
        "table": ["xetra", "quotes"],
        "version": 2,
        "committed-at": "2026-11-22T06:59:50Z",
        "principal": "spark",
        "end-user": "bo",
        "engine": "spark"
      }
    }
  ],
  "next-page-token": "..."
}
```

- One row per child namespace, by name, as the prototype's namespace row draws it (`nsRow`, over `tablesUnder`): the
  totals cover every table beneath the child, at every depth, not only the tables directly in it.
- `tables` counts the active tables beneath it that the caller may `describe`; `snapshots` sums their projections'
  snapshot counts; `newest-commit` is the latest journal entry among their pointers' versions, with `table` naming
  that table by its levels below the child (the console shows `xetra.quotes`). `newest-data-change` is the newest of
  their `last-data-change`s, in the same form, and is what the console shows. A child with no table counted has 0
  and 0, and no `newest-commit`. Views and dropped tables are not counted.
- A table the caller may not describe is not counted, and nothing is audited for it. A child namespace the caller
  holds nothing on, above or in is not a row at all, so its name is not given.
- The parent is answered as the tables read answers a namespace: to a caller who holds something on it, above it or in
  it; any other is refused, audited as `listNamespaceTotals`, and answered 404 `NoSuchNamespaceException`. The
  catalog's top level is answered to any caller who holds something in the catalog.
- `pageSize` is 100 by default, 1 to 200. A page looks at 1,000 tables and namespaces at most, the children listed
  among them. A child whose walk would pass that waits for the next page, so its totals are whole there, unless it is
  the page's first row: then its totals cover the tables it looked at and `complete` is `false` (the console shows
  `412+ tables`). `next-page-token` is there while more children may follow; a token the catalog did not issue is
  400 `BadRequestException`.

## Through a branch or tag

Both reads take `?ref=<name>` (D33, backlog 168) and then read the catalog's branch or tag of that name, as the
Iceberg routes read `catalog@ref`: the namespaces and tables of its cut, by the names the cut has (one main made since
is not there, one main dropped since still is), and each table as the ref reads it.

```json
{
  "id": "6c1d...",
  "name": "trades",
  "format-version": 2,
  "snapshots": 3,
  "ref": {"pointer": "cut", "version": 4, "line": "main"},
  "last-commit": {"version": 4, "line": "main", "committed-at": "...", "principal": "trino"}
}
```

- `ref.pointer` is `own` for a branch's own pointer, which its commits made, and `cut` for the pointer its cut took.
- `ref.version` is that pointer's version, numbered on `ref.line`: the journal that wrote it. A branch numbers its own
  versions from 1 whatever the cut's (item 105), so for an `own` pointer the line is the ref's own name; for a `cut`
  one it is `main`, or the ref the cut took a branch's own pointer from, by the name it had then. A branch's journal
  stays after it ends, keyed by its id, so its line is found then too. Where it cannot be (a ref the cut came from has
  ended, and with it what that one was made from), `line` and `last-commit` are left out.
- `last-commit` is that journal's entry at that version, the commit that wrote the file the ref reads; its `version`
  is `ref.version`, on the same `line`. The totals' `newest-commit` carries `line` the same way.
- `format-version` and `snapshots` come from the projection beside a branch's own pointer. A cut's pointer keeps no
  projection: while main's pointer is still the cut's file, main's projection serves; otherwise the catalog reads the
  cut's metadata file, through the metadata cache, checked against the SHA-256 the cut kept, and takes its format
  version and number of snapshots. A file that no longer matches gives neither. No table is loaded and no audit record
  is written per table, as on main: format and snapshot count are what a caller who may describe the table sees.
- Rights are main's, as on every read through a ref (item 26's choice 12): a table is listed and counted to a caller
  who may `describe` it on main, and the namespace answered as above, by its id. 404 `NoSuchRefException` when no live
  ref has the name; 404 `NoSuchNamespaceException` when its cut has no such namespace.

The ref's own answers (`GET …/refs`, `GET …/refs/{ref}`) say when its cut was taken from main, `cut-at`: its making
for a ref made from main, and for one made from another ref, that of the ref at the foot of its line. A ref made
from a branch also holds the branch's own pointers as they were then: `branch-changes` names each such branch, with
`as-of`. Where a ref in that line has ended, what it was made from is not kept: `cut-at` is left out and
`cut-from-ended` names the ref that ended.

## The cost

Each row of the tables read costs two reads of the catalog's database: the table's main pointer and one journal
entry, and, unless that entry changed data, a read of up to 50 entries below it, at most four such reads. A page of
200 rows is 600 such reads beside the listing itself on a table whose newest 50 commits hold a data change, and no
read of the object store.

The totals read costs the same two reads for every table it counts, plus a listing of the tables and of the
namespaces in every namespace it walks: at most 1,000 tables and namespaces looked at a page, so at most about 2,000
pointer and journal reads. It reads no metadata file, loads no table, and writes no audit record per table. Contents
makes one such read a page for all its namespace rows, never one a row.

Through a ref each table costs the same, plus a read of main's pointer for a cut's table, and walking down the refs
the cut came from costs a journal read and a ref read a step. A cut's table whose file main has moved past costs one
read of the object store, unless the metadata cache holds the file; metadata files never change, so the cache never
goes stale. A page of 200 rows can read up to 200 files the first time; a totals page up to 1,000.

## Tests and bites

- `NamespaceTablesRoutesTest` (catalog-server, Java only): the route, guard and authorizer over a store double that
  fails any call it does not expect, the object store's among them. Each row's format, snapshots and last commit; a
  table the caller may not describe left out; a caller who holds nothing on the namespace answered as for one that
  does not exist, and audited; paging; the 200-row and 1,000-table bounds; a bad token. For the totals: each child's
  totals over every level beneath it and the newest commit's path; the last data change past a compaction and twelve
  of the worker's expiries, the worker's commit marked `maintenance`, and the newest data change of a child; a data
  change looked for among the newest 200 commits and not past them; a table the caller may not describe not counted,
  and a child it holds nothing on, above or in not a row; the parent refused as for one that does not exist, and
  audited; paging; the 200-row bound; the 1,000 looked at, a child waiting for the next page and a first row marked
  incomplete; a bad token.
- `DataChangesTest` (Java only): which commits are data changes. `MetadataLogRefTest#theMaintenancePrincipalsOwnCommitsAreMarked`:
  the metadata log marks the worker's own commits, on main and on a branch's line, and not one it made for an end user.
- `ConsoleBrowserTest#contentsShowEachTablesFormatSnapshotsAndLastCommit` (heavy): the columns in a browser, against
  the route's answer and the table's metadata log; a namespace row's count, snapshots and newest data change against
  the totals read, and a top namespace's row on the catalog's Contents.
  `ConsoleBrowserTest#expiriesFoldIntoOneRowAndContentsNamesTheLastDataChange` (heavy): three expiries after an append
  fold into one History row, opened and closed on click, and Contents names the append.
- Bites in `bites/D45/`: `namespace-tables-lists-undescribable` (every table listed, describable or not),
  `namespace-tables-page-unbounded` (a page without its 200-row bound) and `namespace-totals-count-undescribable`
  (every table beneath a child counted, describable or not); the two that filter bite through a ref too;
  `namespace-tables-data-change-counts-compaction` (a `replace` snapshot counted as a data change) and
  `metadata-log-maintenance-unmarked` (the worker's commits not marked).
- Through a ref (backlog 168), `NamespaceTablesRoutesTest` checks a tag's tables at the cut (a file read for the one
  main moved past, main's projection for the rest, a table dropped on main since still listed), a branch's own
  pointer on its own line, a tag made from a branch on that branch's line after it ended, rights and refusals as on
  main, and totals as the ref has each table; `RefRoutesTest#aRefsCutWasTakenWhenTheRefAtTheFootOfItsLineWasMadeFromMain`
  checks `cut-at`, `branch-changes` and `cut-from-ended`. `ConsoleBrowserTest#aBranchIsMadeCommittedOnPreviewedAndMerged`
  (heavy) checks the Contents columns through a branch. Bites in `bites/D33/`: `namespace-tables-ref-reads-main`,
  `namespace-tables-ref-own-ignored`, `namespace-tables-ref-line-main-only`, `ref-cut-at-own-making`,
  `ref-cut-at-past-ended` and `ref-branch-changes-unsaid`.

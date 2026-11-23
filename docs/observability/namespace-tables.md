# A namespace's tables: format, snapshots and last commit

The console's namespace **Contents** (D45; `docs/design/console/prototype.html`, the namespace page) gives each table
row its format, its snapshot count and its last commit (backlog 137). One read gives them for a namespace's tables, a
page at a time, from what the catalog keeps of each table's current metadata. It reads no metadata file and loads no
table, so it writes no audit record per table: a load through Iceberg REST would read one file from the object store
and write one audit record for every row, each time someone opens the namespace.

A child namespace's row has totals over the tables beneath it (backlog 147): how many, their snapshots summed, and
the newest commit among them. A second read gives them for every child of a namespace, or of the catalog, from the
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
  that table by its levels below the child (the console shows `xetra.quotes`). A child with no table counted has 0
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

## The cost

Each row of the tables read costs two reads of the catalog's database: the table's main pointer and one journal
entry. A page of 200 rows is 400 such reads beside the listing itself, and no read of the object store.

The totals read costs the same two reads for every table it counts, plus a listing of the tables and of the
namespaces in every namespace it walks: at most 1,000 tables and namespaces looked at a page, so at most about 2,000
pointer and journal reads. It reads no metadata file, loads no table, and writes no audit record per table. Contents
makes one such read a page for all its namespace rows, never one a row.

## Tests and bites

- `NamespaceTablesRoutesTest` (catalog-server, Java only): the route, guard and authorizer over a store double that
  fails any call it does not expect, the object store's among them. Each row's format, snapshots and last commit; a
  table the caller may not describe left out; a caller who holds nothing on the namespace answered as for one that
  does not exist, and audited; paging; the 200-row and 1,000-table bounds; a bad token. For the totals: each child's
  totals over every level beneath it and the newest commit's path; a table the caller may not describe not counted,
  and a child it holds nothing on, above or in not a row; the parent refused as for one that does not exist, and
  audited; paging; the 200-row bound; the 1,000 looked at, a child waiting for the next page and a first row marked
  incomplete; a bad token.
- `ConsoleBrowserTest#contentsShowEachTablesFormatSnapshotsAndLastCommit` (heavy): the columns in a browser, against
  the route's answer and the table's metadata log; a namespace row's count, snapshots and newest commit against the
  totals read, and a top namespace's row on the catalog's Contents.
- Bites in `bites/D45/`: `namespace-tables-lists-undescribable` (every table listed, describable or not),
  `namespace-tables-page-unbounded` (a page without its 200-row bound) and `namespace-totals-count-undescribable`
  (every table beneath a child counted, describable or not).

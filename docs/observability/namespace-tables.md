# A namespace's tables: format, snapshots and last commit

The console's namespace **Contents** (D45; `docs/design/console/prototype.html`, the namespace page) gives each table
row its format, its snapshot count and its last commit (backlog 137). One read gives them for a namespace's tables, a
page at a time, from what the catalog keeps of each table's current metadata. It reads no metadata file and loads no
table, so it writes no audit record per table: a load through Iceberg REST would read one file from the object store
and write one audit record for every row, each time someone opens the namespace.

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

## The cost

Each row costs two reads of the catalog's database: the table's main pointer and one journal entry. A page of 200
rows is 400 such reads beside the listing itself, and no read of the object store.

## Tests and bites

- `NamespaceTablesRoutesTest` (catalog-server, Java only): the route, guard and authorizer over a store double that
  fails any call it does not expect, the object store's among them. Each row's format, snapshots and last commit; a
  table the caller may not describe left out; a caller who holds nothing on the namespace answered as for one that
  does not exist, and audited; paging; the 200-row and 1,000-table bounds; a bad token.
- `ConsoleBrowserTest#contentsShowEachTablesFormatSnapshotsAndLastCommit` (heavy): the columns in a browser, against
  the route's answer and the table's metadata log.
- Bites in `bites/D45/`: `namespace-tables-lists-undescribable` (every table listed, describable or not) and
  `namespace-tables-page-unbounded` (a page without its 200-row bound).

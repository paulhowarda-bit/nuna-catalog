# The DR replication watermark

Storage and the catalog database both replicate to the DR region asynchronously (D37), so after the primary region is
lost the DR database can name files the DR store never got. The DR watermark says, for each table and view, the newest
version the DR store holds whole; the maintenance worker's verifier moves it, operators read it, and the reconcile
step of the DR runbook (`deploy/README.md`, "Recovering in the DR region") stops at it or rewinds to it. Item 28 built
it (D37, amended 2026-11-20); item 83 is folded in.

The code is `DrRoutes` and `ReconcileRoutes` in `catalog-server/src/main/java/nuna/catalog/server/governance/`,
`Reconcile` in `catalog-core`, the store's `recordDrWatermark` and `listDrBehind` on all four backends, and
`DrVerify`, `DrVerifier` and `DrStores` in `catalog-maintenance`.

## The watermark

- A version of a table is **safe** in DR when the version before it is safe and every file it adds is in the DR store:
  its metadata file, and for each snapshot it adds, the manifest list, the manifests that snapshot adds, the data and
  delete files they add, and the statistics and partition statistics files the version adds. Data files are checked
  too (Paul, 2026-11-20: "Every file, data too"), so a table at its watermark reads whole.
- A version that adds no snapshot (a schema or property change) is safe on its metadata file alone.
- A version that adds nothing to the newest safe version, no snapshot and no statistics file that version lacks, is
  safe on its metadata file alone too, whatever versions came between. This is how the version a rewind writes, the
  watermark's metadata again, becomes safe as soon as its file arrives: under the plain rule it would follow the
  versions it gave up, which never arrive.
- A view's version is safe when its metadata file is in the DR store.
- The watermark is kept in the catalog database, one record per object, so it reaches DR with the database (PostgreSQL
  V9, CockroachDB V8, Oracle V10, MongoDB step 8). It is written after the version it names, so a replica that keeps
  commit order never holds a watermark above its pointer. It never moves back: a report below the kept watermark, or
  above the pointer, is refused. A purge deletes it.

## The verifier

A job of the maintenance worker (D30), on a thread of its own, every `nuna.maintenance.dr.verify-interval` (1 minute
by default). It is off until a replica is named:

```yaml
nuna:
  maintenance:
    dr:
      verify-interval: PT1M
      replicas:
        <profile>:              # a storage profile of nuna.maintenance.storage, by name
          endpoint: https://s3.dr.example
          region: us-east-1
          path-style-access: false
          access-key-id: ...      # read-only keys on the DR store; the default chain when absent
          secret-access-key: ...
          ca-bundle: /etc/nuna/dr-ca.pem   # optional
```

The DR store is the same buckets and paths on another endpoint, as on-prem site replication keeps them (Paul,
2026-11-20). An AWS DR bucket has another name, which no path in the metadata names: that waits on PLT-4 at work.
The verifier only reads the DR store; it lists and reads, never writes or deletes.

Each pass:

1. Reads the work list, `GET /management/v1/maintenance/dr-work`, as the worker's maintenance principal, page by page:
   every active table and view whose watermark is below its pointer, or which has none.
2. For an object with a watermark, checks each journaled version above it, oldest first (at most 100 a pass), from
   the watermark's own metadata: each version's added files, found with one `HEAD` each.
3. Checks **whole** an object with no watermark, one whose journaled versions do not follow its watermark one by one,
   or one whose watermark's metadata file is gone from the DR store: every file every snapshot of the pointer's
   version reaches, found in one listing of its location (files outside it with one `HEAD` each). A whole check that
   fails is not repeated sooner than `nuna.maintenance.scan-interval`.
4. Reports the newest safe version, `PUT /management/v1/maintenance/tables/{id}/dr-watermark` with
   `{"version": n, "metadata-location": "..."}`, when it is above the kept watermark.

An object whose check or report fails is counted (`nuna.maintenance.dr.failures`), logged at WARN, and checked again
at the next pass. The verifier's meters and its two alerts are in worker-metrics.md ("The DR verifier", "Alerts").

## The routes

| Route | Who | What |
|---|---|---|
| `GET /management/v1/maintenance/dr-work` | a maintenance principal acting as itself | The work list, by id, `pageSize` at a time (default 100, at most 1,000). Each object: `id`, `kind`, `location`, `pointer` and, when it has one, `watermark` (each `version`, `metadata-location`), `versions` (the journaled versions above the watermark, each with `version`, `metadata-location`, `commit-id`, `committed-at`; none without a watermark) and `oldest-unverified-at`. |
| `PUT /management/v1/maintenance/tables/{id}/dr-watermark` | a maintenance principal acting as itself | The verifier's report. `204`, also when that version is already kept; `400` without a version from 1 or a location; `404` when the object is no active table or view; `409` `WatermarkBehindException` when a higher watermark is kept, `WatermarkAheadException` when the version is above the pointer, `WatermarkMismatchException` or `UnknownVersionException` when the location is not that version's. A report is the worker's bookkeeping and writes no audit record; a refusal by the guard is audited. |
| `GET /management/v1/dr/watermarks` | a platform operator | The readout: the tables and views behind, by name (D18, amended 2026-11-20), `pageSize` at a time (default 50, at most 200). Each: `id`, `kind`, `catalog`, `namespace`, `name`, `pointer-version`, `watermark-version` (null when none of its versions has passed), `watermark-recorded-at`, `versions-behind`, `oldest-unverified-at` and `lag-seconds`. |

## Lag

An object's lag is the time since its oldest commit not yet found whole in the DR store: the commit of the version
after its watermark, or, with no watermark, its first. An object at its watermark has none. The readout gives each
object's; the worker's `nuna.maintenance.dr.lag.max` gives the largest after each pass, and `NunaDrLagging` pages past
15 minutes, S3 Replication Time Control's 99.9% bound, until PLT-4 gives the replication SLA.

## The reconcile and the watermark

`POST /management/v1/catalogs/{catalog}/dr-reconcile` (D37):

- **The walk stops at the watermark** (item 83). A table with a watermark is never moved past it: its proven files
  beyond it are cut from the walk, and the answer says how many follow and from where. A table whose pointer is
  already above its watermark is not walked; its problem says to rewind. A table with no watermark walks as before.
- **The rewind**, `{"rewind": true}` (not with `accept` or `accept-archived`). Each table whose pointer is above its
  watermark, or that has none, is reported: `from`, `watermark` (null when it has none), `lost` (the journaled
  commits above the watermark, each with `version`, `metadata-location`, `commit-id`, `committed-at` and
  `principal`, for business reconciliation) and any `problem`. With `"apply": true`, each such table under a DR
  freeze moves back in one journaled commit: the watermark's metadata written again as the pointer's next version,
  published as the fast-forward's commits are (`rewound-to`). A table with no watermark, one not frozen, one whose
  watermark's file storage lacks, or one whose pointer moved since the plan is not moved, and says why
  (`apply-problem`). A table already holding the watermark's metadata has nothing to rewind.

## Not covered

- Serving DR-region readers the watermark before any failover.
- Renamed buckets (PLT-4).
- A console panel, and the demo, which has one store.
- Rewinding views: the reconcile walks and rewinds tables only.

## Tests and bites

| Test | Shows | Runs |
|---|---|---|
| `DrVerifyTest` (`catalog-maintenance`) | Two in-memory stores: the watermark stops before the first version missing any file (a data file, a manifest, a manifest list, a metadata file), delete and statistics files are checked, nothing above the watermark passes while its next version lacks a file, a commit that adds no snapshot passes on its metadata file, a rewind's version passes, a table with no watermark or a gap is checked whole in one listing, a view's version passes on its metadata file. | fast build |
| `DrVerifierTest` (`catalog-maintenance`) | A pass reads every page, reports only above the kept watermark, keeps going past a failed check, retries a failed whole check no sooner than its interval, sets its meters; worker-metrics.md lists them. | fast build |
| `DrRoutesTest` (`catalog-server`) | The routes and guard over a store double: who may call each, and a report taken only for a version the journal or the pointer holds, with its own location. | fast build |
| `ReconcileWatermarkTest` (`catalog-core`) | The walk stops at the watermark and no proof moves it past; without a watermark it is unchanged; a pointer above the watermark says to rewind; the rewind moves back to the watermark, only for a frozen table, not when there is nothing to rewind or the pointer moved. | fast build |
| `DrSuite`, in each store suite | The watermark on all four backends: it only rises, only active tables and views keep one, the objects behind are listed, a purge deletes it, the journal reads after a version. | heavy |
| `DrRewindRouteTest` | The rewind through the route: a frozen table above its watermark is rewound to it with its lost tail reported, and only a platform operator rewinds. | heavy |
| `ManagementAuthorizationTest`, `MutatingRoutesAuditTest` | Who may call the routes, and the refusals audited. | heavy |

The bites are in `bites/D37/`: data files left out of the check, a lower watermark accepted, a rewind without a
freeze, a rewind past the watermark, and the walk past the watermark.

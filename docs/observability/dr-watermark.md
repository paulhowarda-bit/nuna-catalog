# The DR replication watermark

Storage and the catalog database both replicate to the DR region asynchronously (D37), so after the primary region is
lost the DR database can name files the DR store never got. The DR watermark says, for each table and view, the newest
version the DR store holds whole; the maintenance worker's verifier moves it, operators read it, and the reconcile
step of the DR runbook (`deploy/README.md`, "Recovering in the DR region") rewinds tables and views to it. The
reconcile's walk forward checks each step's files in the DR store itself, counted as the verifier counts them, and
first, for a table whose pointer is above its watermark, the versions between them. Item 28 built it (D37, amended
2026-11-20); item 83 is folded in.

The code is `DrRoutes` and `ReconcileRoutes` in `catalog-server/src/main/java/nuna/catalog/server/governance/`,
`Reconcile` in `catalog-core`, the store's `recordDrWatermark` and `listDrBehind` on all four backends, `DrVerify`,
`DrVerifier` and `DrStores` in `catalog-maintenance`, and `AddedFiles` in `catalog-iceberg`, which counts the files a
version adds for both the verifier and the walk. It lives there because catalog-iceberg is the one module of the
catalog's own that may import iceberg-core (D2, `IcebergImportBoundaryTest`), and the worker, an Iceberg client
(D30), can use it too.

## The watermark

- A version of a table is **safe** in DR when the version before it is safe and every file it adds is in the DR store:
  its metadata file, and for each snapshot it adds, the manifest list, the manifests that snapshot adds, the data and
  delete files they add, and the statistics and partition statistics files the version adds. Data files are checked
  too (Paul, 2026-11-20: "Every file, data too"), so a table at its watermark reads whole.
- A version that adds no snapshot (a schema or property change) is safe on its metadata file alone.
- A version that adds nothing to the newest safe version, no snapshot and no statistics file that version lacks, is
  safe on its metadata file alone too, whatever versions came between. So the version a rewind writes, the
  watermark's metadata again, is safe, though it follows the versions it gave up, which never arrive; and so is a
  commit that rolls a table back and expires the snapshots in between. The reconcile's walk judges versions the same
  way.
- A view's version is safe when its metadata file is in the DR store.
- The watermark is kept in the catalog database, one record per object, so it reaches DR with the database (PostgreSQL
  V9, CockroachDB V8, Oracle V10, MongoDB step 8). The verifier's report is written after the version it names, so a
  replica that keeps commit order never holds a watermark above its pointer. A DR rewind records it in the commit that
  writes the version it names (Paul, 2026-11-20: "Rewind sets watermark"; item 100). It never moves back: a report
  below the kept watermark, or above the pointer, is refused. A purge deletes it.

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
   or one whose watermark's metadata file is gone from the DR store: the newest of the pointer's version and the
   versions the work list names below it whose every file every snapshot reaches is found in one listing of its
   location (files outside it with one `HEAD` each), newest first (item 99; Paul, 2026-11-20: "Newest whole
   version"). A version whose metadata file the listing lacks is not read; each other is read once and passed over
   without a walk of the whole table when a file it adds to the next older one is missing, or when it reaches a file
   a newer one lacks, so one walk of the whole table finds it in the cases seen. A view's: the newest whose metadata
   file is there. A table's is never its empty creation version below its pointer (Paul, 2026-11-20: "Never the empty
   version"): when the newest whole version is below the pointer and no snapshot of it reaches a data or delete file
   (no snapshot, or an empty one), the check gives none, so a table that lost an old file gets no first watermark and
   stays with its owner, as before item 99; a new table, at that version, gets it. A whole check that fails is not
   repeated sooner than `nuna.maintenance.scan-interval`.
4. Reports the newest safe version, `PUT /management/v1/maintenance/tables/{id}/dr-watermark` with
   `{"version": n, "metadata-location": "..."}`, when it is above the kept watermark.

An object whose check or report fails is counted (`nuna.maintenance.dr.failures`), logged at WARN, and checked again
at the next pass. The verifier's meters and its two alerts are in worker-metrics.md ("The DR verifier", "Alerts").

## The routes

| Route | Who | What |
|---|---|---|
| `GET /management/v1/maintenance/dr-work` | a maintenance principal acting as itself | The work list, by id, `pageSize` at a time (default 100, at most 1,000). Each object: `id`, `kind`, `location`, `pointer` and, when it has one, `watermark` (each `version`, `metadata-location`), `versions` (the journaled versions above the watermark, oldest first, at most 100, each with `version`, `metadata-location`, `commit-id`, `committed-at`; without a watermark, the newest 100 up to the pointer's, for the whole check) and `oldest-unverified-at`. |
| `PUT /management/v1/maintenance/tables/{id}/dr-watermark` | a maintenance principal acting as itself | The verifier's report. `204`, also when that version is already kept; `400` without a version from 1 or a location; `404` when the object is no active table or view; `409` `WatermarkBehindException` when a higher watermark is kept, `WatermarkAheadException` when the version is above the pointer, `WatermarkMismatchException` or `UnknownVersionException` when the location is not that version's. A report is the worker's bookkeeping and writes no audit record; a refusal by the guard is audited. |
| `GET /management/v1/dr/watermarks` | a platform operator | The readout: the tables and views behind, by name (D18, amended 2026-11-20), `pageSize` at a time (default 50, at most 200). Each: `id`, `kind`, `catalog`, `namespace`, `name`, `pointer-version`, `watermark-version` (null when none of its versions has passed), `watermark-recorded-at`, `versions-behind`, `oldest-unverified-at` and `lag-seconds`. |

## Lag

An object's lag is the time since its oldest commit not yet found whole in the DR store: the commit of the version
after its watermark, or, with no watermark, its first. An object at its watermark has none. The readout gives each
object's; the worker's `nuna.maintenance.dr.lag.max` gives the largest after each pass, and `NunaDrLagging` pages past
15 minutes, S3 Replication Time Control's 99.9% bound, until PLT-4 gives the replication SLA.

## The reconcile and the watermark

`POST /management/v1/catalogs/{catalog}/dr-reconcile` (D37):

- **The walk checks each step's files** (item 83; Paul, 2026-11-20: "Walk with a file check"). Every table is walked
  forward from the DR database's pointer, with a watermark or without, as before through proven files, archived swaps
  and accepted tips. A step is taken only when the store the catalog servers run on, the DR store in DR, holds every
  file it adds to the version before it (the pointer's, for the first), counted as the verifier counts them: the
  manifest list and the manifests of each snapshot it adds, the data and delete files those add, and its statistics
  files. The walk stops before the first step that lacks one and names the file in its `problem`; a file that cannot
  be read stops it the same way. It asks one `HEAD` for each file a step adds and reads each manifest list and
  manifest it adds. The walk never moves a table back.
- **The walk checks from the watermark** (Paul, 2026-11-20: "Check from the watermark", then "Judge like the
  verifier"). A table whose pointer is above its watermark is checked from the watermark before it is walked: each
  version the DR database's journal records above the watermark, up to the pointer's, is judged as the verifier judges
  it (The watermark, above), its own metadata file among its files, counted as for a step. The table is walked only
  when the pointer's version is whole that way. So a pointer that adds nothing to the newest whole version, as the
  version a rewind writes adds nothing to the watermark, is whole though versions between them are not, while a
  version built on one that is not whole never is. Otherwise the walk takes nothing for the table, no step and no tip,
  and its `problem` names the first file missing since the newest whole version and says to rewind the table to its
  watermark. The versions come from the journal, as the verifier's work list and the rewind's `lost` tail do: the
  journal and the pointer are written in one transaction, so the DR database holds every version up to its pointer.
  When the journal does not hold them one by one up to the pointer, or the store lacks the watermark's own metadata
  file, they cannot be checked, and the walk takes nothing and says so. A file that cannot be read stops it the same
  way. A table at its watermark, or with none, is walked from its pointer as before; one with no watermark is its
  owner's (the runbook).
- **The rewind**, `{"rewind": true}` (not with `accept` or `accept-archived`). Each table, then each view (Paul,
  2026-11-20: "Rewind views too"), whose pointer is above its watermark, or that has none, is reported, tables under
  `tables` and views under `views` (with `tables-checked` and `views-checked`): `from`, `watermark` (null when it has
  none), `lost` (the journaled commits above the watermark, each with `version`, `metadata-location`, `commit-id`,
  `committed-at` and `principal`, for business reconciliation) and any `problem`. With `"apply": true`, each such table
  and view under a DR freeze moves back in one journaled commit: the watermark's metadata written again as the
  pointer's next version, published as the fast-forward's commits are (`rewound-to`). The same commit records that
  version as the watermark (Paul, 2026-11-20: "Rewind sets watermark"; item 100): it is the watermark's own metadata,
  whole by construction, so the object is no longer behind once the rewind answers, however many commits it gave up,
  and needs no verifier pass. One with no watermark, one not frozen, one whose watermark's file storage lacks, or one
  whose pointer moved since the plan is not moved, and says why (`apply-problem`); one with no watermark is left to
  its owner (Paul, 2026-11-20: "Owner decides").
- **Rewind before walking** is the runbook's order, though no longer the only guard: whatever the order, a table is
  not walked from a pointer above its watermark unless the pointer's version is whole, judged from the watermark.
  Until the verifier's next pass, the versions a walk takes are above the watermark, and a rewind then would give them
  up again. A table just rewound is at its watermark and is walked from there at once.

## Not covered

- Serving DR-region readers the watermark before any failover.
- Renamed buckets (PLT-4).
- A console panel, and the demo, which has one store.
- Walking views forward: the walk moves tables only; views are rewound, never walked.

## Tests and bites

| Test | Shows | Runs |
|---|---|---|
| `DrVerifyTest` (`catalog-maintenance`) | Two in-memory stores: the watermark stops before the first version missing any file (a data file, a manifest, a manifest list, a metadata file), delete and statistics files are checked, nothing above the watermark passes while its next version lacks a file, a commit that adds no snapshot passes on its metadata file, a rewind's version passes, a table with no watermark or a gap is checked whole in one listing, a view's version passes on its metadata file. A busy table with no watermark gets the newest version whole in one listing, a file lost long ago costs one walk, a view with no watermark gets its newest version there. A table that lost an old file gets no first watermark when only its empty creation version is whole, nor when that version has an empty snapshot, and gets an older version with data that is whole; a new table gets its creation version. | fast build |
| `DrVerifierTest` (`catalog-maintenance`) | A pass reads every page, reports only above the kept watermark, keeps going past a failed check, retries a failed whole check no sooner than its interval, sets its meters; worker-metrics.md lists them. A first watermark below the pointer lags from the version after it. | fast build |
| `DrPassesTest` (`catalog-maintenance`) | Each pass's tally logged at INFO, one line a pass (item 101). | fast build |
| `DrRoutesTest` (`catalog-server`) | The routes and guard over a store double: who may call each, and a report taken only for a version the journal or the pointer holds, with its own location. An object with no watermark lists its newest versions. | fast build |
| `ReconcileFileCheckTest` (`catalog-core`) | An Iceberg client's table in memory and a DR store holding what the test replicates: the walk stops before the first version missing a data file, a manifest it adds or its manifest list, a delete file or a statistics file, and names it; only the checked steps are swapped; a manifest that cannot be read stops the walk. From the watermark: a pointer above it walks on when every version between them is whole, and takes nothing, reported for the rewind, when one lacks a file it adds (even one a later version deletes), its manifest list, its metadata file or the pointer's; a pointer at its watermark, or a table with none, walks from its pointer as before; versions the journal does not hold one by one, or a watermark file the store lacks, take nothing. Judged as the verifier judges them: a rewound table walks at once, with its watermark at the rewind's version or still below it, while the versions a rewind gives up, and a version built on them, never count. | fast build |
| `ReconcileWatermarkTest` (`catalog-core`) | A table at its watermark walks forward past it, and a watermark below the pointer whose versions since are whole, or none, leaves the walk as it is; the rewind moves back to the watermark and records its version as the watermark in the same commit, also after giving up more than 100 versions, only for a frozen table, not when there is nothing to rewind or the pointer moved; a view is rewound as a table is. | fast build |
| `DrSuite`, in each store suite | The watermark on all four backends: it only rises, only active tables and views keep one, a rewind's commit records it at the version it writes (also past more than 100 versions) and a conflicting one records nothing, the objects behind are listed, a purge deletes it, the journal reads after a version. | heavy |
| `DrRewindRouteTest` | The rewind through the route: a frozen table, and a frozen view, above its watermark is rewound to it with its lost tail reported, the table then at its watermark, out of the work list and walked at once, also after giving up more than 100 versions; only a platform operator rewinds. | heavy |
| `MetadataFilesTest#anyFileIsFoundAndRead` (`catalog-objectstore`) | The object store's `HEAD` and whole read of any file of a table, which the walk's check uses. | heavy |
| `ManagementAuthorizationTest`, `MutatingRoutesAuditTest` | Who may call the routes, and the refusals audited. | heavy |

The bites are in `bites/D37/`: data files left out of the check, a lower watermark accepted, a rewind without a
freeze, a rewind past the watermark, the walk past a version whose files are not all in the DR store
(`walk-past-the-watermark`, recorded again for the file check), a view not rewound, the walk's check starting at
the pointer again rather than at the watermark (`check-from-the-pointer`), the walk counting a version built on
given-up ones as whole (`walk-counts-given-up-versions`), the rewind leaving the watermark where it was
(`rewind-leaves-the-watermark`), the whole check testing only the pointer again (`whole-at-the-pointer-only`), and
the empty creation version taken as a first watermark again (`empty-creation-version-accepted`).

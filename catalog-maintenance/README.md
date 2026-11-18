# catalog-maintenance: the maintenance worker (D30)

A separate service that keeps the catalog's tables tidy. It is an Iceberg Java client of the catalog over
REST, like any engine. It signs in as a maintenance principal of its own and deletes files with a storage
identity of its own, the only one the stores' delete policy lets delete (D21). The catalog decides what is due
(the maintenance policy, holds, freezes, windows); the worker does it and commits through the catalog's normal
path. Its decisions are in the ledger's D30 and its amendments.

## What a scan does

Every `scan-interval` (10 minutes by default) the worker reads the catalog's work list,
`GET /management/v1/maintenance/work`, page by page, and works through its three sections. Snapshot expiry,
manifest rewrite, purges and due deletions run at every scan, round the clock. Compaction and orphan scans, the
heavy jobs, start only inside the nightly cleanup window (below).

- **Tables** whose resolved policy runs a job, with that policy. For each one:
  - **Snapshot expiry** (`snapshot-expiry.enabled`, `max-snapshot-age`, `min-snapshots-to-keep`): Iceberg's
    ExpireSnapshots, committed through the catalog. Branches and tags keep their snapshots, as Iceberg decides.
    Every file it leaves unreferenced goes to the catalog's pending-deletion list; the worker never deletes it
    at this point. Iceberg's cleanup is conservative: a file that a snapshot still kept had deleted is left in
    place, and the orphan scan queues it later.
  - **Manifest rewrite** (`manifest-rewrite.enabled`, `min-manifests`): Iceberg's RewriteManifests merges the
    current snapshot's small data manifests, committed through the catalog. The old manifests stay until expiry
    takes the snapshots that use them.
  - **Compaction** (`compaction.enabled`), in the window, when a compaction image is set: the table is assessed
    once a night from its current manifests, and gets a Job only if it needs one ("Compaction on need", below).
  - **Orphan scan** (`orphan-removal.enabled`, `older-than`), in the window, at most once a night and once per
    `orphan-interval`, counted in whole nights. The worker lists the table's location with its own storage
    identity. It then builds the set of files that any metadata reaches: the metadata log, and every manifest
    list, manifest, data file, delete file, statistics file and partition statistics file of every snapshot,
    which covers every branch and tag. It reads the metadata again after the listing, so a file committed
    meanwhile is reachable. It queues the files that are unreachable and older than `older-than`; the catalog
    requires that age to be longer than its PITR window. The scans run one table after another once the night's
    Jobs have started; each stops where it is if the window closes, records nothing, and its table is scanned
    again the next night.
- **Purges**: dropped objects past the drop window. The worker claims each one (`PURGING`), deletes everything
  beneath its location in batches of 1,000, and reports it `PURGED`. An object another worker has claimed is
  skipped. A worker that dies part-way resumes from its own claim at the next scan: the claim belongs to the
  principal, not the process.
- **Deletions**: pending deletions that are due, past the PITR window (7 days by default), of objects that no
  hold or freeze covers. The worker deletes them with its own storage identity and reports them done.

Before any job on a table the worker loads it and checks that its id is still the one the work list named. A
table dropped, renamed or unregistered since, whose name another table now has, is left alone and reported; the
next scan sees the new table under its own policy.

A job that fails is logged, counted in the scan's report, and tried again at the next scan; the other jobs go
on. Every job can be run again safely. Recording a file that is already pending changes nothing: the catalog
keeps each file once per object, with the time it was first recorded.

The worker also reports its own work to the catalog, which keeps it for the console's health pages (D38,
`docs/observability/table-health.md`): each scan as it starts and ends (`PUT /management/v1/maintenance/scans/{id}`),
and each table's assessment and compaction Job as it makes or starts and ends them
(`PUT /management/v1/maintenance/tables/{id}/assessment`, `.../compaction`). A report the catalog does not take is
logged and counted as a `report` failure, and the work goes on; one for a table dropped or replaced since the work list
was read is refused, and left at that.

The worker relies on the catalog's guards rather than repeating them. The work list leaves out held tables'
expiry and orphan scans, frozen namespaces, unregistered tables and everything that is not active. The
catalog refuses to remove a snapshot younger than its minimum age, refuses a purge claim under a hold or a
freeze, and lists a deletion as due only after the PITR window.

## The cleanup window

Compaction and orphan scans start only in the nightly cleanup window (D30, amended 2026-11-15), set per
deployment as `cleanup-window.start`, `end` and `zone`. It has no default: only the deployment knows its night.

- The times are local to `zone`, a named zone such as `Europe/London`, so the window follows its daylight-saving
  changes. An end before the start crosses midnight: `22:30` to `04:00` opens on one date and closes on the next.
  An end equal to the start keeps the window open all day, each night ending as the next begins.
- On the night the clocks go forward the window is shorter; a start or end the clocks skip falls as late as the
  skip. On the night they go back it is longer; a time the clocks repeat is taken the first time round, so the
  window never opens or closes twice in one night.
- Outside the window nothing heavy starts, after a restart too: a worker started in the day starts no compaction
  and no orphan scan until the window opens.
- At the window's end nothing new starts. Each compaction Job's Kubernetes deadline (`activeDeadlineSeconds`) is
  the window's end, and it runs `rewrite_data_files` with partial progress, so the file groups it finished stay
  committed when Kubernetes stops it. An orphan scan still running stops and is not recorded. The first scan after
  the window closes ends the night: it counts the Jobs cut off and the needy tables that got none.

In YAML, quote the times. Spring reads its YAML as YAML 1.1, where an unquoted `22:30` is the number 1350 in base
60, and the worker then refuses to start; `"22:30"` is a time. The chart quotes them for you.

## Compaction on need

In the window the worker reads each enabled table's current manifests once a night and launches a Job only for a
table that needs one, for any of three reasons:

- **small files** (`small-files`): one partition holds at least `compaction.min-input-files` data files under 75%
  of `compaction.target-file-size-bytes`, rewrite_data_files' own bound for a small file. Spark rewrites a
  partition's small files in groups of at least that many, so that is what a Job would rewrite.
- **deletes** (`deletes`): its delete files, position deletes, deletion vectors and equality deletes alike, are
  more than `compaction.delete-file-ratio` of its data files in number.
- **equality deletes** (`equality-deletes`): it has any equality-delete file, which every reader joins against.

The thresholds are maintenance policy keys, set and resolved like the others:

| Policy key | Default | Meaning |
|---|---|---|
| `compaction.target-file-size-bytes` | `536870912` | The size a Job writes; a data file under 75% of it is small. |
| `compaction.min-input-files` | `5` | Small files one partition must hold before the table needs compaction for them; rewrite_data_files' `min-input-files`. |
| `compaction.delete-file-ratio` | `0.3` | The share of its data files its delete files must pass; rewrite_data_files' `delete-ratio-threshold`. A plain decimal above 0. |

The needy tables join the night's queue. Jobs start from its head while fewer than `compaction.max-jobs` run:
tables with equality deletes first, then the furthest past their thresholds (the larger of small files over
`min-input-files` and the delete files' share over `delete-file-ratio`). Each scan in the window starts Jobs as
earlier ones finish, also between one orphan scan and the next.

A Job is the Kubernetes Job `nuna-compact-<table uuid>-<night's date>` in the Jobs' own namespace, so a table gets
one a night at most, across restarts of the worker too. It runs the repo's Spark image
(`catalog-tests-engines/images/spark`) with `rewrite_data_files`, signed in as the worker, with the target size,
`min-input-files` and partial progress; for deletes it also rewrites every data file with a delete
(`delete-file-threshold` 1) and removes the delete files left dangling. The Job is told the table's id as well
as its name, and stops (exit 3, a `TABLE CHANGED` line) unless the table it loads has that id: the pod may start
long after the scan, when another table may have taken the name. The files it replaces are left for expiry. A
finished Job is kept a day for its log; how long it is kept schedules nothing.

The worker's meters count the tables assessed, the needs by reason, the Jobs started and cut off and the tables
left over, and give each needy table a series of its own (`docs/observability/worker-metrics.md`). The catalog keeps
each table's latest assessment and how its last Job stands or ended, as the worker reports them, and lists the
neediest tables in the worker's order (`docs/observability/table-health.md`).

## Running it

```
./gradlew :catalog-maintenance:bootJar
java -jar catalog-maintenance/build/libs/catalog-maintenance-<version>-exec.jar \
    --spring.config.additional-location=file:worker.yaml
```

The image is built from that jar (see `deploy/image/maintenance.Dockerfile`), and the Helm chart
`deploy/helm/nuna-maintenance` runs it. The worker has no HTTP port. It logs a line per scan and a warning
per failed job, and records as meters what each scan did, how long it took and when one last succeeded, which
go out over OTLP with its other telemetry: `docs/observability/worker-metrics.md` lists them and the alerts each
supports. Each scan is also a trace, a span for each part of it, which its calls to the catalog carry to the catalog
as `traceparent`, so the catalog's spans for them join it (`docs/observability/worker-spans.md`).

### Settings (`nuna.maintenance.*`)

| Setting | Default | Meaning |
|---|---|---|
| `catalog-uri` | required | The catalog as the worker reaches it, e.g. `http://nuna-catalog:8181/`. |
| `scan-interval` | `PT10M` | How often the worker reads the work list. |
| `cleanup-window.start`, `end` | required | When the nightly cleanup window opens and closes, local times such as `"01:00"` and `"05:00"`, quoted in YAML. |
| `cleanup-window.zone` | required | The window's time zone by name, e.g. `Europe/London`. |
| `orphan-interval` | `P1D` | The least time between two orphan scans of one table (each lists the whole location), rounded up to whole nights. |
| `page-size` | `100` | Work list items per page. |
| `token.file` | | A file holding the worker's bearer token, read again whenever it changes. Set this or `token.endpoint`, not both. Not with compaction: a Job could outlive the token. |
| `token.endpoint`, `client-id`, `client-secret`, `scope`, `audience` | | The OAuth 2 client-credentials grant against the identity provider. The token is kept until a minute before it expires. Compaction needs this grant and a `scope`: the Job signs in with the same grant itself, and Iceberg's client asks for scope `catalog` when none is given. |
| `storage.<profile>.prefixes` | required | The locations this storage identity covers, e.g. `s3://lake/tables`. Every table location in the work list must lie under one profile's prefix. |
| `storage.<profile>.endpoint` | none (AWS S3) | The S3 endpoint for MinIO or Ceph. |
| `storage.<profile>.region` | `us-east-1` | The region. |
| `storage.<profile>.path-style-access` | `false` | Address buckets by path (MinIO, Ceph). |
| `storage.<profile>.access-key-id`, `secret-access-key` | none | The worker's keys. With neither, the AWS SDK's default chain is used (IRSA, Pod Identity). |
| `compaction.image` | none | The Spark image. Compaction is off without one. |
| `compaction.image-pull-policy` | `IfNotPresent` | Its pull policy. |
| `compaction.namespace` | required with an image | Where Jobs run: a namespace for them alone, never the worker's (refused at startup) nor the catalog's. A pod may run as any service account and mount any Secret of its namespace. |
| `compaction.job-service-account` | the namespace's `default` | The service account the Jobs' pods run as: one with no rights and no token mounted (the chart makes `nuna-compaction`). |
| `compaction.api-server` | `https://kubernetes.default.svc` | The Kubernetes API server. |
| `compaction.service-account` | `/var/run/secrets/kubernetes.io/serviceaccount` | The folder holding the service account's token, CA and namespace. |
| `compaction.catalog-uri` | `catalog-uri` | The catalog as the Job's pods reach it. |
| `compaction.memory`, `driver-memory` | `2Gi`, `1g` | The Job container's memory limit, and Spark's driver memory within it. |
| `compaction.max-jobs` | `2` | How many Jobs run at once; the neediest tables go first. Each runs to the window's end at the latest. |
| `compaction.run-as-user` | `185` | The Spark image's user id. |
| `compaction.spark-conf` | none | More Spark settings for every Job. |

An example, for one Ceph store:

```yaml
nuna:
  maintenance:
    catalog-uri: http://nuna-catalog:8181/
    cleanup-window:
      start: "22:30"
      end: "04:00"
      zone: Europe/London
    token:
      endpoint: https://idp.example.internal/oauth2/token
      client-id: nuna-maintenance
      client-secret: ${NUNA_MAINTENANCE_CLIENT_SECRET}
      scope: nuna-catalog
      audience: nuna-catalog
    storage:
      ceph:
        prefixes: [s3://lake/tables]
        endpoint: https://ceph.example.internal
        path-style-access: true
        access-key-id: ${NUNA_MAINTENANCE_ACCESS_KEY}
        secret-access-key: ${NUNA_MAINTENANCE_SECRET_KEY}
```

## The identity it needs

- **In the catalog.** The worker's principal id must be listed in the catalog's
  `nuna.security.maintenance-principals`. Its group needs `write` on every catalog it maintains. It always acts
  as itself, never for an end user. That gives it the work list, the purge claim and completion, the
  pending-deletion routes, the reports of its own work, and storage access to tables flagged for row/column policy, with every such access
  audited (D20). The credentials the catalog vends it read and write table files but never delete (D21).
- **In storage, per store.** The worker needs `s3:ListBucket` on each bucket, beneath its prefixes, and
  `s3:DeleteObject` beneath them. It reads table files through vended credentials, not this identity.
  - The catalog's delete policies (`GET /management/v1/maintenance/delete-policies`, see `deploy/README.md`)
    take delete from every other principal and give the worker both rights: applied as they are, they are
    all it needs, on every store. The listing is limited to the worker's prefixes (`s3:prefix`). The tests
    apply the Ceph policy as it comes (`TestCatalog.protectDeletes`).
  - On AWS S3 and Ceph the bucket policy allows them to the principal it names. On AWS that holds for a role
    in the bucket's own account; a role in another account also needs them in its own IAM policy. Give the
    chart's service account the role through `serviceAccount.annotations` (IRSA).
  - On MinIO the second user policy, the one to attach to the worker, allows them.
- **In Kubernetes, for compaction only.** The Jobs run in a namespace of their own, created beforehand: never
  the worker's or the catalog's, since a pod there could run as their service accounts or mount their Secrets.
  In it the worker needs `get` and `create` on Jobs (it reads a Job's conditions to see it finish, or stop at
  its deadline) and `create` on Secrets, nothing else. Its pods run as
  `job-service-account`, which has no rights and no token mounted. The chart makes that account, the Role and
  its RoleBinding there when `compaction.image` is set, and refuses to render without
  `compaction.namespace` or with the release's own. The worker's catalog sign-in, the client id and secret
  with the scope, goes into a Secret of a fresh name that the Job owns, created after the Job, so Kubernetes
  deletes it with the Job; the Job renews its own token, so it never runs out.

## Image and chart

`deploy/image/maintenance.Dockerfile` packs the runnable jar the way the catalog's image does. It runs as user
10001 and writes only to `/tmp`.

```
docker build -f deploy/image/maintenance.Dockerfile -t nuna-maintenance:<version> catalog-maintenance/build/libs
```

`deploy/helm/nuna-maintenance` installs one release per catalog deployment, beside the `nuna-catalog` release:

- one replica with the `Recreate` strategy, so two workers never overlap during an upgrade;
- its own service account;
- the settings under `config`, rendered into a ConfigMap; it refuses to render without the cleanup window,
  `config.nuna.maintenance.cleanup-window.start`, `end` and `zone` (for instance
  `--set config.nuna.maintenance.cleanup-window.start=22:30`), and quotes the times in the worker's YAML;
- secrets under `secrets` (or `secrets.existingSecret`), read as a Spring Boot config tree, each key a
  property name such as `nuna.maintenance.token.client-secret`;
- with `config.nuna.maintenance.compaction.image` set, the Role, RoleBinding and Job service account in the
  Jobs' namespace (`config.nuna.maintenance.compaction.namespace`, which must exist).

See `values.yaml` for the rest.

## Tests

`./gradlew :catalog-maintenance:test` runs the fast tests against a catalog server in the same JVM, on
PostgreSQL and the run's S3 store (`-Ps3`, Silo by default). The worker runs on a fake clock of its own, and
the server's clock is moved with it. Unless a test gives it a window of its own, its cleanup window is open all
day (`TestCatalog.allDay`).

| Tests | Tag | What they show |
|---|---|---|
| `CleanupWindowTest` | | The window without a catalog: its edges, across midnight, the nights the clocks go forward and back, all day. |
| `CompactionNeedTest` | | The need from a table's manifests, on an in-memory catalog: small files per partition, delete files past their share (position deletes and deletion vectors), any equality delete, the policy's thresholds; the order; the Job's options. |
| `NightlyCleanupTest` | | The worker across a window that closes early as the clocks go forward: nothing heavy outside it or after a restart in the day; one Job a table a night; equality deletes first, then the neediest; each Job's deadline at the window's end; the Job cut off and the table left over; an orphan scan cut short scanned again the next night; the catalog keeps each assessment, Job and scan as the worker reports them. |
| `SnapshotExpiryTest` | | Expiry queues only unreachable files; branch and tag files survive expiry and the orphan scan. |
| `OrphanScanTest` | | Young orphans are kept; old ones are queued; a sibling location (`t` and `t-twin`) is never touched. |
| `GuardrailsTest` | | Held, frozen and unregistered tables are untouched until the hold and freeze go; a table no longer the one the work list named is neither assessed nor compacted. |
| `PurgeTest` | | A purge removes the prefix after its claim; another worker's claim is left alone; a claimed purge resumes. |
| `PendingDeletionTest` | | Pending deletions wait for their time, then are deleted and reported done. |
| `WorkerRunTest` | | One table's whole maintenance over 16 days of the worker's clock, in a window of four hours a day: compaction and orphan scans once a night in the window and never outside it, deletions 7 days after they were recorded. |
| `KubernetesJobsTest`, `TokenSourceTest` | | The Job manifest: one a table a night, its deadline the window's end, partial progress, the Job before its Secret, the Jobs' own namespace; a Job's state from its conditions; the token sources, and the settings compaction refuses. |
| `WorkerMetricsTest`, `ScanMetricsTest` | | The worker's meters: each count, need and failure kind, the scan's time by outcome, the last success moved only by a scan that read the whole work list, bounded labels but for the needy tables' gauge; real scans counted as their reports say, and kept by the catalog as the worker reports them. |
| `OrphanRaceTest` | `heavy` | A writer committing during orphan scans loses nothing. |
| `KafkaDownTest` | `heavy` | With Kafka down the scan changes nothing; the next one catches up. |
| `KillMidPurgeTest` | `heavy` | A worker killed mid-purge is resumed by the next one. |
| `KubernetesCompactionTest` | `heavy` | Compaction as a Job in Docker Desktop's cluster, the worker and its Jobs each in a namespace of their own that it deletes; the Job's deadline no later than the window's end; the next night the compacted table needs none; a Job naming the table by an id it no longer has stops without compacting; a Job whose window closes at once is stopped by Kubernetes and read as cut off. Needs `kubectl`, `helm` and the `docker-desktop` context. |
| `LargeExpiryTest` | `overnight` | One scan expires 9,999 of 10,000 snapshots. |
| `LargeOrphanScanTest` | `overnight` | The orphan scan's heap per reachable file, at 250,000 to 1,000,000 files. |
| `EngineMatrixTest#compaction` (in `catalog-tests-engines`) | `overnight` | The worker's own Job in the engine matrix's cluster: 32 small files compacted into 4, the same rows, the replaced files left in place, no pending deletion. |

## Limits

- The night's state is kept in memory: which tables were assessed and scanned for orphans, the queue, the Jobs
  running. A worker restarted in the window assesses every table again and scans each for orphans again, one
  table after another; tonight's Job, already there, keeps a table from a second. A worker restarted after the
  window closed, before a scan ended the night, does not count that night's cut-off Jobs and left-over tables,
  and the catalog shows those Jobs running until the table's next Job. The catalog keeps each assessment and Job the worker reports (item 47, D38), but only for the console: the worker
  does not read them back.
- Assessing a table reads its current manifests, every one, once a night. A table of very many manifests takes
  that long to assess; manifest rewrite keeps their number down.
- The orphan scan holds every file a table reaches in memory. Size the worker for the largest table;
  `LargeOrphanScanTest` prints the cost per file and how many files a 2 GiB worker can scan.
- A worker that crashes after an expiry commit and before recording its files leaves them unrecorded. The
  orphan scan finds them once they are older than `older-than`.
- A file outside its table's location (for instance under a `write.data.path` elsewhere), or with an empty,
  `.` or `..` segment after it, is never queued. The catalog refuses such a location, and the file is not the
  worker's to delete. The scan report counts it.

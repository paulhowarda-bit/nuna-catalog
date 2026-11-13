# catalog-maintenance: the maintenance worker (D30)

A separate service that keeps the catalog's tables tidy. It is an Iceberg Java client of the catalog over
REST, like any engine. It signs in as a maintenance principal of its own and deletes files with a storage
identity of its own, the only one the stores' delete policy lets delete (D21). The catalog decides what is due
(the maintenance policy, holds, freezes, windows); the worker does it and commits through the catalog's normal
path. Its decisions are in the ledger's D30 and its amendments.

## What a scan does

Every `scan-interval` (10 minutes by default) the worker reads the catalog's work list,
`GET /management/v1/maintenance/work`, page by page, and works through its three sections.

- **Tables** whose resolved policy runs a job, with that policy. For each one:
  - **Snapshot expiry** (`snapshot-expiry.enabled`, `max-snapshot-age`, `min-snapshots-to-keep`): Iceberg's
    ExpireSnapshots, committed through the catalog. Branches and tags keep their snapshots, as Iceberg decides.
    Every file it leaves unreferenced goes to the catalog's pending-deletion list; the worker never deletes it
    at this point. Iceberg's cleanup is conservative: a file that a snapshot still kept had deleted is left in
    place, and the orphan scan queues it later.
  - **Manifest rewrite** (`manifest-rewrite.enabled`, `min-manifests`): Iceberg's RewriteManifests merges the
    current snapshot's small data manifests, committed through the catalog. The old manifests stay until expiry
    takes the snapshots that use them.
  - **Orphan scan** (`orphan-removal.enabled`, `older-than`), at most once per `orphan-interval` per table. The
    worker lists the table's location with its own storage identity. It then builds the set of files that any
    metadata reaches: the metadata log, and every manifest list, manifest, data file, delete file, statistics
    file and partition statistics file of every snapshot, which covers every branch and tag. It reads the
    metadata again after the listing, so a file committed meanwhile is reachable. It queues the files that are
    unreachable and older than `older-than`; the catalog requires that age to be longer than its PITR window.
  - **Compaction** (`compaction.enabled`, `target-file-size-bytes`), at most once per `compaction-interval` per
    table, and only when a compaction image is set. The worker creates the Kubernetes Job
    `keel-compact-<table uuid>`, which runs the repo's Spark image (`catalog-tests-engines/images/spark`) with
    `rewrite_data_files`, signed in as the worker. The files it replaces are left for expiry.
- **Purges**: dropped objects past the drop window. The worker claims each one (`PURGING`), deletes everything
  beneath its location in batches of 1,000, and reports it `PURGED`. An object another worker has claimed is
  skipped. A worker that dies part-way resumes from its own claim at the next scan: the claim belongs to the
  principal, not the process.
- **Deletions**: pending deletions that are due, past the PITR window (7 days by default), of objects that no
  hold or freeze covers. The worker deletes them with its own storage identity and reports them done.

A job that fails is logged, counted in the scan's report, and tried again at the next scan; the other jobs go
on. Every job can be run again safely. Recording a file that is already pending changes nothing: the catalog
keeps each file once per object, with the time it was first recorded.

The worker relies on the catalog's guards rather than repeating them. The work list leaves out held tables'
expiry and orphan scans, frozen namespaces, unregistered tables and everything that is not active. The
catalog refuses to remove a snapshot younger than its minimum age, refuses a purge claim under a hold or a
freeze, and lists a deletion as due only after the PITR window.

## Running it

```
./gradlew :catalog-maintenance:bootJar
java -jar catalog-maintenance/build/libs/catalog-maintenance-<version>-exec.jar \
    --spring.config.additional-location=file:worker.yaml
```

The image is built from that jar (see `deploy/image/maintenance.Dockerfile`), and the Helm chart
`deploy/helm/keel-maintenance` runs it. The worker has no HTTP port. It logs a line per scan and a warning
per failed job.

### Settings (`keel.maintenance.*`)

| Setting | Default | Meaning |
|---|---|---|
| `catalog-uri` | required | The catalog as the worker reaches it, e.g. `http://keel-catalog:8181/`. |
| `scan-interval` | `PT10M` | How often the worker reads the work list. |
| `orphan-interval` | `P1D` | The least time between two orphan scans of one table (each lists the whole location). |
| `compaction-interval` | `P1D` | The least time between two compaction Jobs for one table. A finished Job is kept this long. |
| `page-size` | `100` | Work list items per page. |
| `token.file` | | A file holding the worker's bearer token, read again whenever it changes. Set this or `token.endpoint`, not both. |
| `token.endpoint`, `client-id`, `client-secret`, `scope`, `audience` | | The OAuth 2 client-credentials grant against the identity provider. The token is kept until a minute before it expires. |
| `storage.<profile>.prefixes` | required | The locations this storage identity covers, e.g. `s3://lake/tables`. Every table location in the work list must lie under one profile's prefix. |
| `storage.<profile>.endpoint` | none (AWS S3) | The S3 endpoint for MinIO or Ceph. |
| `storage.<profile>.region` | `us-east-1` | The region. |
| `storage.<profile>.path-style-access` | `false` | Address buckets by path (MinIO, Ceph). |
| `storage.<profile>.access-key-id`, `secret-access-key` | none | The worker's keys. With neither, the AWS SDK's default chain is used (IRSA, Pod Identity). |
| `compaction.image` | none | The Spark image. Compaction is off without one. |
| `compaction.image-pull-policy` | `IfNotPresent` | Its pull policy. |
| `compaction.namespace` | the worker's own | Where Jobs run. |
| `compaction.api-server` | `https://kubernetes.default.svc` | The Kubernetes API server. |
| `compaction.service-account` | `/var/run/secrets/kubernetes.io/serviceaccount` | The folder holding the service account's token, CA and namespace. |
| `compaction.catalog-uri` | `catalog-uri` | The catalog as the Job's pods reach it. |
| `compaction.memory`, `driver-memory` | `2Gi`, `1g` | The Job container's memory limit, and Spark's driver memory within it. |
| `compaction.deadline` | `PT2H` | How long a Job may run before Kubernetes stops it. |
| `compaction.run-as-user` | `185` | The Spark image's user id. |
| `compaction.spark-conf` | none | More Spark settings for every Job. |

An example, for one Ceph store:

```yaml
keel:
  maintenance:
    catalog-uri: http://keel-catalog:8181/
    token:
      endpoint: https://idp.example.internal/oauth2/token
      client-id: keel-maintenance
      client-secret: ${KEEL_MAINTENANCE_CLIENT_SECRET}
      audience: keel-catalog
    storage:
      ceph:
        prefixes: [s3://lake/tables]
        endpoint: https://ceph.example.internal
        path-style-access: true
        access-key-id: ${KEEL_MAINTENANCE_ACCESS_KEY}
        secret-access-key: ${KEEL_MAINTENANCE_SECRET_KEY}
```

## The identity it needs

- **In the catalog.** The worker's principal id must be listed in the catalog's
  `keel.security.maintenance-principals`. Its group needs `write` on every catalog it maintains. It always acts
  as itself, never for an end user. That gives it the work list, the purge claim and completion, the
  pending-deletion routes, and storage access to tables flagged for row/column policy, with every such access
  audited (D20). The credentials the catalog vends it read and write table files but never delete (D21).
- **In storage, per store.** The worker needs `s3:ListBucket` on each bucket, beneath its prefixes, and
  `s3:DeleteObject` beneath them. It reads table files through vended credentials, not this identity.
  - The catalog's delete policy (`GET /management/v1/maintenance/delete-policies`, see `deploy/README.md`)
    takes delete from every other principal.
  - On Ceph that policy also lets the worker delete, but it does not let the worker list. Add a statement that
    allows the worker's user `s3:ListBucket` on the bucket, as the tests do (`TestCatalog.protectDeletes`).
  - On AWS, the role the delete policy names grants both rights in its own IAM policy. Give the chart's service
    account the role through `serviceAccount.annotations` (IRSA).
  - On MinIO, the worker's user needs a user policy that allows both.
- **In Kubernetes, for compaction only.** In the Jobs' namespace the worker needs `get` and `create` on Jobs,
  and `create` and `update` on Secrets. The chart's Role grants this when `compaction.image` is set. The
  worker's catalog sign-in goes into a Secret that the Job owns, so Kubernetes deletes it with the Job. With
  the client-credentials grant the Job gets the client id and secret, so its token never runs out. With
  `token.file` it gets the token as it is at launch, so a Job must finish before that token expires.

## Image and chart

`deploy/image/maintenance.Dockerfile` packs the runnable jar the way the catalog's image does. It runs as user
10001 and writes only to `/tmp`.

```
docker build -f deploy/image/maintenance.Dockerfile -t keel-maintenance:<version> catalog-maintenance/build/libs
```

`deploy/helm/keel-maintenance` installs one release per catalog deployment, beside the `keel-catalog` release:

- one replica with the `Recreate` strategy, so two workers never overlap during an upgrade;
- its own service account;
- the settings under `config`, rendered into a ConfigMap;
- secrets under `secrets` (or `secrets.existingSecret`), read as a Spring Boot config tree, each key a
  property name such as `keel.maintenance.token.client-secret`;
- with `config.keel.maintenance.compaction.image` set, the Role and RoleBinding for Jobs.

See `values.yaml` for the rest.

## Tests

`./gradlew :catalog-maintenance:test` runs the fast tests against a catalog server in the same JVM, on
PostgreSQL and the run's S3 store (`-Ps3`, Silo by default). The worker runs on a fake clock of its own, and
the server's clock is moved with it.

| Tests | Tag | What they show |
|---|---|---|
| `SnapshotExpiryTest` | | Expiry queues only unreachable files; branch and tag files survive expiry and the orphan scan. |
| `OrphanScanTest` | | Young orphans are kept; old ones are queued; a sibling location (`t` and `t-twin`) is never touched. |
| `GuardrailsTest` | | Held, frozen and unregistered tables are untouched until the hold and freeze go. |
| `PurgeTest` | | A purge removes the prefix after its claim; another worker's claim is left alone; a claimed purge resumes. |
| `PendingDeletionTest` | | Pending deletions wait for their time, then are deleted and reported done. |
| `WorkerRunTest` | | One table's whole maintenance over 16 days of the worker's clock: orphan scans and compactions at most once a day, deletions 7 days after they were recorded. |
| `KubernetesJobsTest`, `TokenSourceTest` | | The Job manifest; the token sources. |
| `OrphanRaceTest` | `heavy` | A writer committing during orphan scans loses nothing. |
| `KafkaDownTest` | `heavy` | With Kafka down the scan changes nothing; the next one catches up. |
| `KillMidPurgeTest` | `heavy` | A worker killed mid-purge is resumed by the next one. |
| `KubernetesCompactionTest` | `heavy` | Compaction as a Job in Docker Desktop's cluster, in a namespace of its own that it deletes. Needs `kubectl`, `helm` and the `docker-desktop` context. |
| `LargeExpiryTest` | `overnight` | One scan expires 9,999 of 10,000 snapshots. |
| `LargeOrphanScanTest` | `overnight` | The orphan scan's heap per reachable file, at 250,000 to 1,000,000 files. |

## Limits

- The intervals between orphan scans and between compactions are kept in memory. After a restart, every
  table's orphan scan runs again at the first scan. A compaction does not run again while its finished Job is
  still kept.
- The orphan scan holds every file a table reaches in memory. Size the worker for the largest table;
  `LargeOrphanScanTest` prints the cost per file and how many files a 2 GiB worker can scan.
- A worker that crashes after an expiry commit and before recording its files leaves them unrecorded. The
  orphan scan finds them once they are older than `older-than`.
- A file outside its table's location (for instance under a `write.data.path` elsewhere) is never queued.
  The catalog refuses such a location, and the file is not the worker's to delete. The scan report counts it.
- Compaction is not in the engine matrix: `KubernetesCompactionTest` covers it.

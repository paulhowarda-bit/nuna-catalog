# 2026-11-24, the worker reads its storage prefixes from the catalog at each scan (backlog 256)

**Built** on `worktree-agent-aed3b2f371ce7b292` (D30, amended 2026-11-24):
- `GET /management/v1/maintenance/storage-prefixes` (`StoragePrefixRoutes`): name, kind, endpoint and prefixes of each
  profile that names a `maintenance-principal`, never a credential; a maintenance principal acting as itself only,
  any other caller an audited 403 (`not_maintenance`).
- Worker settings hold one identity per store, `nuna.maintenance.storage.<store>` (kind, endpoint, region, keys or
  the default chain, CA bundle); prefixes and path-style access are gone from them. Each scan reads the prefixes
  first (`Prefixes.resolve`): a profile is served by the one store of its kind and endpoint, refused when none or two
  are; each prefix is parsed with `ObjectLocation.parse` after `s3a`/`s3n` become `s3`, refused when it fails.
  `OwnStorage` lists and deletes only strictly beneath a prefix (at one is refused now; `Storage.java:102` accepted
  it). A read that fails or names none leaves nothing reached: no orphan scans, purges and due deletions read but
  not acted on. The set is logged at the first read and on each change, new prefixes at WARN; refusals once at WARN
  and counted as `storage-prefixes` failures every scan.
- DR replicas are keyed by store (`dr.replicas.<store>`); the DR verifier reads the prefixes before each pass. A
  compaction Job's CA bundle is its table's store's.
- Merged with backlog 250: the scan's start is reported after the prefix read, each reached prefix keeps its
  `StorePrefix` account on its store's client, and each store reports its own kind (`minio`, `ceph`, `s3`). Then
  merged with backlog 251, which passes the reported kind through as it is; no conflict.
- Chart (`kind` and `endpoint` required per store, `prefixes` refused), `demo/k8s/maintenance-values.yaml` and
  `demo-values.yaml` (the demo's profile now names `maintenance-principal: nuna-maintenance`, without which the worker
  would reach nothing), `deploy/local/worker-values.yaml`, `deploy/README.md` ("Delete rights": the rules per store,
  the console sentence; the DR runbook), the worker's README, `docs/install-at-work.md`,
  `docs/observability/{worker-metrics,dr-watermark,table-health}.md`.

**Checked:**
- `PrefixesTest` 5/5, `WorkerPrefixesTest` 3/3, `DrStoresTest` 3/3, `TrustedS3ClientsTest` 6/6, `KubernetesJobsTest`
  5/5, `StoragePrefixRoutesTest` 2/2; `:catalog-maintenance:test` 94/94 after merging main.
- `DeletePolicyMinioTest` 2/2 on Silo (heavy, run alone beside the nuna-demo stack), the new one a worker user holding
  only `to`, refused outside its prefix and in a second bucket.
- The worker's heavy tests whose storage now comes from the catalog's profiles, run with `-Pheavy` beside the demo:
  `PurgeTest`, `PendingDeletionTest`, `OrphanScanTest`, `WorkerTraceTest`, `NightlyCleanupTest`, `GuardrailsTest`,
  `SnapshotExpiryTest`, `WorkerRunTest`, `KillMidPurgeTest`, `RefCleanupTest`, `OrphanRaceTest`, `StorageReportTest`
  all passed. `ScanMetricsTest` failed: its signed-out scan now also counts one `storage-prefixes` failure (the
  prefix read is refused with the work list). The test expects it now and passes alone (1/1).
- `helm template` of the worker chart: the demo's and the local values render; no store, a store without `endpoint`,
  and a store with `prefixes` are refused.
- 12 new bites recorded and checked with `git apply --check`, none run (`bites/D18/storage-prefixes-open`, 11 in
  `bites/D30/`, the route's and the worker's); 6 recorded again with the same break against the moved code
  (`bites/M6/job-storage-ca-any-profile`, `job-storage-ca-left-out`, `worker-ca-bundle-ignored`,
  `worker-ca-bundles-collide`, `bites/OBS-49/catalog-calls-carry-no-trace`, `bites/D33/worker-expired-branch-not-ended`).
- `./gradlew build` on the branch with `main` merged (backlog 251 in): green in 3 m 13 s, 1,733 test results, none
  failed; `:bitesApply` passed.

**Not run:** `DeletePolicyAwsTest#theBucketPolicyAloneLetsTheWorkerDelete` (`-Ps3=aws`: no AWS profile here). The
worker's heavy tests not in the run above (`KafkaDownTest`, `KubernetesCompactionTest`), the Ceph leg, and the
overnight ones whose worker settings changed shape (`PrivateCaCompactionTest`, `EngineMatrixTest#compaction`, `DrDrillTest`): the overnight run. The demo was not
rebuilt.

**Open:** what catalog-security did not check (backlog 256, last paragraph).

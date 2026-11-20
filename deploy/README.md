# Running the catalog in Kubernetes

- `image/Dockerfile`: the server's container image.
- `helm/nuna-catalog/`: the Helm chart. It runs the catalog servers only. The database, Kafka, the object
  stores and the identity provider run elsewhere [D36], and the values name them.
- `image/maintenance.Dockerfile` and `helm/nuna-maintenance/`: the maintenance worker's image and chart
  [D30], one release beside each catalog deployment. See catalog-maintenance/README.md.
- `image/audit-tables.Dockerfile` and `helm/nuna-audit-tables/`: the audit-tables writer's image and chart [D23],
  which writes every audit record into the audit catalog's `events` table. See catalog-audit-tables/README.md.
- `local/`: try the chart on Docker Desktop's Kubernetes, with throwaway PostgreSQL, Kafka and Silo.

## The image

```bash
./gradlew :catalog-server:bootJar
docker build -f deploy/image/Dockerfile -t <registry>/nuna-catalog:<version> catalog-server/build/libs
```

- The build context is the folder holding the runnable jar (`*-exec.jar`), so Docker is sent nothing else.
- The jar is unpacked into Spring Boot's layers. The 111 libraries are one 70 MB layer and the code about
  1 MB, so a code change re-ships only the code layers.
- The base is `eclipse-temurin:25.0.5_8-jre-noble`, as in the demo. At work, pass the approved Java 25
  runtime with `--build-arg BASE_IMAGE=<image>` (PLT-6). The image needs `sleep` on the path for the
  chart's preStop pause.
- The server runs as uid 10001, not root, and writes only to `/tmp`.

## The chart

```bash
helm install nuna-catalog deploy/helm/nuna-catalog -n <namespace> -f <your-values.yaml>
```

Settings come in two parts.

**`config`** holds Spring Boot properties in YAML form. The chart renders them into `application.yaml` in a
ConfigMap. Every `nuna.*` setting is documented on its settings class in `catalog-server`:
`DatabaseSettings`, `StorageSettings`, `SecuritySettings`, `AuditSettings`, `GovernanceSettings`,
`RestSettings` and `UiSettings`. `deploy/local/values.yaml` is a complete example. The chart refuses to
render without at least one entry in `config.nuna.security.issuers`, and without
`config.spring.datasource.url` unless the database is MongoDB.

**`secrets`** names a Secret that Spring Boot reads as a config tree. Each key of the Secret is a property
name and its value is that property's value:

```bash
kubectl -n <namespace> create secret generic nuna-catalog-secrets \
  --from-literal=spring.datasource.password=... \
  --from-literal=nuna.storage.profiles.onprem.secret-access-key=...
```

Then set `secrets.existingSecret: nuna-catalog-secrets`. Instead of making the Secret yourself, you can
pass `secrets.values` and the chart makes it; keep that values file out of git. A change to `config` or
`secrets.values` restarts the servers. A change to an existing Secret does not, so run
`kubectl rollout restart deployment/<release>` after it.

What the chart sets up:

- **Servers.** Three replicas by default, one per zone [D37]. They are spread over zones and then nodes
  (`ScheduleAnyway`). A PodDisruptionBudget allows one down at a time.
- **Rolling upgrades.** One new server at a time, and no old one stops until its replacement is ready. The
  new release migrates the schema while the old one keeps serving, which M8's rolling-upgrade test showed
  is safe.
- **Probes.** The probes read `/ui/config.json`. It needs no token and no database read, so the probes only
  say the server is up and answering. During a database outage every server answers 503 with Retry-After;
  taking servers out of the Service would not help. The start-up probe allows 10 minutes, because the
  first start of a release runs the migrations.
- **Shutdown.** Each server pauses 5 s (preStop) before Spring's graceful shutdown starts. This lets the
  Service stop routing to it first. Without the pause, 3 of 583 requests failed during a rolling restart.
  With it, 0 of 550 failed.
- **Security context.** Non-root, read-only root filesystem, all capabilities dropped. The service account
  token is not mounted, because the server never calls the Kubernetes API.
- **AWS.** For credential vending on EKS, annotate the service account with
  `serviceAccount.annotations: {eks.amazonaws.com/role-arn: ...}` (IRSA). The AWS SDK's default chain then
  uses that role, which applies to every profile without static keys. EKS Pod Identity should work too,
  with no annotation (the SDK is 2.57, and its default chain reads Pod Identity). Neither has been tried on
  EKS. The role a profile's `sts-role` names must trust that identity for both `sts:AssumeRole` and
  `sts:TagSession`: every vend tags its session with whom it is for (`SessionIdentity`), and AWS refuses a
  tagged AssumeRole without the second.
- **Ingress.** Off by default (`ingress.enabled`). Clients outside the cluster (Spark, Trino, the
  console) need it, or a LoadBalancer Service.
- **OpenShift.** Its restricted policy assigns its own uid. There, set `podSecurityContext` to only
  `runAsNonRoot` and `seccompProfile`. Not tried.

### The database

One database per deployment [D7]. `config.nuna.database.backend` names it, and the first server of a release
migrates its schema. What each one asks of the catalog's database role is on the store's class
(`SqlCatalogStore`, `OracleCatalogStore`, `MongoCatalogStore`).

- `postgresql`, the default: PostgreSQL or CockroachDB. `config.spring.datasource.url` is
  `jdbc:postgresql://<host>:<port>/<database>`; the password goes in the Secret as `spring.datasource.password`.
- `oracle`: single instance, RAC or Exadata. The URL names the catalog's own service,
  `jdbc:oracle:thin:@//<host>:<port>/<service>`, which needs `COMMIT_OUTCOME = TRUE` (Transaction Guard) and
  no `FAILOVER_TYPE`, so the driver never replays a transaction. Set the driver's read timeout, which bounds
  every wait on the database, in `config` (YAML keeps the key's case; an environment variable would not):

  ```yaml
  config:
    spring:
      datasource:
        url: jdbc:oracle:thin:@//<host>:1521/<service>
        username: <user>
        hikari:
          data-source-properties:
            oracle.jdbc.ReadTimeout: 60000      # milliseconds
    nuna:
      database:
        backend: oracle
  ```

  The role needs EXECUTE on `DBMS_APP_CONT`, SELECT on `V_$DATABASE`, `V_$SERVICES` and `V_$PARAMETER`, and
  CREATE JOB. Sessions must sort with `NLS_SORT = BINARY`, which follows the JVM's locale; the image's
  English locale gives it.
- `mongodb`: a replica set. Leave `spring.datasource` empty: the server then has no JDBC database. The
  connection string carries the password, so it goes in the Secret, as `nuna.database.mongodb.uri`. It must
  name the database and set `socketTimeoutMS`, which bounds every wait; the server refuses to start without
  them: `mongodb://<user>:<password>@<h1>,<h2>,<h3>/<database>?replicaSet=<set>&socketTimeoutMS=60000`.
  The role needs `clusterMonitor` besides `readWrite` on the database.

`OracleBackendTest` and `MongodbBackendTest` (catalog-server, `heavy`) run the server this way on Oracle Free
and a one-member replica set.

Before it migrates, each server runs the store's self-check of the database's safety settings [D8]: a
synchronous standby and `synchronous_commit` on PostgreSQL, Transaction Guard and a synchronized Data Guard
standby on Oracle, three voters and a majority write concern on MongoDB, three voting replicas on CockroachDB,
among others (docs/storage-design.md section 7). Any finding stops the server with an error that names each
one, before it changes anything in the database, and a self-check the role cannot run stops it too.
`config.nuna.database.allow-unsafe` lists findings, by code, that the server starts despite, logging each as a
warning; it is empty by default. Only a test or demo database on one node sets it, as `deploy/local/values.yaml`
does with `[pg.no-sync-standby]`; leave it empty in production.

### Telemetry

Both charts send metrics, traces and logs to an OpenTelemetry collector over OTLP/HTTP when `telemetry.endpoint` is
set, and export nothing when it is empty, the default. Log lines go to stdout as JSON either way.
docs/observability/README.md says what is exported.

```yaml
telemetry:
  endpoint: http://otel-collector.observability:4318   # OTLP/HTTP; gRPC (4317) is not supported
  headersSecret: {name: otlp-headers, key: headers}    # or headers: "x-tenant=lake", when nothing in them is secret
  caConfigMap: {name: otlp-ca, key: ca.crt}            # for an https endpoint signed by an internal CA
  sampling: "0.1"                                      # the share of new traces kept
  resourceAttributes: {deployment.environment.name: prod}
```

- The chart turns these into the standard `OTEL_*` variables, which Spring Boot reads: `OTEL_EXPORTER_OTLP_ENDPOINT`,
  `OTEL_EXPORTER_OTLP_HEADERS` (from the Secret's key when `headersSecret.name` is set), `OTEL_EXPORTER_OTLP_CERTIFICATE`
  (the ConfigMap mounted at `/etc/nuna/otlp-ca`), and `OTEL_TRACES_SAMPLER=parentbased_traceidratio` with the
  sampling. A request whose caller's trace was sampled is always kept.
- It always sets `OTEL_SERVICE_NAME` to the chart's name (`nuna-catalog`, `nuna-maintenance`) and
  `OTEL_RESOURCE_ATTRIBUTES` to `service.namespace`, `service.version` (the image tag), `service.instance.id` (the
  pod), `k8s.namespace.name`, `k8s.pod.name` and `k8s.node.name`, plus `resourceAttributes`.
- Headers hold `name=value` pairs separated by commas, e.g. a collector's API key:
  `kubectl -n <namespace> create secret generic otlp-headers --from-literal=headers='authorization=Bearer <key>'`.
- If work's platform injects the `OTEL_*` variables itself (an operator or a sidecar), leave `telemetry.endpoint`
  empty: the endpoint and headers it injects take effect, beside the service name, resource attributes and sampler
  the chart sets.

### Before production

- **Database safety settings.** The server refuses to start while the store's self-check reports a finding
  ("The database", above). Leave `config.nuna.database.allow-unsafe` empty.
- **ETags and routing [D14].** No sticky routing is needed. M8 found that ETags did not survive a change of
  server, because the ETag hashed each server's own vended credentials; Paul chose a shared credential epoch
  over sticky routing (D14, amended 2026-11-19), and item 15 built it. The epoch is the issuance window a load
  falls in, a quarter of `nuna.storage.credential-lifetime` (7.5 minutes at the default 30; D14, amended
  2026-11-20), so servers with the same lifetime, as one release's replicas are, give the same ETag. With the
  default `service.sessionAffinity: None`, a conditional load answered by another server is a 304 too, and an
  Ingress or external balancer needs no stickiness either. Each window's end costs a client one full load.
- **Purge rate [D30].** `nuna.governance.purges-per-minute` (default 10) is counted by each server on its
  own, so the replicas together accept that many purges a minute times the replica count: three servers set
  to 10 accept 30. To hold the whole deployment to a rate, set it to that rate divided by the replica count.
  A purge claim over the limit answers 429 with `Retry-After: 60`, and the maintenance worker tries again on
  its next scan.
- **Audit-trail reads (item 19).** Each `GET .../audit` read opens a Kafka consumer, so one caller (the
  principal its token names, or the end user a trusted engine acts for) may read at most
  `nuna.governance.audit-reads-per-minute` (default 60) a minute. Like the purge rate it is counted by each
  server on its own: a caller whose reads the balancer spreads over three servers set to 60 gets up to 180 a
  minute. The console reads only when a person opens an object's Audit tab, changes how many to show or
  presses Refresh, so the default never holds a person back; it holds a script to about one read a second per
  server. A read over the limit answers 429 with `Retry-After: 60` and is not audited, as a purge claim's is not.
- **Delete rights [D30].** No credentials the catalog vends can delete, a maintenance principal's
  included. The maintenance worker deletes with its own storage identity, which each profile names as
  `maintenance-principal`: an IAM role or user ARN on AWS, `arn:aws:iam::<tenant>:user/<uid>` on Ceph (the
  tenant empty for none), a user name on MinIO. Then a platform operator reads
  `GET /management/v1/maintenance/delete-policies` and applies each entry once, as an identity that may:
  - `bucket-policy` (AWS S3, Ceph): `aws s3api put-bucket-policy --bucket <bucket> --policy '<policy>'`,
    with `--endpoint-url <endpoint>` for Ceph. It denies delete beneath the profiles' prefixes to every
    principal but the worker, the catalog's own role or user included. It also allows the worker to delete
    there and to list the bucket beneath its prefixes (`s3:ListBucket`, limited by `s3:prefix`), which its
    orphan scans and purges need; nothing else grants a Ceph user rights in another user's bucket. On AWS
    that allow is enough for a worker role in the bucket's own account. A bucket holds one policy, so merge
    these statements into any it already has.
  - `user-policy` (MinIO), two per profile. The one with `except`: `mc admin policy create <alias>
    nuna-no-delete <file>`, then `mc admin policy attach <alias> nuna-no-delete --user <user>` for every
    user but the worker, the catalog's own included. The one with `to`, which lets the worker delete and list
    beneath the prefixes: create it the same way (say `nuna-maintenance`) and attach it to the worker's user
    alone. MinIO enforces no bucket policy on signed requests (Silo was checked on 2026-11-12), and its root
    user ignores every policy, so the catalog must not run as root.

  A lifecycle rule deletes objects without anyone calling delete, and a policy can be changed, so each policy
  also denies every principal but the worker setting lifecycle rules on the bucket, and on AWS S3 and MinIO
  changing or deleting the bucket policy. Once it is applied, only the worker can change those (on AWS also
  the account's root user, whom no bucket policy binds). On Ceph the policy denies lifecycle rules only, so:
  - the catalog's role or user holds no bucket-configuration rights (the bucket policy, lifecycle rules) on
    any store;
  - on Ceph an operator's user, never the catalog's, owns the bucket: a bucket's owner can always replace its
    policy.

  The catalog does not apply them itself: its own credentials would then need the right to undo them. Apply
  them again after changing a profile's prefixes. A profile with no `maintenance-principal` is listed as
  `unprotected`. Each form has a heavy test, and each ran on 2026-11-20 (backlog 4): `DeletePolicyAwsTest`
  on real S3 (`-Ps3=aws`), `DeletePolicyMinioTest` on Silo with non-root users, and `DeletePolicyCephTest`
  (`-Ps3=ceph`, in the heavy pass's Ceph leg). `local/run.sh up` applies the MinIO form to Silo, and its `smoke`
  tries a delete as each user ("Trying it locally").
- **Compaction Jobs [D30].** With `config.nuna.maintenance.compaction.image` set, the maintenance worker
  launches its compaction Jobs in `config.nuna.maintenance.compaction.namespace`. Create that namespace
  first, for the Jobs alone: never the catalog's nor the worker's, since a pod there may run as any service
  account and mount any Secret of it. The chart puts its Role, RoleBinding and the Jobs' service account
  there, and refuses to render without the namespace or with the release's own.
- **TLS.** TLS from clients to the catalog is the Ingress's job. TLS from the catalog to the database goes
  in the JDBC URL.
- **Resources.** The defaults (1 CPU, 2 GiB, heap at 75 % of that) are a starting point, not a measurement.
  Each metadata cache may hold up to `nuna.rest.metadata-cache-chars` (256 M characters by default).
  Size the limit on work hardware.

## Recovering in the DR region

The catalog database and object storage each replicate to the DR region asynchronously (D37), and neither waits for the
other. After the primary region is lost, the DR database may name files the DR store never got, or the DR store may
hold commits the DR database never saw. This runbook brings each table back to a version DR holds whole, as a
platform operator. Its routes, the DR watermark and the verifier are in `docs/observability/dr-watermark.md`. The
bucket naming per region and the replication SLA come from PLT-4 at work; until then the DR store is the same buckets
and paths on another endpoint (Paul, 2026-11-20).

**Before a disaster.** Run the maintenance worker's DR verifier in the primary region: name each storage profile's
DR replica, read-only keys on the DR store, under `nuna.maintenance.dr.replicas.<profile>` (endpoint, region,
path-style access, keys, CA bundle); `nuna.maintenance.dr.verify-interval` sets how often it passes (1 minute by
default). In the worker chart they go under `config.nuna.maintenance.dr`, the secret key under `secrets.values` as
`nuna.maintenance.dr.replicas.<profile>.secret-access-key` and a CA bundle in `storageCaConfigMap`, as a storage
profile's do; the chart's values do not describe them yet (item 92).
The verifier keeps each table's DR watermark, the newest version whose every file the DR store holds, in the catalog
database, so it reaches DR with the database. `GET /management/v1/dr/watermarks` lists the tables behind and their
lag; `NunaDrLagging` pages when a commit has not been whole in DR for 15 minutes.

**In the DR region,** with the DR database promoted and the catalog servers started on it and on the DR store:

1. **Freeze** every catalog, before anything writes or cleans up: `PUT /management/v1/catalogs/{catalog}/dr-freeze`
   with `{"reason": "DR recovery"}`. Commits, creates, drops, renames, undrops and purges are refused, and the
   maintenance worker's scans pass the frozen tables by.
2. **Verify.** Start the maintenance worker in DR with its replicas naming the DR store itself, and wait for one DR
   pass to end after the start (`nuna.maintenance.dr.pass.last.success`, or the worker's log). The DR work list
   includes frozen tables, so the pass moves each watermark as far as the DR store allows.
3. **Read the plan.** `POST /management/v1/catalogs/{catalog}/dr-reconcile` with `{"rewind": true}` lists each table
   above its watermark, the watermark it would move back to, and its `lost` commits (version, commit id, time,
   principal): the commits DR does not hold whole. Hand the lost tail to the tables' owners for business
   reconciliation. A table with no watermark (`"watermark": null`) has no version known whole in DR; it is not moved,
   and its owner decides what to do with it.
4. **Rewind.** The same with `{"rewind": true, "apply": true}`: each frozen table above its watermark moves back to
   it in one journaled commit, the watermark's metadata written again as the next version. A table that did not move
   says why (`apply-problem`); a pointer that moved since the plan means run step 3 again.
5. **Walk forward** tables with no watermark, as before the watermark: `{}` reports, `{"apply": true}` moves each
   along its proven metadata files. A table with a watermark is never walked past it. Table locations no catalog holds
   are listed as `unknown-locations`.
6. **Thaw:** `DELETE /management/v1/catalogs/{catalog}/dr-freeze`. Then register the unknown locations by name, if
   wanted: a register is refused under a freeze.

Each step is a platform operator's (D18, amended 2026-11-20); the rewind's commits reach the audit trail as the
fast-forward's do. The overnight
DR drill (`DrDrillTest`) runs the freeze, reconcile and thaw; its leg with the DR store behind is item 92's.

## Trying it locally

On Docker Desktop with Kubernetes switched on:

```bash
deploy/local/run.sh up      # jar, image nuna-catalog:local, PostgreSQL, Kafka, Silo, the chart (2 servers)
deploy/local/run.sh smoke   # through each server: 401, config, namespace, table, vended credentials, deletes, drops
deploy/local/run.sh worker  # after up: Keycloak, the maintenance worker's chart, its own identity everywhere
deploy/local/run.sh down    # uninstall and delete the namespaces
```

- It uses namespace `nuna-local` (`NUNA_K8S_NAMESPACE`), and always passes `--context docker-desktop`
  (`NUNA_K8S_CONTEXT`), never the current context.
- The test issuer is `demo/Mint.java`. Its key and tokens are in `~/.nuna-k8s` (`NUNA_K8S_HOME`).
- Memory limits add up to 3 GiB: two servers at 768 MiB, Kafka 640, Silo 512, PostgreSQL 384.
- `up` takes about 2 minutes. To reach the console:
  `kubectl --context docker-desktop -n nuna-local port-forward svc/nuna-catalog 8181:8181`, then open
  <http://localhost:8181/ui/> and paste a token from `~/.nuna-k8s/tokens/`.
- The catalog names Silo `silo.<namespace>.svc`, not `silo`: it vends that endpoint with each table's credentials,
  and the worker and its compaction Jobs read tables from other namespaces, where a short Service name does not
  resolve.
- The catalog signs in to Silo as a user of its own, `nuna-catalog`, never as Silo's root user, whom no policy
  binds [D21]. `up` makes it with a policy to read, write and list beneath `s3://warehouse/tables`. That policy
  allows delete too, on purpose, as another grant might at work, so that the delete policy is what takes it away.
  Once the catalog is up, `up` reads its delete policies as a platform operator (`oscar`) and applies the MinIO
  pair: the one with `except` to `nuna-catalog`, the one with `to` to the worker's own user, `nuna-maintenance`,
  which it makes too. `smoke` then puts a file beneath its table with the table's vended credentials (as `lake`)
  and tries to delete it as each: the vended credentials and `nuna-catalog` must be refused (403), and
  `nuna-maintenance` must delete it (204). Silo's root user only manages the users.
- `worker` installs the maintenance worker's chart [D30] in `nuna-local-maintenance`, its compaction Jobs in
  `nuna-local-compaction`, with `local/worker-values.yaml`. The worker signs in with the client-credentials grant
  as `service-account-nuna-maintenance` (group `nuna-workers`, granted `write` on `lakehouse`) at Keycloak
  (`local/keycloak.yaml`, in the catalog's namespace), lists and deletes as `nuna-maintenance`, its Silo user, and
  launches Jobs as the chart's service account. Its cleanup window is open all day, turning at 12:00 UTC, and it scans every minute. The
  Jobs run the engine matrix's Spark image, `nuna-engines/spark:4.1.4-iceberg-1.12.0`, which must be built first
  (`docker build -t <that name> catalog-tests-engines/images/spark`). It adds 3 GiB of limits: Keycloak 768 MiB,
  the worker 768 MiB, one Job at a time at 1.5 GiB.

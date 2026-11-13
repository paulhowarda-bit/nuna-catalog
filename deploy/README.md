# Running the catalog in Kubernetes

- `image/Dockerfile`: the server's container image.
- `helm/keel-catalog/`: the Helm chart. It runs the catalog servers only. The database, Kafka, the object
  stores and the identity provider run elsewhere [D36], and the values name them.
- `local/`: try the chart on Docker Desktop's Kubernetes, with throwaway PostgreSQL, Kafka and Silo.

## The image

```bash
./gradlew :catalog-server:bootJar
docker build -f deploy/image/Dockerfile -t <registry>/keel-catalog:<version> catalog-server/build/libs
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
helm install keel-catalog deploy/helm/keel-catalog -n <namespace> -f <your-values.yaml>
```

Settings come in two parts.

**`config`** holds Spring Boot properties in YAML form. The chart renders them into `application.yaml` in a
ConfigMap. Every `keel.*` setting is documented on its settings class in `catalog-server`:
`StorageSettings`, `SecuritySettings`, `AuditSettings`, `GovernanceSettings`, `RestSettings` and
`UiSettings`. `deploy/local/values.yaml` is a complete example. The chart refuses to render without
`config.spring.datasource.url` and at least one entry in `config.keel.security.issuers`.

**`secrets`** names a Secret that Spring Boot reads as a config tree. Each key of the Secret is a property
name and its value is that property's value:

```bash
kubectl -n <namespace> create secret generic keel-catalog-secrets \
  --from-literal=spring.datasource.password=... \
  --from-literal=keel.storage.profiles.onprem.secret-access-key=...
```

Then set `secrets.existingSecret: keel-catalog-secrets`. Instead of making the Secret yourself, you can
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
  EKS.
- **Ingress.** Off by default (`ingress.enabled`). Clients outside the cluster (Spark, Trino, the
  console) need it, or a LoadBalancer Service.
- **OpenShift.** Its restricted policy assigns its own uid. There, set `podSecurityContext` to only
  `runAsNonRoot` and `seccompProfile`. Not tried.

### Before production

- **Database.** The server wires only the PostgreSQL-wire store today (`StoreWiring`), so it runs on
  PostgreSQL or CockroachDB. The Oracle and MongoDB stores pass the shared suite, but no server
  configuration selects them yet.
- **ETags and routing.** M8 found that ETags do not survive a change of server: each server mints its own
  vended credentials, and the ETag includes a hash of them. With the default `service.sessionAffinity: None`,
  a conditional load answered by another server is a 200, not a 304. `ClientIP` affinity keeps a client on
  one server, behind the Service only. An Ingress or external balancer needs its own stickiness. The choice
  between sticky routing and a shared credential epoch is still open (build-plan, M8 findings).
- **Delete rights [D30].** No credentials the catalog vends can delete, a maintenance principal's
  included. The maintenance worker deletes with its own storage identity, which each profile names as
  `maintenance-principal`: an IAM role or user ARN on AWS, `arn:aws:iam::<tenant>:user/<uid>` on Ceph (the
  tenant empty for none), a user name on MinIO. Then a platform operator reads
  `GET /management/v1/maintenance/delete-policies` and applies each entry once, as an identity that may:
  - `bucket-policy` (AWS S3, Ceph): `aws s3api put-bucket-policy --bucket <bucket> --policy '<policy>'`,
    with `--endpoint-url <endpoint>` for Ceph. It denies delete beneath the profiles' prefixes to every
    principal but the worker, the catalog's own role or user included; on Ceph it also lets the worker delete
    there, since nothing else grants a Ceph user rights in another user's bucket. A bucket holds one policy,
    so merge these statements into any it already has.
  - `user-policy` (MinIO): `mc admin policy create <alias> keel-no-delete <file>`, then
    `mc admin policy attach <alias> keel-no-delete --user <user>` for every user but the worker, the
    catalog's own included. MinIO enforces no bucket policy on signed requests (Silo was checked on
    2026-11-12), and its root user ignores every policy, so the catalog must not run as root.

  The catalog does not apply them itself: its own credentials would then need the right to undo them. Apply
  them again after changing a profile's prefixes. A profile with no `maintenance-principal` is listed as
  `unprotected`. Only the Ceph form has a test (`DeletePolicyCephTest`, heavy, `-Ps3=ceph`), and it has not
  run yet.
- **TLS.** TLS from clients to the catalog is the Ingress's job. TLS from the catalog to the database goes
  in the JDBC URL.
- **Resources.** The defaults (1 CPU, 2 GiB, heap at 75 % of that) are a starting point, not a measurement.
  Each metadata cache may hold up to `keel.rest.metadata-cache-chars` (256 M characters by default).
  Size the limit on work hardware.

## Trying it locally

On Docker Desktop with Kubernetes switched on:

```bash
deploy/local/run.sh up      # jar, image keel-catalog:local, PostgreSQL, Kafka, Silo, the chart (2 servers)
deploy/local/run.sh smoke   # through each server: 401, config, namespace, table, vended credentials, drops
deploy/local/run.sh down    # uninstall and delete the namespace
```

- It uses namespace `keel-local`, and always passes `--context docker-desktop`
  (`KEEL_K8S_CONTEXT`), never the current context.
- The test issuer is `demo/Mint.java`. Its key and tokens are in `~/.keel-k8s` (`KEEL_K8S_HOME`).
- Memory limits add up to 3 GiB: two servers at 768 MiB, Kafka 640, Silo 512, PostgreSQL 384.
- `up` takes about 2 minutes. To reach the console:
  `kubectl --context docker-desktop -n keel-local port-forward svc/keel-catalog 8181:8181`, then open
  <http://localhost:8181/ui/> and paste a token from `~/.keel-k8s/tokens/`.

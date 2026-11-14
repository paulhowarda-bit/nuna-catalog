# Installing keel at work (M9)

For the agents that install the catalog at work and carry out M9. Read this first, then `CLAUDE.md`,
`build-plan.md` (M9 and section 6, the open questions work owns), `deploy/README.md` and
`catalog-maintenance/README.md`. Those documents hold the detail; this one gives the order and what is true
today.

## Where things stand (2026-11-13)

- `main` holds everything through v1.x phases 1 and 2: unregister, register-view and labels (D13, D27), and
  the maintenance worker with its purge claim, pending deletions and delete policies (D30).
- Proven green on Paul's machine:
  - The fast build, `./gradlew build`, on PostgreSQL.
  - The nightly of 2026-11-13, `./gradlew build -Pnightly`:
    - the store suites on PostgreSQL, CockroachDB, Oracle and MongoDB, with the CAS test at 1,000 rounds and
      the per-mutation fault sweeps;
    - the engine matrix (Spark, compaction, Kafka Connect, PyIceberg, SingleStore);
    - every load test, the rolling upgrade from 2b1af36 across schema V5 to V7 among them.
- Not verified anywhere yet. Treat these as untested, and say so if they are what you are relying on:
  - The `heavy` tests added in phases 1 and 2: `KubernetesCompactionTest`, `DeletePolicyCephTest`,
    `OrphanRaceTest`, `KillMidPurgeTest`, `KafkaDownTest`, and the store races
    (`commitRacingTheUnregisterExactlyOneApplies`, `lostReviveAnswerResolvesToCommitted`).
  - About 70 of the 200 recorded bites (`bites/`), which have never been run.
  - The `keel-maintenance` Helm chart, which has never been installed.
  - The AWS S3 and MinIO delete policies, which have never been applied to a real store.
- Paul will run the heavy pass (`./heavy.sh`) after the install to harden these. Do not run `-Pheavy`, the
  bites or `-Pnightly` unless he asks.
- **The server runs on PostgreSQL, CockroachDB, Oracle or MongoDB**, one per deployment, chosen with
  `keel.database.backend` (deploy/README.md, "The database"). Oracle and MongoDB were wired on 2026-11-13;
  their server tests (`OracleBackendTest`, `MongodbBackendTest`) ran on Oracle Free and a one-member replica
  set, not yet on RAC, Exadata or a real replica set.
- **The server does not run the stores' self-checks** (`selfCheck`), which the storage design says it does at
  startup. Check the database's safety settings by hand, and ask Paul before wiring it.

## CLAUDE.md at work

Its rules hold at work. Some of its facts describe Paul's home machine:

- The absolute paths (`C:/Users/paulh/OneDrive/Desktop/code/keel-catalog`) mean your main checkout.
- `org.gradle.workers.max=2` and "Docker has 8 GB" are sized for his laptop. Leave the setting unless Paul
  changes it.
- `docker-desktop` is his laptop's Kubernetes context. At work, name your cluster's context wherever a test or
  script asks for one (`-Pengines.cluster=<context>`, `KEEL_K8S_CONTEXT`).
- The repository and its folder are `keel-catalog` (renamed from `fidice-catalog` on 2026-11-13; GitHub
  redirects the old URL).
- Never push unless Paul asks, and the deferrals are his to make.

## 1. Build tools and repositories

- **JDK 25** installed on the machine. Gradle never downloads one
  (`org.gradle.java.installations.auto-download=false`).
- **Gradle 9.9.1.** `gradle/wrapper/gradle-wrapper.properties` fetches `gradle-9.9.1-bin.zip` from
  `services.gradle.org`. Point `distributionUrl` at an internal copy of the same zip and keep
  `distributionSha256Sum`, so the wrapper still checks it.
- **Libraries and plugins.** `settings.gradle.kts` has two `mavenCentral()` lines, one for plugins and one for
  dependencies. Replace both with the internal Maven-format repository:
  `maven { url = uri("https://<internal repository>") }`. Every library and plugin resolves from it, so
  each must be an approved artifact (PLT-6). A missing one fails the build by name; list those for Paul.
- **Docker** for the tests (Testcontainers) and the images; **kubectl** and **helm** for the install.

Then the fast build must be green before anything else:

```bash
./gradlew build
```

## 2. Container images the tests use

Mirror these into the internal registry. Most are set in `gradle.properties` and can be overridden on the
command line (`-Ppostgres.image=<registry>/postgres:18`); the rest are fixed in the files named.

| Image | Set in |
|---|---|
| `postgres:18` | `gradle.properties` (`postgres.image`) |
| `apache/kafka:4.2.2` | `gradle.properties` (`kafka.image`) |
| `pgsty/silo:RELEASE.2026-10-26T00-00-00Z` | `gradle.properties` (`silo.image`) |
| `ghcr.io/shopify/toxiproxy:2.12.0` | `gradle.properties` (`toxiproxy.image`) |
| `cockroachdb/cockroach:v25.4.20` | `gradle.properties` (`cockroachdb.image`) |
| `ghcr.io/clyso/ceph-test:v20.2.5` | `gradle.properties` (`ceph.image`) |
| `quay.io/keycloak/keycloak:26.8.2` | `gradle.properties` (`keycloak.image`) |
| `mongo:8.0.34` | `catalog-store-mongo/build.gradle.kts` |
| `gvenzl/oracle-free:23.26.3-slim-faststart` | `catalog-store-oracle/build.gradle.kts` |
| `ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88` | `ChangeFeedTest`, `EngineMatrixTest` |
| `kafbat/kafka-ui:v1.5.0` | `EngineMatrixTest` |
| `testcontainers/ryuk:0.14.0` | Testcontainers itself |

- Testcontainers can prefix every Docker Hub image (the names without a registry) with an internal one:
  `hub.image.name.prefix=<registry>/` in `~/.testcontainers.properties`. Images from `ghcr.io` and `quay.io`
  need the overrides above or a change in a branch.
- The engine matrix builds its own images from `catalog-tests-engines/images/` (Spark from
  `apache/spark:4.1.4-scala2.13-java21-python3-ubuntu`, PyIceberg, Kafka Connect). Their Dockerfiles download
  jars from Maven Central and packages from PyPI; point those at internal mirrors. The compaction Jobs run the
  same Spark image.

## 3. The images to deploy

```bash
./gradlew :catalog-server:bootJar :catalog-maintenance:bootJar
docker build -f deploy/image/Dockerfile --build-arg BASE_IMAGE=<approved Java 25 runtime> \
  -t <registry>/keel-catalog:<version> catalog-server/build/libs
docker build -f deploy/image/maintenance.Dockerfile --build-arg BASE_IMAGE=<approved Java 25 runtime> \
  -t <registry>/keel-maintenance:<version> catalog-maintenance/build/libs
docker build -t <registry>/keel-spark:<version> catalog-tests-engines/images/spark
```

Both Java images run as uid 10001 and write only to `/tmp`. The base image needs `sleep` on the path (the
catalog chart's preStop pause).

## 4. What must exist before the install

The charts run the catalog servers and the worker only. Everything else runs elsewhere [D36]:

- **Database.** PostgreSQL, CockroachDB, Oracle or MongoDB (see above): a database and a user that may create
  tables, with the extra rights deploy/README.md lists for Oracle and MongoDB. The first server of a release
  migrates the schema while older servers keep serving.
- **Kafka** for audit and change events (`keel.audit.kafka-bootstrap-servers`). A load that vends credentials
  waits for Kafka's acknowledgement.
- **Object stores** (AWS S3, Ceph or MinIO; PLT-3, PLT-5), with their buckets, named per region (PLT-4), and:
  - the catalog's own identity: an STS role on AWS S3 and Ceph, keys on MinIO. The role's trust policy must
    allow the catalog both `sts:AssumeRole` and `sts:TagSession`: every vend tags its session with whom it is
    for (`keel-principal`, `keel-table` and others), and AWS refuses a tagged AssumeRole without the second;
  - the worker's own identity, which alone may delete (D21, D30). On MinIO the catalog must not run as the
    root user, which ignores every policy.
- **Identity providers** (SEC-1): for each issuer its `iss`, its JWKS URL, the audience and the claims for the
  user id, groups and client id; a group for platform operators and one for grant admins; a client for the
  worker with the client-credentials grant and a scope.
- **KMS key sets** and **legal entities** for each catalog (SEC-8, CMP-1).
- The database's real **point-in-time-recovery window** (from the DBAs, PLT-2).
- A **namespace for the compaction Jobs** alone, if compaction is wanted (never the catalog's or the worker's).

## 5. Install the catalog

Start from `deploy/local/values.yaml` (complete, but local: plain http, a test issuer, the console's token
box). For work:

```yaml
image:
  repository: <registry>/keel-catalog
  tag: <version>
config:
  spring:
    datasource:
      url: jdbc:postgresql://<host>:5432/<database>?sslmode=verify-full
      username: <user>
  keel:
    storage:
      profiles:
        lake:
          kind: CEPH                          # S3, CEPH or MINIO
          endpoint: https://<s3 endpoint>
          region: <region>
          table-root: s3://<bucket>/tables
          allowed-prefixes: [s3://<bucket>/tables]
          sts-role: <role ARN>                # AWS S3 and Ceph; MinIO takes keys instead
          maintenance-principal: <the worker's storage identity>
      catalogs:
        <catalog>:
          profile: lake
          kms-key-set: <key set>
          legal-entity: <entity>
    security:
      issuers:
        - issuer: <iss>
          jwks-uri: <JWKS URL>
          audiences: [keel-catalog]
          algorithms: [RS256]
          user-id-claim: <claim>
          groups-claim: <claim>
          client-id-claim: <claim>
      grant-admin-groups: [<group>]
      platform-operator-groups: [<group>]
      maintenance-principals: [<the worker's principal id>]
    governance:
      pitr-window: <the database's PITR window, e.g. 7d>
    rest:
      drop-retention: 30d                     # must exceed pitr-window, or the server refuses to start
    audit:
      kafka-bootstrap-servers: <brokers>
```

- Every `keel.*` setting is documented on its settings class in `catalog-server`: `StorageSettings`,
  `SecuritySettings`, `AuditSettings`, `GovernanceSettings`, `RestSettings`, `UiSettings`.
- Leave `keel.storage.require-tls` at its default (true) and `keel.ui.dev-token-login` off.
- Passwords and keys go in a Secret read as a config tree, one key per property, never in the values file:

```bash
kubectl -n <namespace> create secret generic keel-catalog-secrets \
  --from-literal=spring.datasource.password=...
helm install keel-catalog deploy/helm/keel-catalog -n <namespace> -f <values.yaml> \
  --set secrets.existingSecret=keel-catalog-secrets
```

- The chart's defaults: three replicas spread over zones, a disruption budget of one, probes on
  `/ui/config.json`, a 5 s preStop pause, non-root with a read-only root filesystem. `deploy/README.md` says
  why.
- Decide with Paul before production: sticky routing for ETags (`service.sessionAffinity: ClientIP`, or the
  Ingress's own), Ingress and TLS, and resource sizes measured on work hardware.

## 6. Install the maintenance worker

```bash
kubectl create namespace <jobs namespace>          # only if compaction is wanted
helm install keel-maintenance deploy/helm/keel-maintenance -n <namespace> -f <worker values.yaml>
```

- Settings are `keel.maintenance.*`; `catalog-maintenance/README.md` lists them all, with an example for one
  Ceph store. The worker needs the client-credentials grant with a scope (`token.endpoint`, `client-id`,
  `client-secret`, `scope`, `audience`) whenever compaction is on.
- `storage.<profile>.prefixes` must cover every table location, and the keys there are the worker's own.
- Compaction is off until `compaction.image` names the Spark image and `compaction.namespace` the Jobs'
  namespace.
- Then a platform operator applies the delete policies, once, and again after any change of a profile's
  prefixes: read `GET /management/v1/maintenance/delete-policies` and apply each entry as `deploy/README.md`
  says (a bucket policy on AWS S3 and Ceph, two user policies on MinIO). The catalog never applies them
  itself. A profile without `maintenance-principal` is listed as `unprotected`.

## 7. Check the install

Run the steps of `deploy/local/run.sh smoke` by hand through each server, with real tokens: one with a
group that may create, one that may only read.

1. `GET /v1/config` without a token: 401.
2. `GET /v1/config?warehouse=<catalog>`: 200.
3. Create a namespace, read it through another server, create a table in it.
4. Load the table with `X-Iceberg-Access-Delegation: vended-credentials`: the answer carries
   `s3.access-key-id`.
5. Drop the table (204) and the namespace (204).

Then check that an audit event reached Kafka for each step, and that the worker logs a line per scan with no
failed jobs.

## 8. M9's own work

From `build-plan.md`; the open questions are in its section 6.

- Approved artifacts in the curated repository (PLT-6).
- RAC and Exadata, real IdPs, real object stores, and a DBA review of the self-check thresholds (PLT-2; the
  server does not run the self-checks yet, above).
- Bucket naming per region and the replication SLA for the DR runbook (PLT-4, D29).
- Trusted engines and who attests to them (SEC-2, SEC-5); token exchange and workload identity (SEC-1);
  on-prem MinIO and Ceph versions and their STS support (PLT-3).
- A scheduled overnight job in work CI running `./gradlew build -Pnightly`: the `overnight` tests and the CAS
  test at 1,000 rounds, nothing else. The engine matrix in it needs a Kubernetes cluster whose nodes see
  locally built images (`-Pengines.cluster=<context>`; see `catalog-tests-engines/README.md`). On Paul's
  machine the whole nightly takes about two hours.

## 9. After the install

Paul will harden with the heavy pass: `./heavy.sh` in a worktree of its own (`-Pheavy` on all four backends,
the Ceph leg, then every bite), about 3 to 3.5 hours if it is green. Report anything at work that would stop it
running there, but start it only when he asks. Two things to check:

- Docker memory: the pass runs a three-node cluster beside one other stack.
- `KubernetesCompactionTest` names the `docker-desktop` context in its code (`CONTEXT`), so it needs a cluster
  of that name or a change in a branch.

## 10. The demo

`demo/demo.sh up` (`demo/README.md`) runs the demo on a local Kubernetes cluster (`KEEL_DEMO_CONTEXT`,
default `docker-desktop`). That cluster never pulls: every image comes from the local Docker engine. So pull
each image below from the internal registry and tag it with the name shown
(`docker tag <registry>/postgres:18 postgres:18`). `demo.sh` then pulls nothing, and its Dockerfiles build on
the tagged images.

| Image | Used for |
|---|---|
| `postgres:18`, `apache/kafka:4.2.2`, `pgsty/silo:RELEASE.2026-10-26T00-00-00Z`, `quay.io/keycloak/keycloak:26.8.2`, `kafbat/kafka-ui:v1.5.0` | the stack (also in section 2) |
| `busybox:1.37.0` | the key server (Service `idp`) |
| `eclipse-temurin:25.0.5_8-jre-noble` | the catalog image (`deploy/image/Dockerfile`) |
| `eclipse-temurin:25.0.5_8-jdk-noble` | the lake writer (`demo/k8s/lake.Dockerfile`) |
| `ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88` | SingleStore with the change-feed connector |
| `debian:bookworm-slim` | DuckDB (`demo/k8s/duckdb.Dockerfile`) |
| `trinodb/trino:484` | Trino |
| `dbeaver/cloudbeaver:26.2.4` | CloudBeaver, Trino's UI (`demo/k8s/cloudbeaver.Dockerfile`) |
| `gvenzl/oracle-free:23.26.3-slim-faststart` | the CDC leg's Oracle (`demo.sh cdc`; also in section 2) |
| `maven:3.9.11-eclipse-temurin-21` | building the CDC leg's Kafka Connect image (`demo/k8s/connect.Dockerfile`) |

What reaches beyond the machine (checked 2026-11-13):

- **The CDC leg's Kafka Connect image** (`demo.sh cdc`) is built by Maven from Maven Central: the poms in
  `demo/cdc/plugins` name the Debezium 3.7.1 PostgreSQL and Oracle connectors (with Oracle's JDBC driver
  `com.oracle.database.jdbc:ojdbc11`), the Apache Iceberg 1.12.0 sink and its transforms, and Hadoop's
  client. Point Maven at the internal repository: a `settings.xml` mirror copied into the build stage of
  `connect.Dockerfile`. Its running pods talk only to the cluster.
- **DuckDB cannot be built at work.** Its Dockerfile downloads socat from Debian's mirrors, the DuckDB program
  from github.com and its extensions from extensions.duckdb.org, and `demo.sh up` stops when that build
  fails. Its UI also loads its page from ui.duckdb.org through the DuckDB process, so the UI needs the internet
  even when the image is built. Ask Paul how DuckDB gets to work, or whether it stays home.
- **Turned off:** SingleStore's critical diagnostics, which its dev license turns on and which send DDL and
  table metadata to phone-home.singlestore.com (`singlestore.yaml`); Studio's browser telemetry (Segment,
  Google Analytics, Mixpanel, Intercom) and upgrade check (`singlestore.Dockerfile`); kafbat's hourly release
  check against api.github.com (`stack.yaml`); DuckDB's automatic and community extension installs
  (`duckdb.yaml`); and CloudBeaver's version check, which had its Administration page fetch
  dbeaver.com/product/cloudbeaver-ce-version.json (`cloudbeaver.Dockerfile`). An explicit `INSTALL` in DuckDB still tries extensions.duckdb.org.
- **CloudBeaver** loads its page only from localhost and holds no outside connection; its Trino driver is in
  the image. Two features would reach outside, and the demo uses neither: the map view of a geometry value
  (tiles from openstreetmap.org) and the AI assistant (whichever AI service an administrator configures).
- Everything else in the stack talks only to the cluster: the catalog and its console, Keycloak, Silo,
  PostgreSQL, Kafka, the key server, the lake writer, Trino and the CDC leg. `demo.sh engines` builds the
  engine images, which need the mirrors in section 2.

The demo runs without TLS, and every page works that way at `localhost`: the console with its Keycloak
sign-in, Keycloak's admin console, kafbat, Studio, the DuckDB UI and CloudBeaver (checked 2026-11-13). Opened by the
machine's name or address instead, the console's sign-in fails: a plain-http page gets the browser's Web
Crypto only on localhost, and Keycloak's issuer and the console's redirect address are both localhost.

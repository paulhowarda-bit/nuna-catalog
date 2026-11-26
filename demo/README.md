# Local demo: the catalog, its console, SingleStore both ways, Trino, CDC, and the engine matrix, on Kubernetes

One stack on Docker Desktop's Kubernetes, shared with the engine matrix: PostgreSQL, Silo, the catalog server
(with the console), Keycloak for signing in by username and password, and Trino with CloudBeaver as its UI. `up`
starts that core, writes sample Iceberg tables, makes a branch and a tag of the catalog, and prints how to sign in.
Five switches add the rest, each on with `demo/demo.sh <switch>` and off with `demo/demo.sh <switch> stop` (D50):

- `gen`: the brokerage world, a live generator whose data reaches Iceberg through Kafka, SingleStore or both, as
  `kafka` and `ss` say. It needs one of them, not both (Paul, 2026-11-24): with neither on, it turns `kafka` on.
- `ss`: SingleStore, synced with Iceberg in both directions. `ss stop` leaves `gen` on, through Kafka.
- `kafka`: the generator writes through Kafka, Kafka Connect and kafbat. `kafka stop` leaves `gen` on through
  SingleStore if `ss` is on, and turns it off if not; it is refused while the CDC leg runs.
- `cdc`: changes in PostgreSQL, Oracle and CockroachDB, captured into Iceberg through Debezium and Kafka Connect.
- `metrics`: Grafana with nuna's dashboards, Prometheus and Tempo, which the catalog and the maintenance worker send
  their metrics and traces to (below, "Metrics and traces").

Kafka runs only while the generator writes through it or `cdc` is on: the catalog audits to its log, not to Kafka
(D23, amended 2026-11-23). `up` turns `gen`, `ss`, `kafka` and `metrics` back on if they were on (`~/.nuna-demo/switches`;
all off on a new machine), and keeps a running CDC leg. `engines` runs Spark, Kafka Connect, PyIceberg and SingleStore's ingest against the same stack,
with `gen`, `kafka` and `ss` on. Everything is local and uses test keys; nothing here is fit for production.

## Start

```bash
demo/demo.sh up
```

- Needs bash (Git Bash on Windows), Docker Desktop with Kubernetes on, `kubectl`, `helm`, `curl`, `unzip`,
  and JDK 25 (`JAVA_HOME`, or `java` on the path). Every `up` builds the server jar, the audit table writer's and
  the maintenance worker's jars, the change-feed connector jar and the brokerage world's classpath (`demo-datagen`)
  from this checkout (no tests run) and the images below, so the stack never runs code older
  than the checkout; when nothing changed, Gradle and Docker do nothing. The cluster never pulls an image: it
  runs them from the local Docker engine, and `up` pulls the public ones there.
- Run in the main checkout, where other sessions land all day, `up` builds one commit (backlog 274): the main
  checkout's HEAD when it starts. It runs from `~/.nuna-demo/checkout`, a worktree it keeps detached at that
  commit and locked, so a landing during its run neither stops it nor mixes commits into the images. `engines`,
  `gen`, `ss`, `kafka`, `cdc`, `token` and `rate` do the same, each at the HEAD of its own start; one of them at a
  time holds that worktree, and another is refused until it ends. `up` says which commit it builds, and the URLs
  and tokens it prints (and `token`) start with the commit the demo runs. Run from any other worktree, `demo.sh`
  runs from that worktree's files as they are. `demo/test-pin.sh` checks this in a scratch repository.
- Everything runs in namespace `nuna-demo` (`NUNA_DEMO_NAMESPACE`) of context `docker-desktop`
  (`NUNA_DEMO_CONTEXT`); `kubectl` and `helm` never use your current context. The stack is `demo/k8s`, the
  same manifests the engine matrix deploys when it runs on its own:
  - `stack.yaml`: PostgreSQL, which lets the catalog in over the network only by its client certificate (The
    catalog's database, below), and `idp`, which serves the demo issuer's keys (`demo/Mint.java`).
  - `kafka.yaml`: Kafka and kafbat, which the demo applies only while the generator writes through it or `cdc` is on. The demo patches
    kafbat with `kafka-ui-signin.yaml`, its sign-in through Keycloak; the engine matrix on its own leaves kafbat
    open.
  - `store-silo.yaml` and `seed.yaml`: Silo as Service `s3`, with its console on the pod's 9001, and the job
    that makes the warehouse bucket. The demo patches Silo with `silo-signin.yaml`, its console's sign-in
    through Keycloak; the engine matrix on its own leaves it out.
  - The catalog: the Helm chart `deploy/helm/nuna-catalog`, release `nuna-catalog`, one server, image
    `nuna-catalog:demo` (`deploy/image/Dockerfile`), values `catalog-values.yaml` (both catalogs:
    `lakehouse` for the demo's data, `engines` for the matrix's) and `demo-values.yaml` (the demo's two
    issuers, the console's sign-in, Silo, and the audit catalog `nuna_audit` with its two groups).
  - The audit table writer (`catalog-audit-tables`, D23): the chart `deploy/helm/nuna-audit-tables`, release
    `nuna-audit-tables`, image `nuna-audit-tables:demo` (`deploy/image/audit-tables.Dockerfile`), values
    `audit-tables-values.yaml`. It writes the audit topic to `nuna_audit.audit.events`, which the console's
    Audit page reads (below).
  - The maintenance worker (`catalog-maintenance`, D30): the chart `deploy/helm/nuna-maintenance`, release
    `nuna-maintenance`, image `nuna-maintenance:demo` (`deploy/image/maintenance.Dockerfile`), values
    `maintenance-values.yaml`, with its compaction Jobs in namespace `nuna-demo-compaction`
    (`maintenance-jobs.yaml`), running the engine matrix's Spark image, which `up` builds once. It keeps
    `lakehouse`'s tables (Maintenance, below).
  - `keycloak.yaml`: Keycloak in development mode, with realm `nuna` (`keycloak-realm.json`): the people
    below, their groups, and the clients of the console, kafbat, CloudBeaver, Studio, the audit table
    writer and the maintenance worker. Its signing key and the secrets of CloudBeaver, the writer and the worker
    come from Secret `keycloak-keys`, which `up` makes once per machine in
    `~/.nuna-demo/keycloak` (with `openssl`) and keeps. Only the demo runs it; the engine matrix on its own
    does not.
  - `singlestore.yaml`: image `nuna-demo-singlestore:<connector hash>`, the dev image with the change-feed
    connector built in and Studio signing people in through Keycloak (`singlestore.Dockerfile`). A changed
    connector replaces the pod, and `up` loads the new one again.
  - `trino.yaml`: Trino, image `trinodb/trino:484`.
  - `cloudbeaver.yaml`: CloudBeaver Community, image `nuna-demo-cloudbeaver:<hash>`:
    `dbeaver/cloudbeaver:26.2.4` without its version check, signing people in from a proxy's headers, and with
    `demo/CloudBeaverUsers.java`, which adds the people below as the pod starts, each with connections that
    sign in as them (`cloudbeaver.Dockerfile`). The proxy, `quay.io/oauth2-proxy/oauth2-proxy:v7.15.6`, runs
    beside it in the pod and signs people in through Keycloak.
  - `ports.yaml`: the front door, `nginx:1.30.6-alpine`, which serves the ports below over HTTPS (below), and
    their Services.
  - The lake writer runs as a job when needed (`lake-job.yaml`, image `nuna-demo-lake:local`).
  - `connect.yaml`: Kafka Connect, image `nuna-demo-connect:<hash>` (`connect.Dockerfile`), with the brokerage
    world's sink, and the CDC leg's connectors while it runs.
  - `brokerage-bootstrap.yaml` and `brokerage.yaml`: the brokerage world's bootstrap job and its generator, image
    `nuna-demo-brokerage:<hash>` (`brokerage.Dockerfile`; below).
  - `cdc.yaml`, only while `demo.sh cdc` runs: a second PostgreSQL, Oracle Database Free and CockroachDB.
- Ports on localhost, from Docker Desktop's LoadBalancer Services, all HTTPS: **8180** (Keycloak), **8181**
  (catalog and console), **8182** (kafbat), **8183** (SingleStore Studio), **8184** (Silo's console), **8185**
  (CloudBeaver), **8186** (Kafka Connect's REST API, with a password), **8187** (Silo's S3 API, for
  engines on other machines) and **8188** (Grafana, while `metrics` is on). `up` stops if something else holds one of them, such as a `kubectl port-forward`.
  An engine on another machine reaches the catalog on 8181 and the table files on 8187; the catalog vends
  `s3.endpoint` `http://s3:9000`, which resolves only inside the cluster, so that engine sets its own
  `s3.endpoint` to `https://<host>:8187`: the catalog vends one address per profile (D21, amended 2026-11-20).
- Memory, with `gen` and `ss` on (the core alone asks about 3.8 GB: it leaves out SingleStore's 1.5 GB, Kafka's
  640 MB, kafbat's 384 MB, Kafka Connect's 512 MB and the generator's 256 MB): about 6 GB of requests (SingleStore 1.5 GB, using about 2.2 GB of up to 3 GB; the catalog and
  Trino 768 MB each, Trino using about 1 GB of up to 1.25 GB; Kafka 640 MB; Keycloak and Silo 512 MB each; the front door 16 MB;
  PostgreSQL, kafbat and CloudBeaver 384 MB each, kafbat and CloudBeaver each using about 350 MB of up to
  768 MB; CloudBeaver's 384 MB is 368 MB for CloudBeaver and 16 MB for its sign-in proxy), plus the audit
  table writer's 384 MB, of up to 512 MB, and the maintenance worker's 192 MB, of up to 512 MB (6.6 GB in all),
  plus Kafka Connect's 512 MB, of up to 1 GB, and the brokerage world's generator's 256 MB, of up to 384 MB
  (7.3 GB in all), plus one job at a time: the lake writer 768 MB, the brokerage world's bootstrap 384 MB, or an
  engine up to 2 GB (Spark). Docker Desktop's Kubernetes has about 7.5 GB, which leaves about 200 MB beside Kafka
  Connect and the generator: too little for the lake writer, Spark, or a catalog server rolling to a new image (its
  new pod starts before the old one stops). So `up`, `rate` and `engines` stop both while they run and start them
  again after; `up` starts them last. A compaction Job asks for only 64 MB, of up to 1.25 GB, so it schedules
  beside everything: it uses memory the others leave unused (about 1.2 GB on 2026-11-24), and is the first pod
  stopped if that runs short. `engines` also stops Trino, CloudBeaver and the maintenance worker to make room for
  Spark (7.25 GB asked with Spark). The CDC leg asks for 1.75 GB (Oracle 1.25 GB, CockroachDB 384 MB, using about
  450 MB of up to 768 MB, its PostgreSQL 128 MB), so `cdc` stops Trino, the audit table writer, the maintenance
  worker and the generator, and `engines` and `rate` refuse to run beside it. With the leg up, the stack's requests fill
  Kubernetes to within about 20 MB, so CloudBeaver is replaced by stopping the old pod first.
- The data stays (backlog 237): PostgreSQL (the catalog's database, with the audit tables' catalog), Silo (the
  warehouse's files), Kafka (its topics, Kafka Connect's offsets among them) and SingleStore keep their data on
  volumes of their own, claims `postgres-data`, `s3-data`, `kafka-data` and `singlestore-data` in Docker Desktop's
  `hostpath` storage. `down`, `ss stop`, `gen stop`, a pod's restart and a Docker Desktop restart keep them, and the
  next `up` carries on from them: it writes the sample data only into a lake that lacks it. `wipe` deletes them
  (Stop, below). Resetting Docker Desktop's Kubernetes cluster deletes them too. Keycloak keeps no volume: it imports
  its realm, people and clients from `keycloak-realm.json` at every start, so a change made in its admin console
  lasts until its pod goes; the catalog names people by their user names, which the realm file fixes. The CDC
  leg's databases keep none either: `cdc stop` and `down` delete the leg, its topics and its offsets.

At the end it prints the console URL, <https://localhost:8181/ui/>, where the passwords are, and the tokens.

## HTTPS and the certificate

Every page is HTTPS. The front door (`k8s/ports.yaml`), nginx, takes ports 8180 to 8187, ends TLS with one
certificate and passes each request on over plain HTTP inside the cluster, where the stack's pods reach each
other by their Service names, as before. Studio alone is HTTPS behind it too, with the same certificate: it
builds the address Keycloak returns its SAML sign-in to from the request it gets, and Keycloak refuses an
`http://` one. Keycloak and kafbat build their addresses from the front door's
`X-Forwarded` headers, and it turns any `http://` redirect a service sends into `https://`. No client
certificates: people sign in through Keycloak, and services send tokens.

The certificate is in `~/.nuna-demo/tls`, published as Secret `demo-tls`:

| File | What it is |
|---|---|
| `tls.crt` | the server certificate, then any intermediate certificates (PEM) |
| `tls.key` | its private key (PEM, not encrypted) |
| `ca.crt` | the certificates of the CA that issued it (PEM), which `demo.sh`'s own calls trust |

- **At home** `up` makes them: a demo CA (`ca.key` beside it), which can only vouch for `localhost` and
  127.0.0.0/8 (its name constraints, so its key cannot vouch for any other site), and a certificate from it
  for `localhost`, made again a month before it ends. A browser warns until you trust the demo's CA in your
  Windows user's trust store, once (Windows asks you to confirm; `certmgr.msc`, Trusted Root Certification
  Authorities, removes it):

  ```bash
  certutil -user -addstore Root ~/.nuna-demo/tls/ca.crt
  ```

- **At work**: put work's certificate in the folder and set `NUNA_DEMO_HOST` to its name
  (`docs/install-at-work.md`, section 10). Every address the demo gives, Keycloak's issuer and every sign-in
  address included, is then `https://<NUNA_DEMO_HOST>:<port>`.
- `up` refuses a certificate that does not name the host, has expired, is not its key's, or was not issued
  by `ca.crt`. A new certificate replaces the front door's pod; a new host also replaces Keycloak, kafbat,
  CloudBeaver, the catalog and SingleStore, whose image names Studio's SAML address.

### The catalog's database

The catalog signs in to its PostgreSQL by a client certificate, over TLS, and no PostgreSQL role has a password
(backlog 281), the shape nuna's own deployment takes with work's PKI (`deploy/README.md`, "The database"). Only the
catalog reaches PostgreSQL over the network; everything else (`up`'s setup, the readiness probe,
`kubectl exec deployment/postgres -- psql -U catalog -d catalog`) uses the pod's own socket, which
`k8s/stack.yaml`'s `pg_hba.conf` trusts. `up` makes the files once per machine in `~/.nuna-demo/db-tls` (`db_tls`), and
`down` and `wipe` keep them:

| File | What it is |
|---|---|
| `ca.crt`, `ca.key` | a database CA of the demo's own, which only PostgreSQL and the catalog trust, and only for this |
| `server.crt`, `server.key` | PostgreSQL's certificate, for Service `postgres` by each of its names in the namespace |
| `client.crt`, `client.key`, `client.pk8` | the catalog's, common name `catalog`; `client.pk8` is the key as PKCS #8 DER, the form the JDBC driver reads |
| `initdb-password` | the password PostgreSQL's image asks for to make its first role: random, and taken away again |

They are published as Secret `postgres-tls` (PostgreSQL's) and Secret `catalog-db-tls` (the catalog's, the chart's
`databaseTls`). Each certificate is made again a month before its end, which replaces both pods. On every `up`,
once the catalog serves, `up` checks and says: every network connection is TLS signed in as `CN=catalog`
(`pg_stat_ssl`), one without a certificate is refused, and no role has a password. The engine matrix on its own
makes the same two Secrets from its private CA.

## Sign in

The console offers two ways in:

- **Sign in with localhost:8180**: Keycloak's login page, by username and password, standing in for a
  directory (AD, Entra ID) until one is configured. The console signs in as it would with any IdP (OIDC
  authorization code with PKCE, `nuna.ui.oidc`), and the catalog trusts Keycloak's realm as a second issuer.
  It names a user by `preferred_username`, so `ana` from Keycloak is the same user as `ana`'s token below.
- **The token box** (`nuna.ui.dev-token-login=true`, off by default): paste a token minted by `demo/Mint.java`.
  Machine users (`singlestore`, `lake`, `brokerage`, `cdc`, `engine-matrix`, `trino`) have tokens only.

| User | Password | Group | What the catalog lets them do |
|---|---|---|---|
| `ana` | in `~/.nuna-demo/people` | `demo-analysts` | create and modify on both catalogs: create namespaces, tables and views, read and write data, change, rename and drop them; make, merge and delete branches and tags, as `up` does as her (Branches and tags of the catalog, below) |
| `gary` | in `~/.nuna-demo/people` | `demo-grant-admins`, `demo-auditors` | grant admin: list, grant and revoke, and read the audit trail; no data access, so loading a table is refused. As an auditor, the console's Audit page, and the audit table itself (below) |
| `stella` | in `~/.nuna-demo/people` | `demo-stewards` | manage-tags on `lakehouse`: classifications, owners, legal holds; select on both catalogs |
| `oscar` | in `~/.nuna-demo/people` | `demo-operators` | platform operator: DR freeze |
| `presenter` | in `~/.nuna-demo/people` | all five groups above, and `demo-presenters` | everything, in one sign-in, for showing the demo (below) |
| `singlestore` | token only | `demo-loaders` | create and modify; SingleStore's pipelines use it, both ways |
| `lake` | token only | `demo-loaders` | create and modify; the lake writer, standing in for another engine |
| `brokerage` | token only | `demo-loaders` | create and modify; the brokerage world's generator, whose bootstrap makes its namespaces, tables and views |
| `cdc` | token only | `demo-loaders` | create and modify; Kafka Connect's Iceberg sinks: the brokerage world's `brokerage-lake`, and the CDC leg's |
| `engine-matrix` | token only | `engine-clients` | create and modify on catalog `engines`; `demo.sh engines` runs the engines with it |
| `service-account-nuna-audit-tables` | Keycloak client `nuna-audit-tables`, client-credentials grant | `demo-audit-writers` | create and write on `nuna_audit` only: the audit table writer |
| `trino` | token only | `demo-engines` | nothing in a catalog: Trino's own sign-in, which reads the catalogs' config. Its client, `trino-demo`, is registered as a trusted engine (D17), so it sees every catalog but nothing in it (D18); queries reach the catalog with the token of whoever runs them, which Trino's own vouches for, so they read flagged tables with their row filter and column masks (D20) |

Catalog `engines` holds the engine matrix's tables. Separation of duties (D18) keeps each group to one duty
plus reading, and lets a grant admin or a platform operator hold no grant at all: `gary` and `oscar` see no
data in the catalog, and of the four people only `ana` changes it. Try the console as `ana` and as `gary`.
Every refusal is the server's, shown as it answered. The console's "Sign out" forgets its token and ends
Keycloak's session too (D16): Keycloak asks "Do you want to log out?" (the console keeps no ID token to skip
that), then sends you back to the console, and the next sign-in asks for a password, so it changes user.
kafbat and CloudBeaver keep their own sign-in until you sign out of them, but sign in again only with a
password. Keycloak's own page, <https://localhost:8180/realms/nuna/account>, shows who is
signed in there; its users hold the realm's default roles (`default-roles-nuna` in
`k8s/keycloak-realm.json`), without which that page fails with "Something went wrong". Keycloak's users and
sessions go with its pod.

`presenter` may do everything, in every UI, for showing the demo without changing user (Paul, 2026-11-25: "Truly
everything"; D18 amended; backlog 273). Keycloak puts them in all five groups, so the console shows data, grants,
the audit trail and the Audit page, governance and the DR freeze together, and in `demo-presenters`, which holds
every privilege on every catalog, `nuna_audit` included (by SQL, so the separation of duties never sees it). The
catalog also names them in `nuna.security.own-group-principals`, so they grant to and set row filters and column
masks for their own groups, which no one else may, and in `nuna.security.maintenance-principals`, so they claim
and complete purges and read a flagged table's files without Trino, as the maintenance worker does. Separation of
duties between groups still holds for them: a grant that would give one group two duties is refused. Outside the
catalog `presenter` administers kafbat (as `demo-operators`), CloudBeaver (team `admin`, with their own four
connections), SingleStore and Studio (all privileges, with grant option), Silo (`consoleAdmin`), realm `nuna` in
Keycloak's admin console (`realm-admin`), and the CDC leg's databases (superuser, DBA, admin). CloudBeaver shows
its administrators every person's connections, so `oscar` or `presenter` can show each person's view there without
changing user. None of this is for work, where the directory keeps one person to one duty.

No one else grants to a group they belong to: the catalog refuses it as separation of duties ("no one grants to a
group they belong to"). So `gary` cannot grant to `demo-grant-admins` or `demo-auditors`. To show that refusal,
sign in as `gary`; to show a grant, sign in as `gary` or `presenter` and grant to a new group
name, such as `select` on a namespace to `demo-readers`: grants go to group names, and the catalog does not check
that the directory has the group, so Keycloak needs no change. To show the separation-of-duties refusal itself,
sign in as `gary` and grant `manage-tags` to `demo-analysts`: data and tags never share a group. In the console,
Grant access (on the Grants page, and at the top of every Access tab) says each of these before Grant is pressed:
what the grant will give, that it is held already, or why it is refused, and a refused grant cannot be sent.

### The same people in the other UIs

The four people sign in once, through Keycloak, and every UI that has users lets them in after that without
asking for a password. Outside the console, the catalog sees them through Trino, in CloudBeaver: each person's
Trino connection sends their own token, so the catalog lets them do there what it lets them do in the console.

| UI | How they sign in | `ana`, `gary`, `stella` | `oscar` |
|---|---|---|---|
| kafbat, <https://localhost:8182> | through Keycloak, like the console (client `kafbat`) | administer topics: create them, change their settings, produce and clear messages, delete them; only read the topics the demo runs on (below); read consumer groups, settings and Kafka Connect's connectors | administers Kafka: topics, messages, consumer offsets, ACLs, and the connectors: configs, restart, pause, create and delete |
| SingleStore Studio, <https://localhost:8183> | through Keycloak with SAML (client `singlestore-studio`): type the username and choose "Sign in with SAML". As SingleStore users (`demo/singlestore/users.sql`) | `ana`: read, insert, update, delete, create, alter and drop in every database; `gary`: all privileges, with grant option; `stella`: `SELECT` on every database. `ana` and `stella` also hold `SHOW METADATA` | all privileges, with grant option |
| CloudBeaver, <https://localhost:8185> | through Keycloak (client `cloudbeaver`), straight from the page. As CloudBeaver users (`demo/CloudBeaverUsers.java`) | four connections of their own, which sign in as them: to the catalog through Trino, and to the CDC leg's databases | CloudBeaver's administrator, with four connections of their own and everyone else's |
| Silo's console, <https://localhost:8184> | through Keycloak (client `silo`): choose "Keycloak" | `ana` browses and downloads the `warehouse` bucket's files, read-only (Silo's policy `warehouse-read`); `gary` and `stella` refused | the server's health, metrics, logs and traces (Silo's policy `diagnostics`), and no bucket or object |
| Keycloak's admin console for realm `nuna`, <https://localhost:8180/admin/nuna/console/> | through Keycloak, its own client | refused ("forbidden") | reads the realm: its users, groups, sessions, clients and events; changes nothing |
| Keycloak's account page, <https://localhost:8180/realms/nuna/account> | through Keycloak | their own account and sessions | the same |

kafbat's roles go by Keycloak group: `demo-operators` administers Kafka, and `demo-analysts`,
`demo-grant-admins` and `demo-stewards` administer topics, except the ones the demo runs on, which they only
read: the catalog's audit and change topics (`nuna.*`), the Connect worker's `connect-*`, the brokerage world's
`brokerage.*` and its sink's `brokerage-lake-control`, and any name starting with `_`. The CDC leg's change,
control and schema-history topics stay theirs to administer. kafbat
records every change made through it, refused ones included, with who made it, in topic `__kui-audit-log`, which
only `demo-operators` read in kafbat, and in its log (`kubectl -n nuna-demo logs deployment/kafka-ui | grep -w
audit`). Unlike the console's, kafbat's sign-out leaves Keycloak's session.

How CloudBeaver and Studio sign in through Keycloak, neither of which CloudBeaver Community or Studio does on
its own terms, and how Silo's and Keycloak's own consoles do:

- **CloudBeaver**: oauth2-proxy (`quay.io/oauth2-proxy/oauth2-proxy:v7.15.6`) runs beside it in its pod and
  takes every request on 8185. It signs the person in with Keycloak (OIDC with PKCE, a confidential client)
  and passes the request on with the person's username and CloudBeaver teams in headers: team `admin` comes
  from Keycloak role `admin` of client `cloudbeaver`, which group `demo-operators` holds. CloudBeaver signs a
  session in from those headers (its reverse-proxy sign-in, `k8s/cloudbeaver-signin.conf`), so it listens only
  on the pod's 127.0.0.1, has no password sign-in, and adds no users of its own: someone Keycloak signs in
  who is not one of the four is refused. The proxy sets those headers, and drops `X-Role` and `X-Role-TE`, on
  every request: CloudBeaver also takes teams from `X-Role` and a role from `X-Role-TE`, and before the proxy
  dropped them, any person who sent `X-Role: admin` became CloudBeaver's administrator, with everyone's
  connections (checked 2026-11-15, before and after). CloudBeaver reads the headers only as a session opens,
  so its Logout ends CloudBeaver's session first, then the proxy's and Keycloak's; signing out of Keycloak
  anywhere else leaves CloudBeaver signed in as before, for up to 10 hours, like kafbat.
- **Studio**: Studio signs in with SAML when its cluster has a SAML setting (`k8s/singlestore.Dockerfile`).
  It sends the browser to Keycloak, which posts back a signed SAML response, and Studio signs in to
  SingleStore as the username typed, with the response as the password. SingleStore checks it (settings in
  the master's `memsql.cnf`): Keycloak's signature, against the realm's certificate; the audience
  `singlestore-studio`; the recipient; and that its NameID, the Keycloak username, is the user signing in.
  So signed in to Keycloak as `ana`, typing `oscar` is refused ("the SAML NameID is not valid"), and an
  edited response fails the signature. The people have no SingleStore password, and Studio offers no
  password sign-in, so `root` uses `demo/demo.sh sql`. The response travels over HTTPS to Studio, and
  SingleStore accepts it again for an hour (the assertion's lifetime, as long as a console token), so whoever
  gets hold of one can sign in as that person meanwhile. That is accepted: the demo holds only test data.
- **Silo's console**: Silo, a MinIO fork, signs people in through Keycloak itself (OpenID Connect, a
  confidential client), set by `k8s/silo-signin.yaml`. What it lets them do comes from the ID token's claim
  `policy`, the person's roles of Keycloak client `silo`, each named after a Silo policy: group
  `demo-operators` holds `diagnostics`, Silo's built-in policy without bucket or object rights, so the platform
  operators see no data in the store, as in the catalog (D18). `ana` and `presenter` hold `warehouse-read`
  (Paul, 2026-11-23: "i need ana and presenter to access silo"), the demo's own policy
  (`k8s/silo-warehouse-read.json`, made by `up` after the seed job): list the buckets, browse and download the
  `warehouse` bucket's objects, write nothing. What they read there goes round the catalog's grants,
  governance and audit, as reading the store directly does; nothing they do there can change a table. `presenter`
  holds both, and `consoleAdmin`, Silo's own administrator policy: every bucket and object (backlog 273). Anyone else has no `policy` claim, and Silo refuses them. Silo reads Keycloak's discovery document inside the cluster, where Keycloak names its own
  `http://keycloak:8080` for tokens and keys (`--hostname-backchannel-dynamic`), and only as it starts, so its
  pod waits up to 5 minutes for Keycloak first. The store's root, `nunatest`, keeps its access-key sign-in.
- **Keycloak's admin console**: `demo-operators` hold realm `nuna`'s view roles (`view-realm`, `view-users`,
  `view-clients`, `view-events`, `view-identity-providers`, `view-authorization`), so `oscar` reads
  the realm's admin console without a second sign-in, and changes nothing; `presenter` also holds `realm-admin`
  there, and may change the realm, users and groups included (backlog 273). Otherwise changing the realm stays
  with Keycloak's `admin` (below), in the master realm: whoever could change it could put themselves in any group,
  which separation of duties rules out for the other people in the demo. Changes last until Keycloak's pod goes:
  it imports the realm file at every start (D52).
- **Keys**: the realm signs with a fixed key, not one Keycloak makes at each start, because SingleStore reads
  its certificate only as it starts. `up` makes the key, its certificate and CloudBeaver's client and cookie
  secrets once, with `openssl`, in `~/.nuna-demo/keycloak`, and loads them as Secret `keycloak-keys`. To
  change them, delete that folder and run `up`: Keycloak, CloudBeaver and SingleStore restart with the new
  ones.
- **At work**: this Keycloak and its keys are stand-ins. CloudBeaver's sign-in proxy, Studio's SAML setting,
  SingleStore's certificate and Silo's console sign-in get pointed at work's identity provider, with TLS from work's certificate
  authority, and that can only be done on site.

No password is in the repository (backlog 282). `up` makes each once per machine and keeps it: the people's in
`~/.nuna-demo/secrets/person-<name>`, which it lists, one person a line, in `~/.nuna-demo/people`; the rest in
`~/.nuna-demo/secrets`, one file each. They reach the pods only through Secrets (`demo-secrets`, `s3-root`,
`singlestore-root`, `connect-auth`) and the charts only by `--set-file`. The people's are three groups of four
lower-case letters, to type. To change one, delete its file and run `up` (for the CDC leg's, `cdc` again);
SingleStore, Silo, Keycloak and Grafana take the new one on their kept volumes.

Accounts that run the demo rather than people in it:

| Account | Password | For |
|---|---|---|
| Keycloak `admin` | `~/.nuna-demo/secrets/keycloak-admin` | Keycloak's admin console, <https://localhost:8180/admin>, in the master realm: the only account that changes realm `nuna` |
| SingleStore `root` | `~/.nuna-demo/secrets/singlestore-root` | `demo/demo.sh sql` and the demo's setup; Studio takes no passwords |
| Silo `nunatest` | `~/.nuna-demo/secrets/silo-root` | the S3 store's root, and Silo's console, MinIO's, on <https://localhost:8184>, by its access-key form: every bucket, its objects, users, policies and settings |
| PostgreSQL `catalog` | none: the catalog's client certificate | the catalog's database, which only the catalog reaches over the network; not published outside the cluster (The catalog's database, above) |
| PostgreSQL `backoffice` | `~/.nuna-demo/secrets/backoffice` | the CDC leg's PostgreSQL, its owner; `demo/demo.sh psql` |
| Oracle `treasury` | `~/.nuna-demo/secrets/treasury` | the CDC leg's Oracle schema; `demo/demo.sh sqlplus` |
| Oracle `SYS`, `SYSTEM` | `~/.nuna-demo/secrets/oracle` | the CDC leg's Oracle (`ORACLE_PASSWORD`) |
| Oracle `c##dbzuser` | `~/.nuna-demo/secrets/debezium` | Debezium's LogMiner user |
| CockroachDB `root` | none: the node's client certificate | the CDC leg's CockroachDB; `demo/demo.sh cockroach` |
| CockroachDB `debezium` | `~/.nuna-demo/secrets/debezium` | Debezium's login: `SELECT` and `CHANGEFEED` on `positions.holdings` |

Tokens are valid for 7 days, and `up` mints new ones only when they are about to expire. Print them again
(re-minted if they are about to expire; SingleStore's running pipelines are then replaced and Trino,
CloudBeaver, Kafka Connect and the brokerage world's generator are restarted, so they carry the new ones):

```bash
demo/demo.sh token
```

Tokens are in `~/.nuna-demo/tokens/<user>.jwt`. A curl call as a user:

```bash
curl --cacert ~/.nuna-demo/tls/ca.crt --ssl-revoke-best-effort \
  -H "Authorization: Bearer $(cat ~/.nuna-demo/tokens/ana.jwt)" "https://localhost:8181/v1/lakehouse/namespaces"
```

Git Bash's curl checks certificates with Windows, which fails the demo's CA, as it names no revocation list,
unless told to check revocation only where it can (`--ssl-revoke-best-effort`).

## What is loaded

Catalog `lakehouse`, written by `demo/LakeWriter.java` (the Iceberg Java library, through the catalog with
vended credentials, as any engine would):

- `markets.equities.eu.xetra.trades`: v2, partitioned by day, sorted by symbol; five appends, a row-level
  delete, two added columns (one a nested struct), a second partition spec, and Iceberg's own tag
  `eod-2026-11-09` and branch `surveillance-review` in the table; classified `internal` and under a legal hold.
- `markets.fx.rates` (v3, evolved), `markets.equities.us.quotes` (v3) and `reference.countries` (v2, under a
  DR freeze).
- No table is closed until classified (D27) for now: the toy `finance.ledger` tables that showed it are gone,
  and item 152 brings it back on `brokerage.party.beneficial_owners`.
- View `markets.equities.eu.xetra_daily`, with two versions, in SingleStore's dialect.
- Grants by SQL for the groups, plus two made through the API by `gary`.
- Trino's client, `trino-demo`, registered as a trusted engine through the API by `oscar`, on every `up`.
- The catalog's branch `dev`, with the next day's trades and rates committed on it, and its tag `sample`, made by
  `ana` through the API (Branches and tags of the catalog, below).

Written by SingleStore's change feed: `markets.equities.eu.xetra.orders`, the copy of `ops.orders`.

Written live by the brokerage world (below): everything under `brokerage`.

### The brokerage world

A simulated US broker-dealer (D48, `docs/design/brokerage-world.md`, module `demo-datagen`), running while the
switch `gen` is on (D50): its generator books every financial transaction through the demo kit's posting engine and sends the world's
events to Kafka and SingleStore, and the demo's pipelines bring them into Iceberg as they happen.

- `gen` starts SingleStore if `ss` is on and Kafka if `kafka` is, and runs `brokerage-bootstrap`, a job: it makes
  the world's namespaces, tables and views in `lakehouse` as user `brokerage`, and its Kafka topics while `kafka` is
  on. Then `gen` sets their governance as `stella` (below), starts Kafka Connect and registers the sink (with
  `kafka` on) or the change feeds of the world's SingleStore tables (with it off), and starts the generator,
  Deployment `brokerage`, last (`demo/demo.sh brokerage logs` follows it). Its settings are environment variables in `k8s/brokerage.yaml` and `k8s/brokerage-bootstrap.yaml`:
  `BROKERAGE_ACCOUNTS=1000`, `BROKERAGE_CATCH_UP_DAYS=3`, `BROKERAGE_SEED=20261122`.
- The real clock: the world's events arrive at their own time of day in New York, as a live firm's would. The
  three catch-up days and today's earlier hours go at once when the generator starts; then each event waits for its
  time. A trading day's events fall between 07:00 and 17:00, so the world is quiet overnight, over weekends and on
  market holidays (the log says `quiet until <time>`). Every minute the generator also asks Kafka Connect whether
  the sink's task has failed, as it does when the catalog restarts, and restarts it if so.
- Kafka: the generator writes plain JSON to eleven topics, and Kafka Connect's Iceberg sink `brokerage-lake`
  (`demo/brokerage/connector.json`, as user `cdc`) routes each record to the table its `lake_table` field names,
  committing every 15 seconds. The tables must exist: the sink neither makes nor changes them.

  | Kafka topic | Iceberg table in `lakehouse` |
  |---|---|
  | `brokerage.reference.calendar`, `.securities` | `brokerage.reference.calendar`, `.securities` |
  | `brokerage.markets.bars-5m`, `.prices-eod` | `brokerage.markets.bars_5m`, `.prices_eod` |
  | `brokerage.party.accounts` | `brokerage.party.accounts` |
  | `brokerage.ledger.chart-of-accounts`, `.booking-schemes`, `.financial-transactions`, `.journal-lines`, `.posting-rejections`, `.balances-eod` | `brokerage.ledger.chart_of_accounts`, `.booking_schemes`, `.financial_transactions`, `.journal_lines`, `.posting_rejections`, `.balances_eod` |

- SingleStore: the generator writes the world's orders to `brokerage.orders` (`demo/singlestore/brokerage.sql`),
  and the change feed `brokerage.brokerage_orders_to_lake` copies each change into `brokerage.trading.orders`,
  committing every 15 seconds, with its receipts in `brokerage.lake_commits`, as `ops.orders_to_lake` does.
- Governance, once per lake: `brokerage.reference`'s tables are `public`, `brokerage.markets`' `internal`,
  `brokerage.party.accounts` `restricted` and `brokerage.ledger`'s tables and views `confidential`; namespaces
  `brokerage.party` and `brokerage.ledger` are regulated, marked after their tables are classified, and
  `brokerage.trading` is `internal`. `brokerage` and `brokerage.trading` have owners. The grants on all of
  `lakehouse` cover them: `ana` and `stella` read them, in the console and in Trino.
- Routes (D50, amended 2026-11-24; backlog 238): the generator writes through the ways the switches give it, and
  takes the other way when one goes off.

  | `ss` | `kafka` | The orders | Every other feed |
  |---|---|---|---|
  | on | on | SingleStore, to `brokerage.trading.orders` | Kafka, to its table above |
  | off | on | topic `brokerage.trading.order-events`, through the sink to `brokerage.trading.order_events` | Kafka |
  | on | off | SingleStore | a SingleStore table of its own in database `brokerage` (`demo/singlestore/brokerage-feeds.sql`), keyed by `seq`, whose change feed `brokerage_<table>_to_lake` copies it to `brokerage.singlestore.<table>` |

  The tables are made from `demo-datagen` (`Main sql demo/singlestore`; a test checks the committed files match).
  Before a way goes off, demo.sh stops the generator and runs the bootstrap job again as `drain kafka` or
  `drain singlestore`, which waits (up to three minutes) until what went that way is all in the lake; if it is not,
  the way stays on. The generator then starts on its new route and counts what each way carried, so a restart
  still sends nothing twice. Each of the eleven change feeds is a Java extractor of its own in SingleStore's memory,
  which is why they run only while Kafka is off. `brokerage.singlestore` is `restricted` and regulated, as it holds
  copies of the party and ledger tables.
- `gen stop` stops the generator, waits 20 seconds for the sink's last commit, then stops Kafka Connect, Kafka
  and kafbat unless the CDC leg runs. Kafka's topics stay on its volume, so the next `gen` carries on from them:
  the generator replays the world without sending what it sent before, and catches up on the days it missed.
  `wipe` deletes the topics with the lake; if Kafka alone lost them, the generator stops and says so (`wipe` and
  `up` start a new world).
- Memory: the generator asks for 256 MB, Kafka Connect 512 MB, the bootstrap job 384 MB while it runs. `cdc`
  pauses the generator; `up`, `rate` and `engines` stop it and Kafka Connect while they run.

### The audit tables and the Audit page

Catalog `nuna_audit` holds the queryable audit table `audit.events` (D23, `catalog-audit-tables/README.md`): one row
per record of the catalog's audit log `nuna.audit`, every catalog's, since the writer's pod started. The catalog
writes every audit record to that log and sends its logs over OTLP to the writer's receiver
(`http://nuna-audit-tables:4318/v1/logs`, with the token in `~/.nuna-demo/keycloak/audit-otlp-token`); the writer
keeps `nuna.audit`'s records (D23, amended 2026-11-23: no Kafka). The audit
table writer fills it, committing every minute, so it runs about a minute behind; the console's Audit page (the
feed, its filters and the last day's counts, D45) reads it back and says how far it reaches.

- Readers: group `demo-auditors`, which holds `select` on `nuna_audit` and nothing that changes data: `gary` and
  `presenter`. The Audit page needs that (Paul, 2026-11-22: "Audit readers only"), so it refuses `ana`, `stella`
  and `oscar`, and catalog `nuna_audit` is listed to no one else. The readers' group may not be
  `demo-grant-admins` itself: the catalog grants a grant admin group nothing (D18), so `gary` is in both.
- Writer: Keycloak's service account of client `nuna-audit-tables`, `service-account-nuna-audit-tables`, in
  group `demo-audit-writers`, which holds `create` and `write` on `nuna_audit` only. It signs in with the
  client-credentials grant and a client secret `up` makes once in `~/.nuna-demo/keycloak`. Its own requests are
  left out of the table.
- The catalog makes both grants as it starts (`nuna.audit.tables.*` in `demo-values.yaml`).
- While the CDC leg runs beside SingleStore (`ss` on), the writer is stopped for its memory: the Audit page misses
  the records of that time, which the catalog's log (`kubectl -n nuna-demo logs deployment/nuna-catalog`) still
  holds. `cdc stop` starts it again. With `ss` off it keeps running beside the leg.
- Nothing prunes the table's snapshots: the maintenance worker keeps only `lakehouse` (below), and the table's data
  lasts as long as Silo's pod.

### Maintenance

The maintenance worker (`catalog-maintenance/README.md`, D30) keeps catalog `lakehouse` tidy, so the console's
Overview and Maintenance pages show a working estate: every table's mark, the queue, the night's jobs and the
nights' trends. Every `up` turns every job on in `lakehouse`'s policy, as `ana`, and starts the worker.

- The policy (catalog level; Governance, a table's Settings tab): snapshot expiry, orphan removal, compaction and
  manifest rewrite all on. Snapshots go once an hour old, the least the catalog allows, keeping each table's newest
  5; the rest as built in: a table needs compaction once a partition holds 5 data files under 384 MB (75% of the
  512 MB target), or more delete files than 30% of its data files, or any equality delete; manifests are rewritten
  at 100; orphans are files unreferenced for 14 days.
- The cleanup window is open all day, each night ending as the next begins at 12:00 in New York
  (`maintenance-values.yaml`). So whenever the demo comes up, the worker, which `up` starts last, once the change
  feeds and the brokerage world's sink have committed, assesses every table at its first scan, queues the ones that
  need compaction, and compacts them one at a time, a Spark Job each in namespace `nuna-demo-compaction`; at noon
  the next night assesses them again. It scans every 5 minutes, expiring snapshots and rewriting manifests at each
  scan, and starts the next Job as the last ends. Each table is assessed and compacted once a night at most. The
  worker keeps the night's assessments in memory, so to assess every table again now, start it again:
  `kubectl -n nuna-demo rollout restart deployment/nuna-maintenance`.
- The brokerage world's Kafka Connect sink and SingleStore's change feeds commit every 15 and 5 seconds, so their
  tables gather small files and snapshots all day: they are the queue. `markets.equities.eu.xetra.orders` and
  `brokerage.trading.orders` carry equality deletes, which go first.
- What it leaves alone: branches and tags keep their snapshots and files (the catalog's `dev` and `sample`, and
  `xetra.trades`' own `eod-2026-11-09` and `surveillance-review`); the legal hold on `xetra.trades` and the DR freeze
  on `reference.countries` stop its expiry and orphan scans there, as the catalog's work list leaves them out.
- Its identity: Keycloak's service account of client `nuna-maintenance`, `service-account-nuna-maintenance`, the
  catalog's maintenance principal (`demo-values.yaml`), in group `demo-maintenance`, which holds `write` on
  `lakehouse`. Its compaction Jobs sign in the same way. It deletes from Silo as its own user, `nuna-maintenance`,
  which may only list and delete beneath the tables: the catalog's profile `s3` names it as its
  `maintenance-principal` (`demo-values.yaml`), so the catalog generates its delete policies, and `up`, once the
  catalog serves, reads them as `oscar` from `GET /management/v1/maintenance/storage-setup` and applies the `to`
  policy, `nuna-worker-s3`, to the user, as a platform operator would at work (`deploy/README.md`, "Delete
  rights"). `up` makes the user and the policy again each time, so a changed key or prefix takes effect; if the
  policy cannot be read or applied, `up` says so and carries on, and the worker's scans are refused until the next
  `up`.
- The `except` policy, which denies delete beneath the prefixes to every other user, is not applied in the demo:
  the demo's catalog signs in to Silo as its root user, `nunatest`, whom no policy binds. At work the catalog has a
  user of its own, which the `except` policy must hold (`deploy/README.md`).
- A second catalog's bucket is set up the same way, from the console as `oscar`: Maintenance's "What the worker
  needs to maintain <catalog>" panel says which step is missing, and What to set up, on it and on Service's Worker
  storage panel, gives the worker's settings for a new store and the policies with the commands that apply them
  (items 252, 254).
- A file it queues for deletion waits out the 7-day PITR window first, so the first deletions come a week after
  the data was written. The database outlives `down` and the overnight run's rebuild, so the 30-night trends
  build up; `wipe` starts them again.
- Memory: the worker asks for 192 MB, of up to 512 MB; a compaction Job asks for 64 MB, of up to 1.25 GB, with
  Spark's driver at 640 MB (Start, above). `cdc` and `engines` stop the worker and any Job running, and start the
  worker again after.

## SingleStore

```bash
demo/demo.sh sql
```

Or Studio on <https://localhost:8183>, as one of the people above, through Keycloak. Three databases, and a
fourth once the engines have run:

- `ops`: SingleStore's own tables. `ops.orders` is copied into Iceberg as it changes.
- `lake`: tables loaded from Iceberg: `xetra_trades` and `countries` once, `fx_rates` continuously, and a
  view `xetra_daily`.
- `brokerage`: the brokerage world's orders, `brokerage.orders`, which its generator writes and the change feed
  `brokerage.brokerage_orders_to_lake` copies into Iceberg (`brokerage.trading.orders`) as they change.
- `nuna_m8`: the engine matrix's SingleStore check, Spark's shared tables loaded from catalog `engines`.

### SingleStore to Iceberg: the change feed

The pipeline `ops.orders_to_lake` is a connector pipeline running `singlestore-change-feed` (no Kafka). It
reads `OBSERVE` on `ops.orders` and commits to `markets.equities.eu.xetra.orders` every 5 seconds while
there are changes: new rows as data files, and equality deletes on the primary key for updated and deleted
rows. Each commit records SingleStore's offsets in the Iceberg snapshot, so the feed resumes from Iceberg
after any failure, with nothing copied twice or skipped. Each commit's receipt lands in `ops.lake_commits`.

```sql
UPDATE ops.orders SET status = 'FILLED', updated_at = NOW(6) WHERE order_id = 1005;
-- a few seconds later
SELECT kind, rows_written, keys_deleted, committed_at FROM ops.lake_commits ORDER BY committed_at;
```

Then open the `orders` table in the console: each receipt is a snapshot there.

- If SingleStore no longer holds its log from where the copy ended (the feed was stopped across several
  `SNAPSHOT DATABASE`s), the feed takes a new snapshot of the table and replaces the copy. The commit is
  `resnapshot`, and `gap_after` (in `ops.lake_commits` and in the Iceberg snapshot summary) holds the offsets
  it could not continue from.
- No pipeline may load `markets.equities.eu.xetra.orders`: its rows would come back into SingleStore. The
  feed checks every minute and stops with an error naming the pipeline if one does.
- `ALTER` and `DROP` on `ops.orders` wait while the feed runs (`OBSERVE` holds the table). Stop the pipeline
  first; a column added at the end is added to the Iceberg copy when the feed starts again.
- If the feed fails (the loop guard, a table change it cannot follow), the pipeline stops in `Error`.
  `START PIPELINE ops.orders_to_lake` resumes it from the offsets in Iceberg.
- If SingleStore fails to write a batch of receipts (it ran out of memory, say), the commits are already in
  Iceberg. SingleStore starts the feed again from the last receipt it stored, and the feed sends the later
  ones again, read from the snapshots, so `ops.lake_commits` still lists every commit.
- A table that is only ever inserted into may have a primary key that holds more than its shard key, which the
  feed otherwise refuses (D35 rule (1)): set `"singlestore.append-only": "true"` in the pipeline's CONFIG. The
  first `UPDATE` or `DELETE` on it then stops the pipeline in `Error` before that transaction reaches Iceberg, and
  every start stops at the same change. To copy the table afresh: stop the pipeline, drop the Iceberg copy, run
  `SNAPSHOT DATABASE` on the table's database, and start the pipeline. Dropping the copy alone is not enough: a
  start with no offsets reads SingleStore's latest snapshot and the log after it, which still holds the change.

### Iceberg to SingleStore: Iceberg pipelines

```bash
demo/demo.sh rate
```

appends an EURUSD rate to `markets.fx.rates` through the catalog; the pipeline `lake.fx_rates_feed` loads it
into `lake.fx_rates` within a few seconds. Kafka Connect and the brokerage world's generator stop while the lake
writer's job runs, for its memory, and start again after.

SingleStore reserves each pipeline extractor's Java heap from its own memory, and keeps idle extractors
pooled. An Iceberg pipeline's data workers default to 500 MB each, so two loads and a feed are enough to stop
all writes in the demo's 3 GB pod. `seed.sql` caps them at 128 MB
(`pipelines_iceberg_data_workers_heap_size`). Each change feed's extractor gets 384 MB
(`pipelines_cdc_java_heap_size`), so the brokerage world's feed, beside `ops.orders_to_lake`, reserves another
384 MB; how close that brings the pod to its limit has not been measured yet.

## Trino and CloudBeaver

CloudBeaver is on <https://localhost:8185>: a database navigator and SQL editor in the browser, where the
people above sign in through Keycloak. Each person has four connections of their own, which sign in as them, and sees only
those: "nuna (Trino, as ana)" for `ana`, and the three CDC sources below. The Trino connection reaches a Trino
whose catalogs `lakehouse`, `lakehouse_dev` (`lakehouse`'s branch `dev`, below) and `engines` are nuna's. It sends
Trino the person's own token as the extra
credential `token`, and Trino hands it to the catalog with each request (`iceberg.rest-catalog.session=USER`).
So the catalog sees the person, and each one reads and changes what they may in the console, with the
storage credentials the catalog vends them:

- `ana` reads and changes data and definitions: `CREATE SCHEMA` and `TABLE`, `INSERT`, `UPDATE`, `DELETE`,
  `ALTER` and `DROP` all work, with the write credentials the catalog vends her.
- `stella` reads. `gary` and `oscar` see the namespaces and tables listed, but every read fails with
  "Failed to load table", since the catalog refuses them: they hold no grant (D18).
- Each query is in the catalog's audit trail as the person: a read, a refusal, the credentials vended. Its
  record names the person as the end user, and Trino (principal `trino`, engine `trino-demo`) as acting for them:
  every request also carries Trino's own token in `X-Nuna-Engine-Authorization`, which vouches for the person's.
- Row filters and column masks (D20): `markets.desks.positions` is flagged, and each group has a rule on it. Run
  the same query as `ana` and as `stella`:

  ```sql
  SELECT * FROM lakehouse."markets.desks".positions;
  ```

  `ana` (`demo-analysts`) gets the equity desk's three rows, each trader's name `***`; `stella` (`demo-stewards`)
  gets all six rows with `pnl` empty. Trino asks the catalog for them as its OPA access control
  (`access-control.properties` in `trino.yaml`), forwarding the person's token; the catalog answers from the rules
  `gary` set. Read without Trino (Spark, say, with the person's own token), the table's data answers 403: only a
  trusted engine gets a flagged table's files. `gary` lists and changes the rules with
  `/management/v1/catalogs/lakehouse/row-column-rules`.

- The navigator lists every namespace at every depth, as a schema named by its dotted path; quote it in SQL:

  ```sql
  SELECT * FROM lakehouse."markets.fx".rates;            -- format v3
  SELECT * FROM lakehouse."markets.equities.eu.xetra".trades;
  ```

- The brokerage world's tables fill as it runs:

  ```sql
  SELECT * FROM lakehouse."brokerage.ledger".journal_lines;
  SELECT * FROM lakehouse."brokerage.trading".orders;      -- SingleStore's change feed
  ```

- A refusal's reason (D27, say) is in the error's cause, which `trino --debug` prints; the extra credential is
  the token of whoever asks:

  ```bash
  kubectl --context docker-desktop -n nuna-demo exec deployment/trino -- trino --user ana --debug \
    --extra-credential "token=$(cat ~/.nuna-demo/tokens/ana.jwt)" --execute '<the query>'
  ```

- A query without a token fails: Trino then asks the catalog to exchange one, and the catalog answers that
  it issues none (D13). Trino's own token, as user `trino`, reads the catalogs' config and vouches for each
  query's person, never reading as itself. It holds no
  grant; `demo.sh up` registers its client, `trino-demo`, as a trusted engine (D17), which sees every catalog
  and nothing in it (D18).
- The user name in a connection names the query in Trino; Trino checks no password.
- The first query after Trino starts takes about 10 seconds; after that, about 2.
- CloudBeaver's administrator is `oscar`: Settings, then Administration. `oscar` sees every person's
  connections and may use them. Scripts and settings last as long as its pod, and `engines` and `cdc` restart it; each new
  pod adds the users and their connections again.
- Its page loads only from its own address, port 8185. The image drops CloudBeaver's version check, which had the
  Administration page fetch dbeaver.com; its Version Update tab now says "Version is up to date" without
  checking. Two features would still reach outside, and the demo uses neither: the map view of a geometry
  value fetches tiles from openstreetmap.org, and the AI assistant calls whichever AI service an
  administrator configures; none is.

## Branches and tags of the catalog

Catalog `lakehouse` has refs of its own (D33). A tag keeps every table as it was at one moment, read-only. A
branch starts as the whole catalog at one moment and takes commits of its own, copying no file, until a merge
moves what it changed onto main, every table in one transaction. An engine reaches a ref as a warehouse of its own,
`lakehouse@<ref>`, so any Iceberg client can read it, and write to a branch. The console shows them on the
catalog's tab **Branches and tags**. `docs/refs.md` says how refs work, for engine users and operators.

`up` makes two, through the management API as `ana`, whose group `demo-analysts` holds `create` on `lakehouse`, the
right that making, merging and deleting a ref needs:

- Branch `dev`, made from main. The lake writer (`demo/LakeWriter.java`, as user `lake`) commits the next day,
  2026-11-11, on it, through the warehouse `lakehouse@dev`: trades 9 and 10 in `markets.equities.eu.xetra.trades`,
  and EURUSD and GBPUSD in `markets.fx.rates`. Main has neither.
- Tag `sample`, made from main after that: the sample data as `up` loaded it. On a new lake it comes before the
  brokerage world's bootstrap, so it holds nothing of `brokerage`.

It makes them once per lake: the tag, made last, says they are made. They live in the catalog's PostgreSQL, which
`down` keeps, so only after `demo/demo.sh wipe` does the next `up` make them again.

Trino has a catalog for the branch, `lakehouse_dev` (`iceberg.rest-catalog.warehouse=lakehouse@dev`, in
`k8s/trino.yaml`), beside `lakehouse`, which reads and writes main: an engine's catalog configuration names one ref.
The tag has none: read it in the console or through the REST API (step 1).

These are not the tables' own tags. The lake writer's tag `eod-2026-11-09` and branch `surveillance-review` in the
trades table, and the brokerage world's tag `eod-<day>` on each table that changed that day, are Iceberg's refs
inside one table: the console lists them on the table's Snapshots tab, under "Iceberg refs in this table". A
catalog ref such as `@sample` cuts every table at once, and the console shows it as a rounded `@` chip. How the two
nest: `docs/refs.md`, "Catalog refs and Iceberg's refs inside a table".

### Try it

Sign in to the console, <https://localhost:8181/ui/>, as `ana`, and to CloudBeaver, <https://localhost:8185>, where
her connection "nuna (Trino, as ana)" runs the SQL below.

1. **Read as of the tag.** In the console, open catalog `lakehouse`, tab **Branches and tags**
   (<https://localhost:8181/ui/#c=lakehouse&tab=refs>). It lists `@dev`, a branch with 2 changed tables, and
   `@sample`, a tag, both made by `ana`. On `@sample`'s line, choose **Read**. The band under the title says
   "Reading the tag @sample", and the catalog lists its namespaces live, as main does, `brokerage` among them; the
   tables beneath are the tag's, the sample data's, and none of `brokerage`'s. Open `markets`, `fx`, `rates`: its
   **Snapshots** tab lists the three the sample data made.
   **Read main instead** goes back to main. An engine reads the tag as the warehouse `lakehouse@sample`, as this
   call to the REST API does:

   ```bash
   curl --cacert ~/.nuna-demo/tls/ca.crt --ssl-revoke-best-effort \
     -H "Authorization: Bearer $(cat ~/.nuna-demo/tokens/ana.jwt)" "https://localhost:8181/v1/lakehouse@sample/namespaces"
   ```

   Namespaces are read live through any ref, so it lists `brokerage` too: SingleStore's change feed makes it
   before `up` makes the tag. The tag holds the sample data's tables and none of `brokerage`'s. Nothing commits
   through a tag: the catalog answers 400.

2. **Change two tables on dev.** In CloudBeaver, compare main and the branch. Main has trades 1 to 8 (3 was
   deleted) and the rates of 2026-11-09 and 2026-11-10; `dev` also has 2026-11-11 in both tables:

   ```sql
   SELECT trade_id, symbol, price, qty, trade_ts FROM lakehouse."markets.equities.eu.xetra".trades ORDER BY trade_id;
   SELECT trade_id, symbol, price, qty, trade_ts FROM lakehouse_dev."markets.equities.eu.xetra".trades ORDER BY trade_id;
   SELECT pair, rate, as_of FROM lakehouse."markets.fx".rates ORDER BY as_of, pair;
   SELECT pair, rate, as_of FROM lakehouse_dev."markets.fx".rates ORDER BY as_of, pair;
   ```

   Then add to the day on `dev`:

   ```sql
   INSERT INTO lakehouse_dev."markets.equities.eu.xetra".trades (trade_id, symbol, price, qty, trade_ts, venue)
   VALUES (11, 'DTE', 23.240000, 1500, TIMESTAMP '2026-11-11 09:02:10 UTC', 'XETR');
   INSERT INTO lakehouse_dev."markets.fx".rates VALUES ('USDJPY', 152.07, TIMESTAMP '2026-11-11 16:00:00 UTC', 'ECB');
   ```

   Run the four queries again: `dev` has the new rows, and main still has none of 2026-11-11. In the console,
   `@dev`'s page (its name on the Branches and tags tab) lists the two tables under **Changes**. A branch takes
   commits to the tables it has; creating, dropping or renaming a table or namespace happens on main only, and
   through `lakehouse_dev` Trino's own client refuses a CREATE before it reaches the catalog ("Server does not
   support endpoint: POST /v1/{prefix}/namespaces/{namespace}/tables"; `docs/refs.md`, "What a tag and a branch
   allow").

3. **Preview and merge in the console.** On the Branches and tags tab, choose **Merge…** on `@dev`'s line. The
   preview is the catalog's dry run, which moves nothing: "Ready: 2 tables would move to main in one transaction",
   and for each table "Judged as a commit on main: your groups hold write, main's commit rules pass". The trades
   table's legal hold allows it, as the merge only adds snapshots. Signed in as `stella` instead, there is no
   **Merge…** on the line, but `@dev`'s page has the same preview on its **Merge** tab, and says merging needs
   create on `lakehouse`, which she does not hold.

   Choose **Merge @dev into main**. The dialog shows each table's version on main now and after; choose **Merge
   into main**. The result, "Merged @dev into main", lists main's new version of each table. Main now has the day:
   the first and third queries of step 2 show trades 9 to 11 and the three rates of 2026-11-11. The branch has
   ended, so its name is free, and every query on `lakehouse_dev` fails ("Schema 'markets.fx' does not exist")
   until a branch named `dev` exists again. Read `@sample` again as in step 1: the tag still has none of 2026-11-11.

   A script can do the same through the API, the preview first:

   ```bash
   curl --cacert ~/.nuna-demo/tls/ca.crt --ssl-revoke-best-effort -X POST \
     -H "Authorization: Bearer $(cat ~/.nuna-demo/tokens/ana.jwt)" \
     "https://localhost:8181/management/v1/catalogs/lakehouse/refs/dev/merge?dry-run=true"
   ```

   and then the merge, the same call without `?dry-run=true`. It answers 200 with main's new versions, or 409 with
   every conflict. The merge's rules, rights and answers: `docs/refs.md`, "Merging a branch".

4. **The conflict when main moved too.** Make `dev` again: on the Branches and tags tab, **Make a branch or tag**,
   Kind **Branch**, Name `dev`, From "main, as it is now", then **Make it**. `lakehouse_dev` reads it at once. In
   CloudBeaver, commit to both tables on `dev`, then to one of them on main:

   ```sql
   INSERT INTO lakehouse_dev."markets.equities.eu.xetra".trades (trade_id, symbol, price, qty, trade_ts, venue)
   VALUES (12, 'SAP', 183.400000, 200, TIMESTAMP '2026-11-12 09:00:05 UTC', 'XETR');
   INSERT INTO lakehouse_dev."markets.fx".rates VALUES ('EURUSD', 1.0871, TIMESTAMP '2026-11-12 16:00:00 UTC', 'ECB');
   INSERT INTO lakehouse."markets.fx".rates VALUES ('EURUSD', 1.0868, TIMESTAMP '2026-11-12 16:00:00 UTC', 'desk');
   ```

   Back in the console, choose **Merge…** on `@dev`'s line. It says "@dev cannot merge: 1 table changed on main
   too", with the catalog's 409 `RefMergeConflictException`, which names `markets.fx.rates`: main is at a later
   version than the branch's point. The trades could move, but every table moves or none does, so the catalog
   refuses the merge whole and **Merge @dev into main** is off. **What you can do** offers a new branch made from
   main, to redo the change on, or keeping `dev` until it expires.

   Only a commit on main to a table the branch also changed makes a conflict. The brokerage world commits to main
   all day without stopping `dev` from merging, and main's own maintenance (compaction, snapshot expiry) does not
   count either. `demo/demo.sh rate` appends to main's rates, so after it a `dev` that changed the rates conflicts
   the same way.

   To finish, open `@dev`'s page, choose **Delete**, then **Delete @dev**. `up` makes no new `dev` while `@sample`
   is there.

## CDC: PostgreSQL, Oracle and CockroachDB into Iceberg history tables

```bash
demo/demo.sh cdc
```

starts the CDC leg (`demo/k8s/cdc.yaml`, `demo/cdc`) and stops Trino, the audit table writer, the maintenance
worker and the brokerage world's generator to make room for it:

- `backoffice`: a PostgreSQL with logical WAL, table `settlement.instructions` (`demo/cdc/backoffice.sql`).
- `oracle`: Oracle Database Free in archive log mode with supplemental logging, table `TREASURY.PAYMENTS`
  in PDB `FREEPDB1` (`demo/cdc/oracle.sql`, run as the database starts; it restarts the database once).
- `cockroach`: CockroachDB 25.4.20 (the version the tests use), one node, table `positions.holdings` in
  database `custody` (`demo/cdc/cockroach.sql`, run as the node first starts). The node runs secure, so
  passwords are checked, but accepts SQL without TLS. A single development node needs no licence key and is
  not throttled, and its diagnostics reporting is off.
- On `connect`, the stack's Kafka Connect 4.2.2 (distributed mode, in every `up`), six connectors
  (`demo/cdc/connectors`): Debezium 3.7.1's PostgreSQL connector (`pgoutput`), Oracle connector (LogMiner)
  and CockroachDB connector (a changefeed), and an Apache Iceberg 1.12.0 sink for each, writing through the
  catalog as `cdc` with vended credentials.

| Source | Kafka topic | Iceberg table in `lakehouse` |
|---|---|---|
| `settlement.instructions` | `backoffice.settlement.instructions` | `history.postgres.settlement.instructions` |
| `TREASURY.PAYMENTS` | `oracle.TREASURY.PAYMENTS` | `history.oracle.treasury.payments` |
| `positions.holdings` | `cockroach.positions.holdings` | `history.cockroach.positions.holdings` |

Each table is a history: one row per change, appended, never updated. The sink's `DebeziumTransform` adds a
struct column `_cdc`: `op` (`I`, `U` or `D`), `ts` (when Debezium read the change), `offset` (its Kafka
offset), `source` and `target` (the source table and the Iceberg table) and `key`. A deleted row is written
as it was before the delete. `cdc` waits for each snapshot (4 rows in each table), then updates, deletes and
inserts in each database and waits for those changes: 8 rows in the PostgreSQL history, 7 in Oracle's and 7
in CockroachDB's.

Make more changes and watch them arrive within about 10 seconds (the sinks commit every 5 seconds): kafbat
shows Debezium's change events in the topics above, and the console shows each sink commit as a snapshot of
its history table.

```bash
demo/demo.sh psql             # database backoffice, user backoffice
demo/demo.sh sqlplus          # user treasury in FREEPDB1; COMMIT, or Debezium sees nothing
demo/demo.sh cockroach        # database custody, as root
demo/demo.sh psql ana         # or as one of the people below
demo/demo.sh sqlplus stella
demo/demo.sh cockroach oscar
```

The people have accounts in all three databases, with their Keycloak passwords:

| | PostgreSQL `backoffice` | Oracle `FREEPDB1` | CockroachDB `custody` |
|---|---|---|---|
| `ana` | reads and writes every table; creates in the database and its schemas; alters and drops `settlement` and its table (a member of their owner, `settlement_owner`) | `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `ALTER` and `DROP ANY TABLE` | every privilege on schema `positions` and its table; creates schemas in the database |
| `gary` | superuser | DBA | `admin` |
| `stella`, `oscar` | read every table (`pg_read_all_data`) | `SELECT ANY TABLE` | `SELECT` on `positions.holdings` and on every table `ana` creates |
| everyone | metadata: statistics, settings and activity (`pg_monitor`) | metadata: the data dictionary and `V$` views (`SELECT_CATALOG_ROLE`) | |

`backoffice` (owner of the database, Debezium's login), `treasury` (owner of `PAYMENTS`), `c##dbzuser`
(Debezium's LogMiner user), and CockroachDB's `debezium` and `root` run the leg. In PostgreSQL and
CockroachDB a table `ana` creates is hers: besides her, only `gary` can alter or drop it.

CloudBeaver (<https://localhost:8185>) queries the three databases while the leg runs, through each person's
own connections: "backoffice (PostgreSQL, as ana)", "treasury (Oracle, as ana)" and "custody (CockroachDB, as
ana)" for `ana`. They sign in as the person, with the privileges above: `ana` and `gary` change rows there, and
their changes reach the history tables as any other does; `stella` and `oscar` read. CloudBeaver commits each
statement as it runs. CloudBeaver Community has no CockroachDB driver, so the custody connections use its
PostgreSQL driver, whose protocol CockroachDB speaks.

```sql
SELECT * FROM settlement.instructions ORDER BY instruction_id;   -- backoffice
SELECT * FROM treasury.payments ORDER BY payment_id;             -- treasury
SELECT * FROM positions.holdings ORDER BY holding_id;            -- custody
```

Outside the leg these connections fail to connect: their hosts are gone.

No query engine runs beside the leg: Trino is stopped for its memory. After `demo/demo.sh cdc stop`, read the
history in CloudBeaver's Trino connection, oldest change first (`offset` is a keyword in Trino, so it is quoted):

```sql
SELECT "_cdc".op, "_cdc".ts, instruction_id, status FROM lakehouse."history.postgres.settlement".instructions
ORDER BY "_cdc"."offset";
SELECT "_cdc".op, "_cdc".ts, payment_id, status FROM lakehouse."history.oracle.treasury".payments
ORDER BY "_cdc"."offset";
SELECT "_cdc".op, "_cdc".ts, holding_id, status FROM lakehouse."history.cockroach.positions".holdings
ORDER BY "_cdc"."offset";
```

- History only: the Apache Iceberg sink appends, so there is no table of the rows as they are now. A view
  over the history (the newest row per key that is not a `D`) gives that.
- Types: the sink maps only Kafka Connect's own logical types, so both connectors send times as those
  (`time.precision.mode=connect`, milliseconds), and every timestamp lands as `timestamptz`. A PostgreSQL
  `timestamptz` column would arrive as a string instead, so the source table uses `timestamp(3)`. Numbers
  with a scale land as `decimal(38, s)`, and Oracle's `NUMBER(10)` as `long`.
- CockroachDB's connector has no such settings: it sends `DECIMAL` as a string and `TIMESTAMP` as
  microseconds since the epoch. So `cdc` makes its history table before the sink starts, with the columns the
  sink makes for PostgreSQL's (`decimal(38, s)`, which the sink parses the strings into), and the sink converts
  the microseconds to a timestamp (`cockroach-history.json`). Its history therefore has the same types as
  PostgreSQL's.
- Oracle's upper-case column names are renamed to lower case in the sink (`oracle-history.json`), column by
  column, the key's too: a column added later arrives in upper case.
- The history tables are written as `cdc`, group `demo-loaders`: `ana` reads them like any other table.
- Each `cdc` drops the history tables of the run before and starts the databases empty. `demo.sh cdc` while
  the leg runs only brings it up to date (a changed connector config, image or manifest).
- The first `cdc` pulls Oracle's image (about 5 GB); `up` builds Kafka Connect's image from Maven Central.
- kafbat shows the connectors under Kafka Connect, cluster `connect`: each one's status, tasks and config, with
  restart, pause and resume for `oscar`.
- Kafka Connect's REST API is on <https://localhost:8186>, which asks for a password (user `operator`, the
  password in `~/.nuna-demo/secrets/connect`; backlog 283): it has no sign-in of its own, and a connector it is
  given runs code with the worker's environment, the catalog token among it. Without the password the front door
  answers 401. Inside the cluster it stays open, for kafbat and the brokerage world's generator:
  `curl -s --cacert ~/.nuna-demo/tls/ca.crt --ssl-revoke-best-effort -K ~/.nuna-demo/secrets/connect.curlrc 'https://localhost:8186/connectors?expand=status'`.

```bash
demo/demo.sh cdc stop
```

deletes the leg: its connectors, with their offsets (so the next `cdc` snapshots its new databases), its
databases, Kafka topics and consumer groups; and starts Trino, the audit table writer, the maintenance worker and
the brokerage world's generator again. Kafka Connect keeps running, with the brokerage world's sink. The history tables stay, for
Trino to read.

## The engines

```bash
demo/demo.sh engines
```

runs the engine matrix (`catalog-tests-engines`, `EngineMatrixTest`) against this stack instead of one of its
own: Spark, Kafka Connect and PyIceberg as Kubernetes jobs, then SingleStore's ingest in the demo's SingleStore,
all into catalog `engines` as `engine-matrix`. Trino, CloudBeaver, Kafka Connect, the brokerage world's
generator and the maintenance worker stop for the run and start again after it. It takes about 4 minutes once the engine images exist; the first
run builds them, which takes longer. It leaves Spark's shared tables in `m8.shared.spark`, which the console
shows, and SingleStore's copies in database `nuna_m8`.
Results: `catalog-tests-engines/build/engines-run/results.txt`, and every command in `commands.log` there, in
`~/.nuna-demo/checkout` when run from the main checkout.

## Kafka

kafbat on <https://localhost:8182>, where the people above sign in through Keycloak, shows the stack's Kafka:
the catalog's audit and change topics, the brokerage world's `brokerage.*` topics and its sink's
`brokerage-lake-control`, Kafka Connect's own `connect-*` topics, and once the engines have run, the engine
matrix's Kafka Connect source topic `m8-kafka-events` and its sink's control topic. While the CDC leg runs:
Debezium's change topics, the sinks' control topics and Oracle's schema history.
## Metrics and traces

```bash
demo/demo.sh metrics          # on
demo/demo.sh metrics stop     # off
```

The switch `metrics` (backlog 233; D50, amended 2026-11-25) runs four pods (`k8s/metrics.yaml`) and points the
catalog's and the maintenance worker's `telemetry.endpoint` at the first:

```
catalog, worker --OTLP--> OpenTelemetry collector --metrics--> Prometheus --> Grafana (https://localhost:8188)
                                                  --traces---> Tempo -------> Grafana
```

- **Grafana** on <https://localhost:8188>: the people above sign in through Keycloak with no second password. oscar
  and presenter administer it; ana, gary and stella view. The home page is the catalog's dashboard; folder `nuna`
  holds all three from `deploy/grafana/dashboards`, and Explore, with data source Tempo, searches the traces. A
  chart's exemplar points (Prometheus keeps the ones Boot sends) open the trace of that request. Grafana's own admin
  signs in at <https://localhost:8188/login?disableAutoLogin=true> as `admin`, with the password in `~/.nuna-demo/secrets/grafana-admin`, which `metrics` sets again on a kept Grafana
  database (backlog 282).
- **Prometheus** takes the metrics over OTLP, as item 43 says work's should: `translation_strategy:
  UnderscoreEscapingWithSuffixes`, native histograms off, a 30-minute out-of-order window, exemplars kept. It loads
  the alert rules in `deploy/grafana/rules`; no Alertmanager runs, so they show in Grafana's Alerting page and in
  `ALERTS`, and page no one.
- **Tempo** keeps the traces. While the switch is on the catalog and the worker record every trace (sampling 1,
  against the chart's 0.1), and a worker's scan is one trace with the catalog's calls in it.
- The catalog's logs still go to the audit table writer; the worker's copy of its logs reaches the collector and is
  dropped (`kubectl logs` has them).
- The data stays: Prometheus keeps 7 days of series and Tempo 2 days of traces, each on a volume, and Grafana its own
  database on a third; `metrics stop` and `down` keep them, `wipe` deletes them.
- Memory: the four pods ask for 704 MB (collector 64 MB, Prometheus 256 MB, Tempo 128 MB, Grafana 256 MB, which
  stalled for minutes at a 384 MB limit and now has 768 MB). Turning the switch on rolls the catalog, so `metrics`
  checks that Docker Desktop has 1.5 GB no pod asks for, and refuses otherwise. It fits beside `gen` and `kafka`
  (1.7 GB free before it, measured 2026-11-25) or beside `ss`, not beside `gen`, `kafka` and `ss` together; `ss`
  refuses to start beside it when SingleStore's 1.5 GB is not free. Windows itself had about 700 MB free with
  `gen`, `kafka` and `metrics` on, and paged: the demo answers slowly then, and the SLO burn-rate alerts fire.
- What the dashboards show in the demo, checked against live series on 2026-11-25: every panel has data but these.
  The failure panels (failed loads, commits and vends, requirement failures, failed scans and jobs) stay empty until
  something fails; a load of a missing table shows in "Failed loads". The audit's Kafka acknowledgement row reads
  `nuna_audit_publish_*`, which the demo never makes: its catalog audits to the log, not to Kafka (D23). "Records
  logged and archived" shows `nuna_audit_logged` alone: `nuna_audit_archive_highest` comes from the archive check,
  and the demo configures no audit archive.
  Records removed and delete files added stay empty in the client reports: the demo's engines only append, and
  Iceberg leaves out of a report what did not happen.

## Stop

```bash
demo/demo.sh down
```

This stops and deletes everything in the namespace but the data: the volume claims of PostgreSQL, Silo, Kafka,
SingleStore and the metrics stack stay, with configmap `demo-seeded`, and so does the namespace that holds them. The compaction Jobs'
namespace `nuna-demo-compaction` goes. A running CDC leg is deleted as `cdc stop` deletes it. The next `up` carries
on from the data: the lake, its branch and tag, the governance, the audit table, SingleStore's databases and the
brokerage world as they were.

To start again from fresh sample data:

```bash
demo/demo.sh wipe
```

This is `down`, then deletes the namespace with its volumes. The signing key, tokens, Keycloak's keys, the
certificate and the database's certificates stay in `~/.nuna-demo` either way.

```bash
demo/demo.sh status
```

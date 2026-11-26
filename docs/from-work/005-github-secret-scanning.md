<!-- Transcribed 2026-11-25 from a photo of the work machine's copy (103 lines, 9,179 bytes, LF). This copy is about
150 bytes short, most likely path spellings and backticks; every finding, file and fix is here. The text is the work
agent's, except that each credential-shaped value is shown as <redacted> and one example of the `-p` shape is
described in words, so this copy does not trip the scanner it describes. What we did with it: backlog 280 to 285. -->

# GitHub's secret scanning blocks the repository: test passwords in the code, and the catalog's database password

Added 2026-11-25.

## What

At work the code is going into GitHub (Fidelity-Green/ap183293-nuna-catalog-demo). Fidelity's GitHub refuses any push that contains something its secret scanning ("push protection") reads as a credential. Every match in your drop of 2026-11-24 is a test value or a demo setting, not a real secret, but the push is refused all the same, and the only other way through is an exemption per finding through ECS's bypass process. So we changed the code instead, on our `main` at work, and pushed. These are the changes, so the same push works from your tree.

The scanner matches patterns, not meanings: a literal assigned to something called a password, a password inside a connection address, `-p` followed by a word on a command line, a string shaped like a signed token. Fixing a match means the source no longer holds that shape: making the value at run time is the simple way.

## Why it matters

Your repository cannot go into Fidelity's GitHub as it is, and each new test with a literal password would block the next push. The history matters too: push protection checks every commit pushed, so the old values in earlier commits block a push of the full history even once the current tree is clean. We pushed one snapshot commit, with no history, for that reason. If you want your history in GitHub, these values have to come out of the old commits too (`git filter-repo --replace-text`), or each finding needs an exemption.

## Evidence

GitHub's refusals, by the scanner's own names, with where it found them in your drop of 2026-11-24:

| Scanner finding | Where | What it is |
|---|---|---|
| `HIGH_CONFIDENCE_ECS_STRING_PASSWORD_VALUE` | `catalog-tests-s3/.../s3/PrivateCa.java`, `PASSWORD = <redacted>` | the password of the throwaway PKCS #12 key files the helper makes |
| `HIGH_CONFIDENCE_ECS_STRING_PASSWORD_VALUE` | `catalog-server/.../server/OracleBackendTest.java`, `PASSWORD = <redacted>`; `catalog-store-oracle/.../oracle/OracleFree.java`, `PASSWORD = <redacted>` | the Oracle Free test container's accounts |
| `HIGH_CONFIDENCE_ECS_SET_PASSWORD_VALUE` | `catalog-store-oracle/.../oracle/UnavailableFailuresTest.java`, `dataSource.setPassword(<redacted>)` | the same password, in `dataSource(int port)` |
| `HIGH_CONFIDENCE_ECS_DB_CONN_PASSWORD_TYPE7_VALUE` | `catalog-server/.../server/DatabaseSettingsTest.java`, `GOOD_URI` and two more `mongodb://catalog:<redacted>@...` addresses | sample MongoDB connection strings |
| `HIGH_CONFIDENCE_ECS_CLIENT_SECRET_SSO_VALUE` | `catalog-maintenance/.../maintenance/TokenSourceTest.java`, `"client_secret=<redacted>"` and the other `<redacted>`s | the worker's sample client secret |
| `HIGH_CONFIDENCE_ECS_JWT_TOKEN` | `docs/design/console/prototype.html`, the `<textarea class="tokenbox" id="token">` | an illustrative token in the mock-up |
| `HIGH_CONFIDENCE_ECS_DATASOURCE_PASSWORD_VALUE` | `demo/k8s/catalog-values.yaml` and `deploy/local/values.yaml`, `spring.datasource.password: <redacted>` | the catalog's PostgreSQL password |
| `HIGH_CONFIDENCE_ECS_PASSWORD_PARAM_TYPE1_VALUE` | our `deploy/nuna/remote.sh` (`systemctl --user show` with two `-p` options) | not a password: `-p` read as one; ours, fixed |

Not flagged today, but the same shape, so likely to be flagged by a later rule: `catalog-audit-tables/.../audittables/AuditTablesSettingsTest.java` (`<redacted>` as a client secret), and the demo's fixed test passwords (Silo's key `<redacted>`, Keycloak's admin `<redacted>`, SingleStore's root `<redacted>`).

## Suggested change

1. **Test values made at run time.** What we did, each verified at work as noted:
   - `PrivateCa`: `PASSWORD = UUID.randomUUID().toString()`. The key files live only in the CA's temporary folder and are read by the same JVM, so any value works. Compiled; its tests need Docker, not run.
   - `OracleFree`: `PASSWORD = "pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 20)` (Oracle wants a letter first), made package-visible so `UnavailableFailuresTest.dataSource` uses it instead of its own `<redacted>`; `OracleBackendTest` gets its own the same way. Compiled; these are heavy tests, not run.
   - `DatabaseSettingsTest`: `SECRET = "pw-" + UUID.randomUUID()`, and the three addresses built as `"mongodb://catalog:" + SECRET + "@h1..."`; the redaction check becomes `doesNotContain(SECRET)`. Run: 4 of 4 pass.
   - `TokenSourceTest`: `SECRET = "cs-" + UUID.randomUUID()` in place of every `<redacted>`, including `"client_secret=" + SECRET` and `"nuna-maintenance:" + SECRET`. Run: 3 of 3 pass.
   - `prototype.html`: the sample token replaced by the text "(a bearer token, pasted here)".
   - Worth doing the same in `AuditTablesSettingsTest` before the scanner learns that shape.

2. **The catalog's database without a password.** At work we went further than removing the literal: on Nuna the catalog now signs in to its PostgreSQL with a client certificate, and no PostgreSQL account has a password.
   - `demo/demo.sh`, new `db_tls`: makes a database CA of the demo's own once per machine in `~/.nuna-demo/db-tls` (kept by `down`), a server certificate for Service `postgres` (names `postgres`, `postgres.<ns>.svc`, `postgres.<ns>.svc.cluster.local`) and a client certificate named `catalog`, plus the client key as PKCS #8 DER, the only key form the JDBC driver reads besides PKCS #12. Publishes Secret `postgres-tls`. The web pages' certificate cannot do this job: it proves the host's name, and a client certificate has to come from a CA the database trusts, which at work cannot be Fidelity's.
   - `demo/k8s/stack.yaml`, PostgreSQL: `ssl=on` with that certificate, and `hba_file` from a new ConfigMap `postgres-hba`: `local all all trust` (your `k exec ... psql` and the readiness probe, unchanged) and `hostssl catalog catalog all cert`. `POSTGRES_PASSWORD` gives way to `POSTGRES_HOST_AUTH_METHOD=trust`, which the image needs without a password; `hba_file` replaces the rules initdb writes, so it opens nothing. `fsGroup: 999` and `defaultMode: 0640`, because PostgreSQL refuses a key file others can read.
   - `demo/demo.sh`, new `catalog_secret`: the chart's Secret is now made by the script (`secrets.existingSecret: nuna-catalog-secrets` in `demo-values.yaml`), holding the client certificate, key and CA as files plus the storage profiles' secret keys, because a values file cannot carry the binary key. A hash of the Secret goes on the pod as `podAnnotations.nuna-demo/secrets`, so a new certificate rolls the server.
   - `demo-values.yaml`: `spring.datasource.url` gains `?sslmode=verify-full&sslrootcert=/etc/nuna/secrets/db-ca.crt&sslcert=/etc/nuna/secrets/db-client.crt&sslkey=/etc/nuna/secrets/db-client.pk8`. `catalog-values.yaml` loses its `secrets:` block.
   - `deploy/helm/nuna-catalog/templates/deployment.yaml`: the `secrets` volume gets `defaultMode: 0440`. The JDBC driver refuses `sslkey` if others can read it (owner 0600, or root's 0640), and the pod's `fsGroup` 10001 owns the files, so the config tree still reads them. Probably worth a chart value rather than a fixed mode.
   - Verified on Nuna: all ten of the catalog's connections are TLS 1.3 with client name `/CN=catalog` (`pg_stat_ssl`); `psql` over the network without a certificate gets "connection requires a valid client certificate", and without TLS "no pg_hba.conf entry ... no encryption".
   - Not done: the engine matrix (`EngineMatrixTest`) applies the same `stack.yaml` and `catalog-values.yaml`, so at work it would now need the certificates too; we do not run it on Nuna and left it broken there. If you take this, the matrix's harness would call the same two steps, or keep a password-free variant for itself.
   - For production this is the shape too, with the CA and keys from work's PKI and vault instead of the script.

3. **Your laptop runner, kept on a password.** `deploy/local/values.yaml` no longer carries `spring.datasource.password`; `deploy/local/run.sh` passes it to Helm read from `backing.yaml`'s `POSTGRES_PASSWORD`, its one place, with `sed`. Not run at work (no Docker Desktop here), so please run it once.

4. **One more fix found on the way.** The audit table writer makes `nuna_audit.audit.events` only as it starts. When the catalog's PostgreSQL pod is new but the writer's is not (a Helm upgrade that changes nothing leaves it running), the Audit page answers 503 "the audit table nuna_audit.audit.events does not exist yet" for good, and the writer logs `NoSuchTableException: Table does not exist: audit.events` every 10 seconds while holding the records. `audit_writer` now waits up to 30 seconds for the table, as gary, and restarts the writer if it is missing: seen working on the next up ("the audit table is missing ... starting the audit table writer again"). The better fix is the writer making its table again when a commit finds it gone.

All of it is on our `main` at work: commits 5b25712, 0c7feda, 77bc845 and 57966d6 (the chart's mode).

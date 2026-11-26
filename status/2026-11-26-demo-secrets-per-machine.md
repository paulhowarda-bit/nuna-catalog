# 2026-11-26, every demo value made once per machine, and Kafka Connect's 8186 behind a password (backlog 282, 283)

- `demo/demo.sh` makes every password, key and client secret once per machine in `~/.nuna-demo/secrets`
  (`demo_secrets`), and publishes them as Secrets (`demo-secrets`, `s3-root`, `singlestore-root`, `connect-auth`)
  from files. Helm gets them with `--set-file`, never `--set`. People's passwords are typeable (three groups of four
  lower-case letters) and listed in `~/.nuna-demo/people`; the README names that file and lists none.
- Manifests take every value from a Secret: Keycloak (admin, and the realm's people through `${NUNA_PASSWORD_*}`),
  Grafana, Silo and its seed, SingleStore and the brokerage, CloudBeaver, the CDC databases (their SQL is filled into
  Secrets, not ConfigMaps) and the connectors (`${env:CDC_*}`). demo.sh reads values inside the pods (`$ROOT_PASSWORD`,
  `$MINIO_ROOT_PASSWORD`, `$TREASURY_PASSWORD`) or sends them on stdin, never on a command line it prints.
- Kept volumes: SingleStore sets root's password at every start; Grafana's admin is set again on each `up`
  (`grafana cli admin reset-admin-password --password-from-stdin`); Silo reads its root key from its environment;
  Keycloak, CloudBeaver and the CDC databases keep nothing. No `wipe` should be needed; if Silo keeps an older root
  key, `demo/demo.sh wipe` is the fallback.
- `deploy/local/run.sh` makes its values in `~/.nuna-k8s/secrets` (`values`), publishes `nuna-local-secrets`, and
  passes Helm's secrets with `--set-file`; `values.yaml` and `worker-values.yaml` hold none.
- `EngineMatrixTest` creates `s3-root` and `singlestore-root` itself, since it shares the demo's manifests.
- PostgreSQL's readiness probe waits 5 s (`demo/k8s/stack.yaml`).
- 283: the front door's 8186 asks for a password (nginx `auth_basic`, an apr1 htpasswd from Secret `connect-auth`);
  demo.sh's own calls use `-K ~/.nuna-demo/secrets/connect.curlrc`. Signed-in operators see the connectors in kafbat.
- `secret-shapes-allowed.txt` holds no lines now.
- Checked: the fast build after merging `main`, green (BUILD SUCCESSFUL in 32 s, `build/fast-build.log`);
  `secretShapes` passes; `bash -n` on both scripts.
- Not checked live: Windows had 633 to 753 MB available and Docker Desktop was stopped, so the demo was not started.
  Sign-ins, the Silo and Grafana values on kept volumes, `cdc`, `deploy/local up`/`smoke`/`worker` and an
  unauthenticated request to 8186 being refused are all unverified.
- Left: Kafka Connect's 8083 is still open inside the cluster (no NetworkPolicy).

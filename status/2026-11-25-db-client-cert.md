# 2026-11-25, the demo's catalog signs in to its PostgreSQL by a client certificate, and no role has a password (backlog 281)

- Branch `worktree-agent-a65f3a5582aa53d3f`, from work's note 005 as catalog-security's review changed it (item 281).
- Chart value `databaseTls` (Secret, mount path, mode "0440"); `demo.sh`'s `db_tls` (a database CA once per machine in
  `~/.nuna-demo/db-tls`, PostgreSQL's and the catalog's certificates, Secrets `postgres-tls` and `catalog-db-tls`),
  `db_no_passwords` and `db_check` in `up`; `stack.yaml`'s PostgreSQL with ssl on, `hba_file` from ConfigMap
  `postgres-hba`, a random unused initdb password and the key copied in with mode 0600; the URL's TLS parameters in
  `catalog-values.yaml`. The engine matrix issues the same certificates from PrivateCa; TestS3's keys are made per
  JVM and the store manifests take them as placeholders.
- Checked: `helm template` with and without `databaseTls`; `secretShapes` passes with the 281 files out of the
  allowlist; `:catalog-tests-engines:compileTestJava`; PostgreSQL on a new volume in a scratch namespace (TLSv1.3,
  `/CN=catalog`, connections without a certificate refused); the live demo's `up` on its kept volume (commit
  d6db24e6a2ff): 10 network connections, all TLSv1.3 as CN=catalog, none without a certificate, no role with a
  password.
- Not covered: the engine matrix itself (overnight); a whole-demo `up` on a new PostgreSQL volume (no such `wipe`).

# 2026-11-26, heavy tests on Podman: the Docker client reused connections Podman had closed (backlog 291)

Cause: Podman's API service closes a keep-alive connection that has been idle for 10 s. Measured through
`//./pipe/docker_engine` by sending `GET /_ping` twice on one connection: after 9 s idle the second ping was answered,
after 10 s it got an empty read. docker-java's connection pool, which Testcontainers uses, keeps connections forever
and never checks one before reusing it. So the first request after a quiet spell goes out on a closed connection. The
HTTP client sends a GET again, but not a POST (create, start, pause, exec). In the gate, `TwoIssuersTest` created
`postgres:18.7` after Keycloak's 36 s wait, and that create failed 3 ms after it was sent with
`NoHttpResponseException: localhost:2375 failed to respond`.

Fix: a new module, `catalog-tests-docker`, holds `FreshPipeStrategy`. It is Testcontainers' named-pipe client with a
5 s time to live on pooled connections, set by reflection, since docker-java exposes no setting for it. If a
Testcontainers upgrade renames a field, the first container fails and the error names the field. The root build puts
the module on every module's test runtime path and names the strategy in `TESTCONTAINERS_DOCKER_CLIENT_STRATEGY`, so
the fix covers every module. It applies on Windows only.

`~/.testcontainers.properties`: Testcontainers uses a strategy it is told to use only if it may record it, so a nuna
test run writes `docker.client.strategy=nuna.catalog.tests.docker.FreshPipeStrategy` there. That line was in the file
after these runs. Another project's JVM, without the class on its path, then logs "Can't instantiate a strategy from
… (ClassNotFoundException)", falls back to Testcontainers' own pipe strategy and writes that one back. Its tests run as
before, without this fix. A nuna test run from an IDE outside Gradle picks the strategy up from the file.

Checked, on Podman, one run at a time once the gate run had ended (C: had 27 GB free, `.overnight/running` was absent):
- `./gradlew :catalog-tests-docker:test -Pheavy`: `FreshPipeStrategyTest` passed. It sends a ping, waits 12 s, then
  creates and removes a network. Bite `TEST-291/connections-reused-at-any-age` (the time to live is not set) failed it
  with `NoHttpResponseException: localhost:2375 failed to respond`. The file was restored and the bite passes
  `git apply --check`.
- `./gradlew :catalog-server:test --tests '*TwoIssuersTest' --tests '*ConsoleSignInTest' --tests '*SpanTreeTest'
  -Pheavy`: BUILD SUCCESSFUL in 5m 45s. TwoIssuersTest 3 of 3, ConsoleSignInTest 4 of 4, SpanTreeTest 3 of 3.
  - An earlier run beside the gate run timed out waiting for Keycloak's realm (3 min). kind, a CockroachDB cluster and
    Toxiproxy were running at the time. On this rerun, with nothing beside it but kind, Keycloak started, so that
    timeout was the environment.
- `./gradlew :catalog-store-mongo:test --tests '*MongoStoreSuiteTest' -Pheavy`: BUILD SUCCESSFUL in 10m 48s, 226
  tests, 0 failed, 0 skipped. These cases passed:
  - "4 Failover: every commit acknowledged before the primary is lost is present after promotion"
  - "MongoDB: a commit while the set is failing over, its primary killed and no new one elected yet, is a clean
    failure …"
  - "a standalone server, not a replica set -> mongo.not-replica-set"
  Item 290's per-suite workaround is not in this branch.
- `./gradlew :catalog-store-sql-raft:test -Pheavy`: BUILD FAILED in 1h 2s, "217 tests completed, 14 failed".
  - The top-level `CockroachStoreSuiteTest` passed 33 of 33. That includes "4 Failover", the lost-COMMIT and
    lost-answer cases, and the stalls that pause nodes through the Docker API.
  - The 14 failures were in nested suites: Refs 8 of 21 (24 min), BranchCommits 4 of 11, Grants 1 of 12, Upgrade 1 of
    1. Every one was a JDBC `SocketTimeoutException: Read timed out` from CockroachDB, reported as
    `StoreUnavailableException` or `CommitOutcomeUnknownException`. None was a Docker API error: no `NoHttpResponse` or
    `2375` in any result.
  - Not investigated. Whether CockroachDB is this slow on every Podman run is open.
- `podman ps` after the runs showed only kind's `nuna-control-plane`.
- Fast build after merging main (98b195fd): `./gradlew build`, BUILD SUCCESSFUL in 2m 12s, "All 865 bites apply".

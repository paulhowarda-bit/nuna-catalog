# 2026-11-26, heavy tests on Podman: the Docker client reused connections Podman had closed (backlog 291, unfinished)

Stopped part-way: Paul ran out of disk, and the coordinator asked every agent to stop its test runs.

Cause: Podman's API service closes a keep-alive connection that has been idle for 10 s. Measured through
`//./pipe/docker_engine` by sending `GET /_ping` twice on one connection: after 9 s idle the second ping was answered,
after 10 s it got an empty read. docker-java's connection pool, which Testcontainers uses, keeps connections forever
and never checks one before reusing it. So the first request after a quiet spell goes out on a closed connection. The
HTTP client sends a GET again, but not a POST. In the gate, `TwoIssuersTest` created `postgres:18.7` after Keycloak's
36 s wait, and that create failed 3 ms after it was sent.

Fix: a new module, `catalog-tests-docker`, holds `FreshPipeStrategy`. It is Testcontainers' named-pipe client with a
5 s time to live on pooled connections, set by reflection, since docker-java exposes no setting for it. The root build
puts the module on every module's test runtime path and names the strategy in
`TESTCONTAINERS_DOCKER_CLIENT_STRATEGY`. Testcontainers writes the strategy's name to `~/.testcontainers.properties`.
A JVM that cannot find the class logs a warning and falls back to the plain pipe.

Checked:
- `FreshPipeStrategyTest` (heavy, Windows only) passed. It sends a ping, waits 12 s, then creates and removes a network.
- Bite `TEST-291/connections-reused-at-any-age` failed that test with the gate's own error,
  `NoHttpResponseException: localhost:2375 failed to respond`. The bite was restored and passes `git apply --check`.
- `catalog-server`, beside the gate run: `SpanTreeTest` passed 3 of 3. `TwoIssuersTest` (3) and `ConsoleSignInTest`
  (1) now get past creating their containers, but they failed because Keycloak did not answer its realm URL within
  3 min ("Unexpected end of file from server"). Other containers were running at the same time: kind, CockroachDB and
  Toxiproxy. A rerun was stopped before it finished.

Left:
- Rerun `TwoIssuersTest` and `ConsoleSignInTest` when Docker is quiet. If Keycloak still does not start, read its
  container log.
- Merge `main` and run the fast build, which was not run.
- Close item 291.

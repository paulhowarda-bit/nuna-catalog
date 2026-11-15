# 2026-11-15, the overnight run's two failures, before its second night

Paul asked for both to be fixed before tonight's run.

**Backlog 30, done.** The two bites that came out SETUP now fail on an assertion. `M8/store-skips-postgresql-steps`
breaks the same check without stopping the server: PostgreSQL migrates with the shared steps only and extends no
journal partitions, so `SchemaAtStartupTest` fails on the journal not being partitioned. `D7/oracle-backend-runs-sql-store`
keeps its break and names a new test, `StoreWiringTest` (catalog-server), which checks the store each backend gets
without a database; `StoreWiring.store` makes the store, and the bean migrates it. Checked: `bites/run.sh` on the
two, `BITES (1 of 1 failed)` each, tree restored; the failures are `SchemaAtStartupTest.java:27` and
`StoreWiringTest.java:34`, both assertions.

**Backlog 32, mitigated, open.** Docker's VM logs show Docker up through the Ceph leg of 06:40, with
`catalog-objectstore`'s JVM starting containers at 06:40:54; only `catalog-maintenance`'s JVM, starting with it 9 s
after the heavy build's last containers went, gave up after Testcontainers' 10 s of pings. The leg run on its own
(13:28, `-Pheavy -Ps3=ceph`, in a worktree) found Docker in both JVMs: objectstore 197 passed, maintenance 19 of 20.
`KubernetesCompactionTest` failed there because the keel-demo stack was up and held 6.1 GiB of the node's memory
requests, so its 2 GiB Job never ran; the overnight run takes the demo down first. `overnight.sh` now waits before
each leg and the bites until no test container is left and `docker info` answers (up to 3 min), and sets
`TESTCONTAINERS_CLIENT_PING_TIMEOUT=60`. Checked: with a stub repo and a labelled container alive for 25 s, the first
leg started 3 s after the container went; an unparseable value made `VendingTest` fail to find Docker and 60 let it
pass (10 of 10), so the setting reaches the test JVMs through Gradle. Not yet seen in an overnight run: the item
stays open until the Ceph leg passes in one.

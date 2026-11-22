# 2026-11-22, heavy-fixes: the heavy build made clean

Paul asked for the heavy build to be run and fixed until a run came back clean. The nuna-demo stack was taken down
for it (`demo/demo.sh down`, at his word) and brought back up at the end.

Three full heavy builds, `./gradlew --continue build -Pheavy` (item 122 added `--continue` to the overnight run's
heavy leg first, so one module's failure no longer hides the rest):

1. On main (ecff04c), 39 min: 30 failures, all in the four store suites. 28 were the suites' own bugs, the same 7 on
   each store: `RefSuite`'s and `BranchCommitSuite`'s ref deletion and merge (`projectionPieces` could not count 0;
   items 102, 103) and five `DrSuite` cases (it ignored the version 1 that creating an object commits; items 28,
   100). The other 2 were Oracle's: ORA-17800 during logon was not counted as the database being unreachable (item
   7). Every other module passed.
2. On this branch, 42 min: the four store suites passed (mongo 223, sql 217, oracle 247, sql-raft 187). Five tests
   in other modules lost waits to 30 s stalls while two heavy modules shared Docker (`AuditReadRouteTest` 3,
   `ManagementAuthorizationTest` 1, `WorkerTraceTest` 1); all three classes passed when run alone straight after.
   Filed as item 123.
3. On this branch, 42 min: **BUILD SUCCESSFUL**, 2,415 tests in 17 modules, none failed, 7 skipped by their
   assumptions: four object-store cases checked only on Ceph or AWS, and Oracle's failover, replica and
   safe-environment cases.

What changed:

- Store suites (`catalog-store-api` and the four harnesses): `projectionPieces` counts the key's pointer plus its
  pieces; `DrSuite` counts the creation's version and drops the pointerless object, which the store's API cannot
  make.
- Oracle store: `Transactions.UNAVAILABLE_ERRORS` holds 17800; the dropped-connection test failed 128 of 300
  repetitions before and none after.
- `ConsoleBrowser.session()` asks for a session again, up to three times (item 121); no run needed it.

Items 100, 102, 103 and 121 are done; 28 is noted (it waits on item 92 and the overnight drill); 7 is noted. Not
run here: the bites (the overnight run runs them after a passing heavy build), the Ceph leg and the nightly.

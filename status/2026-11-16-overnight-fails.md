# 2026-11-16, the overnight run's failures, and a heavy build by hand

Paul asked for a heavy build and for the overnight run's failures to be fixed.

**The heavy build** (`./gradlew build -Pheavy --continue` on 23987ca, 24 min, the keel-demo stack down): every module
ran, 1,819 tests, one failure. `KubernetesCompactionTest` passed (backlog 54's quoting fix, landed at 04:36, had not
run before), and so did the Oracle, MongoDB and CockroachDB suites, conformance and the engines module, which the
overnight run's heavy build never reached. The one failure, `WorkerTraceTest`, was a race in the test: backlog 56,
done here.

**The nightly's two failures, backlog 55, fixed but not yet run.** Both tests lost one request each to the same
stall, about 05:45:57 to 05:46:13 UTC, while `ConcurrentWritersTest`'s stack started beside the CockroachDB cluster:
a CockroachDB connection waited out its 15 s, and a server started a second earlier could not get the audit topic's
metadata from Kafka within 5 s, so two vends failed closed with 503. Docker's and Windows' logs show nothing then.
The CAS test now repeats a commit the store says it never applied (`StoreUnavailableException`), up to three tries;
`ConcurrentWritersTest` now has each server vend once before the first case. Neither test was run here (`-Pnightly`
only); the item stays open until both pass in an overnight run.

**Backlog 56, done.** `WorkerTraceTest` took the first catalog span for a table load in the trace and expected the
scan's load as its parent; the expiry's commit loads the table on the same route, and that span can arrive first. It
now finds the load span, then the catalog span that is its child. Checked: the test passes, and `bites/run.sh
bites/OBS-49` gives 7 of 7 BITES, restored.

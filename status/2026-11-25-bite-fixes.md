# 2026-11-25, three bites that did not bite, and the schema tests' version number

Paul, 2026-11-24 and 2026-11-25: fix items 226, 227 and 228, the bites the overnight run of 2026-11-24 found not
biting, and item 268 with them, since 228's bite is judged by `SchemaAtStartupTest`.

- 226: `MetadataLogRefTest` bounds its paging, so its bite fails on an assertion in 42 s, where it ran the test JVM out
  of heap after 48 minutes; `bites/run.sh` judges a test JVM that died SETUP, not NO BITE (a case in
  `bites/test-run.sh`).
- 227: the old test could not show the break, since the cut leaves out a table with no pointer in its snapshot. A new
  MongoDB test moves a table into a new namespace between the cut's two reads, through the driver's command listener,
  every time; the bite bites on it.
- 228: no break that skips PostgreSQL's own steps lets the server start any more (V10's `kms_key` is read at start).
  Recorded again as `store-never-extends-journal`, which bites `SchemaAtStartupTest` on its months-ahead assertion.
- 268: `SchemaAtStartupTest` and `RollingUpgradeTest` expect no PostgreSQL step pending, read from the classpath,
  instead of version 15.

The three bites each said BITES under `bites/run.sh`. The MongoDB runs needed the demo down (D49, notices sent before
and after); it came back up from `main`. `RollingUpgradeTest` runs first in the next overnight run.

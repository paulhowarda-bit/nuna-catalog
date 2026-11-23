# 2026-11-23, item 112: refs in the engine matrix, a tag's credentials on a real store, and docs/refs.md

Branch `worktree-agent-a5e5f6e6ecd0db4ce`, from 58aed570, main merged in at 7d7609b7. `backlog/112.md` says what
was built, the choices made and where the code differs from the item's plan.

- `EngineMatrixTest#refs` (overnight, new, Order 6; SingleStore now 7):
  - Spark and PyIceberg read tag `m8-tag` through `engines@m8-tag`.
  - Each is refused a data write there by the store and a property change by its own client.
  - Each commits a row to two tables on branch `m8-dev`, and main is untouched.
  - The branch merges through the management API (dry run, then the merge). Both engines read main, and the tag is
    unchanged.
  - Scripts `images/python/refs_matrix.py` and `images/spark/refs_matrix.py`, added to both images.
- `VendingRouteTest#aTagsCredentialsRefusePutObject` (heavy, new, M6's adversarial set): a tag's credentials read the
  table, and the run's S3 store refuses PutObject, multipart upload and delete. The same caller's credentials on main
  write. Bite `bites/D33/rest-tag-vends-write-on-a-real-store.patch`, checked with `git apply --check`.
- `docs/refs.md` (new), for engine users and operators, linked from `deploy/README.md`'s "Branches and tags".
- `build-plan.md`: the v1.x line for branches, tags and merge says what is built and what closes it; not done.

Checked: `./gradlew build` (fast) green after merging main: "All 571 bites apply.", "BUILD SUCCESSFUL". It compiles
both new tests.

Not covered: neither new test has run. The overnight run's `./nightly.sh` runs the engine case and its heavy build runs
the vending case. The new bite has not been run, only checked to apply.

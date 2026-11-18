# 2026-11-18, the project is renamed keel to nuna, short for nunatak (backlog 59)

Changed: every tracked path and text file, keel, Keel and KEEL to nuna, Nuna and NUNA: 1,002 files edited and 602
paths moved (packages `nuna.catalog`, `nuna.changefeed` and `nuna.maintenance`, `nuna.*` settings, `NUNA_*`
variables, `X-Nuna-End-User`, metric names, the Helm charts `nuna-catalog` and `nuna-maintenance`, the demo's
namespace `nuna-demo` and home `~/.nuna-demo`, the overnight folder `nuna-overnight` and task `nuna overnight`, and
`CLAUDE.md`'s paths, which name `C:/Users/paulh/code/nuna-catalog`). Both names have four letters, so every
fixed-length value still fits. `docs/install-at-work.md` records the rename.

Checked: `git grep -i keel` finds only the notes of the rename and the result-file names of the overnight run of
2026-11-18 (items 60 to 62). `./gradlew build`, run with the keel-demo stack
taken down on Paul's word: BUILD SUCCESSFUL, 1,329 tests in 11 modules, none failed, and `:bitesApply` passed (every
recorded bite still applies). Three fast builds beside the running demo had failed only on Docker waits: PostgreSQL
connections that never opened, Ryuk unreachable, Kafka never starting.

Not covered here: the heavy build and the bites run in the overnight run. Outside the repository: the GitHub
repository, `keel-overnight`, `~/.keel-demo`, the demo, the old images and the 07:00 report task follow after
landing. The checkout folder, the Claude records keyed by its path and the overnight task's registration move with
`C:\Users\paulh\code\rename-to-nuna-catalog.cmd`, run with the Claude app closed.

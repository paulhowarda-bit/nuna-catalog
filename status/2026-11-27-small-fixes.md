# 2026-11-27: backlogs 309 to 312

Branch `worktree-small-fixes`.

- 309: a purge claim on a table a ref names is a 409 naming the refs, not a 500.
- 310: an OPA ask naming no catalog gets no filter instead of throwing.
- 311: a parameter of the wrong type is a 400 naming it, not a 500.
- 312: both exports (grants, row and column rules) are audited as a new record, `nuna.catalog.export`.

Bites: `D20/opa-no-catalog-throws`, `M8/parameter-mismatch-is-500` and `D23/export-unaudited` bite;
`D33/purge-claim-referenced-unanswered` is recorded but not run, since its test is heavy.

Not run here: the heavy tests `HeldTablesTest`, `MaintenanceNightsRoutesTest` and `SecurityGateTest`, since Podman
was not up. The overnight run runs them and the D33 bite.

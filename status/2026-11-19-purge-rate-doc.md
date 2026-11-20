# 2026-11-19, the purge rate limit is documented as per replica (backlog 11)

Paul decided on 2026-11-19 that `nuna.governance.purges-per-minute` stays per replica and the docs say so (D30,
amended). `deploy/README.md`, Before production, now has a "Purge rate" entry: each server counts on its own, so
three servers set to 10 accept 30 purges a minute together; to hold the deployment to a rate, divide it by the
replica count; a claim over the limit answers 429 with `Retry-After: 60`, and the worker retries on its next scan.

Checked against the code: `LifecycleRoutes` keeps the count in the server's own memory and answers 429 with
`Retry-After: 60`; `catalog-maintenance`'s `Management` maps 429 to `Claim.RATE_LIMITED`, "try next scan".

Markdown only, so no build (CLAUDE.md, Landing).

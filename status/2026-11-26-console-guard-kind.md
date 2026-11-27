# 2026-11-26, the console browser tests' guard sees the demo in kind (backlog 296)

- `ConsoleBrowser.refuseBesideTheDemo` counts the demo's ready pods inside the kind node container of cluster `nuna`
  (its own `crictl`, through Testcontainers' Docker client) as well as Docker Desktop's labelled containers.
- With the demo up, `ConsoleLabelsBrowserTest` refused ("10 of its pods"); with `-Pnuna.demo-beside` it passed, 4 tests.
- Not run with the demo down.

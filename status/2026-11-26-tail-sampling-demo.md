# 2026-11-26, tail sampling in the demo's collector (backlog 265, its home part)

- `demo/k8s/metrics.yaml`: the traces pipeline runs `tail_sampling` (status ERROR, over 300 ms, 10 % of the rest) on
  the contrib collector image 0.166.0 (`demo/demo.sh`), memory limit 256 MiB.
- Checked by running the configuration with a file exporter: of 202 traces sent, the ERROR one, the slow one and 19 of
  200 others kept. Not run in the live demo (the metrics switch is off and left off).
- `docs/observability/README.md`, "Sampling", points work's set-up (item 43) at it. Item 265 stays open for work.

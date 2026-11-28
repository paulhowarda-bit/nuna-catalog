# 2026-11-28, overnight run

Tested ce73c9d0 (Land the two disagreements backlog 317 left; last tested a7a929ac), 01:37 to 06:25. A first pass
from 00:07 on f5c61065 failed the same way within minutes; the run started again at 01:37 on the new tip.

- Heavy build: FAILED, 10 min, 373 failed tests. All but one failed at their start: Testcontainers found no Docker
  environment, or a Spring context did not load because of it (item 337). The other is
  `ScaleTest#demoShFillsTheScaleIntoTheGeneratorsManifests` in demo-datagen: demo.sh's scale line changed in
  1fa8fd6f and the test's literal no longer matches; it is in the fast build too (item 338).
- Ceph leg: FAILED, 2 min, 31 failed tests in catalog-objectstore and catalog-maintenance, all no Docker
  environment (item 337).
- Nightly: passed, 120 min. Counts: catalog-maintenance 3, catalog-store-mongo 55, catalog-store-oracle 55,
  catalog-store-sql 55, catalog-store-sql-raft 55, catalog-tests-engines 7, catalog-tests-load 12, every other module
  0 (catalog-audit-tables, catalog-audit, catalog-core, catalog-iceberg, catalog-objectstore, catalog-security,
  catalog-server, catalog-store-api, catalog-tests-conformance, catalog-tests-docker, catalog-tests-s3, demo-datagen,
  singlestore-change-feed).
- Bites: 366 of 988 ran in 153 min, all 366 bit. 226 not run, their tests having failed tonight. The deadline
  stopped the rest; the next run starts at `bites/D30/scan-end-keeps-start-storage-oracle.patch`.
- Agents: none started; the claude CLI is not signed in (`claude auth login`), the third night running. This report
  filed their failures.
- nuna-demo: not running, so neither taken down nor rebuilt.
- A heavy run by hand (`.overnight/by-hand-heavy-2026-11-28`) has held `.overnight/running` since 06:26 and reaches
  Podman.

Logs: `.overnight\runs\2026-11-28` in the main checkout.

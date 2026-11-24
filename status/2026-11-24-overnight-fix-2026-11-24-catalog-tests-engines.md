# 2026-11-24, the engine matrix's refs case: its tables made v2 (item 112)

The overnight run of 2026-11-24 ran `EngineMatrixTest#refs` for the first time. It failed in its setup Job: PyIceberg
0.12 cannot write a manifest for the catalog's default v3 tables (`ValueError: Cannot write manifest for table
version: 3`), so nothing after the setup ran.

Changed: `catalog-tests-engines/images/python/refs_matrix.py` makes `m8.refs.a` and `m8.refs.b` with `format-version`
2, as `pyiceberg_matrix.py` does for the tables PyIceberg writes.

Checked: `./gradlew build` (fast) green after merging main at 21cf9506. The engine matrix is overnight only and was
not run; the next overnight run checks the fix and, for the first time, the refs case past its setup. Item 112 stays
open: it closes on the first green overnight run with items 102 to 111 landed.

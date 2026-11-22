# atelier tool registry
Check here before doing any mechanical task by hand.

| Tool | Version | Purpose | Usage | Deps | Licenses | Tests | Last verified |
|---|---|---|---|---|---|---|---|
| inline | 1 | Package a marker source into one offline name_vN.html: inline vendored libraries and fonts (checksums verified), brand assets, and a CSP that blocks all network traffic; never overwrites; lints the result | `python tools/inline.py examples/ledger_live.src.html --out-dir ~/atelier-out/ledger_live` · `python tools/inline.py --catalog` | stdlib (tools/ddlib) | vendored libraries MIT, Apache-2.0, ISC; fonts OFL-1.1 (vendor/licenses/) | tools/tests/test_inline.py | 2026-11-21 |
| check | 1 | Offline lint of a demo: offline integrity, motion, CSS, type, copy, disclosure, accessibility, contrast, size; exit 1 on any error | `python tools/check.py out/demo_v3.html --json` · `--source` for a marker file | stdlib (tools/ddlib) | n/a | tools/tests/test_check.py | 2026-11-21 |

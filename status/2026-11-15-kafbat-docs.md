# 2026-11-15, the README and demo.sh say what kafbat fences off and audits (backlog 36)

Changed: `demo/README.md` (kafbat's row in the people's table and the paragraph beneath it) and the sign-in text
`demo.sh` prints now describe backlog 35's kafbat: ana, gary and stella only read `keel.*`, `cdc-connect-*` and
topics starting with `_`; kafbat's audit log is topic `__kui-audit-log`, read in kafbat by `demo-operators`, and
its log. Written after backlog 34 landed, which held both files and rewrote their sign-in text.

Checked: `bash -n demo/demo.sh`, and the fast build.

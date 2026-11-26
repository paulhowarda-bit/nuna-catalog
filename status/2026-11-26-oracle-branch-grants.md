# 2026-11-26, Oracle reads branch grants again (backlog 288, after 113)

The by-hand heavy run on main at 4c5e0084 (Podman) failed `OracleStoreSuiteTest$Grants`, "Branch grants: a grant may
name a branch beside the same grant on every ref ...", with `NumberFormatException: Error at index 0 in: "?"` at
`OracleGrants.grant` (`UUID.fromString` of the row's `ref_scope`).

- Cause: item 113's `V20__ref_grant.sql` builds a branch grant's `ref_scope` with `REGEXP_REPLACE(RAWTOHEX(ref_id),
  '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')`, but the file holds the bytes 0x01 to 0x05 where `\1` to `\5`
  belong (`od -c` of line 27): the escapes were read as octal when the file was written. Every branch grant's ref came
  back as those control characters, `"?"` in Java.
- Fix: `V21__any_grant_ref_scope.sql` replaces `any_grant` with a view that cuts the hex with `SUBSTR` and joins the
  parts with dashes, lower-cased, so its text has no escapes to lose. V20 stays as it landed, so a database that
  applied it validates and V21 mends the view.
- The other stores: PostgreSQL (V19) and CockroachDB (V18) cast the uuid to text and MongoDB writes `UUID.toString()`,
  the same lower-case dashed form Oracle now gives; no other source file holds such control characters. Only Oracle
  changed.
- Bites: none moved; the fix adds no check, so none recorded.
- Checked: `./gradlew :catalog-store-oracle:test --tests '*OracleStoreSuiteTest$Grants' -Pheavy`, 12 tests, 0 failures,
  0 errors, 0 skipped (1 m 58 s, Podman). Then the fast build after merging `main` (which had not moved), green, all
  bites apply.
- Not run: `bites/run.sh`; the other stores' grant suites (their code did not change).

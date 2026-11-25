# 2026-11-24, rules keep to their table's columns (backlog 240)

**Changed:** row filters and column masks are now checked against the table's columns (backlog 240):

- **Setting a rule:** a mask or filter that names a column the table's current schema lacks is refused 400, naming
  it. A mask is stored under the schema's spelling of its column.
- **A schema change:** a commit, transaction or merge that renames or drops a column a rule names is refused 403,
  naming each rule's group, and audited as a denial with reason `row_column_rule`.
- **Trino's answer:** when Trino's column-mask batch does not name a column the user's masks name, every column of
  that table comes back NULL. This covers a rename that races a rule being set, and an old snapshot or branch whose
  schema named the column otherwise.

The design refuses the change rather than keying masks by field id; the item says why. There is no store change.
`TableFacts` gains the current schema's columns.

**Checked:** each test class was run on its own; the whole build was not run.

| Test class | Result |
|---|---|
| `RuleColumnsTest` | 8/8 |
| `OpaAnswersTest` | 9/9 |
| `GovernanceRefusalTest` | 13/13 |
| `RulesTest`, `StorageRulesTest`, `catalog-iceberg` tests | pass |
| `SecurityGateTest`, `-Pheavy` | the new test passes; 2 failures |
| `MutatingRoutesAuditTest`, `-Pheavy` | 1 failure |

The three heavy failures come from outside this change:

- in `SecurityGateTest`, the grant and engine audit-log waits time out because the test's setup writes `{}` to the
  outbox and the relay stalls on it;
- in `MutatingRoutesAuditTest`, the route list has no case for the OPA routes.

Five bites are recorded in `bites/D20/`. Each failed on an assertion when broken by hand, and each passes
`git apply --check`.

**Not covered:**

- Trino batching every column of a table was read in its source, not seen live. The demo was down.
- A filter's columns are read from its words until item 244's parser.

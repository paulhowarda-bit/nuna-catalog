# Local demo: the catalog and its web console

One command starts PostgreSQL, Kafka, Silo, the catalog server (with the console) and Trino, loads sample
tables, and prints two sign-in tokens. Everything is local and uses test keys; nothing here is fit for
production.

## Start

```bash
demo/demo.sh up
```

- Needs bash (Git Bash on Windows), Docker with compose, `curl`, and JDK 25 (`JAVA_HOME`, or `java` on the
  path). Every `up` builds the server jar from this checkout (`./gradlew :catalog-server:bootJar`, no tests
  run), so the stack never runs a jar older than the code; when nothing changed, Gradle does nothing.
- The stack is compose project `fidice-demo`, run from a copy in `~/.fidice-demo` (`FIDICE_DEMO_HOME`), so it
  keeps running if this checkout changes. The engine matrix's project `fidice-engines` is separate.
- Host ports: **8181** (catalog and console) and **8183** (Trino). Nothing else is published.
- Memory: about 3.8 GB of container limits (Trino 1.4 GB, the catalog 900 MB, Kafka 640 MB, Silo 512 MB,
  PostgreSQL 384 MB).

At the end it prints the console URL, <http://localhost:8181/ui/>, and the tokens.

## Sign in

The demo turns on the console's token box (`fidice.ui.dev-token-login=true`, off by default). Paste a token:

| User | Group | What the catalog lets them do |
|---|---|---|
| `ana` | `demo-analysts` | select on the whole catalog: browse, load tables and views, read governance |
| `gary` | `demo-grant-admins` | grant admin: list, grant and revoke, and read the audit trail; no data access, so loading a table is refused |
| `stella` | `demo-stewards` | manage-tags: classifications, owners, legal holds |
| `oscar` | `demo-operators` | platform operator: DR freeze |
| `trino` | `demo-loaders` | create and modify; Trino's own token |

Separation of duties means no one group holds data rights and grant rights, so try the console as `ana` and as
`gary`. Every refusal is the server's, shown as it answered.

Tokens are valid for 7 days. Print them again (re-minted if they are about to expire):

```bash
demo/demo.sh token
```

Tokens are in `~/.fidice-demo/tokens/<user>.jwt`. A curl call as a user:

```bash
curl -H "Authorization: Bearer $(cat ~/.fidice-demo/tokens/ana.jwt)" "http://localhost:8181/v1/lakehouse/namespaces"
```

## What is loaded

Catalog `lakehouse` (Trino catalog `fid`):

- `markets.equities.eu.xetra.trades`: v2, partitioned by day, sorted by symbol; five appends, a row-level
  delete, two added columns (one a nested struct), a second partition spec, a tag `eod-2026-11-09` and a
  branch `surveillance-review`; classified `internal` and under a legal hold.
- `markets.fx.rates` (v3, evolved), `markets.equities.us.quotes` (v3), `reference.countries` (v2, under a DR
  freeze), `finance.ledger.journal` (v3, classified `confidential`) and `finance.ledger.accounts` (v2).
- `finance` is marked regulated, and `accounts` has no classification of its own, so the catalog refuses to
  load it for anyone (D27): the console shows that refusal.
- View `markets.equities.eu.xetra_daily`, with two versions.
- Grants by SQL for the five groups, plus two made through the API by `gary`.

## Trino

```bash
demo/demo.sh trino
```

Then, for example: `SHOW SCHEMAS;` or `SELECT * FROM "markets.equities.eu.xetra".trades;`. Nested namespaces
are dotted schema names.

## Stop

```bash
demo/demo.sh down
```

This removes the containers and their data. The signing key and tokens stay in `~/.fidice-demo`; the next
`up` loads the sample data again.

```bash
demo/demo.sh status
```

# The console redesign

Atelier, Paul's UX agent, redesigned the nuna console on 2026-11-21 and 2026-11-22. Paul asked for the redesign
because the console must "sell the catalog as the best iceberg catalog / maintenance system on the internet". On
2026-11-22 he decided to build all of it, new server routes included (D45).

Each file here is one offline HTML page. Open it in any browser. It needs no server and makes no network calls.

| File | What it is | Atelier's version |
|---|---|---|
| [prototype.html](prototype.html) | The console as it will be built, to click through. It has every page, every tab on every table, both demo catalogs, light and dark, and phone width. The data is illustrative but agrees across pages. | `console_prototype_v11` |
| [wireframes.html](wireframes.html) | The document behind the prototype. It holds the three directions considered (Ice line was chosen), every screen with numbered notes, and each panel's data status (whether its route exists today, needs a new route, or is proposed). It also has the design tokens, type scale and component states. | `wireframes_v17` |

Atelier's working copies and every earlier version are in `~/atelier-out/nuna-console-redesign/` on Paul's home
machine. The prototype's sources are in `src/proto_v1/parts/` there.

## Signing in to the prototype

- **Sign in** signs in as `presenter`, who sees everything.
- **Use a developer token** signs in as `gary`, a grant admin. gary sees only Catalog, Governance and Audit, as the
  server would allow.
- To see the separation-of-duties refusal, go to Governance, then Grants. Grant `manage-tags` to `demo-analysts` on
  `finance`. The refusal is the server's own message, word for word.

## What the build needs that the server lacks

The prototype assumes the following server capabilities, which do not exist today. Each one is a backlog item.

- A catalog-wide audit feed. It needs filters for kind, principal, path prefix and time, and a cursor. Each record
  shows its place in the hash chain and its verification status. The feed also needs the archive check's status,
  counts by kind and the busiest objects of the last day.
- Audit for a namespace that includes the namespace's tables and views.
- Each night's maintenance summary: tables compacted, files in and out, snapshots expired, bytes deleted, and jobs
  cut off or failed. Each job's run on its worker slot, for the overnight chart.
- One row per table for the ice line: score, reasons, hold, freeze, and whether it was fixed last night.
- A governance summary per catalog, and a catalog-wide list of holds and freezes with their history.
- Effective grants on an object, including those inherited from its namespaces and catalog.
- A paged listing of a table's metadata log.
- Catalog and namespace properties for the console.
- Compaction or an orphan scan on request (item 114).
- A DR freeze's refusal is not audited today (`Management.requireUnfrozen`). The prototype shows it as unaudited, to
  match the server.

## Building it under the console's rules

The prototype is one file with inline script and style. The console stays as it is built today:
- plain HTML, CSS and ES modules, served from `catalog-server/src/main/resources/nuna/ui/`;
- no third-party code, and pages built from text nodes;
- the strict Content-Security-Policy.

The prototype's code uses no `innerHTML` and no `style` attributes, so it ports under that policy. Its two typefaces,
Instrument Sans and Geist Mono, are under the SIL Open Font License. Served from the console, they need `font-src
'self'` added to the policy.

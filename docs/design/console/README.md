# The console redesign

Atelier, Paul's UX agent, redesigned the nuna console on 2026-11-21 and 2026-11-22. Paul asked for the redesign
because the console must "sell the catalog as the best iceberg catalog / maintenance system on the internet". On
2026-11-22 he decided to build all of it, new server routes included (D45).

Each file here is one offline HTML page. Open it in any browser. It needs no server and makes no network calls.

| File | What it is | Atelier's version |
|---|---|---|
| [prototype.html](prototype.html) | The console as it will be built, to click through. It has every page, every tab on every table, both demo catalogs, light and dark, and phone width. The data is illustrative but agrees across pages. Since v14 it also has catalog branches and tags (item 109): open it with `?user=presenter` to do everything or `?user=gary` to be refused, and add `&refs=full` to see the limit reached. | `console_prototype_v14` (v11 plus the refs work) |
| [wireframes.html](wireframes.html) | The document behind the prototype. It holds the three directions considered (Ice line was chosen), every screen with numbered notes, and each panel's data status (whether its route exists today, needs a new route, or is proposed). It also has the design tokens, type scale and component states. Chapter 6, "Branches and tags" (key 6), specifies item 109's screens for a builder: each screen's layout and a table of every element's words, when it shows and its data, tagged as built (item 106), to build (107) or a gap no item promises yet; it ends with those gaps and the questions for Paul. | `wireframes_v20` (v17 plus chapter 6) |

Atelier's working copies and every earlier version are in `~/atelier-out/nuna-console-redesign/` on Paul's home
machine. The prototype's sources are in `src/proto_v1/parts/` there, and the refs work's in `src/proto_v12/` (prototype) and `src/v18/` (chapter 6).

The refs design (2026-11-22) was drawn on the console as built that day, not on v11 alone, and against the refs routes on branch `refs-106-current` and item 107's merge as item 26 specifies it.

The prototype's free-form "Set a property", on a catalog and on namespaces, is superseded by D45's amendment of
2026-11-22: the console shows every property read-only and edits only listed keys that do something, and a catalog's
settings stay read-only (item 141).

Built on 2026-11-22 (item 141, `settings.js`): a table has a Properties tab and a namespace's Properties tab is
rebuilt the same way. Each shows the settings the console may change, each with its label, what it does, its value or
its default, and Change and Reset; then every property read-only, with a note on any the console does not list (set
by an engine or tool; or a known key that is the catalog's or the maintenance policy's to decide). Values are checked
in the dialog before anything is sent. A table's change is a commit with `set-properties` or `remove-properties`,
asserted against the table's UUID; a namespace's goes through its properties route. Tables offer
`write.format.default`, `write.parquet.compression-codec` and `-level`, `write.target-file-size-bytes`,
`write.distribution-mode`, `write.delete.mode`, `write.update.mode`, `write.merge.mode`,
`write.metadata.metrics.default` and `write.metadata.previous-versions-max`; namespaces offer `comment`. Left out:
`write.metadata.delete-after-commit.enabled` (nothing in nuna deletes metadata files on commit: the catalog writes
them, an engine's REST client deletes none, and the orphan scan removes the files that fall off the log) and a
namespace's `location` (the catalog assigns every table's location from the table's id, never from its namespace,
D28). The catalog's Properties tab links to its maintenance policy, and the namespace page names its governance owner.

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
- Compaction or an orphan scan on request (item 114, built on 2026-11-22).
- A DR freeze's refusal was not audited. The prototype shows it as unaudited, as the server was then. Item 132 now
  audits it, and item 134 covers the refusals that are still unaudited.

## Building it under the console's rules

The prototype is one file with inline script and style. The console stays as it is built today:
- plain HTML, CSS and ES modules, served from `catalog-server/src/main/resources/nuna/ui/`;
- no third-party code, and pages built from text nodes;
- the strict Content-Security-Policy.

The prototype's code uses no `innerHTML` and no `style` attributes, so it ports under that policy. Its two typefaces,
Instrument Sans and Geist Mono, are under the SIL Open Font License. Served from the console, they need `font-src
'self'` added to the policy.

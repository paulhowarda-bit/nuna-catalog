# 2026-11-25, worktree-agent-aecca870ad6ead5e9: item 170, and item 168's ruling and review fixes

Cut from item 168's branch (`worktree-agent-afc067ddcc81941ff`, 9e504a27) with `main` merged in; 168 and 170 land
together.

## Item 168 (already done; finished here)
- `refLine` in `refs.js` no longer takes the unused `list`.
- Atelier's ruling of 2026-11-25 on its three wordings (the On @ref cell, the band's point, Contents' footnote),
  recorded in `backlog/168.md` and pinned by the new fast `ConsoleRefWordsTest`; the heavy `ConsoleBrowserTest`
  expects the new words (compiled, not run).
- The Nuna Security Expert's two fixes, recorded in `backlog/168.md`: totals through a ref no longer walk into a
  namespace the caller holds nothing on; a cut's metadata file whose hash no longer matches raises the metadata
  integrity alert and shows no facts. `NamespaceTablesRoutesTest` +2 (21 green); bites
  `bites/D33/namespace-totals-ref-walks-hidden-namespace`, `namespace-tables-ref-integrity-unalerted`.

## Item 170 (done)
- The read of which refs keep a table or view, by path and by id, with who dropped it for an audit reader
  (`RefRoutes`, `AuditDrops`); the table page's four states in Atelier's words with "keeps". Details, choices and
  file:line in `backlog/170.md`, "Done, 2026-11-25".
- Fast tests: `RefRoutesTest` +3, `AuditDropsTest` (3), `ConsoleRefWordsTest` +3. Heavy browser test written and
  compiled, not run. Six bites in `bites/D33/`, recorded and checked with `git apply --check`, not run.
- Who took a table out of a ref comes from item 169's feed filter (`subject=<table id>`, built on its branch at
  a29aa46f, not landed, not merged here); the page matches each record itself, so without the filter it still shows
  the actor when the record is among the newest, and leaves the sentence out when the feed refuses.

## Not checked
The heavy `ConsoleBrowserTest` cases (the overnight run takes them); the recorded bites against their tests; the
pages live on the demo.

# 2026-11-22, the console rebuilt to the redesign (backlog 124, D45)

- Every page the routes on `main` at the start of the branch could fill, ported from
  `docs/design/console/prototype.html` (its markup, CSS tokens, SVG and interactions) into
  `catalog-server/src/main/resources/nuna/ui/`: plain HTML, CSS and ES modules, no third-party code, pages built from
  text nodes with no `innerHTML` and no style attributes, under the same Content-Security-Policy. Sign-in is OIDC with
  PKCE as before; `auth.js` is unchanged.
  - The shell: the menu as a rail, a drawer at phone width; the catalog picker; Go to a path (`/`), the keys (`?`); the
    theme (light, dark, auto), kept in `localStorage` as `nuna.theme`. `sessionStorage` still holds only the token.
  - Pages: sign-in; the overview with the ice line of tonight's queue; catalog browse with its tree (filter, queue
    marks) and the catalog's, a namespace's (Contents, Properties, Governance, Grants, Audit), a table's (Overview,
    Schema, Snapshots and refs, Partitioning, Health, Governance, Grants, Audit, Metadata log) and a view's (Overview,
    Versions, Grants, Audit) pages; Maintenance (the queue, the catalog's policy, the worker); Governance (grants by
    group with the duties, revoke, export for an access review; the catalog's own holds and freezes); Audit; Health
    (deployment figures, servers, the worker, which is on Health too since operators may hold no catalog).
  - Refusals show the status and type, the server's message word for word and its `X-Request-ID`.
  - `detail.js` is gone: its parts are in `browse.js`, `table.js`, `panels.js` and `tree.js`.
- Left out, each waiting on its item: the ice line's tables in shape and Contents' snapshots and last commit (127);
  Compact now (114). The DR replication and Store panels on Health have no route at all.
- The panels of items 128 to 131, which landed on `main` while the branch was built, added after merging it (at
  2e7e640):
  - 128: Governance opens on a Summary (classified, held, frozen and regulated figures; the holds and freezes standing,
    each with Release or Lift; each regulated area with what is closed until classified). Holds and freezes lists
    those standing and those lifted, with how far the audit topic was read, above the catalog's own governance. The
    tree marks held, frozen and regulated namespaces and objects, Contents has a Governance column, and the overview
    a governance figure for whoever may describe the catalog.
  - 129: a namespace's, table's or view's Grants tab lists every grant that reaches it, each with where it was made
    (here, the catalog, or a namespace above, linked), and a "Who may" panel: who reads, changes data, manages tags
    and manages grants, the deployment's grant admins included.
  - 130: a table's Metadata log is the commit journal, 25 commits a page, each with its version, time, the update
    actions, who made it (principal, the end user an engine acted for, the engine) and, opened, its metadata file and
    updates.
  - 131: the catalog page has a Properties tab: name, legal entity, storage profile, endpoint, region, table root,
    allowed prefixes, encryption and KMS key set.
  - Tests: `ConsoleBrowserTest` checks the tree's marks and Contents' column, the journal against the route, grants
    from above and "Who may", and two new tests: the governance summary with a released hold listed as lifted and the
    overview's figure, and the catalog's properties. `bites/UI/console-tree-drops-views.patch` is recorded again
    against the new tree, with the same break; every bite in `bites/UI/` and `bites/D45/` applies (`git apply
    --check`).
  - Checked against the local stand-in in a browser, extended with these routes: every new panel, the next page of the
    journal, no console errors and no sideways scroll at 390 px. No build or test was run for this part: the
    coordinator held builds while a by-hand nightly ran.
- Fonts: Instrument Sans and Geist Mono, with their licences, under `/ui/`, `woff2|txt` in `UiWiring`'s file
  pattern, `font-src 'self'` in its policy and `@font-face` rules in `style.css`: committed on this branch by the
  coordinator (1224021) with Paul's approval of the downloads. `bites/UI/csp-allows-inline-script.patch` is recorded
  again against the new policy line, with the same break.
- The panels of items 125 and 126, added after merging `main` at fc4d7aa:
  - 125: the Audit page is the catalog's feed from the audit tables, newest first, filtered by kind (with the last
    day's counts on each chip), person, table or namespace and time, 50 a page. Each record opens to its request id,
    its partition and offset, whether its chain is verified past it, its link in the archive (checked on asking) and
    the event itself. Beside it: the hash chain and the archive check's last run, the last day's counts by type and
    the most recorded objects. The overview has an audit figure. These routes are for audit readers only; anyone
    else, or a server without the audit tables, gets the answer verbatim in the feed, and below it the catalog's own
    records from the audit topic as before.
  - 126: the Maintenance page leads with the newest night's figures, each with a sparkline over the last 30 nights,
    and draws the night's compaction Jobs on their slots and its orphan scans in a lane of their own, with the window's
    end dashed. A job links to its table's Health tab only for a caller who may read the table. The overview has
    "Maintenance, last night" and the same chart. The totals are the whole deployment's, as the route answers them,
    and the page says so.
  - `sizeWords` moved from `table.js` to `dom.js`; `nights.js` is new (and in `ConsoleTest`'s list of files).
  - Tests: `ConsoleBrowserTest` reports a compaction Job and an orphan scan as the worker for a night no other test
    uses, and checks the chart, the figures and the overview; and checks that the Audit page shows the feed's 503 as
    answered (this test server has no audit tables) with the catalog's own trail below. The feed with real records is
    checked only against the local stand-in; the route tests cover the routes.
- Tests: `ConsoleBrowserTest` and `ConsoleHealthTest` (heavy) rewritten for the new pages, with a theme test and a
  phone-width test (drawer, no sideways scroll on six pages); `ConsoleSignInTest` and `ConsoleTest` follow the new
  files. The five console bites in `bites/UI/` are recorded again against the new code, with the same breaks.
- Checked: every page, tab, dialog and refusal against a local stand-in for the routes in a browser (no CSP
  violations, every page 390 px wide at phone width, themes switching), and the tests' selectors against it. The
  heavy tests and the bites were not run here: the overnight run runs them.
- Fast build after merging `main` (at d23015d): `./gradlew build` green in 4 m 46 s; all 438 bites apply.

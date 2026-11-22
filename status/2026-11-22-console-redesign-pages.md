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
- Left out, each waiting on its item: the catalog-wide audit feed with its chain and archive checks (125); last
  night's maintenance, the gantt and the 30-night sparklines (126); the ice line's tables in shape and Contents'
  snapshots and last commit (127); the governance summary, every hold and freeze, the tree's held, frozen and
  regulated marks (128); grants that reach an object from above (129); who made each metadata change (130); catalog
  properties (131); Compact now (114). The DR replication and Store panels on Health have no route at all. Items 128
  to 131 landed on `main` while this branch was built; their panels are not in it yet.
- Fonts not done: Instrument Sans and Geist Mono were not downloaded (no download was approved in this run). The
  console uses the system font stacks already in the tokens, and the policy has no `font-src`. To add them: the woff2
  files and `OFL.txt` under `/ui/`, `woff2|txt` in `UiWiring`'s file pattern, `font-src 'self'` in its policy, and
  `@font-face` rules in `style.css`.
- Tests: `ConsoleBrowserTest` and `ConsoleHealthTest` (heavy) rewritten for the new pages, with a theme test and a
  phone-width test (drawer, no sideways scroll on six pages); `ConsoleSignInTest` and `ConsoleTest` follow the new
  files. The five console bites in `bites/UI/` are recorded again against the new code, with the same breaks.
- Checked: every page, tab, dialog and refusal against a local stand-in for the routes in a browser (no CSP
  violations, every page 390 px wide at phone width, themes switching), and the tests' selectors against it. The
  heavy tests and the bites were not run here: the overnight run runs them.
- Fast build after merging `main` (at d23015d): `./gradlew build` green in 4 m 46 s; all 438 bites apply.

# 2026-11-28, backlog 333: the brand slot, the Brand mode button and the partial work pack

Built on item 332's branch (`worktree-agent-acfdef84a0c61b4a0`, not yet landed), which touches the same files.

What changed:

- Settings `nuna.ui.brand.dir`, `.name`, `.mark-label`, `.show-name`, `.toggle-label` (`UiSettings.Brand`); the words
  reach the console as `brand` in `/ui/config.json`; the folder stays on the server.
- `UiWiring` serves `/ui/brand/*` and `/docs/assets/brand/*` from the pack's folder, falling back to the jar's empty
  `brand.css` and Ice line's icon; brand files are hashed per request, so a changed ConfigMap is served without a
  restart. The console's public paths take `/ui/brand/` files, fonts in `woff2`, `woff`, `ttf` or `otf` included.
- The console's `index.html` and every docs page link the brand stylesheet at its fixed path; `brand.js` (console)
  and `theme.js` (docs) read the pack before the first paint. Role tokens for the header band, the rail's current
  page and the requested pill, each defaulting to Ice line's value. The product's name replaces the hard-coded
  "nuna" in titles and messages while the pack is on.
- The Brand mode button (Paul, 2026-11-28; D56): beside the theme switch where a pack is set, on by default (off by default since item 345),
  off gives Ice line, kept in the browser; a light-only pack pins light and hides both theme switches while on.
- A neutral test pack (`catalog-server/src/test/resources/brand-pack/`) and the partial work pack
  (`deploy/brand/work/brand.css`, colours only), with `docs/brand-at-work.md` telling work how to finish it.
- Helm `brandConfigMap.name`; docs `configure/brand.html` (new), `configure/server.html`, `configure/helm-values.html`.
- Tests: `BrandSlotTest` (new, fast), `ConsoleBrandTest` (new, heavy, browser), `ConsoleFilesCacheTest`,
  `ConsoleTest`, `ConsoleConfiguredTest`, `ConsoleRulesTest`, and the constructor calls in `DocsSiteTest` and
  `CatalogListTest`.
- Bites: six new in `bites/UI/` (`brand-*`), four recorded again (`console-files-by-last-modified`,
  `console-sign-out-keeps-idp-session`, `console-tree-none-goto-empty`, `dev-token-login-on-by-default`). All 991
  bites `git apply --check` clean. (The bites commit's message says seven; it is six.)

Not checked: no Gradle command ran, since the overnight run held `.overnight/running` throughout. `node --check`
passed on `app.js`, `auth.js`, `dom.js`, `brand.js`, `theme.js` and `docs.js`; `docs/site/tools/check.py`: 157 files,
0 errors. The console was looked at on a stub server with both packs (header fit at 761 to 1,401 px, pack on and
off, the phone menu); not against a real server. The Helm template was not rendered.

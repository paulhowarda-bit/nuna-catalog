# Fidelity mode at work: finishing the brand pack

The console and the documentation can wear an install's own look through a brand pack (backlog 333;
`docs/site/configure/brand.html` gives the slot and its token contract). Paul, 2026-11-28 (D56): "Yes do the partial
pack and give instructions to work to complete it. Add the button." This repository is on Paul's personal GitHub, and
the firm's fonts and logo are licensed to associates and vendors only, so they never enter it. What could come here
did: `deploy/brand/fidelity/brand.css`, the colours of Atelier's work mock-up (work item 011) as the slot's tokens.
The work install adds the rest, below, and keeps those files at work only.

Until it does, the partial pack already works: Fidelity's colours, an Evergreen header band with the peak drawn white,
Ice line's fonts, Ice line's tab icon, and the "Fidelity mode" button beside the theme switch, which turns the pack
off and on for each viewer.

## 1. Gather the licensed files (work only)

Into a folder on a work machine, beside a copy of `deploy/brand/fidelity/brand.css`:

- `fidelity-sans.woff2` (and a bold cut if it ships separately): Fidelity Sans, from the Brand Team's font
  distribution. `woff2` only if you can; `woff`, `ttf` and `otf` are served too, but are larger.
- `mark.svg`: the white word mark, for the Evergreen header band. The slot draws it in a fixed box at most 20 px high
  and 120 px wide, left-aligned, never squashed or cropped; export it with its own clear space trimmed, at a ratio of
  6:1 or narrower. Note its width when drawn 20 px high.
- `favicon.svg`: only if the Brand Team allows the firm's symbol as a browser tab icon on an internal tool. Without it
  the tab keeps Ice line's peak, which is fine.

File names are lower case letters, digits and hyphens: the server serves nothing else from the pack.

## 2. Finish `brand.css`

The file's own comment shows the lines to add. In short:

```css
@font-face { font-family: 'Fidelity Sans'; src: url('fidelity-sans.woff2') format('woff2'); font-weight: 400 700;
  font-display: swap; }

:root {
  /* beside the tokens already there */
  --f-ui: 'Fidelity Sans', 'Instrument Sans', system-ui, sans-serif;
  --brand-mark: 1;
  --brand-mark-w: 104px;  /* the mark's width at 20 px high, at most 120px */
  --mark-h: 20px;
}
```

Leave `--f-mono` as Ice line's Geist Mono unless the Brand Team names a monospace face: table names, snapshot ids and
SQL need one whose digits and punctuation are unambiguous.

## 3. The settings

Under the chart's `config` (`docs/install-at-work.md`, section 5):

```yaml
nuna:
  ui:
    brand:
      name: Nuna                    # in titles, on the sign-in page and in messages
      show-name: false              # the word mark spells the name; drop this line until mark.svg is in the pack
      mark-label: Nuna              # what a screen reader says for the header mark
      toggle-label: Fidelity mode   # the button beside the theme switch
    oidc:
      name: Fidelity single sign-on # the sign-in button: "Sign in with Fidelity single sign-on"
```

`nuna.ui.oidc.name` is item 332's setting and works with or without the pack. The chart sets `nuna.ui.brand.dir`
itself (step 4).

## 4. Mount the pack

```bash
kubectl -n <namespace> create configmap nuna-brand \
  --from-file=brand.css --from-file=mark.svg --from-file=fidelity-sans.woff2 [--from-file=favicon.svg]
```

`kubectl` puts the font in `binaryData` itself. A ConfigMap holds 1 MiB in all, enough for one or two `woff2` cuts.
Then set the chart's value and upgrade:

```yaml
brandConfigMap:
  name: nuna-brand
```

The chart mounts it at `/etc/nuna/brand` and sets `NUNA_UI_BRAND_DIR`. The words need a restart (the upgrade gives
one); a later change to the ConfigMap's files reaches the next page loaded without one, once the kubelet has synced
the mount (about a minute).

## 5. Check it

- `/ui/brand/brand.css` and `/docs/assets/brand/brand.css` answer with the pack, and `/ui/config.json` carries
  `brand` with the words above.
- The sign-in page and the console show Fidelity Sans, the mark and the Evergreen band from the first paint, with no
  flash of Ice line and no errors in the browser's console; the header fits from 761 px up with the catalog and
  branch menus open, and on a phone the button is in the menu.
- "Fidelity mode" turns the pack off (Ice line, the peak, "nuna", the theme switch back) and on again, and the choice
  survives a reload.
- Both screens pass the contrast check (body text 4.5:1). Fidelity Green on white is about 4.5:1, so the pack uses
  the dark green (#1e6f1d) for links and text and keeps Fidelity Green for marks; keep it that way.

## Open at work

- **Dark mode.** The pack is light-only (`--brand-light-only`): while it is on, the console and the documentation stay
  light and both theme switches go. If the firm's design system has dark-mode tokens, take out `--brand-light-only`
  and add the dark values under `:root[data-theme="dark"]` and, inside `@media (prefers-color-scheme: dark)`, under
  `:root:not([data-theme="light"])`, as `configure/brand.html` says. Turning the pack off gives the viewer Ice line's
  dark mode either way.
- **The name and the label.** "Nuna" as the product's name, "Fidelity mode" on the button and "Fidelity single
  sign-on" on the sign-in button are Paul's working words, not cleared. Before the console is shown beyond the team,
  check with the Brand Team (the mark's use on an internal tool, the favicon, the colours) and with Legal (naming an
  internal product, and whether "Nuna" may be used). Each is one setting to change; nothing in the code names them.
- **The values marked "derived"** in `brand.css` (a stronger line colour, the sky grey, the header's softer ink) are
  Atelier's, not the firm's; the Brand Team may have their own.

# Fidelity mode at work: finishing the brand pack

The console and the documentation can wear an install's own look through a brand pack (backlog 333;
`docs/site/configure/brand.html` gives the slot and its token contract). Paul, 2026-11-28 (D56): "Yes do the partial
pack and give instructions to work to complete it. Add the button." This repository is on Paul's personal GitHub, and
the firm's fonts and logo are licensed to associates and vendors only, so they never enter it. What could come here
did: `deploy/brand/fidelity/brand.css`, the colours of Atelier's work mock-up (work item 011) as the slot's tokens.
Paul, 2026-11-28 (backlog 344): "I want it to run with the pack we created. At work it should just install the logo
and fonts." So the pack in `deploy/brand/fidelity/` is finished apart from two licensed files: its `brand.css` already
names the font and the mark, its `mark.svg` is a placeholder, and the demo runs it as it stands. Work adds the font,
replaces the placeholder with the logo, and keeps both files at work only.

Until it does, the pack already works: Fidelity's colours, an Evergreen header band, a placeholder mark (an Evergreen
badge with the peak and "nuna"), Ice line's fonts (the browser notes the one missing font file), Ice line's tab icon,
and the "Fidelity mode" button beside the theme switch, which turns the pack off and on for each viewer.

## 1. Add the font and the logo (work only)

Copy `deploy/brand/fidelity/` to a folder on a work machine, and put in it:

- `fidelity-sans.woff2`: Fidelity Sans, from the Brand Team's font distribution, a variable font covering weights 400
  to 700 if there is one. If the regular and bold cuts ship as separate files, also add `fidelity-sans-bold.woff2` and
  in `brand.css` give the first `@font-face` `font-weight: 400` and copy it for the bold file with `font-weight: 700`;
  that is the one edit a split font needs.
- `mark.svg`, over the placeholder: the white word mark, for the Evergreen header band. The slot draws it left-aligned
  in a box 20 px high and at most 120 px wide, never squashed or cropped; export it with its own clear space trimmed,
  at a ratio of 6:1 or narrower. The sign-in page draws the same file on the light Mist background, where a white mark
  disappears: ask the Brand Team for a version that holds on both (the placeholder sits on its own Evergreen badge
  for that reason), or give Atelier the sign-in page to draw on the band.
- `favicon.svg`, optional: only if the Brand Team allows the firm's symbol as a browser tab icon on an internal tool.
  Without it the tab keeps Ice line's peak.

File names are lower case letters, digits and hyphens: the server serves nothing else from the pack. Leave `--f-mono`
as Ice line's Geist Mono unless the Brand Team names a monospace face: table names, snapshot ids and SQL need one whose
digits and punctuation are unambiguous.

## 2. Mount the pack

```bash
kubectl -n <namespace> create configmap nuna-brand --from-file=<the folder>
```

`kubectl` puts the font in `binaryData` itself. A ConfigMap holds 1 MiB in all, enough for one or two `woff2` cuts.
The chart's values in `docs/install-at-work.md` (section 5) already name it (`brandConfigMap.name: nuna-brand`) and
set the words, which need nothing from work unless the names change (Open at work, below):

```yaml
nuna:
  ui:
    brand:
      show-name: false              # the word mark spells the name
      toggle-label: Fidelity mode   # the button beside the theme switch
    oidc:
      name: Fidelity single sign-on # the sign-in button: "Sign in with Fidelity single sign-on"
```

The chart mounts the pack at `/etc/nuna/brand` and sets `NUNA_UI_BRAND_DIR`. A later change to the ConfigMap's files
(`kubectl create configmap ... --dry-run=client -o yaml | kubectl apply -f -`) reaches the next page loaded without a
restart, once the kubelet has synced the mount (about a minute).

## 3. Check it

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

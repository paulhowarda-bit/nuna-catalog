# 2026-11-28: the demo runs the Fidelity pack (backlog 344)

- Paul did not see the Fidelity mode button: the demo ran no pack. He decided (D56, amended): "I want it to run with
  the pack we created. At work it should just install the logo and fonts."
- `deploy/brand/fidelity/brand.css` now names Fidelity Sans (`fidelity-sans.woff2`, falling back to Instrument Sans
  while it is missing) and the mark (`--brand-mark`, a 120 by 20 px box); `mark.svg` is a placeholder, an Evergreen
  badge with the peak and "nuna", so it shows on the header band and on the light sign-in page.
- `demo/demo.sh` puts the pack in ConfigMap `nuna-brand` before installing the catalog; `demo-values.yaml` names it
  and sets `show-name: false` and `toggle-label: Fidelity mode`.
- `docs/brand-at-work.md` asks work only to add the font and replace `mark.svg`; `docs/install-at-work.md`'s values
  carry the words and the ConfigMap. Open for work: a white-only mark disappears on the light sign-in page.

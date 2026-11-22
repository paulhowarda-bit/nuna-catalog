# atelier tool backlog
Candidates not yet built. Each has a source in the designer 1.0.0 toolkit (demo_designer_v2.zip), not carried into
this home; port it from there when it is built.

- **shots**: screenshots of a demo at desktop 1440x900 and mobile 390x844, at about 0.7 s and 3.2 s, plus console
  errors, through an installed Edge or Chrome (headless `--screenshot`, or Playwright on the installed channel).
  Source: ddlib/screenshot.py. Backlog because the Claude Code browser pane covers it, and its test needs a browser.
- **brand-stats**: crawl a brand-guidelines site (same host, up to 40 pages) or a folder of saved pages and PDFs, and
  report its CSS colours (with OKLCH values and contrast pairs) and font declarations: the deterministic half of
  brand ingestion, writing only under ~/.atelier/brand. Source: ddlib/brand_ingest.py and ddlib/colors.py.
- **vendor-manifest**: rebuild vendor/manifest_vN.json (sha256 and licence for each file) after adding a library or
  font. Source: tools/build_vendor_manifest.py.

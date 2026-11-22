# atelier learnings
- 2026-11-21: tools/inline.py rebuilds the toolkit's three packaged examples byte for byte from their .src.html, and each lints clean (source: creation session; confidence: verified).
- 2026-11-21: The knowledge snapshot is from 2026-10-28; browser support for some CSS (scroll-driven animation, view transitions) was disputed then, so it always goes behind @supports with visible-by-default content (source: toolkit README; confidence: stated).
- 2026-11-21: The toolkit was tested end to end with a stand-in model and headless Chromium, never against the work gateway (source: toolkit README; confidence: stated).
- 2026-11-21: check.py reports a fetch() to a URL twice, as an OFF002 error and an OFF002 warning; one fix clears both (source: tools/tests/fixtures/lint_bad.findings.json; confidence: verified).

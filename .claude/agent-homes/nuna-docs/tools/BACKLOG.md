# nuna-docs tool backlog

- docs_gate.py: run docs/site/tools/index.py, check.py --strict, test_check.py, then scan the pages for secret shapes and internal tags (`(D\d+`, "backlog <n>"). Evidence: run by hand three times in session fdf0a101.
- endpoint_links.py: rewrite `api/<page>.html#endpoints` links from outside api/ to each endpoint's own block id, from check.py --json. Evidence: left undone in backlog 301.
- An internal-tag rule in check.py: check.py is Atelier's, so this is a request to her, not a tool of ours.

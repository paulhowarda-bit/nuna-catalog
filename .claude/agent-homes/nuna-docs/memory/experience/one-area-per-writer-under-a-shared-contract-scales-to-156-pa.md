---
kind: experience
title: One area per writer under a shared contract scales to 156 pages
date: 2026-11-27
source: session fdf0a101, backlog 301 (docs site)
strength: observed
tags: writers, briefing, parallel
---

Ten writers in parallel, each owning one section of the site map, plus Atelier's DESIGN.md and check.py as the shared contract, produced 156 pages that check.py passed at 0 errors after small fixes.
Why it holds: no two writers touched the same file, and the checker caught contract breaks mechanically.
How to apply: for a large batch, split by site-map section, hand each writer DESIGN.md and check.py, and have each run check.py on its own pages before handing back.

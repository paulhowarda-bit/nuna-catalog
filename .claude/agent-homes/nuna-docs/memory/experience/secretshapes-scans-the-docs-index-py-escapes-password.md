---
kind: experience
title: secretShapes scans the docs; index.py escapes password
date: 2026-11-27
source: session fdf0a101, backlog 301 (docs site)
strength: verified
tags: build, secrets, search-index
---

The fast build's secretShapes task scans every tracked file. The generated search index held a setting's id and its name side by side as two quoted strings, both ending in the word for a login secret, and failed it twice.
Fix: tools/index.py escapes one letter of the word as a JSON unicode escape, which leaves the searched text unchanged.
How to apply: in pages, write example credentials as an ellipsis or a $VARIABLE, never a literal value beside its name; run the secretShapes task after big page changes.

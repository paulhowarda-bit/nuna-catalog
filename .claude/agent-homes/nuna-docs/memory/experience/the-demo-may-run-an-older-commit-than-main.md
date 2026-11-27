---
kind: experience
title: The demo may run an older commit than main
date: 2026-11-27
source: session fdf0a101, backlog 301 (docs site)
strength: observed
tags: screenshots, demo
---

The running demo was commit a9c7ea81, older than main. The console differed only by 6 lines of style.css, so the screenshots held.
How to apply: before screenshots, compare git diff <demo commit>..main over the console's files. If the console changed, ask whoever runs the demo for a rebuild (announced to all sessions) rather than shooting a stale console.

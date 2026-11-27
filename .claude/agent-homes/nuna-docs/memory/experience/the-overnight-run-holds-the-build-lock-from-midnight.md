---
kind: experience
title: The overnight run holds the build lock from midnight
date: 2026-11-27
source: session fdf0a101, backlog 301 (docs site)
strength: observed
tags: landing, build, overnight
---

The docs branch was finished after midnight, while .overnight/running existed, so the fast build and landing had to wait for the run to end.
How to apply: finish docs work and land before 00:00, or plan to land after the run ends (it can run past 06:30).

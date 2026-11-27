---
kind: experience
title: Internal docs drift from the code: read the code path
date: 2026-11-27
source: session fdf0a101, backlog 301 (docs site)
strength: verified
tags: drift, accuracy, sources
---

Every one of the eleven writers found internal Markdown (deploy/README.md, demo/README.md, module READMEs, docs/observability/, build-plan.md) wrong against the code: stale tab names, wrong sizes, missing settings, old alert counts.
Why it holds: the internal documents are written for agents and Paul and are not kept up with each landing.
How to apply: take every fact on a page from the source (routes, properties classes, console JS, Helm templates). Use internal Markdown only to find where to look, and list each drift found as a finding.

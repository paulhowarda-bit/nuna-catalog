---
kind: experience
title: No API creates a catalog
date: 2026-11-27
source: session fdf0a101, backlog 301 (docs site)
strength: verified
tags: catalogs, api, configuration
---

Catalogs come only from nuna.storage.catalogs.<name> settings read at server start-up (CatalogStorage.java); there is no create-catalog endpoint in the REST or management API. Three writers confirmed it.
How to apply: document creating a catalog as configuration plus a restart, and treat any page or request that assumes a create endpoint as wrong.

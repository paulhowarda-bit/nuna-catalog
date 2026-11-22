# 2026-11-22, the effective grants on an object (backlog 129, D45)

- `Authorizer.effectiveGrants(Target)` lists every grant that reaches a catalog, namespace, table or view: those on
  its catalog, on each namespace above it and on it, in that order, from the catalog down. It walks the authorizer's
  own chain and drops a privilege this version does not know, as a decision does, so the read cannot disagree with an
  access decision. It reads each securable's grants with `listGrantsOn`, every page; the store API is unchanged.
- `GET /management/v1/catalogs/{catalog}/grants/effective?type=&namespace=&name=` answers them, named as `/on`
  names a securable: `{"securable": {...}, "grants": [{"securable", "group", "privilege", "granted-by",
  "granted-at"}], "grant-admin-groups": [...]}`, not paged. `grant-admin-groups` are the deployment's grant admins,
  who hold `manage-grants` on every catalog by configuration. Documented with the grants routes in `GrantRoutes`.
- Who may read it, settled on the recommendation: `manage-grants` on the securable or above it, as for `/on`; a
  refusal is audited as `listEffectiveGrants` and answered as `/on` answers one. Recorded in the item.
- Tests: `EffectiveGrantsTest` in catalog-security, 8 tests on an in-memory store, so they run in the fast build:
  inheritance from the catalog and from namespaces above, nothing from below or beside, views and namespaces, a
  revoke, an unknown privilege, paging past 1,200 grants, and agreement with `decide` for 9 groups, 7 securables and
  every privilege (441 decisions). All 8 pass.
- Bite: making `effectiveGrants` read only the target's own grants failed 6 of the 8 tests; the file was restored and
  its hash matched HEAD's.
- Not in this branch: the route test (`SecurityGateTest#theEffectiveGrantsOnATableIncludeEveryGrantAboveIt`, heavy)
  and the bite file `bites/D45/effective-grants-own-only.patch`. Both lie outside the item's claim, which
  `./backlog.sh claim` cannot widen while it is held; releasing and claiming again was refused here. The item's
  `Touches:` line now names both paths. The item stays open until they are in.
- Fast build after merging main (4fc7960, already merged): `./gradlew build` green, BUILD SUCCESSFUL in 6m 44s.
- Merged main again at 7593e49 (item 130): `./gradlew build` green, BUILD SUCCESSFUL in 4m 44s.

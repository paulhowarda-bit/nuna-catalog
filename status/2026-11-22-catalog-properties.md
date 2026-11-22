# 2026-11-22, catalog and namespace properties for the console (item 131)

- The namespace half needed nothing new. The console reads a namespace's properties from the Iceberg REST load,
  `GET /v1/{prefix}/namespaces/{namespace}`, which answers `{"namespace": [...], "properties": {...}}` and needs
  describe on the namespace. `api.js` already calls it.
- The catalog half is a new route, `GET /management/v1/catalogs/{catalog}/properties`, in
  `governance/CatalogProperties.java`. `GET /management/v1/catalogs` gives only names, and Iceberg REST has nothing
  at the catalog level.
  - It answers `{"name", "kms-key-set", "legal-entity", "storage-profile": {"name", "kind", "endpoint", "region",
    "table-root", "allowed-prefixes", "encryption"}}`. `encryption` is `sse-kms` or `bucket-default`.
  - The binding (profile, key set, legal entity) comes from the store. The endpoint, region and table root come from
    the profile's configuration.
  - Nothing secret and no identity: the access keys, STS role, maintenance principal, CA bundle path and KMS key ARN
    stay out.
  - A caller who holds nothing in the catalog gets 404 NoSuchWarehouseException, as for a missing catalog, and the
    refusal is audited as `readCatalogProperties` (D18).
- The prototype's catalog `owner`, `comment` and `default-format-version` rows, and "Set a property" on a catalog,
  have nothing behind them: the catalog holds no free-form properties. A catalog's owner is a governance attribute.
- `GovernanceWiring.java` is unchanged: the route is a `@RestController`, found by component scanning like every
  route in `governance/`.
- Claims: item 128 held `build-plan.md` and `bites/D45/` at first, so the bite is in `bites/D18/`, the rule it
  guards. Once 128 landed, the claim was widened to `build-plan.md` and the route got its row in the M7 table.
- Tests:
  - `CatalogPropertiesTest` runs in the fast build: 4 tests, all passing. It checks the whole answer, that no
    configured secret or account id appears in it, grant admins and platform operators, and the hidden, audited
    refusal.
  - `ManagementAuthorizationTest#readingACatalogsPropertiesNeedsSomethingInIt` is heavy and has not run here.
- Bite: `bites/D18/catalog-properties-open.patch` replaces the holdings check with a plain lookup. Run by hand in this
  worktree, `CatalogPropertiesTest#aCallerWhoHoldsNothingInTheCatalogIsAnsweredAsForOneThatDoesNotExist` failed on
  its assertion (1 of 4 failed); restored, the tree matched HEAD and the patch passes `git apply --check`.
- Fast build: `./gradlew build` passed after merging main (c077676), and again after merging 47836a8 (`catalog-server` 200 tests, no failures), with `:bitesApply` and the new bite included.
  `catalog-server` ran 187 tests with no failures.

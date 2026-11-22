# 2026-11-22, a table's or view's metadata log, a page at a time (item 130)

- New route beside the export, in `LifecycleRoutes`:
  `GET /management/v1/catalogs/{catalog}/objects/{id}/metadata-log?pageSize=&pageToken=`.
  - It answers `{"pointer": {"metadata-location", "version", "sha256"}, "journal": [...], "next-page-token": ...}`.
    The journal entries have the export's form, newest first. A page holds 25 entries by default and at most 100.
  - It needs select on the object, the export's check, under the action name `readMetadataLog`. It works for dropped
    objects too.
  - Item 130's `Touches:` named `rest/ObjectRoutes.java`, but the export lives in `governance/LifecycleRoutes.java`,
    so the route is there; the item now names the files changed.
- No store change. Each page is one pointer read and one journal read of at most a page, using the store's ascending
  pages (`JournalStore.afterVersion`). A token names the version its page stopped before.
  - Commits made after the first page shift no later page.
  - A page is shorter only where versions are missing. Versions run 1, 2, 3 and so on, so in practice no page is.
- Tests:
  - `MetadataLogPageTest` runs in the fast build (5 tests, all passing). It checks the paging against a journal kept
    as the stores keep it: whole walks at every page size, gaps, later commits, and one bounded read a page.
  - `LifecycleTest#theMetadataLogComesNewestFirstAPageAtATime` and `#aDroppedTableKeepsItsMetadataLog` are heavy.
  - `ManagementAuthorizationTest#theMetadataLogNeedsSelect` is heavy.
  - The heavy tests have not run here; the overnight run runs them.
- Bite: `bites/D45/metadata-log-open.patch` removes the select check; `theMetadataLogNeedsSelect` must fail. It passes
  `git apply --check`; it has not been run.
- Fast build: `./gradlew build` passed after merging main (4fc7960), with `:bitesApply` and the new bite included.
  `catalog-server` ran 183 tests with no failures.
- Not here: the console's use of the route (item 124). The route does not record an `ObjectRead`, since it reads no
  metadata file; the export records one when it does.

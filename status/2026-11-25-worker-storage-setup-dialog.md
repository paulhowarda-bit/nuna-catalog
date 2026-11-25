# 2026-11-25, the operator's "Set up the worker's storage" dialog and Service's Worker storage panel (backlog 254)

- New `storage.js`: the dialog "Set up the worker's storage for <catalog>" and the "Worker storage" panel on Service,
  from item 253's route (`GET /management/v1/maintenance/storage-setup`, `api.storageSetup`), in Atelier's words, with
  her answers of 2026-11-24 on what items 253 and 256 left open (titles for several or no catalogs, the intro counting
  the blocks shown, "Worker's identity" in place of "Covered by s3", listing and delete refusals told apart, MinIO's
  per-profile policy names, the fold summaries, Copy the command/setting, the CA-bundle line). The worker's settings
  are shown only for a store the worker holds no identity on. The backlog item's Done section lists every word.
- Maintenance (item 252): What to add and What to apply open the dialog for the catalog's profile; Open Worker storage
  and the copied note's link open `#p=service&tab=worker-storage&g=<profile>`, the panel in view with the row marked.
- Only platform operators are offered either: the panel lives on Service, and readiness offers the dialog only where
  `operates()`, so the route's audited 403 is never drawn.
- Tests: new `ConsoleStorageSetupBrowserTest` (heavy, 7 tests) as `oscar` with the store not reached, reached, refused
  and not answering, plus an unprotected profile and the AWS S3 and Ceph forms answered in the page; `ana` offered
  neither. `ConsoleReadinessBrowserTest` follows the change, and its `noIdentity` now reads readiness at its own route
  (it asked a path that is no route). **Not run:** the nuna-demo stack was up, so the browser tests refuse to start;
  the overnight heavy build runs them. Checked by hand only in a static page of the console's modules with the route's
  answer stubbed (the panel and the MinIO and Ceph dialogs drawn).
- Bites `bites/UI/console-storage-setup-for-anyone-with-names`, `console-storage-deletes-refused-as-listing`,
  `console-storage-policy-unfolded`, recorded and checked with `git apply --check`; every other bite still applies.
- Fast build: see the end of this entry.

# 2026-11-24, what the worker needs to maintain a catalog, on the Maintenance page (backlog 252)

**Built** on `worktree-agent-aada0bc5d8d539b99`, in Atelier's words and her answers of the day:
- The Maintenance page reads item 251's readiness route and shows "What the worker needs to maintain <catalog>": four
  rows (write access, storage settings, store permission, maintenance policy), each a pill, a line, "Who: …" and an
  action, while any step is not done. The page head says how many steps are left in place of "Nothing in X you may
  read." and of the old "has not reported"; with every step done and nothing assessed it says why.
- Actions only for callers the catalog would allow (no audited 403): Grant write opens item 248's dialog filled in
  (`grantAccess` takes `group` and `privilege`); Edit the catalog's policy for `modify`; links to the Service page for
  platform operators (item 254 replaces them); a copied note for others who may see names; whom to ask for the rest.
- Step 2 after item 256: "The worker holds no identity for the store at {endpoint}." (AWS S3: "for AWS S3.").
- `ReadinessRoutes`: platform operators read it without `describe`, with names (catalog-security); the store step
  gives `deletes-since`. table-health.md "Readiness" and item 251's Done updated.

**Checked:**
- `ReadinessRoutesTest` 8/8 (heavy, run beside the nuna-demo stack, 75 s): the operator reads with names and is not
  audited; a bystander is refused and audited.
- The panel rendered under Node with a DOM stand-in for ana, gary and oscar across nine readiness answers.
- Bites recorded and checked with `git apply --check` (722 bites, none failing to apply):
  `bites/D18/readiness-admits-every-caller.patch`, `bites/UI/console-readiness-grant-for-anyone-with-names.patch`,
  `bites/UI/console-readiness-policy-for-everyone.patch`. None run (`bites/run.sh` is the overnight run's).
- `./gradlew build` on the branch with main merged (7febbaad): green, 1 m 25 s, `:bitesApply` passed.

**Not run:** `ConsoleReadinessBrowserTest` (ana, gary, oscar and a steward) and the two UI bites: the nuna-demo stack
was up and `ConsoleBrowser` refuses beside it. `./gradlew :catalog-server:test --tests ConsoleReadinessBrowserTest
-Pheavy` with the demo down.

**Open:** item 254 (What to add, What to apply, Open Worker storage); Overview still says "has not reported" on the
same grounds (`overview.js`); Atelier's follow-up to show the gaps on Grants and under Service.

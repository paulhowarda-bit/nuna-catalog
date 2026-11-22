# 2026-11-22, a refusal under a DR freeze is audited (item 132)

- A drop, rename, undrop or purge claim refused because a DR freeze covers it now publishes a `nuna.catalog.denial`
  record through `Denials`, as every other refusal does (D18). The reason is `dr_freeze: ` and then the message, which
  carries the freeze's own reason. The answer is the same 403 ForbiddenException with the same message.
  - `ManagementGuard.requireUnfrozen` replaces `Management.requireUnfrozen`, and `ManagementGuard.frozen` also audits
    the purge claim the store refuses as frozen (a freeze placed after the route looked).
  - `ObjectRoutes.requireUnfrozen` refuses through `Guard.refuse`. A rename into a frozen namespace names the table.
- Drop and rename refusals are now `AccessDenied`, which an idempotency key does not record: a retry after the freeze
  is lifted runs afresh.
- Tests, both in the fast build and passing: `FrozenObjectRefusalTest` (3) and `FrozenLifecycleRefusalTest` (3).
  They run the real routes, guard and authorizer over a store double.
- Bites, both run with `bites/run.sh`: `bites/D18/dr-freeze-object-refusal-unaudited.patch` and
  `bites/D18/dr-freeze-lifecycle-refusal-unaudited.patch`, each BITES (3 of 3 failed), tree restored.
- Claim: `ManagementGuard.java`, the tests and the bites were outside the item's Touches line. `./backlog.sh claim 132
  <path>` cannot extend a claim already held and failed with "already claimed", after its overlap check passed. The
  item's Touches line now names them.
- Not done here: a commit or unregister refused by a DR freeze (`catalog-core`), and the legal-hold refusals of drop
  and purge claim, still answer 403 without a denial.

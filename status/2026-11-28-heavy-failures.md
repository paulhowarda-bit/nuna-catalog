# 2026-11-28: the heavy build's Kubernetes, CockroachDB and conformance failures

Branch `worktree-agent-a7c2e235b909c0274`, for the landing of 2026-11-28 (items 332, 333, 335, 336, 339 to 343).

- 342: `KubernetesCompactionTest` read the failed Job's reason once, before Kubernetes 1.37 sets the `Failed`
  condition; it now waits for it. Passes (1 test).
- 343: six `CockroachStoreSuiteTest` tests and `ClientOperationsTest#expiryOnAHeldTableIsRefused` timed out on
  connections the Podman machine never answered: its kernel could not find order-7 blocks
  (`page allocation failure`). The suite failed 3 tests even alone. Paul chose to tune the machine (D57):
  `vm.min_free_kbytes = 262144`, `vm.compaction_proactiveness = 50` in `/etc/sysctl.d/90-nuna-memory.conf`, written
  by `deploy/kind/kind.sh up` when missing. After it the suite passed alone, 218 of 218, with no allocation failure.

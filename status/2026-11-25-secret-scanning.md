# 2026-11-25, credential-shaped literals out of the tests, the docs and the Job, and a build check that keeps them out (backlog 280)

- Source: work's note `docs/from-work/005-github-secret-scanning.md` (Fidelity's GitHub push protection refused the
  2026-11-24 drop), reviewed by catalog-security, whose sweep found about twice what the note named. Items 280 to 285
  came from it; this branch is 280.
- Tests make each value at run time: PrivateCa, OracleFree (package-visible, so the Oracle UnavailableFailuresTest
  uses it), OracleBackendTest, DatabaseSettingsTest (redaction check against the random value), TokenSourceTest,
  AuditTablesSettingsTest, StorageReportTest, CatalogPropertiesTest, DeletePolicyMinioTest, the M8 Stack's DR reader,
  ConsoleSignInTest and TwoIssuersTest (Keycloak's admin, and people's passwords through `${NUNA_TEST_PASSWORD}` in
  the four test realms), ChangeFeedTest (SingleStore's root). StorageProfileTest's endpoint keeps a user, no password.
- Main code: `KubernetesJobs`'s compaction script makes the trust store password as it starts
  (`/proc/sys/kernel/random/uuid`); `KubernetesJobsTest` asserts no fixed password reaches the Job.
- `prototype.html`'s sample token is text. The work note and items 280 to 282 show `<redacted>` for the values.
- `./gradlew secretShapes` (in `check`): the shapes the scanner refused, in every tracked file but `BACKLOG.md`.
  `secret-shapes-allowed.txt` names the 18 files items 281 and 282 still have to clean. Planted literals of five
  shapes failed it.
- Ran: DatabaseSettingsTest 4, TokenSourceTest 6, AuditTablesSettingsTest 10, KubernetesJobsTest 5, StorageReportTest
  1, StorageProfileTest 82, CatalogPropertiesTest 4, all passing; the heavy tests compiled, for the overnight run.

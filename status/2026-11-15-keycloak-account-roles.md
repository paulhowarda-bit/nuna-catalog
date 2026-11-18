# 2026-11-15, the Keycloak account page works for the demo's users (backlog 33)

Changed: each user in `demo/k8s/keycloak-realm.json` (ana, gary, stella, oscar, presenter) now holds
`default-roles-nuna`. Keycloak gives that role to users made in its admin console, but not to users imported from a
realm file, and without it (and the `account` client's `view-profile` and `manage-account` it carries) the account
page's token is refused by Keycloak's account API with 401, which the page shows as "Something went wrong".
`demo/README.md` and the sign-in text `demo.sh` prints now send people to Keycloak's logout page
(`/realms/nuna/protocol/openid-connect/logout`, one click on Logout) to change user, instead of the account page.

Checked against the running demo's Keycloak (26.8.2), without restarting it:

- The branch's realm file, with the realm renamed `nunacheck`, created through the admin API, which runs the same
  import as `--import-realm`: ana and presenter held `default-roles-nuna` and the account client's `view-profile`,
  `manage-account` and `manage-account-links`. Signed in as ana, `/realms/nunacheck/account` showed her personal info.
- Keycloak's example access and ID tokens for ana from `nuna-console` and `kafbat`, in `nuna` (no roles) and
  `nunacheck` (roles): the same claims and audiences, with no role claims; only the order of the scope names differed,
  as `fullScopeAllowed: false` on both clients keeps role mappings out of their tokens.
- `nunacheck` was then deleted. The live `nuna` realm's five users were given `default-roles-nuna` with `kcadm.sh
  add-roles`, so the running demo matches the file, and `/realms/nuna/account` showed ana's personal info.

Not covered: a fresh `demo/demo.sh up` importing the file as `nuna` itself; the next one (the overnight run's, about
06:30) will. During the check another session's fast build held Docker for about 50 minutes, during which Keycloak
and the other demo pods stopped answering their readiness probes.

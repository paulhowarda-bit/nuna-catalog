# 2026-11-15, Keycloak sign-in for CloudBeaver and SingleStore Studio (backlog 34)

The demo's people sign in once, through Keycloak, and then use the console, kafbat, CloudBeaver and Studio without
another password.

- **CloudBeaver** (`demo/k8s/cloudbeaver.yaml`, `cloudbeaver.Dockerfile`, `cloudbeaver-signin.conf`):
  - oauth2-proxy v7.15.6 runs beside it in the pod, on 8185, as Keycloak client `cloudbeaver`.
  - CloudBeaver signs people in from the proxy's headers (provider `reverseProxy`), listens on 127.0.0.1 only and
    has no password sign-in.
  - Team `admin` comes from client role `admin`, held by group `demo-operators`.
  - Its Logout goes to a page the proxy serves, which closes CloudBeaver's session before the proxy's and
    Keycloak's.
- **Studio** (`singlestore.Dockerfile`, `singlestore.yaml`, `demo/singlestore/users.sql`):
  - Studio's cluster has a `samlConfig`, against Keycloak SAML client `singlestore-studio`.
  - The people are `IDENTIFIED WITH authentication_saml`.
  - SingleStore checks every response's signature (the realm's certificate, mounted from Secret `keycloak-keys`),
    audience, recipient and NameID.
- **Keys** (`keycloak.yaml`, `keycloak-realm.json`, `demo.sh`):
  - The realm signs with a fixed key, because SingleStore reads the certificate only as it starts.
  - `demo.sh` makes that key and CloudBeaver's secrets once per machine with `openssl`, in `~/.nuna-demo/keycloak`.

Found and fixed on the way:

- **CloudBeaver privilege escalation.** CloudBeaver's reverse-proxy sign-in also takes teams from `X-Role`, when
  the teams header is absent, and a role from `X-Role-TE`. oauth2-proxy passes both through.
  - Signed in as `ana`, a request with `X-Role: admin` made her CloudBeaver's administrator, with all 16
    connections, and the team stuck after a clean sign-in.
  - The proxy now runs on its alpha config, which drops both headers and always sends a teams header ending in
    `user`.
  - Checked afterwards: `ana` sending `X-Role`, `X-Role-TE`, `X-Forwarded-Groups: admin` and
    `X-Forwarded-Preferred-Username: oscar` is `ana`, team `user`, with her four connections.
- **Stale CloudBeaver session.** After CloudBeaver's Logout and a Keycloak sign-in as `oscar`, CloudBeaver still
  said `ana`: it reads the headers only as a session opens. The sign-out page fixes it. Checked in the browser:
  `oscar` logs out, `stella` signs in and gets her own session and connections.
- Keycloak's forced NameID format is `saml_force_name_id_format`; with the dotted name it sent a transient NameID.
- SingleStore resolves `saml_x509_certificate` against its basedir, the server's folder, not memsql.cnf's.

Checked against the deployed demo (`demo/demo.sh up` from the branch, exit 0):

- **CloudBeaver**, by a scripted sign-in through the proxy and Keycloak:
  - `ana`, `gary` and `stella` get team `user` and their own four connections.
  - `oscar` gets `admin`, `user` and all 16 connections.
  - `presenter` is refused.
  - A request without the proxy's cookie is sent to Keycloak.
  - CloudBeaver answers on 127.0.0.1 only.
- **Studio's SAML flow**, without a browser, against SingleStore. Each response was given to SingleStore as the
  password, as Studio does:
  - `ana`'s response signs in as `ana@%`, and `stella`'s as `stella@%`.
  - `ana`'s response used as `oscar` is refused ("the SAML NameID is not valid").
  - `ana`'s response with its NameID edited to `oscar` is refused ("digital signature does not validate").
  - `ana`'s old password is refused.
- **In the browser**, with one Keycloak sign-in: CloudBeaver, the console and kafbat as `ana`; Studio as `ana`,
  then as `stella` after a change of user.
  - Studio keeps its session across a reload.
  - Typing `oscar` in Studio while signed in to Keycloak as `ana` is refused.
  - `stella`'s Trino connection in CloudBeaver opens catalogs `engines` and `lakehouse`.
- The console still accepts Keycloak's tokens under the new key, and SingleStore's two load pipelines ran.

Not covered, and known limits:

- A SAML response can be replayed for an hour. SingleStore enforces the assertion's one-hour window and keeps no
  replay cache, and the response travels in clear over http on localhost. The README says so, and what would
  close it at work.
- Signing out of Keycloak elsewhere leaves CloudBeaver signed in for up to 10 hours, as kafbat is.
- The CDC leg's connections in CloudBeaver were not opened: the leg was not running.

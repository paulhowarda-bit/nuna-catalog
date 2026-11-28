# Security, governance and regulatory compliance for an enterprise Iceberg REST catalog (US financial services)

Research date: 2026-11-06. Lens: a from-scratch Iceberg REST catalog for a large US broker-dealer and asset manager running many engines (Spark, Flink, Trino, Snowflake, PyIceberg) across regions, on AWS and on-prem S3-compatible storage.

**Provenance tags.** `[read: URL]` means I fetched and read the source myself. Where a curl'd copy was used for exact text, the copy is in `C:/Users/paulh/AppData/Local/Temp/claude/d9ac3f89f5ec51f6/2eb8cba6-c503-4baa-9e18-b3df89b0f733/scratchpad/downloads/security/`. `[read (WebFetch summary): URL]` means I read the page only through WebFetch's paraphrase. `[secondary: URL]` means the source is a search-result excerpt or someone else's account that I could not read directly. `[inference]` is my own reasoning.

**This is not legal advice.** Regulatory text is quoted or paraphrased from primary sources. Whether a rule applies to a given entity of the firm's, and whether Iceberg tables are "records" under it, are decisions for the firm's counsel and compliance teams.

**Versions in force on 2026-11-06.**
- Apache Iceberg 1.12.0 was released 2026-09-30 [read: https://api.github.com/repos/apache/iceberg/releases]. Its REST spec file already contains `read-restrictions`, `labels`, `remote-signing-config`, the `/sign` endpoint, and `loadCredentials` with `planId`. The `none` value for `X-Iceberg-Access-Delegation` is only on `main`, merged 2026-10-29 [read: https://raw.githubusercontent.com/apache/iceberg/apache-iceberg-1.12.0/open-api/rest-catalog-open-api.yaml; https://api.github.com/repos/apache/iceberg/commits?path=open-api/rest-catalog-open-api.yaml].
- Apache Polaris 1.8.0 was released 2026-09-28 [read: https://api.github.com/repos/apache/polaris/releases].
- The Polaris and Lakekeeper documentation cited below was read from each project's `main` branch docs, which may be ahead of the latest release.

---

## 1. Authentication: enterprise IdP, workload identity, token handling, mTLS, Kerberos

### Takeaway
The Iceberg REST spec defines only OAuth2 and Bearer security schemes. It deprecates the catalog-hosted `/v1/oauth/tokens` endpoint and recommends that servers not implement it. A new catalog should therefore be a pure OAuth2 resource server that validates JWTs from the firm's IdP and never issues tokens itself.

The catch is identity propagation. Iceberg's built-in Java OAuth2 manager supports client credentials and RFC 8693 token exchange. Microsoft Entra ID's on-behalf-of flow is not RFC 8693, and the richer flows (authorization code, device code, JWT bearer, mTLS client authentication) exist only in third-party AuthManagers such as Dremio's.

### Cited Findings

**What the spec defines**
- The spec's `securitySchemes` define only `OAuth2` (a clientCredentials flow with `tokenUrl: /v1/oauth/tokens` and scope `catalog`) and `BearerAuth`. The text says: "If a separate authorization server is used, substitute the tokenUrl with the full token path of the external authorization server." It also says implementations "must not return altered success (200) responses when a request is unauthenticated or unauthorized" [read: https://raw.githubusercontent.com/apache/iceberg/main/open-api/rest-catalog-open-api.yaml].
- `/v1/oauth/tokens` is marked "DEPRECATED for REMOVAL… It is _not_ recommended to implement this endpoint, unless you are fully aware of the potential security implications." It has been deprecated since Iceberg Java 1.6.0 and is to be removed from the spec in Iceberg Java 2.0. All clients are told to "explicitly set the configuration property `oauth2-server-uri` to the correct OAuth endpoint." The endpoint covered client credentials, token exchange, and refresh through token exchange [read: same spec].
- The deprecation cites issue #10537, "Security improvements in the Iceberg REST specification" [read: same spec]. Dev-list participants objected that a catalog proxying the IdP's token endpoint becomes a credential-receiving man-in-the-middle [secondary: https://mail-archive.com/dev@iceberg.apache.org/msg06185.html — search-result excerpt].
- `loadTable` may return `config` with a `token` key, a bearer token to use for subsequent requests on that table. It may also return an RFC 8693 token type as the key, for example `urn:ietf:params:oauth:token-type:jwt=<JWT>` [read: same spec].

**Iceberg Java client (used by Spark and Flink)**
- `AuthManager` is a pluggable interface with these sessions:
  - `initSession`, used only for the config endpoint.
  - `catalogSession`, long-lived.
  - `contextualSession(SessionContext, parent)`, a per-caller session that multi-user engines can use.
  - `tableSession`, which uses the properties returned by the table endpoint and is also used by request-signer clients.

  [read: https://raw.githubusercontent.com/apache/iceberg/main/core/src/main/java/org/apache/iceberg/rest/auth/AuthManager.java]
- Built-in `rest.auth.type` values are `none`, `basic`, `oauth2`, `sigv4` (`org.apache.iceberg.aws.RESTSigV4AuthManager`, which delegates to `oauth2` by default) and `google` [read: https://raw.githubusercontent.com/apache/iceberg/main/core/src/main/java/org/apache/iceberg/rest/auth/AuthProperties.java].
- The built-in OAuth2 properties are `token`, `credential` (for client credentials), `oauth2-server-uri`, `token-expires-in-ms` (default 1 h), `token-refresh-enabled` (default true), `token-exchange-enabled` (default true), `scope`, `audience` and `resource`, plus constants for the access, refresh, ID, SAML1/2 and JWT token types. The source notes: "Some IDPs do not support token exchange which is the first approach used for acquiring a new token. Disabling this will allow fallback to the client credential flow" [read: https://raw.githubusercontent.com/apache/iceberg/main/core/src/main/java/org/apache/iceberg/rest/auth/OAuth2Properties.java].
- Iceberg's `HTTPClient` accepts a pluggable `TLSConfigurer` through `rest.client.tls.configurer-impl`. A client certificate (mTLS) to the catalog therefore needs a custom class rather than plain properties [read: https://raw.githubusercontent.com/apache/iceberg/main/core/src/main/java/org/apache/iceberg/rest/HTTPClient.java; the mTLS conclusion is inference].
- Kerberos/SPNEGO appears in neither the spec's security schemes nor the built-in `AuthProperties` types [read: the spec and AuthProperties.java above]. Kerberos-only legacy clients would need a custom `AuthManager` or a gateway [inference].

**Dremio AuthManager (third party, Apache-2.0)**
- It is a drop-in `AuthManager` for any Iceberg REST catalog. It supports these grants:
  - client credentials
  - authorization code
  - device authorization (RFC 8628)
  - token exchange (RFC 8693), "including impersonation and delegation"
  - JWT bearer (RFC 7523)
  - deprecated resource-owner password

  Client authentication methods are `client_secret_basic`/`post`, `client_secret_jwt`, `private_key_jwt`, and RFC 8705 mTLS (`tls_client_auth`, `self_signed_tls_client_auth`). It also supports DPoP. Its mTLS page says RFC 8705 is "heavily favored in regulated finance… and FAPI-conformant deployments" and that the manager "implements the first half of RFC 8705, that is, Mutual-TLS client authentication" [read: https://raw.githubusercontent.com/dremio/iceberg-auth-manager/main/docs/README.md; https://raw.githubusercontent.com/dremio/iceberg-auth-manager/main/docs/mtls.md; https://raw.githubusercontent.com/dremio/iceberg-auth-manager/main/docs/client-authentication.md].

**Enterprise IdPs**
- **Entra ID** on-behalf-of uses `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer` with `requested_token_use=on_behalf_of`, not the RFC 8693 token-exchange grant [read: https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-on-behalf-of-flow].
- **Entra workload identity federation** lets an app registration or managed identity trust tokens from an external IdP. Supported sources include "Workloads running on any Kubernetes cluster (AKS, EKS, GKE, or on-premises)" [read: https://learn.microsoft.com/en-us/entra/workload-id/workload-identity-federation].
- **Okta** implements RFC 8693 (`grant_type=urn:ietf:params:oauth:grant-type:token-exchange` with `subject_token_type=…access_token`) [read: https://developer.okta.com/docs/guides/set-up-token-exchange/main/].

**Engines**
- **Trino**: `iceberg.rest-catalog.security` accepts `NONE`, `SIGV4`, `GOOGLE` or `OAUTH2`, and `OAUTH2` requires a static `token` or a `credential`. Other relevant settings are `iceberg.rest-catalog.session` = `NONE` or `USER` ("Session information included when communicating with the REST Catalog"), `oauth2.server-uri`, `token-refresh-enabled`, `token-exchange-enabled`, and `vended-credentials-enabled` (default false) [read: https://raw.githubusercontent.com/trinodb/trino/master/docs/src/main/sphinx/object-storage/metastores.md].
- **Snowflake**: a catalog integration authenticates with `OAUTH` (client ID, secret, allowed scopes, optional `OAUTH_TOKEN_URI`; "If not specified, Snowflake assumes that the remote catalog provider is the OAuth identity provider"), `BEARER` or `SIGV4` (IAM role plus optional external ID). `ACCESS_DELEGATION_MODE` is `VENDED_CREDENTIALS` or `EXTERNAL_VOLUME_CREDENTIALS` [read: https://docs.snowflake.com/en/sql-reference/sql/create-catalog-integration-rest].
- **Polaris** supports `internal`, `external` (OIDC via Quarkus) and `mixed` authentication per realm. In `external` mode it "Disables the internal token endpoint (returns HTTP 501)." The internal token broker signs with an RSA key pair or a symmetric key [read: https://raw.githubusercontent.com/apache/polaris/main/site/content/in-dev/unreleased/managing-security/external-idp/_index.md].

### Inferences
- A from-scratch catalog should not implement `/v1/oauth/tokens`. It should validate IdP-issued JWTs (issuer, audience, signature through JWKS, expiry, and optionally a certificate binding) and map claims and groups to principals and roles. This removes a whole class of secrets from the catalog.
- **Service principals.** Each engine deployment (a Spark platform, a Flink job cluster, Trino, Snowflake) gets its own IdP client. Prefer `private_key_jwt` or workload-identity federation over client secrets, because secrets in Spark configs leak through job submissions and UIs.
- **Kubernetes Spark and Flink.** Use the cluster's projected service-account token, federated to the IdP (Entra workload identity federation or the equivalent in Ping or Okta). This yields one identity per job or namespace.
- **Snowflake** authenticates with one OAuth client per catalog integration. Every Snowflake user behind that integration is the same principal to the catalog, so Snowflake RBAC on the integration becomes part of the control boundary.
- **The identity-propagation gap is the hardest AuthN problem.** The built-in Java OAuth2 manager speaks RFC 8693, which works with Okta (and reportedly Ping; not verified). With Entra, OBO is a JWT-bearer variant, so it needs either the Dremio manager (if its JWT-bearer grant can send `requested_token_use`, which is unverified) or a custom AuthManager.
- Human access (UI, PyIceberg notebooks) should use authorization code with PKCE or device code through the IdP, so the IdP enforces MFA (see section 6, NYDFS 500.12 and FTC 314.4(c)(5)).

### Gaps
- I did not read Ping Identity's RFC 8693 documentation.
- Unverified: whether Dremio's JWT-bearer grant can carry Entra's `requested_token_use=on_behalf_of`.
- Unverified: exactly what Trino sends to the catalog in `session=USER` mode (a user name in the SessionContext, or a user token).
- I did not verify how Spark on EMR (Runtime Roles / Lake Formation mode) and Flink pass per-user identity into a third-party REST catalog.

---

## 2. Authorization models: grain, inheritance, ownership, separation of duties

### Takeaway
The leading OSS catalogs offer three models:
- **Polaris:** two-tier RBAC (principal → principal role → catalog role → privilege), with OPA as a preview external decision point and Ranger code in its tree.
- **Lakekeeper:** ReBAC on OpenFGA with inherited privileges and ownership, or policy-as-code on Cedar in the commercial edition.
- **AWS Lake Formation and Unity Catalog:** grants plus tag-based ABAC.

For a regulated firm, the deciding features are separation of duties, per-principal enumeration for access reviews, and an audit record of every decision. Zanzibar-style stores cannot cheaply provide per-principal enumeration.

### Cited Findings

**Polaris**
- The RBAC model, per the docs: "Polaris administrator assigns access privileges to catalog roles and then grants principals access to resources by assigning catalog roles to principal roles." The relationships are many-to-many. Privileges are never granted directly to a principal role [read: https://raw.githubusercontent.com/apache/polaris/main/site/content/in-dev/unreleased/managing-security/access-control.md].
- Securables are catalog, namespace, table, view and policy.
- The privilege list includes:
  - `TABLE_READ_DATA` / `TABLE_WRITE_DATA` (which control credential vending)
  - `TABLE_READ_PROPERTIES`, `TABLE_FULL_METADATA`
  - `NAMESPACE_*`
  - `CATALOG_MANAGE_ACCESS` (grants), separate from `CATALOG_MANAGE_CONTENT` and `CATALOG_MANAGE_METADATA`
  - `POLICY_*`
  - `SEMANTIC_MODEL_*`

  The doc describes no object-ownership concept [read: same doc].
- Polaris supports an external Policy Decision Point. OPA is the documented option, marked "currently a preview feature and may undergo breaking changes." Rego policies receive actions such as `LOAD_TABLE_WITH_READ_DELEGATION` [read: https://raw.githubusercontent.com/apache/polaris/main/site/content/in-dev/unreleased/managing-security/external-pdp/opa.md].
- **Ranger:** a Ranger authorizer for Polaris (`polaris.authorization.type=ranger`, opt-in) was proposed by Ranger's PMC and tracked as Polaris PR #3928 [secondary: https://mail-archive.com/dev@ranger.apache.org/msg39491.html — search-result excerpt]. The Polaris 1.8.0 release notes show Ranger dependency upgrades and "Migrate Ranger integration tests to server runner", so Ranger code is in the tree [read: https://api.github.com/repos/apache/polaris/releases]. The managing-security docs list only OPA as an external PDP [read: https://raw.githubusercontent.com/apache/polaris/main/site/content/in-dev/unreleased/managing-security/external-pdp/_index.md].

**Lakekeeper**
- Lakekeeper delegates every decision to one Authorizer:
  - **OpenFGA** (open source; "Extra service to run: Yes — an OpenFGA deployment with its own database"; permissions are relationships stored as data, changed at runtime by admins and object owners; no attribute conditions).
  - **Cedar** (Lakekeeper Plus, i.e. commercial; built in; conditions on time, tags and request attributes; "Switching away generally needs a new Lakekeeper instance").
  - **AllowAll** (development only) or a **Custom** authorizer.

  [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/authorization.md]
- Under OpenFGA, the privileges per level are `ownership`, `pass_grants`, `manage_grants`, `manage_tags`, `describe`, `select`, `write`, `modify` and `create`. `describe`, `select`, `write` and `modify` "build on each other" and are inherited down the hierarchy. Creators automatically become owners. Owners can by default expand access to their objects [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/authorization-openfga.md].
- **Separation of duties** at project level:
  - `security_admin` manages grants and ownership but has "no access to the content of objects beyond listing". It can, however, grant itself privileges.
  - `data_admin` manages objects but not grants.
  - `project_admin` holds both.
  - The server `admin` "can list and administrate all Projects, but does not have access to data in projects" "in order to guarantee auditability".
  - `manage_tags` is "independent of write and modify — a separation-of-duties choice, so a data steward can classify objects without holding data or schema-modification rights".

  [read: same doc]
- **Zanzibar's enumeration limit.** Under OpenFGA, "The project-scoped listing is not supported. `GET /management/v1/grants` reports `GrantListingNotImplemented` (501)… 'everything one principal holds in this project' has no index to answer it from." The other limits are that tuples have "no grantor" (the audit event is the only record of who granted) and that grant events are never deduplicated [read: same doc].
- Lakekeeper says "Direct grants are not effective permissions". To ask what a principal can effectively do, call the `.../actions` endpoints or `POST /management/v1/action/batch-check` [read: authorization.md].
- **Provider-managed roles.** Roles can be owned by a role provider such as an IdP group sync, which makes them immutable through the API (`400 ManagedRoleImmutable`). Membership is synced per user at login, so a group appears only once a member has logged in [read: authorization.md].
- **Break-glass (Cedar only).** An emergency override header whose reason goes into the audit event, "cut off at 256 bytes, so send a ticket reference" [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/authorization-cedar.md].
- **Row and column filtering.** Lakekeeper's docs say neither engine expresses it: "filtering rows or columns *within* an object is not something Lakekeeper enforces" [read: authorization.md].

**Ranger, Privacera, Immuta**
- Ranger's integration guide says applications use either an embedded plugin or the Ranger PDP REST service, and must enforce denials themselves [secondary: https://ranger.apache.org/blogs/integrating_applications.html — search-result excerpt]. I found nothing primary on Privacera or Immuta Iceberg REST integration in this pass.

### Inferences
- Access reviews drive the model choice. NYDFS 500.7(a)(4) requires an at-least-annual review of "all user access privileges" (section 6). The catalog must therefore answer "what can principal X effectively do, everywhere" in bulk.
  - A pure Zanzibar/OpenFGA store makes that expensive. Lakekeeper itself returns 501 for it.
  - A relational or document grants model with a fixed privilege lattice and hierarchical inheritance (Polaris-like, with Lakekeeper's privilege lattice) can answer both "who can access object Y" and "what can principal X access" with indexed queries.
- **Ownership** is useful for self-service, but "owner can re-grant" should be off by default for regulated namespaces. Otherwise data owners can widen access outside the access-review process. Lakekeeper makes this configurable through `pass_grants` and `manage_grants`.
- **Role source of truth.** IdP groups should feed the catalog's roles, as in Lakekeeper's provider-managed roles. Joiner, mover and leaver handling then stays in the IdP and IGA tooling, which supports "promptly terminate access following departures" (500.7(a)(6)).
- **The minimum SoD split for a regulated catalog** is: grant administration, data and DDL administration, tag/classification stewardship, and platform operations without data access. The decision path must also emit an audit record of every allow and deny.

### Gaps
- No primary source read on Privacera or Immuta support for third-party Iceberg REST catalogs, or on Unity Catalog's or Lake Formation's ABAC tag model (only their credential-vending limits).
- The Polaris Ranger authorizer's release status (merged, documented, GA) is unconfirmed. The code is present but not in the docs.

---

## 3. Fine-grained access (row filters and column masks): who can enforce it

### Takeaway
When an engine reads Parquet files directly, only the engine can enforce row filters and column masks. A catalog can decide policy but cannot enforce it against a client that holds storage access.

The new Iceberg `read-restrictions` field turns the catalog into a standard policy decision point for trusted engines. It was merged into the spec on 2026-09-03 and appears in the 1.12.0 spec file. As of 2026-11-06, however, no released Iceberg Java client parses it; the API and Spark implementations are open PRs targeted at 1.13.0. Clients that do not support it ignore it silently.

Unity Catalog and Lake Formation handle the same problem by refusing to vend credentials for filtered tables, or by enforcing through trusted or AWS-run engines.

### Cited Findings

**The `read-restrictions` spec**
- The spec text: "The response may include a read-restrictions field. A reader that supports read restrictions must fail any read against the loaded table that cannot apply a returned restriction in full." It also says: "These restrictions apply to every read performed using this response (including subsequent planTableScan and fetchScanTasks calls)… The response should not be cached outside of the authenticated scope" [read: https://raw.githubusercontent.com/apache/iceberg/main/open-api/rest-catalog-open-api.yaml].
- `ReadRestrictions` has two fields:
  - `required-row-filter`, an Iceberg predicate using field IDs, evaluated "against original, untransformed column values".
  - `required-column-projections`, a list of field-id/action pairs. Actions include `show-last-4`, `replace-with-null`, `truncate-to-year`, `sha-256-global` and `mask-alphanum`, among others.

  Rules: a reader that cannot apply a restriction "must fail the query and must not silently return raw, partial, or empty results"; duplicate field IDs, projections on map keys, and nested-plus-child projections must fail [read: same spec].
- Spec PR #13879, "Spec: Add finer grained read restrictions as part of loadTable", was merged 2026-09-03 [read: https://api.github.com/repos/apache/iceberg/commits?path=open-api/rest-catalog-open-api.yaml]. The field is present in the 1.12.0 spec file [read: https://raw.githubusercontent.com/apache/iceberg/apache-iceberg-1.12.0/open-api/rest-catalog-open-api.yaml].

**Java implementation status**
- `LoadTableResponse.java` contains no read-restrictions handling on either `main` or the `apache-iceberg-1.12.0` tag [read: https://raw.githubusercontent.com/apache/iceberg/main/core/src/main/java/org/apache/iceberg/rest/responses/LoadTableResponse.java].
- These PRs are open with milestone "Iceberg 1.13.0" [read: https://api.github.com/search/issues?q=repo:apache/iceberg+read-restrictions+OR+ReadRestrictions+is:pr]:
  - https://github.com/apache/iceberg/pull/16198 ("API: [1/N] Add ReadRestrictions Actions")
  - https://github.com/apache/iceberg/pull/16082 ("Spark 4.1: [3/N] Add ReadRestrictions")
  - https://github.com/apache/iceberg/pull/17809 (RCK tests)

**Google's account of the design (blog dated 2026-10-01, by Uyarer and Yun)**
- The catalog is the policy decision point and the trusted engine is the enforcement point. How the catalog establishes trust in an engine "is out of scope". Trusted clients are "asserted, not proven", with no attestation. "a client that chooses to ignore the restrictions can read the raw files." Engine enforcement work "begins now". The action vocabulary is closed and no portable policy language exists [read (WebFetch summary): https://opensource.googleblog.com/2026/10/standardizing-fine-grained-access-control-in-apache-icebergs-rest-catalog.html].

**Other catalogs**
- **Unity Catalog** credential vending to external engines (Unity REST and Iceberg REST) does not support "Tables with row filters or column masks." Vended credentials "inherit the privileges of the Databricks principal used to configure the integration." External use requires `EXTERNAL USE SCHEMA` [read: https://docs.databricks.com/aws/en/external-access/credential-vending]. A community post describes a newer Spark path where Databricks serverless applies filters before data reaches the external engine; I treat it as unverified [secondary: https://techcommunity.microsoft.com/t5/azure/cross-engine-abac-on-azure-databricks-governing-external-spark/m-p/4565409 — search-result excerpt].
- **Lake Formation** offers two integration modes [read: https://docs.aws.amazon.com/lake-formation/latest/dg/Integrating-with-LakeFormation.html]:
  - (a) "application integration": LF "can vend scoped-down temporary credentials in the form of AWS STS tokens" to authorized (registered) engines.
  - (b) "Central enforcement": LF "querying API operations retrieve data from Amazon S3 and filter the results… Third-party query engines only see and operate on filtered data."

  A separate option, "Allows external engines to access data in Amazon S3 locations with full table access", lets engines obtain credentials "without IAM session tags if the user has full table access" (used by EMR on EC2) [read: https://docs.aws.amazon.com/lake-formation/latest/dg/using-cred-vending.html].
- **Lakekeeper's OPA bridge** describes the two choices for a multi-user engine [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/opa.md]:
  1. "Catalog enforces permissions" by impersonating the user through token exchange.
  2. "Compute enforces permissions" by contacting the catalog as a "god-like" user and fetching permissions through OPA, which "requires the engine to run in a trusted environment".

  The bridge serves Trino's OPA queries from Lakekeeper permissions, using batch checks of up to 1000 per request. "Currently trino is the only iceberg query engine we are aware of that is flexible enough to honor external permissions via OPA."

**Server-side scan planning as an enforcement point**
- `planTableScan` returns file scan tasks and plan tasks. `loadCredentials` accepts a `planId` ("The plan ID that has been used for server-side scan planning"). The `scan-planning-mode=server` config tells clients they "MUST use server-side scan planning" [read: https://raw.githubusercontent.com/apache/iceberg/main/open-api/rest-catalog-open-api.yaml].

### Inferences
- **What a catalog can guarantee:** object-level authorization (catalog, namespace, table, view), the scope of storage access it delegates, and the audit trail. Server-side planning plus plan-scoped credentials or remote signing can also restrict a client to the files in a plan, which coarsely enforces partition-aligned row policy (for example, region = US).
- **What it cannot guarantee:** column masking, or row filtering within a file, against a client that can read the files. Read-restrictions do not change this. They only help engines that implement them and that the catalog trusts. Because non-supporting readers silently ignore the field and the spec has no client-capability signal, the catalog must keep an allow-list of trusted engine principals. For any table with active row or column policy, it must refuse to vend credentials or sign requests for any other client, as Unity does.
- **Only two architectures give enforcement against untrusted clients:**
  - (a) A trusted serving engine. Users get SQL or Arrow results, never files.
  - (b) A Lake Formation-style "central enforcement" data API that the catalog fronts.

  For the firm, (a) means Trino or Spark platform clusters registered as trusted engines. PyIceberg and notebook users get no direct file access to sensitive tables.
- Session policies cannot express per-file scoping beyond a handful of files (2048-character limit, section 4). Plan-scoped enforcement on AWS therefore realistically requires remote signing.

### Gaps
- No primary source found on Trino, Snowflake or Flink plans to implement read-restrictions. The Google post names none as implementing it.
- Not verified: Lake Formation's own Iceberg REST endpoint behaviour for filtered tables, or Snowflake's behaviour when reading a table it cannot fully access.

---

## 4. Credential vending, remote signing, and engine-owned IAM

### Takeaway
Vended credentials (STS AssumeRole with a table-prefix session policy, an Azure user-delegation SAS, or a GCS downscoped token) are the standard way for the catalog to scope storage access per table. The real constraints are:
- the 2,048-character session-policy limit;
- a 1-to-12-hour session lifetime, capped at 1 hour under role chaining;
- an STS quota of 600 requests per second per account per region;
- the inability to revoke a single vended session;
- patchy on-prem support.

For on-prem storage, remote signing is the portable fallback because it needs only SigV4. Trusted multi-user engines can use their own IAM roles, with the catalog doing metadata authorization only.

### Cited Findings

**Spec mechanics**
- `X-Iceberg-Access-Delegation` lets a client advertise `vended-credentials` and/or `remote-signing`; "The server may choose to supply access via any or none of the requested mechanisms." The value `none` ("able to operate without any access delegation mechanism") was added on `main` on 2026-10-29 and is not in 1.12.0 [read: main and 1.12.0 spec files; commit list].
- Credentials come back in `storage-credentials` as a list of `{prefix, config}`, and clients choose the longest matching prefix. A `loadCredentials` endpoint (`GET …/tables/{table}/credentials`) supports refresh [read: main spec].
- Remote signing uses `/v1/{prefix}/namespaces/{namespace}/tables/{table}/sign`, promoted into the main spec on 2026-03-31. `remote-signing-config` (formalized 2026-07-30) carries static properties and headers for the signer [read: main spec; commit list].

**Polaris**
- On AWS, Polaris calls "AWS STS AssumeRole with an inline session policy scoped to the specific table locations and operations (read, list, write) the caller is authorized to perform."
- On Azure it generates a User Delegation SAS "scoped to the container and path prefix", noting that Azure caps its validity at 7 days.
- On GCS it uses a Credential Access Boundary downscoped token.
- It returns a credential-refresh endpoint.
- If the delegation header is present but can't be satisfied, Polaris fails with 400 rather than return the table without credentials.

[read: https://raw.githubusercontent.com/apache/polaris/main/site/content/in-dev/unreleased/vended-credentials.md]

**Lakekeeper**
- "Vended credentials need an STS endpoint, which not every S3 implementation offers. We test vended credentials against AWS, Silo (a maintained MinIO fork) and SeaweedFS."
- "Remote signing works with every storage that supports AWS Signature Version 4… such as Rook Ceph RADOS, NetApp StorageGRID 12.0 or newer and MinIO."
- In remote signing, "clients get no storage credentials… Lakekeeper checks that the user may access the table and that the request stays inside the table's location".
- Options include `sts-token-validity-seconds` (default 3600), `sts-session-tags`, and external-ID enforcement for its system identity.

[read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/storage-s3.md]

**AWS STS limits**
- STS: "default request quota is 600 requests per second, per account, per Region", shared by AssumeRole, GetCallerIdentity, GetSessionToken and others. "For cross-account AssumeRole requests, only the account making the AssumeRole request impacts the STS quota." Increases are by support ticket [read: https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_iam-quotas.html].
- Session policies: "The size of the passed JSON policy document and all passed managed policy ARN characters combined can't exceed 2,048 characters". At most 10 managed policy ARNs. Session policies and tags count against a separate token-size limit, reported by `SessionTokenUtilization` (`PackedPolicySize` is deprecated). Up to 50 session tags. Role session name is up to 64 characters [read: same].
- Session duration: 900 seconds up to the role's maximum (1–12 hours), default 1 hour. "Role chaining limits your AWS CLI or AWS API role session to a maximum of one hour." `PackedPolicyTooLarge` is returned when the token exceeds the size limit [read: https://docs.aws.amazon.com/STS/latest/APIReference/API_AssumeRole.html].
- Revocation: you "can immediately revoke all permissions to the role's credentials issued before a certain point in time." AWS attaches a deny-all inline policy conditioned on token issue time, affecting all sessions of the role [read: https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_use_revoke-sessions.html].

**Azure and GCS**
- Azure user-delegation key: "You can specify that the key is valid for a period of up to seven days". A SAS fails once its key expires [read: https://learn.microsoft.com/en-us/rest/api/storageservices/create-user-delegation-sas].
- GCS: "A Credential Access Boundary can contain up to 10 access boundary rules. You can apply only one Credential Access Boundary to each short-lived credential." Conditions can refer to object-name prefixes [read: https://cloud.google.com/iam/docs/downscoping-short-lived-credentials].

**On-prem S3-compatible stores**
- **MinIO:** AssumeRole with an inline `Policy` ("Maximum length of 2048"); the result is the intersection with the user's policy. `DurationSeconds` "from 900 seconds (15 minutes) up to 365 days" [read: https://raw.githubusercontent.com/minio/minio/master/docs/sts/assume-role.md].
- **Ceph RGW:** AssumeRole and AssumeRoleWithWebIdentity both accept a `Policy` (session policy) and `DurationSeconds`. Permissions are the intersection of the role policy and the passed policy [read: https://raw.githubusercontent.com/ceph/ceph/main/doc/radosgw/STS.rst].
- **NetApp StorageGRID 12.0:** "You can now configure a new S3 trust policy to support the AssumeRole API" [read: https://docs.netapp.com/us-en/storagegrid-120/release-notes/whats-new.html]. Session-policy support was not confirmed.
- **Pure FlashBlade** (Pure blog, 2026-02-27): "supports STS however it doesn't implement the AssumeRole API call, instead favoring AssumeRoleWithWebIdentity and AssumeRoleWithSAML… credential vending isn't available for Apache Polaris". The workaround is static credentials with Polaris's `stsUnavailable` [read: https://blog.purestorage.com/purely-technical/using-apache-polaris-with-flashblade/].
- **Dell ECS/ObjectScale:** IAM supports AssumeRole and AssumeRoleWithSAML. The temporary credentials "cannot be revoked", and session policies "limit… but do not grant permissions" [secondary: https://infohub.delltechnologies.com/en-au/l/ecs-iam-introduction/introduction-2978 — search-result excerpt; the page redirected to a landing page I could not read].

### Inferences
- **Exfiltration risk.** Vended credentials are bearer secrets that live until expiry. Single-session revocation is impossible on AWS (only a role-wide cutoff) and is stated to be impossible on ECS. A leaked Spark executor log or driver config therefore exposes table data for up to the TTL. Mitigations:
  - TTL of 15–60 minutes plus refresh through `loadCredentials`.
  - Bucket policies restricting the vending role to corporate VPC endpoints or network ranges (standard AWS condition keys; not re-read here).
  - A role session name and session tags carrying the catalog principal and request ID, so CloudTrail S3 data events can be joined to catalog audit records.
  - SSE-KMS keys whose policy must also allow the session.
- **Remote signing** gives per-request authorization and immediate revocation, and no secret leaves the catalog. The costs are a catalog round trip per S3 request batch (a latency and availability dependency at high read volume) and client support (not all clients implement it, per Lakekeeper).
- **Engine-owned IAM** (the engine's own role, `X-Iceberg-Access-Delegation: none`) suits trusted multi-user engines, where the engine enforces FGAC. The catalog must then treat that engine principal's table access as "full table access" and rely on the engine's own audit.
- **Scale.** With many thousands of tables and many engines, a cold cache can produce bursts of AssumeRole calls (one per table per principal-scope per TTL). The 600 RPS per-account quota is shared with GetCallerIdentity calls made by SDKs. Remedies:
  - Cache credentials per (principal-scope, table, read/write) for most of their TTL.
  - Spread vending across several AWS "vending" accounts, since cross-account quota is charged to the caller.
  - Monitor throttling.
- **Role chaining.** A catalog running on EKS or EC2 already holds role credentials, so AssumeRole into the vending role is role chaining, capped at 1 hour. That fits short TTLs but rules out long-lived batch credentials.
- **Policy size.** A per-table session policy covering read, list and write for one table location fits easily. Tables whose files span several prefixes (`write.data.path` separate from metadata, object-storage hashed layouts across buckets, or multi-region replicas) can approach the 2,048-character limit. The catalog should constrain where table files may live.
- **Applying this to the firm's on-prem estate:**
  - MinIO (or its forks) and Ceph support AssumeRole with session policies.
  - StorageGRID 12.0 supports AssumeRole, with session policies unverified.
  - FlashBlade has no AssumeRole.
  - ECS supports AssumeRole, but credentials are irrevocable and session-policy size limits are unverified.

  The catalog should implement both vended credentials and remote signing behind a storage-profile abstraction, as Lakekeeper does.

### Gaps
- Not read: StorageGRID 12.0 STS details (session policies, maximum duration, endpoint); Dell ObjectScale session-policy limits; FlashBlade's federation documentation (Pure1 login required).
- No source found on AWS STS behaviour or quotas for very high-volume AssumeRole at the scale of thousands of tables, beyond the default quota.
- Azure SAS revocation (revoking user delegation keys) was not read.

---

## 5. Auditing: what to log, tamper evidence, SIEM, change events

### Takeaway
Neither leading OSS catalog provides a regulator-grade audit trail out of the box.
- Polaris says outright that its events "are not a complete audit trail": no denials, asynchronous, best-effort, droppable.
- Lakekeeper logs every authorization decision, allowed and denied, with a versioned schema, and publishes CloudEvents change events to Kafka or NATS. It still leaves retention and tamper evidence to the receiving system.

The firm's catalog should write audit records in the same transaction as the state change and ship them to an immutable store and the SIEM.

### Cited Findings

**Polaris events**
- The warning box: "Polaris events are not a complete audit trail. They do not record authorization denials or authentication failures, and delivery is asynchronous and best-effort… Retention, access control, tamper evidence, and querying of records are properties of the system that receives the events, not of Polaris."
- Event types are `BEFORE_*`/`AFTER_*` per operation, including `LOAD_TABLE`, `LOAD_CREDENTIALS`, `ROTATE_CREDENTIALS`, grant and role assignment.
- Metadata includes event ID, timestamp, realm, principal (when authenticated), request ID and OTel trace context.
- An `EventSanitizer` strips sensitive objects.
- On delivery: "not written in the same transaction as the operation"; "A bounded backlog drops events"; "A failing listener loses the event… not retried."

[read: https://raw.githubusercontent.com/apache/polaris/main/site/content/in-dev/unreleased/events.md]

**Lakekeeper audit logs**
- Audit is "enabled by default", emitted on target `lakekeeper::audit` with `event_source: "audit"`. Record types are `authorization` (an access check and its decision), `replay` (an idempotent retry) and `operation` (grant written, roles resolved).
- `audit_format` uses MAJOR.MINOR versioning with a published JSON schema and open or closed value sets. Records contain PII.

[read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/logging.md]

`LAKEKEEPER__AUDIT__TRACING__ENABLED` logs "all authorization checks (both successful and failed)… actor, action, resource, and outcome" [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/configuration.md].

**Lakekeeper change events**
- Events are published to NATS or Kafka (librdkafka, with SSL, SASL and OAUTHBEARER/OIDC; "Kerberos (SASL mechanism GSSAPI) is not supported"), or logged when `LAKEKEEPER__LOG_CLOUDEVENTS=true` [read: configuration.md].

**Regulatory hooks (detail in section 6)**
- 17a-4(f)(3)(iii): on the WORM path, the firm must have "an audit system providing for accountability regarding inputting of records… and inputting of any changes", with results "preserved for the time required for the audited records" [read: https://www.ecfr.gov/current/title-17/section-240.17a-4].
- NYDFS 500.6: audit trails designed to detect and respond to cybersecurity events, with records kept "not fewer than five years" [read: https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.6].
- FTC 314.4(c)(8): "monitor and log the activity of authorized users and detect unauthorized access or use of, or tampering with, customer information" [read: https://www.ecfr.gov/current/title-16/section-314.4].

### Inferences
**Minimum audit event set:**
- authentication success and failure, including token validation failures;
- every authorization decision, with allow or deny and the policy or grant that decided it;
- `loadTable` and `loadView`, and `planTableScan`;
- each credential issuance, with prefix scope, mode, TTL, STS role session name and session tags;
- each remote-signing decision, aggregated per request batch;
- each commit, with prior and new `metadata-location`, snapshot ID, operation, requirements and updates (the schema or partition change), the engine principal and the end-user principal;
- create, drop, rename, register and unregister;
- grant, revoke, role membership and ownership changes;
- storage profile and config changes;
- break-glass use.

**Integrity and delivery:**
- Write each record in the same database transaction as the state change (a transactional outbox), then deliver at-least-once to Kafka. From there it goes to the SIEM and to an Object Lock bucket. Hash-chain each batch so records are tamper-evident.
- Polaris's best-effort listener model would not meet a "complete audit trail" bar. That is a concrete reason to build this in rather than adopt Polaris events.

**Identity:**
- 17a-4's audit-trail alternative requires "the identity of the individual creating, modifying, or deleting the record" (section 6). If engines commit with a shared service principal, the catalog cannot meet that for regulated tables. This makes end-user identity propagation (section 1) a compliance requirement, not merely a nicety.
- **Change events vs audit.** A CloudEvents change stream (table created, committed, dropped) is the integration feed for the governance catalog, lineage and cache invalidation. Keep it separate from the audit stream, which carries PII and denials and needs stricter access controls.

### Gaps
- Not covered: Unity Catalog and Lake Formation audit schemas (system tables, CloudTrail) as comparators.
- No source read on SIEM-specific formats (Splunk CIM, OCSF) for catalog events.

---

## 6. US financial-services obligations that shape catalog design

### Takeaway
The obligations that most constrain the catalog are:
- **SEC Rule 17a-4(f)** for broker-dealer records (with FINRA 4511 pointing to it, and Advisers Act 204-2 for the asset manager).
- **Data-security rules** (Reg S-P for SEC registrants; FTC Safeguards as a benchmark; NYDFS Part 500 if one of the firm's entities is a NY-licensed covered entity), which require least privilege, annual access review, MFA, encryption and audit trails.

17a-4(f) allows either WORM storage or, since the 2022 amendments (compliance date 2023-05-03), a "complete time-stamped audit trail" that can re-create any modified or deleted record. Iceberg's snapshot model resembles that audit trail only while snapshots are retained. Routine snapshot expiration, compaction cleanup and orphan removal destroy it, and S3 Object Lock in compliance mode silently prevents storage reclamation.

### Cited Findings

**SEC Rule 17a-4 (17 CFR 240.17a-4)** [read: https://www.ecfr.gov/current/title-17/section-240.17a-4, via the eCFR renderer API]
- Retention periods:
  - (a) records under 17a-3(a)(1)–(3), (5), (21), (22) for "not less than 6 years, the first two years in an easily accessible place";
  - (b) other listed records for "not less than three years, the first two years in an easily accessible place".
- (f)(2) requires an electronic recordkeeping system to either:
  - **(i)(A)** "Preserve a record for the duration of its applicable retention period in a manner that maintains a complete time-stamped audit trail that includes: (1) All modifications to and deletions of the record or any part thereof; (2) The date and time of actions that create, modify, or delete the record; (3) If applicable, the identity of the individual creating, modifying, or deleting the record; and (4) Any other information needed to maintain an audit trail… and will permit re-creation of the original record if it is modified or deleted"; or
  - **(i)(B)** "Preserve the records exclusively in a non-rewriteable, non-erasable format".
- The other (f)(2) requirements:
  - (ii) "Verify automatically the completeness and accuracy of the processes for storing and retaining records";
  - (iii) if applicable, serialize the storage media and time-date the retention period;
  - (iv) "readily download and transfer copies of a record and its audit trail (if applicable) in both a human readable format and in a reasonably usable electronic format", plus the information needed to locate records;
  - (v) a backup electronic recordkeeping system "or" other redundancy capabilities.
- (f)(3) firm obligations:
  - immediately produce records;
  - (iii) on the WORM path, an audit system for inputs and changes, preserved as long as the records;
  - (iv) keep current all information needed to locate records;
  - (v) a filed undertaking by a "designated executive officer" or a "designated third party".
- (i): records maintained by an "outside service bureau, depository, bank, or other recordkeeping service" need a written undertaking from that outside entity. The broker-dealer has "independent access" if it can access records "without the need of any intervention of the outside entity". An outside agreement "will not relieve" the broker-dealer of responsibility.

**The 2022 adopting release (87 FR 66412, 2022-11-03)** [read: https://www.govinfo.gov/content/pkg/FR-2022-11-03/pdf/2022-22670.pdf]
- "The compliance date for the amendments to 17 CFR 240.17a-4 is May 3, 2023."
- "The amendments to Rule 17a-4 add an audit-trail alternative to the WORM requirement… a broker-dealer will need to use an electronic recordkeeping system that maintains and preserves electronic records in a manner that permits the recreation of an original record if it is modified or deleted."
- The Commission describes the audit-trail requirement as providing "the flexibility of a principles-based requirement by setting forth a high-level yet specific outcome… —the ability to recreate an altered or deleted record—without prescribing how the system must be configured".
- Firms "may continue to meet the requirements of the rule by using any WORM-compliant electronic recordkeeping system they employ today".
- The release discusses at length how cloud service providers fit the third-party undertaking.

**FINRA Rule 4511** [read: https://www.finra.org/rules-guidance/rulebooks/finra-rules/4511]
- (b) "preserve for a period of at least six years those FINRA books and records for which there is no specified period"; (c) "preserved in a format and media that complies with SEA Rule 17a-4."

**Investment Advisers Act Rule 204-2 (17 CFR 275.204-2)** [read: https://www.ecfr.gov/current/title-17/section-275.204-2]
- (e)(1): most required books and records preserved "for a period of not less than five years from the end of the fiscal year during which the last entry was made… the first two years in an appropriate office".
- (g)(3) electronic storage: procedures "To maintain and preserve the records, so as to reasonably safeguard them from loss, alteration, or destruction" and "To limit access to the records to properly authorized personnel and the Commission".

**S3 Object Lock and WORM** [read: https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html]
- Object Lock "has been assessed by Cohasset Associates for use in environments that are subject to SEC 17a-4, CFTC, and FINRA regulations."
- It "works only in buckets that have S3 Versioning enabled". In compliance mode, a locked version "can't be overwritten or deleted by any user, including the root user… its retention period can't be shortened."
- A "Permanent DELETE request (a request that specifies a version ID)" returns 403. A "Simple DELETE request… returns a 200 OK response and inserts a delete marker", and the locked version remains.
- Retention and legal holds "don't prevent new versions of the object".

**Maintenance policies.** Polaris lets maintenance policies (`system.snapshot-expiry`, `system.orphan-file-removal`, `system.data-compaction`, `system.metadata-compaction`) be attached to catalog, namespace or table and inherited, with override. The jobs themselves "are performed by external engines or services" [read: https://raw.githubusercontent.com/apache/polaris/main/site/content/in-dev/unreleased/policy.md].

**SOX and its analogues**
- SOX §404 requires each annual report to state management's responsibility for "an adequate internal control structure and procedures for financial reporting" and to assess its effectiveness. The section refers to "the issuer" [read: https://www.law.cornell.edu/uscode/text/15/7262].
- Registered funds: each Form N-CSR must include certifications signed by the principal executive and financial officers [read: https://www.ecfr.gov/current/title-17/section-270.30a-2].
- Broker-dealers: 17a-5's compliance report addresses "Internal Control Over Compliance", meaning controls over 15c3-1, 15c3-3, 17a-13 and account-statement rules [read: https://www.ecfr.gov/current/title-17/section-240.17a-5].

**GLBA safeguards**
- SEC Regulation S-P (17 CFR 248.30) covers broker-dealers, advisers and funds. It requires written policies covering "administrative, technical, and physical safeguards" and an incident response program to "detect, respond to, and recover from unauthorized access". Service providers must notify "no later than 72 hours after becoming aware" of a breach. It also covers disposal and recordkeeping of incident documentation [read: https://www.ecfr.gov/current/title-17/section-248.30].
- The 2024 Reg S-P amendments were published 2024-06-03, with "an 18-month compliance period… for larger entities, and a 24-month compliance period" for smaller ones [read: https://www.govinfo.gov/content/pkg/FR-2024-06-03/pdf/2024-11116.pdf]. That puts the larger-entity date at about 2025-12-03 [inference: date arithmetic].
- The FTC Safeguards Rule applies to "financial institutions over which the Federal Trade Commission… has jurisdiction" [read: https://www.ecfr.gov/current/title-16/section-314.1]. Its specific controls are a useful benchmark [read: https://www.ecfr.gov/current/title-16/section-314.4]:
  - (c)(1) access controls, periodically reviewed;
  - (c)(3) "Protect by encryption all customer information… both in transit over external networks and at rest", or compensating controls approved by the Qualified Individual;
  - (c)(5) "multi-factor authentication for any individual accessing any information system";
  - (c)(6) dispose of customer information "no later than two years after the last date the information is used… unless… otherwise required to be retained by law or regulation";
  - (c)(8) logging and monitoring.

**NYDFS 23 NYCRR 500**
- Applicability: a "Covered entity means any person operating under or required to operate under a license, registration, charter… under the Banking Law, the Insurance Law or the Financial Services Law". A "Class A company" has at least $20M in gross annual revenue and either more than 2,000 employees or more than $1B in revenue (affiliates included) [read: https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.1].
- 500.6: maintain systems to reconstruct material financial transactions, and audit trails to detect and respond to cybersecurity events, with records kept at least five years [read: https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.6].
- 500.7 [read: https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.7]:
  - limit access to "only those necessary to perform the user's job";
  - limit privileged accounts;
  - "periodically, but at a minimum annually, review all user access privileges and remove or disable accounts and access that are no longer necessary";
  - "promptly terminate access following departures";
  - class A companies must monitor privileged access and implement "a privileged access management solution".
- 500.12: "Multi-factor authentication shall be utilized for any individual accessing any information systems of a covered entity". A limited-exemption carve-out covers "service accounts that prohibit interactive login" [read: https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.12].
- 500.13 [read: https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.13]:
  - an asset inventory tracking "owner; location; classification or sensitivity; support expiration date; and recovery time objectives";
  - secure disposal of nonpublic information no longer needed, "except where such information is otherwise required to be retained by law or regulation".
- 500.15: a written encryption policy "that meets industry standards" for nonpublic information "both in transit over external networks and at rest". The CISO may approve compensating controls, reviewed annually [read: https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.15].

**Encryption options in Iceberg** [read: https://raw.githubusercontent.com/apache/iceberg/main/docs/docs/encryption.md]
- Iceberg table encryption covers "data, delete, manifest and manifest list files". `metadata.json` "is not encrypted". It is "supported in the Hive and REST catalogs for tables with Parquet and Avro". Configuration is a catalog-level `encryption.kms-type` (aws, azure, gcp, aliyun) or `encryption.kms-impl`, plus a table `encryption.key-id`.
- "Catalog security requirements":
  - "Catalogs must ensure the `encryption.key-id` property is not modified or removed during table lifetime."
  - Catalogs must not read metadata from unprotected `metadata.json` "if these files are kept unprotected in a storage vulnerable to tampering". Options include keeping metadata "in a trusted independent object store" or keeping checksums "in a separate trusted storage".
- Status: PR #13225, "Core: Support client-configured encryption in RESTCatalog", is still open [read: https://github.com/apache/iceberg/pull/13225 status via GitHub search API]. A May 2026 dev-list thread says the key-metadata format and the `encryption-keys` structure are "implementation-specific" and were reverse-engineered from Java [secondary: https://mail-archive.com/dev@iceberg.apache.org/msg13695.html — search-result excerpt].

**Lineage.** OpenLineage's `CatalogDatasetFacet` carries `framework` (for example iceberg), `type` (for example polaris or glue), `name`, `metadataUri`, `warehouseUri`, `source` and `catalogProperties`; `framework`, `type` and `name` are required [read: https://raw.githubusercontent.com/OpenLineage/OpenLineage/main/spec/facets/CatalogDatasetFacet.json]. The naming spec defines namespaces such as `s3://{bucket}` for object storage, `arn:aws:glue:{region}:{account id}` for Glue, and `unitycatalog://{host}` for Unity [read (WebFetch summary): https://openlineage.io/docs/spec/naming].

### Inferences
**Records classification comes first.** Counsel and compliance must decide which Iceberg tables hold 17a-3, 17a-4, 204-2 or 31a-x records, or the sole copy of them. Many analytic tables are derived copies, whose system of record (with its own WORM or audit trail) lives elsewhere. If no Iceberg table is a required record, 17a-4(f) does not constrain table maintenance, and the catalog needs only good audit and change control.

**If Iceberg tables are records, three viable designs:**
- **(1) WORM path.** The table's storage prefix sits in an Object Lock compliance bucket with retention at least as long as the record period.
  - Iceberg's append-only files suit this.
  - Expiration, compaction rewrites and orphan removal issue simple deletes (200 plus delete marker), so jobs "succeed" while the locked versions stay. Storage is never reclaimed during retention, and compaction roughly doubles stored bytes for rewritten data.
  - Row-level deletes and updates (merge-on-read or copy-on-write) are logical only.
- **(2) Audit-trail path.** Keep every snapshot for the full retention period, so any prior state can be re-created through time travel. The catalog's commit log must record time-stamped who, what and when, including the individual. Expire-snapshots and orphan removal must be blocked by catalog policy for those tables. Production in "human readable and reasonably usable" formats (Parquet or CSV export of a snapshot plus the commit log) must be supported.
- **(3) Archive path.** Periodically export immutable record snapshots into a separate WORM archive, and let the operational Iceberg table be maintained normally.

**What this means for the catalog either way:**
- A per-table or per-namespace retention class.
- Commit validation that rejects metadata updates removing snapshots inside the retention window. Snapshot removal happens through commits (`remove-snapshots` updates), so the catalog sees it.
- Refusal to `DROP … PURGE` or unregister held tables.
- A legal-hold flag.
- Audit records preserved as long as the underlying records (17a-4(f)(3)(iii)(B)).

Of these, commit-time enforcement is the most important feature the firm's catalog needs that Polaris and Lakekeeper do not document.

**Deletion vs retention.** The disposal duties (FTC 314.4(c)(6), NYDFS 500.13(b), Reg S-P disposal) explicitly carve out data "required to be retained by law or regulation". Where deletion requests and retention collide, retention wins for in-scope records. Outside them, deletion must be physical: expire the snapshots that reference the deleted rows, then remove the files. The catalog must therefore track which tables may and may not be physically purged.

**Change control (SOX-like).** SOX 404 attaches to "issuers". For the firm's entities, the analogous regimes are fund N-CSR certifications and broker-dealer 17a-5 ICOC; counsel must map which datasets feed those. For the catalog this means:
- Schema, partition and property changes, grants, and storage-profile changes on in-scope tables go through approved paths (CI/CD service principals, branch / write-audit-publish workflows).
- Changes are attributable and reviewable.
- The catalog can enforce "who may commit to `main`" separately from "who may write to a branch".

**Encryption.**
- Encryption in transit is TLS everywhere. TLS to the catalog and to object storage is needed even internally, because NYDFS covers "external networks" but internal-network scope is a CISO policy call.
- At rest: SSE-KMS with keys per data domain or classification satisfies "encryption at rest" today. The KMS key policy also acts as a second gate, because a leaked vended S3 credential without `kms:Decrypt` on the key cannot read the data (standard SSE-KMS behaviour; not re-read here).
- Iceberg table encryption (envelope encryption in the client) is the only option that protects against a compromised storage administrator. It is still maturing for REST (the open PR, the spec-format gap), so treat it as a pilot for the highest-sensitivity tables.
- Its "catalog security requirements" (immutable `encryption.key-id`, trusted metadata integrity) are cheap to build in now: store a hash of each `metadata.json` in the catalog database and verify it on load.

**Data classification and lineage.** Classification tags are an input to authorization (ABAC) and to retention class. Ownership and classification are mandated asset-inventory attributes (500.13). Lineage (OpenLineage) is emitted by engines. The catalog's part is to be identifiable in lineage events through `CatalogDatasetFacet` and to emit change events.

### Gaps
- Not read: Investment Company Act Rule 31a-2 (fund records retention), CFTC 1.31 (if any of the firm's entities is CFTC-registered), FINRA Regulatory Notices on 17a-4 cloud storage, and the Cohasset assessment of S3 Object Lock itself (only AWS's reference to it).
- No authoritative source found on whether an Iceberg snapshot history meets the 17a-4(f)(2)(i)(A) audit-trail standard. That is a compliance and counsel judgement.
- Which of the firm's entities are NYDFS covered entities, FTC-jurisdiction institutions, or SOX issuers is not determinable from public rule text.
- No source read on how on-prem stores (MinIO, ECS, StorageGRID, FlashBlade) are assessed for 17a-4 WORM.

---

## 7. Boundary: what belongs in the Iceberg catalog vs a governance or business catalog

### Takeaway
The Iceberg catalog should own whatever must be enforced or atomic at request time: identity mapping, authorization decisions and grants, credential and signing issuance, commit-time policy (retention holds, schema-change guards, encryption-key immutability), the enforcement-relevant subset of tags, and the audit and change streams.

The governance catalog (Collibra, Alation, DataHub, OpenMetadata) should own the human and semantic layer: glossary, business ownership and stewardship workflow, classification curation, lineage graphs, data quality and certification. It syncs the decisions the Iceberg catalog needs to enforce into it.

The Iceberg spec's new `labels` field is the sanctioned channel for projecting governance metadata to engines.

### Cited Findings
- The REST spec `Labels` field: "Catalog-provided metadata enrichment (for example ownership, classification, or cost attribution) returned with a table or view. Labels are catalog-provided and optional; clients may ignore them… The spec does not require how a catalog produces or stores labels, nor whether they are persisted or versioned." It has `object-labels` and per-field `fields` keyed by field ID. It was merged 2026-09-09 (PR #15750) and is in the 1.12.0 spec [read: main and 1.12.0 spec files; commit list].
- Lakekeeper governance tags are project-scoped definitions created by `tag_creator`. Applying them to objects and columns needs `manage_tags`, independent of data rights. Under Cedar, tags can drive conditions [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/authorization-openfga.md; https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/authorization.md].
- Polaris attaches typed policies (maintenance types today) to catalog, namespace or table with inheritance [read: https://raw.githubusercontent.com/apache/polaris/main/site/content/in-dev/unreleased/policy.md].
- NYDFS 500.13 requires an asset inventory with owner and classification [read: https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.13].
- OpenLineage identifies the catalog through `CatalogDatasetFacet` [read: https://raw.githubusercontent.com/OpenLineage/OpenLineage/main/spec/facets/CatalogDatasetFacet.json].
- Lakekeeper publishes table change events (CloudEvents) to Kafka or NATS for downstream consumers [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/docs/docs/configuration.md].

### Inferences
- **Inside the Iceberg catalog:**
  - principals, roles and IdP group mapping;
  - grants and the authorizer;
  - trusted-engine registry;
  - storage profiles and vending;
  - remote signing;
  - read-restriction decisions, computed from tags and policies;
  - retention class and legal hold;
  - encryption-key binding;
  - commit validation;
  - audit log;
  - change events;
  - `labels` passthrough.
- **Outside, in the governance catalog:**
  - glossary and business definitions;
  - steward workflows and certification;
  - lineage graph storage and impact analysis;
  - data quality;
  - classification curation, including scanners proposing tags;
  - the asset-inventory record of truth for owner and classification.
- **Sync direction:**
  - governance → Iceberg catalog: classification tags, owner, retention class. The Iceberg catalog stores the enforcement copy with provenance and an audit record of who changed it.
  - Iceberg catalog → governance: change events and the table inventory.
  - Engines → lineage store: OpenLineage events.
- **Avoid** the dual-master problem for anything enforcement-relevant. The authoritative value at decision time must be in the Iceberg catalog's database, so an outage or lag in the governance tool cannot open access. Default-deny on unclassified tables in regulated namespaces.
- **Polaris-style typed policies are a reasonable pattern** for maintenance and retention configuration, but they are the "bloat" area the user worries about. Keep the typed-policy surface to what the catalog itself enforces (retention and hold, maintenance guards), and leave generic policy authoring to the governance or PDP layer.

### Gaps
- Not read: Collibra, Alation, DataHub or OpenMetadata documentation on bi-directional tag sync with Iceberg REST catalogs.
- No standard exists for a tag or policy sync protocol between governance and Iceberg catalogs. `labels` is read-only enrichment.

---

## Decisions this implies

### D1. Catalog authentication role
- **Options:**
  - (a) The catalog issues tokens (implements `/v1/oauth/tokens`, as Polaris internal mode does).
  - (b) The catalog is a pure OAuth2 resource server validating enterprise-IdP JWTs.
  - (c) Option (b) plus SigV4 request authentication for AWS-native engines.
- **Evidence:** the spec deprecates `/v1/oauth/tokens` and advises against implementing it. Polaris returns 501 for it in external mode. Snowflake and Trino can point at an external token URI. Snowflake and Trino also support SigV4 to REST catalogs (section 1).
- **Recommendation:** (b) now; (c) only if a concrete engine needs it. Require `private_key_jwt` or workload-identity federation for service principals, and authorization code with PKCE or device code (with IdP-enforced MFA) for humans. Treat mTLS (RFC 8705 certificate-bound tokens) as an option for high-privilege service principals. Do not support Kerberos natively; front any legacy clients with a gateway.

### D2. End-user identity propagation through multi-user engines
- **Options:**
  - (a) Engines connect with their own service principal only.
  - (b) Engines exchange end-user tokens per session (RFC 8693 via Okta or Ping; Entra OBO through a custom or Dremio AuthManager).
  - (c) A hybrid: engines are "trusted" principals that assert the end user (a signed header or token exchange) and enforce FGAC themselves.
- **Evidence:** 17a-4(f)(2)(i)(A)(3) requires "the identity of the individual". Lakekeeper documents both catalog-enforces (token exchange) and compute-enforces (OPA bridge) modes. Entra OBO is not RFC 8693.
- **Recommendation:** (c), with a trusted-engine registry in the catalog. Commits to retention-class tables are rejected unless an end-user identity is present.

### D3. Authorization model and engine
- **Options:**
  - (a) A built-in hierarchical RBAC/grants model in the catalog database (Polaris-like structure, Lakekeeper-like privilege lattice).
  - (b) ReBAC on OpenFGA.
  - (c) Policy-as-code (OPA or Cedar) as the primary decision point.
  - (d) Delegation to Ranger, Privacera or Immuta.
- **Evidence:**
  - OpenFGA adds a service and database and, in Lakekeeper, cannot list per-principal grants (501).
  - NYDFS 500.7 requires an annual review of all user access privileges.
  - Polaris OPA is preview; the Polaris Ranger authorizer is in code but not documented.
  - Lakekeeper's SoD roles (`security_admin`, `data_admin`, `manage_tags`) and its batch-check API.
- **Recommendation:** (a) as the system of record.
  - A fixed privilege lattice: describe < select < write < modify, plus create, manage_grants and manage_tags.
  - Inheritance down catalog, namespace, table and view.
  - Roles sourced from IdP groups.
  - Ownership without re-grant by default in regulated namespaces.
  - An `Authorizer` SPI with a batch-check endpoint, and per-principal and per-object enumeration APIs for access reviews.
  - Add an OPA or Ranger adapter only if the firm's enterprise standard mandates one (open question).

### D4. Fine-grained access control
- **Options:**
  - (a) No FGAC in the catalog; engines handle it with their own policies.
  - (b) The catalog computes `read-restrictions` and returns them only to registered trusted engines, and refuses vending or signing for restricted tables to anyone else.
  - (c) A data-serving enforcement layer (Lake Formation-style central enforcement).
- **Evidence:**
  - The spec was merged 2026-09-03 and is in the 1.12.0 spec file.
  - No released Java client implements it; PRs target 1.13.0.
  - Non-supporting readers ignore it silently.
  - Unity refuses vending for filtered tables.
  - Lake Formation offers both vending and central enforcement.
- **Recommendation:** (b), designed now and gated by an allow-list. Use a trusted Trino or Spark platform as the FGAC path until engines ship read-restrictions support. Revisit (c) only if untrusted tools (notebooks) need sensitive data.

### D5. Storage access mode
- **Options:**
  - (a) Vended credentials only.
  - (b) Remote signing only.
  - (c) Engines use their own IAM roles (no delegation).
  - (d) Per storage profile and per sensitivity tier.
- **Evidence:**
  - STS limits: 2,048-character policy; 600 RPS per account per region; role chaining capped at 1 hour; no single-session revoke.
  - On-prem: FlashBlade has no AssumeRole; StorageGRID 12.0 does; MinIO and Ceph support session policies; ECS credentials are irrevocable.
  - Lakekeeper's guidance: enable both vending and signing where possible.
- **Recommendation:** (d).
  - Vended credentials with a 15–60 minute TTL, refresh, role session name and session tags carrying the catalog principal and request ID, and caching. Spread vending across multiple AWS accounts at scale.
  - Remote signing for stores without AssumeRole and session policies, and for highest-sensitivity tables.
  - `none` (engine IAM) for registered trusted engines.
  - Constrain table locations to one prefix per table so session policies stay small.

### D6. Audit architecture
- **Options:**
  - (a) Best-effort event listeners (Polaris model).
  - (b) Structured audit logs to stdout, shipped by log agents (Lakekeeper model).
  - (c) A transactional outbox in the same database transaction as each state change, delivered at-least-once to Kafka, then to the SIEM and to a hash-chained Object Lock archive.
- **Evidence:** Polaris disclaims completeness, does not log denials, and drops events. 17a-4(f)(3)(iii)(B) requires audit results preserved for the records' period. NYDFS 500.6 requires 5 years.
- **Recommendation:** (c).
  - Log every decision, allowed and denied.
  - Version the schema (as Lakekeeper's `audit_format` does).
  - Keep a separate CloudEvents change stream for integrations.
  - Note for the persistence decision: the chosen database (MongoDB was the prior lean) must support multi-document transactions spanning the commit, grant and outbox records.

### D7. Retention and WORM for regulated tables
- **Options:**
  - (a) Keep regulated records out of Iceberg (Iceberg holds derived copies only).
  - (b) The audit-trail path (retain all snapshots for the record period, with catalog-enforced guards and export tooling).
  - (c) The WORM path (Object Lock compliance on the table prefix).
  - (d) Periodic WORM archive exports.
- **Evidence:** the 17a-4(f)(2)(i)(A)/(B) text; the SEC's "recreate an altered or deleted record" outcome; Object Lock's delete-marker semantics; Polaris maintenance policies run by external engines.
- **Recommendation:** counsel decides scope. Either way, build catalog-side retention classes, legal holds, and commit-time rejection of snapshot removal, purge, drop and unregister within the hold. Prefer (a) or (d) for most data. Use (c) only for tables that must themselves be the record, and accept non-reclaimable storage and compaction amplification.

### D8. Encryption
- **Options:**
  - (a) SSE-KMS with per-domain or per-classification keys.
  - (b) Iceberg client-side table encryption.
  - (c) Both, for the top tier.
- **Evidence:** NYDFS 500.15 and FTC 314.4(c)(3) require encryption at rest and in transit over external networks. Iceberg encryption's catalog requirements are documented. REST client-side encryption support is still maturing (open PR #13225; spec-format gap).
- **Recommendation:** (a) now, with TLS everywhere. Build (b)'s catalog obligations (immutable `encryption.key-id`, hashes of `metadata.json` in the catalog database) from day one. Pilot (b) later.

### D9. Governance boundary
- **Options:**
  - (a) A fat catalog that also holds glossary, lineage and stewardship.
  - (b) A thin enforcement catalog plus a governance catalog as the system of record for semantics, with an enforcement copy of tags in the Iceberg catalog.
- **Evidence:** the spec's `labels` is read-only enrichment with no storage requirement. NYDFS 500.13 asset-inventory attributes. OpenLineage is engine-emitted and carries a catalog facet.
- **Recommendation:** (b). This keeps the from-scratch catalog lean (the user's anti-bloat goal). Sync tags in, emit change events out, and default-deny on unclassified objects in regulated namespaces.

---

## Open questions for the enterprise

**Legal and compliance (only counsel and compliance can answer)**
1. Which of the firm's legal entities would use the catalog? For each, which regimes apply:
   - broker-dealer (17a-3/17a-4, FINRA 4511, 17a-5 ICOC);
   - investment adviser (204-2);
   - registered funds (31a-2, N-CSR certifications);
   - NYDFS covered entity (500), including class A status;
   - Reg S-P "larger entity" status;
   - any FTC-jurisdiction affiliate;
   - SOX issuer status.
2. Will any Iceberg table be a required record, or the only copy of one, under 17a-4, 204-2 or 31a-2? If so, does the firm rely on the WORM path or the audit-trail path, and which designated executive officer or third party signs the 17a-4(f)(3)(v) undertaking for this system?
3. Does compliance accept Iceberg snapshot history plus a catalog commit log as satisfying 17a-4(f)(2)(i)(A)'s "complete time-stamped audit trail"? What download format will examiners expect?
4. Are on-prem object stores assessed (Cohasset or equivalent) for 17a-4 WORM? Which cloud providers have 17a-4(i) undertakings on file?
5. How are deletion requests (privacy, Reg S-P disposal, contractual) reconciled with retention holds? Who approves a physical purge?

**Security and identity**
6. Which IdP is authoritative for workloads and humans: Entra ID, Ping or Okta? Does it support RFC 8693 token exchange, or only Entra OBO? This decides the AuthManager strategy.
7. Is there an enterprise mandate for a central policy engine (Ranger, Privacera, Immuta, OPA) that the catalog must delegate to? Or may the catalog be the system of record for data-access grants?
8. What is the access-review (recertification) process and tool (SailPoint or similar)? What export or API format must the catalog provide for "all privileges of principal X" and "all principals on object Y"?
9. Which engines will be registered as "trusted" for FGAC enforcement? Who attests to their configuration, and what is the policy for notebooks and PyIceberg on sensitive tables?
10. What maximum lifetime is acceptable for vended credentials? Must storage access be restricted to corporate network endpoints (VPC endpoints or on-prem ranges)?
11. Does the CISO require encryption in transit on internal networks too, and client-side (Iceberg) encryption for any data class? Who owns KMS key policy per domain?
12. Which SIEM and audit archive? What retention for catalog audit logs (5 years per NYDFS 500.6 at minimum, or the record period if 17a-4(f)(3)(iii)(B) applies)? Is hash-chaining or notarization required?

**Platform**
13. Which on-prem object stores, at which versions, are in scope (MinIO or a fork, Ceph, ECS/ObjectScale, StorageGRID, FlashBlade)? Do they support AssumeRole with session policies, or must remote signing be the on-prem default?
14. How many AWS accounts and regions will hold table storage? Is STS quota sharding across vending accounts acceptable to cloud governance?
15. Which governance catalog is the system of record (Collibra, Alation, DataHub, OpenMetadata)? Can it push classification and ownership changes as events, and does it consume OpenLineage?
16. Which table-maintenance service (compaction, expiration) will run? Under which identity? How will it be prevented from expiring snapshots on held tables?

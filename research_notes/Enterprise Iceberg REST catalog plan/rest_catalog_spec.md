# Apache Iceberg REST Catalog Specification — full API surface, contracts, clients, and pipeline (as of 2026-11-06)

Researcher notes for the enterprise (Fidelity) from-scratch REST catalog plan. Lens: separate (a) what the spec and real clients require, (b) what an enterprise operator needs, (c) what exists mainly for a vendor's commercial model.

**Provenance key.** Every claim carries a tag: `[read: …]` means I downloaded and read that exact text this session; `[secondary: …]` means a summary I did not verify against the primary; `[inference]` means my reasoning. Long URLs are abbreviated with these aliases (all were fetched on 2026-11-06):

| Alias | Full URL |
|---|---|
| SPEC | https://github.com/apache/iceberg/blob/9203eaff9eb97fef9d1f5a3ef4460e8453adaed9/open-api/rest-catalog-open-api.yaml (main HEAD at fetch time, 6,420 lines; `#Lx-Ly` are line anchors) |
| SPEC-1.12 | https://github.com/apache/iceberg/blob/apache-iceberg-1.12.0/open-api/rest-catalog-open-api.yaml (latest release, 6,414 lines) |
| SPEC-x.y | same path at tag `apache-iceberg-x.y.0` (1.6.0 … 1.11.0 were downloaded and grepped to date features) |
| S3SIGN | https://github.com/apache/iceberg/blob/main/aws/src/main/resources/s3-signer-open-api.yaml |
| OAREADME | https://github.com/apache/iceberg/blob/main/open-api/README.md |
| PROTO | https://github.com/apache/iceberg/blob/main/docs/docs/rest-protocol.md |
| RESTDOC | https://github.com/apache/iceberg/blob/main/docs/docs/rest-catalog.md |
| JREST | https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/ (file name follows, e.g. JREST/RESTSessionCatalog.java#L125) |
| JAUTH | https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/auth/ |
| RELNOTES | https://github.com/apache/iceberg/blob/main/site/docs/releases.md |
| CONTRIB | https://github.com/apache/iceberg/blob/main/site/docs/contribute.md |
| SPECLOG | `gh api repos/apache/iceberg/commits?path=open-api/rest-catalog-open-api.yaml&since=2025-01-01` (66 commits, listed by date and title) |
| DEVVOTES | https://lists.apache.org/api/stats.lua?list=dev&domain=iceberg.apache.org&d=lte=365d&q=VOTE (dev@ list subjects, last 365 days) |
| PY | https://github.com/apache/iceberg-python/blob/main/pyiceberg/catalog/rest/__init__.py (latest release pyiceberg-0.12.0, 2026-09-01) |
| RS | https://github.com/apache/iceberg-rust/blob/main/crates/catalog/rest/src/ (latest release v0.11.0, 2026-11-03) |
| GO | https://github.com/apache/iceberg-go/blob/main/catalog/rest/ (latest release v0.7.0, 2026-10-01) |
| DUCK | https://github.com/duckdb/duckdb-iceberg/blob/main/ |
| TRINO | https://github.com/trinodb/trino/blob/master/ (latest release 484, 2026-10-16) |

**What I read in full vs. skimmed.** SPEC was read line by line for all paths (L1-L2084), parameters (L2085-L2266), and the config, update, requirement, credential, read-restriction, label, LoadTableResult, scan-planning, commit-request, OAuth, metrics, error, signing, and response schemas. I only grepped or skimmed the type/expression schemas (L2439-L2940), the function-definition schemas (L5078-L5278), the value-type and content-file schemas (L5317-L5700), and the examples (L6197-L6420). Java, Python, Rust, Go, DuckDB, and Trino sources were read in targeted excerpts found by grep, not end to end.

**Release timeline used throughout** [read: `gh api repos/apache/iceberg/releases`]: 1.9.0 2025-04-28; 1.10.0 2025-09-11; 1.10.1 2025-12-22; 1.11.0 2026-05-20; 1.12.0 2026-09-30 (the release notes page says September 29) [read: RELNOTES]. The only differences between SPEC-1.12 and main are: the new `none` value for `X-Iceberg-Access-Delegation`, a missing `type: object` on `AssertViewUUID`, and a column-stat field rename (`avg-value-size-in-bytes` int32 to `total-bytes` int64) [read: `diff SPEC-1.12 SPEC`].

---

## 1. Full endpoint inventory (main and 1.12.0), required vs optional, and how clients discover support

### Takeaway
The spec has 37 operations, but it marks almost nothing as "required": support is negotiated through the `endpoints` list in `GET /v1/config`, and when a server omits that list, clients assume a fixed legacy default set of 14 namespace and table endpoints. The practical minimum is the default set plus `HEAD` exists checks; everything else (views, credentials, scan planning, signing, functions, unregister, register-view) is opt-in by advertising it, and the deprecated `/v1/oauth/tokens` is explicitly "not recommended to implement."

### Cited Findings

**How support is discovered**
- `GET /v1/config` returns `defaults` (applied before client config), `overrides` (applied after client config), an optional `endpoints` list, and optional `idempotency-key-lifetime`. "All REST clients should first call this route." [read: SPEC#L65-L135, SPEC#L2298-L2341]
- Each `endpoints` entry must be the string `"<HTTP verb> <resource path from OpenAPI REST spec>"`, for example `"GET /v1/{prefix}/namespaces/{namespace}"` [read: SPEC#L2317-L2329]. The Java client parses that exact format and compares method plus templated path [read: JREST/Endpoint.java].
- If `endpoints` is absent, the spec says clients assume exactly 14 endpoints: list/create/load/drop namespace, update namespace properties, list/create/load/commit/drop table, register table, report metrics, rename table, and `POST /transactions/commit` [read: SPEC#L105-L134]. The Java client hard-codes the same 14, with the comment that "these default endpoints must not be updated in order to maintain backwards compatibility with legacy servers" [read: JREST/RESTSessionCatalog.java#L125-L143]. HEAD exists endpoints were removed from the defaults on 2025-02-21 (#12351) [read: SPECLOG].
- View endpoints are never assumed. The Java client adds the 6 view endpoints only if the client sets `view-endpoints-supported=true` (default false) and the server sent no list [read: JREST/RESTSessionCatalog.java#L145-L155, #L232-L245; RESTDOC L82].
- The Java client calls `Endpoint.check(...)` before most operations and throws `UnsupportedOperationException("Server does not support endpoint: …")` when the endpoint is not advertised; `loadTable` throws `NoSuchTableException` instead; `listTables` silently returns an empty list; `tableExists` falls back to `loadTable` "in order to work with 1.7.x and older servers" [read: JREST/Endpoint.java; JREST/RESTSessionCatalog.java#L324-L327, #L417-L435].
- PROTO states the design intent: "a server that does not implement, for example, view or scan-planning endpoints simply causes those features to be unavailable rather than producing failed requests." [read: PROTO L50-L53]

**Path conventions**
- All catalog routes are under `/v1/{prefix}/…`; `prefix` is a required path parameter described as "An optional prefix in the path", usually set by the server as a config override so one server can host several catalogs [read: SPEC#L2108-L2114; PROTO L46-L49].
- Multipart namespaces are one path segment joined by the separator advertised in config override `namespace-separator` (default unit separator `0x1F`, URL-encoded `%1F`); "servers must use both the advertised separator and `0x1F` as valid separators" for older clients [read: SPEC#L2090-L2106]. Configurable separator was added 2025-12-08 (#14448) and first shipped in 1.11.0 [read: SPECLOG; grep of SPEC-1.10 vs SPEC-1.11].
- The OpenAPI version is 3.1.1, `info.version` is `0.0.1`, and the description says "Defines the specification for the first version of the REST Catalog API" [read: SPEC#L21-L30]. There is no semantic version for the REST spec itself; evolution is additive plus endpoint advertisement [inference from the same lines and the absence of any other version field].
- Global security is declared as `OAuth2: [catalog]` or `BearerAuth` ("All routes are currently configured using an Authorization header") [read: SPEC#L59-L62].

**Inventory.** "Default" means the endpoint is in the assumed-if-not-advertised set. "Since" is the first release whose spec file contains it (from grepping each tag's YAML) unless a commit date is given.

| # | Operation (operationId) | Method and path (after `/v1/{prefix}`) | Default? | Since | Notes |
|---|---|---|---|---|---|
| 1 | getConfig | `GET /v1/config?warehouse=` | always | pre-1.6 | 404 if `warehouse` unknown (added 2026-04-15, #15746) [read: SPEC#L159-L173; SPECLOG] |
| 2 | getToken (DEPRECATED) | `POST /v1/oauth/tokens` | n/a | pre-1.6 | "DEPRECATED for REMOVAL … _not_ recommended to implement … removed from this spec in Iceberg (Java) 2.0" [read: SPEC#L181-L248] |
| 3 | listNamespaces | `GET /namespaces?parent=&pageToken=&pageSize=` | yes | | `parent` lists one level only [read: SPEC#L254-L306] |
| 4 | createNamespace | `POST /namespaces` | yes | | 409 if exists; Idempotency-Key [read: SPEC#L308-L349] |
| 5 | loadNamespaceMetadata | `GET /namespaces/{ns}` | yes | | [read: SPEC#L356-L385] |
| 6 | namespaceExists | `HEAD /namespaces/{ns}` | no (since 2025-02) | | 204/404, no body [read: SPEC#L387-L417] |
| 7 | dropNamespace | `DELETE /namespaces/{ns}` | yes | | "Namespace must be empty"; 409 if not empty [read: SPEC#L419-L458] |
| 8 | updateProperties | `POST /namespaces/{ns}/properties` | yes | | "Server implementations are not required to support namespace properties"; 422 if a key is in both removals and updates [read: SPEC#L460-L523] |
| 9 | listTables | `GET /namespaces/{ns}/tables` | yes | | paginated [read: SPEC#L530-L562] |
| 10 | createTable | `POST /namespaces/{ns}/tables` | yes | | `stage-create` for CTAS-style create transactions; `X-Iceberg-Access-Delegation` header [read: SPEC#L564-L623] |
| 11 | listFunctions | `GET /namespaces/{ns}/functions` | no | 1.12.0 | SQL UDFs (merged 2026-06-07, #15180) [read: SPEC#L625-L662; SPECLOG] |
| 12 | loadFunction | `GET /namespaces/{ns}/functions/{function}` | no | 1.12.0 | "All overloaded definitions are included in a single response" [read: SPEC#L664-L705] |
| 13 | planTableScan | `POST /namespaces/{ns}/tables/{t}/plan` | no | 1.7.0 | server-side scan planning [read: SPEC#L707-L794] |
| 14 | fetchPlanningResult | `GET …/tables/{t}/plan/{plan-id}` | no | 1.7.0 | [read: SPEC#L796-L860] |
| 15 | cancelPlanning | `DELETE …/tables/{t}/plan/{plan-id}` | no | 1.7.0 | [read: SPEC#L862-L916] |
| 16 | fetchScanTasks | `POST …/tables/{t}/tasks` | no | 1.7.0 | [read: SPEC#L919-L969] |
| 17 | registerTable | `POST /namespaces/{ns}/register` | yes | | `overwrite` flag added 2025-02-17 (#12239) [read: SPEC#L971-L1025, #L4510-L4523; SPECLOG] |
| 18 | loadTable | `GET /namespaces/{ns}/tables/{t}?snapshots=all\|refs&referenced-by=` | yes | | `If-None-Match`/304 (1.8.0); may return `read-restrictions` (1.12.0) [read: SPEC#L1033-L1116] |
| 19 | updateTable (commit) | `POST /namespaces/{ns}/tables/{t}` | yes | | see section 2 [read: SPEC#L1118-L1236] |
| 20 | dropTable | `DELETE /namespaces/{ns}/tables/{t}?purgeRequested=` | yes | | `purgeRequested` default false [read: SPEC#L1238-L1277] |
| 21 | tableExists | `HEAD /namespaces/{ns}/tables/{t}` | no (since 2025-02) | | [read: SPEC#L1279-L1310] |
| 22 | unregisterTable | `POST …/tables/{t}/unregister` | no | 1.12.0 | leaves files in place; returns last metadata; "All attempted commits after the unregister operation in this catalog must fail" [read: SPEC#L1312-L1360] |
| 23 | loadCredentials | `GET …/tables/{t}/credentials?planId=&referenced-by=` | no | 1.7.0 spec | `planId` param added 2025-11-19 (#14519) [read: SPEC#L1362-L1406; SPECLOG] |
| 24 | signRequest | `POST …/tables/{t}/sign` | no | 1.11.0 | remote signing promoted into the main spec 2026-03-31 (#15450) [read: SPEC#L1408-L1440; SPECLOG] |
| 25 | renameTable | `POST /tables/rename` | yes | | cross-namespace move allowed but "not required to support it"; 406 if unsupported [read: SPEC#L1442-L1504] |
| 26 | reportMetrics | `POST …/tables/{t}/metrics` | yes | | ScanReport / CommitReport, 204 [read: SPEC#L1506-L1548, #L4706-L4854] |
| 27 | commitTransaction | `POST /transactions/commit` | yes | | multi-table atomic commit, 204 [read: SPEC#L1550-L1665] |
| 28 | listViews | `GET /namespaces/{ns}/views` | no | | [read: SPEC#L1672-L1704] |
| 29 | createView | `POST /namespaces/{ns}/views` | no | | note: no Idempotency-Key parameter defined [read: SPEC#L1706-L1751] |
| 30 | loadView | `GET …/views/{v}?referenced-by=` | no | | no ETag defined for views [read: SPEC#L1759-L1805, #L6161-L6166] |
| 31 | replaceView (commit) | `POST …/views/{v}` | no | | same 409/500/502/504 contract as tables [read: SPEC#L1807-L1907] |
| 32 | dropView | `DELETE …/views/{v}` | no | | [read: SPEC#L1909-L1941] |
| 33 | viewExists | `HEAD …/views/{v}` | no | | [read: SPEC#L1943-L1964] |
| 34 | renameView | `POST /views/rename` | no | | [read: SPEC#L1966-L2028] |
| 35 | registerView | `POST /namespaces/{ns}/register-view` | no | 1.11.0 | (#14869, merged 2026-01-17) [read: SPEC#L2030-L2083; SPECLOG] |
| 36 | signS3Request (separate, DEPRECATED) | `POST /v1/aws/s3/sign` | n/a | | separate spec in the aws module, now titled "[DEPRECATED] Apache Iceberg S3 Signer API … Use the new remote signing endpoint instead" [read: S3SIGN L19-L80] |

Operations added per release (diff of operationIds): 1.9 to 1.10 none; 1.10 to 1.11 `registerView`, `signRequest`; 1.11 to 1.12 `listFunctions`, `loadFunction`, `unregisterTable`; 1.12 to main none [read: operationId diff of SPEC-1.9 … SPEC].

**Cross-cutting optional features and how each is discovered**
- **Pagination.** `pageToken` and `pageSize` on list endpoints. Clients start with an empty `pageToken`; servers return `next-page-token`; null or missing means end. "Servers that support pagination must return all results in a single response with the value of `next-page-token` set to `null` if the query parameter `pageToken` is not set." Servers that do not paginate ignore the token and omit `next-page-token` [read: SPEC#L2180-L2196, #L2391-L2414]. No advertisement exists; the Java client always sends `pageToken` and sends `pageSize` only if the client sets `rest-page-size` [read: JREST/RESTSessionCatalog.java#L324-L350; RESTDOC L83].
- **Idempotency-Key** (1.11.0, merged 2025-11-20, #14196; plan endpoints added 2025-12-08, #14730). Optional header (UUIDv7, 36 chars) on most mutations. Finalize and replay 200/201/204 and "deterministic terminal 4xx (including 409 …)"; do not store 5xx. Clients "MUST reuse the same key when retrying the same logical operation." Support is advertised by `idempotency-key-lifetime` (ISO-8601 duration) in the config response: "If absent, clients MUST assume idempotency is not supported." [read: SPEC#L2237-L2265, #L2330-L2341; SPECLOG]. Covered operations: createNamespace, dropNamespace, updateProperties, createTable, planTableScan, cancelPlanning, fetchScanTasks, registerTable, updateTable, dropTable, unregisterTable, renameTable, commitTransaction, replaceView, dropView, renameView, registerView. Not covered: createView, reportMetrics, signRequest [read: SPEC, `idempotency-key` parameter references]. The text says keys should not expire "before the end of the advertised token lifetime", which appears to mean the key lifetime [read: SPEC#L2263; inference on intent].
- **ETag / If-None-Match** (1.8.0). `ETag` header on CreateTable, LoadTable, and CommitTable responses (commit ETag added 2025-12-19, #14760); `If-None-Match` on loadTable yields `304 Not Modified`. ETags must differ for `snapshots=refs` vs `all` representations [read: SPEC#L1066-L1094, #L2198-L2210, #L6106-L6179; SPECLOG]. Not advertised; the Java client caches tables that came with an ETag (default 5 minutes, 100 entries) and sends `If-None-Match` on the next load [read: PROTO L153-L158; RESTDOC L100-L107; JREST/RESTSessionCatalog.java L497-L510].
- **Snapshot loading mode.** `snapshots=all|refs`; default `all`; `refs` returns only snapshots referenced by branches or tags [read: SPEC#L1075-L1086]. Not advertised; the Java client opts in with `snapshot-loading-mode=REFS` and lazily re-loads with `all` when it needs full history [read: JREST/RESTSessionCatalog.java L566-L585]. Trino's docs warn that `REFS` "Requires REST catalog server support" [read: TRINO/docs/src/main/sphinx/object-storage/metastores.md L498-L504].
- **`referenced-by`** (1.11.0, #13810): comma-separated chain of views through which a table or view is being loaded, so the server can authorize via view ownership [read: SPEC#L2212-L2235; SPECLOG].
- **Labels** (1.12.0, #15750): optional `labels` (object-level and per-field key/value pairs, e.g. ownership or classification) on LoadTableResult and LoadViewResult; "clients may ignore them" [read: SPEC#L4179-L4219, #L4291-L4292, #L4592-L4593; SPECLOG].
- **Scan-planning mode** (1.11.0): table config `scan-planning-mode` = `client` or `server` ("Clients MUST use server-side scan planning via the `planTableScan` endpoint") [read: SPEC#L4238-L4240].
- **No events, notifications, or batch-load endpoints exist** in SPEC or SPEC-1.12 (operationId list above). They are open proposals (section 7) [read: SPEC operationId list].

### Inferences
- A server that always sends an explicit `endpoints` list controls its own surface precisely. A server that omits it implicitly promises `POST /transactions/commit` and the metrics endpoint to Java, Rust, and Go clients, because those are in their default sets [inference from SPEC#L105-L134 and the client defaults in section 6]. Always send the list.
- "Required" in practice means: anything in the default set that your target engines actually call, plus correct error codes. Namespace properties, cross-namespace rename, pagination, idempotency, ETags, and every non-default endpoint are optional per the spec text cited above [inference].
- The spec has grown from 4,891 lines (1.9.0) to 6,420 lines (main) in about 18 months [read: `wc -l` of downloaded files], almost entirely through optional additions. Growth of the spec is not, by itself, evidence that an implementation must be large [inference].

### Gaps
- I did not verify which `endpoints` strings each commercial catalog (Polaris, Glue, Unity, S3 Tables) advertises; that was out of scope and not read.
- The exact first release containing `loadCredentials` could be 1.7.0 or 1.8.0; I only grepped that the string appears once in the 1.7.0 YAML.

---

## 2. Commit semantics, every requirement and update type, and the error contract

### Takeaway
A commit is "assert these requirements against current metadata, then apply these updates," and the server owns validation and the atomic swap. The single most important contract is the outcome code: 409 means "definitely not applied; client refreshes and retries," while 500/502/504 (and in Java, 503) mean "state unknown; client must not clean up." A server that ever returns 409 for a commit that actually landed, or 5xx with a 409 meaning, can cause data loss or corrupt tables. Unknown update or requirement types must be rejected with 400, so the server must implement every update type the clients emit.

### Cited Findings

**Commit request shapes**
- `CommitTableRequest` = optional `identifier` (required inside a transaction), `requirements[]`, `updates[]` [read: SPEC#L4441-L4457]. `CommitTransactionRequest` = `table-changes[]`, each a CommitTableRequest with identifier [read: SPEC#L4476-L4485]. `CommitViewRequest` = `updates[]` plus optional `requirements[]` [read: SPEC#L4459-L4474].
- "Server implementations are required to fail with a 400 status code if any unknown updates or requirements are received." [read: SPEC#L1132-L1133, #L1569-L1570]
- Success: table commit returns `CommitTableResponse` (`metadata-location` and `metadata`, both required, plus ETag) [read: SPEC#L5306-L5315, #L6168-L6179]; transaction commit returns 204 with no body [read: SPEC#L1581-L1582].
- Stage-create: `createTable` with `stage-create=true` returns initialized metadata without creating the table; the client later sends all create changes (e.g. `add-schema`, `set-current-schema`) to the commit route with `assert-create` "to ensure that the table was not created concurrently." `metadata-location` may be null for staged metadata [read: SPEC#L568-L580, #L1141-L1144, #L4273-L4276]. The Java client uses stage-create for `createTransaction`, `replaceTransaction`, and `createOrReplaceTransaction` (the CTAS/RTAS path) [read: JREST/RESTSessionCatalog.java L1130-L1258].

**Table requirements (8)** [read: SPEC#L3679-L3819]
| type | semantics |
|---|---|
| `assert-create` | table must not exist (create transactions) |
| `assert-table-uuid` | table UUID equals `uuid` |
| `assert-ref-snapshot-id` | branch/tag `ref` points at `snapshot-id`; `snapshot-id: null` means the ref must not exist (field required but nullable, clarified 2025-08-22, #13902) |
| `assert-last-assigned-field-id` | last assigned column id matches |
| `assert-current-schema-id` | current schema id matches |
| `assert-last-assigned-partition-id` | last assigned partition field id matches |
| `assert-default-spec-id` | default spec id matches |
| `assert-default-sort-order-id` | default sort order id matches |

**View requirement (1):** `assert-view-uuid` [read: SPEC#L3821-L3841].

**Which requirements the Java client actually sends** [read: https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/UpdateRequirements.java]: create sends `assert-create` (Java name `AssertTableDoesNotExist`); replace and update send `assert-table-uuid`; `set-snapshot-ref` adds `assert-ref-snapshot-id` for the base ref; `add-schema` adds `assert-last-assigned-field-id`; `set-current-schema` adds `assert-current-schema-id`; `add-spec` adds `assert-last-assigned-partition-id`; `set-default-spec` adds `assert-default-spec-id`; `set-default-sort-order` adds `assert-default-sort-order-id`; `remove-partition-specs` and `remove-schemas` also pin the default spec/current schema and every branch's snapshot id.

**Metadata updates (25 actions)** [read: SPEC#L3246-L3677]
| action | payload | table | view |
|---|---|---|---|
| assign-uuid | uuid ("should only be done when creating") | yes | yes |
| upgrade-format-version | format-version (max 3) | yes | yes |
| add-schema | schema (`last-column-id` deprecated) | yes | yes |
| set-current-schema | schema-id (-1 = last added) | yes | |
| add-spec | spec | yes | |
| set-default-spec | spec-id (-1 = last added) | yes | |
| add-sort-order | sort-order | yes | |
| set-default-sort-order | sort-order-id (-1 = last added) | yes | |
| add-snapshot | snapshot (incl. v3 `first-row-id`, `added-rows`) | yes | |
| set-snapshot-ref | ref-name + SnapshotReference | yes | |
| remove-snapshots | snapshot-ids | yes | |
| remove-snapshot-ref | ref-name | yes | |
| set-location | location | yes | yes |
| set-properties | updates map | yes | yes |
| remove-properties | removals | yes | yes |
| add-view-version | view-version | | yes |
| set-current-view-version | view-version-id (-1 = last added) | | yes |
| set-statistics | statistics (`snapshot-id` deprecated) | yes | |
| remove-statistics | snapshot-id | yes | |
| set-partition-statistics | partition-statistics | yes | |
| remove-partition-statistics | snapshot-id | yes | |
| remove-partition-specs | spec-ids (1.9.0 era) | yes | |
| remove-schemas | schema-ids (added 2025-02-14, #12022) | yes | |
| add-encryption-key | encryption-key (1.10.0) | yes | |
| remove-encryption-key | key-id (1.10.0) | yes | |
TableMetadata `format-version` max is 3 (raised 2025-08-29, #13505) and includes v3 `next-row-id` and `encryption-keys` [read: SPEC#L3064-L3146; SPECLOG].

**Error contract** (all errors are `{"error": {"message", "type", "code", "stack"?}}`; `type` is free text but the Java client switches on it) [read: SPEC#L2272-L2296, #L4917-L4933]
| code | meaning in the spec | Java client mapping [read: JREST/ErrorHandlers.java] |
|---|---|---|
| 400 | malformed request or validation failure; unknown update/requirement types | `BadRequestException`; or `IllegalArgumentException` / `ValidationException` if `type` says so; namespace routes map type `NamespaceNotEmptyException` |
| 401 | token "expired, revoked, malformed, or invalid"; client MAY get a new token and retry [read: SPEC#L5920-L5935] | `NotAuthorizedException` |
| 403 | authenticated but not permitted | `ForbiddenException` |
| 404 | NoSuchTable / NoSuchNamespace / NoSuchView / NoSuchPlanId / NoSuchPlanTask / NoSuchWarehouse / NoSuchFunction | disambiguated by `type`; default per route |
| 406 | "Not Acceptable / Unsupported Operation" [read: SPEC#L5955-L5967] | falls through to generic `RESTException` |
| 409 | create/rename/register: already exists; dropNamespace: not empty; commit routes: `CommitFailedException`, "one or more requirements failed. The client may retry." [read: SPEC#L1170-L1176] | `AlreadyExistsException`, `NamespaceNotEmptyException`, or `CommitFailedException` by route |
| 419 | optional "Authentication Timeout"; "401 … SHOULD be preferred" [read: SPEC#L6042-L6057] | generic |
| 422 | namespace property key in both removals and updates [read: SPEC#L509-L517] | `RESTException` |
| 500 | on commit routes: "the commit state is unknown" (`CommitStateUnknownException`) [read: SPEC#L1179-L1192] | commit routes: `CommitStateUnknownException`; otherwise `ServiceFailureException` |
| 502 / 504 | on commit routes: gateway error / gateway timeout, "the commit state is unknown" [read: SPEC#L1195-L1222] | commit routes: `CommitStateUnknownException` |
| 503 | "request could have been partially processed"; a non-idempotent request "should only be retried by the client when the Retry-After header is present" [read: SPEC#L6059-L6075] | commit routes: `CommitStateUnknownException`; otherwise `ServiceUnavailableException` [read: JREST/ErrorHandlers.java#L120-L141, #L322-L360] |
| 501 | not in spec | `UnsupportedOperationException` |

**Client behaviour on outcomes**
- 409 to client retry: PROTO says that on a failed requirement "the client refreshes the table and reapplies its changes before retrying" [read: PROTO L74-L79].
- Unknown state, Java: on `CommitStateUnknownException` for a "SIMPLE" commit that only adds one snapshot to `main`, the Java client refreshes and treats the commit as successful if the new snapshot id is present; otherwise it rethrows [read: JREST/RESTTableOperations.java#L178-L300].
- Transport retries, Java (1.12.0 behaviour change): the HTTP client retries 429 always; retries 408/500/502/503/504 only for idempotent methods **or requests carrying an `Idempotency-Key`**; retries 503 with `Retry-After`; and stops retrying keyed requests once the advertised key lifetime would be exceeded [read: JREST/ExponentialHttpRequestRetryStrategy.java L40-L170; RELNOTES "The REST client now retries POST requests carrying an Idempotency-Key on retriable errors (408, 500, 502, 503, 504) (#17947)"]. The client attaches a UUIDv7 key to mutations only when the server advertised `idempotency-key-lifetime` [read: JREST/RESTSessionCatalog.java#L223-L228; JREST/RESTUtil.java L434-L442].
- Transport retries, PyIceberg: retries 429/500/502/503/504 only for GET, HEAD, OPTIONS, PUT, DELETE (not POST); commit maps 409 to `CommitFailedException` and 500/502/504 to `CommitStateUnknownException` (503 is not mapped to unknown) [read: PY L299-L300, L543-L547, L1611-L1640].
- iceberg-rust maps 409 to a retryable `CatalogCommitConflicts` and 500/502/504 to "the commit state is unknown" errors [read: RS/catalog.rs L1636-L1668]; iceberg-go maps 500, 502, **503**, and 504 on commit routes to `ErrCommitStateUnknown` [read: GO/rest.go L116, L1820-L1823]. So 503 means "unknown" to Java and Go, but PyIceberg and Rust do not map it that way.
- Reference server behaviour: `CatalogHandlers` validates requirements against current metadata, then retries the apply-and-swap internally on `CommitFailedException` (re-validating requirements against the refreshed base) up to the default commit retry count [read: JREST/CatalogHandlers.java#L604-L640]. The RCK has a switch `rck.supports-serverside-retry` (default true) for exactly this behaviour [read: OAREADME L72-L80].

**Why "commit state unknown" matters for server design**
- In the Java core library, `CommitFailedException` triggers refresh, re-apply, and retry; files written for the failed attempt (manifests, manifest lists, metadata) are cleaned up when the commit is finally abandoned. `CommitStateUnknownException` deliberately does not trigger cleanup, because the files may now be referenced by a committed snapshot [secondary: recalled Iceberg core behaviour; not re-read this session, but consistent with the reconciliation logic read in JREST/RESTTableOperations.java#L226-L300].
- Spec change 2025-08-21 "Spec, Core: Mark 503 as non retryable error code for Update Table" (#13619) [read: SPECLOG]; the current spec text keeps 503 as "could have been partially processed" [read: SPEC#L6059-L6066].

### Inferences
- **The 409 promise is a safety property, not a status code.** If the server times out talking to its store (e.g. a MongoDB write with majority concern that times out after the primary applied it), it does not know whether the pointer moved. Returning 409 in that case tells the client "not applied", the client cleans up the new manifests, and if the write actually landed the table now points at deleted files. Such cases must return 500 (or 503/504) [inference from the code table above and the client behaviour cited].
- **Idempotency keys turn "unknown" into "known"** for the Java client: after a lost response it retries the same key, and a correct server replays the original 200 instead of returning 409 (a naive re-execution would fail the `assert-ref-snapshot-id` requirement and produce a misleading 409). Therefore: only advertise `idempotency-key-lifetime` when the key store is durable and shared across every node and region that can receive the retry. The reference store is in-memory and its own comment says "Production servers should provide a durable store." [read: JREST/CatalogHandlers.java L120-L127; inference on consequences]
- **Server-side re-apply is optional but valuable.** If two writers commit non-conflicting appends to the same table, a server that re-validates the requirements against the latest metadata and applies the second commit avoids a client round-trip. But the server must re-validate the requirements exactly as written; it may not "merge" when a requirement fails [inference from JREST/CatalogHandlers.java#L623-L640].
- **The 400-on-unknown rule means the update list is not negotiable.** Spark/Java table maintenance (expire snapshots, statistics, schema cleanup) emits `remove-snapshots`, `remove-snapshot-ref`, `set-statistics`, `remove-partition-specs`, `remove-schemas`, etc. Every type in the table above must be supported to avoid breaking ordinary maintenance jobs. This looks like bloat but is required [inference from SPEC#L1132 and UpdateRequirements.java].
- **Metadata-table naming trap.** On a 404 for `db.tbl.snapshots`, the Java client retries `loadTable` with the namespace as the table (`db.tbl`) to serve metadata tables, but only if the error maps to `NoSuchTableException`. If the server answers that first 404 with `type: NoSuchNamespaceException` (because namespace `db.tbl` does not exist), the client throws `NoSuchNamespaceException` and metadata-table queries fail [inference from JREST/RESTSessionCatalog.java L512-L545 and JREST/ErrorHandlers.java#L143-L160; not tested]. Return `NoSuchTableException` from loadTable 404s.
- **Reusing iceberg-core in a Java server** gives exact semantic parity: `UpdateRequirement.validate(...)` and `MetadataUpdate` application are the same code the clients and the reference server use [inference from JREST/CatalogHandlers.java#L604-L640].

### Gaps
- I did not re-read Iceberg core's `SnapshotProducer` cleanup code this session; the "no cleanup on unknown" statement is secondary (recalled), though widely documented.
- The spec does not say what status to return when a server-side store write times out; the recommendation above is inference.

---

## 3. Access delegation: vended credentials, remote signing, refresh

### Takeaway
Access delegation is negotiated per request by the `X-Iceberg-Access-Delegation` header (`vended-credentials`, `remote-signing`, and since 2026-10-29 on main only, `none`), and the server decides what to return. Vended credentials arrive in `storage-credentials` (preferred) or `config`, refresh goes through `GET …/credentials`, and the Java S3 refresh path has hard requirements (exactly one `s3*` credential, an expiry key, 5-minute prefetch) that a server must meet.

### Cited Findings
- Header semantics: "Optional signal to the server that the client supports delegated access via a comma-separated list of access mechanisms. The server may choose to supply access via any or none of the requested mechanisms." Values: `vended-credentials`, `remote-signing`, and on main `none` ("the client is able to operate without any access delegation mechanism") [read: SPEC#L2151-L2178]. `none` was merged 2026-10-29 (#18480), so it is not in 1.12.0 [read: SPECLOG; diff vs SPEC-1.12].
- The header applies to createTable, registerTable (added 2026-02-09, #15231), loadTable, planTableScan, and fetchPlanningResult (plan endpoints added 2026-02-09, #14781) [read: SPEC#L583, #L712, #L802, #L981, #L1065; SPECLOG].
- `LoadTableResult` fields: `metadata-location` (nullable when staged), `metadata`, `config`, `storage-credentials[]`, `remote-signing-config`, `read-restrictions`, `labels` [read: SPEC#L4269-L4292].
- `StorageCredential` = `prefix` + `config` map; clients "choose the most specific prefix (by selecting the longest prefix)". "Clients must first check whether the respective credentials exist in the `storage-credentials` field before checking the `config`." [read: SPEC#L3843-L3856, #L4252-L4255]
- Documented S3 config keys: `client.region`, `s3.access-key-id`, `s3.secret-access-key`, `s3.session-token`, `s3.remote-signing-enabled`, `s3.cross-region-access-enabled` [read: SPEC#L4242-L4250].
- `GET …/credentials` returns `LoadCredentialsResponse { storage-credentials[] }` (required), accepts `planId` (credentials for a server-planned scan) and `referenced-by` [read: SPEC#L1362-L1406, #L4169-L4177]. Completed scan-planning results can also carry `storage-credentials` (added 2025-11-20, #14563) [read: SPEC#L4340-L4349; SPECLOG].
- **Java refresh flow** [read: https://github.com/apache/iceberg/blob/main/aws/src/main/java/org/apache/iceberg/aws/s3/VendedCredentialsProvider.java L48-L193; https://github.com/apache/iceberg/blob/main/aws/src/main/java/org/apache/iceberg/aws/AwsClientProperties.java L81-L84; https://github.com/apache/iceberg/blob/main/aws/src/main/java/org/apache/iceberg/aws/s3/S3FileIOProperties.java L249-L294]:
  - The provider first uses credentials from properties only if all four of `s3.access-key-id`, `s3.secret-access-key`, `s3.session-token`, `s3.session-token-expires-at-ms` are present and expiry is more than 5 minutes away.
  - Otherwise it calls the credentials endpoint, filters credentials whose prefix starts with `s3`, and fails unless exactly one remains ("only one S3 credential should exist") and it contains all four keys.
  - Prefetch time is expiry minus 5 minutes; stale time is expiry.
  - The refresh URL is enabled through `client.refresh-credentials-endpoint` and `client.refresh-credentials-enabled`, which "servers typically supply in the returned table configuration" [read: PROTO L107-L111].
  - 1.11.0 added "scheduled refresh for the S3FileIO held storage credentials (#15678)" and the GCS equivalent (#15696) [read: RELNOTES].
- **Remote signing.** The signing request carries `region`, `uri`, `method`, `headers`, optional `properties`, optional `body` (only for requests such as S3 `DeleteObjects` whose body must be validated), and `provider` (defaults to `s3`); the result returns `uri` and signed `headers`; the response carries `Cache-Control` `private` or `no-cache` [read: SPEC#L5818-L5861, #L6188-L6195]. `RemoteSigningConfig` (static `properties` and `headers` the signer client MUST pass through) was formalized 2026-07-30 (#16822) and shipped in 1.12.0; `signer.endpoint` and `signer.uri` are now DEPRECATED [read: SPEC#L4257-L4267, #L5863-L5879; SPECLOG]. The old `/v1/aws/s3/sign` API is deprecated [read: S3SIGN L19-L22], and 1.12.0 removed "deprecated S3 signer classes and properties (#17627)" [read: RELNOTES].
- PROTO: "Whether credentials are vended is up to the server. Some servers only vend credentials when the request carries the `X-Iceberg-Access-Delegation` header." [read: PROTO L104-L105]
- Client defaults: PyIceberg sends `X-Iceberg-Access-Delegation: vended-credentials` on every request by default [read: PY L268, L1210]; DuckDB sends it when `access_delegation_mode` is vended credentials, including on commit requests, because "Some catalogs (e.g. BigLake) … reject the commit with a 400 asking for vended-credentials" [read: DUCK/src/catalog/rest/api/catalog_api.cpp L482-L506]; the Java client sends it only if configured through the generic `header.` prefix (e.g. `header.X-Iceberg-Access-Delegation`) [read: JREST/RESTUtil.java L434]; iceberg-go sends it on scan-planning requests by default [read: GO/rest.go L80-L82].

### Inferences
- **(a) required:** none of this is required by the spec; a catalog can return no credentials and let engines use their own storage identity [inference from SPEC#L2155-L2157].
- **(b) enterprise:** vended credentials are the enterprise feature with the highest value: they make the catalog the single storage-authorization point and remove long-lived bucket credentials from every engine. To work with Java's refresher, put exactly one S3 credential per table in `storage-credentials`, include `s3.session-token-expires-at-ms`, set `client.refresh-credentials-endpoint` in `config`, and make credential TTL comfortably longer than the 5-minute prefetch window [inference from the VendedCredentialsProvider read].
- **(c) vendor-leaning:** remote signing exists for catalogs that never release credentials (the catalog sits in the data path for every object request, so its latency and availability gate all reads). It is useful where policy forbids credential egress, but it is the more expensive mode to run [inference].
- For on-prem S3-compatible storage without STS, vending is not possible and remote signing or engine-owned credentials are the only options [inference].

### Gaps
- I did not read the ADLS and GCS vended-credential key names in this session.
- I did not verify how Snowflake or Trino handle credential refresh when a query outlives the vended TTL.

---

## 4. Authentication: OAuth2 in the spec, deprecation of `/v1/oauth/tokens`, the Java AuthManager API, and how engines authenticate

### Takeaway
The spec only says "send an Authorization header" (OAuth2 or Bearer). The catalog-as-token-endpoint (`/v1/oauth/tokens`) has been deprecated since Java 1.6.0, is "not recommended to implement," and will be removed in Java 2.0. Auth is now pluggable on the client through `AuthManager` (`none`, `basic`, `oauth2`, `sigv4`, `google`, or a custom class). The enterprise path is an external IdP issuing JWT bearer tokens, but most clients still default the token URL to the catalog, so every client config must set `oauth2-server-uri` explicitly.

### Cited Findings
- Spec: security schemes are OAuth2 (scope `catalog`) or BearerAuth on every route [read: SPEC#L59-L62].
- `/v1/oauth/tokens` "is **DEPRECATED for REMOVAL**. It is _not_ recommended to implement this endpoint, unless you are fully aware of the potential security implications. All clients are encouraged to explicitly set the configuration property `oauth2-server-uri`… Deprecated since Iceberg (Java) 1.6.0. The endpoint and related types will be removed from this spec in Iceberg (Java) 2.0." It supported client-credentials, token exchange (subject + actor tokens), and refresh via token exchange [read: SPEC#L181-L248].
- Rationale (issue #10537, 2024-06-19, closed): the endpoint "pushes operators of Iceberg REST endpoints into the authorization service business"; clients "might expose their clear-text credentials to the wrong service"; naive servers proxying it become "a man-in-the-middle"; goal is to let servers "opt for other authorization mechanisms than OAuth 2.0" and "integrat[e] with any standard" [read: https://github.com/apache/iceberg/issues/10537].
- Table-level tokens: `loadTable`/`loadView` `config` may include `token` or an RFC 8693 token-type key such as `urn:ietf:params:oauth:token-type:jwt=<JWT>` to be used for subsequent table requests [read: SPEC#L1050-L1053, #L1775-L1778].
- 401 is the preferred response for expired/invalid tokens; clients MAY re-acquire and retry [read: SPEC#L5920-L5935, #L6042-L6046].
- **Java AuthManager API** (1.8+): sessions at four levels: `initSession` (config call only), `catalogSession` (long-lived), `contextualSession(SessionContext, parent)` (per user/engine session), and `tableSession(identifier, tableConfig, parent)` (per table, driven by the table `config`), plus a `tableSession(sharedClient, properties)` for components such as signers [read: JAUTH/AuthManager.java L25-L120]. `rest.auth.type` = `none` | `basic` | `oauth2` | `sigv4` | `google` or a fully qualified class name; if unset, `oauth2` is inferred when `credential` or `token` is present (with a warning); `sigv4` wraps a delegate (default `oauth2`); legacy `rest.sigv4-enabled` is deprecated [read: JAUTH/AuthProperties.java; JAUTH/AuthManagers.java L30-L135].
- Java OAuth2 properties: `token`, `credential` (`client_id:client_secret`), `oauth2-server-uri` (**default `v1/oauth/tokens`**, "Required if the REST catalog is not the OAuth2 authentication server"), `token-expires-in-ms`, `token-refresh-enabled`, `token-exchange-enabled` (default true; disabling "will allow fallback to the client credential flow"), `scope` (default `catalog`), `audience`, `resource` [read: RESTDOC L124-L137]. SigV4: `rest.signing-region`, `rest.signing-name` (default `execute-api`), static keys or credentials provider or assume-role [read: RESTDOC L145-L165]. Google: ADC, `gcp.auth.credentials-path`/`-json`, scopes [read: RESTDOC L167-L174].
- Open Java PRs as of 2026-11-06: a standards-based `OAuth2Manager` that discovers the token endpoint from the issuer (RFC 8414 / OIDC discovery), supports `client_secret_basic`/`client_secret_post`, and "does not use token exchange for refresh" (#18756, opened 2026-11-02); a dependency-free `BearerAuthManager` that reads a token from a periodically re-read file "such as a Kubernetes projected service account token or a sidecar" (#18754); file-based tokens `token-path` (#17389) [read: PR bodies via `gh pr view`].
- **PyIceberg** registers `noop`, `basic`, `legacyoauth2`, `oauth2`, `google`, `entra` auth managers, plus SigV4 via `rest.sigv4-enabled`; it warns when `credential`/`token` is used without `oauth2-server-uri` (deprecation of the catalog token endpoint) [read: https://github.com/apache/iceberg-python/blob/main/pyiceberg/catalog/rest/auth.py L40-L395; PY L288-L302, L992-L1010].
- **iceberg-rust**: `none` or `oauth2` (inferred if `token`, `credential`, or `oauth2-server-uri` set) plus SigV4; default token endpoint is `<uri>/v1/oauth/tokens` [read: RS/catalog.rs L71-L72, L254-L267, L465-L467; RS/auth/sigv4.rs exists].
- **iceberg-go**: OAuth (`oauth2-server-uri` or legacy `rest.authorization-url`), SigV4 options, pluggable signer [read: GO/options.go L97-L170; GO/rest.go L760-L780].
- **Trino**: `iceberg.rest-catalog.security` = `NONE` | `OAUTH2` | `SIGV4` | `GOOGLE`; OAUTH2 needs `oauth2.token` or `oauth2.credential`, optional `oauth2.server-uri`, `token-refresh-enabled`, `token-exchange-enabled`; `iceberg.rest-catalog.session=USER` sends end-user session information [read: TRINO/docs/src/main/sphinx/object-storage/metastores.md L489-L534]. Trino builds a Java `RESTSessionCatalog` underneath [read: TRINO/plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/catalog/rest/TrinoIcebergRestCatalogFactory.java L39, L144].
- **Spark/Flink**: use the Iceberg Java client directly (`spark.sql.catalog.<name>.type=rest`, `rest.auth.type=oauth2`, `credential=…`) [read: RESTDOC L39-L66]. RESTDOC warns that `credential` and `token` appear in Spark's Environment tab and event logs unless covered by `spark.redaction.regex` [read: RESTDOC L139-L143].
- **DuckDB**: `authorization_type` = `oauth2` | `sigv4` | `none`, with options `client_id`, `client_secret`, `token`, `scope`, `endpoint`, and others [read: DUCK/src/catalog/rest/storage/iceberg_authorization.cpp L12-L14; DUCK/src/iceberg_attach_options.cpp].
- **Snowflake** (catalog integration `CATALOG_SOURCE = ICEBERG_REST`): `TYPE = OAUTH` (`OAUTH_TOKEN_URI` "If not specified, Snowflake assumes that the remote catalog provider is the OAuth identity provider", client id/secret, `OAUTH_ALLOWED_SCOPES`), `TYPE = BEARER` (token or PAT), or `TYPE = SIGV4` (IAM role, region, external id) [read: https://docs.snowflake.com/en/sql-reference/sql/create-catalog-integration-rest].
- **StarRocks**: `iceberg.catalog.security` = `OAUTH2` or `JWT`; with `JWT` "StarRocks will use the logged in user's JWT to access the catalog" (end-user identity pass-through) [read: https://github.com/StarRocks/starrocks/blob/main/docs/en/data_source/catalog/iceberg/iceberg.md L288-L290; …/iceberg_rest_security.md L19-L52].

### Inferences
- **Do not implement `/v1/oauth/tokens`** (category c / legacy). But because Java (`oauth2-server-uri` default), Rust, and Snowflake (`OAUTH_TOKEN_URI` unset) default to calling the catalog's token URL, each client configuration must point at the enterprise IdP explicitly; an unconfigured client will POST `client_id:client_secret` to the catalog. Consider making that route return a fixed 400/404 that names the correct IdP URL, without ever accepting the secret [inference from the cited defaults].
- The Java legacy OAuth2 manager refreshes with RFC 8693 token exchange by default; many enterprise IdPs do not support token exchange, so clients should set `token-exchange-enabled=false` until the standards-based manager (#18756) lands [inference from RESTDOC L134 and the #18756 description].
- The server side reduces to validating JWT bearer tokens (issuer, audience, signature via JWKS, expiry) and mapping claims to principals. End-user identity (Trino `session=USER`, StarRocks `JWT`) needs either pass-through user tokens or a trusted-engine model [inference].

### Gaps
- I did not read the Trino or Dremio source to confirm exactly which header carries the `session=USER` identity.
- Dremio's REST-catalog auth options were not visible in the page text I extracted.

---

## 5. Conformance: is there a TCK, and how do implementers use it?

### Takeaway
Yes: the REST Compatibility Kit (RCK) in `open-api/`, a JUnit suite that runs the Java `CatalogTests` (102 `@Test` + 4 parameterized) and `ViewCatalogTests` through the real Java REST client against any server URL. It has a handful of behaviour switches. The reference server it runs against by default is the same code published as the `apache/iceberg-rest-fixture` Docker image. It tests catalog semantics through the Java client only; it does not cover vended credentials, signing, or multi-client behaviour.

### Cited Findings
- OAREADME: "The REST Compatibility Kit (RCK) is a Technology Compatibility Kit (TCK) implementation for the Iceberg REST Specification … tests based on the Java reference implementation … that can be executed against any REST server" [read: OAREADME L43-L47].
- Configuration via `CATALOG_*` env vars (prefix stripped; `_` becomes `.`, `__` becomes `-`, lower-cased) or `rck.*` system properties; run with `./gradlew :iceberg-open-api:test --tests RESTCompatibilityKitSuite -Drck.local=false -Drck.uri=… -Drck.warehouse=… -Drck.credential=…` [read: OAREADME L49-L107].
- Behaviour switches: `rck.requires-namespace-create` (true), `rck.supports-serverside-retry` (true), `rck.overrides-requested-location` (false), `rck.supports-names-with-dot` (false), `rck.supports-variant` (false) [read: OAREADME L72-L80; https://github.com/apache/iceberg/blob/main/open-api/src/test/java/org/apache/iceberg/rest/RESTCompatibilityKitSuite.java]. The RCK subclass also hard-codes `supportsNamesWithSlashes()=false` and `supportsUnregister()=true` [read: https://github.com/apache/iceberg/blob/main/open-api/src/test/java/org/apache/iceberg/rest/RESTCompatibilityKitCatalogTests.java].
- Suite = `RESTCompatibilityKitCatalogTests` + `RESTCompatibilityKitViewCatalogTests` [read: RESTCompatibilityKitSuite.java]; the shared `CatalogTests` file contains 102 `@Test` and 4 `@ParameterizedTest` methods [read: grep count of https://github.com/apache/iceberg/blob/main/core/src/test/java/org/apache/iceberg/catalog/CatalogTests.java].
- **Fixture**: `RESTCatalogServer` wraps any Java catalog (default SQLite `JdbcCatalog` in WAL mode) behind the REST adapter, configured by the same `CATALOG_*` env vars; image `apache/iceberg-rest-fixture`, published for release tags (e.g. `apache-iceberg-1.7.1` publishes `:1.7.1`) and nightly at 02:00 UTC [read: https://github.com/apache/iceberg/blob/main/docker/iceberg-rest-fixture/README.md; https://github.com/apache/iceberg/blob/main/.github/workflows/publish-iceberg-rest-fixture-docker.yml L22-L64].
- The fixture advertises every route in its `Route` enum (namespaces, tables, register/unregister, metrics, transactions, views, register-view, scan planning, tokens) and overrides `namespace-separator` [read: https://github.com/apache/iceberg/blob/main/core/src/test/java/org/apache/iceberg/rest/RESTCatalogAdapter.java L170-L183; …/rest/Route.java L53-L145]. It has **no** route for `loadCredentials` (open PR #18312 "add loadCredentials API endpoint route to rest-fixture"), signing, or functions [read: Route.java; open PR list].
- Pending RCK work: "[4/N] Add RCK tests for ReadRestrictions" (#17809, open), "emit labels from the REST server test fixture" (#18047, open) [read: open PR list via `gh`].
- The fixture's idempotency store is in-memory with TTL and in-flight coalescing: concurrent requests with the same key wait for the first to finish and replay its result [read: JREST/CatalogHandlers.java L120-L200].

### Inferences
- Run the RCK in CI against the server from the first commit; set `rck.supports-serverside-retry` to match your design. Treat a pass as necessary but not sufficient: it exercises only the Java client paths, so add an engine matrix (Spark, Trino, PyIceberg, DuckDB, Snowflake if in scope) for header, auth, credential-vending, pagination, and error-type behaviour [inference].
- The fixture is the de facto reference implementation, so diffing your server's responses against the fixture for the same requests is a cheap conformance oracle [inference].

### Gaps
- I did not find an official PyIceberg-, Rust-, or Go-based conformance suite; each client tests against the fixture in its own CI, which I did not inspect.

---

## 6. Client reality: what Spark, Flink, Trino, PyIceberg, iceberg-rust, iceberg-go, DuckDB, Snowflake, StarRocks, and Dremio actually use

### Takeaway
Every client uses the core: config, namespace CRUD, list/load/create/commit/drop/rename tables, and register. Beyond that they diverge. Java-based engines get ETag caching, idempotency keys, REFS loading, metrics, and server-side planning when advertised. PyIceberg and DuckDB push vended credentials by default. DuckDB is the only engine found that uses `/transactions/commit`, and it fails closed on read-restrictions. No mainstream client enforces read-restrictions yet, and none except Java (and iceberg-go for planning calls) sends Idempotency-Key.

### Cited Findings

**Default endpoint sets when the server sends no `endpoints` list**
| Client | Default set | Source |
|---|---|---|
| Java (Spark, Flink, Trino, others built on Iceberg Java) | 14 incl. metrics and transactions/commit; views only with `view-endpoints-supported=true` | [read: JREST/RESTSessionCatalog.java#L125-L155] |
| PyIceberg | 12; **excludes** metrics and transactions/commit; views only with `view-endpoints-supported` | [read: PY L225-L262] |
| iceberg-rust | 14 incl. metrics and transactions/commit | [read: RS/endpoint.rs L181-L201] |
| iceberg-go | 14 incl. metrics and transactions/commit; HEAD excluded "on purpose: their absence is what makes Check*Exists fall back to GET" | [read: GO/endpoints.go L129-L160, L197-L203] |

**Feature matrix** (read from source or docs; blank = not found in what I read)
| Feature | Java 1.12 | PyIceberg main | iceberg-rust main | iceberg-go main | DuckDB main | Trino 484 | Snowflake |
|---|---|---|---|---|---|---|---|
| Views | yes (if advertised) | yes | not found in catalog.rs | yes | list/load/create/drop | `view-endpoints-enabled` default **true** | |
| HEAD exists | yes, falls back to GET | yes if advertised | yes if advertised | yes, falls back to GET | | via Java | |
| transactions/commit | API only; "Engines commit tables individually today" | | | `CommitTransaction` | **used** when advertised; multi-table SQL transactions fail without it | via Java | |
| stage-create | CTAS/RTAS transactions | | | | option `stage_create_tables` | via Java | |
| purgeRequested | `purgeTable` sends it; Spark needs `rest-catalog-purge` (1.12) | yes | yes | yes | option `purge_requested` | via Java | |
| Pagination | sends `pageToken`; `pageSize` if configured | yes | yes | yes | sends `pageToken` | via Java | |
| ETag / 304 | yes (cache) | | yes | yes | | via Java | |
| snapshots=refs | `snapshot-loading-mode` | yes | | | | `snapshot-loading-mode` | |
| Idempotency-Key | yes when advertised | not found | not found | plan/tasks requests | not found | via Java | |
| Access-delegation header | only if configured (`header.` prefix) | `vended-credentials` by default | not found | on planning calls | `vended-credentials` incl. on commits | `vended-credentials-enabled` (default false) | `ACCESS_DELEGATION_MODE = VENDED_CREDENTIALS` (default external volume) |
| loadCredentials refresh | S3/GCS/ADLS FileIO refresh | `_load_credentials` | | `RefreshTableCredentials` | yes | Trino vended-credential providers | |
| Server-side scan planning | yes (1.11+) | yes (`scan_planning.py`) | | yes | yes (`iceberg_scan_planning.cpp`) | | |
| Remote signing | yes | | | signer interface | | | |
| Metrics reporting | yes if advertised (default on) | | in default set | yes if advertised | `commit_report` objects | `metrics-reporting-enabled` default true | |
| Read-restrictions enforcement | **no** (PRs [1/N]-[4/N] open) | not found | not found | not found | **rejects any non-empty restriction** | no (Java) | |
| Functions (UDF) | | | | yes | | | |

Sources for the matrix: Java [read: JREST/RESTSessionCatalog.java L324-L760; PROTO L81-L86; RELNOTES (#15614 `rest-catalog-purge`, #14963 remote scan planning, #13400)]; PyIceberg [read: PY L154-L262, L1210, L1404-L1490, L1745-L1812; file list incl. `scan_planning.py`]; iceberg-rust [read: RS/catalog.rs L290-L310, L787-L880, L1040-L1060, L1385-L1410]; iceberg-go [read: GO/endpoints.go; GO/rest.go method list; GO/scan_planning.go L147-L150, L600-L618; GO/vended_creds.go]; DuckDB [read: DUCK/src/catalog/rest/api/catalog_api.cpp L150-L800; DUCK/src/catalog/rest/transaction/iceberg_transaction.cpp L392-L405; DUCK/src/iceberg_attach_options.cpp; DUCK/test/python/test_read_restrictions.py]; Trino [read: TRINO metastores.md L467-L589]; Snowflake [read: https://docs.snowflake.com/en/sql-reference/sql/create-catalog-integration-rest]; read-restriction PRs #16198, #16131, #16082, #17809 all open [read: `gh` search].

**What fails when a feature is missing**
- Java: any operation whose endpoint is not advertised throws `UnsupportedOperationException` (load throws `NoSuchTableException`; listTables returns empty). If a table's config says `scan-planning-mode=server` but `planTableScan` is not advertised, loading the table fails with "Server requires server-side scan planning … but does not support endpoint" [read: JREST/RESTSessionCatalog.java#L657-L705].
- PyIceberg: `NotImplementedError("Server does not support endpoint: …")` [read: PY L976-L986].
- DuckDB: a transaction touching more than one table throws "cannot commit this transaction atomically … without atomic multi-table commit support" unless `/transactions/commit` is advertised [read: DUCK iceberg_transaction.cpp L392-L405]. Any table load with a non-empty `read-restrictions` object is rejected [read: DUCK test_read_restrictions.py L9-L33].
- Spark `DROP TABLE … PURGE` against REST deletes files client-side and sends `purgeRequested=false`, which "breaks REST catalogs that require `purgeRequested=true` (e.g. AWS S3 Tables)"; 1.12.0 adds an opt-in property to delegate purge (#15614). The PR title spells the property `rest.catalog-purge`, while the 1.12.0 release notes say `rest-catalog-purge` [read: https://github.com/apache/iceberg/pull/15614; RELNOTES]. The spelling conflict is unresolved in what I read.

**Commercial / other engines**
- **Snowflake catalog-linked databases**: "Snowflake automatically syncs with the external catalog to detect namespaces and Iceberg tables" by polling every `SYNC_INTERVAL_SECONDS` (default 30 s); also supports create/drop schema and table; the "remote Iceberg catalog must support credential vending" if using vended credentials, otherwise an external volume is required; "Snowflake doesn't sync remote catalog access control for users or roles"; nested namespaces only "when your catalog integration uses a catalog that supports nested namespaces" (otherwise flatten with `NAMESPACE_FLATTEN_DELIMITER`); a remote rename appears as drop plus create; `ALLOWED_NAMESPACES` / `BLOCKED_NAMESPACES` limit discovery [read: https://docs.snowflake.com/en/user-guide/tables-iceberg-catalog-linked-database].
- **StarRocks**: properties `iceberg.catalog.security` (OAUTH2 or JWT pass-through), `oauth2.*`, `vended-credentials-enabled`, `rest.nested-namespace-enabled`, `rest.view-endpoints-enabled` [read: StarRocks iceberg.md L268-L320]. Their property names mirror the Java client's, which suggests it uses Iceberg Java [inference].
- **Dremio**: "Use vended credentials" is on by default and falls back to manually supplied S3/Azure credentials; "Allowed Namespaces" with optional subtree inclusion (nested namespaces) [read: https://docs.dremio.com/current/data-sources/lakehouse-catalogs/iceberg-rest-catalog/].
- **Trino** extras: `case-insensitive-name-matching` (relies on listing tables/views per namespace and caching), `nested-namespace-enabled` (default false), `server-assigned-table-location-enabled` [read: TRINO metastores.md L538-L585]. An open Iceberg PR adds recursive namespace listing because "SHOW SCHEMAS with REST catalog in the Trino Iceberg connector currently gets namespaces recursively … It can be time-consuming" (#16199) [read: PR body].
- Client identification today: Java sends `X-Client-Version` and no `User-Agent` by default; PyIceberg sends `User-Agent: PyIceberg/<ver>` [read: JREST/HTTPClient.java L80-L83; PY L1208-L1209]. Open PR #17727 proposes a standard User-Agent and tabulates rust and go headers [read: PR body].

### Inferences
- **(a) The practical "must implement" set for these engines** is: config with an explicit `endpoints` list; namespaces (list, create, load, drop, properties, HEAD); tables (list, create incl. stage-create, load, commit, drop incl. purge semantics, rename, register, HEAD); views if any engine will create views (Trino enables view endpoints by default); `loadCredentials` if vending; pagination; and the full error contract [inference from the matrix].
- **Snowflake's 30-second polling** across thousands of tables means repeated list and load traffic per linked database; ETags and cheap list endpoints are a capacity requirement, not an optimization [inference from the Snowflake doc plus SPEC ETag text].
- **Read-restrictions cannot be a security control today.** Old clients ignore unknown response fields, the spec has no way for a client to declare it enforces restrictions, and only DuckDB (fail-closed) handles them [inference from SPEC#L1056-L1063, #L3858-L3871, and the open Java PRs].
- DuckDB is the only client found that exercises `/transactions/commit`; Java exposes it as an API. Implementing it is optional, but advertising it without true atomicity would be a correctness bug [inference].

### Gaps
- I did not read Spark- or Flink-specific REST code; their behaviour is inferred from the shared Java client plus RESTDOC.
- Snowflake's exact REST call pattern (ETag use, page sizes, whether it sends `snapshots=refs`) is not documented in the pages read.
- Dremio's client library and whether it uses ETags, idempotency, or multi-table commits were not determined.
- iceberg-rust view support may live outside `catalog.rs`; I found none there.

---

## 7. Spec evolution process, and what is pending or merged in 2025-2026

### Takeaway
Spec changes are governed by ASF votes on dev@: any change under `open-api/rest-catalog*` needs its own vote, and the REST spec has gained a voted feature roughly every 2-4 weeks through 2025-2026 (idempotency, credentials-for-plans, ETag on commit, register-view, signing, functions, unregister, labels, read-restrictions). The pipeline is now dominated by security/governance features (KMS vending, presigning, batch signing), batch and event APIs (resolve, events), indexes, and the unresolved question of how REST will carry format-version-4 tables (in-place relaxation versus a `/v2` endpoint).

### Cited Findings
**Process**
- "Changes to files under the `format` directory and `open-api/rest-catalog*` are considered specification changes. Unless already covered under an Iceberg improvement proposal, specification changes require their own vote … The vote follows the ASF code modification model and no lazy consensus modifier." Major changes go through an improvement proposal (GitHub issue, design doc, `[DISCUSS]` thread, then a vote needing three positive PMC votes) [read: CONTRIB L57-L58, L71-L101].

**Votes on dev@ (subjects, last 365 days, REST-related)** [read: DEVVOTES]
2025-11-13 planId on `/credentials`; 2025-11-14 REST Catalog Idempotency Spec; 2025-11-17 storage credentials in scan-planning response; 2025-12-03 idempotency key for Plan API mutations; 2025-12-05 ETag on CommitTableResponse; 2026-01-05 AccessDelegation header on plan APIs; 2026-01-14 register views; 2026-01-20 Adopt the SQL UDF spec; 2026-02-23 and 2026-03-02 promote remote signing endpoint (second attempt); 2026-03-09 scan planning mode in loadTable; 2026-03-24 identifier-conflict wording for tables and views; 2026-03-26 list/load function endpoints; 2026-04-06 404 for config endpoint; 2026-05-12 CatalogObjectIdentifier; 2026-05-20 unregister endpoint (result 2026-05-26); 2026-07-20 formalize remote signing config (result 07-30); 2026-07-22 labels on the read path (result 09-08); 2026-07-24 align REST expressions with the expression spec (result 07-27); 2026-07-30 variant type (result 08-02); 2026-08-31 finer-grained read restrictions (result 09-03).

**Merged to the spec in 2025-2026** (66 commits; selected) [read: SPECLOG]: OpenAPI 3.1 (2025-01-14); freshness-aware loading/ETag (2025-01-28); remove HEAD from defaults (2025-02-21); register overwrite (2025-02-17); remove-schemas (2025-02-14); encryption keys (2025-05-12); row lineage fields (2025-05-27); 503 non-retryable for update (2025-08-21); format version 3 (2025-08-29); Idempotency-Key (2025-11-20); plan storage credentials (2025-11-20); configurable namespace separator (2025-12-08); ETag on commit (2025-12-19); register view (2026-01-17); referenced-by (2026-02-12); scan-planning-mode (2026-03-13); signing endpoint promoted (2026-03-31); config 404 (2026-04-15); REFS mode clarified (2026-05-08); unregister (2026-05-26); functions (2026-06-07); remote signing config (2026-07-30); expressions aligned (2026-07-27); variant type (2026-08-02); read restrictions (2026-09-03); labels (2026-09-09); `none` access delegation (2026-10-29).

**Open as of 2026-11-06** (label OPENAPI or REST in title; creation date, last update) [read: `gh` PR search and PR bodies]
| PR | Proposal | Status notes |
|---|---|---|
| #12584 | **Events endpoint** (catalog change feed) | open since 2025-03-20; author says "refactored on main - ready for another round" (2026-05-20); last update 2026-10-28 |
| #15830 | **Resolve endpoint** `POST /v1/{prefix}/resolve`: batch load of many catalog objects with per-item `loaded` / `not-modified` / `not-found` | open since 2026-03-30 |
| #16199 | Recursive namespace listing | open; motivated by Trino and DuckDB |
| #16963 | Index endpoints and key-lookup index | open |
| #17155 | **KMS credential vending** for table encryption (`kms-vended-credentials` delegation mode, `key-management-credentials`) | open |
| #17642 | **Format v4 in REST by relaxing responses in place** (`root-manifest`, optional `metadata-location`, `table-location`) | open; alternative #17543 (new `/v2` endpoints and `*V2` schemas) was closed |
| #18782 | Java client loads tables via a **v2 load endpoint** when advertised (only v2 returns v4 tables) | open, 2026-11-04; says it is "outlined in" #17642, which is the in-place alternative, so the direction is unsettled |
| #17727 | Standard `User-Agent` format | open |
| #17800 | Snapshot encryption key ID | open |
| #18080 | `remote-presigning` access-delegation value; `data-access` on `signRequest` | open |
| #18254 | **Batch signing endpoint** | open |
| #17299 | Data sequence number in scan tasks | open |
| #17837 / #18428 | Replace `nullable` with OpenAPI 3.1 type arrays | open |
| #16198, #16131, #16082, #17809 | Read-restriction client implementation (API, RESTTable, Spark 4.1, RCK tests) | all open |
| #17912, #18188, #18783 | Shared credential refresh endpoints; freshness-aware RESTTableOperations; `table-location` in responses | open (Java client) |

**Requested topics with no matching spec PR found**: catalog federation, "server-side commit push-down" (beyond the existing server-owned commit and the RCK `supports-serverside-retry` flag), and a REST spec version bump other than the v4 discussion [read: absence in the OPENAPI-labelled open PR list and SPECLOG].

### Inferences
- **Who drives the pipeline**: author affiliations are not shown in what I read, but the themes (FGAC, labels, KMS vending, presigning and batch signing, scan planning, events, resolve) are what multi-tenant catalog vendors and engines need to sit in the data path or federate. Several also have real enterprise value (labels for classification, KMS vending for encryption, events for audit and cache invalidation) [inference].
- **REST spec "versioning" is endpoint-level**: new capabilities arrive as optional endpoints or optional fields with discovery via `endpoints` or config keys. The first potentially breaking change is format v4 (relative paths, optional `metadata-location`), and its shape is unresolved [inference from #17543, #17642, #18782].
- Expect at least 1-2 new voted REST features per Iceberg minor release; an implementation should plan for continuous tracking of `main` [inference from the vote cadence].

### Gaps
- I did not read the design docs linked from #12584, #15830, #17155, #18254 (Google Docs / s.apache.org) or the dev@ discussion threads, only PR bodies and vote subjects.
- I found no Polaris- or vendor-specific "catalog federation" proposal in the Iceberg spec repo; I did not search the Polaris project.

---

## Decisions this implies

Each decision lists options, evidence, and a recommendation. Category tags: (a) spec/client-required, (b) enterprise operator need, (c) mainly vendor-commercial.

1. **Server language and how commit semantics are implemented.**
   - Options: (i) Java server that reuses `iceberg-core` (`UpdateRequirement.validate`, `MetadataUpdate`, `TableMetadata` builder, the `CatalogHandlers` patterns) with MongoDB as the pointer and object store; (ii) reimplement metadata semantics in another language.
   - Evidence: the server must reject unknown update and requirement types with 400 and must apply 25 update types identically to the clients (section 2); the reference server does exactly this with iceberg-core [read: JREST/CatalogHandlers.java#L604-L640].
   - Recommendation: (i). Your Java lean is justified here specifically, because it buys semantic parity for the riskiest part of the server. Pin the iceberg-core version and upgrade with the spec. MongoDB fits as the store for a compare-and-swap of `metadata-location` (conditional update on the expected current location or version) [inference].

2. **v1 endpoint set (always advertise an explicit `endpoints` list).**
   - v1 (a): config; namespaces list/create/load/HEAD/drop/properties; tables list/create (incl. stage-create)/load/HEAD/commit/drop (incl. `purgeRequested`)/rename/register; views CRUD and rename if any engine creates views (Trino enables them by default); pagination; full error contract.
   - v1 (b): `loadCredentials` plus vended credentials (if storage supports STS-style scoped credentials); ETag and 304; `snapshots=refs`; metrics endpoint (accept, enqueue, return 204 fast) because it is an audit and usage feed.
   - v1.x (b): `transactions/commit` (needs real multi-document atomicity; DuckDB uses it; advertise only when atomic); Idempotency-Key with a durable, replicated store; `unregisterTable` and `register-view` (migration and DR); labels (classification and ownership, cheap).
   - Later or on demand (c-leaning, but with real value in some cases): server-side scan planning (large, stateful, and only useful if the catalog must own planning or enforce FGAC); remote signing (only if policy forbids credential egress); functions/UDFs; read-restrictions (wait for client enforcement, section 6).
   - Never: `/v1/oauth/tokens` (deprecated, "not recommended") and the old `/v1/aws/s3/sign`.

3. **Commit outcome contract.**
   - Options: return 409 on any store error (simple) versus a strict tri-state.
   - Evidence: 409 drives client cleanup and retry, 5xx means unknown (section 2).
   - Recommendation: 409 only when requirements failed against a consistent read, or when the compare-and-swap definitively lost. Any timeout or ambiguous store result returns 500 (or 503/504 at the edge). Make load balancers, gateways, and request timeouts consistent with this.

4. **Idempotency.**
   - Options: do not advertise; advertise with an in-memory store; advertise with a durable, replicated store.
   - Evidence: Java 1.12 retries keyed POSTs on 500/502/503/504 until the advertised lifetime; the reference store is in-memory and explicitly not for production.
   - Recommendation: advertise `idempotency-key-lifetime` only once keys are stored durably with the response, are visible to every node and region that can receive the retry, and are written in the same transaction as the commit. Until then, do not advertise.

5. **Authentication.**
   - Options: catalog-issued tokens (legacy endpoint); enterprise IdP JWT bearer; mTLS; SigV4 (AWS only).
   - Recommendation: enterprise IdP with OAuth2 client credentials for service identities, JWT validation in the catalog, and a documented client configuration per engine that always sets `oauth2-server-uri` (or `OAUTH_TOKEN_URI` for Snowflake) and, for the Java legacy manager, `token-exchange-enabled=false` unless the IdP supports RFC 8693. Decide separately whether end-user identity must reach the catalog (Trino `session=USER`, StarRocks `JWT`).

6. **Access delegation.**
   - Options: engine-owned storage credentials; vended credentials; remote signing.
   - Recommendation: vended credentials, scoped per table prefix, one S3 credential per table, including `s3.session-token-expires-at-ms`, and `client.refresh-credentials-endpoint` in table config. Honour `none` and the absence of the header (engines with their own access). Treat remote signing as a later option for highly restricted buckets.

7. **Fine-grained access control.**
   - Options: enforce in engines (existing engine policy tools); return `read-restrictions`; force server-side planning.
   - Evidence: spec support since 2026-09-03, no Java/Spark/Trino/PyIceberg enforcement yet, no capability negotiation, DuckDB rejects such tables.
   - Recommendation: do not rely on catalog-returned read-restrictions in v1. Keep table-level authorization in the catalog (403 on load) and column/row policy in engines. Revisit when the [1/N]-[4/N] Java PRs ship and if the spec adds a capability signal.

8. **Conformance gate.**
   - Recommendation: run the RCK in CI from day one (match `rck.*` flags to your design), plus a nightly multi-engine matrix (Spark, Trino, PyIceberg, DuckDB, and Snowflake if in scope) that covers auth, vending, pagination at thousands of tables, ETag, purge, and view behaviour. Use the fixture image as a reference for diffing responses.

9. **Spec tracking and versioning.**
   - Recommendation: target SPEC-1.12 plus the `none` header value from main. Track dev@ `[VOTE]` threads. Design the storage model so `metadata-location` can later be absent and paths relative (format v4), without committing to `/v2` routes yet.

10. **Purge and drop semantics (regulated).**
    - Options: honour `purgeRequested=true` with immediate deletion; soft-delete with retention and a later purge; reject purge.
    - Evidence: Spark defaults to client-side purge and sends `purgeRequested=false`; 1.12 adds an opt-in to delegate it.
    - Recommendation: implement drop as a soft-delete with retention and an audited purge job, and decide whether client-side purges should be blocked by storage permissions (vended credentials without delete on table roots).

---

## Open questions for the enterprise

1. Which engines and exact versions are in scope (Spark, Flink, Trino, Snowflake catalog-linked databases, DuckDB, PyIceberg, Dremio, StarRocks)? The Java client version decides whether idempotency keys, ETags, and REFS loading are used at all.
2. What object storage sits underneath (AWS S3, on-prem S3-compatible, ADLS, GCS)? Does it offer STS-style scoped, short-lived credentials? This decides whether vended credentials are possible or remote signing is needed.
3. Which IdP issues tokens, does it support OAuth2 client credentials and RFC 8693 token exchange, and what claims identify a human versus a service?
4. Must end-user identity reach the catalog for authorization and audit, or is engine-level (service) identity acceptable?
5. Where must column and row-level controls be enforced (engine policy tools versus catalog), and does any regulation require the catalog itself to enforce them?
6. What are the multi-region requirements: active-active or active-passive, RPO and RTO for the catalog pointer, and commit latency targets? Can MongoDB provide a single linearizable compare-and-swap per table across regions?
7. Peak commit rate per table (e.g. Flink checkpoints) and total load rate (e.g. Snowflake polling every 30 s across thousands of tables)?
8. Are atomic multi-table commits a business requirement (for example, publishing related tables together)?
9. What retention applies to catalog history, dropped tables, and metadata files (books-and-records obligations), and must drops be reversible?
10. Is Snowflake (an external SaaS) allowed to receive vended credentials, and over what network path (public, PrivateLink)?
11. Will views be shared across engines with different SQL dialects, and who owns view definitions?
12. Is a catalog change feed (the pending events API) needed for audit or cache invalidation, and is a non-standard interim API acceptable until the spec settles?
13. Which namespace model will be used (nested versus flat)? Trino and Snowflake need explicit nested-namespace settings.

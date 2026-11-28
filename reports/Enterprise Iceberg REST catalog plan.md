# Build a thin catalog with thick controls

The firm can build an enterprise Iceberg REST catalog from scratch, and the protocol is the small part of the job. The server has to do four things well. It advertises an explicit endpoint list. It applies every metadata-update type through iceberg-core's own validation code. It swaps one pointer per table. And it keeps a strict rule about which commit failures return 409 and which return 5xx. Every serious implementation has converged on this, and the work is bounded. Most of the effort goes into enterprise layers that no open-source catalog has finished for a regulated firm:

- an audit trail that records denials and survives failures (Polaris says its own events "are not a complete audit trail");
- retention holds enforced at commit time;
- the ability to list everything one principal can access, for access reviews;
- credential vending scoped per table, with location guardrails;
- a multi-region disaster-recovery design that handles a table pointer replicating ahead of its files.

The Java lean is well supported. Reusing iceberg-core's metadata builder gives the server the same behaviour as every client on the riskiest code path. The MongoDB lean is defensible but not favoured. A three-site replica set with majority writes and one pointer document per table is a correct commit store that loses no acknowledged commits. But both public MongoDB Iceberg catalogs (Nessie, and Polaris's beta backend) use the catalog-wide pointer this report rejects. And keeping the commit, its audit record, and grant changes consistent is an ordinary transaction on CockroachDB or PostgreSQL, but needs workarounds on MongoDB. The database choice should therefore follow the firm's disaster-recovery standard and which database its DBAs already run across three sites.

The claim that open-source catalogs are bloated by vendor needs holds for Polaris's periphery: multi-tenant realms, a built-in token issuer, a NoSQL storage stack that is about a quarter of its server code, federation, and non-Iceberg tables. It fails for Polaris's security core. It also misses the other direction: vendor economics remove features too. Lakekeeper sells table maintenance separately, and hosted endpoints block views, create-table-as-select, or external maintenance. Of the 34 decisions logged below, 26 can proceed now. The other 8 wait on the firm's answers, of which the most consequential are:

- which tables count as required records under SEC Rule 17a-4;
- how much committed catalog state may be lost on failover (RPO), and how fast service must recover (RTO);
- which database platform the DBAs operate;
- which identity provider issues tokens;
- which object stores are in use.

*Provenance: this report synthesises eight research notes compiled on 2026-11-06. Where a claim was read only through a tool's summary, or only from a secondary source, the report says so. Researchers' own reasoning is labelled as inference. Nothing here is legal advice.*

## Clients, not the spec text, fix what the server must do

The REST specification marks almost nothing as mandatory. Support is negotiated: a client calls `GET /v1/config`, and the server lists the endpoints it supports. If the server omits that list, the Java client assumes **a fixed set of 14 endpoints, including `POST /transactions/commit` (atomic commits across several tables) and the metrics endpoint**. A code comment says these defaults "must not be updated in order to maintain backwards compatibility" ([RESTSessionCatalog.java](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/RESTSessionCatalog.java)). PyIceberg assumes 12 endpoints and leaves out those two ([PyIceberg REST client](https://github.com/apache/iceberg-python/blob/main/pyiceberg/catalog/rest/__init__.py)). A server that omits the list has therefore promised multi-table atomicity to Java, Rust and Go clients without meaning to. When an endpoint is missing, clients disable the feature rather than fail the request ([rest-protocol.md](https://github.com/apache/iceberg/blob/main/docs/docs/rest-protocol.md)). That makes optional endpoints genuinely optional, as long as the server always sends an explicit list.

Where the spec is strict, it is very strict. "Server implementations are required to fail with a 400 status code if any unknown updates or requirements are received" ([REST spec](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)). Because of that rule, the server must implement **all 23 table-update types (25 actions counting view updates) and all 8 table requirements plus 1 view requirement**. Routine maintenance jobs send `remove-snapshots`, `remove-schemas` and `set-statistics`, so what looks like bloat is mandatory.

The server must also compute some fields itself on every commit. These include `last-updated-ms`, the snapshot and metadata logs, `last-sequence-number`, the last-assigned column and partition IDs, and in format v3, `next-row-id`. The spec says `next-row-id` "must also be updated (even if the new snapshot is not in the main branch)" ([table spec](https://github.com/apache/iceberg/blob/main/format/spec.md)). The client, not the server, assigns each snapshot's `first-row-id`, because the value is already baked into the manifest list the client wrote. The server can only accept it or send the commit back. The researcher's inference is that mishandling this silently produces overlapping row IDs, which breaks the change-data-capture and audit lineage a financial firm would want row lineage for. Java's `TableMetadata.Builder` already rejects stale sequence numbers and a `first-row-id` behind `next-row-id` ([TableMetadata.java](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/TableMetadata.java)). That is the strongest single argument for a Java server.

Which status code a failed commit returns is a safety property, not a formality:

- **409 means "definitely not applied."** The client refreshes, re-applies its changes, retries, and deletes the files written for abandoned attempts.
- **500, 502 and 504 mean "state unknown."** Java's `SnapshotProducer` rethrows `CommitStateUnknownException` without retrying and skips the cleanup path ([SnapshotProducer.java](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/SnapshotProducer.java)).

A server that answers 409 for a write that actually landed lets the client delete files the table now references (researcher inference). Clients also disagree about 503, which decides which code the server should use when it cannot tell whether a commit landed:

| Commit-route status | Java 1.12 | PyIceberg | iceberg-rust | iceberg-go |
|---|---|---|---|---|
| 500 | unknown | unknown | unknown | unknown |
| 502 / 504 | unknown | unknown | unknown | unknown |
| 503 | unknown | **not** unknown | **not** unknown | unknown |

Sources: [ErrorHandlers.java](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/ErrorHandlers.java), [PyIceberg](https://github.com/apache/iceberg-python/blob/main/pyiceberg/catalog/rest/__init__.py), [iceberg-rust catalog.rs](https://github.com/apache/iceberg-rust/blob/main/crates/catalog/rest/src/catalog.rs), [iceberg-go rest.go](https://github.com/apache/iceberg-go/blob/main/catalog/rest/rest.go).

**Only 500 (and the gateway codes 502 and 504) mean "unknown" to every client.** A server that cannot tell whether a commit landed must return 500, never 503.

Iceberg 1.12 adds a safety net. **The Java client now retries POST requests that carry an `Idempotency-Key` header on 408, 500, 502, 503 and 504** ([release notes](https://github.com/apache/iceberg/blob/main/site/docs/releases.md)). It attaches a key only if the server advertises `idempotency-key-lifetime` ([RESTSessionCatalog.java](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/RESTSessionCatalog.java)). The reference implementation keeps keys in memory, and its own comment says "Production servers should provide a durable store" ([CatalogHandlers.java](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/CatalogHandlers.java)). With a durable store, a retry gets back the original success response, so an "unknown" outcome becomes a known one.

Authentication is equally narrow. The spec defines only OAuth2 and bearer tokens. It marks the catalog-hosted `/v1/oauth/tokens` endpoint "DEPRECATED for REMOVAL… _not_ recommended to implement" ([REST spec](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)), because it "pushes operators of Iceberg REST endpoints into the authorization service business" ([issue #10537](https://github.com/apache/iceberg/issues/10537)). The catch is that clients still default to it. Java's `oauth2-server-uri` defaults to the catalog's own token path ([rest-catalog.md](https://github.com/apache/iceberg/blob/main/docs/docs/rest-catalog.md)). Snowflake assumes the catalog is the identity provider unless `OAUTH_TOKEN_URI` is set ([Snowflake catalog integration](https://docs.snowflake.com/en/sql-reference/sql/create-catalog-integration-rest)). Correct per-engine client configuration is therefore part of the deliverable, not an afterthought.

Several optional features are de facto requirements at the firm's scale.

- **ETags with 304 Not Modified, and `snapshots=refs`.** ClickHouse measured about **535 bytes per snapshot** in a loadTable response. At 10,000 snapshots that means 5.3–6.0 MB and 356–512 ms per load. With `snapshots=refs`, the response falls to 10–12 KB ([ClickHouse #128348](https://github.com/ClickHouse/ClickHouse/issues/128348)). The same test found that Polaris 1.8.0 and the reference fixture return HTTP 400 for `refs` when statistics files sit on unreferenced snapshots, and Trino writes such a file on every insert.
- **Cheap listings and 304s.** Snowflake catalog-linked databases poll the remote catalog every 30 seconds by default ([Snowflake docs](https://docs.snowflake.com/en/user-guide/tables-iceberg-catalog-linked-database)), which makes cheap lists and 304s a capacity requirement (inference).
- **Views.** Trino turns view endpoints on by default ([Trino metastores docs](https://github.com/trinodb/trino/blob/master/docs/src/main/sphinx/object-storage/metastores.md)).

By contrast, the new `read-restrictions` field (catalog-supplied row filters and column masks) cannot be a security control yet. It was merged into the spec on 2026-09-03, but **no released Java client parses it**. The implementing pull requests target 1.13.0 ([PR #16198](https://github.com/apache/iceberg/pull/16198)). DuckDB rejects any table with a non-empty restriction ([DuckDB test](https://github.com/duckdb/duckdb-iceberg/blob/main/test/python/test_read_restrictions.py)).

Finally, the target keeps moving.

- **A conformance kit exists.** The REST Compatibility Kit runs Iceberg's Java catalog tests (102 tests plus 4 parameterized) against any server. It does not cover credential vending, request signing, or behaviour across different clients ([open-api README](https://github.com/apache/iceberg/blob/main/open-api/README.md)).
- **The spec grows steadily.** The spec file grew from 4,891 lines at 1.9.0 to 6,420 on main in about 18 months, almost entirely through optional additions. The dev@ list voted through a new REST feature roughly every two to four weeks ([dev@ vote index](https://lists.apache.org/api/stats.lua?list=dev&domain=iceberg.apache.org&d=lte=365d&q=VOTE)).
- **iceberg-core is not a stable API.** Iceberg calls it "common and internal code" that "can evolve within a major release" ([contribute.md](https://github.com/apache/iceberg/blob/main/site/docs/contribute.md)). Its API-compatibility checker records **22, 88 and (so far) 12 accepted breaking changes against the 1.10, 1.11 and 1.12 baselines** ([revapi.yml](https://github.com/apache/iceberg/blob/main/.palantir/revapi.yml)).
- **Format v4 is not adopted, and its REST shape is unsettled.** A proposal for new `/v2` endpoints was closed. An alternative that relaxes the existing responses in place is still open ([PR #17642](https://github.com/apache/iceberg/pull/17642)). Core committers favour making metadata.json optional, provided catalogs can produce it on demand ([Ryan Blue, 2026-04-16](https://lists.apache.org/thread/5f5p4rcxyq7j6rt7yts68shoqmm75478)). The researchers found no written proposal for this yet.

A from-scratch catalog therefore needs a permanent team function that tracks the spec, not a one-off build.

## Enterprise duties, not endpoints, set the size of the build

Raw scale is not what makes this hard. **Netflix reports more than 3 million Iceberg tables, 600 peak commits per second and 12,000 peak table loads per second** ([Trino Summit 2024 deck](https://trino.io/assets/blog/trino-summit-2024/trino-summit-2024-netflix.pdf)). A discrete-event simulation by Chris Douglas, which the researcher read only through a tool's summary, puts the sustainable ceiling for one table at about 1–2 commits per second. The limit comes from the client's own metadata reads and writes, not from the catalog ([Douglas](https://cdouglas.github.io/posts/2026/03/catalog)).

The persistence researcher estimated about 50 commits per second in aggregate for a mix of streaming and batch tables like the firm's. That is an estimate, not a measurement. The same researcher used ClickHouse's per-snapshot size to estimate metadata growth (inference). A Flink job that commits every 10 seconds, under the default 5-day snapshot expiry ([TableProperties.java](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/TableProperties.java)), keeps about 43,200 snapshots. That gives a metadata.json of roughly 26 MB. None of this strains any candidate database. What does bite is:

- correctness when the database fails over;
- discipline on metadata size;
- implementation defects. Polaris, for example, issues 23 SELECTs to resolve one uncached table path ([dev@ thread](https://lists.apache.org/thread/tl6z48gdblsko3x0b9nt2917wb2fgtor)).

**Storage access is where catalogs earn their keep, and where they fail quietly.** Vended credentials make the catalog the single point that authorises storage access. Short-lived credentials scoped to one table replace long-lived bucket credentials in every engine. Java's refresher has hard requirements. It accepts exactly one `s3` credential, which must carry four keys including `s3.session-token-expires-at-ms`, and it refreshes 5 minutes before expiry ([VendedCredentialsProvider.java](https://github.com/apache/iceberg/blob/main/aws/src/main/java/org/apache/iceberg/aws/s3/VendedCredentialsProvider.java)).

AWS adds hard limits:

- a **2,048-character session-policy limit**;
- **600 STS requests per second per account per region** ([IAM quotas](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_iam-quotas.html));
- a one-hour cap on chained role sessions ([AssumeRole](https://docs.aws.amazon.com/STS/latest/APIReference/API_AssumeRole.html));
- no way to revoke a single session, only all sessions of a role issued before a given time ([session revocation](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_use_revoke-sessions.html)).

Polaris hit the policy limit with deeply nested namespaces ("Packed policy consumes 118% of allotted space") ([#3243](https://github.com/apache/polaris/issues/3243)). On-premises support is patchy:

- MinIO and Ceph support AssumeRole with session policies.
- Pure FlashBlade does not implement AssumeRole at all ([Pure blog](https://blog.purestorage.com/purely-technical/using-apache-polaris-with-flashblade/)).
- StorageGRID 12.0 supports AssumeRole, but its session-policy support is unverified.

Scoping bugs are real. Nessie's GCS vending "failed every credential-vending request" until September 2026, and its S3 and GCS scoping let a credential for `warehouse/orders` reach `warehouse/orders2` ([Nessie changelog](https://github.com/projectnessie/nessie/blob/main/CHANGELOG.md)). Polaris's location guardrails are worth copying ([LocalIcebergCatalog.java](https://github.com/apache/polaris/blob/main/runtime/service/src/main/java/org/apache/polaris/service/catalog/iceberg/LocalIcebergCatalog.java)):

- allowed locations per catalog;
- a database index that detects overlap with sibling tables;
- metadata files required to sit inside the table directory;
- refusal to trust storage settings a caller supplies, such as `s3.endpoint`.

**Authorization must answer the regulator's question, not just the engine's.** NYDFS 500.7 requires an at-least-annual review of "all user access privileges" ([23 NYCRR 500.7](https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.7)). The catalog must therefore be able to list everything a given principal can do. Lakekeeper's OpenFGA engine cannot: it returns `501 GrantListingNotImplemented` for that query ([Lakekeeper OpenFGA docs](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/authorization-openfga.md)).

Row and column controls are a harder limit. The catalog can decide policy, but it cannot enforce it against a client that holds storage credentials. Unity Catalog's response is to refuse credential vending for tables with row filters or column masks ([Databricks credential vending](https://docs.databricks.com/aws/en/external-access/credential-vending)). Ryan Blue's 2024 position on the dev list is the cleanest design rule: the protocol should pass "requirements — the result of policy decisions — and NOT be used to pass policy itself", and only to engines the catalog already trusts ([dev@ thread](https://lists.apache.org/thread/9ff0fkwbtzwrh6m3h6kpfbjmdbfo00pw)).

**Audit and retention are where open-source catalogs fall shortest.** Polaris states that its events "are not a complete audit trail. They do not record authorization denials or authentication failures, and delivery is asynchronous and best-effort" ([Polaris events](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/events.md)). An issue asking for a supported audit trail for regulated deployments is still open ([#5561](https://github.com/apache/polaris/issues/5561)).

SEC Rule 17a-4(f)(2) lets a broker-dealer meet its storage requirement in either of two ways ([17 CFR 240.17a-4](https://www.ecfr.gov/current/title-17/section-240.17a-4)):

- **WORM storage**: non-rewriteable, non-erasable;
- **since 3 May 2023, an audit-trail alternative**: a "complete time-stamped audit trail" that records modifications and deletions, "the identity of the individual" where applicable, and "will permit re-creation of the original record" ([2022 adopting release](https://www.govinfo.gov/content/pkg/FR-2022-11-03/pdf/2022-22670.pdf)).

Iceberg's snapshot history resembles that audit trail only while snapshots are retained. Expiring snapshots, compaction cleanup and orphan-file removal destroy it. Under S3 Object Lock in compliance mode, a simple DELETE returns 200 and adds a delete marker while the locked version remains ([Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html)). Maintenance jobs therefore report success while storage is never reclaimed.

The security researcher found that neither Polaris nor Lakekeeper documents **commit-time enforcement of retention holds**. That feature is the most important one the firm's catalog needs. It is also cheap to build, because removing snapshots happens through commits the catalog already sees.

**Disaster recovery has a hazard that commit stores alone cannot fix.** S3 Replication Time Control replicates "99.9 percent of those objects within 15 minutes" ([S3 RTC](https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication-time-control.html)). A synchronously replicated pointer can therefore reach the DR region before the files it references. The persistence researcher's designs, all inference, handle this in three ways:

- a per-table "DR-safe" pointer, advanced only after a verifier confirms the files have replicated;
- a procedure that rewinds each table to its last fully replicated version at failover;
- a runbook that freezes garbage collection after a failover that lost recent commits. Without the freeze, orphan-file removal would permanently delete files those lost commits still need.

The other-implementations researcher's comparison found no open-source catalog that documents multi-region writes across active regions; Gravitino documents only a read-proxy pattern ([Gravitino overview](https://github.com/apache/gravitino/blob/main/docs/overview.md)).

**Maintenance belongs beside the catalog, not inside it.**

- Polaris stores maintenance policies but does not run them.
- Polaris's in-process purge made "the whole polaris instance… unresponsive" when it dropped a large table ([#3462](https://github.com/apache/polaris/issues/3462)).
- Lakekeeper puts snapshot expiry, compaction and orphan removal in its commercial edition ([Lakekeeper maintenance](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/table-maintenance.md)).
- S3 Tables runs them by default ([S3 Tables maintenance](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-maintenance.html)).
- LinkedIn's OpenHouse triggers a job for each table as soon as a snapshot commits ([OpenHouse](https://engineering.linkedin.com/blog/2023/taking-charge-of-tables--introducing-openhouse-for-big-data-mana)).

The history behind the spec explains why the core should stay thin. Tabular's engineers proposed the REST spec in 2021–22 for three reasons ([Ryan Blue, 2021](https://lists.apache.org/thread/nyh8l6oqylwyd1cfzcpq7b28p4ct088k)):

- to keep thousands of clients from connecting directly to the catalog database;
- to let hosted engines that cannot load custom JARs reach custom catalogs;
- to end lockstep upgrades. When clients write the root metadata, every writer must upgrade at the same time.

Tabular's own guidance was blunt. "The metastore catalog's responsibility is minimal — it just handles the atomic swap — to avoid creating a central metadata bottleneck" ([Tabular cookbook](https://web.archive.org/web/20240601000000/https://tabular.io/apache-iceberg-cookbook/getting-started-catalog-background/)). Engines should get "zero access by default" ([Jason Reid](https://web.archive.org/web/20240601000000/https://tabular.io/blog/securing-the-data-lake-part-1/)). And the server should keep writing metadata.json as "an important backup of table state" ([Ryan Blue, 2022](https://lists.apache.org/thread/j7dh122l944d91vp1q7gfwc4g4y8jgyh)). Tabular exposed audit as queryable Iceberg system tables, including "an audit of all authorization decisions" ([Tabular docs](https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/default-tabular-databases.html)). It could never be self-hosted, however. Databricks agreed to acquire Tabular on 2024-06-04 ([press release](https://www.databricks.com/company/newsroom/press-releases/databricks-agrees-acquire-tabular-company-founded-original-creators)).

Nessie supplies the opposite lessons.

- **A single global pointer does not scale.** Its 2022 post-mortem called the global pointer a "single point of contention" that a rolling upgrade corrupted ([Nessie incident](https://github.com/projectnessie/nessie/blob/main/site/docs/blog/posts/2022-05-incident.md)).
- **User-facing branches carry a growing cost.** Merging about 45 content keys (tables and views) into a long-lived branch that has accumulated thousands of merge commits takes more than 280 seconds, even as a no-op dry run ([#12843](https://github.com/projectnessie/nessie/issues/12843)).

Put together, building from scratch means eight workstreams plus permanent spec tracking:

1. A commit engine, built on iceberg-core's reusable classes.
2. A persistence layer.
3. Authentication and authorization, including enumeration for access reviews.
4. Credential vending with location guardrails.
5. A durable audit and change-event pipeline.
6. A retention and legal-hold layer.
7. A DR toolkit with runbooks.
8. A separate maintenance worker tier (v1.x).
9. Plus a permanent conformance-and-upgrade function that tracks the spec.

The notes give no effort estimate, and this report does not invent one. For scale only: the researcher's local line count of Polaris at commit [6c268f6](https://github.com/apache/polaris/tree/6c268f62252a7d074190215cb693cb81cd00d3b5) puts its entire Iceberg handler package at about 13,000 non-blank lines, already including generic-table, policy and federation handlers. The whole server is about 131,500 lines. The protocol core is the small part.

Adopting Polaris instead would not remove workstreams 5, 6 or 7, because Polaris has not built them either (inference).

## Java earns its place; MongoDB passes with conditions

**Java is the right language on the evidence, not just by preference.** Iceberg's Java metadata builder and update-application classes are the reference implementation. Every client and the reference server use the same code for each update type and requirement. Polaris and Gravitino reuse these classes. Polaris copied Iceberg's request-handling class at 1.8.0 rather than track it, and the copied file is headed "CODE_COPIED_TO_POLARIS" ([CatalogHandlerUtils.java](https://github.com/apache/polaris/blob/main/runtime/service/src/main/java/org/apache/polaris/service/catalog/iceberg/CatalogHandlerUtils.java)). Nessie reimplemented its own model, and Lakekeeper proves Rust is viable on top of iceberg-rust ([commit_tables.rs](https://github.com/lakekeeper/lakekeeper/blob/main/crates/lakekeeper/src/server/commit_tables.rs)). Rust is the only credible alternative.

There are two caveats. First, iceberg-core breaks its API between minor releases (above), so it must be pinned, wrapped behind an internal interface, and upgraded deliberately. Second, the builder may not be a complete guardrail. In November 2026 Gravitino added its own `IcebergCommitValidation`, arguing that "Iceberg applies some metadata updates without checking them, and the result is a table that clients can no longer write or load" ([Gravitino source](https://github.com/apache/gravitino/blob/main/iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/IcebergCommitValidation.java)). The researchers did not independently verify Gravitino's specific claims. Java 25 is a long-term-support release with general availability on 2025-09-16 ([JDK 25](https://openjdk.org/projects/jdk/25/)). It includes JEP 491, which removed virtual-thread pinning on `synchronized` ([JEP 491](https://openjdk.org/jeps/491)). A blocking, thread-per-request server therefore scales without a reactive framework.

**MongoDB clears the correctness bar.**

- A single-document conditional update is atomic, and MongoDB's own docs say to "include the expected current value in the update filter" ([MongoDB atomicity](https://github.com/mongodb/docs/blob/main/content/manual/manual/source/core/write-operations-atomicity.txt)). That is exactly the per-table compare-and-swap (CAS) Iceberg needs.
- Majority write concern plus journaling prevents rollback of acknowledged writes ([rollbacks](https://github.com/mongodb/docs/blob/main/content/manual/manual/source/core/replica-set-rollbacks.txt)).
- With an odd number of sites, elections are automatic ([geo-distributed replica sets](https://github.com/mongodb/docs/blob/main/content/manual/manual/source/core/replica-set-architecture-geographically-distributed.txt)).
- Nessie labels its `MONGODB2` backend "production" ([Nessie configuration](https://github.com/projectnessie/nessie/blob/main/site/in-dev/configuration.md)), and its storage README calls MongoDB "works fine - probably a good choice" ([storage README](https://github.com/projectnessie/nessie/blob/main/versioned/storage/README.md)). The researcher found no MongoDB-specific production bug reports.

The other-implementations researcher concluded that "every self-hosted catalog surveyed is relational", but explicitly left Nessie and Polaris to other researchers. The accurate statement is narrower: **MongoDB precedent exists, but only for catalog-wide pointer designs**.

**MongoDB's costs are real and specific.**

- **Both public precedents serialize all commits through one pointer.** Polaris's beta MongoDB backend commits every catalog change against a single `cat/%d/heads/main` pointer and uses no MongoDB transactions ([Polaris NoSQL README](https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md); [Polaris MongoDB docs](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/metastores/nosql-mongodb.md)). Nessie serializes per branch.
- **Nessie sets no write concern, read concern, indexes or transactions in code.** All of that is left to the connection string ([MongoDB2Persist.java](https://github.com/projectnessie/nessie/blob/main/versioned/storage/mongodb2/src/main/java/org/projectnessie/versioned/storage/mongodb2/MongoDB2Persist.java)).
- **Reads must go to the primary.** Since MongoDB 8.0, majority writes are acknowledged before secondaries apply them, so "Queries on secondaries immediately after a `{ w: "majority" }` write acknowledgment may read from the collection before the secondary" has applied it ([write concern](https://github.com/mongodb/docs/blob/main/content/manual/manual/source/reference/write-concern.txt)).
- **The 16 MB document limit rules out storing full metadata.** The estimated 26 MB metadata.json of a streaming table (inference above) cannot live in one document.
- **Multi-document transactions are constrained** to under 60 seconds and a default 5 ms lock wait, and they abort on write conflicts ([transaction production notes](https://github.com/mongodb/docs/blob/main/content/manual/manual/source/core/transactions-production-consideration.txt)).
- **The audit outbox is awkward.** The audit design below requires the commit and its audit record to be written atomically. On MongoDB that means either running every commit as a multi-document transaction or embedding the audit record in the table's pointer document and relaying it through change streams. The researchers did not read MongoDB's documentation on how change streams behave across failover.

| Criterion | MongoDB, 3-site replica set | CockroachDB, region survival | PostgreSQL (Patroni or Aurora Global) |
|---|---|---|---|
| Per-table CAS | Conditional single-document update | Conditional UPDATE, serializable | Conditional UPDATE; WHERE re-evaluated at Read Committed ([PG docs](https://www.postgresql.org/docs/current/transaction-iso.html)) |
| Acknowledged commit lost on regional failover | None with `w:majority` + journaling | None ([survival goals](https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/multiregion-survival-goals.md)) | Aurora Global: RPO "typically measured in seconds" ([Aurora DR](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-global-database-disaster-recovery.html)); zero needs synchronous cross-region replication |
| Writes after losing a region | Automatic if a third site votes | Automatic | Managed or manual promotion |
| Commit + audit outbox + grant atomically | Multi-document transaction, or embedding | Native transaction | Native transaction |
| Multi-table commit | Transaction (5 ms lock wait, 60 s limit) | Native; contention raises 40001 retries | Native; order updates by table id |
| Write latency | ≥ RTT to the nearest majority site (inference) | "at least as much as the round-trip time to the nearest region" | Region-local unless synchronous |
| Iceberg catalog precedent | Nessie (production), Polaris (beta), both catalog-wide pointer | Polaris JDBC (supported), Nessie (experimental) | Polaris (recommended), Lakekeeper (only backend), Gravitino, Unity OSS |

Sources not linked in the table: [Polaris metastores](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/metastores/_index.md), [Lakekeeper README](https://github.com/lakekeeper/lakekeeper/blob/main/README.md).

DynamoDB global tables are disqualified in their default multi-region mode. Concurrent cross-region writes resolve as "last writer wins" ([DynamoDB global tables](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/V2globaltables_HowItWorks.html)), which can silently discard the winner of a CAS (inference).

**The verdict:** MongoDB is acceptable but not preferred. The evidence does not single it out over CockroachDB, and it does not disqualify it. The lean should prevail only if the firm's DBA organisation runs MongoDB as a tier-1 platform across three sites (decision D7). If it does, the persistence invariants in decision D8 make it safe.

## The bloat thesis holds at the edges and fails at the core

The thesis holds strongly for Polaris's architecture and only weakly for its feature list:

- **NoSQL storage stack.** Of Polaris's 85 Gradle modules ([module list](https://github.com/apache/polaris/blob/main/gradle/projects.main.properties)), 40 are the NoSQL persistence stack. Researcher line counts put it at about 34,600 lines, roughly 26% of server Java code.
- **Multi-tenancy plumbing.** "Realm" appears in 231 of 830 core, service and persistence source files. If the realm header is missing, Polaris falls back to the first realm, which its production guide treats as a security issue ([realm.md](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/realm.md)).
- **Configuration surface.** About 200 documented properties, 63 feature flags and 6 behaviour-change flags ([feature flags](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/configuration/config-sections/flags-polaris_features.md)).
- **Container image.** 418 MB compressed ([Docker Hub](https://hub.docker.com/v2/repositories/apache/polaris/tags?page_size=6)).

The headline vendor features are small by comparison: federation is about 241 lines and semantic models about 800. The cost is in the abstractions they forced. A generic entity model with JSON fields resolves table paths one entity at a time, and grants are stored as one row per privilege. That produced a `create_namespace` taking more than 30 seconds at 167,637 entities and 176,103 grant rows before indexes were added ([#3685](https://github.com/apache/polaris/issues/3685)).

Polaris's governance explains the shape. Four of its PMC members are from Dremio (including the chair) and four from Snowflake, and both run Polaris as a multi-customer service ([community page](https://github.com/apache/polaris/blob/main/site/content/community/_index.adoc)). Dremio has agreed to be acquired by SAP ([Dremio newsroom](https://www.dremio.com/newsroom/sap-to-acquire-dremio-to-unify-sap-and-non-sap-data-to-power-agentic-ai/)); completion in July 2026 is reported only by secondary sources.

**The thesis fails at the security core and at the spec's mandatory surface.**

- Credential vending, location guardrails, the commit contract and idempotency are what any enterprise with many engines needs, and Polaris does them well.
- Implementing all update types is mandatory, not bloat.
- The pipeline of spec additions is mixed. Fine-grained access control, labels, KMS vending, presigning, batch signing, server-side scan planning and an events API serve vendors who want to sit in the data path. But labels, KMS vending and events also have real enterprise value. That reading of who drives the pipeline is inference, because author affiliations were not read.

**The thesis is also incomplete, because vendor economics remove features as well as add them.**

- Lakekeeper's open-core line falls exactly where an operator's day-two needs begin. Maintenance, IdP-synced roles, Cedar policy and admission gates are in the commercial edition ([Lakekeeper authorization](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/authorization.md)).
- S3 Tables' REST endpoint refuses views, staged create (needed for create-table-as-select), OAuth and metadata.json files over 50 MB ([S3 Tables limits](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-integrating-open-source.md)).
- BigLake caps metadata.json at 1 MB ([BigLake](https://docs.cloud.google.com/bigquery/docs/blms-rest-catalog)).
- Databricks blocks external clients from running table maintenance ([Databricks Iceberg](https://docs.databricks.com/aws/en/external-access/iceberg)).
- Snowflake plans to bill 0.5 credit per million API calls ([Snowflake Horizon](https://docs.snowflake.com/en/user-guide/tables-iceberg-access-using-external-query-engine-snowflake-horizon)).

The features a regulated firm needs most are ones no open-source catalog has built well:

- a durable audit trail that includes denials;
- retention holds enforced at commit time;
- enumeration of access for access reviews;
- documented runbooks for multi-region DR.

**Verdict: a from-scratch build saves less code than the thesis implies.** It can drop roughly a quarter of Polaris's code and most of its configuration. But it must add enterprise layers that no vendor has had a commercial reason to finish.

The matrix below classifies each feature as essential (the spec or clients require it), enterprise (an operator at the firm's scale needs it) or vendor (it exists mainly for a commercial model). It then gives a keep, defer or drop call and a phase.

| Feature | Class | Call | Phase | Evidence |
|---|---|---|---|---|
| Explicit `endpoints` list in `/v1/config` | Essential | Keep | v1 | Java assumes 14 default endpoints, including multi-table commit ([RESTSessionCatalog](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/RESTSessionCatalog.java)) |
| Namespace and table create/read/update/delete, rename, register, existence (HEAD) checks, pagination, staged create | Essential | Keep | v1 | Default set in every client ([spec](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)) |
| All table and view update types and requirements; 400 on unknown | Essential | Keep | v1 | "required to fail with a 400" ([spec](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)) |
| Server-derived fields, including v3 `next-row-id` and encryption keys | Essential | Keep | v1 | [table spec](https://github.com/apache/iceberg/blob/main/format/spec.md) |
| 409-versus-5xx commit outcomes; server-side re-apply on benign conflicts | Essential | Keep | v1 | [ErrorHandlers](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/ErrorHandlers.java), [CatalogHandlers](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/CatalogHandlers.java) |
| metadata.json written each commit; `metadata-location` returned | Essential | Keep | v1 | Required in v1–v3 responses ([spec](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)) |
| Bearer/JWT validation against the enterprise identity provider | Essential | Keep | v1 | [issue #10537](https://github.com/apache/iceberg/issues/10537) |
| View endpoints | Essential (clients expect them) | Keep | v1 | Trino enables views by default ([Trino docs](https://github.com/trinodb/trino/blob/master/docs/src/main/sphinx/object-storage/metastores.md)) |
| `purgeRequested` handled as soft delete | Essential | Keep | v1 | [PR #15614](https://github.com/apache/iceberg/pull/15614) |
| Metrics endpoint (accept and forward to telemetry) | Essential (Java/Rust/Go defaults) | Keep | v1 | [RESTSessionCatalog](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/RESTSessionCatalog.java) |
| ETag/304 and correct `snapshots=refs` | Enterprise (capacity) | Keep | v1 | [ClickHouse #128348](https://github.com/ClickHouse/ClickHouse/issues/128348) |
| Durable Idempotency-Key | Enterprise (safety) | Keep | v1 | [CatalogHandlers](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/CatalogHandlers.java) |
| Credential vending with refresh (`loadCredentials`) | Enterprise | Keep | v1 | [VendedCredentialsProvider](https://github.com/apache/iceberg/blob/main/aws/src/main/java/org/apache/iceberg/aws/s3/VendedCredentialsProvider.java) |
| Location guardrails and overlap checks | Enterprise | Keep | v1 | [LocalIcebergCatalog](https://github.com/apache/polaris/blob/main/runtime/service/src/main/java/org/apache/polaris/service/catalog/iceberg/LocalIcebergCatalog.java) |
| Role-based access with a privilege ladder, inheritance, IdP-group roles, access enumeration and separation of duties | Enterprise | Keep | v1 | [500.7](https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.7), [Lakekeeper OpenFGA](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/authorization-openfga.md) |
| Registry of trusted engines; no credentials for policy-flagged tables to anyone else | Enterprise | Keep | v1 | [Databricks vending](https://docs.databricks.com/aws/en/external-access/credential-vending) |
| Audit outbox in the same transaction, including denials | Enterprise | Keep | v1 | [Polaris events](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/events.md) |
| Change events (CloudEvents to Kafka) | Enterprise | Keep | v1 | [Lakekeeper README](https://github.com/lakekeeper/lakekeeper/blob/main/README.md) |
| Commit journal and metadata.json content hash | Enterprise | Keep | v1 | [encryption docs](https://github.com/apache/iceberg/blob/main/docs/docs/encryption.md) |
| Retention classes, legal hold, commit-time guard, export | Enterprise | Keep | v1 | [17a-4](https://www.ecfr.gov/current/title-17/section-240.17a-4) |
| Soft delete, undrop, purge only on the server side | Enterprise | Keep | v1 | [Lakekeeper concepts](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md) |
| Validation guardrail layer and clock-skew guard | Enterprise | Keep | v1 | [Gravitino](https://github.com/apache/gravitino/blob/main/iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/IcebergCommitValidation.java), [Lakekeeper production](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/production.md) |
| Case-insensitive, case-preserving identifiers | Enterprise | Keep | v1 | [Lakekeeper concepts](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md) |
| Multiple catalogs, each a storage/KMS/region boundary | Enterprise | Keep | v1 | [Polaris entities](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/realm.md) |
| DR runbooks with a garbage-collection freeze (v1); continuous replication verifier (v1.x) | Enterprise | Keep | v1 / v1.x | [S3 RTC](https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication-time-control.html) |
| `/transactions/commit` (atomic across tables) | Essential once advertised | Keep | v1.x | Only DuckDB uses it ([DuckDB](https://github.com/duckdb/duckdb-iceberg/blob/main/src/catalog/rest/transaction/iceberg_transaction.cpp)) |
| Unregister, register-view, labels | Enterprise (migration, classification) | Keep | v1.x | [spec](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml) |
| Maintenance policy plus an external worker tier | Enterprise | Keep | v1.x | [#3462](https://github.com/apache/polaris/issues/3462), [S3 Tables](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-maintenance.html) |
| Adapter to an external policy decision point | Enterprise (if mandated) | Defer | v1.x | [Polaris OPA (preview)](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/managing-security/external-pdp/opa.md) |
| Remote request signing (`/sign`) | Enterprise (stores without STS) | Defer | v1.x or later | [Lakekeeper S3](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/storage-s3.md) |
| `read-restrictions` | Enterprise once clients enforce it | Defer | Later | [PR #16198](https://github.com/apache/iceberg/pull/16198) |
| Server-side scan planning | Leans vendor (Unity ABAC) | Defer | Later | [Unity blog](https://www.databricks.com/blog/unity-catalog-and-next-era-apache-icebergtm) |
| Function (UDF) endpoints | Spec-optional | Defer | Later | [spec](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml) |
| Catalog-wide "as-of" tags | Enterprise (reproducibility) | Defer | Later | [Nessie storage README](https://github.com/projectnessie/nessie/blob/main/versioned/storage/README.md) |
| Read-only federation to Hive Metastore/Glue | Enterprise (migration aid) | Defer | Later | [Waggle Dance](https://github.com/ExpediaGroup/waggle-dance) |
| Generic (non-Iceberg) tables | Vendor (platform breadth) | Defer | Only if Delta must coexist | [Polaris generic tables](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/generic-table.md) |
| `/v1/oauth/tokens`, built-in token issuer, client-secret store | Vendor / deprecated | Drop | Never | [spec](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml) |
| Legacy `/v1/aws/s3/sign` | Deprecated | Drop | Never | [S3 signer spec](https://github.com/apache/iceberg/blob/main/aws/src/main/resources/s3-signer-open-api.yaml) |
| Realms / multi-tenancy | Vendor (SaaS) | Drop | Never | [realm.md](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/realm.md) |
| Pluggable multi-backend persistence; catalog-wide-pointer NoSQL stack | Vendor | Drop | Never | [Polaris NoSQL README](https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md) |
| Two-tier principal-role/catalog-role grant rows | Vendor-shaped | Drop | Never | [#3685](https://github.com/apache/polaris/issues/3685) |
| In-process purge and task executor | Polaris design choice | Drop | Never | [#3462](https://github.com/apache/polaris/issues/3462) |
| Scan and commit metrics stored in the catalog database | Vendor/product | Drop (send to telemetry) | Never | [Polaris schema](https://github.com/apache/polaris/blob/main/persistence/relational-jdbc/src/main/resources/postgres/schema.sql) |
| Semantic models, shares, notifications, OpenLineage ingest, Spark plugin, management CLI | Vendor | Drop | Never | [Polaris module list](https://github.com/apache/polaris/blob/main/gradle/projects.main.properties) |
| Nessie-style branches and merges as a general model | Vendor/product | Drop | Never | [#12843](https://github.com/projectnessie/nessie/issues/12843) |
| Ingestion services (File Loader, CDC), Glue sync, billing, sample data inside the catalog | Vendor | Drop | Never | [Tabular docs](https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/introducing-tabular.html) |
| Hadoop/file-system tables | Legacy | Drop | Never | Deprecated, to be removed in v4 ([table spec](https://github.com/apache/iceberg/blob/main/format/spec.md)) |

## Decisions log

The eight notes each ended with a "Decisions this implies" section. They are merged and de-duplicated below into 34 decisions. **26 can proceed now and 8 are blocked on the firm's input.** IDs such as PLT-1 refer to the open questions in the next section. Where researchers disagreed, the disagreement is laid out and a call is made.

### Strategy and stack

#### D1. Build from scratch, adopt Polaris, or fork another catalog?
**Options.**
- (a) Build a single-tenant Java catalog.
- (b) Adopt Apache Polaris and switch off what the firm does not need.
- (c) Fork Lakekeeper, or buy Lakekeeper Plus.
- (d) Adopt or fork Nessie.
- (e) Adopt Gravitino or Unity Catalog OSS.

**Evidence.**
- *Polaris.* Every 1.x release from 1.2 to 1.8 lists breaking changes ([Polaris CHANGELOG](https://github.com/apache/polaris/blob/main/CHANGELOG.md)). Its docs warn "Changes in Java classes should be expected at any time" ([evolution.md](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/evolution.md)). It lacks a regulated-grade audit trail ([#5561](https://github.com/apache/polaris/issues/5561)).
- *Lakekeeper.* Written in Rust, PostgreSQL-only and pre-1.0. One contributor has 938 commits against 163 for the next human ([contributors](https://api.github.com/repos/lakekeeper/lakekeeper/contributors)). Day-two features are in the paid edition.
- *Nessie.* Its REST support is still "experimental" ([Nessie REST guide](https://github.com/projectnessie/nessie/blob/main/site/docs/guides/iceberg-rest.md)). Dremio now says "its development has slowed considerably" ([Dremio](https://www.dremio.com/blog/what-is-nessie-catalog-versioning-and-git-for-data/)).
- *Gravitino and Unity OSS.* Gravitino does not implement multi-table transactions and authorizes only when embedded in the full server ([Gravitino REST docs](https://github.com/apache/gravitino/blob/main/docs/iceberg-rest-service.md)). Unity OSS gained Iceberg writes only on 2026-10-13 ([v0.7.0](https://github.com/unitycatalog/unitycatalog/releases/tag/v0.7.0)).
- *Self-hosting peers.* LinkedIn built its own thin catalog, OpenHouse ([OpenHouse](https://engineering.linkedin.com/blog/2023/taking-charge-of-tables--introducing-openhouse-for-big-data-mana)). Airbnb and Bloomberg built PostgreSQL-backed REST catalogs, according to a secondary summary only ([Tower](https://tower.dev/blog/seven-takeaways-from-seven-iceberg-summit-user-talks)).

**Where researchers disagreed.**
- The other-implementations researcher would build, with Polaris as the explicit fallback the build must beat.
- The Polaris researcher proposed a side-by-side benchmark at the firm's scale.
- The Nessie researcher suggested evaluating Polaris's NoSQL/MongoDB modules as a reusable component.

**Recommendation.** Build (a). Run a time-boxed bake-off of upstream Polaris 1.8 on PostgreSQL, with external OIDC and every optional feature off. It serves as the performance baseline, and as the fallback if the from-scratch v1 misses the gates in D31.

Do not reuse Polaris's MongoDB modules. They are beta, built around the catalog-wide pointer, and nearly a one-author subsystem: 95 commits from one engineer ([commit history](https://api.github.com/repos/apache/polaris/commits?path=persistence/nosql)). Reject (d) and (e). Consider (c) only if Rust is acceptable (PLT-6), and even then the paid-edition features must still be built or bought. Adopting Polaris would not remove the audit, retention or DR work (inference).

**Status:** Recommended — can proceed. This assumes PO-1: a standing team.

#### D2. Which implementation language?
**Options.** Java; Rust (iceberg-rust); Go or Python.

**Evidence.** Java's metadata builder is the reference implementation, and Polaris and Gravitino reuse it. iceberg-rust is proven on the server side by Lakekeeper ([commit_tables.rs](https://github.com/lakekeeper/lakekeeper/blob/main/crates/lakekeeper/src/server/commit_tables.rs)). The researcher's inference is that the Go and Python libraries are client-oriented and less proven server-side.

**Recommendation.** Java. The prior lean holds on evidence, because the riskiest code (applying metadata updates) can be reused rather than rewritten.

**Status:** Recommended — can proceed.

#### D3. How much of iceberg-core should the server reuse?
**Options.**
- (a) Wrap `CatalogHandlers`, Iceberg's reference request handler.
- (b) Reuse only the reusable classes (call this the kernel) and write the commit loop in-house. The kernel is `TableMetadata` and its builder, `MetadataUpdate`, `UpdateRequirement`, the parsers and serializers, and `ViewMetadata`.
- (c) Reimplement everything, as Nessie did.

**Evidence.** `CatalogHandlers` forces the server to implement Iceberg's `Catalog` and `TableOperations` interfaces. Its create path is self-described as "a hacky way to get TableOperations for an uncommitted table". Its idempotency store lives only in memory ([CatalogHandlers.java](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/CatalogHandlers.java)). Polaris copied it rather than track it ([CatalogHandlerUtils](https://github.com/apache/polaris/blob/main/runtime/service/src/main/java/org/apache/polaris/service/catalog/iceberg/CatalogHandlerUtils.java)).

**Where researchers disagreed.** The persistence researcher spoke of embedding iceberg-core for "CatalogHandlers semantics". The table-spec researcher argued for the kernel only. They agree on behaviour and differ only on which class to call; the call is the kernel.

**Recommendation.** (b). Pin an exact Iceberg version and wrap it behind an internal adapter. Keep golden metadata.json round-trip tests, and upgrade deliberately each minor release.

**Status:** Recommended — can proceed.

#### D4. Which runtime and framework?
**Options.** Spring Boot 4.x; Quarkus 3.40 (used by Polaris and Nessie); Helidon SE; Javalin on Jetty 12; GraalVM native image.

**Evidence.** JEP 491 lets virtual threads use `synchronized` without pinning, so blocking I/O scales ([JEP 491](https://openjdk.org/jeps/491)). Polaris pins Quarkus 3.40.1.1 ([libs.versions.toml](https://github.com/apache/polaris/blob/main/gradle/libs.versions.toml)) with about 20 Quarkus extensions, and ships a 418 MB image. The researcher argued native image is not worth it for a 24×7 service with reflection-heavy Jackson and Iceberg code (inference).

**Recommendation.** JDK 25 LTS, virtual threads and blocking I/O, on whichever framework the firm's platform team already curates (likely Spring Boot 4.x). Otherwise use Helidon SE or Javalin. Do not use native image. Avoid Quarkus unless its extension ecosystem is already curated.

**Status:** Blocked on the firm's input (PLT-6).

### State, persistence and commits

#### D5. What is the source of truth: metadata.json in object storage, or the catalog database?
**Options.**
- (a) Pointer only. Polaris keeps a pointer and a summary in the database, and reads metadata.json from storage on every uncached load ([LocalIcebergCatalog](https://github.com/apache/polaris/blob/main/runtime/service/src/main/java/org/apache/polaris/service/catalog/iceberg/LocalIcebergCatalog.java)).
- (b) File canonical, plus a copy in the database. Lakekeeper normalizes metadata into PostgreSQL tables but still writes files ([migration](https://github.com/lakekeeper/lakekeeper/blob/main/crates/lakekeeper-storage-postgres/migrations/20241106201139_split_table_metadata.sql)). Nessie writes the file and also stores a parsed snapshot ([CatalogServiceImpl](https://github.com/projectnessie/nessie/blob/main/catalog/service/impl/src/main/java/org/projectnessie/catalog/service/impl/CatalogServiceImpl.java)).
- (c) Database canonical, with the file still written each commit.
- (d) Database only.

**Evidence.**
- v1–v3 responses require `metadata-location`, and engines, `registerTable`, DR and any exit to another catalog read the files.
- Ryan Blue: metadata.json is "an important backup of table state… the most portable representation" ([2022](https://lists.apache.org/thread/j7dh122l944d91vp1q7gfwc4g4y8jgyh)).
- Core committers favour making metadata.json optional in v4, if catalogs can produce it on demand ([Ryan Blue, 2026](https://lists.apache.org/thread/5f5p4rcxyq7j6rt7yts68shoqmm75478)). An open REST proposal would make `metadata-location` absent "for catalog-managed tables" ([PR #17642](https://github.com/apache/iceberg/pull/17642)). There is no written proposal.
- Iceberg's encryption guidance says catalogs must not trust metadata held in storage that is "vulnerable to tampering" ([encryption docs](https://github.com/apache/iceberg/blob/main/docs/docs/encryption.md)). Hive added a metadata hash for this reason ([PR #14685](https://github.com/apache/iceberg/pull/14685)).

**Where researchers disagreed.**
- The table-spec researcher chose (c), for v4 readiness and queryable history.
- The persistence researcher chose (b), and insisted the database is never the only copy.
- The Nessie researcher chose (b), serving loads from the database copy.

**Recommendation.** Split authority precisely.
- **The database decides which version is current.** Its compare-and-swap of the pointer is the commit point.
- **The metadata.json file at that pointer is the content of record for v1–v3.** It is written before the swap.
- **The same atomic database write also stores three derived records:** a compact projection of current state, a SHA-256 hash of the file, and an append-only commit-journal entry. The journal entry holds the requirements, updates and principal for each commit. This is the audit-grade history, and also the raw material for rendering metadata on demand if v4 allows it.
- **On a hash mismatch, fail closed** for encrypted and regulated tables and raise an integrity incident.
- **Make the database the content master only when v4 spec text requires it.** Doing it sooner collides with MongoDB's 16 MB limit and with every engine, DR and exit path that reads files.

**Status:** Recommended — can proceed.

#### D6. One pointer per table, or one per catalog?
**Options.**
- (a) One pointer row or document per table. This is what the Iceberg JDBC catalog, Lakekeeper and Polaris's JDBC backend do.
- (b) One named pointer per catalog plus a commit log. This is what Nessie and Polaris's NoSQL backend do.
- (c) (b), split into several pointers when one becomes hot.

**Evidence.**
- Nessie's post-mortem called its global pointer a "single point of contention" ([incident](https://github.com/projectnessie/nessie/blob/main/site/docs/blog/posts/2022-05-incident.md)).
- Nessie's docs say relational backends "tend to become a bottleneck when concurrent Nessie commits against the same branch happen" ([configuration](https://github.com/projectnessie/nessie/blob/main/site/in-dev/configuration.md)). Its README rates PostgreSQL fine "for up to a couple of Nessie commits per second" ([README](https://github.com/projectnessie/nessie/blob/main/versioned/storage/README.md)).
- Polaris's NoSQL backend defaults to 10,000 retries within 15 seconds, explicitly to cope with "highly contended resources" ([Polaris persistence config](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/configuration/config-sections/smallrye-polaris_persistence.md)).
- Netflix peaks at 600 commits per second.

**Where researchers disagreed.** The Nessie researcher recommended (b), evolving to (c), and avoiding MongoDB multi-document transactions. The persistence and Polaris researchers recommended (a) and explicitly rejected (b).

**Recommendation.** (a). What (b) offers can be had more cheaply:
- atomic multi-table commits, through a database transaction on that rare path (D15);
- change polling, through the outbox stream (D23);
- catalog-wide "as-of" views, through the commit journal (D33).

**Status:** Recommended — can proceed.

#### D7. Which database: MongoDB, CockroachDB or PostgreSQL?
**Options.** MongoDB as a three-site replica set; CockroachDB with region survival; PostgreSQL with synchronous Patroni or Aurora Global; DynamoDB global tables.

**Evidence.** See the comparison table in the MongoDB section above. In brief:
- MongoDB and CockroachDB both lose no acknowledged commit on regional failover and fail over automatically.
- PostgreSQL needs synchronous cross-region replication, or must accept Aurora's "seconds" of lost commits ([Aurora DR](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-global-database-disaster-recovery.html)).
- CockroachDB's REGIONAL BY ROW tables can pin each table's pointer row to its home region ([regional tables](https://github.com/cockroachdb/docs/blob/main/src/current/v26.3/regional-tables.md)). Nessie warns CockroachDB raises "write too old" errors under contention, though that warning comes from its catalog-wide-pointer design.
- DynamoDB's default multi-region mode is last-writer-wins ([DynamoDB](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/V2globaltables_HowItWorks.html)).

**Where researchers disagreed.**
- *Persistence researcher:* MongoDB, with CockroachDB "equally valid" if the DBAs run it. PostgreSQL only if lost commits measured in seconds are acceptable.
- *Other-implementations researcher:* PostgreSQL, unless the MongoDB team can guarantee multi-document transactions, majority write concern and case-insensitive unique indexes.
- *Polaris researcher:* decide on which platform the firm operates, not on developer preference.
- *Tabular researcher:* judge any store against four criteria: single-table compare-and-swap, atomic multi-entity commit, cross-region consistency, and in-house expertise.
- *Security researcher:* the database must write commit, grant and audit-outbox records in one transaction.

**Recommendation.** Choose the zero-loss-capable store that the firm's DBAs already run across three sites.
- **If the DR standard demands zero lost commits and automatic regional failover:**
  - Use MongoDB if it is the operated platform, under the invariants in D8.
  - Use CockroachDB if it is the operated platform. Where both are operated, CockroachDB edges ahead. The commit, outbox and grant writes and multi-table commits are ordinary serializable transactions, and REGIONAL BY ROW gives each table a home region. The cost is a client-side retry loop for serialization errors (SQLSTATE 40001).
- **If active-passive with seconds of lost commits is acceptable:** use PostgreSQL. It has the simplest semantics and the most precedent.
- **Reject DynamoDB in its default multi-region mode outright.**

The MongoDB lean should prevail only if PLT-2 confirms it is the tier-1, three-site platform.

**Status:** Blocked on the firm's input (PLT-1, PLT-2).

#### D8. What persistence rules must any chosen database meet?
**Options.** Leave durability and consistency settings to configuration, as Nessie does; or set them in code as explicit invariants.

**Evidence.**
- Nessie maps MongoDB timeouts to an "unknown outcome" exception and re-reads state before retrying ([MongoDB2Persist](https://github.com/projectnessie/nessie/blob/main/versioned/storage/mongodb2/src/main/java/org/projectnessie/versioned/storage/mongodb2/MongoDB2Persist.java)).
- Nessie needed two offline export/import migrations between storage formats, and a rolling upgrade corrupted its pointer in production ([CHANGELOG](https://github.com/projectnessie/nessie/blob/main/CHANGELOG.md); [incident](https://github.com/projectnessie/nessie/blob/main/site/docs/blog/posts/2022-05-incident.md)).
- Polaris users missed a manual index migration ([#3685](https://github.com/apache/polaris/issues/3685)).
- Lakekeeper makes identifiers case-insensitive but case-preserving, because Spark and Trino lowercase names, Snowflake uppercases them and PyIceberg passes them through unchanged ([Lakekeeper concepts](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md)).
- Polaris caps stored objects at 350 kB ([Polaris NoSQL README](https://github.com/apache/polaris/blob/main/persistence/nosql/persistence/README.md)).

**Recommendation.** Set these as invariants in code, whichever store is chosen.

1. **Commit.** A per-table compare-and-swap on the expected metadata location plus a monotonic version number.
2. **Durability.** Acknowledge only after a majority or synchronous replica has the write.
3. **Pointer reads.** Read pointers from the primary at majority read concern, never from secondaries.
4. **One atomic write.** Pointer, projection, hash, journal entry, outbox record and idempotency result go together.
5. **Ambiguous outcomes.** Map timeouts to "unknown", then re-read the pointer and journal to settle the outcome before answering.
6. **Size budget.** An explicit per-document budget, with no full-history blobs.
7. **Identifier uniqueness.** Case-insensitive, case-preserving. Verify the chosen store supports it.
8. **Upgrades.** A versioned on-disk format, online migrations run by the deploy pipeline, and rolling-upgrade tests in CI.
9. **One backend.** A single backend behind a narrow internal interface, with no public plug-in interface.

On MongoDB specifically:
- a `findOneAndUpdate` filtered on `{_id, metadataLocation, version}`;
- `w:majority`, `j:true` and `retryWrites`, all set in code;
- `readConcern: majority` on the primary;
- a 2-2-1 three-site replica set, with no sharding and no Global Clusters;
- multi-document transactions for the commit-plus-outbox write.

**Status:** Recommended — can proceed.

#### D9. How does the server commit, and what does it return?
**Options.** Return 409 on any store error (simple); or keep a strict distinction between 409 and 5xx, reconciling ambiguous outcomes before answering. Separately: leave all retries to the client, or re-apply the client's changes on the server after a harmless conflict.

**Evidence.**
- Iceberg's reference handler retries up to four times on a lost compare-and-swap, re-validating requirements against the refreshed table each time. It returns stale sequence numbers or a stale `first-row-id` to the client as a commit failure, because "Server-side retry won't help" ([CatalogHandlers](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/CatalogHandlers.java)).
- The conformance kit assumes server-side retry by default ([open-api README](https://github.com/apache/iceberg/blob/main/open-api/README.md)).
- Polaris leaves the orphan metadata file in place when the persistence outcome is ambiguous ([LocalIcebergCatalog](https://github.com/apache/polaris/blob/main/runtime/service/src/main/java/org/apache/polaris/service/catalog/iceberg/LocalIcebergCatalog.java)).
- Lakekeeper skips no-op commits ([maintenance docs](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/table-maintenance.md)). It refuses a main-branch snapshot from a writer whose clock is more than a minute ahead ([production](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/production.md)).

**Recommendation.** Write the commit loop in-house:
1. Load the base metadata from the primary.
2. Validate the client's requirements.
3. Apply the updates through the kernel.
4. Run the validation layer (D11).
5. Write metadata.json.
6. Perform the atomic database write.

If the compare-and-swap loses to a harmless concurrent commit, re-apply against the refreshed table. Never merge past a failed requirement.

Return 409 only when a requirement failed on a consistent read, or the swap definitively lost. Settle ambiguous outcomes by re-reading the pointer; return 500 only when the outcome truly cannot be known, and never 503 on commit routes. Configure gateways and load balancers so that a timeout can never surface as a 409.

Also skip no-op commits, enforce a clock-skew guard, and leave orphan files from unknown outcomes for garbage collection.

**Status:** Recommended — can proceed.

#### D10. Should v1 support idempotency keys?
**Options.** Do not advertise them; advertise them with an in-memory store; advertise them with a durable store shared by every node.

**Evidence.**
- Clients attach keys only when the server advertises a key lifetime, and Java 1.12 retries keyed POSTs on 5xx ([release notes](https://github.com/apache/iceberg/blob/main/site/docs/releases.md)).
- The reference store lives in memory and says production servers need a durable one ([CatalogHandlers](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/rest/CatalogHandlers.java)).
- Polaris stores keys inside the created entity so "the key and the operation commit atomically", but ships with the feature off ([Polaris idempotency](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/configuration/config-sections/smallrye-polaris_idempotency.md)).

**Where researchers disagreed.** The REST researcher would advertise keys only once the store is durable, shared across regions and written in the same transaction as the commit. The persistence researcher would ship them in v1.

**Recommendation.** v1. D8 already makes the key write atomic with the commit, and a single logical database makes it visible to every node and region. Replay the stored success and deterministic 4xx responses, as the spec requires. Never store 5xx responses. When two requests with the same key arrive at once, make the second wait for the first and return its result.

**Status:** Recommended — can proceed.

#### D11. How strictly should the server validate commits?
**Options.** Rely on the Java metadata builder alone; or add an enterprise validation layer on top.

**Evidence.**
- Gravitino's validation list claims the builder lets some invalid updates through. Those claims were not independently verified ([Gravitino](https://github.com/apache/gravitino/blob/main/iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/IcebergCommitValidation.java)).
- Iceberg's encryption guidance says "Catalogs must ensure the `encryption.key-id` property is not modified or removed during table lifetime" ([encryption docs](https://github.com/apache/iceberg/blob/main/docs/docs/encryption.md)).
- Lakekeeper exposes a hook that lets external systems veto changes, for example to enforce data contracts ([Lakekeeper README](https://github.com/lakekeeper/lakekeeper/blob/main/README.md)).

**Recommendation.** The builder plus a pluggable rules layer that checks:
- schema-evolution and type-promotion rules;
- new field IDs above `last-column-id`;
- partition-field ID collisions;
- unknown transforms in the default partition spec or sort order;
- that `main` is a branch;
- that each snapshot's operation is known and its schema ID exists;
- policy on table locations and properties;
- that `encryption.key-id` never changes;
- retention holds on snapshot removal (D24);
- a change-approval hook for regulated namespaces (ENG-10);
- writer clock skew.

Test the layer against Gravitino's list to confirm or refute its claims.

**Status:** Recommended — can proceed.

#### D12. Which table format versions, and how to prepare for v4?
**Options.** Default new tables to v2 (Java's default) or to v3; allow or block upgrades to v4.

**Evidence.**
- v3 was adopted by vote in May 2025 ([vote](https://lists.apache.org/thread/9oncq0j0222nrm6scvm89xdq3gozg24p)) and marked complete on 2025-05-29 ([PR #13175](https://github.com/apache/iceberg/pull/13175)).
- Java still creates v2 tables by default, while main already supports format version 4 ([TableMetadata.java](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/TableMetadata.java)).
- On the dev list, a v1 load "MUST NOT return v4 table metadata" ([Yufei Gu](https://lists.apache.org/thread/fxftrwg17dc4b2szhjf4y79x11z7kylp)), and Daniel Weeks proposed a separate v2 load endpoint ([Weeks](https://lists.apache.org/thread/2y5vj6vsyd49p4vsnqxj75tgy0jxxfwq)).

**Recommendation.**
- **Support v1–v3 fully.** That includes `next-row-id`, advanced atomically with every reference swap on any branch, and encryption keys.
- **Set the default format per namespace.** Use v2 until ENG-1 shows that every engine reads v3, and v3 sooner if ENG-7 wants row lineage.
- **Reject upgrades to v4** until the v4 format and REST changes pass their votes.
- **Design ahead for v4.** Storage should allow `metadata-location` to be absent and the table location to be supplied by the catalog. Routing should be ready for versioned load endpoints.

**Status:** Recommended — can proceed.

### Protocol surface

#### D13. Which endpoints ship in v1, and how are they advertised?
**Options.** Omit the `endpoints` list and accept the clients' default assumptions; or always send an explicit list, choosing which optional endpoints to include.

**Evidence.**
- The client defaults are described in the first section.
- Trino enables view endpoints by default ([Trino docs](https://github.com/trinodb/trino/blob/master/docs/src/main/sphinx/object-storage/metastores.md)).
- Polaris persists engine metrics in its own catalog database ([Polaris schema](https://github.com/apache/polaris/blob/main/persistence/relational-jdbc/src/main/resources/postgres/schema.sql)).
- Researcher inference, untested: on a metadata-table lookup such as `db.tbl.snapshots`, the Java client retries only if the server's 404 has type `NoSuchTableException`. A 404 typed `NoSuchNamespaceException` breaks the lookup.

**Recommendation.** Always send an explicit `endpoints` list.

v1 includes:
- config;
- namespaces: list, create, load, existence (HEAD), drop and properties, with paginated lists;
- tables: list, create (including staged create), load, existence (HEAD), commit, drop, rename and register;
- the full set of view endpoints;
- `loadCredentials`;
- the metrics endpoint, which accepts reports, queues them for telemetry and returns 204 quickly. Do not store metrics in the catalog database, as Polaris does.

Also advertise idempotency (D10) and `snapshot-loading-mode`, implement the full error contract, and return `NoSuchTableException` on table-load 404s.

Later phases:
- *v1.x:* `/transactions/commit` (D15), unregister, register-view and labels.
- *Later:* scan planning, signing and functions.
- *Never:* the token endpoint and the legacy signer. The deprecated token route should return a fixed error naming the IdP's URL and never accept a secret (inference).

**Status:** Recommended — can proceed.

#### D14. How does the server keep table loads fast?
**Options.** Serve every load in full; or use ETags, snapshot filtering and caching to cut what each load sends.

**Evidence.**
- ClickHouse's measurements, including the `refs` bug in Polaris and in Iceberg's reference implementation, the "fixture" ([#128348](https://github.com/ClickHouse/ClickHouse/issues/128348)).
- Lakekeeper stops answering 304 well before vended credentials expire ([concepts](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md)).
- Polaris's entity cache has a race-condition history ([#761](https://github.com/apache/polaris/issues/761)) and caused an out-of-memory failure in production ([#5386](https://github.com/apache/polaris/issues/5386)).

**Recommendation.**
- **ETags.** Compute each ETag as a hash of the metadata location, the snapshot mode, the view-chain scope (`referenced-by`) and a credential epoch. Answer 304 by checking only the pointer, and stop answering 304 near credential expiry.
- **Snapshots.** Implement `snapshots=refs` correctly, including statistics files on unreferenced snapshots.
- **Caching.** Cache parsed metadata keyed by its location. Locations are immutable, so the cache never needs invalidating. Skip pointer caching at first.
- **Payload size.** Alert on per-table metadata size and snapshot count. Enforce short snapshot retention on streaming tables. Do not invent a partial-load API.

**Status:** Recommended — can proceed.

#### D15. When should atomic commits across tables ship?
**Options.** Ship `/transactions/commit` in v1; ship it in v1.x; or never advertise it.

**Evidence.**
- DuckDB is the only client found that uses it. Without it, DuckDB multi-table SQL transactions fail ([DuckDB](https://github.com/duckdb/duckdb-iceberg/blob/main/src/catalog/rest/transaction/iceberg_transaction.cpp)).
- Lakekeeper locks the tables' rows in ID order, keeps a compare-and-swap as a backstop, and runs it all in one PostgreSQL transaction ([commit.rs](https://github.com/lakekeeper/lakekeeper/blob/main/crates/lakekeeper-storage-postgres/src/tabular/table/commit.rs)).
- Gravitino and OneLake omit the endpoint.

**Recommendation.** v1.x. Advertise it only when it is truly atomic. Implement it as one database transaction with updates ordered by table ID, map write conflicts to 409, and bound the transaction size. Pull it into v1 if ENG-3 shows atomic publishing across tables is a business requirement.

**Status:** Recommended — can proceed. Ships in v1.x.

### Security

#### D16. How do clients authenticate?
**Options.** The catalog issues its own tokens (the legacy endpoint); the catalog validates tokens issued by the firm's identity provider; mTLS; AWS SigV4.

**Evidence.**
- The spec deprecates the catalog token endpoint, and Polaris's external mode answers it with HTTP 501 ([Polaris external IdP](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/managing-security/external-idp/_index.md)).
- Iceberg's docs warn that `credential` and `token` appear in Spark's Environment tab unless redacted ([rest-catalog.md](https://github.com/apache/iceberg/blob/main/docs/docs/rest-catalog.md)).
- Entra workload identity federation can trust tokens issued by any Kubernetes cluster ([Entra](https://learn.microsoft.com/en-us/entra/workload-id/workload-identity-federation)).
- Dremio's third-party AuthManager supports mTLS client authentication (RFC 8705) and private-key JWT client authentication ([Dremio AuthManager](https://github.com/dremio/iceberg-auth-manager/blob/main/docs/README.md)).

**Recommendation.** The catalog only validates tokens; it never issues them.
- **Validate** each JWT's issuer, audience, signature (against the IdP's published keys) and expiry, and map its claims and groups to principals. Store no client secrets.
- **Services** authenticate with private-key JWTs or workload-identity federation.
- **Humans** authenticate with the authorization-code flow with PKCE, or the device-code flow, so the IdP enforces MFA ([500.12](https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.12)).
- **Publish a configuration for each engine.** Always set `oauth2-server-uri` (or Snowflake's `OAUTH_TOKEN_URI`). Set `token-exchange-enabled=false` unless the IdP supports RFC 8693 token exchange. Redact credentials in Spark.
- **Other methods.** Use mTLS only for high-privilege principals. Put a gateway in front of any Kerberos-only clients. Use SigV4 only when a specific engine needs it.

**Status:** Recommended — can proceed.

#### D17. How does the end user's identity reach the catalog?
**Options.**
- (a) Engines connect only with their own service identity.
- (b) Engines exchange the end user's token for each session.
- (c) Registered trusted engines assert the end user's identity and enforce fine-grained policy themselves.

**Evidence.**
- 17a-4's audit-trail option requires "the identity of the individual" where applicable ([17a-4](https://www.ecfr.gov/current/title-17/section-240.17a-4)).
- Entra's on-behalf-of flow is a JWT-bearer grant, not RFC 8693 token exchange ([Entra OBO](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-on-behalf-of-flow)), whereas Okta implements RFC 8693 ([Okta](https://developer.okta.com/docs/guides/set-up-token-exchange/main/)).
- Trino can send end-user session information (`session=USER`) ([Trino docs](https://github.com/trinodb/trino/blob/master/docs/src/main/sphinx/object-storage/metastores.md)). StarRocks can pass the logged-in user's JWT through ([StarRocks](https://github.com/StarRocks/starrocks/blob/main/docs/en/data_source/catalog/iceberg/iceberg.md)).
- Snowflake "doesn't sync remote catalog access control for users or roles" ([Snowflake](https://docs.snowflake.com/en/user-guide/tables-iceberg-catalog-linked-database)).
- Unverified: whether Dremio's AuthManager can carry Entra's `requested_token_use` parameter, and exactly what Trino sends in `session=USER` mode.

**Recommendation.** (c), with a registry of trusted engines. Reject commits to retention-class tables that carry no end-user identity, if CMP-7 confirms the individual's identity is required.

**Status:** Blocked on the firm's input (SEC-1, SEC-2, CMP-7).

#### D18. What authorization model does the catalog use?
**Options.**
- (a) Polaris's two-tier role model.
- (b) Single-tier roles sourced from IdP groups, with a privilege ladder and inheritance, stored in the catalog database.
- (c) Relationship-based access on OpenFGA.
- (d) An external policy decision point as the primary authority.

**Evidence.**
- Polaris has 110 privilege values and 121 operations. It loads grants with one extra query per entity (the N+1 pattern), which degraded badly at 176,103 grant rows ([#3685](https://github.com/apache/polaris/issues/3685)).
- OpenFGA cannot list grants for a project and does not record who granted each permission ([Lakekeeper OpenFGA](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/authorization-openfga.md)).
- Lakekeeper separates duties: a security admin with no data access, a data admin without grant rights, and tag stewardship as its own privilege.
- Tabular had no superuser and no ownership. It used nested roles, delegation through GRANT, and FUTURE grants that cover objects created later ([Tabular RBAC](https://web.archive.org/web/20251009135800/https://docs.tabular.io/en/understanding-tabular-role-based-access-controls--rbac-.html)).
- Gravitino supports explicit DENY ([Gravitino access control](https://github.com/apache/gravitino/blob/main/docs/security/access-control.md)).

**Where researchers disagreed.**
- *Ownership:* Tabular had none; Lakekeeper and Gravitino make creators owners; the security researcher would keep ownership but deny owners re-grant rights in regulated namespaces.
- *DENY:* the other-implementations researcher wanted it; Lakekeeper is additive only.
- *Nesting:* Tabular nested roles; the security and Polaris researchers would leave nesting to the IdP.

**Recommendation.** (b).
- **Roles** are IdP groups. Nesting is the IdP's job.
- **Privileges** form a ladder: describe < select < write < modify. Add create, manage-grants and manage-tags.
- **Inheritance** flows down from catalog to namespace to table or view, which also covers objects created later.
- **Separation of duties:** grant admin, data/DDL admin, tag steward, and a platform operator with no data access.
- **Ownership** is recorded for accountability, but in regulated catalogs it confers no re-grant rights. No superuser bypass.
- **No DENY in v1.** Additive grants plus default-deny keeps access reviews tractable (inference). Revisit if the firm's entitlement model requires DENY (SEC-3).
- **Day-one APIs:** listing by principal and by object, and batch permission checks.
- **Audit:** every decision is audited (D23).

**Status:** Recommended — can proceed.

#### D19. Should the catalog defer to an enterprise policy engine?
**Options.** Make the catalog the system of record for data-access grants; sync entitlements in from an enterprise system; or ask an external policy decision point at request time.

**Evidence.**
- Polaris's OPA integration is a "preview feature and may undergo breaking changes" ([Polaris OPA](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/managing-security/external-pdp/opa.md)).
- Lakekeeper's OPA bridge serves Trino. Lakekeeper says Trino is the only engine it knows "flexible enough to honor external permissions via OPA" ([Lakekeeper OPA](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/opa.md)).

**Recommendation.** Define an internal authorizer interface from day one, batched and aware of the full object path. Build an adapter only for the system the firm mandates. Prefer syncing entitlements in over calling out on every request, for latency (inference).

**Status:** Blocked on the firm's input (SEC-3, SEC-4).

#### D20. Who enforces row and column controls?
**Options.**
- (a) Engines only.
- (b) The catalog computes `read-restrictions` for trusted engines, and refuses credentials to everyone else for tables with such policy.
- (c) Server-side scan planning that returns filtered plans.
- (d) A central data-serving layer that enforces policy itself.

**Evidence.**
- No released Java client enforces `read-restrictions` yet, and DuckDB fails closed on them.
- Unity refuses to vend credentials for filtered tables ([Databricks](https://docs.databricks.com/aws/en/external-access/credential-vending)).
- Unity's attribute-based access works through scan planning and needs the Iceberg 1.11 client ([Unity blog](https://www.databricks.com/blog/unity-catalog-and-next-era-apache-icebergtm)).
- Lake Formation offers central enforcement ([Lake Formation](https://docs.aws.amazon.com/lake-formation/latest/dg/Integrating-with-LakeFormation.html)).
- Google's account of the design, read only through a tool's summary, says engine trust is "asserted, not proven" ([Google blog](https://opensource.googleblog.com/2026/10/standardizing-fine-grained-access-control-in-apache-icebergs-rest-catalog.html)).

**Recommendation.**
- **v1:** table-level authorization, plus a registry of trusted engines and a flag on tables whose row or column policy the engine enforces. For flagged tables, vend or sign only to the trusted Trino or Spark platforms.
- **Design (b) now,** and ship it once Java 1.13 support lands and the spec adds a way for clients to declare they enforce restrictions.
- **Consider (c) or (d) only** if untrusted tools such as notebooks need sensitive data.

**Status:** Blocked on the firm's input (SEC-5, CMP-8).

#### D21. How are storage credentials vended, and what guardrails apply?
**Options.** Engines keep their own storage credentials; the catalog vends short-lived credentials scoped to each table; or the catalog signs each storage request (D22).

**Evidence.**
- The STS and session-policy limits, and Java's refresher requirements, are described in the second section.
- Polaris already uses principal attribution, session tags and a credential cache ([Polaris vended credentials](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/vended-credentials.md)).
- Nessie's prefix over-grant bug ([Nessie changelog](https://github.com/projectnessie/nessie/blob/main/CHANGELOG.md)).
- Lakekeeper warns that a writer holding vended credentials "can overwrite or delete any file of the table" ([concepts](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md)).

**Recommendation.** Vend credentials by default, starting with AWS S3.

How credentials are issued:
- one `s3` credential per table, scoped to the table's prefix by a session policy, carrying its expiry time, with the refresh endpoint set in the table config;
- a lifetime of 15–60 minutes, with security setting the maximum (SEC-6);
- a role session name and session tags carrying the catalog principal and request ID, so CloudTrail records can be joined to catalog audit;
- credentials cached for most of their lifetime;
- vending spread across several AWS accounts if the per-account STS limit binds (PLT-5);
- the `none` value and an absent delegation header honoured for trusted engines with their own IAM roles.

Guardrails:
- Polaris-style checks: allowed locations per catalog, an overlap index, metadata files inside the table directory, and no trust in client-supplied storage endpoints;
- catalog-assigned table locations of bounded length, so session policies stay under 2,048 characters whatever the namespace depth (inference from [#3243](https://github.com/apache/polaris/issues/3243));
- no delete permission for non-maintenance principals (D25);
- adversarial tests for prefix boundaries such as `orders` versus `orders2`.

**Status:** Recommended — can proceed. Covers AWS S3; other stores are D22.

#### D22. When does the catalog sign storage requests instead of vending credentials?
**Options.** Vend credentials everywhere; sign every request; or sign only for stores or data classes that need it.

**Evidence.**
- Remote signing works with any store that supports AWS SigV4, while vending needs an STS endpoint ([Lakekeeper S3](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/storage-s3.md)).
- FlashBlade lacks AssumeRole.
- Nessie notes that signing "requires a REST call for every S3 request" ([Nessie REST guide](https://github.com/projectnessie/nessie/blob/main/site/docs/guides/iceberg-rest.md)).
- Jason Reid warned that authorizing every request needs "incredibly high throughput and low latency" ([Reid, part 3](https://web.archive.org/web/20240601000000/https://tabular.io/blog/securing-the-data-lake-part-3/)).
- Polaris's request for on-prem S3 and remote signing has been open since 2024 ([#32](https://github.com/apache/polaris/issues/32)).

**Recommendation.** Implement the current spec's `/sign` endpoint (not the legacy signer) only where a store lacks AssumeRole with session policies, or where a data class requires a record of every object read. The signer sits in the data path, so load-test it accordingly. Sign only requests the catalog recognises, as Lakekeeper's hardened signer does.

**Status:** Blocked on the firm's input (PLT-3, SEC-7).

### Governance and compliance

#### D23. How is audit captured and delivered?
**Options.**
- (a) Best-effort event listeners (Polaris).
- (b) Structured logs collected by log agents (Lakekeeper).
- (c) A transactional outbox: the audit record is written in the same atomic operation as the change it describes, then relayed.

**Evidence.**
- Polaris's events disclaimer and its open audit-trail issue, which lists denials logged at INFO and dropped event batches ([#5561](https://github.com/apache/polaris/issues/5561)).
- Lakekeeper logs both allowed and denied decisions, with a versioned audit format ([Lakekeeper logging](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/logging.md)).
- NYDFS 500.6 requires audit records to be kept for at least five years ([500.6](https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.6)).
- The REST events endpoint is still an open proposal ([PR #12584](https://github.com/apache/iceberg/pull/12584)).

**Recommendation.** (c).

What to record, synchronously:
- reads, credential issues and signing batches;
- denials and authentication failures;
- commits, with the old and new pointer, the updates, and both the engine and end-user principals;
- grant changes;
- uses of break-glass access.

Delivery:
- a versioned record schema;
- at-least-once relay to Kafka, then to the SIEM and to a hash-chained archive under Object Lock;
- a separate CloudEvents change stream for lineage, cache invalidation and maintenance triggers. This is an interim API until the spec's events endpoint lands;
- Tabular-style queryable audit tables in v1.x.

**Status:** Recommended — can proceed. Retention and SIEM details come from CMP-4 and SEC-9.

#### D24. How does the catalog meet record-retention and legal-hold obligations?
**Options.**
- (a) Keep required records out of Iceberg.
- (b) The audit-trail path: retain every snapshot for the record period, with catalog guards and export.
- (c) The WORM path: Object Lock in compliance mode on the table's prefix.
- (d) Periodic exports to a WORM archive.

**Evidence.**
- 17a-4(f)(2)(i)(A) and (B) ([eCFR](https://www.ecfr.gov/current/title-17/section-240.17a-4)).
- The SEC's stated outcome: the system must permit "the recreation of an original record if it is modified or deleted" ([2022 release](https://www.govinfo.gov/content/pkg/FR-2022-11-03/pdf/2022-22670.pdf)).
- Object Lock's delete-marker behaviour ([Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html)).
- FINRA 4511 requires books and records to be preserved in a format that complies with 17a-4 ([FINRA 4511](https://www.finra.org/rules-guidance/rulebooks/finra-rules/4511)).

**Recommendation.** Build the mechanisms in v1 whatever the path:
- retention classes at catalog, namespace and table level;
- a legal-hold flag;
- rejection at commit time of snapshot removal inside a hold window;
- refusal to drop, purge or unregister held tables;
- export of a snapshot plus its commit journal in human-readable and machine-usable formats.

Prefer (a) or (d) for most data. Use (c) only for tables that must themselves be the record, and accept that storage is never reclaimed and that compaction roughly doubles stored bytes for rewritten data (researcher inference). Counsel picks the path for each class of table.

**Status:** Blocked on the firm's input (CMP-1, CMP-2, CMP-3).

#### D25. How do drop and purge work?
**Options.** Delete files immediately when `purgeRequested=true`; soft-delete with a retention window and a later audited purge; or reject purges.

**Evidence.**
- Spark before 1.12 deletes the files itself and sends `purgeRequested=false`. Iceberg 1.12 adds an opt-in setting that hands the purge to the catalog ([PR #15614](https://github.com/apache/iceberg/pull/15614)). The PR title and the release notes spell the property differently, and the researchers left that unresolved.
- Lakekeeper's setting that tells clients not to delete files is "a request a client can ignore" ([concepts](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md)).
- Unity OSS deletes dropped storage after 7 days, a delay that "is not a recovery window" ([v0.7.0](https://github.com/unitycatalog/unitycatalog/releases/tag/v0.7.0)).

**Recommendation.**
- **Drop is a soft delete,** with an expiry fixed at drop time, undrop available within the window, and the table UUID retained.
- **Only the maintenance tier purges files (D30),** with rate limits and audit, and never for held tables. The catalog request process never purges.
- **Require Spark 1.12 or later with purge delegation,** after verifying the property's spelling against the actual client.
- **Enforce soft delete in storage** by withholding delete permission from the vended credentials of non-maintenance principals.

**Status:** Recommended — can proceed. The window comes from CMP-4.

#### D26. How are tables encrypted, and how is metadata integrity protected?
**Options.** Server-side encryption with KMS keys per domain or classification; Iceberg's client-side table encryption; or both for the most sensitive tier.

**Evidence.**
- Iceberg encrypts data and manifests, but metadata.json "is not encrypted" ([encryption docs](https://github.com/apache/iceberg/blob/main/docs/docs/encryption.md)).
- Client-side encryption through REST catalogs is still an open pull request ([PR #13225](https://github.com/apache/iceberg/pull/13225)).
- NYDFS 500.15 requires encryption at rest and over external networks ([500.15](https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.15)).

**Recommendation.**
- **Now:** server-side KMS encryption with keys per domain or classification, and TLS everywhere, including internal networks.
- **The catalog never calls KMS.** It stores only wrapped keys.
- **Integrity:** verify each metadata.json against its stored hash (D5), and keep `encryption.key-id` immutable (D11).
- **Later:** pilot Iceberg's client-side encryption.

**Status:** Recommended — can proceed. Key ownership comes from SEC-8.

#### D27. Where is the line between this catalog and the governance catalog?
**Options.**
- (a) A fat catalog that also holds the glossary, lineage and stewardship workflow.
- (b) A thin enforcement catalog, with a governance catalog as the system of record for business meaning.

**Evidence.**
- The spec's `labels` field is read-only enrichment that "clients may ignore", and the spec says nothing about how labels are stored ([REST spec](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)).
- NYDFS 500.13 requires an asset inventory recording owner and classification ([500.13](https://www.law.cornell.edu/regulations/new-york/23-NYCRR-500.13)).
- Netflix's Metacat kept business metadata separate from technical metadata ([Metacat](https://netflixtechblog.com/metacat-making-big-data-discoverable-and-meaningful-at-netflix-56fb36a53520)).
- OpenLineage identifies a catalog through its `CatalogDatasetFacet` ([facet schema](https://github.com/OpenLineage/OpenLineage/blob/main/spec/facets/CatalogDatasetFacet.json)).

**Recommendation.** (b).
- **The governance tool owns** the glossary, stewardship, lineage graph, data quality and classification curation.
- **The catalog keeps enforcement copies** of classification, owner and retention class, recording where each value came from and auditing every change.
- **Unclassified objects in regulated namespaces are denied by default.**
- **Labels pass through to engines** from v1.x.

**Status:** Recommended — can proceed. The tool choice comes from ENG-9.

### Operations and resilience

#### D28. What are the tenancy unit and the namespace model?
**Options.** Multi-tenant realms in every row (Polaris); a single tenant with catalogs as the partition unit; or a separate deployment per legal entity.

**Evidence.**
- Polaris's realm risks (above).
- Lakekeeper recommends a single project for a single company, and warns "Never share locations between Warehouses" ([concepts](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/concepts.md)).
- Glue supports only single-level namespaces ([Glue REST](https://docs.aws.amazon.com/glue/latest/dg/connect-glu-iceberg-rest.md)).

**Recommendation.**
- **No realms.** One deployment per environment.
- **A catalog is the boundary.** Each catalog (the REST prefix) is a storage, KMS, region and legal-entity boundary.
- **Nested namespaces are allowed,** as the spec permits, but policy caps their depth (ENG-5).
- **Table locations are not derived from deep namespace paths.**

**Status:** Recommended — can proceed.

#### D29. How does the catalog survive the loss of a region?
**Options.**
- (a) Stateless catalog servers in every region over one strongly consistent database spanning three sites, with a home region per table.
- (b) A home region per catalog, with read-only catalogs replicated asynchronously to other regions and an explicit failover.
- (c) Independent databases accepting writes in each region. Rejected: it breaks the single serialization point every table needs.

**Evidence.**
- S3 RTC replicates 99.9% of objects within 15 minutes ([S3 RTC](https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication-time-control.html)).
- MongoDB and CockroachDB can fail over losing no acknowledged commits; Aurora loses seconds.
- Lakekeeper empties all caches after a database failover ([Lakekeeper configuration](https://github.com/lakekeeper/lakekeeper/blob/main/docs/docs/configuration.md)).
- dbt Labs calls multi-region catalogs an open question ([dbt](https://www.getdbt.com/blog/iceberg-give-it-a-rest)).

**Where researchers disagreed.** The persistence researcher chose (a). The Tabular, other-implementations and Polaris researchers chose (b). The Nessie researcher preferred a single primary region.

**Recommendation.** (a) if PLT-1 requires zero lost commits for catalog state. Because data files replicate asynchronously anyway, a zero-loss database does not make table contents loss-free. Its value is protecting audit records and grants, and avoiding the hazard where a failover that lost commits lets garbage collection destroy those commits' files (inference). (b) is acceptable otherwise.

Either way, build:
- **in v1:** a rewind and fast-forward runbook with a garbage-collection freeze, fencing of the old region, and a cache flush;
- **in v1.x:** a replication-watermark verifier that advances a per-table "DR-safe" pointer only after files have replicated;
- **retention settings tied to the database's point-in-time-recovery window:** snapshot age, the orphan-removal age threshold, retention of non-current object versions, and `previous-versions-max`;
- **restore drills** that check a sample of tables have every file they reference.

**Status:** Blocked on the firm's input (PLT-1, PLT-3, PLT-4).

#### D30. Who owns table maintenance?
**Options.** Each team runs its own jobs; the catalog only stores policy (Polaris); the catalog runs maintenance itself (S3 Tables, Lakekeeper Plus); or a separate worker tier triggered by catalog events (OpenHouse, Amoro).

**Evidence.**
- Polaris's in-process purge failure ([#3462](https://github.com/apache/polaris/issues/3462)).
- S3 Tables runs maintenance by default ([S3 Tables](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-maintenance.html)).
- OpenHouse starts jobs "almost instantaneously as Iceberg snapshots are committed" ([OpenHouse](https://engineering.linkedin.com/blog/2023/taking-charge-of-tables--introducing-openhouse-for-big-data-mana)).
- Apache Amoro's README still says "(incubating)" ([Amoro](https://github.com/apache/amoro/blob/master/README.md)).

**Where researchers disagreed.** The persistence researcher had the catalog schedule jobs and workers execute them. The Polaris and other-implementations researchers had the catalog only emit events and leave scheduling to a separate service.

**Recommendation.** Split the roles.
- **The catalog owns** maintenance policy (expiry, orphan removal, compaction, manifest rewrite) with inheritance, plus guardrails: minimum ages, hold enforcement, the point-in-time-recovery coupling, and suspension during DR events. It also emits commit events.
- **A separate worker service (v1.x)** schedules and runs the jobs under its own identity and commits through the normal compare-and-swap, so every maintenance commit is attributable. Build it, or evaluate Amoro.
- **Streaming tables** get short snapshot retention and frequent manifest rewrites.

**Status:** Recommended — can proceed. Funding comes from PLT-7.

#### D31. What conformance, performance and upgrade gates must the build pass?
**Options.** Rely on the REST Compatibility Kit alone; or add an engine matrix, load tests and failure tests as release gates.

**Evidence.**
- The conformance kit's scope is Java-client paths only ([open-api README](https://github.com/apache/iceberg/blob/main/open-api/README.md)).
- Polaris's Gatling workloads are catalog-agnostic, but the repository publishes no results ([polaris-tools](https://github.com/apache/polaris-tools/blob/main/benchmarks/README.md)).
- A vendor-affiliated benchmark with one GitHub star reported PostgreSQL deadlock-backed 503s from Lakekeeper 0.13.3 and HTTP 500s from Nessie under concurrent commits ([catalog-bench](https://github.com/querygraph/catalog-bench)).
- Cloudera's REST catalog timed out on 0.2–5.7% of requests at 15–30 requests per second, depending on heap size ([Cloudera sizing](https://docs.cloudera.com/runtime/7.3.1/data-sharing-reference/topics/cr-ds-rest-catalog-sizing-guide.html)).

**Recommendation.**
- **Conformance:** run the kit in CI from the first commit. Run a nightly engine matrix per ENG-1, and diff responses against the reference fixture image (`apache/iceberg-rest-fixture`).
- **Load:** a long-snapshot-history load test in the style of ClickHouse's, and a test with 8–32 concurrent writers on one table.
- **Release gates:** zero 5xx and no deadlocks under contention; adversarial credential-scoping tests; rolling-upgrade and database-failover tests.
- **Service-level objectives** (the persistence researcher's proposal, not published numbers): table load p99 under 50 ms for metadata under 1 MB; 304 p99 under 10 ms; commit p99 under 300 ms excluding client I/O; at least 500 commits per second across tables.

**Status:** Recommended — can proceed.

#### D32. How does the team keep pace with the spec?
**Options.** Freeze on one Iceberg release; or track the spec continuously and take part in its development.

**Evidence.** Spec churn and the rate of breaking changes in iceberg-core are covered in the first section. The v4 REST design and the "optional metadata.json" direction are being decided in dev@ threads from August to November 2026.

**Recommendation.**
- **Target** spec 1.12 plus the `none` access-delegation value from main.
- **Upgrade** the pinned iceberg-core each minor release, about two or three times a year.
- **Watch** dev@ vote threads.
- **Prefer spec proposals to private extensions:** events, fine-grained access control, v4 catalog-managed metadata (PO-4).

**Status:** Recommended — can proceed.

### Scope boundaries

#### D33. Should the catalog offer git-style branching across tables?
**Options.** Full Nessie-style branches and merges; catalog-wide read-only tags only; or Iceberg's own per-table branches and tags only.

**Evidence.**
- Nessie merges degrade as history grows (#12843), and merges time out while `main` takes concurrent commits ([#10423](https://github.com/projectnessie/nessie/issues/10423)).
- Garbage collection across branches needs repository-wide mark-and-sweep with deferred deletion and human review ([Nessie management](https://github.com/projectnessie/nessie/blob/main/site/docs/guides/management.md)).
- Branches do not migrate one-to-one to Polaris ([Dremio](https://www.dremio.com/blog/migrating-from-nessie-to-apache-polaris-without-rewriting-iceberg-data/)).
- Iceberg's native branches and tags already cover write-audit-publish workflows ([branching docs](https://github.com/apache/iceberg/blob/main/docs/docs/branching.md)).

**Recommendation.** No catalog-level branches or merges. Pass Iceberg's native table branches and tags through. Build catalog-wide "as-of" tags later, on the commit journal, if ENG-3 shows a need.

**Status:** Recommended — can proceed.

#### D34. What about federation, non-Iceberg tables and adopting legacy tables?
**Options.** Federate to legacy catalogs or not; support non-Iceberg "generic" tables or not; and how to bring existing tables in.

**Evidence.**
- Polaris federation is off by default ([Polaris federation](https://github.com/apache/polaris/blob/main/site/content/in-dev/unreleased/federation/_index.md)).
- Expedia's Waggle Dance shows federation serving migration ([Waggle Dance](https://github.com/ExpediaGroup/waggle-dance)).
- Hive 4.1 and later expose a full Iceberg REST catalog over the existing metastore ([Hive REST catalog](https://hive.apache.org/docs/latest/admin/iceberg-rest-catalog/)).
- File-system tables are deprecated and will be removed in v4 ([table spec](https://github.com/apache/iceberg/blob/main/format/spec.md)).

**Recommendation.**
- **v1 adoption** goes through `registerTable`, with bulk registration tooling.
- **No Hadoop or file-system tables.**
- **Federation to Hive Metastore or Glue** only as a read-only, time-boxed migration aid, if ENG-6 needs it.
- **Generic tables** only if Delta or Hudi must share governance.

**Status:** Recommended — can proceed.

## Open questions for the firm, grouped by who must answer

The questions below merge and de-duplicate the eight notes' "open questions" sections. Each lists the decisions it unblocks or refines.

### Compliance and legal

| ID | Question | Unblocks |
|---|---|---|
| CMP-1 | Which of the firm's legal entities will use the catalog, and which regimes apply to each? Broker-dealer rules (17a-3/17a-4, FINRA 4511, 17a-5 internal control over compliance), adviser rule 204-2, fund rules (31a-2, N-CSR certifications), NYDFS Part 500 (including Class A status), Reg S-P "larger entity" status, FTC jurisdiction, SOX issuer status. | D24, D17, D23, D26 |
| CMP-2 | Will any Iceberg table be a required record, or the only copy of one, under 17a-4, 204-2 or 31a-2? If so, does the firm rely on the WORM path or the audit-trail path, and who signs the 17a-4(f)(3)(v) undertaking? | D24, D25, D29, D30 |
| CMP-3 | Does compliance accept Iceberg snapshot history plus the catalog commit log as a "complete time-stamped audit trail" under 17a-4(f)(2)(i)(A)? What export format will examiners expect? | D24, D23 |
| CMP-4 | What retention applies to catalog audit logs (at least 5 years under NYDFS 500.6, or the record period), the commit journal, prior metadata files, and dropped tables? Must drops be reversible, and for how long? | D23, D25, D5, D30 |
| CMP-5 | How are deletion requests (privacy, Reg S-P disposal, contractual) reconciled with retention holds, and who approves a physical purge? | D25, D24 |
| CMP-6 | Are the on-prem object stores assessed for 17a-4 WORM use (as Cohasset assessed S3 Object Lock)? Which cloud providers have 17a-4(i) undertakings on file? | D24, D22 |
| CMP-7 | For regulated tables, must every commit record the individual's identity, or is an engine's service identity acceptable? | D17 |
| CMP-8 | Does any regulation or internal policy require the catalog itself, rather than the engines, to enforce row and column controls? | D20 |

### Security and IAM

| ID | Question | Unblocks |
|---|---|---|
| SEC-1 | Which IdP is authoritative for workloads and for humans (Entra ID, Ping, Okta)? Does it support RFC 8693 token exchange, or only Entra's on-behalf-of flow? Which claims distinguish humans from services? Is workload-identity federation available for Kubernetes? | D16, D17 |
| SEC-2 | Must end-user identity reach the catalog for each data class, or is engine-level identity acceptable? Which shared engines (for example Trino) need per-user impersonation? | D17, D20 |
| SEC-3 | Is there a mandated central policy engine (Ranger, Privacera, Immuta, OPA) or entitlement system of record (SailPoint, AD groups)? Must the catalog ask it on every request, or sync from it? | D19, D18 |
| SEC-4 | What are the access-review process and tool? What export format is needed for "all privileges of principal X" and "all principals on object Y"? | D19, D18 |
| SEC-5 | Which engines are trusted to enforce row and column policy, and who attests to their configuration? What is the policy for notebooks and PyIceberg on sensitive tables? | D20 |
| SEC-6 | What is the maximum acceptable lifetime of a vended credential (this sets revocation latency)? Must storage access be limited to corporate network endpoints? May delete permission be withheld from non-maintenance principals? | D21, D25 |
| SEC-7 | Does any data class require a record of every object read, which would mean per-request signing? | D22 |
| SEC-8 | Is TLS required on internal networks? Is Iceberg client-side encryption required for any class? Who owns KMS key policy for each domain? Must the catalog detect tampering with metadata.json? | D26 |
| SEC-9 | Which SIEM and audit archive will be used, and is hash-chaining or notarisation required? | D23 |
| SEC-10 | May Snowflake, an external SaaS, receive vended credentials, and over which network path (public internet or PrivateLink)? | D21, D13 |

### Platform and infrastructure

| ID | Question | Unblocks |
|---|---|---|
| PLT-1 | What is the DR standard for this tier? How much acknowledged catalog state may be lost (zero, or minutes), and what is the recovery-time target? Are writes active in several regions, or single-writer with DR? Must a commit in one region be visible immediately in another? | D7, D29 |
| PLT-2 | Which databases does the DBA organisation run at tier 1 across three sites: MongoDB Enterprise Advanced or Atlas, CockroachDB, Aurora, or PostgreSQL with Patroni? Is a third site available? Will DBAs accept the cross-region latency of majority writes, and support multi-document transactions? | D7, D8, D29 |
| PLT-3 | Which object stores are in scope: AWS S3, and on-prem products at which versions (MinIO or a fork, Ceph, ECS/ObjectScale, StorageGRID, FlashBlade)? ADLS or GCS? Does each support STS AssumeRole with session policies? | D21, D22, D29 |
| PLT-4 | What replication mechanism and SLA apply between regions? Are bucket versioning and Object Lock mandated? Do bucket names differ by region, which would force table locations to be rewritten on failover? | D29, D24 |
| PLT-5 | How many AWS accounts and regions hold table storage? Is spreading STS calls across several vending accounts acceptable to cloud governance? | D21 |
| PLT-6 | Which framework BOMs, JDK distribution, MongoDB or JDBC drivers and iceberg-core versions are approved in the curated artifact repository? What is the lead time to add an artifact? Is Rust acceptable for a critical service? | D4, D2, D1 |
| PLT-7 | Who funds and operates the maintenance compute (Spark versus Java workers), and under which change-control regime? | D30 |

### Data engineering, engine owners and data governance

| ID | Question | Unblocks |
|---|---|---|
| ENG-1 | Which engines are in scope, at which exact versions (Spark, Flink, Trino/Starburst, Snowflake catalog-linked databases, Dremio, Databricks, PyIceberg, DuckDB, StarRocks, Kafka Connect)? Which of them write? Are the Java clients on 1.11 or later (ETags, idempotency) and 1.12 or later (purge delegation)? Do they all read format v3? | D12, D13, D10, D21, D25 |
| ENG-2 | How many tables, views and namespaces, and how deep are the namespaces? What are peak table-load QPS, aggregate commits per second, and the hottest table's commit interval? What snapshot counts and latency targets apply? | D14, D29, D31, D6 |
| ENG-3 | Is publishing several tables atomically a business requirement? Does anyone have a funded use case for long-lived branches and merges, or would catalog-wide "as-of" tags suffice? | D15, D33 |
| ENG-4 | Will views be shared across engines with different SQL dialects, and who owns view definitions? | D13 |
| ENG-5 | Will namespaces be nested or flat, and to what maximum depth? | D28, D21 |
| ENG-6 | Which legacy catalogs exist (Hive Metastore versions, Glue accounts), and how many tables must be migrated or federated? Must non-Iceberg formats such as Delta or Hudi share governance? | D34 |
| ENG-7 | Is v3 row lineage wanted for change data capture or regulatory audit? | D12 |
| ENG-8 | Is a catalog change feed needed now, and is a non-standard interim events API acceptable until the spec's events endpoint lands? | D23, D13 |
| ENG-9 | Which governance catalog is the system of record (Collibra, Alation, DataHub, OpenMetadata)? Can it push classification events, and does it consume OpenLineage? | D27 |
| ENG-10 | Who approves schema and partition changes on regulated tables? Should the catalog enforce that approval, or only audit it? | D11, D24 |

### Product owner (you)

| ID | Question | Unblocks |
|---|---|---|
| PO-1 | Will the firm commit a standing team to own the catalog as a tier-1 service for its whole life? That includes 2–3 iceberg-core upgrades a year and a voted REST feature every few weeks. | D1, D32 |
| PO-2 | Is commercial support acceptable (Vakamo for Lakekeeper; Snowflake or Dremio for Polaris), or must the catalog be fully in-house? | D1 |
| PO-3 | Will you fund a time-boxed bake-off against upstream Polaris on PostgreSQL, with every optional feature off, as the baseline the build must beat? | D1, D31 |
| PO-4 | Will the firm take part in dev@iceberg (v4 catalog-managed metadata, the v2 load endpoint, events, fine-grained access control) rather than maintain private extensions? | D32, D12 |

## Phased scope: v1, v1.x, later, never

### v1: a production catalog for the first regulated line of business

**Protocol.**
- Config with an explicit `endpoints` list, the idempotency key lifetime and `snapshot-loading-mode`.
- Namespaces with properties and paging, nested but with depth capped by policy.
- Tables, including staged create, ETag/304, correct `snapshots=refs`, commit, drop as soft delete, rename and register.
- All view endpoints, `loadCredentials`, and a metrics endpoint that forwards to telemetry.
- The full error contract with the strict 409-versus-500 rule.

**Commit engine.**
- The pinned iceberg-core classes behind an adapter.
- Every update and requirement type.
- Full v1–v3 support, including `next-row-id` and encryption keys.
- The validation layer, including the clock-skew guard, `encryption.key-id` immutability and the retention-hold guard.
- A block on upgrades to v4.

**Persistence.**
- The database chosen under D7, meeting the D8 invariants.
- One atomic write covering pointer, projection, metadata hash, commit journal, audit outbox and idempotency result.
- Automated, versioned migrations.

**Security.**
- Validation of IdP-issued JWTs, with configuration published for each engine.
- The privilege ladder with inheritance, IdP-group roles, separation-of-duties roles, listing APIs and batch checks.
- Credential vending for AWS S3 with location guardrails.
- The trusted-engine registry, with no credentials for policy-flagged tables to anyone else.

**Governance.**
- The audit outbox, relayed to Kafka, the SIEM and an Object Lock archive.
- The change-event stream.
- Retention classes, legal holds and export.
- Soft delete and undrop.
- Hash verification of metadata.json on load.

**Operations.**
- Stateless servers in every region over the chosen database topology.
- A rewind and fast-forward runbook with a garbage-collection freeze.
- Retention settings tied to the point-in-time-recovery window.

**Gates.** The D31 suite: conformance kit, engine matrix, load and conflict tests, zero-5xx, adversarial credential-scoping, and rolling-upgrade and failover tests.

### v1.x: completing the enterprise feature set

- `/transactions/commit`, advertised only when truly atomic.
- `unregisterTable`, `register-view` and labels passed through from the governance catalog.
- The separate maintenance worker service: expiry, orphan removal, manifest rewrite, and compaction through Spark.
- The continuous replication-watermark verifier for DR.
- Queryable audit tables in Iceberg.
- An external policy-engine adapter, if SEC-3 mandates one.
- Remote signing for stores without STS, or for data classes needing a record of every object read (D22).
- End-user identity propagation through trusted engines, unless CMP-7 pulls it into v1.
- ADLS or GCS vending, if PLT-3 puts them in scope.

### Later: when the spec, the clients or a business case is ready

- `read-restrictions` emission to trusted engines, after the Java 1.13 support ships and the spec gains a way for clients to declare they enforce it.
- Server-side scan planning, only as an enforcement point for untrusted engines.
- Function (UDF) endpoints.
- The standard events endpoint, replacing the interim stream once PR #12584 merges.
- Batch "resolve" loads and KMS credential vending, as they are voted in.
- Format v4, with the versioned load endpoint and catalog-managed metadata built on the commit journal.
- An Iceberg client-side encryption pilot.
- Catalog-wide "as-of" tags.
- Read-only federation to Hive Metastore or Glue, for migration.
- Generic tables, if Delta coexistence demands them.
- Explicit DENY, if the entitlement model requires it.
- Short-lived, auto-expiring branches, given a funded use case.

### Never

- The `/v1/oauth/tokens` endpoint, a built-in token issuer or a client-secret store.
- The legacy `/v1/aws/s3/sign` signer.
- Realms and multi-tenancy.
- A pluggable multi-backend persistence layer, or catalog-wide-pointer commit logs.
- Nessie-style long-lived branches and merges as a general model.
- In-process purge.
- Hadoop or file-system tables.
- Engine scan and commit metrics stored in the catalog database.
- Semantic models, shares, notifications, billing and sample data.
- Ingestion services built into the catalog process.
- DynamoDB in its default multi-region mode; MongoDB with `w:1`; table loads served from database secondaries.

## Conclusion

The question "build or adopt?" turns out to be mostly a question of who builds the enterprise layers, because no candidate supplies them:

- Polaris disclaims a full audit trail.
- Lakekeeper sells its maintenance features.
- Nessie is effectively maintained by one engineer.
- Hosted catalogs restrict the spec to suit their vendors.

That makes the from-scratch path more reasonable than it first appears, but also more expensive than "drop the vendor bloat" implies. The protocol core can be small precisely because Iceberg moved the hard metadata work into clients. The firm's real build is audit, retention, authorization, credential scoping and DR, and those would be needed on top of Polaris anyway.

Two further points change how the remaining choices should be framed.

First, the database decision is a disaster-recovery decision in disguise. Object storage replicates asynchronously, so no database can make table contents loss-free across regions. A zero-loss database matters because it keeps audit records and grants intact and stops garbage collection from destroying the files of "forgotten" commits. MongoDB, CockroachDB and PostgreSQL should be judged on that, and on what the firm's DBAs already run, rather than on the user's preference or on the open-source precedent.

Second, the commit journal that 17a-4 pushes the firm to build is the same structure Iceberg v4's "catalog-managed metadata" would need. Compliance and future-proofing point to one design. The v4 shape is being decided on the dev list right now, so taking part (PO-4) is the cheapest way to make sure the firm's design and the spec converge.

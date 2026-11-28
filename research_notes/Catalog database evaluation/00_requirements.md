# Catalog database: workload and requirements

Input to the database evaluation (decision D7 in `decision-ledger.md`). Decisions already made: Java 25 / Spring Boot 4 server, stateless nodes; iceberg-core building blocks with our own commit loop (D3); one pointer record per table (D6); split authority (D5): the DB pointer swap is the commit point, metadata.json in object storage is the content of record, and the same atomic DB write stores a compact current-state projection, the file's SHA-256 and a commit-journal entry.

Numbers marked *envelope* are design assumptions until the firm supplies real ones (open question ENG-2). Sources are the notes in `research_notes/Enterprise Iceberg REST catalog plan/`.

## Records

| Record | Cardinality | Size | Access |
|---|---|---|---|
| Table pointer + projection | one per table; envelope 1M tables (Glue caps 1M per account-region; Netflix runs 3M+ Iceberg tables) | pointer ~200 B; projection a few KB to a few hundred KB (current schema, specs, refs, recent snapshots) | point read by (namespace, name); compare-and-set update |
| Commit journal entry | one per commit, append-only, retained for years (regulatory) | usually 1-20 KB (requirements + updates JSON + principal); occasionally MBs (large schema) | append; range scan by table and time |
| Audit outbox | one or more per request incl. denials | ~1 KB | append; drained to Kafka/SIEM in order; must be durable |
| Idempotency key | one per keyed mutating request | ~1-50 KB stored response | insert-if-absent; TTL expiry (spec advertises a lifetime) |
| Namespace | envelope 10k-100k, nested | small | list children with paging |
| Grants | envelope 100k-1M rows (Polaris hit 176k grant rows and 30 s namespace creates before indexing) | small | "can P do X on object path" checks, batched; list all grants of principal P (access review) and all principals on object O |
| View pointer + versions | like tables, fewer | small | as tables |

Identifiers: case-insensitive uniqueness of names within a namespace is likely required (Lakekeeper does this); listing by prefix with stable paging.

## Operations and rates (envelope)

- **Table loads dominate.** Netflix peak: 12,000 loads/s and 600 commits/s across 3M tables. Snowflake catalog-linked databases poll every 30 s per table; SingleStore pipelines poll loadTable every ~2.5 s per pipeline (inference). Envelope: 5,000 loads/s sustained, 20,000 peak; served from the DB projection, with ETag/304.
- **Commits:** envelope 500/s aggregate peak. Hot tables (Flink commits per checkpoint, often 10-60 s; Kafka Connect default 5 min) commit up to ~1-2/s on one table; the per-table ceiling is set by client storage I/O, not the catalog.
- **Commit transaction** (one atomic unit): read pointer → compare-and-set pointer (fail if changed) → upsert projection → insert journal entry → insert audit outbox rows → insert idempotency result. Typically 5-6 records, a few KB to a few hundred KB total.
- **Multi-table commit** (`/transactions/commit`): the same across N tables atomically; bound N (e.g. 100). Rare.
- **Grant change:** grant rows + audit outbox row, atomically.
- **Lists:** namespaces with tens of thousands of tables, paged.

## Correctness requirements

1. Compare-and-set on a single pointer must be linearizable: two writers must never both succeed from the same base.
2. Multi-record atomic commit as above. Isolation strong enough that the pointer, journal and outbox can never disagree.
3. Read-after-commit: a load that starts after a commit is acknowledged must see it (clients refresh after commit; other engines poll).
4. No loss of an acknowledged commit on node failure. On region failure: zero loss preferred (lost commits whose files exist get garbage-collected as orphans, deleting committed data); the DR standard (PLT-1) is not yet known.
5. Ambiguous outcomes (timeouts, failover mid-write) must be detectable, so the server returns 5xx (commit state unknown), never 409.
6. Durable, ordered change capture for the outbox → Kafka/SIEM (CDC or polling).
7. TTL expiry for idempotency keys.

## Operational requirements

- **Deployment (decided 2026-11-06, D36/D36a):** one catalog, deployed in ONE environment, either on-prem or in AWS; the same code and database must be self-deployable in both. The database never spans on-prem and AWS. Managed-only services (Aurora DSQL, DynamoDB, Spanner) are therefore out as the single choice. A managed offering is acceptable in AWS only if the same engine can be self-hosted on-prem.
- **Durability (decided 2026-11-06, D37):** zero loss of acknowledged commits within the deployment region or metro: 3 AZs or 3 local sites with synchronous quorum writes, at ~1-2 ms per commit. Asynchronous replication to a DR region is acceptable (seconds to minutes of RPO), recovered by a reconcile-from-storage runbook. Cross-region synchronous writes are NOT required.
- Backup and point-in-time recovery that can be aligned with object-store retention.
- Mature Java driver usable from blocking code on virtual threads.
- Licensing acceptable for a large financial firm (commercial licences are fine; note licence changes).
- Operable by an enterprise DBA team at tier 1; availability of expertise.
- Online schema migration and rolling upgrades without downtime.

## Evaluation dimensions (score each candidate)

1. Transaction primitives: CAS, multi-key/multi-row transactions, isolation level, transaction size/time limits, behaviour under contention on one key.
2. Multi-region topology options, RPO/RTO per topology, write-latency cost per commit.
3. Strongly consistent read scaling (can loads scale out without stale pointers?).
4. Change data capture / outbox support.
5. Limits: row/document/value size, transaction size and duration.
6. Ops maturity: backup/PITR, rolling upgrades, observability, Kubernetes/VM deployment, expertise availability.
7. Licence and cost model.
8. Precedent: use as a metadata/catalog store at scale (e.g. FoundationDB under Snowflake's metadata, Spanner under Google services), and in Iceberg catalogs.
9. Disqualifiers for this workload.

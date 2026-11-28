# Iceberg table and view format spec (v1–v4) as it bears on building a REST catalog server

Research date: 2026-11-06. Lens: a from-scratch Iceberg REST catalog for a large regulated US financial firm (many thousands of tables, many engines, multi-region HA; prior lean Java + MongoDB).

Provenance tags: **[read: URL]** = primary source I downloaded and read (spec text, source code, dev-list email, PR description). **[secondary: URL]** = blog or summary. **[inference]** = my reasoning. Dev-list emails were read through the lists.apache.org JSON API; the URL given is the archive permalink for that message. Spec quotations are from the `main` branch of `format/spec.md` as downloaded on 2026-11-06 (last commit touching it: 2026-11-04, #18301).

Main primary sources:
- Table spec: https://raw.githubusercontent.com/apache/iceberg/main/format/spec.md (rendered at https://iceberg.apache.org/spec/)
- View spec: https://raw.githubusercontent.com/apache/iceberg/main/format/view-spec.md
- REST OpenAPI: https://raw.githubusercontent.com/apache/iceberg/main/open-api/rest-catalog-open-api.yaml
- Java core: https://github.com/apache/iceberg/tree/main/core/src/main/java/org/apache/iceberg (files named below)

---

## 1. What table metadata (metadata.json) contains in v1/v2/v3, and which fields the catalog must parse, validate or rewrite on commit

### Takeaway
In practice a REST catalog applies every metadata change itself. The client sends a list of *requirements* (assertions) and *updates* (deltas); the server checks the assertions against current metadata, applies the deltas, and produces the new metadata.json. So the server must understand essentially every top-level field. A minority of fields are **server-derived** and must be recomputed on every commit regardless of what the client sends: `last-updated-ms`, `last-sequence-number`, `last-column-id`, `last-partition-id`, `snapshot-log`, `metadata-log`, `current-snapshot-id`, the IDs of newly added schemas, specs and sort orders, and in v3 `next-row-id`. The data-file tree (manifests and manifest lists) is written by clients and is opaque to the catalog. The catalog stores only paths to it.

### Cited Findings
**Top-level fields by version** [read: spec.md "Table Metadata Fields"]
- v1 required: `format-version`, `location`, `last-updated-ms`, `last-column-id`, `schema` (deprecated), `partition-spec` (deprecated). `table-uuid`, `schemas`, `current-schema-id`, `partition-specs`, `default-spec-id`, `last-partition-id` and `sort-orders` are optional in v1.
- v2 adds as required: `table-uuid`, `last-sequence-number`, `schemas`, `current-schema-id`, `partition-specs`, `default-spec-id`, `last-partition-id`, `sort-orders`, `default-sort-order-id`. It adds the optional `refs` map. `schema` and `partition-spec` "should be omitted" [read: spec.md Appendix E "Version 2"].
- Optional in all versions: `properties`, `current-snapshot-id`, `snapshots`, `snapshot-log`, `metadata-log`, `statistics`, `partition-statistics`.
- v3 adds `next-row-id` (required) and `encryption-keys` (optional). As of 2026-11-02 (PR #18405) the v1–v3 table also lists `stats-fields` (optional, v3 column), described as a list of "stats-only fields used to track stats for derived values" [read: spec.md; commit list via `gh api repos/apache/iceberg/commits?path=format/spec.md`].
- `current-snapshot-id` "must be the same as the current ID of the `main` branch in `refs`". There is "always a `main` branch reference pointing to the `current-snapshot-id` even if the `refs` map is null" [read: spec.md].
- `table-uuid`: "Implementations must throw an exception if a table's UUID does not match the expected UUID after refreshing metadata." `format-version`: "Implementations must throw an exception if a table's version is higher than the supported version" [read: spec.md].
- `properties` is "not intended to be used for arbitrary metadata" [read: spec.md].
- `snapshot-log`: "Each time the current-snapshot-id is changed, a new entry should be added… When snapshots are expired… all entries before a snapshot that has expired should be removed." `metadata-log`: "Each time a new metadata file is created, a new entry of the previous metadata file location should be added… Tables can be configured to remove oldest metadata log entries" [read: spec.md].
- A metadata JSON "may be compressed with GZIP". Some implementations require the suffix `.gz.metadata.json`, and Java can also read `metadata.json.gz` [read: spec.md Appendix C and Appendix F].
- Java writes `-1` for "no current snapshot" on v1/v2 and "will use `null`… for all tables with a version greater than or equal to V3". Other implementations "can accept `-1` as `null`" [read: spec.md Appendix F].

**Snapshots and refs** [read: spec.md "Snapshots", "Snapshot References"]
- Snapshot fields (v1–v3): `snapshot-id`, `parent-snapshot-id`, `sequence-number` (v2+ required), `timestamp-ms`, `manifest-list` (v2+ required), `manifests` (v1 only), `summary` with required `operation` (v2+), `schema-id`. v3 adds the required `first-row-id` and `added-rows`, and the optional `key-id`.
- `operation` ∈ {`append`, `replace`, `overwrite`, `delete`}.
- Ref fields: `snapshot-id`, `type` (`tag`|`branch`), `min-snapshots-to-keep`, `max-snapshot-age-ms`, `max-ref-age-ms`. "The `main` branch never expires."
- Snapshot IDs are generated by the writer. The spec recommends that writers "verify the id does not conflict with existing snapshots" [read: spec.md Appendix F].

**Partition specs, sort orders, IDs** [read: spec.md "Partitioning", "Partition Evolution", "Sorting"]
- "Writers must not create a new partition spec if there already exists a compatible partition spec defined in the table. Partition field IDs must be reused if an existing partition spec contains an equivalent field."
- New partition field IDs are "assigned based on the last assigned partition ID in table metadata". `last-partition-id` is the highest partition-field ID across all specs.
- "Writers are not allowed to commit files with a partition spec that contains a field with an unknown transform." Readers must tolerate unknown transforms; this is required in v3.
- Sort order id `0` is reserved for unsorted.

**How the REST protocol carries changes** [read: rest-catalog-open-api.yaml]
- `CommitTableRequest` = `{identifier?, requirements[], updates[]}`.
- There are 23 `TableUpdate` types: assign-uuid, upgrade-format-version, add-schema, set-current-schema, add-spec, set-default-spec, add-sort-order, set-default-sort-order, add-snapshot, set-snapshot-ref, remove-snapshots, remove-snapshot-ref, set-location, set-properties, remove-properties, set-statistics, remove-statistics, set-partition-statistics, remove-partition-statistics, remove-partition-specs, remove-schemas, add-encryption-key, remove-encryption-key.
- There are 8 `TableRequirement` types: assert-create, assert-table-uuid, assert-ref-snapshot-id, assert-last-assigned-field-id, assert-current-schema-id, assert-last-assigned-partition-id, assert-default-spec-id, assert-default-sort-order-id.
- "Server implementations are required to fail with a 400 status code if any unknown updates or requirements are received."
- `AddSchemaUpdate.last-column-id` is marked "**DEPRECATED for REMOVAL** since it more safe to handle this internally, and shouldn't be exposed to the clients". The server is expected to compute `last-column-id` itself.
- `AssignUUIDUpdate`: "should only be done when creating the table/view. It is not safe to re-assign the UUID."
- `SetPartitionStatisticsUpdate` and `RemovePartitionStatisticsUpdate` were only added to the `TableUpdate` union on 2026-02-04 (#15115), and `VariantType` was only added to the REST schema on 2026-08-02 (#17256). The OpenAPI document has lagged the table spec [read: commit list via `gh api …commits?path=open-api/rest-catalog-open-api.yaml`].

**What Java's reference builder validates and derives** [read: core/.../TableMetadata.java on main]
- `addSnapshot` rejects: no schema, spec or sort order yet; a duplicate snapshot id; and (as `RetryableValidationException`) `sequenceNumber <= lastSequenceNumber` for v2+ unless the snapshot has no parent. On v4+ it rejects a timestamp not after the parent's. On v3+ it rejects a null `first-row-id` and (retryable) `first-row-id < next-row-id`. It then sets `lastSequenceNumber = snapshot.sequenceNumber()` and `nextRowId += snapshot.addedRows()`.
- `upgradeFormatVersion` rejects a version above `SUPPORTED_TABLE_FORMAT_VERSION` (= **4** on main) and any downgrade. `DEFAULT_TABLE_FORMAT_VERSION` is still **2**.
- `addSchemaInternal` rejects `newLastColumnId < lastColumnId` and runs `Schema.checkCompatibility(schema, formatVersion)`. It reuses the ID of an identical existing schema.
- `addPartitionSpecInternal` reuses a compatible spec's ID or creates `max+1`, checks the spec against the current schema, requires sequential IDs in v1, and advances `lastAssignedPartitionId`.
- `build()` stamps `lastUpdatedMillis = System.currentTimeMillis()`. It checks the default spec and sort order against the current schema, appends the previous metadata file to `metadata-log` (trimmed to `write.metadata.previous-versions-max`), and recomputes `snapshot-log`.

### Inferences
- **Field-by-field catalog duty** [inference from the findings above]:
  - *Server-derived, rewritten on every commit:* `last-updated-ms`, `metadata-log`, `snapshot-log`, `last-sequence-number`, `last-column-id`, `last-partition-id`, new schema/spec/sort-order IDs (reuse or allocate), `current-snapshot-id` (mirror of `refs.main`), and `next-row-id` (v3).
  - *Client-supplied but server-validated:* snapshots (unique id, sequence number, parent, v3 row-ID range, v4 timestamp), refs (existing snapshot, `main` must be a branch), schemas (field-ID monotonicity, type/version compatibility), specs and sort orders (sources exist, known transforms for the default spec), `format-version` (no downgrade, not above supported), and `table-uuid` (immutable).
  - *Stored opaquely:* `properties` (except the handful the server interprets), statistics and partition-statistics file pointers, encryption-key blobs, snapshot summaries.
- The catalog **cannot** validate data-level correctness. Examples: whether a "replace" really deleted files that still exist, or whether `added-rows` is correct. It sees only a manifest-list path. Those checks are the client's job under "Commit Conflict Resolution and Retry". The server's guarantee is ref-level optimistic concurrency.
- A few properties change server behaviour: `write.metadata.previous-versions-max`, `write.metadata.delete-after-commit.enabled`, `write.metadata.compression-codec`, `write.metadata.path`, and `format-version` on create. An enterprise catalog should treat the rest as governed key-value data, and may want an allow/deny policy on keys. That policy is an operator need, not a spec need.

### Gaps
- I did not exhaustively diff every `MetadataUpdate.applyTo` against every spec rule. Section 6 records Gravitino's claim that some updates are applied unchecked; I did not independently confirm each case.

---

## 2. What format v3 changed, when it was adopted, and what new catalog duties it added

### Takeaway
v3 was adopted by community vote in May 2025. The spec marked it complete on 2025-05-29. Most v3 features live in data and manifest files: deletion vectors, variant, geo, nanosecond timestamps, and default values mostly appear only in schema JSON. v3 adds three real catalog-side duties:
1. Maintain `next-row-id`: validate each snapshot's `first-row-id` against it and advance it by `added-rows`. This applies on every branch.
2. Store `encryption-keys` and the snapshot `key-id`.
3. Correctly parse, validate and round-trip the new schema constructs: new types, `initial-default`/`write-default`, and multi-arg `source-ids`.

The catalog does **not** assign `first-row-id`. The client assigns it, because the value is baked into the manifest list the client already wrote. The server can only accept it or force a client retry.

### Cited Findings
- **Adoption.** Ryan Blue opened "[VOTE] Adopt the v3 spec changes" on 2025-05-19. Listed scope: default values; variant, geospatial, timestamp(9) and unknown types; row lineage; synchronously maintained binary deletion vectors; "Table encryption key tracking"; "Table metadata support for future multi-argument transforms". "Adoption doesn't change the default table version." The vote "signals that there will be no further break changes in v3" [read: https://lists.apache.org/thread/9oncq0j0222nrm6scvm89xdq3gozg24p]. PR #13175 "Spec: Mark version 3 as completed" merged 2025-05-29 and cites that vote [read: https://github.com/apache/iceberg/pull/13175].
- **Spec summary of v3.** "New data types: nanosecond timestamp(tz), unknown, variant, geometry, geography; Default value support for columns; Multi-argument transforms for partitioning and sorting; Row Lineage tracking; Binary deletion vectors; Table encryption keys" [read: spec.md "Version 3"].
- **Java releases.** 1.10.0 shipped 2025-09-11, 1.11.0 on 2026-05-19/20, 1.12.0 on 2026-09-29/30. 1.10.0 included "Add table metadata keys for encryption (#12927)" and row-lineage reader support [read: https://raw.githubusercontent.com/apache/iceberg/main/site/docs/releases.md; GitHub releases API]. Java main still defaults new tables to v2 (`DEFAULT_TABLE_FORMAT_VERSION = 2`) [read: TableMetadata.java].
- **Row lineage, catalog-visible rules.**
  - "A snapshot's `first-row-id` is assigned to the table's current `next-row-id` on each commit attempt. If a commit is retried, the `first-row-id` must be reassigned based on the table's current `next-row-id`."
  - "When the new snapshot is committed, the table's `next-row-id` must also be updated (even if the new snapshot is not in the main branch)."
  - "When a table is upgraded to v3, its `next-row-id` is initialized to 0 and existing snapshots are not modified."
  - `next-row-id` "should be increased by the sum of `record_count` for all data files that will be assigned a `first_row_id`"; the snapshot's `added-rows` "can be used safely to increment the table's `next-row-id` during a commit."
  
  [read: spec.md "Row Lineage", "Snapshot Row IDs", "Table Metadata Fields"]
- **Java server-side behaviour for row lineage.** `CatalogHandlers.commit` catches `RetryableValidationException` from `applyTo` and rethrows it as `CommitFailedException("Validation failed, please retry")`. The code comment reads: "the commit includes stale values (e.g. sequence number or first-row-id behind the current table state)… Server-side retry won't help since the stale values are in the request itself" [read: core/.../rest/CatalogHandlers.java]. PyIceberg's `_apply_table_update(AddSnapshotUpdate)` makes the same `first_row_id < next_row_id` check and advances `next_row_id` by `added_rows` [read: https://raw.githubusercontent.com/apache/iceberg-python/main/pyiceberg/table/update/__init__.py]. iceberg-rust's `TableMetadataBuilder::add_snapshot` does likewise and resets `next_row_id` to `INITIAL_ROW_ID` on upgrade to V3 [read: https://raw.githubusercontent.com/apache/iceberg-rust/main/crates/iceberg/src/spec/table_metadata_builder.rs].
- **Default values.** "The `initial-default` must be set when a field is added and cannot change." "When a required field is added, both defaults must be set to a non-null value." "All columns of `unknown`, `variant`, `geometry`, `geography`, and `file` types must default to null" [read: spec.md "Default values", "Schema Evolution"].
- **Type promotion (v3+).** `unknown` → any type, and `date` → `timestamp`/`timestamp_ns` are now allowed. Promotion is forbidden when a partition transform's output would change [read: spec.md "Schema Evolution"]. Gravitino's code says `TypeUtil#isPromotionAllowed` in Java does not implement the date promotions [read: Gravitino IcebergCommitValidation.java, see §6]. This is a Gravitino claim and I have not verified it.
- **Multi-arg transforms.** "`source-ids` was added and must be written in the case of a multi-argument transform. `source-id` must be written in the case of single-argument transforms" [read: spec.md Appendix E v3].
- **Encryption keys.** Each `encryption-keys` entry holds `key-id` (string), `encrypted-key-metadata` (base64, "can be a wrapped format specific to the table's KMS provider"), optional `encrypted-by-id`, and `properties`. Snapshot `key-id` is the "ID of the encryption key that encrypts the manifest list key metadata" [read: spec.md "Encryption Keys", "Snapshots"].
- **Encryption: integrity of metadata.json.** Java PR #14685 (merged 2025-12-08) added a Hive-only `metadata_hash` property "stored exclusively in HMS" so that `HiveTableOperations` can "ensure that the metadata.json has not been tampered with when table encryption is used" [read: https://github.com/apache/iceberg/pull/14685]. Yufei Gu (2026-02-10): making metadata.json optional "could also provide better protection for encrypted Iceberg tables, since metadata.json files are plain text today" [read: https://lists.apache.org/thread/xyloxtxxl0fpn3b3y915zj7pmtnn1fjf].
- **Encryption: keys travel with the snapshot.** PR #17984 (merged 2026-09-16) makes `SnapshotProducer` commit manifest-list encryption keys together with the snapshot. Before it, "Concurrent refreshes can replace a writer's encryption manager, allowing a snapshot to commit without the keys needed to read its manifest list" [read: https://github.com/apache/iceberg/pull/17984].
- **Deletion vectors** are Puffin blobs tracked in manifests (`referenced_data_file`, `content_offset`, `content_size_in_bytes`). "Writers are not allowed to add new position delete files to v3 tables." v3 also makes `dv_count` and related fields required in the partition-statistics file schema [read: spec.md Appendix E v3, "Partition Statistics"].

### Inferences
- The catalog-side v3 work is small but sharp. If `next-row-id` is mishandled, the damage is silent: overlapping row IDs break CDC and audit lineage, which is exactly what a financial firm would want row lineage for. The server must advance `next-row-id` atomically with the ref swap, including for commits to non-main branches. If the commit loop retries server-side after a concurrent commit, it must re-validate `first-row-id` against the *refreshed* `next-row-id`. The Java loop does this by re-applying updates to `taskOps.refresh()`.
- Encryption makes the catalog part of the trust chain. In v1–v3 an encrypted table still has a plaintext metadata.json, whose `encryption-keys` contains wrapped key material and whose manifest-list pointers are visible. A catalog serving encrypted tables should (a) control who can write metadata.json paths and (b) consider storing a content hash of each committed metadata.json, as Java's Hive integration now does. No REST equivalent exists in the spec today; see Gaps.
- None of the v3 features (row lineage, DVs, default values) need the catalog to read data or manifest files.

### Gaps
- I found no REST-spec mechanism equivalent to Hive's `metadata_hash` for metadata.json integrity.
- A related vote, "[VOTE] [SPEC] Document standard encryption key metadata", was still being revised in Sept 2026. I read only one reply (Gidon Gershinsky, 2026-09-03, favouring a re-vote) [read: https://lists.apache.org/thread/obvxs0tv82oznrltnmwo2swxh88wqlrz]. Its outcome is unknown.

---

## 3. Status of v4 / next-generation metadata as of November 2026, and how it shifts work into the catalog

### Takeaway
v4 is "under active development and has not been formally adopted" [read: spec.md]. A large part of it has already been merged into `spec.md` on main during 2026: root manifest / adaptive metadata tree, relative paths, content stats, constraints, the `file` type, monotonic snapshot timestamps, and the equality-delete prohibition.

For a catalog, three changes matter most:
1. The table `location` becomes optional and "intended to be managed and supplied by a catalog", because metadata paths can be relative.
2. Snapshots carry `root-manifest` instead of `manifest-list`, which requires REST changes. The emerging consensus is a new `v2` loadTable endpoint.
3. Leading dev-list participants (Ryan Blue, Anton Okolnychyi, Amogh Jahagirdar, Yufei Gu, Fokko Driesprong, Eduard Tudenhöfner) favour **relaxing the requirement to store metadata.json at all** for v4, provided the catalog can produce the full metadata on demand. That would make the catalog the system of record for table state. It is not yet spec text.

### Cited Findings
**Spec text on main (v4 sections)** [read: spec.md]
- v4 summary: "adaptive metadata tree, enabling efficient small commits, column updates, and columnar statistics representation"; data files and their DV "stored in a combined entry"; "relative locations"; "Writing new equality deletes is no longer allowed"; new `file` type; table "constraints".
- "In v1-v3, the manifests that make up a snapshot are stored in a manifest list file… Starting in v4, manifest lists are replaced by a single root manifest per snapshot, which can contain references to data files, data manifests, and delete manifests." v4 root and leaf manifests are Parquet.
- Relative paths: v4 "adds support for relative locations in metadata, enabling tables to be relocated without rewriting metadata files… The table's location may be fixed in table metadata or inferred, but is intended to be managed and supplied by a catalog." v4 `location` is "_optional_… Must be an absolute path when present." "When not present, the table location must be managed externally and provided when loading the metadata." "How the table location is persisted or determined when not specified in metadata is not a table-level concern; catalogs should provide a table's location."
- v4 snapshot: `root-manifest` is required and `manifest-list` is gone; optional `constraint-statuses`. "When committed while the table is at version 4 or higher, a snapshot's `timestamp-ms` must be strictly greater than its parent snapshot's `timestamp-ms` on the same branch."
- v4 metadata adds `last-constraint-id` and `constraints` (types `check`, `unique`, `primary-key`; `enforced` flag). "Upgrading a v2 or v3 table to v4 rewrites the table's `identifier-field-ids` as a `primary-key` constraint that is not enforced", with `timestamp-ms` set to "the time of the upgrade". Constraint statuses are "writer-reported". "A snapshot's `constraint-statuses` must not be modified after the snapshot is created."
- The file-system (rename-based) commit scheme "is **deprecated** and will be removed in version 4 of this spec. The scheme is **unsafe** in object stores."

**Dates the v4 pieces landed in spec.md** [read: `gh api repos/apache/iceberg/commits?path=format/spec.md`]

| Date | PR | Change |
|---|---|---|
| 2026-05-20 | #14234 | v4 content stats |
| 2026-05-23 | #15630 | relative paths (the "[VOTE] Spec: Addition of Relative Paths to v4" thread opened ~2026-05-19) |
| 2026-09-29 | #17783 | forbid new equality deletes |
| 2026-10-12 | #16294 | monotonic snapshot timestamps |
| 2026-10-14 | #16025 | adaptive metadata tree entry structures |
| 2026-10-19 | #17918 | `file` type |
| 2026-10-27 | #18585 | file-level `tight_stats` |
| 2026-10-28 | #17822 | constraints |
| 2026-11-02 | #18405 | stats-only fields |

**Java implementation status**
- `SUPPORTED_TABLE_FORMAT_VERSION = 4` on main, with `MIN_FORMAT_VERSION_OPTIONAL_LOCATION = 4`, `MIN_FORMAT_VERSION_PARQUET_MANIFESTS = 4` and `MIN_FORMAT_VERSION_MONOTONIC_TIMESTAMPS = 4` [read: TableMetadata.java].
- `TableMetadata`'s constructor has no constraints or stats-fields yet, so Java lags the spec's newest v4 fields [read: TableMetadata.java build()].
- 1.11.0 (May 2026): "Introduce foundational types for V4 manifest support", "Add builders for v4 structs". 1.12.0 (Sept 2026): "Add v4 manifest reader", "Resolve relative paths in V4 manifest reader", "Add V4 location relativization utilities", "Allow writing Parquet and Avro manifests in the V4 layout" [read: releases.md].

**Dev-list direction**
- *Origin.* "[DISCUSS] v4 - One file commits", Ryan Blue, 2025-05-29: allow "the manifest list layer to store data and delete files. As a result, only one file write would be needed before committing" [read: https://lists.apache.org/thread/2gvqy1ho959xlws6vrkp26nsxmbx15o4].
- *Scope of that thread.* Ryan Blue, 2025-05-30: "table-level metadata and the content metadata tree are separate concepts. We've addressed table-level metadata and the snapshot list by introducing change-based commits in the REST protocol. This work aims to make updates to the content metadata tree more lightweight" [read: https://lists.apache.org/thread/5nnpll2gqc2w2f547of29l0lvdryg7vk]. Yufei Gu, same thread: "The community is also working on the lift of a mandatory physical metadata.json file in the storage, in which case, the REST catalog doesn't have to deal with file IO anymore. Metadata.json could live within a key-value, RDMS or even just in memory" [read: https://lists.apache.org/thread/whb77qnklprzwycyps8xsdwnyz1oznf8].
- *"[DISCUSS] metadata.json in v4?"* Anton Okolnychyi, 2026-02-10: streaming suffers from "the increasing size of the root metadata file"; proposes making "the generation of the root metadata file optional in the spec" or offloading parts of it [read: https://lists.apache.org/thread/l1onvv5p5cq2vtql9g8w5fbxc1hhd65l]. Anton, 2026-02-11: "a few good ways to make the generation of the root metadata optional with some smarter catalogs… without sacrificing portability if catalogs are required to generate the root metadata file on demand" [read: https://lists.apache.org/thread/b4rzhgmg5vh8mmob7qopxgkfko88nbpr]. On 2026-02-12 both Fokko Driesprong and Eduard Tudenhöfner preferred making metadata.json optional over splitting snapshot history into extra files. Fokko: "it makes sense to be able to request the metadata json if needed." Eduard: "focus on making the metadata optional but have a way of generating it on demand when needed" [read: https://lists.apache.org/thread/g7ld940blj8o3rcyq6sfhw3tfrxng95f; https://lists.apache.org/thread/n6k4gfpl6wck046nyljo56g50qzp930r].
- *"[DISCUSS] Offloading Snapshots from Metadata.json"*, Ryan Blue, 2026-04-16: "the problem of large metadata.json files is largely solved by the REST protocol, which does not need to send snapshots to clients. I agree with Anton's suggestion to relax the requirement that the metadata.json file has to be stored somewhere (for v4). As long as catalogs are required to be able to produce the full content of metadata.json when loading the table for a client requesting all snapshots, we don't need to worry about storing the file." He also notes "the current Java REST implementation still requests all snapshots to commit, which we should fix" [read: https://lists.apache.org/thread/5f5p4rcxyq7j6rt7yts68shoqmm75478]. Amogh Jahagirdar agreed: "much better for us to make progress on relaxing the requirement for metadata.json storage" [read: https://lists.apache.org/thread/06kl7plln7wrbf50f50c2tkk8hkh4pwy].
- *"[DISCUSS] Iceberg v4 Spec Scope and Priorities"*, Daniel Weeks, 2026-08-11. Workstreams: Content Metadata Updates (AMT / single-file commits, column statistics, relative paths, column append); Table Features (check constraints, default value expressions, generated columns); Data Types (file, vector). AMT and stats are "well into implementation" [read: https://lists.apache.org/thread/ko8cs3tgol97f0m20yozchpxlotzl1mj]. Steven Wu added monotonic snapshot timestamps and a `_last_updated_timestamp_ms` metadata column [read: https://lists.apache.org/thread/qz122ct8xrp5g8wbydj8b1mqs6jlsnf4].
- *"[DISCUSS] Initial IRC changes needed for Iceberg V4"*, Dhruv Arya, 2026-08-13. Proposed: (1) `root-manifest` in Snapshot, with `manifest-list` unset for v4; (2) `table-location` in LoadTableResult, CommitTableResponse and UnregisterTableResult; (3) "Make metadata-location in CommitTableResponse optional: This is for catalog managed tables" [read: https://lists.apache.org/thread/6y446mbtrho2r54cbd2x9gzyb13jjlbl]. Replies:
  - Daniel Weeks (2026-08-18): "add a `v2::loadTable` endpoint and require all v4 tables to be loaded through that mechanism… only load table requires a version bump since committing a table still use the normal snapshot update" [read: https://lists.apache.org/thread/2y5vj6vsyd49p4vsnqxj75tgy0jxxfwq].
  - Amogh Jahagirdar (2026-08-20): "the v4 spec should be voted on as a pre-requisite before voting on the v2 REST spec" [read: https://lists.apache.org/thread/9wdwjhog9qlv6jccshbmstjy109s2wol].
  - Yufei Gu (2026-08-24): "The v1 `loadTable` endpoint MUST NOT return v4 table metadata", suggesting a documented error (e.g. 406) when a v1 client asks for a v4 table [read: https://lists.apache.org/thread/fxftrwg17dc4b2szhjf4y79x11z7kylp].
  - Daniel Weeks (2026-08-24) agreed on an error response but was "uncertain about taking on something like partial metadata loading as part of the v2 endpoint" [read: https://lists.apache.org/thread/1sv1fw7zz5rrglxx0vhwnhdj4cmmnnx8].
- *REST PRs.* #17543 ("IRC spec changes for v4", new V2 schemas and /v2 endpoints) was closed 2026-09-22 without merge. #17642 ("relax responses in place") is open, last updated 2026-09-27. It proposes `Snapshot` oneOf `root-manifest`/`manifest-list`, `TableMetadata` with max `format-version` 4 and optional `location`, `table-location` on load/unregister/commit responses, and `metadata-location` no longer required in `CommitTableResponse` ("absent for catalog-managed tables"). Its body calls itself "a proposal to drive discussion; v4 is still in development" [read: `gh pr view 17543/17642 -R apache/iceberg`].
- *Secondary view (July 2026).* "There is no v4 spec you can set on a table today". The article warns a REST catalog doing planning over inlined entries risks "turning it into a small query engine" [secondary: https://dev.to/alexmercedcoder/the-state-of-apache-iceberg-v4-in-july-2026-what-the-dev-list-tells-us-about-the-formats-next-475g].

### Inferences
How v4 shifts work into the catalog:
1. **Location authority.** With relative paths, the catalog-supplied table location is load-bearing. If the catalog returns the wrong `table-location`, every relative path resolves wrongly. Table relocation (DR failover to another bucket or region) becomes a catalog-only operation. This is a real benefit for multi-region HA.
2. **Catalog-managed metadata.** If "metadata.json optional" lands, a v4 commit could be: the client writes one root-manifest Parquet file, then the catalog commits a snapshot delta into its own store with no object-store IO. The catalog then *must* be able to render a spec-compliant metadata.json on demand, for portability, static-table readers and engines that read the file directly (Yufei's caveat). That is a strong argument for storing table metadata in the catalog database in a normalized or delta form from day one, rather than only as a pointer to a file.
3. **Upgrade-time rewrites.** If the server applies `upgrade-format-version` to 4, it must perform the spec-mandated rewrite of `identifier-field-ids` into an unenforced `primary-key` constraint and allocate a `constraint-id`. Java's builder does not do this yet, because it has no constraint fields.
4. **New validations.** Monotonic snapshot timestamps per branch: Java enforces this as a non-retryable `ValidationException`, so client clock skew yields hard failures. Constraint-ID allocation and constraint/schema coupling: "A schema change that removes a constraint-referenced field must be rejected unless the constraint is removed in the same change." Enforcing constraints themselves stays client-side.
5. **API versioning.** Expect a `/v2/.../tables/{t}` load endpoint gated by the config endpoint's advertised endpoints. A v1 load of a v4 table must be refused with an error.
6. **Not shifted.** AMT does not make the catalog write manifests. Clients still write the root manifest. Server-side scan planning (already in the REST spec) is where catalog CPU cost would grow under v4, because it requires reading Parquet root manifests with inlined entries.

### Gaps
- No vote to adopt v4 had been called as of 2026-11-06 in the threads I read. There is no published target date ("Nobody knows" [secondary: dev.to, July 2026]).
- I did not find a written proposal (doc or PR) for "catalog-managed / optional metadata.json". The direction is in emails and in REST PR #17642's wording only. Anton's 2026-02-12 call "is anyone willing to create a proposal?" may still be unanswered.
- The AMT design docs (Google Docs) linked from the threads were not read.
- Column-stats details (content stats, `tight_stats`, stats-only fields) were not studied beyond the table-metadata impact. `stats-fields` is the only part that touches table metadata.

---

## 4. View spec: what a catalog must store and validate

### Takeaway
View metadata is a small JSON document. It holds `view-uuid`, `format-version` (must be 1), `location`, `schemas`, `current-version-id`, `versions`, `version-log` and `properties`. Each version is immutable and holds one or more SQL representations, at most one per dialect. The catalog stores and atomically swaps it exactly like table metadata. The catalog never parses the SQL. Validation is structural: schema references, dialect uniqueness, UUID immutability, history trimming.

### Cited Findings
- **Fields.** `view-uuid` ("must throw an exception if a view's UUID does not match"), `format-version` ("must be 1"), `location`, `schemas`, `current-version-id`, `versions` (count kept per `version.history.num-entries`), `version-log`, optional `properties` ("not intended to be used for arbitrary metadata") [read: view-spec.md].
- **Version.** `version-id`, `schema-id`, `timestamp-ms`, `summary` (`engine-name`, `engine-version`), `representations`, optional `default-catalog` (when null "the catalog in which the view is stored must be used"), and required `default-namespace`. "View versions are immutable… If a view definition changes (or new representations are to be added), a new version must be created." SQL representation = `{type: "sql", sql, dialect}`, "only one SQL representation per dialect" [read: view-spec.md].
- **Version log.** "A version can appear multiple times in the version log, indicating that the view definition was rolled back" [read: view-spec.md].
- **Commit model.** The same atomic pointer swap "delegated to the metastore" [read: view-spec.md "Overview", "Metadata Location"].
- **Java `ViewMetadata.Builder` checks:**
  - `SUPPORTED_VIEW_FORMAT_VERSION = 1`
  - "Cannot add version with unknown schema"
  - "Cannot add multiple queries for dialect" (case-insensitive)
  - "Cannot reassign uuid"
  - "Invalid view: no versions were added"
  - history trimmed by `version.history.num-entries` (default 10)
  - a replace that drops a dialect fails unless `replace.drop-dialect.allowed=true` (default false)
  - view metadata compression defaults to `gzip`; tables default to `none`
  
  [read: core/.../view/ViewMetadata.java; core/.../view/ViewProperties.java; TableProperties.java]
- **REST view operations.** `ViewUpdate` types: assign-uuid, upgrade-format-version, add-schema, set-location, set-properties, remove-properties, add-view-version, set-current-view-version. `CommitViewRequest` requirements are optional [read: rest-catalog-open-api.yaml]. A register-view endpoint was added 2026-01-17 (#14869) [read: OpenAPI commit list].
- **UDFs.** A UDF spec (`format/udf-spec.md`) follows the same pattern: a "self-contained metadata file", immutable, "Catalogs use an atomic swap" [read: https://raw.githubusercontent.com/apache/iceberg/main/format/udf-spec.md]. REST list/load function endpoints were added 2026-06-07 (#15180) [read: OpenAPI commit list].

### Inferences
- Views are cheap for the catalog: no row lineage, no snapshots, and a bounded version list (10). The same commit engine (assertions, apply, CAS) can serve tables, views and future UDFs if it is written against a generic "metadata document + pointer" abstraction.
- Enterprise needs beyond the spec: view lineage and dependency tracking, and SQL dialect governance. These require SQL parsing, which the spec deliberately leaves out of the catalog. Treat them as optional add-ons, not core.
- The spec requires rejecting a v1 view load when the stored `format-version` > 1. Today that never triggers.

### Gaps
- Materialized-view spec status was not researched. There were MV threads in 2024; I did not read their outcome.

---

## 5. Commit model: optimistic concurrency, what "atomic" means, metadata.json growth, and loadTable payload size

### Takeaway
"Atomic" in the spec means exactly one thing: a compare-and-swap of the table's current metadata pointer from the base version to the new version. Exactly one of two concurrent commits based on the same version wins. The spec deliberately does not standardize how. A REST server therefore needs (1) a per-table linearizable CAS in its store and (2) a server-side "validate requirements, apply updates, CAS" loop that may retry internally when the conflict is benign.

metadata.json grows without bound in snapshots, schemas and specs until clients run maintenance. Only `metadata-log` is capped, at 100 entries by default. Old metadata.json *files* are kept by default. loadTable returns the whole document unless `snapshots=refs` is requested. Java clients still load all snapshots in order to commit.

### Cited Findings
- **Spec on atomicity.**
  - "An atomic swap of one table metadata file for another provides the basis for serializable isolation."
  - "Each table metadata change creates a new table metadata file that is committed by an atomic operation… This produces a linear history of table versions and ensures that concurrent writes are not lost."
  - "The atomic operation used to commit metadata depends on how tables are tracked and is not standardized by this spec."
  - The metastore pattern is "a pointer in a metastore or database that is updated with a check-and-put operation", with files named `<V>-<random-uuid>.metadata.json`.
  
  [read: spec.md "Optimistic Concurrency", "Table Metadata", "Metastore Tables"]
- **Client-side retry semantics.** "Append operations have no requirements and can always be applied." Replace and delete operations "must verify that the files that will be deleted are still in the table." "Table schema updates and partition spec changes must validate that the schema has not changed between the base version and the current version" [read: spec.md "Commit Conflict Resolution and Retry"].
- **Sequence numbers are client-assigned and baked into files.** "To change a sequence number for a retry, only the manifest list must be rewritten" [read: spec.md "Sequence Numbers"]. So the server cannot fix up a stale sequence number or `first-row-id`; it must bounce the commit to the client (see §2).
- **Java reference commit loop (`CatalogHandlers.commit`).**
  1. Retries up to `COMMIT_NUM_RETRIES_DEFAULT` (4) with exponential backoff, only on `CommitFailedException`.
  2. Each attempt uses `taskOps.refresh()` after the first.
  3. Validates requirements; a failure is wrapped to avoid retry and returned to the client.
  4. Applies updates to `TableMetadata.buildFrom(base)`. A `RetryableValidationException` is returned to the client as `CommitFailedException`.
  5. Skips the commit if `updated.changes().isEmpty()`, then calls `taskOps.commit(base, updated)`.
  
  [read: core/.../rest/CatalogHandlers.java]
- **Java metastore persistence.** `BaseMetastoreTableOperations` names files `String.format("%05d-%s%s", newVersion, UUID.randomUUID(), fileExtension)`. After `doCommit` it calls `CatalogUtil.deleteRemovedMetadataFiles(io(), base, metadata)` [read: core/.../BaseMetastoreTableOperations.java]. The JDBC catalog's CAS (`V0_DO_COMMIT_SQL`) is a single conditional update: `UPDATE <table> SET <metadata location> = ?, <previous metadata location> = ? WHERE <catalog name> = ? AND <namespace> = ? AND <table name> = ? AND <metadata location> = ?`, built from column-name constants [read: core/.../jdbc/JdbcUtil.java].
- **Growth controls (Java defaults).**
  - `write.metadata.previous-versions-max` default **100**, applied via `addPreviousFile`, which trims `metadata-log` to that size (minimum 1).
  - `write.metadata.delete-after-commit.enabled` default **false**.
  - `write.metadata.compression-codec` default `none`.
  - `commit.retry.num-retries` default 4.
  
  [read: TableProperties.java; TableMetadata.java]
- **Pruning metadata.** Snapshots leave metadata only through `remove-snapshots` (client-side expire). Schemas and specs can be removed via `remove-schemas` and `remove-partition-specs`. 1.10.0 exposed `cleanExpiredMetadata` in `ExpireSnapshots` [read: rest-catalog-open-api.yaml; releases.md 1.10.0].
- **loadTable payload controls.** The `snapshots` query parameter takes `all` (default) or `refs` ("load all snapshots referenced by branches or tags"). `If-None-Match` yields `304 Not Modified` [read: rest-catalog-open-api.yaml]. 1.11.0 added "Freshness-aware table loading in REST catalog (#14398)" [read: releases.md].
- **Known pain.**
  - The 2024 "[PROPOSAL] Partially Loading Metadata - LoadTable V2" (Haizhou Zhao, 2024-10-10) cited "tables with big metadata (e.g. due to huge list of snapshot/metadata log, complicated schema, etc.)" [read: https://lists.apache.org/thread/qocy0w2y1wk1775x2n68mt9vompcbhmc].
  - Daniel Weeks replied that the "refs only approach does fall back to a full metadata load when committing… due to the complexity of changing the TableMetadata implementation, not necessarily a limitation of the REST spec" [read: https://lists.apache.org/thread/0xftgyhlr9m9p5fjkk2ykt9wsbrlml3b].
  - In 2026 Ryan Blue still said "the current Java REST implementation still requests all snapshots to commit" [read: https://lists.apache.org/thread/5f5p4rcxyq7j6rt7yts68shoqmm75478].
  - Anton Okolnychyi named streaming appends as the workload that suffers from root-metadata growth [read: https://lists.apache.org/thread/l1onvv5p5cq2vtql9g8w5fbxc1hhd65l].
- **Idempotency.** The REST spec added an `Idempotency-Key` header (2025-11-20, #14196). 1.12.0 clients "retry POST requests carrying an `Idempotency-Key` on retriable errors (408, 500, 502, 503, 504)" [read: OpenAPI commit list; releases.md 1.12.0]. `CatalogHandlers` implements idempotency with a **static in-memory** `ConcurrentMap` (`IDEMPOTENCY_STORE`, 30-minute lifetime) [read: CatalogHandlers.java].
- **REPLACE conflicts.** Polaris added server-side conflict resolution for REPLACE (compaction) snapshots: on an `assert-ref-snapshot-id` failure it can roll back intervening no-op REPLACE snapshots, gated by `polaris.internal.conflict-resolution.by-operation-type.replace` [read: Polaris CatalogHandlerUtils.java].

### Inferences
- **MongoDB fit.** A single-document conditional update (`findOneAndUpdate` filtered on `{_id, metadataVersion}` or on the current metadata location) is a correct per-table CAS, provided writes use majority write concern and the read that builds `base` sees majority-committed data. Multi-table transactions are a different problem (covered elsewhere).
- **Two-phase write ordering for v1–v3.** Write the new metadata.json to object storage first, then CAS the pointer. On CAS failure, delete the orphan or leave it for GC. This is the pattern `BaseMetastoreTableOperations` follows.
- **Commit-state-unknown.** If the CAS outcome is unknown (timeout), the server must reconcile before answering. Java's `checkCommitStatus` checks whether the new location became current or appears in `metadata-log`. Returning 5xx yields "commit state unknown" on the client.
- **Hidden scaling cost.** With `delete-after-commit` false, every commit leaves a full-size metadata.json in object storage forever, until orphan-file cleanup runs. For streaming tables with thousands of snapshots, each file can be MBs. An enterprise catalog should (a) set a sane default for previous-versions-max and delete-after-commit, or perform its own retention, and (b) keep its own immutable commit history in the database, because `metadata-log` is trimmed and is not an audit log.
- **loadTable payload.** Response size scales with snapshots × summary size plus schemas. Supporting `snapshots=refs` and ETag/304 is cheap and protects the server. Enforcing snapshot-retention hygiene (client-side expiration) is an operational policy question, not a spec one.

### Gaps
- I found no measured statistics on metadata.json size distributions, nor on loadTable latency against size. PR #17164 ("Report metadata file size in commit metrics") was closed in July 2026; I did not check whether it merged.

---

## 6. Iceberg Java library reuse server-side; how Polaris, Nessie, Gravitino reuse it; API-stability risk; non-Java libraries

### Takeaway
iceberg-core already contains a complete server-side commit "kernel":
- `TableMetadata` + `TableMetadata.Builder` (all spec validations and derived fields)
- `MetadataUpdate.applyTo(builder)` for every REST update type
- `UpdateRequirement.validate(base)` for every assertion
- `TableMetadataParser` / `MetadataUpdateParser` / `RESTSerializers` for JSON
- `ViewMetadata` for views
- `CatalogHandlers` as a reference request flow

Polaris and Gravitino use this kernel. Nessie reimplemented it in its own model; Lakekeeper (Rust) uses iceberg-rust's equivalent. The classes are public but live in iceberg-core. Iceberg's policy calls core "common and internal code" that "can evolve within a major release" with only a one-minor-release deprecation cycle, and revapi records dozens of accepted breaks per release. Treat it as a pinned, actively tracked dependency, not a stable API.

### Cited Findings
- **The kernel classes.**
  - `MetadataUpdate` is a public interface with `default void applyTo(TableMetadata.Builder)` and `applyTo(ViewMetadata.Builder)`. Its nested classes cover AssignUUID, UpgradeFormatVersion, AddSchema, SetCurrentSchema, AddPartitionSpec, SetDefaultPartitionSpec, RemovePartitionSpecs, RemoveSchemas, AddSortOrder, SetDefaultSortOrder, SetStatistics, RemoveStatistics, SetPartitionStatistics, RemovePartitionStatistics, AddSnapshot, RemoveSnapshots, RemoveSnapshotRef, SetSnapshotRef, SetProperties, RemoveProperties, SetLocation, AddViewVersion, SetCurrentViewVersion, AddEncryptionKey and RemoveEncryptionKey [read: core/.../MetadataUpdate.java].
  - `UpdateRequirement` is a public interface with `validate(TableMetadata)` / `validate(ViewMetadata)`, covering AssertTableDoesNotExist, AssertTableUUID, AssertViewUUID, AssertRefSnapshotID, AssertLastAssignedFieldId, AssertCurrentSchemaID, AssertLastAssignedPartitionId, AssertDefaultSpecID and AssertDefaultSortOrderID [read: core/.../UpdateRequirement.java].
  - `UpdateRequirements.forCreateTable/forReplaceTable/forUpdateTable/forReplaceView` derive requirements from updates; this is client-side logic [read: core/.../UpdateRequirements.java].
- **What `CatalogHandlers.updateTable` assumes.**
  - It needs an Iceberg `Catalog` whose `loadTable` returns a `BaseTable` exposing `TableOperations` ("Cannot wrap catalog that does not produce BaseTable").
  - Creates go through `catalog.buildTable(ident, EMPTY_SCHEMA).createOrReplaceTransaction()`, which the code itself calls "a hacky way to get TableOperations for an uncommitted table".
  - "create transactions do not retry".
  - It also hosts static in-memory planning and idempotency state.
  
  [read: CatalogHandlers.java]
- **API-stability policy.**
  - `iceberg-api` gets "Major Version Deprecations Required".
  - `iceberg-common`, `iceberg-core`, `iceberg-data`, `iceberg-orc` and `iceberg-parquet` get "Minor Version Deprecations Required": "Changes to public interfaces and classes… require a deprecation cycle of one minor release. These projects contain common and internal code used by other projects and can evolve within a major release."
  
  [read: https://raw.githubusercontent.com/apache/iceberg/main/site/docs/contribute.md "Semantic Versioning"]
- **Revapi evidence.**
  - `build.gradle` applies revapi to `iceberg-api`, `iceberg-core`, `iceberg-parquet`, `iceberg-orc`, `iceberg-common` and `iceberg-data` (baseline 1.12.0) [read: https://raw.githubusercontent.com/apache/iceberg/main/build.gradle].
  - `.palantir/revapi.yml` records accepted iceberg-core breaks, keyed by baseline: 22 against 1.10.0, 88 against 1.11.0 (mostly REST scan-planning response builders), and 12 so far against 1.12.0.
  - Examples: removal of a `TableMetadataParser.read(FileIO, …)` overload ("Removing deprecated code for 1.11.0"); removal of a `CatalogHandlers.loadTable(Catalog, …)` overload ("Removed deprecated functionality scheduled for removal in 1.12.0"); `TableMetadata` serialization changes; internal `MergingSnapshotProducer` helpers removed as "not an extension point".
  
  [read: https://raw.githubusercontent.com/apache/iceberg/main/.palantir/revapi.yml]
- **Polaris.** `CatalogHandlerUtils` header: "CODE_COPIED_TO_POLARIS… Copied from CatalogHandlers as of Iceberg 1.8.0. The registerView… method was copied from Iceberg 1.11.0." It keeps iceberg-core's `TableMetadata.buildFrom`, `UpdateRequirement.validate` and `MetadataUpdate.applyTo`, and adds the REPLACE-rollback conflict resolution plus its own `RetryableValidationException` handling [read: https://raw.githubusercontent.com/apache/polaris/main/runtime/service/src/main/java/org/apache/polaris/service/catalog/iceberg/CatalogHandlerUtils.java].
- **Gravitino.**
  - `IcebergCatalogWrapper` calls `CatalogHandlers.createTable/loadTable/updateTable` directly on a wrapped Iceberg `Catalog` [read: https://raw.githubusercontent.com/apache/gravitino/main/iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ops/IcebergCatalogWrapper.java].
  - On 2026-11-02/03 Gravitino added `IcebergCommitValidation implements UpdateRequirement`, justified as: "Iceberg applies some metadata updates without checking them, and the result is a table that clients can no longer write or load."
  - Its checks: removed specs must be unused by any manifest; added schemas must keep field kinds and only use allowed promotions, new field ids must be above `last-column-id`, and new required fields need defaults; v3 date→timestamp promotions (which "Iceberg's `TypeUtil#isPromotionAllowed` does not implement"); partition-field-ID collisions; unknown transforms on default specs or sort orders; `main` must be a branch; snapshot `operation` must be known and `schema-id` must exist.
  - It also assigns missing partition `field-id`s because "the REST spec makes `field-id` optional, but Iceberg's metadata builder numbers such fields from 1000 again". It reads a package-private Iceberg 1.11 class via reflection.
  
  [read: https://raw.githubusercontent.com/apache/gravitino/main/iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/IcebergCommitValidation.java; commit history via gh api]
- **Nessie.** Defines its own `IcebergMetadataUpdate` interface with `applyToTable(IcebergTableMetadataUpdateState)` over its own `IcebergSchema`/`IcebergSnapshot` model. Its imports show no use of `org.apache.iceberg.MetadataUpdate` [read: https://raw.githubusercontent.com/projectnessie/nessie/main/catalog/format/iceberg/src/main/java/org/projectnessie/catalog/formats/iceberg/rest/IcebergMetadataUpdate.java].
- **Non-Java libraries.**
  - *iceberg-rust:* `TableUpdate::apply(self, builder: TableMetadataBuilder)`, `TableRequirement::check(&self, Option<&TableMetadata>)`, and a `TableMetadataBuilder` with v3 `next_row_id` handling [read: https://raw.githubusercontent.com/apache/iceberg-rust/main/crates/iceberg/src/catalog/mod.rs; …/spec/table_metadata_builder.rs].
  - *Lakekeeper (Rust REST catalog):* re-exports iceberg-rust's `TableMetadataBuilder` through its `iceberg-ext` crate and uses `.check()` for requirements in `commit_tables.rs` [read: https://raw.githubusercontent.com/lakekeeper/lakekeeper/main/crates/lakekeeper/src/server/commit_tables.rs; …/crates/iceberg-ext/src/spec/mod.rs].
  - *iceberg-go:* `Apply(*MetadataBuilder)` on each update type, including encryption keys and partition statistics, and `Validate(Metadata)` on requirements [read: https://raw.githubusercontent.com/apache/iceberg-go/main/table/updates.go; …/table/requirements.go].
  - *PyIceberg:* `update_table_metadata(...)` with a singledispatch `_apply_table_update` per update type, and `validate()` on requirement classes [read: https://raw.githubusercontent.com/apache/iceberg-python/main/pyiceberg/table/update/__init__.py].

### Inferences
- **Reuse level.** Reuse the kernel, not `CatalogHandlers`:
  - *Use:* `TableMetadata`, its `Builder`, `MetadataUpdate`, `UpdateRequirement`, `TableMetadataParser`, `MetadataUpdateParser`, `RESTSerializers`/request-response models, `ViewMetadata`.
  - *Own:* the commit loop (it is ~40 lines in CatalogHandlers), persistence, CAS, idempotency and retry policy.
  - *Why:* `CatalogHandlers` forces you to implement Iceberg's `Catalog`/`TableOperations` SPI. Its create path is self-described as hacky. Its idempotency store is single-JVM in-memory, unsuitable for multi-node HA. Polaris abandoned tracking it and copied it at 1.8.0.
- **Upgrade cost.** Pin an exact Iceberg version and upgrade deliberately each minor release (about 2–3 per year: 1.10 Sept 2025, 1.11 May 2026, 1.12 Sept 2026). Run a conformance suite: Iceberg's own `TestTableMetadata`/REST catalog tests plus golden metadata.json round-trips. New spec features arrive only through upgrades. For example, v3 `next-row-id` validation exists only in recent cores, and constraints for v4 are not implemented yet.
- **Validation gaps.** Gravitino's 2026 experience suggests the Java builder is necessary but not sufficient as a server-side guardrail against buggy or hostile clients. An enterprise catalog serving many engines should add its own validation layer, as Gravitino did by implementing `UpdateRequirement`.
- **Language.** Java is the lowest-risk choice for reuse fidelity because the Java builder is the reference. Rust (iceberg-rust plus the Lakekeeper precedent) is the only credible non-Java path. Go and Python libraries implement update application but are client-oriented and less proven server-side.

### Gaps
- I did not measure iceberg-core's transitive dependency footprint (e.g. Avro, Jackson, Caffeine, Hadoop optionality). That matters for a lean server and should be checked with `gradle dependencies`.
- I did not verify Gravitino's specific claims (partition `field-id` renumbering from 1000; missing date promotions) against Iceberg tests.
- I did not check Nessie's maintenance status or whether its reimplementation tracks v3.

---

## Decisions this implies

**D1. How much of iceberg-core to reuse**
- *Options:* (a) wrap `CatalogHandlers` and implement `Catalog`/`TableOperations`; (b) reuse only the kernel (`TableMetadata.Builder`, `MetadataUpdate`, `UpdateRequirement`, parsers, `ViewMetadata`) and own the commit loop; (c) reimplement in-house, Nessie-style.
- *Evidence:* the kernel covers every REST update and requirement type (§6). `CatalogHandlers` has in-memory idempotency and a "hacky" create path. Polaris forked it. Core is minor-version-unstable, with 12–88 accepted breaks per baseline release.
- *Recommendation:* **(b)**. Pin the version, wrap it behind an internal interface, and keep golden-file conformance tests. Option (c) only makes sense if the Java dependency becomes untenable, and would cost tracking every spec change yourself.

**D2. Source of truth: metadata.json files or the catalog database**
- *Options:* (a) pointer-only (the DB stores the metadata-location, files in object store are canonical); (b) DB-canonical (the full metadata document or normalized form in MongoDB) while still writing metadata.json per commit for v1–v3; (c) DB-only.
- *Evidence:* v1–v3 REST responses require `metadata-location`. Core committers favour making metadata.json optional in v4, with the catalog able to produce it on demand. PR #17642 makes `metadata-location` optional "for catalog-managed tables" (§3).
- *Recommendation:* **(b)**. It makes v4 catalog-managed tables a configuration change rather than a migration. It also gives you queryable history, audit and integrity hashes. Mind MongoDB's 16 MB document limit: store snapshots, schemas and history as separate documents or collections, not one blob. [inference]

**D3. Commit concurrency primitive**
- *Options:* a per-table version CAS in MongoDB, versus an external lock or lease service.
- *Evidence:* the spec only requires check-and-put. The JDBC catalog is a one-row conditional `UPDATE … WHERE metadata_location = ?`.
- *Recommendation:* a single-document conditional update with majority write concern, plus a server-side retry loop that re-applies updates on benign conflicts (the Java pattern), and an explicit commit-state-unknown reconciliation.

**D4. Server-side validation strictness**
- *Options:* the Java builder only, or the builder plus enterprise guardrails.
- *Evidence:* Gravitino's Nov 2026 `IcebergCommitValidation` list. The spec's must-rules on transforms, defaults and promotions are only partly enforced by the builder.
- *Recommendation:* the builder plus a pluggable validation layer: schema evolution rules, partition-field IDs, unknown transforms, `main` must be a branch, snapshot operation and schema-id sanity, property and location policy. Classify as (b) enterprise need. It is not bloat.

**D5. Format-version policy**
- *Options:* the v2 default (Java's default) or a v3 default; allow or block v4.
- *Evidence:* v3 was adopted May 2025. Java still defaults to v2. v4 is not adopted, and v4 tables will need a `v2` loadTable endpoint, with v1 loads refused.
- *Recommendation:* support v1–v3 fully now. Make the default a per-namespace policy, likely v2 until all of the firm's engines read v3. Implement `next-row-id` and encryption-key handling correctly from day one. Hard-block `upgrade-format-version` to 4 until the v4 and REST v2 votes pass. Design the API layer for versioned endpoints.

**D6. Metadata history and retention**
- *Options:* rely on `metadata-log` (100 entries) and leave old metadata.json files, or run catalog-owned retention plus a DB audit log.
- *Evidence:* defaults are previous-versions-max = 100 and delete-after-commit = false. Snapshots, schemas and specs grow until clients expire them.
- *Recommendation:* keep an immutable per-commit record in the DB (who, when, requirements, updates, resulting location, hash). Manage file retention under books-and-records policy. Enforce or monitor snapshot expiry per table.

**D7. loadTable payload management**
- *Evidence:* `snapshots=refs` and ETag/304 exist. The Java client still loads all snapshots to commit.
- *Recommendation:* implement both, and add metrics on per-table metadata size and snapshot count with alerting. Do not invent a partial-load API ahead of the community's v2 endpoint.

**D8. Encryption-key custody and metadata integrity**
- *Evidence:* v3 `encryption-keys` are wrapped blobs. metadata.json is plaintext. Java's Hive catalog added a `metadata_hash` integrity check for encrypted tables, and there is no REST analogue.
- *Recommendation:* store a hash of every metadata.json the catalog writes and verify it on load. Restrict who may set `encryption-keys` and `location`. Decide whether the catalog ever touches the KMS; preferably it does not, and only stores wrapped keys.

**D9. Language**
- *Evidence:* the reference builder is Java. iceberg-rust is proven server-side by Lakekeeper.
- *Recommendation:* Java, consistent with the prior lean, unless the team has strong Rust skills. Whatever the language, keep the kernel behind an adapter so a v4-era rewrite of `TableMetadata` does not ripple through the server.

**Spec-required vs enterprise vs vendor (for this slice)**
- *(a) Spec and client required:* per-table atomic CAS; all 23 update types and 8 requirement types, with 400 on unknown; server-derived fields (§1); `next-row-id` and encryption keys (v3); view metadata; refusing unsupported format versions; writing metadata.json and returning `metadata-location` (v1–v3).
- *(b) Enterprise operator:* validation guardrails, location and property governance, commit audit log, metadata-file retention, integrity hashes, payload and size monitoring, multi-region location authority (v4 relative paths), REPLACE-conflict auto-resolution (Polaris-style; an operational nicety).
- *(c) Vendor-model:* nothing in the table and view metadata spec is vendor-only. Multi-tenancy concerns sit in the catalog's resource model and auth, outside this note's scope. [inference]

## Open questions for the enterprise

1. Which engines, and which versions, will write to the catalog (Spark, Trino, Flink, Snowflake, Dremio, PyIceberg…)? Do all of them read v3 today? This decides the default format version and the v3 feature allowlist.
2. Is row lineage (v3 `_row_id`, `_last_updated_sequence_number`) wanted for CDC or regulatory audit? If so, v3 becomes a priority, and correct `next-row-id` handling is a compliance-grade requirement.
3. What are the commit rates and snapshot counts per table, especially for streaming? These drive metadata.json size, loadTable payloads and how soon v4 catalog-managed metadata matters.
4. Records retention: must prior metadata.json files and commit history be retained (or WORM-protected) for books-and-records purposes? If so, `delete-after-commit` and `metadata-log` trimming conflict with that.
5. Table encryption: will the firm use Iceberg-native encryption, and which KMS? Must the catalog provide tamper-evidence (hash verification) for metadata.json?
6. Multi-region: should each region's object store hold a copy of metadata.json? Or will v4 relative paths plus catalog-supplied `table-location` be the DR strategy, in which case the catalog's location record must be region-aware?
7. Governance: who approves schema evolution or partition changes on regulated tables? Should the catalog enforce that through validation hooks, or only record and audit it?
8. Upgrade cadence: can the team commit to upgrading the pinned Iceberg core every minor release (2–3 per year), with a conformance suite, to keep pace with spec changes?
9. Does the firm want to influence v4 (catalog-managed metadata, the v2 loadTable endpoint) by participating on dev@iceberg? These designs are being decided now, in Aug–Nov 2026 threads.

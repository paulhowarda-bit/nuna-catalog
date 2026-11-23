# What a catalog console puts up front

Research for Atelier, 2026-11-23, at Paul's request: what matters most in an Iceberg catalog, read as a catalog
first and a maintainer of Iceberg files second; what Unity Catalog, Polaris and Nessie put up front in their UIs; and
what that means for nuna's console. Sources are listed at the end. No screenshots of the three products were seen:
Unity's layout comes from Databricks' docs, Polaris's and Nessie's from their UI source.

## 1. The parts of an Iceberg catalog, in order of weight

A catalog's job, as the REST spec defines it (`spec/rest-catalog-open-api.yaml`), is to name tables and swap each
table's metadata pointer atomically. Everything else hangs off that.

**The catalog proper**

1. **The namespace tree and its objects.** Namespaces (nested, with properties), tables, views, and, since the
   newest spec, functions. This is what every engine lists and every user browses.
2. **Each table's metadata, as the catalog holds it now.** Format version (1 to 3), UUID, location and current
   metadata location, schema (field ids, types, required, doc), partition spec and sort order, properties.
3. **The commit: the current snapshot and how it got there.** Snapshots with their operation and summary (rows,
   files, bytes added and removed), the snapshot log and metadata log, refs (`main`, branches, tags). The atomic
   commit, single-table or multi-table, is the catalog's core guarantee; the snapshot history is its visible trace.
4. **Who may do what.** Authentication, grants on catalog, namespace, table and view, and credential vending or
   remote signing (how an engine gets to the files). Without these, a catalog is a file listing.
5. **How engines connect.** `/v1/config` (the warehouse, defaults and overrides, the endpoints the server offers), the
   prefix, the auth flow. It is the first thing a new user needs and the last thing a UI usually shows.
6. **Catalog-level versioning** (Nessie, nuna's refs, item 109): branches and tags over the whole catalog, merge
   and its conflicts. This is not in the REST spec; it sets a catalog apart.
7. **Views.** Versions, SQL per dialect, the schema they produce.
8. **Scan planning and metrics** (`/plan`, `/tasks`, `/metrics`). These are server work engines ask for; nobody
   browses them.

**Maintenance, second**

9. **Table health**: small files, delete files and delete ratio, manifest count, snapshot count and age, orphan
   bytes.
10. **The jobs that fix it**: compaction (rewrite data files, apply deletes), rewrite manifests, expire snapshots,
    remove orphan files, compute statistics (Puffin NDV, partition stats). What ran, what is queued, what it saved.

**Trust, cutting across both**

11. **Governance and audit**: owner, classification, legal holds and freezes, the audit trail of commits and
    refusals.

## 2. What the three products put up front

| | Unity Catalog (Catalog Explorer) | Polaris (Polaris Console; Snowflake Open Catalog) | Nessie (Nessie UI) |
|---|---|---|---|
| First screen | Quick Access: Recents, Favorites; a separate Discover page with domains, certified assets and assets suggested by AI | Hero "Welcome", count cards (Catalogs, Connections, Principals), quick actions | Straight into the default branch's root tree |
| Navigation | Catalog, schema, object tree; sharing and external data at the top; Govern apart | Home, Connections, Catalogs (tree), Access Control | One link, Tables; header holds the ref selector, branch and tag counts, breadcrumb, Commit History |
| Table page | Tabs: Overview (columns, owner, comment, quality mark), Sample Data, Details, Permissions, History, Lineage, Insights, Quality, Policies | One page: Table Information (UUID, format version, location, metadata location, properties, partition specs), Schema, DDL; actions Rename, Edit properties, Delete | Type and metadata location; views add version, SQL and dialect |
| Iceberg history | History is Delta's; no Iceberg snapshot list, no refs ("branching and tagging aren't supported") | None | Commits over the catalog, not snapshots of a table |
| Maintenance | Done by the platform (predictive optimization); shown only as entries in History | None | None |
| Governance | Strong: grants, owner, governed tags, ABAC policies, certification, lineage, usage | The RBAC chain: principals, principal roles, catalog roles, privileges | None |

**What they share.** A tree from catalog to namespace to table, with a breadcrumb. A table page that starts with the
schema. None of them shows a table's Iceberg snapshots, its refs or its maintenance.

**Where they differ.** Unity leads with discovery and trust (what is popular, certified, who uses it, where it came
from). Polaris leads with administration (storage configuration and roles). Nessie leads with version control (the
ref selector and the commit log).

## 3. What nuna's console should put up front

nuna's console today (`catalog-server/src/main/resources/nuna/ui/`):

- **Overview.** It opens with maintenance: the ice line ("Every table, one mark") and "Queued for tonight". After
  those come tiles for Governance, Audit, latency and Servers.
- **Navigation.** Overview, Catalog, Maintenance, Governance, Audit, Health.
- **Table tabs.** Overview, Schema, Snapshots, Partitioning, Properties, Health, Governance, Grants, Audit, Metadata
  log.

The table page is already catalog-first. The overview is not: it leads with what nuna does second.

Recommendations, in order:

1. **The overview leads with the catalog.**
   - What is in it: catalogs, namespaces, tables, views.
   - What changed: recent commits across the catalog, with table, operation, rows and engine. This is the catalog's
     heartbeat.
   - How to get to a table: go-to-path, recent and favourite tables.
   - Then one maintenance tile (in shape, queued tonight, fixed last night) linking to Maintenance, beside the
     Governance and Audit tiles. The ice line keeps its full size on the Maintenance page.
2. **A Connect panel** on the overview or the catalog page, which none of the three has:
   - the REST URI, warehouse and prefix, and the auth flow;
   - a copyable snippet for Spark, Trino, PyIceberg and DuckDB.
3. **The table header carries the catalog's facts**:
   - path, format version, the current snapshot (when, which operation, which engine), rows, files and size;
   - the ref selector, as Nessie puts it in the header;
   - a health mark linking to the Health tab, as Unity puts its quality mark on the Overview.
4. **A table's Overview starts with the schema and the current snapshot.** The partition spec and sort order sit
   beside them: Polaris shows partition specs in its first card. Health stays a tab.
5. **Sell what nobody else shows, under the catalog's facts, not above them**:
   - the table's snapshot and ref timeline;
   - catalog branches and tags with a merge preview;
   - per-table health with what last night fixed;
   - the hash-chained audit;
   - legal holds.

   These are nuna's edge over all three products, and each belongs on the page it explains.
6. **Access in one place.** Unity's Permissions tab and Polaris's Access Control both read as "who can do what here".
   nuna splits a table's access across Governance and Grants. Consider one Access tab: owner, effective grants
   (inherited included), and how credentials are vended.

Left for later: lineage, usage insights and sample data. They are Unity's lead features, but each needs data nuna
does not collect today (engine-side lineage, query logs, a compute to sample with).

## Sources

- Unity Catalog:
  - [Catalog Explorer](https://docs.databricks.com/aws/en/catalog-explorer/)
  - [Explore database objects](https://docs.databricks.com/aws/en/discover/database-objects)
  - [Revamped Catalog Explorer blog](https://www.databricks.com/blog/accelerating-discovery-unity-catalog-revamped-catalog-explorer)
  - [Discover page](https://docs.databricks.com/aws/en/discover/discover-page)
  - [Table insights](https://docs.databricks.com/aws/en/discover/table-insights)
  - [Lineage](https://docs.databricks.com/aws/en/data-governance/unity-catalog/data-lineage)
  - [Predictive optimization](https://docs.databricks.com/aws/en/optimizations/predictive-optimization)
  - [Iceberg in Azure Databricks](https://learn.microsoft.com/azure/databricks/iceberg/)
- Polaris:
  - [Polaris Console source](https://github.com/apache/polaris-tools/tree/main/console)
  - [Open Catalog: create a catalog](https://docs.snowflake.com/en/user-guide/opencatalog/create-catalog)
  - [Open Catalog: secure catalogs](https://docs.snowflake.com/en/user-guide/opencatalog/secure-catalogs)
  - [Open Catalog: view a table's schema](https://docs.snowflake.com/en/user-guide/opencatalog/view-table-schema)
- Nessie:
  - [Nessie UI guide](https://projectnessie.org/guides/ui/)
  - `projectnessie/nessie-ui` source (`App.tsx`, `TableHead.tsx`, `ContentView.tsx`, `CommitLog.tsx`)

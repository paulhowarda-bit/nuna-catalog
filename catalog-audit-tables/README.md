# catalog-audit-tables: the audit table writer (D23)

A separate service that writes every record of the catalog's audit topic to one Iceberg table, `events`, in an
audit catalog of the deployment's own, about a minute behind, so access reviewers can ask across objects, people and
months in SQL from any engine: every table a principal read last quarter, every denial in a namespace. It is an
Iceberg Java client of the catalog over REST, like the maintenance worker (D2 as amended), and signs in as a
principal of its own. The decisions are D23's amendment of 2026-11-20 and `backlog/27.md`.

## The table

`<catalog>.<namespace>.events`, by default `nuna_audit.audit.events`: format v2, partitioned by `day(time)`, one row
per record of the topic. The writer creates the namespace and the table, and refuses to write to a table of that
name with other columns.

| Column | Type | Meaning |
|---|---|---|
| `event_id` | string | the record's CloudEvents id |
| `time` | timestamptz | when the catalog recorded it, to the microsecond |
| `type`, `source` | string | e.g. `nuna.catalog.read`, and the deployment that recorded it |
| `principal`, `end_user`, `engine`, `request_id` | string | who asked, for whom, through which engine, and the request; an authentication failure has only a request id |
| `catalog`, `object_kind`, `object_id`, `object_path` | string, string, string, list of string | what it is about: a table, view, namespace or catalog, by name as it was then and by stable id |
| `operation`, `privilege`, `reason` | string | where the type has them: a denial's operation, missing privilege and reason, a grant's privilege, a hold's reason |
| `data` | string | the whole payload as the topic carries it, JSON, so a new payload version needs no new column |
| `topic_partition`, `topic_offset` | int, long | the record's place in the topic, which ties a row to its archive chain |

The mapping from each event type lives in `catalog-audit` (`AuditRows`), without Iceberg. The relay is at least once
(D23), so an `event_id` can repeat, at another offset; an offset is never written twice.

Not in it: history older than the topic's retention, records that went to the fallback log in a Kafka outage (not in
the topic), and the records of the writer's own requests (below). Rows are never deleted by the writer: the archive is
the five-year record (D24).

## How it writes

The writer reads the topic in its own consumer group. Rows wait in memory and are committed every
`commit-interval` (1 minute by default) after the first of them arrived, or once `max-rows` wait. Each commit adds
the rows' Parquet files and records each partition's next offset in the same Iceberg commit, in the snapshot's summary
(`nuna.audit-tables.offsets.<topic>`). On start, after a failed commit, a rebalance or a restart, it reads those
offsets back and resumes from them: a commit that never lands is read again, and one that landed is not written
again. The consumer group's own offsets are committed after each commit only so Kafka's tools show the lag.

- **Its own records.** Its every commit makes records (its loads, the credentials vended to it, its commits), which
  would otherwise make the next commit. It leaves out every record whose principal is its own (`principal`); they
  stay in the topic, the SIEM and the archive.
- **One replica.** A second writer's commit fails Iceberg's validation that no rows were added since the snapshot it
  started from, and it reads the offsets again; the chart runs one, with the `Recreate` strategy.
- **A record it cannot map** stops it there, every attempt, with the record's place in the error: nothing after it is
  written and nothing is skipped. A catalog newer than the writer can do this; upgrade the writer first.
- **Outages** of the catalog, the store or Kafka delay rows; no request waits for the writer. It logs the failure and
  retries every 10 seconds.
- **Snapshot expiry.** The offsets live in the writer's snapshots. Compaction and manifest rewrites add snapshots
  without them, which the writer looks past. If expiry removed every snapshot that records offsets (the writer down
  longer than the audit catalog's `max-snapshot-age`, and only newer snapshots kept), the writer refuses to start
  rather than guess. Keep the audit catalog's snapshots longer than the writer may be down.

## Running it

```
./gradlew :catalog-audit-tables:bootJar
java -jar catalog-audit-tables/build/libs/catalog-audit-tables-<version>-exec.jar \
    --spring.config.additional-location=file:audit-tables.yaml
```

It has no HTTP port. It logs a line per commit, as JSON on stdout.

### Settings (`nuna.audit-tables.*`)

| Setting | Default | Meaning |
|---|---|---|
| `catalog-uri` | required | the catalog's base URI, e.g. `http://nuna-catalog:8181/` |
| `catalog` | `nuna_audit` | the audit catalog, declared in the catalog's `nuna.storage.catalogs` |
| `namespace` | `audit` | the namespace of the table `events` in it |
| `principal` | required | the writer's own principal id as the catalog records it (its token's user-id claim) |
| `commit-interval` | `PT1M` | how often waiting rows are committed |
| `max-rows` | `100000` | rows that make a commit before the interval has passed |
| `token.endpoint`, `token.client-id`, `token.client-secret` | required | the client-credentials grant against the identity provider; Iceberg's client renews it |
| `token.scope`, `token.audience` | none | a scope or audience to ask for |
| `kafka.bootstrap-servers` | required | the cluster holding the audit topic |
| `kafka.topic` | `nuna.catalog.audit` | the catalog's `nuna.audit.audit-topic` |
| `kafka.group` | `nuna-audit-tables` | the writer's consumer group |
| `kafka.properties.*` | none | further Kafka client settings (TLS, SASL); the writer owns `bootstrap.servers`, `group.id`, `enable.auto.commit`, `auto.offset.reset`, `isolation.level` and the deserializers |
| `client-properties.*` | none | further Iceberg client properties, e.g. `s3.endpoint` |

## Deploying it

1. Declare the audit catalog on the catalog server, in `nuna.storage.catalogs.nuna_audit` (profile, KMS key set,
   legal entity), as any catalog.
2. Give the writer a principal of its own at the identity provider, in a group that holds `create` and `write` on the
   audit catalog and nothing else.
3. Grant the readers' group `select` on the audit catalog. Rows are not filtered: a reader sees every object and
   person in the deployment's records, as a SIEM user does, so the group must be narrow. Everyone else finds no such
   table (D18). Until the server takes the group as a setting (backlog 85), both grants are made through the grants
   API, `POST /management/v1/catalogs/nuna_audit/grants`.
4. Give the maintenance worker's group `write` on the audit catalog too, so its expiry and compaction keep the table
   tidy (D30), within the expiry caveat above.
5. Build the image, `deploy/image/audit-tables.Dockerfile`, and install the chart `deploy/helm/nuna-audit-tables`:
   one replica, its settings under `config`, the client secret (and any Kafka secret) under `secrets`, each key a
   property name such as `nuna.audit-tables.token.client-secret`. The chart refuses to render without `catalog-uri`,
   `principal`, `token.endpoint`, `token.client-id` and `kafka.bootstrap-servers`.

## Tests

- `AuditRowsTest` (`catalog-audit`) maps every line of the golden `events-v1.jsonl` and fails for an event type with
  no mapping.
- `TableWriterTest` runs the writer against Kafka's fake consumer and Iceberg's in-memory catalog, which can lose a
  commit or its answer: every record written once with its place in the topic, resuming after the last committed
  offset, no gap and no repeat after a lost commit, a second writer, a revoked partition or a record it cannot map,
  its own records left out. `AuditTablesSettingsTest` checks the settings and the clients made of them.
- `AuditTablesTest` (tagged `heavy`: PostgreSQL, Kafka and the run's S3 store) runs it against a real catalog server:
  a row for each kind of request about two intervals behind, no gap and no repeat after a writer dies mid-batch, and
  the table found only by the auditors.
- Bites: `bites/D23/`.

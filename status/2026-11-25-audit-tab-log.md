# 2026-11-25, an object's Audit tab reads the audit table (backlog 197)

- `GET .../audit` reads the audit table `nuna_audit.audit.events` wherever the audit tables are configured, so an
  object's Audit tab works without Kafka; it reads the topic only where there are no audit tables, and answers 503
  with neither. `EventsReader.Query` gained `objectId`. Who may read it is unchanged: manage-grants on the object (D19),
  not select on the audit catalog.
- The tab's words and the Audit page's status line are Atelier's: complete up to about a minute ago, or why not (a full
  page may have older records; a shorter one means the reader's 500,000-row scan budget ran out, where a larger page
  finds nothing more); "as of the last commit at", not "up to", since a writer catching up commits after its newest
  record.
- Checked: four `ConsoleBrowserTest` Audit tests with `-Pheavy`, 4 of 4, the new one stubbing the table's answer in the
  page; five bites in `bites/OBS-197/`, all BITES (the browser one SETUP on its first run, a container that did not
  start beside another session's stack, BITES on the rerun); the fast build after merging `main`.
- Not covered here: the check in the demo with every switch off, signed in as gary, which needs this on `main` first.

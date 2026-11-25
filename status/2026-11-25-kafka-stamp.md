# 2026-11-25, the Kafka copy carries the log's stamp (backlog 276)

Paul, asked whether the Kafka copy of each audit record should carry its run and number (a question 272 left open),
answered "Keep it unstamped", then "stamp it to sorry changed my mind". D23 amended 2026-11-25.

What changed:

- **The audit trail** hands Kafka the record it logged, a `StampedEvent` (event and `LogStamp`): the audit topic and
  the change stream get the log's line byte for byte, `nunarun` and `nunaseq` included, for synchronous records and
  relayed batches alike. `EventPublisher` publishes a `StampedEvent`; the relayed batch goes through a new
  `StampedTarget`, which `ChangeStream.fanOut` and `KafkaEventPublisher` implement. The relay's own port,
  `RelayTarget`, still carries plain events, since the number is given where the record is logged.
- **The audit table writer** with `source: kafka` keeps the stamp in `log_run` and `log_seq`, so a row from the topic is
  placed in its run's archive chain as one from the log is. The feed and the console needed no change: both go by
  `log_run`, whatever the row's source.

Checked:

- `:catalog-audit:test` (`KafkaRecordTest` new, `ChangeStreamTest`, `AuditRowsTest`), `:catalog-audit-tables:test
  --tests '*TableWriterTest'` (one new test), and `:catalog-server:test` for `AuditTrailTest`, `AuditFeedRoutesTest`,
  `AuditReadLimitTest`, `RowColumnReadRoutesTest` and `RefReadsTest`: all pass. `KafkaPublishingTest` and
  `BrokerOutageTest` (heavy) compile; they run overnight.
- Bites: four new in `bites/OBS-276/`; six re-recorded against the moved code (`D23/audit-record-not-logged`,
  `D23/credential-issue-refused-when-kafka-fails`, `D23/relayed-batch-not-logged`, `D23/scan-fails-when-kafka-down`,
  `M7/change-stream-unfiltered`, `OBS-262/log-line-unstamped`, the last now naming the renamed test). Nine run with
  `bites/run.sh`: all BITES, tree restored. `D23/scan-fails-when-kafka-down` needs `KafkaDownTest`'s containers and
  is left to the overnight run. All 761 bites apply.

Not done: the console's Read from line for a Kafka row names the partition and offset but not the run; the Hash chain
line and "Check this record" already work for it.

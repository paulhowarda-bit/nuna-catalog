# 2026-11-23: audit goes to logs (195) and the demo's switches (196)

Paul, 2026-11-23, after seeing that the catalog hands out no credential without Kafka: "Log as the main audit path";
then three switches for the demo, "cdc", "Gen" and "ss", with Kafka only for the generator and CDC (D23 amended, D48
amended, D50).

- 195 (its own status entry, `2026-11-23-audit-to-logs.md`): every audit record goes to the log `nuna.audit` first;
  Kafka is a best-effort copy; the audit table writer can take the log over OTLP.
- 196: `demo.sh gen`, `ss` and `cdc` (each with `stop`); Kafka and kafbat in `kafka.yaml`, started only while `gen` or
  `cdc` is on; the demo's catalog without Kafka, its audit log over OTLP to the writer.
- 197 added: a table's Audit tab and the console's audit words without Kafka.
- Landed together (D47) on `land-2026-11-23-audit-switches`, fast build green twice (before and after merging main at
  17af87a0). Not yet checked in a rebuilt demo; the new heavy test (`KafkaOutageTest`) and the 14 new D23 bites run
  overnight.

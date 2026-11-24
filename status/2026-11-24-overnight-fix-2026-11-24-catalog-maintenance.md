# 2026-11-24, KafkaDownTest brought to D23 as amended (backlog 223)

The overnight run of 2026-11-24 failed `KafkaDownTest` in its heavy build: the test still expected a scan to fail
while Kafka is down, which item 195 changed (the catalog audits to its log `nuna.audit` and vends without Kafka). The
test now asserts that a scan with the broker paused expires the old snapshot and records its files as pending
deletions, and a bite (`bites/D23/scan-fails-when-kafka-down.patch`, the request failing when Kafka does not take its
audit record) is recorded against it.

Checked: `./gradlew build` green (1 min 29 s, `:bitesApply` included). Not covered: the test and the bite need
containers and have not run; the next overnight run checks both.

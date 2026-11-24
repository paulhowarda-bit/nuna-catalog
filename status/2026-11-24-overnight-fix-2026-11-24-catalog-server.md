# 2026-11-24, catalog-server's heavy failures in the overnight run (backlog 224)

The overnight run of 2026-11-24 (main at ee2084d2) had eight `catalog-server` heavy failures. Every one was a test
left behind by items 193, 195, 206, 207 or 214, which landed after the last heavy run; one (`LifecycleTest`) followed
from another (`KafkaOutageTest`). Test changes only, in seven classes; backlog 224 lists each cause and change.
`SecurityGateTest`'s outbox counts, which only won the race against the relay, now read the audit log too.

Checked: `./gradlew build` after merging `main`, passed. Not run: the heavy tests themselves, which need containers;
the next overnight run's heavy build checks them.

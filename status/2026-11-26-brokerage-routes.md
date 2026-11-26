# 2026-11-26 brokerage-routes

Item 238, the SingleStore route checked on the demo (Paul: "finish it"): not finished, and `backlog/238.md` says why.
`demo.sh ss` fails on Podman's kind cluster because every container there may run at most 307 tasks: systemd's
DefaultTasksMax is 15% of the node's 2,048 PIDs, and SingleStore idles at 284. Lifting its scope live let `ss`
finish. With `ss` alone, Windows paged in 15,000 to 117,000 pages a second. The demo went down for container tests
before the eleven change feeds started; by their reservations they need 4,224 MB against SingleStore's 2,764 MB.
The switches were put back as they were (kafka only).

Later the same day (Paul: "Yes, carry on"): item 238 done. Item 294 lifted the task cap. The eleven world feeds now
share one change feed, `brokerage_world_to_lake` (a connector pipeline takes `singlestore.tables`, 08e7f686), so they
reserve one 384 MB extractor heap, not eleven. Checked on the demo built from 08e7f686 with Kafka off: every copy's
receipts matched SingleStore, Trino read the copies, and `ss stop` moved gen to the Kafka route without sending a
record twice. SingleStore held at most 2,236 MB of its 3 GiB and Windows paged in at most 32,657 pages a second.
`world_feeds_start` now classifies each copy before marking `brokerage.singlestore` regulated: the copies were closed
until classified. Added item 298: a change feed stops on restart once maintenance compacts its copy
(`ops.orders_to_lake` is stopped in the demo that way). The switches were left as they were (kafka only).

Item 298 built (worktree-changefeed-resume): a change feed resumes past maintenance's `replace` snapshots, and from
the table property `nuna.changefeed.offsets` (written in the same transaction as each commit) once expiry has
removed its commits. Four tests in `IcebergCopyTest`, two new bites and one recorded again, all biting. The check on
the demo (`ops.orders_to_lake` started again) follows once the demo runs it.

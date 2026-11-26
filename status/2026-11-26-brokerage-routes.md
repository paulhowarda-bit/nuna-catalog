# 2026-11-26 brokerage-routes

Item 238, the SingleStore route checked on the demo (Paul: "finish it"): not finished, and `backlog/238.md` says why.
`demo.sh ss` fails on Podman's kind cluster because every container there may run at most 307 tasks: systemd's
DefaultTasksMax is 15% of the node's 2,048 PIDs, and SingleStore idles at 284. Lifting its scope live let `ss`
finish. With `ss` alone, Windows paged in 15,000 to 117,000 pages a second. The demo went down for container tests
before the eleven change feeds started; by their reservations they need 4,224 MB against SingleStore's 2,764 MB.
The switches were put back as they were (kafka only).

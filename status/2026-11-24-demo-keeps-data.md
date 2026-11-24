# 2026-11-24, the demo keeps its data across down and up (backlog 237, D52)

PostgreSQL, Silo, Kafka and SingleStore keep their data on `hostpath` volumes. `demo.sh down` deletes everything but
them (and configmap `demo-seeded`); `ss stop` and `gen stop` keep theirs; the new `demo.sh wipe` deletes them for
fresh sample data. The overnight run's down and up now keep the data. Keycloak and the CDC leg's databases keep none.

Checked live: `wipe` and `up` from the branch, `gen`, a marker namespace, then `down` and `up`: the marker,
`trades`' snapshot, Kafka's offsets (journal-lines 62,786 then 63,255), SingleStore's orders (1,721 then 1,734) all
carried on, `up` skipped the seed, and the generator resumed ("already sent", days replayed without sending). Fast
build green after merging main. Not covered: a Docker Desktop restart, `down` with the CDC leg running. The demo was
left as found, with `gen` and `ss` off.

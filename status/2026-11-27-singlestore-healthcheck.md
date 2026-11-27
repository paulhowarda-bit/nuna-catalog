# 2026-11-27, SingleStore's health check under Podman (backlog 302)

`ChangeFeedTest` never started in the overnight heavy build of 2026-11-27: `singlestoredb-dev:0.2.88` is an OCI image,
and Podman, unlike Docker Desktop, drops an OCI image's `HEALTHCHECK`, so the container had no health and
`Wait.forHealthcheck()` timed out after 5 minutes with SingleStore up. Memory was not the cause (6.7 GB available in
the Podman machine). Behind it, backlog 280's sweep had left `connect()` using the old root password `it-root`.

The test now gives the image's own check (`/scripts/healthcheck.sh`, 5 s, 5 s, 90 s start, 3 retries) at create
time and connects with `ROOT_PASSWORD`. `ChangeFeedTest` passed beside the demo, 7 of 7 in 98.9 s; the fast build is
green. Whether it passes in the overnight heavy build is still to be seen.

Any other image whose health check a test waits on will meet the same thing under Podman: give the check explicitly.

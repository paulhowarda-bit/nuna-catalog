# 2026-11-15, the overnight run keeps a failed leg's result files

`overnight.sh` now copies, for each leg that does not pass, the test result files that leg wrote into
`runs/<date>/<leg>-results/` (at their paths in the clone), with what `docker info` said just after. The summary's Log
column names the folder. The bites run the same tests later and overwrite those files in the clone; they hold each
test's output, which for the Ceph leg of 2026-11-15 would have held Testcontainers' log of its search for Docker
(backlog 32). Its usage text now prints the whole header comment, not lines 2 to 30.

Checked with a stub repo whose `gradlew` writes a failing result file for the Ceph leg and passing ones for the
others (`KEEL_OVERNIGHT_LEGS="heavy ceph nightly"`, a demo namespace that does not exist): the Ceph leg's file and
`docker-info.txt` were kept, with the test's output in it, the heavy and nightly legs kept nothing, and the summary
read `ceph.log, ceph-results/`. Not yet seen in a real overnight run. Backlog 32 stays open until the Ceph leg passes
in one.

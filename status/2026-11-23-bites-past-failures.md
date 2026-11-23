# 2026-11-23, the overnight bites run past failing tests

Item 164 (Paul: "A single test failure should not stop the entire run"). `overnight.sh` runs the bites whatever the
legs' tests did, leaving out only the bites whose test class failed tonight or whose module's tests hung, and all of
them only when the heavy build failed with no failing test. Test tasks time out under `-Pheavy` (60 min), `-Pnightly`
and `-Pbites` (120 min), so a hung test fails its module and the leg goes on. The heavy build and the Ceph leg already
ran with `--continue` (item 122), and `nightly.sh` always has.

Checked in a stub Gradle project (the timeout stops and fails a hung task; `--continue` runs the rest) and with
`overnight.sh` in a stub repo, in three cases: tests failing and a module hung, the heavy build broken, a module hung
only. The fast build passed. Not checked: a real overnight run.

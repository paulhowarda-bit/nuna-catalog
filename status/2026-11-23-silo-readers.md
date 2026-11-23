# 2026-11-23, ana and presenter browse Silo's console, read-only (backlog 180)

Paul asked for `ana` and `presenter` to get into Silo. Silo policy `warehouse-read`
(`demo/k8s/silo-warehouse-read.json`): list the buckets, list and read the `warehouse` bucket, write nothing.
`demo.sh up` makes it in Silo after the seed job; Keycloak client `silo` gains the role of that name, given to
`ana` and `presenter`, and Silo takes it from the ID token's `policy` claim (item 174's sign-in).

Applied to the running demo by hand, without a restart: the policy with `mc admin policy create` in Silo's pod
(demo.sh's own step, run against it), the client role and the two mappings through Keycloak's admin API as the
master realm's `admin`. Checked through Silo's console API after one Keycloak sign-in each: `ana` and `presenter`
list `warehouse` (10,948 objects, read only) and its `tables/` prefix; `oscar` lists no bucket and is refused
`warehouse` (403). Not checked: a full `down` and `up` with the change, which the overnight rebuild does.

# 2026-11-25, Kafka Connect calls retry a 502 instead of stopping up (backlog 267)

- `demo/demo.sh` `connect_api`: tries a call again every 2 s for about a minute while Kafka Connect or the front door
  answers 409, 502, 503 or 504. On 2026-11-24 `up` stopped at the brokerage world's step on one 502 to
  `PUT /connectors/brokerage-lake/config`, with Windows paging hard.
- `demo/demo.sh` status text (item 238): the Switches block names `kafka` and says `gen` runs through Kafka or
  SingleStore or both, no longer that it turns `ss` on; the Brokerage line names the route.
- Checked: `bash -n demo/demo.sh`; `tls_curl` and `connect_api` cut from `demo.sh` and run against a stub server (the
  cases and counts in item 267); the fast build.
- Not covered: a live 502 on the demo, which cannot be made on purpose.

# 2026-11-25, a kubectl timeout no longer reads as a deployment gone (backlog 286)

- `demo/demo.sh` `running`: only kubectl's NotFound says a deployment is not there; any other failure is asked again,
  10 s a try, 12 tries 5 s apart, then the script stops naming kubectl's answer. `cdc_running` uses it. That day's first
  `gen stop`, with Windows paging the Docker VM out, took the generator for gone and stopped Kafka under it.
- Checked: `bash -n demo/demo.sh`; `running` and `cdc_running` cut from `demo.sh` and run against a stub `k` (the cases
  and counts in item 286); the fast build.
- Not covered: a live API-server timeout on the demo, which is down and cannot be made to time out on purpose.

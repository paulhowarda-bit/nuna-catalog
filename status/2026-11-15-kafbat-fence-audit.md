# 2026-11-15, kafbat fences off the topics the demo runs on and keeps an audit log (backlog 35)

Changed, in `demo/k8s/kafka-ui-signin.yaml` only:

- Role `topic-admins` (ana, gary, stella) keeps every topic action on topics matching `(?!keel\.|cdc-connect-|_).*`
  and has only VIEW, MESSAGES_READ and ANALYSIS_VIEW on `(keel\.|cdc-connect-|_).*`: the catalog's
  `keel.catalog.audit` and `keel.catalog.changes`, the Connect worker's `cdc-connect-*`, and names starting with
  `_`. kafbat 1.5.0 matches a pattern against the whole topic name (`Pattern.matcher(name).matches()`) and grants
  the union of every matching permission. The CDC leg's change, control and schema-history topics stay theirs.
- kafbat's audit log is on (`KAFKA_CLUSTERS_0_AUDIT_*`): every change made through it, refused ones included, with
  who made it, in topic `__kui-audit-log` (1 partition, 90 days) and in its log (logger `audit`). Level ALTER_ONLY,
  so reads are not recorded. Reading that topic in kafbat takes the `audit` permission besides the topic's; only
  `operators` (oscar) holds it. `requireAuditTopic` is on, so kafbat stops rather than run without the topic.

Checked on the running demo, kafbat patched with the branch's file (`kubectl patch`, as `demo.sh` does), signing in
through Keycloak as a browser does:

- oscar made stand-ins `keel.fence-check` and `_fence-check`, each with a message, so that a fence that failed would
  touch only them. ana read both topics and oscar's messages (200); producing, changing settings, clearing and
  deleting answered 403 on each, as did creating `keel.ana-made` and `_ana-made`. She read `keel.catalog.audit` and
  its messages (200) and was refused kafbat's audit topic (403). On `demo-ana-fence` she created, changed settings,
  produced, cleared and deleted (200). oscar deleted the stand-ins (200) and read the audit topic through kafbat.
- The audit topic held 42 records for the two runs of that check, 20 of them refused (`ACCESS_DENIED`), from ana
  and oscar; kafbat's log had the same lines.
- With the audit topic set to an invalid name, kafbat exited with code 1 at start and Kubernetes restarted it
  (CrashLoopBackOff), while the old pod kept serving; the setting was then removed.

Not covered: a cold `demo/demo.sh up`, where kafbat may start before Kafka (the overnight run's will be the first);
the kafbat pages themselves in a browser. The README and the sign-in text `demo.sh` prints still describe the old
rights: item 34 holds those files, so backlog 36 changes them after it lands.

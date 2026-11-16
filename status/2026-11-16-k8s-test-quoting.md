# 2026-11-16, KubernetesCompactionTest's kubectl filter (backlog 54)

The overnight run of 2026-11-16 failed `KubernetesCompactionTest#compactionRunsAsAJobPerTable` in the heavy build and
the Ceph leg: the check item 46 added for a cut-off Job's reason passed
`jsonpath={.status.conditions[?(@.type=="Failed")].reason}` to kubectl, and Java on Windows hands the inner double
quotes over unescaped, so kubectl saw `@.type==Failed` and refused the template. The filter now quotes with single
quotes.

- Checked by running both filters through `ProcessBuilder` against the docker-desktop cluster's node: the double-quoted
  one fails with the overnight run's error ("unrecognized identifier Ready"), the single-quoted one prints
  `KubeletReady`.
- The test itself is heavy and was not run here; the next overnight run runs it, and with it the modules the heavy
  build never reached on 2026-11-16 (the Oracle, MongoDB and CockroachDB suites, conformance, engines) and the bites.
- No other test passes double quotes inside a kubectl argument.

# 2026-11-15, metrics reports leave the catalog sanitized, and unknown report types keep their name (backlog 37)

- `RestRequest.reportMetrics` runs `ExpressionUtil.sanitize` on a scan report's parsed, unbound filter before the
  report is forwarded, whatever the client sent. A filter the stock Java client already sanitized is hashed again:
  its `(hash-…)` and `(5-digit-int)` placeholders become new hashes, so no string the client chose is forwarded.
- A report of a type the parser does not know is forwarded as the `report-type` and `table-name` the client sent and
  the body's size in UTF-8 bytes (`MetricsSummary.bytes`), never the body. `MetricsSink` logs it as
  `unknown-report catalog=… namespace=… table=… bytes=… report={"report-type":…,"table-name":…}`: the client's
  strings appear only inside escaped JSON, so a type holding a newline cannot start a forged line. A `table-name`
  that is not a string answers 400, as a bad scan or commit report does.
- Tests: `MetricsReportRequestTest` (catalog-iceberg, 7) and `MetricsReportTest` (catalog-server, 4, read from the
  `nuna.telemetry.metrics` logger behind the route), all passing; `RestRoutesTest#metricsCredentialsAndTokens` and
  `SecurityGateTest#metricsAreReportedOnlyOnATableTheCallerMayRead` still pass.
- Bites in `bites/D13/`, each run once with `bites/run.sh`, each BITES (1 of 1 failed) with the tree restored:
  `metrics-filter-unsanitized`, `metrics-unknown-body-forwarded`, `metrics-unknown-type-leads-the-line`. The two
  earlier metrics bites patch `TableRoutes.java`, which this branch leaves alone; both still apply.
- Fast build: three runs before the merge with `main`. The first two failed on Docker waits while the nuna-demo stack
  held Docker (S3 read timeouts, the audit consumer reading 0 records, PostgreSQL refusing connections, Chrome
  sessions not created; 17 and 12 failures, none in the metrics tests). The third, with the demo down, failed one
  test: `ConsoleBrowserTest#grantsAreMadeAndRevokedAndASeparationOfDutiesRefusalIsShownVerbatim`, a Selenium
  `StaleElementReferenceException`, which had passed in the second run. After merging `main` (4acb145) the same test
  failed the same way once more; alone the class passed 7 of 7. It is a race in the test's wait (backlog 51). The
  next fast build passed: 1,241 tests, none skipped or failed, `:bitesApply` included, 8 min 17 s.
- Found while doing it: Iceberg's sanitizer hash is an unkeyed 31-bit Murmur3, and hashing every SSN recovered
  `123-45-6789` from its `(hash-7f7bcfed)` in 8.2 s (two candidates). Backlog 45 puts the choice to Paul.

Not covered: a known report's `metadata` map and a commit report's other strings are forwarded as sent; item 37 names
only the filter.

# 2026-11-25, the overnight run's three catalog-server failures that no agent took

Paul, 2026-11-25: fix items 269, 270 and 271. They landed on the fast build, as every landing does; the heavy tests
they change run overnight.

- 269: `MutatingRoutesAuditTest` names the OPA routes (item 115) as changing nothing.
- 270: `SecurityGateTest`'s capture of the audit log was made before its Spring context started, which detached it.
  It is made once the context is up there and in `RestRoutesTest`, and `AuditLogCapture` fails at once when detached.
- 271: a console race. A catalog page left before its contents came took the next page's Reading control; it now draws
  only while in the document. The browser test makes the race land every time; one bite added, one recorded again.

The fast build passed. None of the changed tests ran before landing: they are heavy, and the next overnight run is
their first. `ConsoleReadinessBrowserTest` also failed that night (a 404 on the readiness route); item 254, landed
after the run, moved the test to readiness's own route, and the next run shows whether that fixed it.

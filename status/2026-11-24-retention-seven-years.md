# 2026-11-24: the audit archive keeps records seven years (backlog 264)

Branch `worktree-retention-seven-years`. Paul answered item 264's question with "7 years", then "nuna should have a 7
year policy". D23 is amended: seven years is nuna's own retention policy for the audit archive, above NYDFS 500.6(b)'s
three years for cybersecurity audit trails. The floor and the default are 2,557 days (was 1,827) in
`ObjectLockArchive` and `AuditSettings`, with the tests, the console's archive line, the design files, the build plan
and the bite `bites/M7/archive-short-retention.patch` changed to match. `LockedArchiveTest` and the bite are heavy:
the overnight run runs them. The fast build ran at landing.

# 2026-11-24: the demo README says who can grant to which group (backlog 249)

`demo/README.md` now says that no one grants to a group they belong to, so `presenter` can grant to none of the five
demo groups and `gary` to neither of his own, and that a grant is shown by granting to a new group name such as
`demo-readers`. It also says how to show the separation-of-duties refusal: `gary` granting `manage-tags` to
`demo-analysts`. Checked against `GrantRoutes.java` (`change`), not live: the demo was down for item 245's browser
tests. Markdown only, so no build.

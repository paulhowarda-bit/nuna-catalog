# 2026-11-24: the grant preview route (backlog 246)

`POST /management/v1/catalogs/{catalog}/grants/preview` takes a grant's body and answers what the grant route would
do, writing nothing: `admitted`; `unchanged`, with `granted-by` and `granted-at`; or `refused`, with `refusal`
(`privilege-not-on-object`, `self-grant`, `separation-of-duties`) and `reason`, the grant route's own message. It is
for Atelier's grant dialog (backlog 248). The grant route and the preview now share one path in `GrantRoutes`: the
body read and resolved and the caller's manage-grants checked (`asked`), the checks before the write in one order
(`refusal`), and the separation check (`admits`), which the grant route asks inside the store's write and the
preview over the group's grants it reads. A caller that may not grant there is refused as the grant route refuses
it, audited as `previewGrant`; the answers are not audited, as the batch check's are not.

The grant route now refuses `create` on a table or view, which means nothing there: 400, unaudited, a mistake in
the request rather than a refused one. A revoke still removes one granted before.

catalog-security reviewed the route before landing: nothing blocks it; no hidden name, membership or grant reaches a
caller beyond what `/on`, `/effective` and the grant route's own 403s already give, and the audit split is right.
Acted on:

- `Separation.refusal` named the first conflict in the order the store read the group's grants, which differs
  between the grant's read (no order) and the preview's (the listing's order), so the two could name different
  privileges. It now names the first in `Privilege`'s order.
- The preview's javadoc records what it discloses: a caller with manage-grants on any one securable learns, for any
  group it names, which duties the group holds in the catalog (one privilege each, never where), with no record,
  where the grant route leaves a `Denial` or a grant for each try. Not changed: backlog 246 asks for unaudited
  answers, and D18's separation is catalog-wide.
- Bites the review found missing, below.
- `write` on a view, which gives only `select` there yet counts as a data duty, is backlog 258.

Checked:

- `GrantPreviewTest`, new, 11 tests, Java only over a store double that writes a grant as the stores do, reading
  the group's grants in another order than the listing: each answer and each refusal; the grant route does what the
  preview said, case by case; a preview writes and audits nothing; a refused caller is answered as at the grant
  route and audited; malformed bodies answer alike. Preview added to `GrantHiddenNamesTest`'s hidden-name and
  hidden-catalog cases (11 tests); `SeparationTest` gains the order case (8). `GrantRaceTest` (3) still green.
- `MutatingRoutesAuditTest` (heavy, not run here) lists the route among the POST routes that change nothing.
- Bites, new in `bites/D18/`: `grant-preview-allows-self-grant` (the preview skips the checks before the write),
  `grant-create-on-table`, `grant-preview-catalog-shown-to-outsiders`, `grant-preview-skips-separation`,
  `grant-self-grant-check-dropped` (the shared check, on a fast test) and `separation-reason-in-read-order`.
  Recorded again against the shared path, same breaks: `D18/grant-catalog-shown-to-outsiders`,
  `D18/separation-checked-outside-the-write`, `M5/self-grant-allowed`. Run with `bites/run.sh`: every one but
  `M5/self-grant-allowed` (its test, `SecurityGateTest`, is heavy; the overnight run runs it).

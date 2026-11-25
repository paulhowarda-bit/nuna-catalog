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

Checked:

- `GrantPreviewTest`, new, 11 tests, Java only over a store double that writes a grant as the stores do: each
  answer and each refusal; the grant route does what the preview said, case by case; a preview writes and audits
  nothing; a refused caller is answered as at the grant route and audited; malformed bodies answer alike. Preview
  added to `GrantHiddenNamesTest`'s hidden-name and hidden-catalog cases (11 tests). Both green.
- `MutatingRoutesAuditTest` (heavy) lists the route among the POST routes that change nothing.
- Bites: `bites/D18/grant-preview-allows-self-grant.patch` (the preview skips the grant route's checks before the
  write) and `bites/D18/grant-create-on-table.patch` (create admitted on a table or view), both biting. Recorded
  again against the shared path, same breaks: `D18/grant-catalog-shown-to-outsiders`,
  `D18/separation-checked-outside-the-write`, `M5/self-grant-allowed`.

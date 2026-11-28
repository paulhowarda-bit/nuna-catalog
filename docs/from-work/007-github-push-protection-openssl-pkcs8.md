<!-- Transcribed 2026-11-26 from a photo of the work machine's copy
(for-paul/pending/007-github-s-push-protection-refuses-the-openssl-pkcs8.md). Its last sentence was cut off in the
photo. The refused command is described in words rather than quoted, so this copy does not trip the scanner
it describes. What we did with it: backlog 292. -->

# GitHub's push protection refuses the openssl pkcs8 command in the 2026-11-26 drop

Added 2026-11-26.

## What

The firm's GitHub refused the push of your drop of 2026-11-26 (`nuna-catalog-main (5).zip`) for one finding,
`HIGH_CONFIDENCE_ECS_CLIENT_SECRET_FORMAT_VALUE`, in three places that all hold the same command, the one that turns
the catalog's database client key into PKCS #8 DER: `openssl pkcs8 -topk8 -nocrypt`, then the input key, then
`-outform DER`, then the output file. [Quoted in work's note; described here.]

It is not a secret: the scanner matches the text's shape. The same command with its arguments in another order was
accepted on 2026-11-25:

    openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -in client.key -out client.pk8

## Why it matters

Your tree cannot go into work's GitHub until those three lines change, and `./gradlew secretShapes` does not catch
this shape: a copy of it found nothing in your drop, yet the push was refused.

## Evidence

- The refusal (2026-11-26), by the scanner's name and place: `demo/demo.sh:632` (`db_tls`), `deploy/README.md:138`
  (the `client.pk8` row of the database certificate table) and `docs/install-at-work.md:311`.
- With the three lines in the order above, and nothing else changed, the next push was accepted (2026-11-26).
- The order above is what Nuna's own `db_tls` used, which went into GitHub on 2026-11-25.

## Suggested change

1. In the three places, write the command as
   `openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -in client.key -out client.pk8` (the same conversion:
   `-inform PEM` is openssl's default).
2. Optionally, teach `secretShapes` the refused shape, so the build stops it before a push. What exactly the scanner
   matches is not known: only that the input key given before `-outform DER` was refused and [the photo ends here]

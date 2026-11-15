# 2026-11-15, why the demo runs without TLS

Paul, 2026-11-15: the demo holds only test data, and it runs without TLS by choice, because self-signed
certificates do not work well at work. Its hookup to work's certificate authority, identity provider and AWS roles
can only be done there.

- `docs/install-at-work.md`, section 10, now gives that reason. It also says what the hookup means for sign-in:
  the console, kafbat, CloudBeaver's sign-in proxy and Studio's SAML setting point at work's identity provider
  instead of the demo's Keycloak, and SingleStore gets that provider's signing certificate.
- `demo/README.md` no longer suggests TLS and a shorter Keycloak SAML lifetime for work. It records the hour-long
  replay of Studio's SAML response as accepted for test data, and a new "At work" line says the Keycloak setup is
  a stand-in, replaced on site.

Markdown only, so no build.

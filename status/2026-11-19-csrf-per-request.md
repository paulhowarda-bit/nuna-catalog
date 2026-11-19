# 2026-11-19, CloudBeaver's sign-in proxy keeps a CSRF cookie per sign-in (backlog 64)

Work reported that signing in to CloudBeaver sometimes ends on oauth2-proxy's 500 page at `/oauth2/callback`: the
service worker's sign-in overwrote the page's PKCE code verifier, which oauth2-proxy keeps in one CSRF cookie. The
`signin` container in `demo/k8s/cloudbeaver.yaml` now sets `OAUTH2_PROXY_COOKIE_CSRF_PER_REQUEST: "true"`, as work's
`main` does (6060d6c there).

Checked by running `quay.io/oauth2-proxy/oauth2-proxy:v7.15.6` with a cut-down copy of the demo's alpha config and
calling `/oauth2/start` twice: without the setting both calls set `nuna_cloudbeaver_signin_csrf`; with it they set
`nuna_cloudbeaver_signin_sBNqN4TI_csrf` and `nuna_cloudbeaver_signin_P7f_JRGG_csrf`.

Not covered: the race was not reproduced at home, and the running demo has the old setting until `demo/demo.sh up`
rebuilds it.

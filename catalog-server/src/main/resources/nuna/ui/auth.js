// Signing in. The console is an ordinary API caller with a bearer token from the IdP:
//  - production: OIDC authorization code with PKCE (a public client, no secret) against nuna.ui.oidc;
//  - development: a pasted token, offered only when the server sets nuna.ui.dev-token-login=true.
// The token lives in sessionStorage (this tab only, gone when it closes); never localStorage, never a cookie.
// "Sign out" forgets the token and ends the person's session at the IdP too (D16, amended 2026-11-19).

const TOKEN = 'nuna.token';
const ID_TOKEN = 'nuna.id-token';
const PENDING = 'nuna.oidc.pending';
const SIGNING_OUT = 'nuna.oidc.signing-out';

/** How long the console waits on the IdP before saying it is not answering (Atelier's review, F13; backlog 193). */
export const IDP_WAIT_MS = 15000;

/**
 * A call to the IdP that gives up after IDP_WAIT_MS. A slow or unreachable IdP is named by its host, so the person
 * reads which service is not answering rather than "Loading…" for a minute.
 */
async function idp(url, init) {
  const host = new URL(url).host;
  try {
    return await fetch(url, {...init, credentials: 'omit', signal: AbortSignal.timeout(IDP_WAIT_MS)});
  } catch (error) {
    throw new Error(error && error.name === 'TimeoutError'
      ? `${host} is not answering: no reply in ${IDP_WAIT_MS / 1000} s.`
      : `${host} could not be reached: ${error && error.message || error}.`);
  }
}

/** Forgets a sign-in this tab started and did not finish, so the next "Sign in" starts afresh. */
export function startAgain() {
  sessionStorage.removeItem(PENDING);
  sessionStorage.removeItem(SIGNING_OUT);
}

let onSignedOut = () => {};

export function token() {
  return sessionStorage.getItem(TOKEN);
}

/** Called with a reason when the token is gone or refused. */
export function whenSignedOut(handler) {
  onSignedOut = handler;
}

export function signedOut(reason) {
  sessionStorage.removeItem(TOKEN);
  onSignedOut(reason);
}

export function useToken(value) {
  const trimmed = value.trim().replace(/^Bearer\s+/i, '');
  if (!claims(trimmed)) throw new Error('That is not a JWT (three base64url parts).');
  sessionStorage.setItem(TOKEN, trimmed);
}

/**
 * The token's claims, decoded without checking the signature: for showing who is signed in and when the
 * token expires, never for deciding anything. The server checks the token on every call.
 */
export function claims(jwt = token()) {
  if (!jwt) return null;
  const parts = jwt.split('.');
  if (parts.length !== 3) return null;
  try {
    const json = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(parts[1].length / 4) * 4, '='));
    return JSON.parse(new TextDecoder().decode(Uint8Array.from(json, c => c.charCodeAt(0))));
  } catch {
    return null;
  }
}

/** Whether there is a token that has not expired by its own claim. */
export function signedIn() {
  const c = claims();
  return Boolean(c) && (typeof c.exp !== 'number' || c.exp * 1000 > Date.now());
}

// ---- OIDC authorization code with PKCE ----

function base64url(bytes) {
  let text = '';
  for (const b of new Uint8Array(bytes)) text += String.fromCharCode(b);
  return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function random(n) {
  return base64url(crypto.getRandomValues(new Uint8Array(n)));
}

async function discovery(oidc) {
  const issuer = oidc.issuer.replace(/\/+$/, '');
  const response = await idp(issuer + '/.well-known/openid-configuration', {});
  if (!response.ok) throw new Error(`Your organisation's sign-in service did not answer as expected (${response.status}).`);
  return response.json();
}

function redirectUri(oidc) {
  return oidc['redirect-uri'] || new URL('/ui/', location.origin).toString();
}

/** Sends the browser to the IdP. */
export async function startSignIn(oidc) {
  const meta = await discovery(oidc);
  const verifier = random(32);
  const state = random(16);
  const challenge = base64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  sessionStorage.setItem(PENDING, JSON.stringify({state, verifier, tokenEndpoint: meta.token_endpoint}));
  const url = new URL(meta.authorization_endpoint);
  url.search = new URLSearchParams({
    response_type: 'code', client_id: oidc['client-id'], redirect_uri: redirectUri(oidc),
    scope: oidc.scopes.join(' '), state, code_challenge: challenge, code_challenge_method: 'S256',
  }).toString();
  location.assign(url.toString());
}

/**
 * Finishes a sign-in when the IdP sent the browser back with ?code=&state=. Returns true if it did. The code
 * and state are removed from the address bar either way.
 */
export async function finishSignIn(oidc) {
  const params = new URLSearchParams(location.search);
  if (!params.has('code') && !params.has('error')) return false;
  history.replaceState(null, '', location.pathname + location.hash);
  const pending = JSON.parse(sessionStorage.getItem(PENDING) || 'null');
  sessionStorage.removeItem(PENDING);
  if (params.has('error')) {
    throw new Error(`Your organisation's sign-in refused: ${params.get('error_description') || params.get('error')}.`);
  }
  if (!pending || pending.state !== params.get('state')) {
    throw new Error('This sign-in was not started in this tab, or has expired. Start again.');
  }
  const response = await idp(pending.tokenEndpoint, {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({
      grant_type: 'authorization_code', code: params.get('code'), redirect_uri: redirectUri(oidc),
      client_id: oidc['client-id'], code_verifier: pending.verifier,
    }),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || !body.access_token) {
    throw new Error(`Signing in did not finish: your organisation's sign-in answered ${response.status} ${body.error || ''}`.trim());
  }
  sessionStorage.setItem(TOKEN, body.access_token);
  // Kept only to name the session when signing out, so the IdP ends it without asking (backlog 193).
  if (body.id_token) sessionStorage.setItem(ID_TOKEN, body.id_token);
  return true;
}

// ---- signing out, here and at the IdP (OpenID Connect RP-initiated logout) ----

/**
 * The IdP's end-session URL from its discovery document, or null if it names no end_session_endpoint. The IdP sends
 * the browser back to the console's page, the sign-in's redirect URI, which its client must also allow after a
 * logout. It names the session by the sign-in's ID token (id_token_hint), so Keycloak and PingFederate end it in one
 * step; without one, they ask the person to confirm on their own page (Atelier's review, F13; backlog 193).
 */
export function endSessionUrl(meta, oidc, idToken) {
  if (!meta.end_session_endpoint) return null;
  const url = new URL(meta.end_session_endpoint);
  url.searchParams.set('client_id', oidc['client-id']);
  if (idToken) url.searchParams.set('id_token_hint', idToken);
  url.searchParams.set('post_logout_redirect_uri', redirectUri(oidc));
  return url.toString();
}

/**
 * Signs out: forgets the token, then sends the browser to the IdP to end the person's session there, so the next
 * sign-in asks for a password again; resolves to null as the browser leaves. Resolves to a message, and stays,
 * when no IdP is configured or its session cannot be ended: its discovery document names no end_session_endpoint,
 * or cannot be read.
 */
export async function signOut(oidc) {
  const idToken = sessionStorage.getItem(ID_TOKEN);
  sessionStorage.clear();
  if (!oidc) return 'Signed out.';
  const host = new URL(oidc.issuer).host;
  let url;
  try {
    url = endSessionUrl(await discovery(oidc), oidc, idToken);
  } catch (error) {
    return `Signed out of nuna, but ${host} did not end your session there (${error.message}). `
      + 'Signing in again may not ask for a password.';
  }
  if (!url) {
    return `Signed out of nuna. ${host} offers no way to end your session there, so signing in again may not ask `
      + 'for a password.';
  }
  sessionStorage.setItem(SIGNING_OUT, 'true');
  location.assign(url);
  return null;
}

/** Whether this page is the IdP sending the browser back after signOut; true once only. */
export function backFromSignOut() {
  const back = sessionStorage.getItem(SIGNING_OUT) !== null;
  sessionStorage.removeItem(SIGNING_OUT);
  return back;
}

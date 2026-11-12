// Signing in. The console is an ordinary API caller with a bearer token from the IdP:
//  - production: OIDC authorization code with PKCE (a public client, no secret) against keel.ui.oidc;
//  - development: a pasted token, offered only when the server sets keel.ui.dev-token-login=true.
// The token lives in sessionStorage (this tab only, gone when it closes); never localStorage, never a cookie.

const TOKEN = 'keel.token';
const PENDING = 'keel.oidc.pending';

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
  const response = await fetch(issuer + '/.well-known/openid-configuration', {credentials: 'omit'});
  if (!response.ok) throw new Error(`The IdP's discovery document answered ${response.status}`);
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
    throw new Error(`The IdP refused the sign-in: ${params.get('error')} ${params.get('error_description') || ''}`);
  }
  if (!pending || pending.state !== params.get('state')) {
    throw new Error('The sign-in answer does not match a sign-in this tab started; start again.');
  }
  const response = await fetch(pending.tokenEndpoint, {
    method: 'POST', credentials: 'omit',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({
      grant_type: 'authorization_code', code: params.get('code'), redirect_uri: redirectUri(oidc),
      client_id: oidc['client-id'], code_verifier: pending.verifier,
    }),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || !body.access_token) {
    throw new Error(`The IdP's token endpoint answered ${response.status} ${body.error || ''}`);
  }
  sessionStorage.setItem(TOKEN, body.access_token);
  return true;
}

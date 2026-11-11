// Calls to the catalog's own APIs, as any client makes them: a bearer token, nothing else. The server
// decides every permission; this module only reports what it answered.

import {token, signedOut} from './auth.js';

export class ApiError extends Error {
  constructor(status, type, message) {
    super(message);
    this.status = status;
    this.type = type;
  }
}

/** A JSON request; resolves to the parsed body (null for 204), rejects with ApiError on any non-2xx. */
export async function call(path, {method = 'GET', body, headers = {}} = {}) {
  const sent = Object.assign({Accept: 'application/json', Authorization: 'Bearer ' + token()}, headers);
  if (body !== undefined) sent['Content-Type'] = 'application/json';
  const response = await fetch(path, {
    method, headers: sent, body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store', credentials: 'omit', redirect: 'error',
  });
  if (response.status === 204) return null;
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (!response.ok) {
    const error = json && json.error;
    if (response.status === 401) signedOut('The catalog did not accept the token (401). Sign in again.');
    throw new ApiError(response.status, error ? error.type : response.statusText,
      error ? error.message : text.slice(0, 500));
  }
  return json;
}

/** A namespace as a path segment: levels joined by the unit separator 0x1F, each URL-encoded. */
export function ns(levels) {
  return levels.map(encodeURIComponent).join('%1F');
}

/** Every page of an Iceberg REST listing; `field` names the array in each page. */
export async function all(path, field) {
  const items = [];
  let pageToken = null;
  for (let page = 0; page < 100; page++) {
    const sep = path.includes('?') ? '&' : '?';
    const body = await call(path + (pageToken ? sep + 'pageToken=' + encodeURIComponent(pageToken) : ''));
    items.push(...(body[field] || []));
    pageToken = body['next-page-token'];
    if (!pageToken) return items;
  }
  return items;
}

// ---- the catalog, by its REST prefix ----

const prefixes = new Map();

/** The REST prefix of a warehouse, from /v1/config. */
export async function prefix(warehouse) {
  if (!prefixes.has(warehouse)) {
    const config = await call('/v1/config?warehouse=' + encodeURIComponent(warehouse));
    prefixes.set(warehouse, (config.overrides && config.overrides.prefix) || warehouse);
  }
  return prefixes.get(warehouse);
}

export async function catalogs() {
  return (await call('/management/v1/catalogs')).catalogs.map(c => c.name);
}

export async function namespaces(catalog, parent) {
  const p = await prefix(catalog);
  const query = parent.length ? '?parent=' + ns(parent) : '';
  return all(`/v1/${encodeURIComponent(p)}/namespaces${query}`, 'namespaces');
}

export async function namespace(catalog, levels) {
  const p = await prefix(catalog);
  return call(`/v1/${encodeURIComponent(p)}/namespaces/${ns(levels)}`);
}

export async function tables(catalog, levels) {
  const p = await prefix(catalog);
  return all(`/v1/${encodeURIComponent(p)}/namespaces/${ns(levels)}/tables`, 'identifiers');
}

export async function views(catalog, levels) {
  const p = await prefix(catalog);
  return all(`/v1/${encodeURIComponent(p)}/namespaces/${ns(levels)}/views`, 'identifiers');
}

/**
 * loadTable with every snapshot. The catalog vends storage credentials with a load by default (D21); the
 * console never uses them, so they are dropped here and never shown or kept.
 */
export async function table(catalog, levels, name) {
  const p = await prefix(catalog);
  const loaded = await call(`/v1/${encodeURIComponent(p)}/namespaces/${ns(levels)}/tables/`
    + `${encodeURIComponent(name)}?snapshots=all`, {headers: {'X-Iceberg-Access-Delegation': 'none'}});
  const credentials = Boolean(loaded['storage-credentials'] && loaded['storage-credentials'].length)
    || Object.keys(loaded.config || {}).some(key => key.startsWith('s3.') || key.startsWith('client.'));
  delete loaded['storage-credentials'];
  delete loaded.config;
  loaded.credentialsDiscarded = credentials;
  return loaded;
}

export async function view(catalog, levels, name) {
  const p = await prefix(catalog);
  return call(`/v1/${encodeURIComponent(p)}/namespaces/${ns(levels)}/views/${encodeURIComponent(name)}`);
}

// ---- management API ----

/** The management path of a catalog, namespace, table or view. */
export function managed(target) {
  let path = '/management/v1/catalogs/' + encodeURIComponent(target.catalog);
  if (target.namespace && target.namespace.length) path += '/namespaces/' + ns(target.namespace);
  if (target.kind === 'table') path += '/tables/' + encodeURIComponent(target.name);
  if (target.kind === 'view') path += '/views/' + encodeURIComponent(target.name);
  return path;
}

export function governance(target) {
  return call(managed(target) + '/governance');
}

export function recentAudit(target, limit) {
  return call(managed(target) + '/audit?limit=' + limit);
}

/** The management API's securable: {"type", "namespace", "name"}. */
export function securable(target) {
  const out = {type: target.kind};
  if (target.kind !== 'catalog') out.namespace = target.namespace;
  if (target.kind === 'table' || target.kind === 'view') out.name = target.name;
  return out;
}

export function grantsOn(target) {
  const query = new URLSearchParams({type: target.kind});
  for (const level of target.kind === 'catalog' ? [] : target.namespace) query.append('namespace', level);
  if (target.kind === 'table' || target.kind === 'view') query.set('name', target.name);
  return all(`/management/v1/catalogs/${encodeURIComponent(target.catalog)}/grants/on?${query}`, 'grants');
}

export function grantsOfGroups(catalog, groups) {
  const query = new URLSearchParams();
  for (const group of groups) query.append('group', group);
  const q = query.toString();
  return all(`/management/v1/catalogs/${encodeURIComponent(catalog)}/grants${q ? '?' + q : ''}`, 'grants');
}

export function grant(target, group, privilege) {
  return call(`/management/v1/catalogs/${encodeURIComponent(target.catalog)}/grants`,
    {method: 'POST', body: {securable: securable(target), group, privilege}});
}

export function revoke(catalog, securableSpec, group, privilege) {
  return call(`/management/v1/catalogs/${encodeURIComponent(catalog)}/grants/revoke`,
    {method: 'POST', body: {securable: securableSpec, group, privilege}});
}

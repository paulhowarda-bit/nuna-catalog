// Calls to the catalog's own APIs, as any client makes them: a bearer token, nothing else. The server decides
// every permission; this module only reports what it answered, with the request id it stamped on the answer.

import {token, signedOut} from './auth.js';

export class ApiError extends Error {
  constructor(status, type, message, requestId) {
    super(message);
    this.status = status;
    this.type = type;
    this.requestId = requestId;
    this.at = Date.now();
  }
}

const STATUS_TEXT = {200: 'OK', 201: 'Created', 204: 'No Content'};

/**
 * A JSON request. Resolves to {status, statusText, requestId, body} (body null for 204); rejects with ApiError on
 * any non-2xx, carrying the server's own type and message and its X-Request-ID.
 */
export async function send(path, {method = 'GET', body, headers = {}} = {}) {
  const sent = Object.assign({Accept: 'application/json', Authorization: 'Bearer ' + token()}, headers);
  if (body !== undefined) sent['Content-Type'] = 'application/json';
  const response = await fetch(path, {
    method, headers: sent, body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store', credentials: 'omit', redirect: 'error',
  });
  const requestId = response.headers.get('X-Request-ID');
  const text = response.status === 204 ? '' : await response.text();
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
      error ? error.message : text.slice(0, 500), requestId);
  }
  return {status: response.status, statusText: STATUS_TEXT[response.status] || response.statusText, requestId, body: json};
}

/** A JSON request that resolves to the parsed body. */
export async function call(path, options) {
  return (await send(path, options)).body;
}

/** A namespace as a path segment: levels joined by the unit separator 0x1F, each URL-encoded. */
export function ns(levels) {
  return levels.map(encodeURIComponent).join('%1F');
}

/** Every page of a listing; `field` names the array in each page. */
export async function all(path, field, maxPages = 100) {
  const items = [];
  let pageToken = null;
  for (let page = 0; page < maxPages; page++) {
    const sep = path.includes('?') ? '&' : '?';
    const body = await call(path + (pageToken ? sep + 'pageToken=' + encodeURIComponent(pageToken) : ''));
    items.push(...(body[field] || []));
    pageToken = body['next-page-token'];
    if (!pageToken) return items;
  }
  return items;
}

// ---- Iceberg REST, by the catalog's prefix ----

const prefixes = new Map();

/** The REST prefix of a warehouse, from /v1/config. */
export async function prefix(warehouse) {
  if (!prefixes.has(warehouse)) {
    const config = await call('/v1/config?warehouse=' + encodeURIComponent(warehouse));
    prefixes.set(warehouse, (config.overrides && config.overrides.prefix) || warehouse);
  }
  return prefixes.get(warehouse);
}

async function rest(catalog) {
  return '/v1/' + encodeURIComponent(await prefix(catalog));
}

/** The catalogs the caller holds something on: [{name, ...}]. */
export async function catalogs() {
  return (await call('/management/v1/catalogs')).catalogs;
}

export async function namespaces(catalog, parent) {
  const query = parent.length ? '?parent=' + ns(parent) : '';
  return all(`${await rest(catalog)}/namespaces${query}`, 'namespaces');
}

export async function namespace(catalog, levels) {
  return call(`${await rest(catalog)}/namespaces/${ns(levels)}`);
}

/** Sets and removes a namespace's properties through Iceberg REST. */
export async function namespaceProperties(catalog, levels, updates, removals) {
  return send(`${await rest(catalog)}/namespaces/${ns(levels)}/properties`,
    {method: 'POST', body: {updates: updates || {}, removals: removals || []}});
}

export async function tables(catalog, levels) {
  return all(`${await rest(catalog)}/namespaces/${ns(levels)}/tables`, 'identifiers');
}

export async function views(catalog, levels) {
  return all(`${await rest(catalog)}/namespaces/${ns(levels)}/views`, 'identifiers');
}

/**
 * loadTable with every snapshot. The console asks for no storage credentials, and the catalog honours that from
 * any caller (D21, amended 2026-11-19), so no storage keys reach the browser.
 */
export async function table(catalog, levels, name) {
  return call(`${await rest(catalog)}/namespaces/${ns(levels)}/tables/${encodeURIComponent(name)}?snapshots=all`,
    {headers: {'X-Iceberg-Access-Delegation': 'none'}});
}

/**
 * Sets and removes a table's properties in one commit, as an engine commits them: asserted against the table's UUID,
 * so a table dropped and made again under the name is never changed by a page loaded before.
 */
export async function tableProperties(catalog, levels, name, uuid, updates, removals) {
  const changes = [];
  if (Object.keys(updates).length) changes.push({action: 'set-properties', updates});
  if (removals.length) changes.push({action: 'remove-properties', removals});
  return send(`${await rest(catalog)}/namespaces/${ns(levels)}/tables/${encodeURIComponent(name)}`,
    {method: 'POST', body: {requirements: [{type: 'assert-table-uuid', uuid}], updates: changes}});
}

export async function view(catalog, levels, name) {
  return call(`${await rest(catalog)}/namespaces/${ns(levels)}/views/${encodeURIComponent(name)}`);
}

// ---- the management API ----

const enc = encodeURIComponent;

/** The management path of a catalog, namespace, table or view. */
export function managed(target) {
  let path = '/management/v1/catalogs/' + enc(target.catalog);
  if (target.namespace && target.namespace.length) path += '/namespaces/' + ns(target.namespace);
  if (target.kind === 'table') path += '/tables/' + enc(target.name);
  if (target.kind === 'view') path += '/views/' + enc(target.name);
  return path;
}

export const governance = target => call(managed(target) + '/governance');

export const setAttribute = (target, attribute, value, provenance) =>
  send(managed(target) + '/attributes/' + enc(attribute), {method: 'PUT', body: {value, provenance}});

export const placeHold = (target, reason) => send(managed(target) + '/holds', {method: 'POST', body: {reason}});

/** Releases a hold on the level it was placed on. */
export const releaseHold = (target, id) => send(managed(target) + '/holds/' + enc(id), {method: 'DELETE'});

export const freeze = (target, reason) => send(managed(target) + '/dr-freeze', {method: 'PUT', body: {reason}});

export const unfreeze = target => send(managed(target) + '/dr-freeze', {method: 'DELETE'});

export const policy = target => call(managed(target) + '/maintenance-policy');

export const setPolicy = (target, keys) => send(managed(target) + '/maintenance-policy', {method: 'PUT', body: keys});

export const removePolicy = target => send(managed(target) + '/maintenance-policy', {method: 'DELETE'});

export const recentAudit = (target, limit) => call(managed(target) + '/audit?limit=' + limit);

/**
 * A table's or view's metadata log, newest first, through a catalog ref when `ref` names one (item 109), a page at a time (item 130): {pointer, journal, next-page-token},
 * each journal entry the commit that wrote one metadata file, with who made it. Needs select on the object.
 */
export const metadataLog = (catalog, id, pageToken, ref) => call(`/management/v1/catalogs/${enc(catalog)}/objects/${enc(id)}`
  + `/metadata-log?pageSize=25${pageToken ? '&pageToken=' + enc(pageToken) : ''}${ref ? '&ref=' + enc(ref) : ''}`);

/** A catalog's properties (item 131): its name, legal entity, whether a KMS key is set, and its storage profile. */
export const catalogProperties = catalog => call(`/management/v1/catalogs/${enc(catalog)}/properties`);

/** A catalog's governance in figures (item 128): tables classified, closed, held and frozen; regulated areas. */
export const governanceSummary = catalog => call(`/management/v1/catalogs/${enc(catalog)}/governance/summary`);

/** Every hold and freeze in a catalog (item 128): those standing, and those the store recorded lifted in the last 30 days (item 133). */
export const holdsAndFreezes = catalog => call(`/management/v1/catalogs/${enc(catalog)}/governance/holds`);

/** An object's export: its pointer, current metadata and commit journal, by the object's id. */
export const exportObject = (catalog, id) =>
  call(`/management/v1/catalogs/${enc(catalog)}/objects/${enc(id)}/export`);

/** The management API's securable: {"type", "namespace", "name"}. */
export function securable(target) {
  const out = {type: target.kind};
  if (target.kind !== 'catalog') out.namespace = target.namespace;
  if (target.kind === 'table' || target.kind === 'view') out.name = target.name;
  return out;
}

/**
 * Every grant that reaches an object (item 129): on its catalog, each namespace above it and on it, from the catalog
 * down, each with the securable it was granted on, and the deployment's grant-admin groups. Needs manage-grants on
 * the object or above it.
 */
export function effectiveGrants(target) {
  const query = new URLSearchParams({type: target.kind});
  for (const level of target.kind === 'catalog' ? [] : target.namespace) query.append('namespace', level);
  if (target.kind === 'table' || target.kind === 'view') query.set('name', target.name);
  return call(`/management/v1/catalogs/${enc(target.catalog)}/grants/effective?${query}`);
}

/** The grants of the named groups, or of the caller's own groups when none is named. */
export function grantsOfGroups(catalog, groups) {
  const query = new URLSearchParams();
  for (const group of groups) query.append('group', group);
  const q = query.toString();
  return all(`/management/v1/catalogs/${enc(catalog)}/grants${q ? '?' + q : ''}`, 'grants');
}

export const grant = (target, group, privilege) => send(`/management/v1/catalogs/${enc(target.catalog)}/grants`,
  {method: 'POST', body: {securable: securable(target), group, privilege}});

export const revoke = (catalog, securableSpec, group, privilege) =>
  send(`/management/v1/catalogs/${enc(catalog)}/grants/revoke`,
    {method: 'POST', body: {securable: securableSpec, group, privilege}});

/** Every grant in a catalog, for an access review; needs manage-grants on the catalog. */
export const exportGrants = catalog => call(`/management/v1/catalogs/${enc(catalog)}/grants/export`);

// ---- health and maintenance (D38) ----

/** The whole deployment's catalog health over the last day; platform operators only. */
export const catalogHealth = () => call('/management/v1/health/catalog');

/** This server's own figures, with the catalog's database as it finds it (item 139); platform operators only. */
export const serverHealth = () => call('/management/v1/health/server?store=true');

/**
 * The tables and views behind their DR watermark (D37, item 138), by id, `pageSize` a page and at most `maxPages`
 * pages: {tables, more}, `more` when the readout had more than were read. Platform operators only.
 */
export async function drBehind(pageSize, maxPages) {
  const tables = [];
  let pageToken = null;
  for (let page = 0; page < maxPages; page++) {
    const body = await call('/management/v1/dr/watermarks?pageSize=' + pageSize + (pageToken ? '&pageToken=' + enc(pageToken) : ''));
    tables.push(...(body.tables || []));
    pageToken = body['next-page-token'];
    if (!pageToken) return {tables, more: false};
  }
  return {tables, more: true};
}

/** The maintenance worker's recent scans and its last successful one; platform operators only. */
export const scans = () => call('/management/v1/maintenance/scans');

/** One page of the neediest tables the caller may read, in every catalog, neediest first. */
export const neediest = pageToken => call('/management/v1/maintenance/tables?pageSize=200'
  + (pageToken ? '&pageToken=' + enc(pageToken) : ''));

/** Every table the caller may read that needs compaction, neediest first (up to 2,000). */
export async function queue() {
  return all('/management/v1/maintenance/tables?pageSize=200', 'tables', 10);
}

/** A table's health: its latest assessment, last compaction and requests (item 114), with select on it. */
export const tableHealth = target => call(managed(target) + '/health');

/** Asks for a table's compaction or orphan scan now (item 114): `job` is `compaction` or `orphan-scan`. */
export const requestMaintenance = (target, job) => send(managed(target) + '/maintenance/requests',
  {method: 'POST', body: {job}});

/** Every table the caller may select, one row each, in every catalog (item 127): every page, while there is a token. */
export const estate = () => all('/management/v1/maintenance/estate?pageSize=500', 'tables', Infinity);

/** The maintenance nights, newest first: `days` of totals, the newest `jobs` of them with their jobs (item 126). */
export const nights = (days, jobs) => call(`/management/v1/maintenance/nights?days=${days}&jobs=${jobs}`);

// ---- the audit feed (item 125): audit readers only ----

/** A page of a catalog's audit feed from the audit tables, newest first; `q` holds type, principal, path, to, pageToken. */
export function auditFeed(catalog, q) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(q)) if (value) query.set(key, value);
  return call(`/management/v1/catalogs/${enc(catalog)}/audit/feed?${query}`);
}

/** The last day's audit counts in a catalog: by type, the busiest objects, and the deployment's by type. */
export const auditSummary = catalog => call(`/management/v1/catalogs/${enc(catalog)}/audit/summary`);

/** The archive check's status: how far each chain is verified, and this server's last run. */
export const auditArchive = () => call('/management/v1/audit/archive');

/** One record's place in its hash chain, from the archive. */
export const auditChain = (partition, offset, eventId) =>
  call(`/management/v1/audit/chains/${enc(partition)}/records/${enc(offset)}?event-id=${enc(eventId)}`);

// ---- the catalog's branches and tags (item 26, D33; item 109) ----

const refsPath = catalog => `/management/v1/catalogs/${enc(catalog)}/refs`;

/**
 * A catalog's refs, every page: {refs, limits, may}. Each branch has its changed-count; `limits` are nuna.refs.*; `may`
 * says whether the caller may make, renew, delete and merge them. With `expired`, a branch past its expiry that still
 * holds its name is listed too, marked expired.
 */
export async function refs(catalog, expired) {
  const out = {refs: [], limits: {}, may: {}};
  let pageToken = null;
  for (let page = 0; page < 100; page++) {
    const query = new URLSearchParams({pageSize: '100'});
    if (expired) query.set('expired', 'true');
    if (pageToken) query.set('pageToken', pageToken);
    const body = await call(`${refsPath(catalog)}?${query}`);
    out.refs.push(...(body.refs || []));
    out.limits = body.limits || {};
    out.may = body.may || {};
    pageToken = body['next-page-token'];
    if (!pageToken) break;
  }
  return out;
}

/** One ref; a branch's answer lists the tables and views it changed that the caller may describe. */
export const ref = (catalog, name) => call(`${refsPath(catalog)}/${enc(name)}`);

export const makeRef = (catalog, name, kind, from) => send(refsPath(catalog), {method: 'POST', body: {name, kind, from}});

export const renewRef = (catalog, name) => send(`${refsPath(catalog)}/${enc(name)}/renew`, {method: 'POST'});

export const deleteRef = (catalog, name) => send(`${refsPath(catalog)}/${enc(name)}`, {method: 'DELETE'});

/** A branch's merge into main; with `dryRun`, what it would do now, writing nothing. */
export const mergeRef = (catalog, name, dryRun) => send(`${refsPath(catalog)}/${enc(name)}/merge`
  + (dryRun ? '?dry-run=true' : ''), {method: 'POST'});

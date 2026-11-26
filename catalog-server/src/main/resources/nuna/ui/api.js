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
 * JSON as the catalog wrote it (backlog 204): an integer past 2^53, as Iceberg's 64-bit snapshot ids mostly are, is read
 * from its own digits as a BigInt, not rounded to the nearest double, so the console shows, links and copies the id
 * the catalog sent. Safe integers stay numbers; ids compare with ===, key Maps and print with String() either way.
 */
export const parseJson = text => JSON.parse(text, (key, value, context) =>
  typeof value === 'number' && !Number.isSafeInteger(value) && context && /^-?\d+$/.test(context.source)
    ? BigInt(context.source) : value);

/** JSON text of `x`, writing each BigInt `parseJson` read as the digits it was read from. */
export const toJson = (x, space) => JSON.stringify(x, (key, value) =>
  typeof value === 'bigint' ? JSON.rawJSON(String(value)) : value, space);

/**
 * A JSON request. Resolves to {status, statusText, requestId, body} (body null for 204); rejects with ApiError on
 * any non-2xx, carrying the server's own type and message and its X-Request-ID.
 */
export async function send(path, {method = 'GET', body, headers = {}} = {}) {
  const sent = Object.assign({Accept: 'application/json', Authorization: 'Bearer ' + token()}, headers);
  if (body !== undefined) sent['Content-Type'] = 'application/json';
  let response;
  let text;
  try {
    response = await fetch(path, {
      method, headers: sent, body: body === undefined ? undefined : toJson(body),
      cache: 'no-store', credentials: 'omit', redirect: 'error',
    });
    text = response.status === 204 ? '' : await response.text();
  } catch (error) {
    // The network failed: no answer, or not all of it, came back. Marked so the failure box says so (dom.js).
    error.unreached = true;
    throw error;
  }
  const requestId = response.headers.get('X-Request-ID');
  let json = null;
  try {
    json = text ? parseJson(text) : null;
  } catch {
    json = null;
  }
  if (!response.ok) {
    const error = json && json.error;
    if (response.status === 401) signedOut('Your sign-in has expired or was not accepted. Sign in again.');
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

/**
 * The catalogs the caller holds something on, with what its groups may do in each and in the deployment (item 184):
 * {catalogs: [{name, may}], may}.
 */
export const catalogs = () => call('/management/v1/catalogs');

/**
 * Every namespace, table and view whose name holds `fragment`, among those the caller may describe, in every catalog
 * (backlog 198): [{catalog, kind, namespace, name}], the first page, namespaces first.
 */
export const names = fragment => call('/management/v1/names?pageSize=50&q=' + encodeURIComponent(fragment)).then(b => b.names || []);

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

/** Removes an attribute set on the target itself; the levels above keep theirs. */
export const removeAttribute = (target, attribute) => send(managed(target) + '/attributes/' + enc(attribute), {method: 'DELETE'});

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

/** An object's export: its pointer, current metadata and commit journal, by the object's id, through a catalog ref when `ref` names one (item 172). */
export const exportObject = (catalog, id, ref) =>
  call(`/management/v1/catalogs/${enc(catalog)}/objects/${enc(id)}/export${ref ? '?ref=' + enc(ref) : ''}`);

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

/**
 * What a grant would do, writing nothing (backlog 246): {securable, group, privilege, answer}, the answer `admitted`,
 * `unchanged` (with granted-by and granted-at) or `refused` (with refusal and reason). Not audited; a caller who may
 * not manage grants there is refused as the grant is, audited.
 */
export const previewGrant = (target, group, privilege) => call(`/management/v1/catalogs/${enc(target.catalog)}/grants/preview`,
  {method: 'POST', body: {securable: securable(target), group, privilege}});

/** Whether the caller's groups hold `privilege` on the catalog itself, from the batch check, which is not audited. */
export const mayOnCatalog = (catalog, privilege) => call(`/management/v1/catalogs/${enc(catalog)}/grants/check`,
  {method: 'POST', body: {checks: [{securable: {type: 'catalog'}, privilege}]}})
  .then(body => !!((body.results || [])[0] || {}).allowed);

/**
 * The groups holding a grant in a catalog, every page, each with its duties and privileges (backlog 247), and the
 * deployment's grant-admin and platform-operator groups: {groups, grantAdmins, operators}. Needs manage-grants on the
 * catalog itself.
 */
export async function grantGroups(catalog) {
  const out = {groups: [], grantAdmins: [], operators: []};
  let pageToken = null;
  for (let page = 0; page < 100; page++) {
    const body = await call(`/management/v1/catalogs/${enc(catalog)}/grants/groups`
      + (pageToken ? '?pageToken=' + enc(pageToken) : ''));
    out.groups.push(...(body.groups || []));
    out.grantAdmins = body['grant-admin-groups'] || [];
    out.operators = body['platform-operator-groups'] || [];
    pageToken = body['next-page-token'];
    if (!pageToken) break;
  }
  return out;
}

export const revoke = (catalog, securableSpec, group, privilege) =>
  send(`/management/v1/catalogs/${enc(catalog)}/grants/revoke`,
    {method: 'POST', body: {securable: securableSpec, group, privilege}});

// ---- row filters and column masks (D20, backlog 115; the console's, backlog 243) ----

const rulesPath = catalog => `/management/v1/catalogs/${enc(catalog)}/row-column-rules`;

/** Every group's rule on a table, with its filter and masks: {table, rules}. Needs manage-grants on the table or above. */
export function rules(target) {
  const query = new URLSearchParams();
  for (const level of target.namespace) query.append('namespace', level);
  query.set('name', target.name);
  return call(`${rulesPath(target.catalog)}?${query}`);
}

/** If-Match for the rule's version as the list gave it, so a rule changed since answers 412 (backlog 244); none for a new rule. */
const ifMatch = version => version === undefined || version === null ? {} : {'If-Match': `"${version}"`};

/**
 * Sets a group's rule on a table, replacing the one it had: `rule` is {group, row-filter, column-masks}; `version`, the
 * listed rule's version when it edits one.
 */
export const setRule = (target, rule, version) => send(rulesPath(target.catalog),
  {method: 'POST', body: Object.assign({table: securable(target)}, rule), headers: ifMatch(version)});

/** Removes a group's rule from a table, only if it is still at the listed `version`. */
export const removeRule = (target, group, version) => send(rulesPath(target.catalog) + '/remove',
  {method: 'POST', body: {table: securable(target), group}, headers: ifMatch(version)});

/** A table's current schema and properties, flagged or not, for anyone who may describe it (backlog 242). */
export const tableSchema = target => call(managed(target) + '/schema');

/** What the caller's own rules do to them on a table: {flagged, rows, masked-columns}; needs select (backlog 242). */
export const myRuleView = target => call(managed(target) + '/row-column-rules/mine');

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

/**
 * Whether the maintenance worker can maintain a catalog, step by step (backlog 251): grant, storage, store and policy,
 * each with its state, and names for those who may modify the catalog or manage its grants and platform operators.
 * Needs describe on the catalog, or a platform operator.
 */
export const readiness = catalog => call(`/management/v1/catalogs/${enc(catalog)}/maintenance/readiness`);

/**
 * What an operator sets up outside the catalog for each storage profile (backlog 253): the worker's reach, its settings
 * for a store it does not reach, and the delete policies with their commands. Platform operators only.
 */
export const storageSetup = () => call('/management/v1/maintenance/storage-setup');

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

/** The archive check's status: this server's last run, its checkpoints and what it found wrong. */
export const auditArchive = () => call('/management/v1/audit/archive');

/** A logged record's place in its server run's hash chain, from the archive (backlog 272). */
export const auditChain = (run, number, eventId) =>
  call(`/management/v1/audit/chains/${enc(run)}/records/${enc(number)}?event-id=${enc(eventId)}`);

// ---- the catalog's branches and tags (item 26, D33; item 109) ----

const refsPath = catalog => `/management/v1/catalogs/${enc(catalog)}/refs`;

/**
 * Which refs keep each table or view at a path, live or dropped, newest first (item 170): {objects}, each with its
 * state, when it was made and dropped, who dropped it (for an audit reader), its refs and `may.remove`.
 */
export const objectRefs = target => call(`/management/v1/catalogs/${enc(target.catalog)}/namespaces/${ns(target.namespace)}`
  + `/objects/${enc(target.name)}/refs`);

/** {@link objectRefs} for the one table or view an id names, until it is purged. */
export const objectRefsById = (catalog, id) => call(`/management/v1/catalogs/${enc(catalog)}/objects/${enc(id)}/refs`);

/**
 * Takes the table or view an id names out of refs (item 156): `{refs: [name, ...]}` or `{'all-refs': true}`. Needs
 * manage-tags on it; answers the object and the refs it left.
 */
export const removeFromRefs = (catalog, id, body) => send(`/management/v1/catalogs/${enc(catalog)}/objects/${enc(id)}/refs/remove`,
  {method: 'POST', body});

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

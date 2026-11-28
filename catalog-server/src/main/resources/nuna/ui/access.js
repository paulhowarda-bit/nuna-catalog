// What the caller's groups may do, from the catalog list (backlog 184): whether they read data, change it, manage
// tags or grants somewhere in each catalog, read the audit feed, or operate the deployment. The console uses it only
// to draw the pages, tabs and buttons a caller can use, so browsing writes no denial; the server still decides every
// call, and a control drawn by mistake gets its refusal as before.

import {claims} from './auth.js';
import {h} from './dom.js';

let byCatalog = new Map();
let deployment = {};
let auditCatalogs = new Set();

/** Keeps what the catalog list answered: {catalogs: [{name, may, audit}], may}. */
export function keepAccess(body) {
  byCatalog = new Map((body.catalogs || []).map(c => [c.name, c.may || {}]));
  auditCatalogs = new Set((body.catalogs || []).filter(c => c.audit).map(c => c.name));
  deployment = body.may || {};
}

/** Whether the catalog holds the audit tables, which are an auditor's work on Audit, not data to land on (backlog 200). */
export const holdsAudit = name => auditCatalogs.has(name);

/** The catalogs a sign-in may land on: every one but the audit tables' own, unless that is all there is (backlog 200). */
export const landingCatalogs = names => names.filter(n => !holdsAudit(n)).length
  ? names.filter(n => !holdsAudit(n)) : names;

/** Whether the caller's groups hold `privilege` (select, modify, manage-grants, manage-tags) somewhere in the catalog. */
export const may = (catalog, privilege) => !!(byCatalog.get(catalog) || {})[privilege];

/** A platform operator: the Service page and the DR freeze. */
export const operates = () => !!deployment.operate;

/**
 * Whether the caller's groups hold some privilege in the catalog, so its namespaces, branches and tags and governance
 * may be listed to them (backlog 325). The list names a catalog only to one who holds something in it, except to a
 * platform operator, who sees every one for its DR freeze: for them, a privilege it names must be held.
 */
export const holdsIn = catalog => byCatalog.has(catalog)
  && (!operates() || Object.values(byCatalog.get(catalog)).some(Boolean));

/** Whether the caller reads data in any catalog, which Overview and Maintenance are about; reading the audit tables
 * is an auditor's work, on Audit, not data of their own (Atelier's re-walk, 2026-11-23; backlog 200). */
export const readsData = () => [...byCatalog].some(([name, m]) => m.select && !auditCatalogs.has(name));

/** Audit: the catalog's feed for audit readers, its own trail and every object's for those who manage its grants. */
export const auditFeedOpen = () => !!deployment['read-audit-feed'];
export const trailOpen = catalog => may(catalog, 'manage-grants');
export const auditOpen = catalog => auditFeedOpen() || trailOpen(catalog);

/** The tab a table or view opens on when the link names none: Access for one who manages the catalog's grants but reads
 * none of its data, as a grant admin does (D18), since Overview would refuse them; else Overview (backlog 245). */
export const openingTab = catalog => may(catalog, 'manage-grants') && !may(catalog, 'select') ? 'access' : '';

/** The caller's groups, from the token, for the words on a page they cannot use. */
export function groupWords() {
  const groups = (claims() || {}).groups;
  return Array.isArray(groups) && groups.length ? groups.join(', ') : 'none';
}

/** A tab the caller's groups cannot use, reached by a link: plain words, and the catalog is never asked. */
export const closedNote = words => h('div', {class: 'empty', 'data-closed': ''}, h('h3', null, words),
  h('p', null, `Your groups: ${groupWords()}. Ask a grant admin if your work needs it.`));

/** The words for a table's or view's data tabs, to a caller whose groups hold no grant that reads it (backlog 325). */
export const unreadWords = target => `Reading this ${target.kind}'s data needs select on it, which your groups do not hold. `
  + 'Its grants and governance are on Access.';

/** The words for an object's Audit tab, closed to the caller. */
export const trailWords = catalog => `An object's audit trail is open to those who manage ${catalog}'s grants.`;

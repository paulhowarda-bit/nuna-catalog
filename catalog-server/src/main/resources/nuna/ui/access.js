// What the caller's groups may do, from the catalog list (backlog 184): whether they read data, change it, manage
// tags or grants somewhere in each catalog, read the audit feed, or operate the deployment. The console uses it only
// to draw the pages, tabs and buttons a caller can use, so browsing writes no denial; the server still decides every
// call, and a control drawn by mistake gets its refusal as before.

import {claims} from './auth.js';
import {h} from './dom.js';

let byCatalog = new Map();
let deployment = {};

/** Keeps what the catalog list answered: {catalogs: [{name, may}], may}. */
export function keepAccess(body) {
  byCatalog = new Map((body.catalogs || []).map(c => [c.name, c.may || {}]));
  deployment = body.may || {};
}

/** Whether the caller's groups hold `privilege` (select, modify, manage-grants, manage-tags) somewhere in the catalog. */
export const may = (catalog, privilege) => !!(byCatalog.get(catalog) || {})[privilege];

/** A platform operator: the Service page and the DR freeze. */
export const operates = () => !!deployment.operate;

/** Whether the caller reads data in any catalog, which Overview and Maintenance are about. */
export const readsData = () => [...byCatalog.values()].some(m => m.select);

/** Audit: the catalog's feed for audit readers, its own trail and every object's for those who manage its grants. */
export const auditFeedOpen = () => !!deployment['read-audit-feed'];
export const trailOpen = catalog => may(catalog, 'manage-grants');
export const auditOpen = catalog => auditFeedOpen() || trailOpen(catalog);

/** The caller's groups, from the token, for the words on a page they cannot use. */
export function groupWords() {
  const groups = (claims() || {}).groups;
  return Array.isArray(groups) && groups.length ? groups.join(', ') : 'none';
}

/** A tab the caller's groups cannot use, reached by a link: plain words, and the catalog is never asked. */
export const closedNote = words => h('div', {class: 'empty', 'data-closed': ''}, h('h3', null, words),
  h('p', null, `Your groups: ${groupWords()}. Ask a grant admin if your work needs it.`));

/** The words for an object's Audit tab, closed to the caller. */
export const trailWords = catalog => `An object's audit trail is open to those who manage ${catalog}'s grants.`;

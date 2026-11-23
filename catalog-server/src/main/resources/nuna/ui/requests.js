// A table's maintenance on request (item 114): "Compact now" and "Scan for orphans now" for a caller whose groups
// hold modify on the table, and each request with its state, waiting or started. The worker takes a request at its
// next scan, inside its cleanup window or outside it, and stops it at the request's deadline. The catalog refuses one
// under a DR freeze, an orphan scan under a legal hold, and a job the table's policy turns off; the dialog shows its
// answer as it came.

import * as api from './api.js';
import {at, code, dialogForm, field, fill, granted, h, icon, openDialog, pill} from './dom.js';

export const JOBS = {
  'compaction': {noun: 'Compaction', button: 'Compact now', verb: 'Compact'},
  'orphan-scan': {noun: 'Orphan scan', button: 'Scan for orphans now', verb: 'Scan'},
};

const lower = value => String(value || '').toLowerCase();

/** Whether a grant made on `on` reaches the table: on the catalog, a namespace above it, or the table itself. */
function reaches(on, target) {
  if (!on) return false;
  if (on.type === 'catalog') return true;
  const levels = on.namespace || [];
  const above = levels.length <= target.namespace.length && levels.every((l, i) => lower(l) === lower(target.namespace[i]));
  if (on.type === 'namespace') return above;
  return on.type === 'table' && above && levels.length === target.namespace.length && lower(on.name) === lower(target.name);
}

const grantsKept = new Map();

/** The caller's own groups' grants in the catalog, asked once a minute at most; none when the catalog refuses. */
function ownGrants(catalog) {
  const hit = grantsKept.get(catalog);
  if (hit && Date.now() - hit.at < 60_000) return hit.promise;
  const promise = api.grantsOfGroups(catalog, []).catch(() => []);
  grantsKept.set(catalog, {at: Date.now(), promise});
  return promise;
}

/** Whether the caller's groups hold modify on the table, which a request needs. */
export async function mayModify(target) {
  return (await ownGrants(target.catalog)).some(g => g.privilege === 'modify' && reaches(g.securable, target));
}

/** Whether the caller's groups hold modify anywhere in the catalog. */
export async function mayModifyAny(catalog) {
  return (await ownGrants(catalog)).some(g => g.privilege === 'modify');
}

const waiting = (requests, job) => (requests || []).some(r => r.job === job && r.state === 'waiting');

/** The table's requests as its health shows them: who asked, when, and whether it waits or has started. */
export function requestsBox(requests) {
  const list = requests || [];
  if (!list.length) return null;
  return h('div', {class: 'granted', role: 'status', id: 'table-health-requests'},
    list.map(r => h('p', {class: 'refusal-h', 'data-job': r.job, 'data-state': r.state}, icon('clock'),
      `${(JOBS[r.job] || {noun: r.job}).noun} requested at ${at(r['requested-at'])} by ${r['requested-by']} `,
      r.state === 'waiting' ? pill('accent', 'waiting') : pill('ok', 'started ' + at(r['started-at'])))),
    list.some(r => r.state === 'waiting') ? h('p', {class: 'fine'}, 'The worker takes a waiting request at its next scan, '
      + 'inside its cleanup window or outside it, once a compaction slot is free. It shows here as started then, and on '
      + 'Maintenance as one of the night\'s jobs.') : null);
}

/** The marks beside a table's name while a request waits. */
export function requestPills(requests) {
  return (requests || []).filter(r => r.state === 'waiting').map(r => h('span', {class: 'pill accent', 'data-requested': r.job},
    icon('clock'), `${(JOBS[r.job] || {noun: r.job}).noun} requested ${at(r['requested-at'])}`));
}

/**
 * The buttons that ask for each job now, for a caller who may modify the table; a job already waiting has its button
 * turned off. `done` runs once the catalog has taken a request.
 */
export function requestButtons(target, requests, done) {
  return Object.entries(JOBS).map(([job, j]) => h('button', {class: 'btn sm', type: 'button', 'data-act': 'request-' + job,
    disabled: waiting(requests, job), onclick: () => askDialog(target, job, done)}, icon('play'), j.button));
}

const INTRO = {
  'compaction': path => ['The worker compacts ', code(path), ' as one Job at its next scan, inside its cleanup window or '
    + 'outside it, in the first of its compaction slots to come free, whatever its last assessment says. The Job stops at '
    + 'the request\'s deadline and keeps every file group it finished.'],
  'orphan-scan': path => ['The worker lists the location of ', code(path), ' at its next scan, inside its cleanup window or '
    + 'outside it, and records each file nothing references, older than its policy\'s orphan-removal.older-than, for '
    + 'deletion once the database\'s PITR window has passed. A scan the request\'s deadline stops records nothing.'],
};

/** Asks for one job now, in a dialog that shows the catalog's answer, a refusal as it came. */
export function askDialog(target, job, done) {
  const j = JOBS[job];
  const path = [...target.namespace, target.name].join('.');
  const body = h('div', null, h('p', null, INTRO[job](path)),
    h('p', {class: 'fine'}, 'Needs ', code('modify'), ' on the table. Refused under a DR freeze, an orphan scan under a '
      + 'legal hold, and a job the table\'s maintenance policy turns off.'),
    dialogForm(null, j.button, async (form, out) => {
      const answer = await api.requestMaintenance(target, job);
      const r = answer.body || {};
      fill(out, granted(`${j.noun} of ${target.name} requested`, answer,
        `Waiting since ${at(r['requested-at'])}, asked by ${r['requested-by']}. The worker takes it at its next scan.`));
      if (done) done();
    }));
  openDialog(`${j.verb} ${target.name} now`, body);
}

/**
 * "Compact a table now" on Maintenance: a table path in the catalog, the queue offered first; then the compaction
 * dialog for it. A path names its namespace's levels and the table, joined by dots.
 */
export function pickDialog(catalog, rows) {
  const input = h('input', {name: 'path', type: 'text', list: 'request-tables', autocomplete: 'off', spellcheck: 'false',
    placeholder: 'A table path, such as ' + (rows[0] ? [...rows[0].namespace, rows[0].name].join('.') : 'sales.orders')});
  const options = h('datalist', {id: 'request-tables'}, rows.map(r => h('option', {value: [...r.namespace, r.name].join('.')})));
  const body = h('div', null, h('p', null, 'Choose a table. Tonight\'s queue is offered first; any table in ', code(catalog),
    ' can be compacted.'),
  dialogForm([field('Table', h('span', {class: 'field mono'}, input)), options], 'Choose', async (form, out) => {
    const path = input.value.trim();
    const row = rows.find(r => [...r.namespace, r.name].join('.') === path);
    const cut = path.lastIndexOf('.');
    if (!row && cut <= 0) {
      fill(out, h('p', {class: 'fine grant-hint'}, 'Name the table with its namespace, such as sales.orders.'));
      return;
    }
    const target = row ? {catalog, kind: 'table', namespace: row.namespace, name: row.name}
      : {catalog, kind: 'table', namespace: path.slice(0, cut).split('.'), name: path.slice(cut + 1)};
    askDialog(target, 'compaction');
  }));
  openDialog('Compact a table now', body);
}

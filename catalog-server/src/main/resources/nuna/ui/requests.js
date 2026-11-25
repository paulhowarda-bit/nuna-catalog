// A table's maintenance on request (item 114): "Compact now" and "Scan for orphans now" for a caller whose groups
// hold modify on the table, and each request with its state, waiting or started. The worker takes a request at its
// next scan, inside its cleanup window or outside it, and stops it at the request's deadline. The catalog refuses one
// under a DR freeze, an orphan scan under a legal hold, and a job the table's policy turns off; the dialog shows its
// answer as it came.

import * as api from './api.js';
import {at, code, dialogForm, field, fill, granted, h, icon, openDialog, pill} from './dom.js';
import {REASONS} from './queue.js';

export const JOBS = {
  'compaction': {noun: 'Compaction', button: 'Compact now', verb: 'Compact'},
  'orphan-scan': {noun: 'Orphan scan', button: 'Scan for orphans now', verb: 'Scan'},
};

const lower = value => String(value || '').toLowerCase();

/** Whether a grant made on `on` reaches the object: on the catalog, a namespace above it, or the object itself. */
function reaches(on, target) {
  if (!on) return false;
  if (on.type === 'catalog') return true;
  const levels = on.namespace || [];
  const above = levels.length <= target.namespace.length && levels.every((l, i) => lower(l) === lower(target.namespace[i]));
  if (on.type === 'namespace') return above;
  return (on.type === 'table' || on.type === 'view') && !!target.name && above && levels.length === target.namespace.length
    && lower(on.name) === lower(target.name);
}

const grantsKept = new Map();

/** The caller's own groups' grants in the catalog, asked once a minute at most; none when the catalog refuses. */
export function ownGrants(catalog) {
  const hit = grantsKept.get(catalog);
  if (hit && Date.now() - hit.at < 60_000) return hit.promise;
  const promise = api.grantsOfGroups(catalog, []).catch(() => []);
  grantsKept.set(catalog, {at: Date.now(), promise});
  return promise;
}

/** The caller's own groups' grants that reach the object, for what its Access tab says they may do (backlog 199). */
export async function heldHere(target) {
  return (await ownGrants(target.catalog)).filter(g => reaches(g.securable, target));
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
  'compaction': path => ['The worker compacts ', code(path), ' at its next scan, in or out of its cleanup window, as soon as '
    + 'a compaction slot is free, whatever its last assessment says. It stops at the request\'s deadline and keeps the work '
    + 'it finished.'],
  'orphan-scan': path => ['The worker lists the location of ', code(path), ' at its next scan, inside its cleanup window or '
    + 'outside it, and records each file nothing refers to that is older than its policy\'s orphan-removal age, for '
    + 'deletion once the database\'s recovery window has passed. A scan stopped at the deadline records nothing.'],
};

/** Asks for one job now, in a dialog that shows the catalog's answer, a refusal as it came. */
export function askDialog(target, job, done) {
  const j = JOBS[job];
  const path = [...target.namespace, target.name].join('.');
  const body = h('div', null, h('p', null, INTRO[job](path)),
    h('p', {class: 'fine'}, 'Needs ', code('modify'), ' on the table. The catalog refuses it during a DR freeze, refuses an '
      + 'orphan scan while the table is on legal hold, and refuses a job the table\'s policy turns off.'),
    dialogForm(null, j.button, async (form, out) => {
      const answer = await api.requestMaintenance(target, job);
      const r = answer.body || {};
      fill(out, granted(`${j.noun} of ${target.name} requested`, answer,
        `Waiting since ${at(r['requested-at'])}, asked by ${r['requested-by']}. The worker takes it at its next scan.`));
      if (done) done();
    }));
  openDialog(`${j.verb} ${target.name} now`, body);
}

/** Tonight's queue reasons in words, for the dropdown: "equality deletes, small files". */
const reasonWords = row => ((row.assessment || {}).reasons || []).map(r => (REASONS[r] || [null, r])[1].toLowerCase()).join(', ');

/** More tables than this the caller may compact, and the dropdown offers tonight's queue and a path field only. */
const LISTED = 300;

/**
 * "Compact a table now" on Maintenance (backlog 222): a dropdown of the tables in the catalog the caller's groups may
 * compact, a modify grant reaching each, tonight's queue first with its reasons, then the rest by path; a frozen one is
 * shown and cannot be chosen. "Another table, by its path…" opens a path field, which shows from the start when the
 * caller may compact more than LISTED tables. Then the compaction dialog for the table chosen. `rows` is tonight's
 * queue in the catalog, `tables` every table in it the caller may read (the estate).
 */
export async function pickDialog(catalog, rows, tables) {
  const grants = (await ownGrants(catalog)).filter(g => g.privilege === 'modify');
  const pathOf = t => [...t.namespace, t.name].join('.');
  const mine = t => grants.some(g => reaches(g.securable, {namespace: t.namespace, name: t.name}));
  const frozen = new Set(tables.filter(t => t.frozen).map(pathOf));
  const queued = rows.filter(mine);
  const inQueue = new Set(queued.map(pathOf));
  const others = tables.filter(t => mine(t) && !inQueue.has(pathOf(t))).sort((a, b) => pathOf(a).localeCompare(pathOf(b)));
  const listed = others.length <= LISTED;
  const byPath = '\u0000path';
  const option = (t, words) => {
    const path = pathOf(t);
    const cold = frozen.has(path) || !!t.frozen;
    return h('option', {value: path, disabled: cold, 'data-frozen': cold ? '' : null},
      [path, words, cold ? 'frozen' : null].filter(Boolean).join(' · '));
  };
  const pick = h('select', {name: 'table'},
    h('option', {value: '', disabled: true, selected: true}, 'Choose a table'),
    queued.length ? h('optgroup', {label: 'Queued for tonight'}, queued.map(r => option(r, reasonWords(r)))) : null,
    listed && others.length ? h('optgroup', {label: 'Other tables you may compact'}, others.map(t => option(t, null))) : null,
    h('option', {value: byPath}, 'Another table, by its path…'));
  const input = h('input', {name: 'path', type: 'text', autocomplete: 'off', spellcheck: 'false',
    placeholder: 'A table path, such as ' + (queued[0] || others[0] ? pathOf(queued[0] || others[0]) : 'sales.orders')});
  const typing = !listed || (!queued.length && !others.length);
  const pathField = field('Path', h('span', {class: 'field mono'}, input), 'Its namespace\'s levels and its name, joined by dots.');
  pathField.hidden = !typing;
  if (typing) pick.value = byPath;
  const body = h('div', null, h('p', null, 'Choose a table in ', code(catalog), ' that your groups may compact. Tonight\'s queue '
    + 'comes first.', listed ? null : ` You may compact ${others.length + queued.length} tables, too many to list: write the path of one `
    + 'not queued.'),
  dialogForm([field('Table', h('span', {class: 'field mono select'}, pick)), pathField], 'Next', async (form, out) => {
    const path = pick.value === byPath ? input.value.trim() : pick.value;
    const row = [...queued, ...others].find(t => pathOf(t) === path);
    const cut = path.lastIndexOf('.');
    if (!row && cut <= 0) {
      fill(out, h('p', {class: 'fine grant-hint'}, 'Name the table with its namespace, such as sales.orders.'));
      return;
    }
    const target = row ? {catalog, kind: 'table', namespace: row.namespace, name: row.name}
      : {catalog, kind: 'table', namespace: path.slice(0, cut).split('.'), name: path.slice(cut + 1)};
    askDialog(target, 'compaction');
  }));
  // Next waits for a table: chosen in the list, or written in the path field (Atelier, 2026-11-24).
  const next = body.querySelector('button[type="submit"]');
  const ready = () => { next.disabled = pick.value === byPath ? !input.value.trim() : !pick.value; };
  pick.addEventListener('change', () => {
    pathField.hidden = pick.value !== byPath;
    if (pick.value === byPath) input.focus();
    ready();
  });
  input.addEventListener('input', ready);
  ready();
  openDialog('Compact a table now', body);
}

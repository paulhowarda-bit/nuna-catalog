// Tonight's queue: the tables the maintenance worker last assessed as needing compaction, neediest first, of
// those the caller may read (GET /management/v1/maintenance/tables). The ice line's lower half draws it: each
// table a mark hanging below the water, as deep as its score. The upper half, every table in shape standing
// above the line, waits on backlog 127, which gives one row per table.

import {$$, bar, h, ordinal, pill, plural, s} from './dom.js';
import {objectHref} from './route.js';
import * as api from './api.js';

export const REASONS = {
  'equality-deletes': ['bad', 'Equality deletes',
    'Equality deletes: every reader must join against them; such a table is compacted first.'],
  'small-files': ['warn', 'Small files',
    'Small files: a partition holds enough small data files to rewrite them into fewer, larger ones.'],
  'deletes': ['warn', 'Deletes',
    'Deletes: more delete files than the policy\'s share of its data files, which every read applies.'],
};

export const reasonPill = reason => pill((REASONS[reason] || ['warn'])[0], (REASONS[reason] || [null, reason])[1]);

/** A queue row's target, for links and the API. */
export const targetOfRow = row => ({catalog: row.catalog, kind: 'table', namespace: row.namespace, name: row.name});

export const keyOfRow = row => [row.catalog, ...row.namespace, row.name].join('.');

const isEq = row => ((row.assessment || {}).reasons || []).includes('equality-deletes');

/** A table's name as the prototype writes it: its namespace muted, a break point, its name. */
export function pathName(levels, name) {
  return [h('span', {class: 'path'}, levels.join('.') + '.'), h('wbr'), name];
}

let cached = null;

/** Every queued table the caller may read, in every catalog; asked once a minute at most. */
export async function queue(fresh) {
  if (!fresh && cached && Date.now() - cached.at < 60_000) return cached.rows;
  const rows = await api.queue();
  cached = {at: Date.now(), rows};
  return rows;
}

export const queueOf = (rows, catalog) => rows.filter(r => r.catalog === catalog);

/** The score bar's scale: the neediest table's score, at least 10. */
export const scaleOf = rows => Math.max(10, ...rows.map(r => Number((r.assessment || {}).score) || 0));

/** The latest assessment time among the rows, or null. */
export function lastAssessed(rows) {
  const times = rows.map(r => Date.parse((r.assessment || {})['assessed-at'])).filter(t => !Number.isNaN(t));
  return times.length ? Math.max(...times) : null;
}

/**
 * The queue below the water: one mark per table, neediest first, grouped by top-level namespace and labelled
 * above the line. `me` lights one table and dims the rest (a table's own page).
 */
export function iceQueue(host, W, rows, {me, label} = {}) {
  const groups = [];
  for (const row of rows) {
    const top = row.namespace[0];
    let g = groups.find(x => x.name === top);
    if (!g) groups.push(g = {name: top, rows: []});
    g.rows.push(row);
  }
  const deepest = scaleOf(rows);
  const Y = me ? 6 : 26;
  const H = Y + 6 + 46 + (me ? 6 : 18);
  const GAP = me ? 0 : 14;
  const step = Math.min(28, (W - 16 - GAP * Math.max(0, groups.length - 1)) / Math.max(1, rows.length));
  const svg = s('svg', {viewBox: `0 0 ${W} ${H}`, role: 'group', 'aria-label': label
    || `Tonight's queue: ${plural(rows.length, 'table')}, neediest first, each as deep as its score`});
  const sink = s('g', {class: 'sink'});
  let x = 8;
  groups.forEach((g, gi) => {
    const x0 = x;
    for (const row of g.rows) {
      const a = row.assessment || {};
      const depth = 6 + 46 * (Number(a.score) || 0) / deepest;
      const mine = me && keyOfRow(row) === me;
      const mx = x + step / 2 - 2;
      const link = s('a', {href: objectHref(targetOfRow(row), 'health'), class: 'qa g' + (gi % 6) + (mine ? ' is-me' : ''),
        'aria-label': `${row.namespace.join('.')}.${row.name}, score ${a.score}`});
      link.append(s('title', null, `${row.catalog}.${row.namespace.join('.')}.${row.name} · score ${a.score} · `
        + (a.reasons || []).map(r => (REASONS[r] || [null, r])[1].toLowerCase()).join(', ')));
      link.append(s('rect', {class: 'hit', x: mx - 6, y: 0, width: 16, height: H}),
        s('rect', {class: 'q' + (isEq(row) ? ' eq' : '') + (me && !mine ? ' dim' : ''), x: mx, y: Y + 1, width: 4, height: depth, rx: 1}));
      sink.append(link);
      x += step;
    }
    if (!me) {
      svg.append(s('text', {class: 'lab', x: x0 + 2, y: Y - 8}, g.name));
      if (gi) svg.append(s('line', {class: 'grp', x1: x0 - GAP / 2, y1: Y - 18, x2: x0 - GAP / 2, y2: Y}));
    }
    x += GAP;
  });
  svg.append(sink, s('line', {class: 'water', x1: 0, y1: Y, x2: W, y2: Y}));
  host.append(svg);
  if (me) {
    const i = rows.findIndex(r => keyOfRow(r) === me);
    host.append(h('p', {class: 'ice-cap'}, i >= 0
      ? `Tonight's queue, neediest first: this table is ${ordinal(i + 1)} of ${rows.length}`
      : `Tonight's queue holds ${plural(rows.length, 'table')}; this one is not among them`));
  } else {
    host.append(h('p', {class: 'ice-cap'}, 'Below the line: queued, depth is score. The tables in shape above it '
      + 'come with one row per table (backlog 127).'));
  }
}

/** The legend under the ice line. */
export function iceLegend(rows) {
  const eq = rows.filter(isEq).length;
  return h('div', {class: 'ice-legend'},
    h('span', null, h('i', {class: 'sw-q'}), h('b', null, String(rows.length - eq)), ' small files or deletes'),
    h('span', null, h('i', {class: 'sw-eq'}), h('b', null, String(eq)), ' equality deletes, compacted first'));
}

/** The queue's table on Maintenance: #, table, why, score, small files, deletes / data, last compaction. */
export function queueTable(rows, {numbered = true, cls = 'queue stack-line', id} = {}) {
  const scale = scaleOf(rows);
  const body = h('tbody', null, rows.map((row, i) => {
    const a = row.assessment || {};
    const c = row['last-compaction'];
    return h('tr', {'data-table': keyOfRow(row), 'data-reasons': (a.reasons || []).join(' ')},
      numbered ? h('td', {class: 'num'}, String(i + 1)) : null,
      h('td', {class: 'name'}, h('a', {href: objectHref(targetOfRow(row), 'health')}, pathName(row.namespace, row.name))),
      h('td', {class: 'why'}, (a.reasons || []).flatMap((r, k) => k ? [' ', reasonPill(r)] : [reasonPill(r)])),
      h('td', {class: 'score'}, bar(a.score, scale, isEq(row)), ' ', h('span', {class: 'tnum'}, String(a.score ?? ''))),
      numbered ? h('td', {class: 'num'}, String(a['small-files'] ?? '')) : null,
      numbered ? h('td', {class: 'num'}, `${a['delete-files'] ?? 0} / ${a['data-files'] ?? 0}`) : null,
      numbered ? h('td', null, c ? compactionWords(c) : h('span', {class: 'muted'}, 'None')) : null);
  }));
  const heads = numbered
    ? [['#', 'num'], 'Table', 'Why', 'Score', ['Small files', 'num'], ['Deletes / data', 'num'], 'Last compaction']
    : ['Table', 'Why', 'Score'];
  return h('div', {class: 'wrap-tbl'}, h('table', {class: 'tbl compact ' + cls, id},
    h('thead', null, h('tr', null, heads.map(x => Array.isArray(x) ? h('th', {class: x[1]}, x[0]) : h('th', null, x)))), body));
}

export function compactionWords(c) {
  const cls = c.outcome === 'succeeded' ? 'ok' : c.outcome === 'running' ? 'accent' : 'warn';
  return [pill(cls, c.outcome), ' ', h('span', {class: 'muted tnum'}, shortWhen(c['ended-at'] || c['started-at']))];
}

function shortWhen(value) {
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return String(value || '');
  const p2 = n => String(n).padStart(2, '0');
  return `${d.getDate()} ${['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][d.getMonth()]} `
    + `${p2(d.getHours())}:${p2(d.getMinutes())}`;
}

/** The filter chips over the queue table: all, and each reason with its count. */
export function queueChips(rows, table) {
  const count = r => rows.filter(x => ((x.assessment || {}).reasons || []).includes(r)).length;
  const box = h('div', {class: 'chips', role: 'group', 'aria-label': 'Filter the queue'},
    h('button', {class: 'chip', type: 'button', 'data-f': 'all', 'aria-pressed': 'true'}, 'All ', h('span', {class: 'n'}, String(rows.length))),
    Object.keys(REASONS).filter(count).map(r => h('button', {class: 'chip', type: 'button', 'data-f': r, 'aria-pressed': 'false'},
      REASONS[r][1] + ' ', h('span', {class: 'n'}, String(count(r))))));
  for (const b of $$('.chip', box)) {
    b.addEventListener('click', () => {
      for (const o of $$('.chip', box)) o.setAttribute('aria-pressed', String(o === b));
      for (const tr of $$('tbody tr', table)) tr.hidden = !(b.dataset.f === 'all' || tr.dataset.reasons.split(' ').includes(b.dataset.f));
    });
  }
  return box;
}

/** The empty queue: nothing for the worker to do tonight, among the tables the caller may read. */
export function emptyQueue(catalog) {
  return h('div', {class: 'empty', id: 'neediest-none'},
    h('h3', null, 'Nothing queued for tonight'),
    h('p', null, 'No table you may read in ', h('code', null, catalog), ' has small files, deletes past its policy\'s '
      + 'share of its data files, or equality deletes, as the worker last assessed them. Tables you may not read are not counted here.'));
}

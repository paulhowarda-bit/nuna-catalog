// Tonight's queue: the tables the maintenance worker last assessed as needing compaction, neediest first, of
// those the caller may read (GET /management/v1/maintenance/tables). The ice line draws every table the caller may
// select, one row each (GET /management/v1/maintenance/estate, item 127): each table in shape a mark standing above
// the water, as tall as its data files on a log scale, and each queued one hanging below it, as deep as its score.

import {$$, bar, fmt, h, ordinal, pill, plural, s} from './dom.js';
import {objectHref} from './route.js';
import * as api from './api.js';

/** The job types a maintenance policy turns on and off. */
const JOBS = ['compaction.enabled', 'snapshot-expiry.enabled', 'orphan-removal.enabled', 'manifest-rewrite.enabled'];

/** Whether a resolved policy's job keys each come from the catalog or the built-in policy it keeps. */
const catalogWide = r => JOBS.every(k => ['built-in', 'default', undefined].includes(r[k].source)
  || String(r[k].source).startsWith('catalog'));

/**
 * When a resolved maintenance policy has every job type off (Atelier's review, F9; backlog 190), the words that say so,
 * whose policy it is linked to where it is shown: the catalog's when each key comes from it or the built-in policy it
 * keeps, else the table's own. Null when any job is on.
 */
export function maintenanceOff(catalog, p, href) {
  const r = (p && p.resolved) || {};
  if (!JOBS.every(k => r[k] && String(r[k].value) === 'false')) return null;
  const catalogs = catalogWide(r);
  return h('span', {'data-maintenance-off': ''}, 'Maintenance is off in ', h('a', {href, onclick: e => {
    const shown = document.querySelector('[data-policy]');
    if (shown) {
      e.preventDefault();
      shown.scrollIntoView({block: 'start'});
    }
  }}, catalogs ? `${catalog}'s policy` : 'this table\'s policy'), ': nothing will be compacted or expired.');
}

/**
 * Whether the catalog's policy has every job off, read through one of the caller's own tables there (`tables`, estate
 * rows), whose policy they may read, so asking writes no denial: when that table's job keys each come from the catalog
 * or the built-in policy, they are the catalog's. The words, or null when any job is on, a level nearer the table sets
 * one, or the policy cannot be read.
 */
export async function catalogOff(catalog, tables, href) {
  const t = tables[0];
  if (!t) return null;
  try {
    const p = await api.policy({catalog, kind: 'table', namespace: t.namespace, name: t.name});
    return catalogWide((p && p.resolved) || {}) ? maintenanceOff(catalog, p, href) : null;
  } catch {
    return null;
  }
}

export const REASONS = {
  'equality-deletes': ['bad', 'Equality deletes',
    'Equality deletes: every reader must join against them; such a table is compacted first.'],
  'small-files': ['warn', 'Small files',
    'Small files: a partition holds enough small data files to rewrite them into fewer, larger ones.'],
  'deletes': ['warn', 'Deletes',
    'Deletes: more delete files than its policy allows for its data files; every read has to apply them.'],
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

let estateKept = null;

/** Every table the caller may select, one row each, in every catalog, every page; asked once a minute at most. */
export async function estate(fresh) {
  if (!fresh && estateKept && Date.now() - estateKept.at < 60_000) return estateKept.rows;
  const rows = await api.estate();
  estateKept = {at: Date.now(), rows};
  return rows;
}

const needs = row => !!(row.assessment && row.assessment['needs-compaction']);

/**
 * The estate's counts: every table, those in shape (assessed, and needing no compaction), those queued, by why,
 * those not yet assessed, those whose compaction last night succeeded, and those held or frozen.
 */
export function estateCounts(rows) {
  const queued = rows.filter(needs);
  return {
    total: rows.length,
    inShape: rows.filter(r => r.assessment && !needs(r)).length,
    queued: queued.length,
    eq: queued.filter(isEq).length,
    unassessed: rows.filter(r => !r.assessment).length,
    fixed: rows.filter(r => r['fixed-last-night'] && !needs(r)).length,
    held: rows.filter(r => r.held).length,
    frozen: rows.filter(r => r.frozen).length,
  };
}

/**
 * The estate's shape in one sentence, from its counts (`estateCounts`), for at least one table: a table the worker
 * has not assessed yet is counted apart, never as in shape or out of it.
 */
export function shapeWords(c) {
  if (c.unassessed === c.total) return `${plural(c.total, 'table')} not assessed yet: the maintenance worker has not reported.`;
  if (!c.unassessed) return `${fmt(c.inShape)} of ${plural(c.total, 'table')} in shape.`;
  return `${fmt(c.inShape)} of ${plural(c.total - c.unassessed, 'assessed table')} in shape; ${fmt(c.unassessed)} not assessed yet.`;
}

/**
 * The ice line over the whole estate: one mark per table, grouped by top-level namespace and labelled under the
 * line. Above it, each table in shape, as tall as its data files on a log scale, with a dashed reference at 1,000
 * files; one not yet assessed is a stub. Below it, each queued table, as deep as its score, a link to its Maintenance tab.
 * A table whose compaction succeeded last night has a dashed outline where it was queued. Held and frozen tables have
 * their own colours.
 */
export function iceEstate(host, W, rows) {
  const groups = [];
  for (const row of [...rows].sort((a, b) => keyOfRow(a).localeCompare(keyOfRow(b)))) {
    const top = row.namespace[0];
    let g = groups.find(x => x.name === top);
    if (!g) groups.push(g = {name: top, rows: []});
    g.rows.push(row);
  }
  const MAXH = 90;
  const Y = MAXH + 22;
  const H = Y + 6 + 46 + 26;
  const GAP = 14;
  const L = 8;
  const R = 84;
  const logMax = Math.max(4, ...rows.map(r => Math.log10(1 + (Number((r.assessment || {})['data-files']) || 0))));
  const tall = files => 3 + (MAXH - 3) * Math.log10(1 + files) / logMax;
  const deepest = Math.max(10, ...rows.filter(needs).map(r => Number(r.assessment.score) || 0));
  const step = Math.min(28, (W - L - R - GAP * Math.max(0, groups.length - 1)) / Math.max(1, rows.length));
  const c = estateCounts(rows);
  const svg = s('svg', {viewBox: `0 0 ${W} ${H}`, role: 'group', 'aria-label': 'Every table you may select as one mark. '
    + `Above the line, ${plural(c.inShape, 'table')} in shape, each as tall as its data-file count on a log scale. `
    + `Below it, ${c.queued} queued for tonight, each as deep as its score.`});
  const ghosts = s('g', {class: 'ghosts'});
  const sky = s('g', {class: 'rise'});
  const sink = s('g', {class: 'sink'});
  const labels = s('g', {class: 'labs'});
  let x = L;
  groups.forEach((g, gi) => {
    const x0 = x;
    const group = s('a', {href: objectHref({catalog: g.rows[0].catalog, kind: 'namespace', namespace: [g.name]}),
      class: 'ga g' + (gi % 6), 'aria-label': `${g.name}, ${plural(g.rows.length, 'table')}, ${g.rows.filter(needs).length} queued`});
    group.append(s('rect', {class: 'hit', x: x0 - 2, y: Y - MAXH - 4, width: g.rows.length * step + 4, height: MAXH + 2}));
    for (const row of g.rows) {
      const a = row.assessment;
      const width = Math.max(1, step * 0.74);
      if (needs(row)) {
        const depth = 6 + 46 * (Number(a.score) || 0) / deepest;
        const mx = x + step / 2 - 2;
        const link = s('a', {href: objectHref(targetOfRow(row), 'maintenance'), class: 'qa g' + (gi % 6),
          'aria-label': `${row.namespace.join('.')}.${row.name}, score ${a.score}`});
        link.append(s('title', null, `${row.catalog}.${row.namespace.join('.')}.${row.name} · score ${a.score} · `
          + (a.reasons || []).map(r => (REASONS[r] || [null, r])[1].toLowerCase()).join(', ')));
        link.append(s('rect', {class: 'hit', x: mx - 6, y: Y - 5, width: 16, height: depth + 9}),
          s('rect', {class: 'q' + (isEq(row) ? ' eq' : ''), x: mx, y: Y + 1, width: 4, height: depth, rx: 1}));
        if (row.held) link.append(s('circle', {class: 'sky held', cx: mx + 2, cy: Y, r: 4}));
        sink.append(link);
      } else {
        const height = a ? tall(Number(a['data-files']) || 0) : 2;
        group.append(s('rect', {class: 'sky' + (row.frozen ? ' frozen' : row.held ? ' held' : ''), x, y: Y - height, width, height}));
        if (row['fixed-last-night']) ghosts.append(s('rect', {class: 'ghost', x, y: Y + 2, width, height: 12}));
      }
      x += step;
    }
    sky.append(group);
    labels.append(s('text', {class: 'lab-strong', x: x0, y: H - 6}, g.name),
      s('text', {class: 'lab', x: x0 + g.name.length * 7.9 + 8, y: H - 6}, g.rows.length.toLocaleString('en-GB')));
    if (gi < groups.length - 1) labels.append(s('line', {class: 'grp', x1: x + GAP / 2, y1: Y - 6, x2: x + GAP / 2, y2: Y + 6}));
    x += GAP;
  });
  if (logMax >= 3) {
    const ry = Y - tall(1000);
    ghosts.append(s('line', {class: 'ref', x1: L, y1: ry, x2: W - R + 12, y2: ry}));
    labels.append(s('text', {class: 'lab', x: W - 2, y: ry + 4, 'text-anchor': 'end'}, '1,000 files'));
  }
  svg.append(ghosts, sky, sink, s('line', {class: 'water', x1: 0, y1: Y, x2: W, y2: Y}), labels);
  host.append(svg);
  host.append(h('p', {class: 'ice-cap'}, 'Above the line: tables in shape. Below: tables queued for tonight. Click a '
    + 'namespace or a queued table to open it.'));
}

/** The legend under the estate's ice line, with each count. */
export function estateLegend(rows) {
  const c = estateCounts(rows);
  const item = (sw, n, words) => h('span', null, h('i', {class: sw}), h('b', null, n.toLocaleString('en-GB')), ' ', words);
  return h('div', {class: 'ice-legend', id: 'estate-legend'},
    item('sw-sky', c.inShape, 'in shape'),
    item('sw-q', c.queued - c.eq, 'small files or deletes'),
    item('sw-eq', c.eq, 'equality deletes, compacted first'),
    c.fixed ? item('sw-ghost', c.fixed, 'fixed last night') : null,
    c.held ? item('sw-held', c.held, 'held') : null,
    c.frozen ? item('sw-frozen', c.frozen, 'frozen') : null,
    c.unassessed ? h('span', null, h('b', null, c.unassessed.toLocaleString('en-GB')), ' not assessed yet') : null);
}

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
      const link = s('a', {href: objectHref(targetOfRow(row), 'maintenance'), class: 'qa g' + (gi % 6) + (mine ? ' is-me' : ''),
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
    host.append(h('p', {class: 'ice-cap'}, 'Below the line: queued, depth is score.'));
  }
}

/** The queue's table on Maintenance: #, table, why, score, small files, deletes / data, last compaction. */
export function queueTable(rows, {numbered = true, cls = 'queue stack-line', id} = {}) {
  const scale = scaleOf(rows);
  const body = h('tbody', null, rows.map((row, i) => {
    const a = row.assessment || {};
    const c = row['last-compaction'];
    return h('tr', {'data-table': keyOfRow(row), 'data-reasons': (a.reasons || []).join(' ')},
      numbered ? h('td', {class: 'num'}, String(i + 1)) : null,
      h('td', {class: 'name'}, h('a', {href: objectHref(targetOfRow(row), 'maintenance')}, pathName(row.namespace, row.name))),
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

/**
 * The empty queue: nothing for the worker to do tonight, among the tables the caller may read. `c` is the catalog's
 * estate counts (`estateCounts`): a table not assessed yet is named as such, not counted as needing nothing.
 */
export function emptyQueue(catalog, c, off = false, edit = null) {
  // With maintenance off in the policy, that is why nothing is assessed; the worker is not to blame (Atelier's re-walk).
  const why = off ? ['Maintenance is off in ', h('code', null, catalog), '\'s policy, so the worker assesses no table and queues '
    + 'none. ']
    : c.total && c.unassessed === c.total
    ? ['The maintenance worker has not assessed any table you may read in ', h('code', null, catalog), ' yet, so it has '
      + 'queued none. ']
    : ['As the worker last assessed them, no table you may read in ', h('code', null, catalog), ' has small files, too '
      + 'many deletes or equality deletes. ',
    c.unassessed ? `${plural(c.unassessed, 'table')} ${c.unassessed === 1 ? 'is' : 'are'} not assessed yet. ` : ''];
  return h('div', {class: 'empty', id: 'neediest-none'},
    h('h3', null, 'Nothing queued for tonight'),
    h('p', null, ...why, 'Tables you may not read are not counted here.', off ? edit : null));
}

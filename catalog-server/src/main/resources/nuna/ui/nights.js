// The maintenance nights (item 126): the newest night's totals and its compaction Jobs and orphan scans on their
// slots, and 30 nights of figures as sparklines. A night is one opening of the cleanup window and the day after it,
// named by the date it opened. The totals are the whole deployment's, counts of the worker's own work, and anyone
// signed in reads them; a job names its table only to a caller who may select it.

import * as api from './api.js';
import {fmt, h, icon, plural, s, sized, sizeWords} from './dom.js';
import {objectHref, pageHref} from './route.js';

/** The last 30 nights, the newest with its jobs; one read shared by the overview's parts. */
export const thirtyNights = () => api.nights(30, 1).then(answer => answer.nights || []);

const hm = iso => new Date(iso).toLocaleTimeString('en-GB', {hour: '2-digit', minute: '2-digit'});
const dayWords = night => new Date(night + 'T12:00:00Z').toLocaleDateString('en-GB', {weekday: 'short', day: 'numeric', month: 'short'});
const n = x => Number(x || 0);

/** The overview's "Maintenance, last night": the newest night's totals. */
export function lastNightKpi(catalog, nights) {
  const t = nights[0];
  return h('div', {class: 'kpi', 'data-kpi': 'maintenance'},
    h('p', {class: 'kpi-l'}, h('a', {href: pageHref('maintenance', catalog)}, icon('maint'), 'Maintenance, last night')),
    t ? [h('p', {class: 'kpi-v'}, fmt(n(t['tables-compacted'])), h('small', null, 'tables compacted')),
      h('div', {class: 'kpi-list'},
        h('div', null, h('span', null, 'Small files merged'), h('b', null, `${fmt(n(t['files-compacted']))} → ${fmt(n(t['files-written']))}`)),
        h('div', null, h('span', null, 'Snapshots expired'), h('b', null, fmt(n(t['snapshots-expired'])))),
        h('div', null, h('span', null, 'Unreferenced files deleted'), h('b', null, sizeWords(n(t['bytes-deleted'])))),
        h('div', null, h('span', null, `Cut off at ${hm(t.closes)}, progress kept`), h('b', null, fmt(n(t['jobs-cut-off']))))),
      h('p', {class: 'kpi-d'}, `Night of ${dayWords(t.night)}, every catalog`)]
      : [h('p', {class: 'kpi-v'}, '–', h('small', null, 'no night yet')),
        h('p', {class: 'kpi-d'}, 'The worker has reported no job in the last 30 nights.')]);
}

/** The newest night's jobs on their slots, with its window's end, and the night's job counts. */
export function lastNightPanel(nights) {
  const t = nights[0];
  if (!t) {
    return h('div', {class: 'panel', id: 'last-night'}, h('h2', null, 'Last night'),
      h('p', {class: 'muted'}, 'The worker has reported no job in the last 30 nights.'));
  }
  const jobs = t.jobs || [];
  const slots = Math.max(0, ...jobs.filter(j => j.kind === 'compaction').map(j => n(j.slot)));
  const chart = h('div', {class: 'gantt', 'data-gantt': ''});
  if (jobs.length) sized(chart, (host, w) => gantt(host, w, t, jobs, slots));
  return h('div', {class: 'panel', id: 'last-night', 'data-night': t.night},
    h('h2', null, 'Last night ', h('span', {class: 'meta'}, `${dayWords(t.night)} · ${hm(t.opens)} to ${hm(t.closes)}`
      + (slots ? ` · ${plural(slots, 'slot')}` : ''))),
    jobs.length ? [chart, h('p', {class: 'fine'}, 'Dashed line: the end of the cleanup window. A job cut off there keeps the work it '
      + 'finished. Jobs on tables you may not read are drawn without a name.')]
      : h('p', {class: 'muted'}, 'No compaction or orphan scan ran that night. Work done at other hours is counted below.'),
    h('div', {class: 'kpi-list gantt-sum'},
      h('div', null, h('span', null, 'Compaction jobs finished'), h('b', null, fmt(n(t['tables-compacted'])))),
      h('div', null, h('span', null, `Cut off at ${hm(t.closes)}`), h('b', {class: n(t['jobs-cut-off']) ? 'warn-n' : null}, fmt(n(t['jobs-cut-off'])))),
      h('div', null, h('span', null, 'Failed'), h('b', null, fmt(n(t['jobs-failed'])))),
      h('div', null, h('span', null, 'Still running'), h('b', null, fmt(n(t['jobs-running']))))));
}

/** The jobs drawn on their lanes: a lane per slot, then the orphan scans; the window's end dashed. */
function gantt(host, W, t, jobs, slots) {
  const X0 = 64;
  const X1 = W - 4;
  const LH = 22;
  const LG = 7;
  const TOP = 8;
  const now = Date.now();
  const scans = jobs.filter(j => j.kind === 'orphan-scan');
  const lanes = slots + (scans.length ? 1 : 0);
  const ends = jobs.map(j => j['ended-at'] ? Date.parse(j['ended-at']) : now);
  const t0 = Math.min(Date.parse(t.opens), ...jobs.map(j => Date.parse(j['started-at'])));
  const t1 = Math.max(Date.parse(t.closes), ...ends.map(e => Math.min(e, Date.parse(t.closes) + 6 * 3600_000)));
  const px = ms => X0 + (Math.min(Math.max(ms, t0), t1) - t0) / Math.max(1, t1 - t0) * (X1 - X0);
  const bottom = TOP + lanes * (LH + LG);
  const svg = s('svg', {viewBox: `0 0 ${W} ${bottom + 22}`, role: 'group',
    'aria-label': `Jobs of the night of ${t.night}, from ${hm(t.opens)} to ${hm(t.closes)}: ${plural(jobs.length - scans.length, 'compaction job')} `
      + `on ${plural(slots, 'slot')} and ${plural(scans.length, 'orphan scan')}.`});
  for (let i = 0; i < lanes; i++) {
    const y = TOP + i * (LH + LG);
    svg.append(s('rect', {class: 'lane', x: X0, y, width: X1 - X0, height: LH, rx: 4}),
      s('text', {class: 'axis', x: 0, y: y + 16}, i < slots ? `Slot ${i + 1}` : 'Orphans'));
  }
  jobs.forEach((j, i) => {
    const lane = j.kind === 'compaction' ? n(j.slot) - 1 : slots;
    const y = TOP + lane * (LH + LG);
    const a = px(Date.parse(j['started-at']));
    const b = px(ends[i]);
    const cls = j.kind === 'orphan-scan' ? 'scan' : j.outcome === 'cut-off' ? 'job cut' : j.outcome === 'failed' ? 'job failed' : 'job';
    const bar = s('rect', {class: cls, x: a + 0.8, y: y + 3, width: Math.max(1.5, b - a - 1.6), height: LH - 6, rx: 3});
    const table = j.table;
    const name = table ? [...(table.namespace || []), table.name].join('.') : 'a table you may not read';
    const words = `${j.kind === 'orphan-scan' ? 'Orphan scan' : 'Compaction'} of ${name}: ${hm(j['started-at'])} to `
      + `${j['ended-at'] ? hm(j['ended-at']) : 'now'}, ${j.outcome}${j.kind === 'compaction' && j['ended-at']
        ? `, ${fmt(n(j['files-in']))} files into ${fmt(n(j['files-out']))}` : ''}`;
    const g = table
      ? s('a', {href: objectHref({catalog: table.catalog, kind: 'table', namespace: table.namespace || [], name: table.name}, 'maintenance'),
        class: 'gj', 'aria-label': words, 'data-job': j.id})
      : s('g', {class: 'gj-anon', role: 'img', 'aria-label': words, 'data-job': j.id});
    g.append(s('title', null, words), bar);
    svg.append(g);
  });
  const hour = 3600_000;
  const step = (t1 - t0) / hour > 8 ? 2 : 1;
  for (let x = Math.ceil(t0 / hour) * hour; x <= t1; x += hour) {
    svg.append(s('line', {class: 'tick', x1: px(x), y1: TOP - 4, x2: px(x), y2: bottom - LG + 4}));
    if (Math.round((x - Math.ceil(t0 / hour) * hour) / hour) % step === 0) {
      const anchor = px(x) > X1 - 24 ? 'end' : px(x) < X0 + 24 ? 'start' : 'middle';
      svg.append(s('text', {class: 'axis', x: px(x), y: bottom + 14, 'text-anchor': anchor}, hm(new Date(x).toISOString())));
    }
  }
  const end = px(Date.parse(t.closes));
  svg.append(s('line', {class: 'end', x1: end, y1: TOP - 6, x2: end, y2: bottom - LG + 6}));
  host.append(svg);
}

/** Thirty nights of a figure, oldest to newest, as a line ending in a dot. */
function spark(values, key) {
  const svg = s('svg', {class: 'spark', viewBox: '0 0 240 36', preserveAspectRatio: 'none', 'aria-hidden': 'true', 'data-spark': key});
  if (values.length < 2) return svg;
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const pt = (v, i) => [i / (values.length - 1) * 236 + 2, 32 - (hi === lo ? 0.5 : (v - lo) / (hi - lo)) * 27];
  const line = values.map((v, i) => {
    const [x, y] = pt(v, i);
    return `${i ? ' L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`;
  }).join('');
  const [ex, ey] = pt(values[values.length - 1], values.length - 1);
  svg.append(s('path', {class: 'ar', d: `${line} L238 36 L2 36 Z`}), s('path', {class: 'ln', d: line}),
    s('circle', {class: 'dot', cx: ex, cy: ey, r: 2.6}));
  return svg;
}

const median = values => {
  const sorted = values.slice().sort((a, b) => a - b);
  const m = sorted.length >> 1;
  return sorted.length % 2 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2;
};

/** The maintenance page's figures: the newest night's, each over the nights the worker reported in. */
export function nightKpis(nights) {
  if (!nights.length) return null;
  const old = nights.slice().reverse();
  const series = key => old.map(x => n(x[key]));
  const t = nights[0];
  const kpi = (key, label, value, more) => h('div', {class: 'kpi', 'data-kpi': key},
    h('p', {class: 'kpi-l'}, label), h('p', {class: 'kpi-v'}, value), spark(series(key), key), h('p', {class: 'kpi-d'}, more));
  const days = plural(nights.length, 'night');
  return h('div', {class: 'kpis', id: 'night-kpis'},
    kpi('tables-compacted', 'Tables compacted, last night', fmt(n(t['tables-compacted'])),
      [`${days} median `, h('b', null, fmt(Math.round(median(series('tables-compacted')))))]),
    kpi('files-compacted', 'Small files merged', fmt(n(t['files-compacted'])), ['into ', h('b', null, fmt(n(t['files-written']))), ' files']),
    kpi('snapshots-expired', 'Snapshots expired', fmt(n(t['snapshots-expired'])),
      [h('b', null, fmt(series('snapshots-expired').reduce((a, b) => a + b, 0))), ` in ${days}`]),
    kpi('bytes-deleted', 'Unreferenced files deleted', sizeWords(n(t['bytes-deleted'])),
      [h('b', null, sizeWords(series('bytes-deleted').reduce((a, b) => a + b, 0))), ` in ${days}`]));
}

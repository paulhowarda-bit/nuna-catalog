// The overview: one catalog at a glance. Every table the caller may select on the ice line, in shape above it and
// queued below it, with the counts (item 127); tonight's queue; last night's maintenance with its jobs on their slots
// (item 126); for platform operators, the latency objectives; for audit readers, the last day's audit counts (item
// 125); and the governance figures for those who may describe the catalog (item 128).

import * as api from './api.js';
import {auditOpen, operates} from './access.js';
import {at, failure, fmt, h, icon, load, muted, pageHead, panel, pill, plural, sized} from './dom.js';
import {lastNightKpi, lastNightPanel, thirtyNights} from './nights.js';
import {objectHref, pageHref} from './route.js';
import {catalogOff, estate, estateCounts, estateLegend, iceEstate, keyOfRow, lastAssessed, queue, queueOf, queueTable, shapeWords,
  targetOfRow} from './queue.js';
import {sloFigures} from './health.js';
import {governanceFigures} from './governance.js';
import {editPolicyLink} from './maintenance.js';
import {readinessOf, stepsWords} from './readiness.js';

export function overviewPage(catalog) {
  const main = h('div', {class: 'nc-main', id: 'overview'});
  load(main, async () => {
    const [rows, everywhere] = await Promise.all([queue(true).then(all => queueOf(all, catalog)), estate(true)]);
    const tables = everywhere.filter(t => t.catalog === catalog);
    const assessed = lastAssessed(rows);
    const c = estateCounts(tables);
    // Before the worker reports, no table is assessed: none is in shape or queued, and the page says so.
    const none = c.total > 0 && c.unassessed === c.total;
    // The worker assesses only where compaction is on, so the catalog's policy is said to have it off only while none
    // is assessed (backlog 190).
    // While none is assessed, item 251's readiness says why, as on Maintenance (backlog 266): the steps left, or once all
    // four are done, why nothing is assessed. For a caller it would refuse, or a catalog no worker has reported on, the
    // page keeps the words that wait for the worker.
    const [off, readiness] = none ? await Promise.all([catalogOff(catalog, tables, pageHref('maintenance', catalog)),
      readinessOf(catalog)]) : [null, null];
    const ready = readiness && readiness.ready;
    const reported = !!ready && !!(ready['reported-at'] || ready['scanned-at']);
    const left = stepsWords(catalog, ready);
    const now = new Date();
    const out = [pageHead({
      hero: true,
      eyebrow: `${catalog} · ${now.toLocaleDateString('en-GB', {weekday: 'long', day: 'numeric', month: 'long', year: 'numeric'})}, `
        + now.toLocaleTimeString('en-GB', {hour: '2-digit', minute: '2-digit'}),
      title: !c.total ? nothingToRead(catalog, everywhere, 'overview')
        : left ? left
        : off ? off
        : none ? shapeWords(c, ready)
          : [shapeWords(c) + ' ', c.queued
            ? h('span', {class: 'q'}, `${fmt(c.queued)} queued for tonight.`) : 'Nothing queued for tonight.'],
      sub: off && !left ? `${plural(c.total, 'table')} you may read, none assessed: the worker assesses a table only where its policy turns `
        + 'maintenance on.'
        : [left ? null : unassessedNames(tables),
          'Of the tables you may read, as the maintenance worker last assessed them. Each table\'s Maintenance tab has the detail.'],
      side: assessed ? pill('ok', icon('check'), 'Last assessed ' + new Date(assessed).toLocaleTimeString('en-GB', {hour: '2-digit', minute: '2-digit'})) : null,
    })];
    if (c.total) {
      const ice = h('div', {class: 'iceline wide', 'data-iceline': 'estate'});
      sized(ice, (host, w) => iceEstate(host, w, tables));
      out.push(panel('Every table, one mark', 'above the line: in shape, taller for more data files (log scale) · below: queued, deeper for a higher score',
        ice, estateLegend(tables)));
    }
    const kpis = h('div', {class: 'kpis', id: 'overview-kpis'});
    kpis.append(h('div', {class: 'kpi', 'data-kpi': 'shape'},
      h('p', {class: 'kpi-l'}, icon('check'), 'In shape'),
      none ? h('p', {class: 'kpi-v'}, '–', h('small', null, 'none assessed yet'))
        : h('p', {class: 'kpi-v'}, `${fmt(c.inShape)} of ${fmt(c.total - c.unassessed)}`, c.unassessed ? h('small', null, 'assessed') : null),
      h('p', {class: 'kpi-d'}, left ? left
        : none && !off && reported ? shapeWords(c, ready)
        : none ? `${fmt(c.unassessed)} not assessed yet: ` + (off ? 'maintenance is off in the catalog\'s policy'
          : 'the maintenance worker has not reported')
        : [c.queued ? `${fmt(c.queued)} queued for tonight` : 'none queued for tonight',
          c.unassessed ? `, ${fmt(c.unassessed)} not assessed yet` : ''])));
    out.push(kpis);
    const nights = thirtyNights();
    Promise.all([nights.then(list => lastNightKpi(catalog, list), error => h('div', {class: 'kpi'}, failure(error, {compact: true}))),
      operates() ? latencyKpi() : null, auditOpen(catalog) ? auditKpi(catalog) : null, governanceKpi(catalog)]).then(list => {
      kpis.append(...list.flat().filter(Boolean));
    });
    const lastNight = h('div');
    load(lastNight, async () => lastNightPanel(await nights));
    out.push(h('div', {class: 'grid-21'}, panel('Queued for tonight', 'neediest first, as the worker will start them',
      rows.length ? [queueTable(rows.slice(0, 8), {numbered: false, cls: 'stack-sm'}),
        h('p', {class: 'fine'}, rows.length > 8 ? `${rows.length - 8} more in the queue. ` : '',
          h('a', {href: pageHref('maintenance', catalog)}, 'Open the maintenance queue'))]
        // While a step is left, a link to Maintenance's "What the worker needs to maintain <catalog>" in place of the panel.
        : h('p', {class: 'muted'}, left ? [left + ' ', h('a', {href: pageHref('maintenance', catalog), 'data-readiness': ''},
          `What the worker needs to maintain ${catalog}`, ' →')]
          : off ? [`Nothing queued: maintenance is off in ${catalog}'s policy.`,
          editPolicyLink(catalog, () => main.replaceWith(overviewPage(catalog)))]
          : none && reported ? shapeWords(c, ready)
          : none ? 'Nothing queued: the maintenance worker has not assessed any table you may read yet.'
          : c.unassessed ? `No assessed table you may read needs compaction; ${fmt(c.unassessed)} not assessed yet.`
            : 'No table you may read needs compaction.')), lastNight));
    return out;
  });
  return main;
}

/** The most not-assessed tables the headline names; it counts the rest. */
const UNASSESSED_NAMED = 5;

/**
 * The tables the worker has not assessed yet, named under the headline, each linked to its Maintenance tab, one
 * closed until classified said so, since the worker cannot load it (backlog 327); none while every table is
 * unassessed, which the headline explains itself.
 */
export function unassessedNames(tables) {
  const rows = tables.filter(t => !t.assessment).sort((a, b) => keyOfRow(a).localeCompare(keyOfRow(b)));
  if (!rows.length || rows.length === tables.length) return null;
  return h('span', {'data-unassessed': ''}, 'Not assessed yet: ', rows.slice(0, UNASSESSED_NAMED).map((row, i) =>
    [i ? ', ' : '', h('a', {href: objectHref(targetOfRow(row), 'maintenance')}, keyOfRow(row)),
      row.closed ? ' (closed until classified)' : '']),
  rows.length > UNASSESSED_NAMED ? `, and ${fmt(rows.length - UNASSESSED_NAMED)} more` : '', '. ');
}

/**
 * The headline of a catalog with no table the caller may read: it says so, and names the catalog with the most
 * tables the caller may read, linked to the same page there (Atelier's review, F2).
 */
export function nothingToRead(catalog, everywhere, page) {
  const counts = new Map();
  for (const t of everywhere) if (t.catalog !== catalog) counts.set(t.catalog, (counts.get(t.catalog) || 0) + 1);
  const [other, n] = [...counts].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))[0] || [];
  return [`Nothing in ${catalog} you may read.`, other ? [' ', h('a', {href: pageHref(page, other), 'data-other-catalog': other},
    `${other} has ${plural(n, 'table')}`, ' →')] : null];
}

/** The catalog's governance in figures: for those who may describe the catalog; anyone else sees nothing here. */
async function governanceKpi(catalog) {
  let f;
  try {
    f = governanceFigures(await api.governanceSummary(catalog));
  } catch (error) {
    if (error.status === 403 || error.status === 404) return null;
    return h('div', {class: 'kpi'}, failure(error, {compact: true}));
  }
  return h('div', {class: 'kpi', 'data-kpi': 'governance'},
    h('p', {class: 'kpi-l'}, h('a', {href: pageHref('governance', catalog)}, icon('gov'), 'Governance')),
    h('p', {class: 'kpi-v'}, f.total ? Math.floor(100 * f.classified / f.total) + '%' : '–', h('small', null, 'classified')),
    h('div', {class: 'kpi-list'},
      h('div', null, h('span', null, 'Tables classified'), h('b', null, `${fmt(f.classified)} of ${fmt(f.total)}`)),
      h('div', null, h('span', null, 'Legal holds'), h('b', null, fmt(f.holds))),
      h('div', null, h('span', null, 'DR freezes'), h('b', null, fmt(f.freezes))),
      h('div', null, h('span', null, 'Closed until classified'), h('b', null, fmt(f.closed)))));
}

/** The catalog's audit, last day: for audit readers; anyone else, or a server without the audit tables, shows nothing. */
async function auditKpi(catalog) {
  let sum;
  try {
    sum = await api.auditSummary(catalog);
  } catch (error) {
    if (error.status === 403 || error.status === 404 || error.status === 503) return null;
    return h('div', {class: 'kpi'}, failure(error, {compact: true}));
  }
  const check = await api.auditArchive().then(a => a.configured ? a.check : undefined, () => undefined);
  const byType = sum['by-type'] || {};
  const count = types => Object.entries(byType).filter(([t]) => !types || types.includes(t)).reduce((a, [, x]) => a + Number(x || 0), 0);
  return h('div', {class: 'kpi', 'data-kpi': 'audit'},
    h('p', {class: 'kpi-l'}, h('a', {href: pageHref('audit', catalog)}, icon('audit'), 'Audit, last day')),
    h('p', {class: 'kpi-v'}, fmt(count(null)), h('small', null, 'records')),
    h('div', {class: 'kpi-list'},
      h('div', null, h('span', null, 'Refusals'), h('b', null, fmt(count(['nuna.catalog.denial', 'nuna.catalog.auth-failure'])))),
      h('div', null, h('span', null, 'Across all catalogs'), h('b', null, fmt(Object.values(sum['deployment-by-type'] || {})
        .reduce((a, x) => a + Number(x || 0), 0)))),
      h('div', null, h('span', null, 'Archive chain'), h('b', null, check === undefined ? 'no archive'
        : check ? (check.intact ? 'intact at ' + at(check['ended-at']) : 'broken') : 'not checked yet'))));
}

/** Latency objectives, last day: platform operators only; anyone else sees nothing here, not a refusal. */
async function latencyKpi() {
  let health;
  try {
    health = await api.catalogHealth();
  } catch (error) {
    if (error.status === 403) return null;
    return h('div', {class: 'kpi'}, failure(error, {compact: true}));
  }
  const slos = sloFigures(health.slos || {});
  const met = slos.filter(x => x.met === true).length;
  const judged = slos.filter(x => x.met !== undefined).length;
  return [h('div', {class: 'kpi'},
    h('p', {class: 'kpi-l'}, h('a', {href: pageHref('service')}, icon('health'), 'Latency objectives, last day')),
    h('p', {class: 'kpi-v'}, judged ? `${met} of ${judged}` : '–', h('small', null, judged ? 'met' : 'no requests in the last day')),
    h('div', {class: 'kpi-list'}, slos.map(x => h('div', null, h('span', null, x.short), h('b', null, x.share || muted('–')))))),
  h('div', {class: 'kpi'},
    h('p', {class: 'kpi-l'}, h('a', {href: pageHref('service')}, icon('server'), 'Servers')),
    h('p', {class: 'kpi-v'}, `${health.answered}`, h('small', null, 'answered')),
    h('div', {class: 'kpi-list'}, h('div', null, h('span', null, 'Did not answer'), h('b', null, String(health.silent || 0)))))];
}

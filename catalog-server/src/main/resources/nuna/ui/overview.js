// The overview: one catalog at a glance. Today's routes give tonight's queue (drawn below the ice line), the
// governance figures for those who may describe the catalog (item 128) and, for platform operators, the latency
// objectives. Waiting on other items, and left out until they land: the tables in shape above the line (127), last
// night's maintenance and its gantt (126), and the last day's audit counts (125).

import * as api from './api.js';
import {failure, fmt, h, icon, load, muted, pageHead, panel, pill, plural, sized} from './dom.js';
import {pageHref} from './route.js';
import {iceLegend, iceQueue, lastAssessed, queue, queueOf, queueTable} from './queue.js';
import {sloFigures} from './health.js';
import {governanceFigures} from './governance.js';

export function overviewPage(catalog) {
  const main = h('div', {class: 'nc-main', id: 'overview'});
  load(main, async () => {
    const rows = queueOf(await queue(true), catalog);
    const assessed = lastAssessed(rows);
    const now = new Date();
    const out = [pageHead({
      hero: true,
      eyebrow: `${catalog} · ${now.toLocaleDateString('en-GB', {weekday: 'long', day: 'numeric', month: 'long', year: 'numeric'})}, `
        + now.toLocaleTimeString('en-GB', {hour: '2-digit', minute: '2-digit'}),
      title: rows.length ? h('span', {class: 'q'}, `${plural(rows.length, 'table')} queued for tonight.`)
        : `Nothing in ${catalog} is queued for tonight.`,
      sub: 'Of the tables you may read, as the maintenance worker last assessed them. Each table\'s health is on its own page.',
      side: assessed ? pill('ok', icon('check'), 'Last assessed ' + new Date(assessed).toLocaleTimeString('en-GB', {hour: '2-digit', minute: '2-digit'})) : null,
    })];
    if (rows.length) {
      const ice = h('div', {class: 'iceline wide', 'data-iceline': 'queue'});
      sized(ice, (host, w) => iceQueue(host, w, rows));
      out.push(panel('Tonight\'s queue, below the line', 'depth is score · neediest first', ice, iceLegend(rows)));
    }
    const kpis = h('div', {class: 'kpis', id: 'overview-kpis'});
    out.push(kpis);
    Promise.all([governanceKpi(catalog), latencyKpi()]).then(([gov, latency]) => {
      kpis.append(...[gov, latency].flat().filter(Boolean));
      if (!kpis.children.length) kpis.remove();
    });
    out.push(h('div', {class: 'grid-21'}, panel('Queued for tonight', 'neediest first, as the worker will start them',
      rows.length ? [queueTable(rows.slice(0, 8), {numbered: false, cls: 'stack-sm'}),
        h('p', {class: 'fine'}, rows.length > 8 ? `${rows.length - 8} more in the queue. ` : '',
          h('a', {href: pageHref('maintenance', catalog)}, 'Open the maintenance queue'))]
        : h('p', {class: 'muted'}, 'No table you may read needs compaction.'))));
    return out;
  });
  return main;
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
    h('p', {class: 'kpi-l'}, h('a', {href: pageHref('health')}, icon('health'), 'Latency objectives, last day')),
    h('p', {class: 'kpi-v'}, judged ? `${met} of ${judged}` : '–', h('small', null, judged ? 'met' : 'none in the window')),
    h('div', {class: 'kpi-list'}, slos.map(x => h('div', null, h('span', null, x.short), h('b', null, x.share || muted('–')))))),
  h('div', {class: 'kpi'},
    h('p', {class: 'kpi-l'}, h('a', {href: pageHref('health')}, icon('server'), 'Servers')),
    h('p', {class: 'kpi-v'}, `${health.answered}`, h('small', null, 'answered')),
    h('div', {class: 'kpi-list'}, h('div', null, h('span', null, 'Did not answer'), h('b', null, String(health.silent || 0)))))];
}

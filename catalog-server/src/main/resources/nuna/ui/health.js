// The health page (D38): catalog health for the whole deployment, for platform operators, and the neediest tables
// the viewer may read. The server decides who sees what: a refusal is shown as it answered, never guessed at here.

import * as api from './api.js';
import {badge, facts, fill, failure, h, load, mono, table, when} from './dom.js';
import {go} from './app.js';

const DAY_MS = 24 * 60 * 60 * 1000;

const ALERTS = [
  ['metadata-integrity', 'Metadata integrity', 'A metadata file no longer hashes to what its pointer recorded. Pages.'],
  ['regulated-schema-change', 'Regulated schema change', 'A commit changed a regulated table\'s schema or partition spec.'],
  ['large-metadata', 'Large metadata', 'A loaded table\'s metadata is over the size or snapshot limit.'],
];

const SLOS = [
  ['load', 'Table loads (200)'],
  ['not-modified', 'Loads answered 304'],
  ['commit', 'Commits (200)'],
];

export const REASONS = {
  'small-files': 'Small files: a partition holds enough small data files to rewrite them into fewer, larger ones.',
  'deletes': 'Deletes: more delete files than the policy\'s share of its data files, which every read applies.',
  'equality-deletes': 'Equality deletes: every reader must join against them; such a table is compacted first.',
};

export function healthPage() {
  const catalog = h('section', {class: 'block', id: 'catalog-health'});
  const tables = h('section', {class: 'block', id: 'neediest-tables'});
  load(catalog, catalogHealth);
  load(tables, neediestTables);
  return h('div', {class: 'health'},
    h('div', {class: 'pane-head'}, h('h2', null, 'Health'),
      h('button', {type: 'button', class: 'ghost small', onclick: () => {
        load(catalog, catalogHealth);
        load(tables, neediestTables);
      }}, 'Refresh')),
    catalog, tables);
}

// ---- catalog health: platform operators only ----

async function catalogHealth() {
  let health;
  let scans;
  try {
    [health, scans] = await Promise.all([api.catalogHealth(), api.scans()]);
  } catch (error) {
    if (error.status !== 403) throw error;
    return h('div', null, h('h3', null, 'Catalog health'),
      h('p', {class: 'muted health-refused'}, 'Catalog health is shown to platform operators only. '
        + 'The catalog answered:'), failure(error));
  }
  return h('div', {class: 'health-figures'},
    h('h3', null, 'Catalog health'),
    h('p', {class: 'muted'}, 'The whole deployment over the last day, from every server that answered; a server '
      + 'younger than a day counts from its start.'),
    servers(health),
    h('div', {class: 'health-grid'},
      h('div', {class: 'sub'}, h('h4', null, 'Alerts fired'), alerts(health.alerts)),
      h('div', {class: 'sub'}, h('h4', null, 'Audit: Kafka acknowledgement'), audit(health.audit)),
      h('div', {class: 'sub'}, h('h4', null, 'Credential vending'), vends(health['credential-vends']))),
    h('div', {class: 'sub'}, h('h4', null, 'Latency against the SLOs'), slos(health.slos)),
    h('div', {class: 'sub', id: 'health-worker'}, h('h4', null, 'Maintenance worker'), worker(scans)));
}

function servers(health) {
  const list = health.servers || [];
  const silent = health.silent || 0;
  return h('div', {class: 'sub'},
    h('p', {class: silent ? 'notice' : 'muted', id: 'health-gathered'},
      `Gathered from ${health.answered} server${health.answered === 1 ? '' : 's'}`
      + (silent ? `; ${silent} did not answer, so their figures are missing.` : '.')),
    table(['Server', 'Address', 'Answered', 'Counting since'], list.map(s => h('tr', {class: s.answered ? null : 'denied-row'},
      h('td', null, s.name ? mono(s.name) : h('span', {class: 'muted'}, '–'), s['this-server'] ? badge('this server', 'current') : null),
      h('td', null, s.address ? mono(s.address) : h('span', {class: 'muted'}, '–')),
      h('td', null, s.answered ? badge('answered', 'ok') : h('span', null, badge('no answer', 'refused'), ' ', s.why)),
      h('td', {class: 'nowrap'}, s.from ? when(s.from) : ''))), {class: 'grid', id: 'health-servers'}));
}

function alerts(counts) {
  return table(['Alert', 'Fired', ''], ALERTS.map(([kind, label, meaning]) => h('tr', {'data-alert': kind},
    h('td', null, label), h('td', {class: 'num'}, String(counts[kind])),
    h('td', null, counts[kind] ? badge('fired', 'refused') : badge('none', 'ok'), ' ',
      h('span', {class: 'muted'}, meaning)))), {class: 'grid', id: 'health-alerts'});
}

function p99(value) {
  if (!value) return null;
  return value['at-most-ms'] !== undefined ? '≤ ' + value['at-most-ms'] + ' ms' : '> ' + value['over-ms'] + ' ms';
}

function audit(a) {
  return facts([
    ['Records acknowledged', String(a.acknowledged)],
    ['Mean', a['mean-ms'] !== undefined ? a['mean-ms'] + ' ms' : null],
    ['p99', p99(a.p99)],
    ['Publishes failed', a.failed ? h('span', null, badge(String(a.failed), 'refused'),
      h('span', {class: 'muted'}, ' each refused the request it audited (D23)')) : '0'],
  ]);
}

function vends(v) {
  const failures = Object.entries(v.failures || {}).sort(([a], [b]) => a.localeCompare(b));
  return h('div', null, facts([
    ['Issued', String(v.issued)],
    ['From the cache', String(v.cached)],
    ['Declined by the caller', String(v.none)],
    ['Failed', v.failed ? badge(String(v.failed), 'refused') : '0'],
  ]), failures.length ? table(['Failure', 'Vends'], failures.map(([type, n]) => h('tr', null,
    h('td', null, mono(type)), h('td', {class: 'num'}, String(n)))), {class: 'grid', id: 'health-vend-failures'}) : null);
}

function slos(s) {
  return table(['Requests', 'Objective', 'Count', 'Within it', 'Share', 'p99', ''], SLOS.map(([family, label]) => {
    const slo = s[family];
    const share = slo.count ? (100 * slo.within / slo.count).toFixed(2) + '%' : '';
    const verdict = slo.met === undefined ? h('span', {class: 'muted'}, 'none in the window')
      : slo.met ? badge('met', 'ok') : badge('missed', 'refused');
    return h('tr', {'data-slo': family}, h('td', null, label), h('td', null, 'p99 < ' + slo['objective-ms'] + ' ms'),
      h('td', {class: 'num'}, String(slo.count)), h('td', {class: 'num'}, String(slo.within)),
      h('td', {class: 'num'}, share), h('td', null, p99(slo.p99) || ''), h('td', null, verdict));
  }), {class: 'grid', id: 'health-slos'});
}

function failureCounts(failures) {
  const entries = Object.entries(failures || {}).filter(([, n]) => n);
  return entries.length ? entries.map(([kind, n]) => kind + ' ' + n).join(', ') : '';
}

function worker(body) {
  const scans = body.scans || [];
  const last = body['last-success'];
  const now = Date.now();
  const recent = scans.filter(s => now - new Date(s['started-at']).getTime() < DAY_MS);
  const failedScans = recent.filter(s => s.outcome === 'failed');
  const byKind = {};
  for (const s of recent) {
    for (const [kind, n] of Object.entries(s.failures || {})) byKind[kind] = (byKind[kind] || 0) + n;
  }
  const latestFailed = scans.find(s => s.outcome === 'failed');
  return h('div', null,
    facts([
      ['Last successful scan', last ? h('span', {id: 'health-last-success'}, when(last['ended-at'] || last['started-at']),
        h('span', {class: 'muted'}, ' by ' + last.worker)) : h('span', {class: 'alert-line'}, 'none kept')],
      ['Scans in the last day', String(recent.length)],
      ['Failed scans in the last day', failedScans.length ? badge(String(failedScans.length), 'refused') : '0'],
      ['Failed jobs in the last day', h('span', {id: 'health-job-failures'}, failureCounts(byKind) || '0')],
      latestFailed ? ['Latest failed scan', h('span', null, when(latestFailed['started-at']), ': ',
        latestFailed.error || '')] : null,
    ]),
    scans.length ? table(['Started', 'Ended', 'Outcome', 'Failed jobs', 'Worker'], scans.slice(0, 10).map(s => h('tr', {class: s.outcome === 'failed' ? 'denied-row' : null},
      h('td', {class: 'nowrap'}, when(s['started-at'])), h('td', {class: 'nowrap'}, s['ended-at'] ? when(s['ended-at']) : h('span', {class: 'muted'}, 'running or stopped')),
      h('td', null, s.outcome ? badge(s.outcome, s.outcome === 'failed' ? 'refused' : 'ok') : h('span', {class: 'muted'}, '–')),
      h('td', null, failureCounts(s.failures)), h('td', null, s.worker))), {class: 'grid', id: 'health-scans'})
      : h('p', {class: 'muted'}, 'The worker has reported no scan.'));
}

// ---- the neediest tables: those the viewer may read ----

async function neediestTables() {
  const rows = h('tbody');
  const more = h('div');
  const status = h('div');
  let shown = 0;
  const next = async token => {
    const page = await api.neediest(token);
    for (const t of page.tables || []) {
      rows.appendChild(neediestRow(t));
      shown++;
    }
    fill(status, shown ? null : h('p', {class: 'muted', id: 'neediest-none'},
      'No table you may read needs compaction, as the worker last assessed them.'));
    fill(more, page['next-page-token'] ? h('button', {type: 'button', onclick: () => {
      fill(more, h('p', {class: 'loading'}, 'Loading…'));
      next(page['next-page-token']).catch(error => fill(more, failure(error)));
    }}, 'More') : null);
  };
  await next(null);
  return h('div', null, h('h3', null, 'Tables that need compaction'),
    h('p', {class: 'muted'}, 'Neediest first, as the maintenance worker last assessed them; only the tables you may read.'),
    h('table', {class: 'grid', id: 'neediest'},
      h('thead', null, h('tr', null, ['Table', 'Why', 'Score', 'Data files', 'Small files', 'Delete files',
        'Assessed', 'Last compaction'].map(text => h('th', null, text)))), rows),
    status, more);
}

function neediestRow(t) {
  const a = t.assessment || {};
  const c = t['last-compaction'];
  const path = [...t.namespace, t.name].join('.');
  return h('tr', {'data-table': t.catalog + '.' + path},
    h('td', null, h('button', {type: 'button', class: 'link', title: 'Open its health',
      onclick: () => go({catalog: t.catalog, kind: 'table', namespace: t.namespace, name: t.name}, 'health')},
    h('span', {class: 'muted'}, t.catalog + ' · '), path)),
    h('td', null, (a.reasons || []).map(reason => badge(reason, reason === 'equality-deletes' ? 'refused' : 'new'))),
    h('td', {class: 'num'}, String(a.score ?? '')),
    h('td', {class: 'num'}, String(a['data-files'] ?? '')),
    h('td', {class: 'num'}, String(a['small-files'] ?? '')),
    h('td', {class: 'num'}, String(a['delete-files'] ?? '')),
    h('td', {class: 'nowrap'}, when(a['assessed-at'])),
    h('td', null, c ? h('span', null, badge(c.outcome, c.outcome === 'succeeded' ? 'ok' : c.outcome === 'running'
      ? 'current' : 'refused'), ' ', when(c['ended-at'] || c['started-at'])) : h('span', {class: 'muted'}, 'none')));
}

// ---- one table's health, on its page ----

export async function tableHealthPanel(target) {
  const health = await api.tableHealth(target);
  const a = health.assessment;
  const c = health['last-compaction'];
  const assessment = a ? h('div', null,
    h('div', {class: 'status-row'}, a['needs-compaction']
      ? h('div', {class: 'status status-held', id: 'table-health-verdict'}, h('strong', null, 'Needs compaction'),
        h('div', null, 'Score ' + a.score + ': ' + (a.reasons || []).join(', ')))
      : h('div', {class: 'status', id: 'table-health-verdict'}, h('strong', null, 'No compaction needed'),
        h('div', {class: 'muted'}, 'Under every threshold of its maintenance policy.'))),
    a['needs-compaction'] ? h('section', {class: 'block'}, h('h3', null, 'Why'),
      h('ul', {id: 'table-health-reasons'}, (a.reasons || []).map(reason => h('li', {'data-reason': reason},
        REASONS[reason] || reason)))) : null,
    h('section', {class: 'block'}, h('h3', null, 'Latest assessment'), facts([
      ['Assessed', when(a['assessed-at'])],
      ['Score', String(a.score)],
      ['Data files', String(a['data-files'])],
      ['Small files', a['small-files'] + ' (' + (100 * a['small-file-share']).toFixed(1) + '% of data files)'],
      ['Delete files', String(a['delete-files'])],
      ['Position-delete files', String(a['position-delete-files'])],
      ['Deletion vectors', String(a['deletion-vectors'])],
      ['Equality-delete files', String(a['equality-delete-files'])],
    ])))
    : h('p', {class: 'muted', id: 'table-health-unassessed'}, 'The maintenance worker has not assessed this table yet.');
  return h('div', null, assessment,
    h('section', {class: 'block', id: 'table-health-compaction'}, h('h3', null, 'Last compaction'), c ? facts([
      ['Outcome', badge(c.outcome, c.outcome === 'succeeded' ? 'ok' : c.outcome === 'running' ? 'current' : 'refused')],
      ['Started', when(c['started-at'])],
      ['Ended', c['ended-at'] ? when(c['ended-at']) : null],
    ]) : h('p', {class: 'muted'}, 'No compaction has run for this table.')));
}

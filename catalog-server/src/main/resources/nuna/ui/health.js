// Health (D38): catalog health for the whole deployment, for platform operators, with the maintenance worker's
// scans (also on Maintenance: operators hold no grant, so may have no catalog to open it in), and one table's health
// on its own tab. The server decides who sees what: a refusal is shown as it answered, never guessed at here.
// Waiting on routes no item gives yet, and left out: the DR replication and store panels.

import * as api from './api.js';
import {facts, failure, fmt, h, icon, load, muted, pageHead, panel, pill, s, sized, tbl, took, when} from './dom.js';
import {REASONS, compactionWords, iceQueue, keyOfRow, queue, queueOf, reasonPill} from './queue.js';

const ALERTS = [
  ['metadata-integrity', 'Metadata integrity', 'A metadata file no longer hashes to what its pointer recorded. Pages.'],
  ['regulated-schema-change', 'Regulated schema change', 'A commit changed a regulated table\'s schema or partition spec.'],
  ['large-metadata', 'Large metadata', 'A loaded table\'s metadata is over the size or snapshot limit.'],
];

const SLOS = [
  ['load', 'Loads and exports answered 200', 'Loads within'],
  ['not-modified', 'Loads answered 304', '304s within'],
  ['commit', 'Commits answered 200', 'Commits within'],
];

/** Each SLO family with its share within the objective, as words and as a number. */
export function sloFigures(slos) {
  return SLOS.map(([family, label, short]) => {
    const slo = slos[family] || {};
    const ratio = slo.count ? slo.within / slo.count : null;
    return {family, label, short: `${short} ${slo['objective-ms']} ms`, slo, ratio,
      share: ratio === null ? null : (100 * ratio).toFixed(2) + '%', met: slo.met};
  });
}

function p99(value) {
  if (!value) return null;
  return value['at-most-ms'] !== undefined ? '≤ ' + value['at-most-ms'] + ' ms' : '> ' + value['over-ms'] + ' ms';
}

/** The share within the objective as a dot on a 97 to 100% scale; the tall tick is the 99% objective. */
function sloScale(ratio, W) {
  const x0 = W === 300 ? 16 : 14;
  const x1 = W - x0;
  const xOf = pct => x0 + (x1 - x0) * (pct - 97) / 3;
  const svg = s('svg', {viewBox: `0 0 ${W} 40`, 'aria-hidden': 'true'});
  svg.append(s('line', {class: 'ax', x1: x0, y1: 12, x2: x1, y2: 12}));
  for (const pct of [97, 98, 99, 100]) {
    const x = xOf(pct);
    svg.append(s('line', {class: pct === 99 ? 'obj' : 'tk', x1: x, y1: pct === 99 ? 4 : 8, x2: x, y2: pct === 99 ? 20 : 16}),
      s('text', {class: 'lab', x, y: 36, 'text-anchor': pct === 97 ? 'start' : pct === 100 ? 'end' : 'middle'}, pct + '%'));
  }
  if (ratio !== null) svg.append(s('circle', {class: 'dot', cx: xOf(Math.max(97, Math.min(100, 100 * ratio))), cy: 12, r: 6}));
  return svg;
}

function verdict(met) {
  return met === undefined ? muted('none in the window') : met ? pill('ok', icon('check'), 'met') : pill('bad', 'missed');
}

export function healthPage() {
  const main = h('div', {class: 'nc-main', id: 'catalog-health'});
  load(main, catalogHealth);
  return main;
}

async function catalogHealth() {
  let health;
  try {
    health = await api.catalogHealth();
  } catch (error) {
    if (error.status !== 403) throw error;
    return [pageHead({eyebrow: 'deployment · the last day, to now', title: 'Health'}),
      h('div', {class: 'notice'}, icon('info'), h('span', {class: 'health-refused'},
        h('b', null, 'Catalog health is shown to platform operators only.'), ' Your groups do not include one. The catalog answered:')),
      failure(error, {big: true})];
  }
  const slos = sloFigures(health.slos || {});
  const judged = slos.filter(x => x.met !== undefined);
  const missed = judged.filter(x => !x.met).length;
  const servers = health.servers || [];
  const total = servers.length || health.answered + (health.silent || 0);
  const worker = h('div');
  load(worker, workerPanel);
  return [
    pageHead({
      eyebrow: 'deployment · the last day, to now',
      title: [health.silent ? `${health.answered} of ${total} servers answered. ` : `All ${plural(health.answered)} answered. `,
        !judged.length ? 'No request was timed in the window.' : missed
          ? h('span', {class: 'q'}, `${missed} latency objective${missed === 1 ? ' was' : 's were'} missed.`)
          : 'Every latency objective was met.'],
      sub: 'Each server counts its own last day; this page adds them up. A server that does not answer is named here '
        + 'and its figures are left out, never guessed.',
    }),
    h('div', {class: 'health-figures'},
      panel('Latency objectives', 'met when 99 in 100 finish within the objective',
        tbl('slo-tbl hide-sm', ['Requests', 'Objective', ['Count', 'num'], ['Within it', 'num'], 'Share within, on a 97–100% scale', 'p99', 'Verdict'],
          slos.map(x => h('tr', {'data-slo': x.family}, h('td', null, x.label), h('td', {class: 'tnum'}, 'p99 < ' + x.slo['objective-ms'] + ' ms'),
            h('td', {class: 'num'}, String(x.slo.count ?? 0)), h('td', {class: 'num'}, String(x.slo.within ?? 0)),
            h('td', {class: 'slo'}, sloScale(x.ratio, 240), x.share || ''), h('td', {class: 'tnum'}, p99(x.slo.p99) || ''),
            h('td', null, verdict(x.met)))), {id: 'health-slos'}),
        h('div', {class: 'mslo slo only-sm'}, slos.map(x => h('div', null, h('span', null, x.short),
          x.share ? pill(x.met ? 'ok' : x.met === false ? 'bad' : '', x.share) : muted('none'), sloScale(x.ratio, 300)))),
        h('p', {class: 'fine hide-sm'}, 'Share within the objective on a 97 to 100% scale. The tall tick is the 99% objective.')),
      h('div', {class: 'grid-3'},
        panel('Audit to Kafka', null, audit(health.audit || {}),
          h('p', {class: 'fine'}, 'A failed publish refuses the request it audits (D23).')),
        panel('Credential vending', null, vends(health['credential-vends'] || {})),
        panel('Alerts fired', null, alerts(health.alerts || {}),
          h('p', {class: 'fine'}, 'Paging stays in your alerting; these are the last day\'s counts.'))),
      h('div', {class: 'grid-2'}, serversPanel(health, servers), worker)),
  ];
}

function serversPanel(health, servers) {
  const silent = health.silent || 0;
  return panel('Servers', `${health.answered} answered · ${silent} silent`,
    h('p', {class: silent ? 'notice' : 'fine', id: 'health-gathered'},
      `Gathered from ${plural(health.answered, 'server')}`
      + (silent ? `; ${silent} did not answer, so their figures are missing.` : '.')),
    h('div', {class: 'kpi-list', id: 'health-servers'}, servers.map(srv => h('div', {'data-server': srv.address || srv.name || ''},
      h('span', null, srv.answered ? h('b', {class: 'ok-line'}, '✓') : h('b', {class: 'warn-n'}, '✕'), ' ',
        srv.name || srv.address || '–', srv.name && srv.address ? muted(' · ' + srv.address) : null,
        srv['this-server'] ? muted(' · this server') : null,
        srv.answered ? muted(' · answered') : h('span', {class: 'muted'}, ' · no answer: ', srv.why || '')),
      h('b', null, srv.from ? 'since ' + when(srv.from) : '')))));
}

function alerts(counts) {
  return h('div', {class: 'kpi-list', id: 'health-alerts'}, ALERTS.map(([kind, label, meaning]) =>
    h('div', {'data-alert': kind, title: meaning}, h('span', null, label),
      h('b', {class: counts[kind] ? 'warn-n' : null, 'data-count': ''}, String(counts[kind] ?? 0)))));
}

function audit(a) {
  return facts([
    ['Records acknowledged', fmt(a.acknowledged ?? 0)],
    ['Mean acknowledgement', a['mean-ms'] !== undefined ? a['mean-ms'] + ' ms' : null],
    ['p99', p99(a.p99)],
    ['Publishes failed', a.failed ? h('span', {class: 'warn-n'}, String(a.failed)) : '0'],
  ]);
}

function vends(v) {
  const failures = Object.entries(v.failures || {}).sort(([a], [b]) => a.localeCompare(b));
  return h('div', null, facts([
    ['Issued', fmt(v.issued ?? 0)],
    ['Served from the cache', fmt(v.cached ?? 0)],
    ['Declined by the caller', fmt(v.none ?? 0)],
    ['Failed', v.failed ? h('span', {class: 'warn-n'}, String(v.failed)) : '0'],
  ]), failures.length ? tbl('', ['Failure', ['Vends', 'num']], failures.map(([type, n]) => h('tr', null,
    h('td', {class: 'name'}, type), h('td', {class: 'num'}, String(n)))), {id: 'health-vend-failures'}) : null);
}

function plural(n, word = 'server') {
  return `${fmt(n)} ${n === 1 ? word : word + 's'}`;
}

// ---- the maintenance worker's scans, on Maintenance: platform operators only ----

const DAY_MS = 24 * 60 * 60 * 1000;

function failureCounts(failures) {
  const entries = Object.entries(failures || {}).filter(([, n]) => n);
  return entries.length ? entries.map(([kind, n]) => kind + ' ' + n).join(', ') : '';
}

export async function workerPanel() {
  let body;
  try {
    body = await api.scans();
  } catch (error) {
    if (error.status !== 403) throw error;
    return panel('Worker scans', null, h('p', {class: 'fine health-refused'},
      'The worker\'s scans are shown to platform operators only. The catalog answered:'), failure(error, {compact: true}));
  }
  const scans = body.scans || [];
  const last = body['last-success'];
  const recent = scans.filter(x => Date.now() - new Date(x['started-at']).getTime() < DAY_MS);
  const byKind = {};
  for (const x of recent) for (const [kind, n] of Object.entries(x.failures || {})) byKind[kind] = (byKind[kind] || 0) + n;
  const latestFailed = scans.find(x => x.outcome === 'failed');
  const shown = h('tbody');
  const rowOf = x => h('tr', {class: x.outcome === 'failed' ? 'is-refused' : null},
    h('td', {class: 'tnum'}, when(x['started-at'])),
    h('td', {class: 'tnum'}, x['ended-at'] ? took(new Date(x['ended-at']) - new Date(x['started-at'])) : muted('running or stopped')),
    h('td', null, x.outcome === 'failed' ? pill('bad', 'Failed') : x.outcome ? (failureCounts(x.failures)
      ? pill('warn', 'Succeeded, some jobs failed') : pill('ok', 'Succeeded')) : muted('–')),
    h('td', {class: failureCounts(x.failures) || x.error ? null : 'muted'}, [failureCounts(x.failures), x.error ? ' ' + x.error : '']
      .join('').trim() || 'None'));
  const fillRows = n => shown.replaceChildren(...scans.slice(0, n).map(rowOf));
  fillRows(5);
  const more = scans.length > 5 ? h('button', {class: 'linkish', type: 'button', onclick: e => {
    fillRows(50);
    e.currentTarget.closest('p').replaceChildren(`The last ${Math.min(50, scans.length)} scans the catalog keeps.`);
  }}, `Show the last ${Math.min(50, scans.length)} scans`) : null;
  return h('div', {class: 'panel', id: 'health-worker'}, h('h2', null, 'Worker scans ', h('span', {class: 'meta'}, last ? last.worker : 'no worker has reported')),
    facts([
      ['Last successful scan', last ? h('span', {id: 'health-last-success'}, when(last['ended-at'] || last['started-at']),
        ' by ' + last.worker) : h('span', {class: 'warn-n'}, 'none kept')],
      ['Scans in the last day', String(recent.length)],
      ['Failed jobs in the last day', h('span', {id: 'health-job-failures'}, failureCounts(byKind) || '0')],
      latestFailed ? ['Latest failed scan', when(latestFailed['started-at']) + ': ' + (latestFailed.error || '')] : null,
    ]),
    scans.length ? tbl('scans', ['Started', 'Took', 'Outcome', 'Failures'], shown, {id: 'health-scans'})
      : h('p', {class: 'muted'}, 'The worker has reported no scan.'),
    h('p', {class: 'fine'}, 'A failure on one table never stops the scan; the next scan retries that table. ', more));
}

// ---- one table's health, on its tab ----

export async function tableHealthTab(target) {
  const [health, rows] = await Promise.all([api.tableHealth(target), queue().catch(() => [])]);
  const a = health.assessment;
  const c = health['last-compaction'];
  const mine = [target.catalog, ...target.namespace, target.name].join('.');
  const inCatalog = queueOf(rows, target.catalog);
  const verdictLine = !a ? null : a['needs-compaction']
    ? h('p', {id: 'table-health-verdict'}, h('span', {class: 'pill ' + ((a.reasons || []).includes('equality-deletes') ? 'bad' : 'warn')},
      'Needs compaction · score ' + a.score), ' ', muted('Score ' + a.score + ': ' + (a.reasons || []).join(', ')))
    : h('p', {id: 'table-health-verdict'}, pill('ok', icon('check'), 'No compaction needed'), ' ',
      muted('Under every threshold of its maintenance policy.'));
  const mini = a && a['needs-compaction'] && inCatalog.some(r => keyOfRow(r) === mine) ? h('div', {class: 'iceline mini'}) : null;
  if (mini) sized(mini, (host, w) => iceQueue(host, w, inCatalog, {me: mine}));
  const assessment = a ? h('div', {class: 'panel'}, h('h2', null, 'Assessment ', h('span', {class: 'meta'}, 'assessed ' + when(a['assessed-at']))),
    verdictLine, mini,
    a['needs-compaction'] ? h('ul', {id: 'table-health-reasons', class: 'stack'}, (a.reasons || []).map(reason =>
      h('li', {'data-reason': reason}, reasonPill(reason), ' ', h('span', {class: 'fine'}, (REASONS[reason] || [null, null, reason])[2])))) : null,
    facts([
      ['Score', String(a.score)],
      ['Data files', String(a['data-files'])],
      ['Small files', a['small-files'] + ' (' + (100 * (a['small-file-share'] || 0)).toFixed(1) + '% of data files)'],
      ['Delete files', String(a['delete-files'])],
      ['Position-delete files', String(a['position-delete-files'])],
      ['Deletion vectors', String(a['deletion-vectors'])],
      ['Equality-delete files', String(a['equality-delete-files'])],
    ]))
    : h('div', {class: 'panel'}, h('h2', null, 'Assessment'),
      h('p', {class: 'muted', id: 'table-health-unassessed'}, 'The maintenance worker has not assessed this table yet.'));
  const last = h('div', {class: 'panel', id: 'table-health-compaction'}, h('h2', null, 'Last compaction'), c ? facts([
    ['Outcome', compactionWords(c)[0]],
    ['Started', when(c['started-at'])],
    ['Ended', c['ended-at'] ? when(c['ended-at']) : null],
  ]) : h('p', {class: 'muted'}, 'No compaction has run for this table.'));
  const policyPanel = h('div');
  load(policyPanel, () => policyFacts(target));
  return h('div', {class: 'grid-2'}, assessment, h('div', {class: 'stack'}, last, policyPanel));
}

/** The policy in force on a table: each key with where it was set. */
async function policyFacts(target) {
  const p = await api.policy(target);
  const resolved = Object.entries(p.resolved || {}).sort(([a], [b]) => a.localeCompare(b));
  return panel('Policy for this table', 'each key with where it was set', h('dl', {class: 'facts one'},
    resolved.map(([key, setting]) => [h('dt', null, h('code', null, key)),
      h('dd', null, setting.source === 'guardrail' ? [h('b', null, setting.value), ' · guardrail',
        setting.reason ? [h('br'), h('span', {class: 'fine'}, setting.reason)] : null]
        : [setting.value, ' · ', sourceWords(setting.source)])])));
}

/** Where a policy key was set: built in, a guardrail, or the level's kind. */
export function sourceWords(source) {
  if (!source || source === 'default') return muted('built-in');
  if (source === 'guardrail') return 'guardrail';
  const [kind] = String(source).split(':');
  return h('span', {class: 'muted', title: source}, kind === 'catalog' ? 'the catalog' : kind === 'namespace' ? 'a namespace above'
    : 'this ' + kind);
}

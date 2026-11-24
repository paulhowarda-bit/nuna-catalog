// Service (D38, named Health before item 192): catalog health for the whole deployment, for platform operators, with the maintenance worker's
// scans (also on Maintenance: operators hold no grant, so may have no catalog to open it in), and one table's health
// on its own tab, with its maintenance on request (item 114). The server decides who sees what: a refusal is shown as
// it answered, never guessed at here. DR replication (item 138) and the store (item 139) are for platform operators
// too; their panels are left out for anyone the catalog refuses them to.

import * as api from './api.js';
import {at, code, facts, failure, fmt, h, icon, load, muted, pageHead, panel, pill, s, sized, tbl, took, when} from './dom.js';
import {objectHref} from './route.js';
import {REASONS, compactionWords, iceQueue, keyOfRow, maintenanceOff, queue, queueOf, reasonPill} from './queue.js';
import {mayModify, requestButtons, requestsBox} from './requests.js';

const ALERTS = [
  ['metadata-integrity', 'Metadata integrity', 'A metadata file no longer hashes to what its pointer recorded. Pages.'],
  ['regulated-schema-change', 'Regulated schema change', 'A commit changed a regulated table\'s schema or partition spec.'],
  ['large-metadata', 'Large metadata', 'A loaded table\'s metadata is over the size or snapshot limit.'],
];

const SLOS = [
  ['load', 'Table loads and exports', 'Loads within', 'Loads and exports answered 200'],
  ['not-modified', 'Loads that found no change', 'Unchanged loads within', 'Loads answered 304'],
  ['commit', 'Commits', 'Commits within', 'Commits answered 200'],
];

/** Each SLO family with its share within the objective, as words and as a number. */
export function sloFigures(slos) {
  return SLOS.map(([family, label, short, status]) => {
    const slo = slos[family] || {};
    const ratio = slo.count ? slo.within / slo.count : null;
    return {family, label, status, short: `${short} ${slo['objective-ms']} ms`, slo, ratio,
      share: ratio === null ? null : (100 * ratio).toFixed(2) + '%', met: slo.met};
  });
}

function p99(value) {
  if (!value) return null;
  return value['at-most-ms'] !== undefined ? '≤ ' + value['at-most-ms'] + ' ms' : '> ' + value['over-ms'] + ' ms';
}

/**
 * The share within the objective as a dot on a 97 to 100% scale; the tall tick is the 99% objective. A share below
 * the scale is a marker pointing off its left edge with the share beside it, so 78% and 1% no longer look alike
 * (Atelier's review, F15; backlog 194).
 */
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
  if (ratio === null) return svg;
  const pct = 100 * ratio;
  if (pct < 97) {
    svg.append(s('path', {class: 'off', d: `M${x0 - 8} 12 L${x0 + 3} 5.5 L${x0 + 3} 18.5 Z`}),
      s('text', {class: 'offv', x: x0 + 9, y: 16.5}, Math.floor(pct * 10) / 10 + '%'));
  } else {
    svg.append(s('circle', {class: 'dot', cx: xOf(Math.min(100, pct)), cy: 12, r: 6}));
  }
  return svg;
}

function verdict(met) {
  return met === undefined ? muted('no requests in the last day') : met ? pill('ok', icon('check'), 'met') : pill('bad', 'missed');
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
    return [pageHead({eyebrow: 'all catalogs · the last day', title: 'Service'}),
      h('div', {class: 'notice'}, icon('info'), h('span', {class: 'health-refused'},
        h('b', null, 'Service is shown to platform operators only,'), ' and your groups include none. The catalog answered:')),
      failure(error, {big: true})];
  }
  const slos = sloFigures(health.slos || {});
  const judged = slos.filter(x => x.met !== undefined);
  const missed = judged.filter(x => !x.met).length;
  const servers = health.servers || [];
  const total = servers.length || health.answered + (health.silent || 0);
  const worker = h('div');
  load(worker, workerPanel);
  const dr = h('div');
  load(dr, drPanel);
  const store = h('div');
  load(store, storePanel);
  return [
    pageHead({
      eyebrow: 'all catalogs · the last day',
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
          slos.map(x => h('tr', {'data-slo': x.family, title: x.status}, h('td', null, x.label), h('td', {class: 'tnum'}, 'p99 < ' + x.slo['objective-ms'] + ' ms'),
            h('td', {class: 'num'}, String(x.slo.count ?? 0)), h('td', {class: 'num'}, String(x.slo.within ?? 0)),
            h('td', {class: 'slo'}, sloScale(x.ratio, 240), x.share || ''), h('td', {class: 'tnum'}, p99(x.slo.p99) || ''),
            h('td', null, verdict(x.met)))), {id: 'health-slos'}),
        h('div', {class: 'mslo slo only-sm'}, slos.map(x => h('div', {title: x.status}, h('span', null, x.short),
          x.share ? pill(x.met ? 'ok' : x.met === false ? 'bad' : '', x.share) : muted('none'), sloScale(x.ratio, 300)))),
        h('p', {class: 'fine hide-sm'}, 'Share within the objective on a 97 to 100% scale. The tall tick is the 99% objective; a share '
          + 'below 97% points off the scale\'s left edge, with its value.')),
      h('div', {class: 'grid-3'},
        panel('Audit', null, audit(health.audit || {})),
        panel('Credential vending', null, vends(health['credential-vends'] || {})),
        panel('Alerts fired', null, alerts(health.alerts || {}),
          h('p', {class: 'fine'}, 'Paging stays in your alerting; these are the last day\'s counts.'))),
      h('div', {class: 'grid-2'}, serversPanel(health, servers), worker),
      h('div', {class: 'grid-2'}, dr, store)),
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

/**
 * Where audit records go (D23, amended 2026-11-23; backlog 205): every one to the log before the catalog answers, and
 * a copy to Kafka where it is configured, best effort. The server's figures count only that copy.
 */
function audit(a) {
  const copied = (a.acknowledged ?? 0) + (a.failed ?? 0) > 0;
  return h('div', {id: 'health-audit'},
    h('p', null, 'Each request's audit record is written to the log ', code('nuna.audit'), ' before the catalog answers it.'),
    copied ? [facts([
      ['Copied to Kafka', fmt(a.acknowledged ?? 0)],
      ['Mean time to copy', a['mean-ms'] !== undefined ? a['mean-ms'] + ' ms' : null],
      ['p99', p99(a.p99)],
      ['Copies failed', a.failed ? h('span', {class: 'warn-n'}, String(a.failed)) : '0'],
    ]), h('p', {class: 'fine'}, 'A copy that fails blocks nothing: the log already holds the record.')]
      : h('p', {class: 'fine'}, 'No record was copied to Kafka in the last day.'));
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

// ---- DR replication (D37, item 138): platform operators only ----

// The readout reads one journal record a row, so Health reads at most 1,000 tables and views, by id.
const DR_PAGE = 200;
const DR_PAGES = 5;

/**
 * The readout's rows, split: those behind a watermark the verifier recorded, and those with none, which the readout
 * counts as behind by every version they have; the most versions behind and the longest lag are of the first.
 */
export function drFigures(tables) {
  const behind = tables.filter(t => t['watermark-version'] !== null && t['watermark-version'] !== undefined);
  const never = tables.filter(t => !behind.includes(t));
  const most = behind.reduce((a, t) => !a || t['versions-behind'] > a['versions-behind'] ? t : a, null);
  const longest = behind.filter(t => t['lag-seconds'] !== undefined)
    .reduce((a, t) => !a || t['lag-seconds'] > a['lag-seconds'] ? t : a, null);
  return {behind, never, most, longest};
}

async function drPanel() {
  let read;
  try {
    read = await api.drBehind(DR_PAGE, DR_PAGES);
  } catch (error) {
    if (error.status === 403) return null;
    throw error;
  }
  const {tables, more} = read;
  const {behind, never, most, longest} = drFigures(tables);
  const count = n => (more ? 'at least ' : '') + fmt(n);
  const head = h('h2', null, 'DR replication ', h('span', {class: 'meta'}, 'the DR store against each watermark'));
  if (!tables.length) {
    return h('div', {class: 'panel hide-sm', id: 'health-dr'}, head, kpis([
      ['behind', 'Tables behind their watermark', '0'],
    ]), h('p', {class: 'fine', id: 'health-dr-none'}, 'No table or view is behind its DR watermark: the verifier found '
      + 'each whole in the DR store at its current version.'));
  }
  if (!behind.length) {
    return h('div', {class: 'panel hide-sm', id: 'health-dr'}, head,
      h('p', {class: 'notice', id: 'health-dr-no-verifier'}, icon('info'), h('span', null,
        h('b', null, 'No DR verifier has reported on them.'),
        ` ${count(never.length)} ${never.length === 1 && !more ? 'table or view has' : 'tables and views have'} no DR `
        + 'watermark, so none is counted as behind one. The maintenance worker\'s verifier records one when it '
        + 'checks a DR store.')));
  }
  const target = t => ({catalog: t.catalog, kind: t.kind, namespace: t.namespace || [], name: t.name});
  return h('div', {class: 'panel hide-sm', id: 'health-dr'}, head, kpis([
    ['behind', 'Tables behind their watermark', count(behind.length)],
    never.length ? ['never', 'Never verified', count(never.length)] : null,
    ['most', 'Most versions behind', [fmt(most['versions-behind']), ' · ', most.catalog
      ? h('a', {href: objectHref(target(most)), title: [most.catalog, ...(most.namespace || []), most.name].join('.')}, most.name)
      : most.name]],
    ['lag', 'Longest lag', longest ? took(1000 * longest['lag-seconds']) : null],
  ]),
  never.length ? h('p', {class: 'fine'}, 'Never verified: no watermark yet, so the readout counts every version they '
    + 'have. They are left out of Most versions behind and Longest lag.') : null,
  more ? h('p', {class: 'fine', id: 'health-dr-more'}, `Only the first ${fmt(DR_PAGE * DR_PAGES)} tables were checked; `
    + 'Most versions behind and Longest lag are among those.') : null);
}

/** A list of figures, each [key, label, value]; a value that is null shows as a dash. */
function kpis(rows) {
  return h('div', {class: 'kpi-list'}, rows.filter(Boolean).map(([key, label, value]) =>
    h('div', {'data-fact': key}, h('span', null, label), h('b', null, value === null || value === undefined ? '–' : value))));
}

// ---- the catalog's database, as this server finds it (item 139): platform operators only ----

const BACKENDS = {postgresql: 'PostgreSQL or CockroachDB', oracle: 'Oracle', mongodb: 'MongoDB'};

async function storePanel() {
  let body;
  try {
    body = await api.serverHealth();
  } catch (error) {
    if (error.status === 403) return null;
    throw error;
  }
  const store = body.store || {};
  const check = store['self-check'] || {};
  const allowed = check.allowed || [];
  return h('div', {class: 'panel hide-sm', id: 'health-store'},
    h('h2', null, 'Store ', h('span', {class: 'meta'}, 'as ' + (body.server && body.server.name || 'this server') + ' finds it')),
    kpis([
      ['backend', 'Backend', store.backend ? store.backend + ' ' + store.version
        : h('span', {class: 'warn-n'}, (BACKENDS[store.configured] || store.configured) + ' · not reached')],
      ['self-check', 'Self-check at start', h('span', {class: allowed.length ? 'warn-n' : null}, at(check.at) + ' · '
        + (allowed.length ? `started despite ${plural(allowed.length, 'finding')}` : 'passed'))],
      ['pitr', 'Point-in-time recovery window', windowWords(store['pitr-window-seconds']) + ' · declared'],
    ]),
    store.unreadable ? h('p', {class: 'fine', id: 'health-store-unreadable'}, 'The database did not answer: ', store.unreadable) : null,
    allowed.length ? h('ul', {class: 'stack', id: 'health-store-findings'}, allowed.map(f =>
      h('li', {'data-code': f.code}, code(f.code), ' ', h('span', {class: 'fine'}, f.detail)))) : null,
    h('p', {class: 'fine'}, 'Each server checks its database\'s durability when it starts, and starts despite a finding '
      + 'only when the deployment\'s ', h('span', {title: 'nuna.database.allow-unsafe'}, 'settings'), ' allow it.'),
    h('p', {class: 'fine', id: 'health-store-pitr'}, 'This window is what the deployment ',
      h('span', {title: 'nuna.governance.pitr-window'}, 'declares'), ', not something read from the database. Orphan '
      + 'removal deletes only files older than it, and dropped tables are kept longer, so a database restored within it '
      + 'never names a deleted file. Keep the database\'s own backups at least this long: the catalog cannot check.'));
}

/** A declared window in seconds, in whole days or hours when it is one. */
function windowWords(seconds) {
  if (seconds % 86400 === 0) return plural(seconds / 86400, 'day');
  if (seconds % 3600 === 0) return plural(seconds / 3600, 'hour');
  return took(1000 * seconds);
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
      : h('p', {class: 'muted'}, 'The maintenance worker has not reported a scan. Check that it is running.'),
    h('p', {class: 'fine'}, 'A failure on one table never stops the scan; the next scan retries that table. ', more));
}

// ---- one table's health, on its tab ----

export async function tableHealthTab(target, reload) {
  const [health, rows, may, policy] = await Promise.all([api.tableHealth(target), queue().catch(() => []), mayModify(target),
    api.policy(target).catch(() => null)]);
  const a = health.assessment;
  const c = health['last-compaction'];
  const off = maintenanceOff(target.catalog, policy, objectHref(target, 'maintenance'));
  const offLine = off ? h('p', {class: 'notice', id: 'table-health-off'}, icon('info'), off) : null;
  const mine = [target.catalog, ...target.namespace, target.name].join('.');
  const inCatalog = queueOf(rows, target.catalog);
  const verdictLine = !a ? null : a['needs-compaction']
    ? h('p', {id: 'table-health-verdict'}, h('span', {class: 'pill ' + ((a.reasons || []).includes('equality-deletes') ? 'bad' : 'warn')},
      'Needs compaction · score ' + a.score), ' ', muted('Score ' + a.score + ': ' + (a.reasons || []).map(r => REASONS[r] ? REASONS[r][1].toLowerCase() : r).join(', ')))
    : h('p', {id: 'table-health-verdict'}, pill('ok', icon('check'), 'No compaction needed'), ' ',
      muted('Under every threshold of its maintenance policy.'));
  const mini = a && a['needs-compaction'] && inCatalog.some(r => keyOfRow(r) === mine) ? h('div', {class: 'iceline mini'}) : null;
  if (mini) sized(mini, (host, w) => iceQueue(host, w, inCatalog, {me: mine}));
  const assessment = a ? h('div', {class: 'panel'}, h('h2', null, 'Assessment ', h('span', {class: 'meta'}, 'assessed ' + when(a['assessed-at']))),
    offLine, verdictLine, mini,
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
    : h('div', {class: 'panel'}, h('h2', null, 'Assessment'), offLine,
      h('p', {class: 'muted', id: 'table-health-unassessed'}, 'The maintenance worker has not assessed this table yet.'));
  // Requests are main's: the worker compacts and scans main only, so a page read through a ref offers none (item 109).
  if (may && !target.ref) {
    assessment.append(h('div', {class: 'refusal-acts', id: 'table-health-acts'}, requestButtons(target, health.requests, reload),
      h('span', {class: 'fine'}, 'Runs at the worker\'s next scan, even outside the cleanup window.')));
  }
  const last = h('div', {class: 'panel', id: 'table-health-compaction'}, h('h2', null, 'Last compaction'), c ? facts([
    ['Outcome', compactionWords(c)[0]],
    ['Started', when(c['started-at'])],
    ['Ended', c['ended-at'] ? when(c['ended-at']) : null],
  ]) : h('p', {class: 'muted'}, 'No compaction has run for this table.'));
  const policyPanel = h('div');
  load(policyPanel, () => policyFacts(target));
  return [requestsBox(health.requests), h('div', {class: 'grid-2'}, assessment, h('div', {class: 'stack'}, last, policyPanel))];
}

/** The maintenance policy's keys in words (Atelier's words table, "Not words" 3; backlog 207). */
export const POLICY_LABELS = {
  'snapshot-expiry.enabled': 'Snapshot expiry', 'snapshot-expiry.max-snapshot-age': 'Keep snapshots for',
  'snapshot-expiry.min-snapshots-to-keep': 'Keep at least', 'orphan-removal.enabled': 'Orphan removal',
  'orphan-removal.older-than': 'Remove orphans older than', 'compaction.enabled': 'Compaction',
  'compaction.target-file-size-bytes': 'Target file size', 'compaction.min-input-files': 'Fewest input files',
  'compaction.delete-file-ratio': 'Delete-file share', 'manifest-rewrite.enabled': 'Manifest rewrite',
  'manifest-rewrite.min-manifests': 'Fewest manifests',
};

/** A policy key as its label, the key itself in its title; a key the console has no label for stays a name. */
export const policyKey = key => POLICY_LABELS[key] ? h('span', {title: key, 'data-key': key}, POLICY_LABELS[key]) : code(key);

/** The policy in force on a table: each key with where it was set. */
async function policyFacts(target) {
  const p = await api.policy(target);
  const resolved = Object.entries(p.resolved || {}).sort(([a], [b]) => a.localeCompare(b));
  return h('div', {'data-policy': ''}, panel('Policy for this table', 'each setting with where it was set', h('dl', {class: 'facts one'},
    resolved.map(([key, setting]) => [h('dt', null, policyKey(key)),
      h('dd', null, setting.source === 'guardrail' ? [h('b', null, setting.value), ' · guardrail',
        setting.reason ? [h('br'), h('span', {class: 'fine'}, setting.reason)] : null]
        : [setting.value, ' · ', sourceWords(setting.source)])]))));
}

/** Where a policy key was set: built in, a guardrail, or the level's kind. */
export function sourceWords(source) {
  if (!source || source === 'default') return muted('built-in');
  if (source === 'guardrail') return 'guardrail';
  const [kind] = String(source).split(':');
  return h('span', {class: 'muted', title: source}, kind === 'catalog' ? 'the catalog' : kind === 'namespace' ? 'a namespace above'
    : 'this ' + kind);
}

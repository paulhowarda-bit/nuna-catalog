// Audit in one catalog (item 125): every record in the catalog, newest first, from the audit tables, filtered by
// kind, person, path and time, each record with its request id, where it was read from and its hash chain; the
// last day's counts by kind and the busiest objects; and the archive check. These routes are for audit readers
// only (D23): anyone else is shown the refusal as the catalog answered it, with the catalog's own records below,
// for those who manage its grants, read from the audit table, or from Kafka where there is none (backlog 197).

import * as api from './api.js';
import {at, code, copyButton, facts, failure, fmt, h, icon, jsonPre, load, muted, pageHead, panel, pill, plural,
  tbl} from './dom.js';
import {objectHref, SEP} from './route.js';
import {AUDIT_KINDS as KINDS, CHANGES, auditBadge, auditKindOf, auditTab, describe, shortType as short} from './panels.js';

/** Every type a kind names: All, less the audit's own reads, is these. */
const TYPES = KINDS.flatMap(([, , , types]) => types || []);
const PREFIX = 'nuna.catalog.';
/** A record type's kind in the feed: All for a type no kind names. */
const kindOf = type => auditKindOf(type) || 'all';

/** The day and time, today's too: "22 Nov 16:33". */
function onDay(value) {
  const d = new Date(value);
  const p2 = n => String(n).padStart(2, '0');
  return Number.isNaN(d.getTime()) ? String(value || '')
    : `${d.getDate()} ${d.toLocaleString('en-GB', {month: 'short'})} ${p2(d.getHours())}:${p2(d.getMinutes())}`;
}

// ---- the feed's filters, kept in the link so Back and a shared link keep them (backlog 187) ----

function readFilters() {
  const q = new URLSearchParams(location.hash.slice(1));
  const kind = q.get('kind');
  const to = q.get('to');
  return {kind: KINDS.some(([k]) => k === kind) ? kind : 'all', who: q.get('who') || '', path: q.get('path') || '',
    upTo: UP_TO.some(([v]) => v === to) ? to : '', auditReads: q.get('reads') === 'all'};
}

function keepFilters(st) {
  const q = new URLSearchParams(location.hash.slice(1));
  for (const [k, v] of [['kind', st.kind === 'all' ? '' : st.kind], ['who', st.who], ['path', st.path], ['to', st.upTo],
    ['reads', st.auditReads ? 'all' : '']]) {
    if (v) q.set(k, v);
    else q.delete(k);
  }
  const hash = '#' + q.toString();
  if (hash !== location.hash) history.replaceState(history.state, '', hash);
}

/** How far back the feed reads, as `to` (exclusive): now, or an hour, six hours, a day or a week back. */
const UP_TO = [['', 'Up to now'], ['1', 'Before an hour ago'], ['6', 'Before six hours ago'], ['24', 'Before a day ago'],
  ['168', 'Before a week ago']];

export function auditPage(catalog) {
  const main = h('div', {class: 'nc-main', id: 'audit-page'});
  const summary = api.auditSummary(catalog);
  const archive = api.auditArchive();
  summary.catch(() => {});
  archive.catch(() => {});
  const head = h('div');
  const kpis = h('div', {class: 'kpis', id: 'audit-kpis'});
  const side = h('div', {class: 'stack'});
  load(head, async () => {
    let title = `The audit of ${catalog}`;
    try {
      const sum = await summary;
      title = `${plural(total(sum['by-type']), 'record')} in ${catalog} in the last day.`;
    } catch (ignored) {
      // the summary's own refusal is shown in its panel
    }
    return pageHead({
      eyebrow: `${catalog} · audit`,
      title,
      sub: 'Every read, change, credential, access refusal and file deletion in this catalog, newest first. Open a record '
        + 'for the full event, its request id and its place in the hash chain.',
      side: h('button', {class: 'btn sm', type: 'button', 'data-act': 'goto-audit'}, icon('search'), 'Open a table\'s audit tab'),
    });
  });
  Promise.allSettled([summary, archive]).then(([sum, arch]) => {
    const list = [];
    if (sum.status === 'fulfilled') list.push(...summaryKpis(catalog, sum.value));
    if (arch.status === 'fulfilled') list.push(archiveKpi(arch.value));
    if (list.length) kpis.append(...list);
    else kpis.remove();
  });
  const feedBox = h('div', {class: 'panel', id: 'audit-feed'});
  const own = h('div');
  const feed = feedPanel(catalog, summary, own);
  feedBox.append(h('h2', null, 'Every record ', h('span', {class: 'meta'}, 'newest first')), feed.el);
  // A refusal of the counts or the archive is the feed's refusal again, shown there in full; these stay quiet.
  const quiet = error => error.status === 403 || error.status === 503 ? null : failure(error);
  const archivePanel = h('div');
  load(archivePanel, () => archive.then(chainPanel, quiet));
  const counts = h('div');
  load(counts, () => summary.then(sum => countPanels(catalog, sum, feed.kinds), quiet));
  side.append(archivePanel, counts);
  main.append(head, kpis, h('div', {class: 'grid-21'}, feedBox, side), own);
  return main;
}

const total = byType => Object.values(byType || {}).reduce((n, x) => n + Number(x || 0), 0);
const kindTotal = (byType, types) => Object.entries(byType || {}).filter(([t]) => !types || types.includes(short(t)))
  .reduce((n, [, x]) => n + Number(x || 0), 0);

function summaryKpis(catalog, sum) {
  const all = total(sum['by-type']);
  return [
    h('div', {class: 'kpi', 'data-kpi': 'records'}, h('p', {class: 'kpi-l'}, icon('audit'), `Records in ${catalog}`),
      h('p', {class: 'kpi-v'}, fmt(all)), h('p', {class: 'kpi-d'}, h('b', null, fmt(total(sum['deployment-by-type']))), ' across all catalogs')),
    h('div', {class: 'kpi', 'data-kpi': 'refusals'}, h('p', {class: 'kpi-l'}, 'Refusals'),
      h('p', {class: 'kpi-v'}, fmt(kindTotal(sum['by-type'], ['denial', 'auth-failure']))), h('p', {class: 'kpi-d'}, 'each one a record in the feed')),
    h('div', {class: 'kpi', 'data-kpi': 'changes'}, h('p', {class: 'kpi-l'}, 'Changes'),
      h('p', {class: 'kpi-v'}, fmt(kindTotal(sum['by-type'], CHANGES))), h('p', {class: 'kpi-d'}, 'commits, governance, grants and deletions')),
  ];
}

/** When the archive check last ended, and whether the archive was whole and complete. */
function archiveKpi(a) {
  const check = a.check;
  return h('div', {class: 'kpi', 'data-kpi': 'archive'}, h('p', {class: 'kpi-l'}, icon('check'), 'Archive checked'),
    h('p', {class: 'kpi-v'}, !a.configured ? '–' : check ? at(check['ended-at']) : 'not yet'),
    h('p', {class: 'kpi-d'}, !a.configured ? 'no archive is configured' : !check ? 'the first check runs a minute after start'
      : !check.intact ? h('b', null, 'the archive is not intact: see Hash chain')
        : !check.complete ? h('b', null, 'records are missing: see Hash chain')
          : `${plural(check.runs || 0, 'server run')} whole`));
}

/** What the check found wrong with a chain, in a sentence or a few. */
const findings = c => [
  ...(c.problems || []),
  ...(c.missing || []).map(m => m.from === m.to ? `record ${fmt(m.from)} is missing` : `records ${fmt(m.from)} to ${fmt(m.to)} are missing`),
  c.unended ? `the run stopped at record ${fmt(c.highest)} without its end` : null,
  c.failed ? `could not be checked: ${c.failed}` : null,
].filter(Boolean).join('; ');

/**
 * The archive and its hash chains, one per server run (backlog 262): where it is, the writer's checkpoints, how many runs
 * are verified, and what was found wrong.
 */
function chainPanel(a) {
  if (!a.configured) {
    return panel('Hash chain', null, h('p', {class: 'muted'}, 'No archive is configured on this server, so no chain is checked.'));
  }
  const check = a.check;
  const cp = check ? check.checkpoints || {} : {};
  const wrong = check ? (check.chains || []).filter(c => findings(c)) : [];
  const rows = [
    ...(cp.problems || []).map(p => ['checkpoints', p]),
    ...(cp.late ? [['checkpoints', cp.at ? `the newest is from ${at(cp.at)}, more than two intervals ago: is the writer running?`
      : 'the writer has written none']] : []),
    ...wrong.map(c => [c.chain, findings(c)]),
  ];
  return h('div', {class: 'panel', id: 'hash-chain'}, h('h2', null, 'Hash chain ', h('span', {class: 'meta'}, 'checked hourly')),
    h('p', null, !check ? pill('', icon('clock'), 'Not checked yet') : !check.intact ? pill('bad', icon('x'), 'Not intact')
      : !check.complete ? pill('bad', icon('x'), 'Records missing') : pill('ok', icon('check'), 'Whole at ' + at(check['ended-at']))),
    facts([
      ['Runs', check ? h('span', {title: 'One chain per server run'}, `${fmt(check.runs)} verified, ${fmt(check['runs-done'])} ended`
        + ` complete · ${fmt(check['records-verified'])} records`) : null],
      ['Each record', 'hashes the one before it in its server run, and carries its number in the run'],
      ['Checkpoints', cp.at ? `newest ${at(cp.at)}, one at least every ${cp.interval}` : null],
      ['Archive', code(`s3://${a.bucket}/${a.prefix || ''}`)],
      ['Last check', check ? `${at(check['started-at'])} to ${at(check['ended-at'])}` : null],
    ]),
    rows.length ? tbl('stack-line', ['Chain', 'What was found'], rows.map(([chain, found]) => h('tr', {'data-chain': chain},
      h('td', {class: 'name'}, chain), h('td', {class: 'wrap'}, found)))) : null);
}

/** The last day's counts by kind, which the feed draws with its chips; and the objects with the most records. */
function countPanels(catalog, sum, kinds) {
  return [
    h('div', {class: 'panel', id: 'audit-kinds'}, h('h2', null, 'By kind ', h('span', {class: 'meta'}, 'last day')), kinds,
      h('p', {class: 'fine', title: 'The writer leaves its own records out, and a record sent to the fallback log reaches '
        + 'neither count.'}, `${onDay(sum.from)} to ${onDay(sum.to)}. Choose a kind to show its records in the feed. Service `
        + 'counts differently, so its figures may not match these.')),
    h('div', {class: 'panel', id: 'audit-busiest'}, h('h2', null, 'Most recorded ', h('span', {class: 'meta'}, 'last day')),
      (sum.busiest || []).length ? tbl('stack-line', ['Object', ['Records', 'num'], ['Reads', 'num'], 'Written most by'],
        sum.busiest.map(b => h('tr', {'data-path': (b.path || []).join('.')},
          h('td', {class: 'name'}, objectLink(catalog, b.kind, b.path, 'audit')), h('td', {class: 'num'}, fmt(b.records)),
          h('td', {class: 'num'}, fmt(b.reads)), h('td', {class: 'name'}, b.writer || muted('–')))))
        : h('p', {class: 'muted'}, 'No object has a record in the last day.')),
  ];
}

/** An object named by an audit row, as a link to its page. */
function objectLink(catalog, kind, path, tab) {
  const k = String(kind || '').toLowerCase();
  const levels = path || [];
  if (k === 'catalog' || !levels.length) return code(catalog);
  const target = k === 'namespace' ? {catalog, kind: k, namespace: levels}
    : {catalog, kind: k, namespace: levels.slice(0, -1), name: levels[levels.length - 1]};
  // The path breaks after each dot, so a long one wraps and the number columns beside it stay in view (backlog 220).
  return h('a', {href: objectHref(target, tab)}, levels.flatMap((x, i) => i < levels.length - 1 ? [x + '.', h('wbr')] : [x]));
}

// ---- the feed ----

/** The feed and its filters: {el, choose(type)}, where choose shows the kind a record type belongs to. */
function feedPanel(catalog, summary, own) {
  const st = readFilters();
  const chipBox = h('div', {class: 'chips', role: 'group', 'aria-label': 'Filter by kind'});
  const who = h('input', {type: 'search', name: 'who', 'aria-label': 'Who', placeholder: 'Anyone', autocomplete: 'off', spellcheck: 'false'});
  const path = h('input', {type: 'search', name: 'path', 'aria-label': 'Table or namespace', placeholder: 'a.b.table',
    autocomplete: 'off', spellcheck: 'false'});
  const upTo = h('select', {name: 'to', 'aria-label': 'Up to'}, UP_TO.map(([v, l]) => h('option', {value: v}, l)));
  const reads = h('input', {type: 'checkbox', name: 'audit-reads'});
  // How many records the headline counts that the chips leave out while the audit's reads are hidden (backlog 220).
  const hidden = h('span', {class: 'muted', 'data-hidden': ''});
  who.value = st.who;
  path.value = st.path;
  upTo.value = st.upTo;
  reads.checked = st.auditReads;
  // The audit's own reads are each reader's look at the feed: hidden unless asked for, or they bury the rest.
  const typesOf = kind => (KINDS.find(([k]) => k === kind)[3] || TYPES).filter(t => st.auditReads || t !== 'audit-read');
  const status = h('p', {class: 'fine feed-status', role: 'status'});
  const feed = h('div', {class: 'feed', 'data-feed': ''});
  const more = h('p', {class: 'fine feed-more'});
  let gen = 0;
  let shown = 0;
  let counted = null;
  // By kind, drawn with the chips from the same counts, so its rows are the chips' words and add up to them (backlog 214).
  const kinds = h('div', {'data-kinds': ''});
  const drawKinds = () => {
    if (!counted) return;
    // A type no kind names is in the feed's All only while the audit's reads are shown, as the feed then asks for every type.
    const unknown = st.auditReads ? Object.keys(counted).filter(t => !TYPES.includes(short(t))) : [];
    const rows = [...KINDS.filter(([k]) => k !== 'all').map(([k, label]) => [k, label, typesOf(k)]),
      ['all', 'Other', unknown.map(short)]].map(([k, label, types]) => {
      const parts = Object.entries(counted).filter(([t, n]) => types.includes(short(t)) && Number(n)).sort((a, b) => b[1] - a[1]);
      return {k, label, n: parts.reduce((s, [, n]) => s + Number(n), 0), title: parts.map(([t, n]) => `${short(t)} ${fmt(n)}`).join(' · ')};
    }).filter(r => r.n);
    kinds.replaceChildren(tbl('kinds', ['Kind', ['Records', 'num']], [...rows.map(r => h('tr', {'data-kind': r.k, title: r.title},
      h('td', null, h('button', {class: 'linkish', type: 'button', 'data-choose': r.k, 'aria-label': `Show ${r.label.toLowerCase()} in the feed`,
        onclick: () => chooseKind(r.k)}, r.label)), h('td', {class: 'num'}, fmt(r.n)))),
    h('tr', {class: 'is-cur'}, h('td', null, h('b', null, 'All')), h('td', {class: 'num'}, h('b', null, fmt(rows.reduce((s, r) => s + r.n, 0)))))]));
  };
  const drawChips = () => {
    drawKinds();
    const n = counted && !st.auditReads ? total(counted) - kindTotal(counted, typesOf('all')) : 0;
    hidden.textContent = n ? ` · ${plural(n, 'record')} hidden` : '';
    chipBox.replaceChildren(...KINDS.map(([k, label]) => chip(k, label)));
  };
  const chip = (k, label) => {
    const n = counted ? (k === 'all' && st.auditReads ? total(counted) : kindTotal(counted, typesOf(k))) : null;
    return h('button', {class: 'chip', type: 'button', 'data-f': k, 'aria-pressed': String(k === st.kind), onclick: () => {
      st.kind = k;
      for (const c of chipBox.children) c.setAttribute('aria-pressed', String(c.dataset.f === k));
      restart();
    }}, label, n !== null ? [' ', h('span', {class: 'n'}, fmt(n))] : null);
  };
  drawChips();
  summary.then(sum => {
    counted = sum['by-type'] || {};
    drawChips();
  }, () => {});
  const query = token => {
    const types = st.kind === 'all' && st.auditReads ? null : typesOf(st.kind);
    return {
      type: types ? types.map(t => PREFIX + t).join(',') : null,
      principal: st.who || null,
      path: st.path ? st.path.split('.').filter(Boolean).join(SEP) : null,
      to: st.upTo ? new Date(Date.now() - Number(st.upTo) * 3600_000).toISOString() : null,
      pageToken: token || null,
    };
  };
  const page = (token, my) => api.auditFeed(catalog, query(token)).then(answer => {
    if (my !== gen) return;
    const records = answer.records || [];
    feed.append(...records.map(r => feedRow(catalog, r)));
    shown += records.length;
    const committed = (answer.table || {})['committed-at'];
    // "As of" the last commit, not "up to" it: a writer catching up commits later than the newest record it holds.
    status.textContent = (committed ? `${plural(shown, 'record')} shown, newest first, as of the last commit at ${at(committed)}.`
      : `${plural(shown, 'record')} shown: nothing has been committed to the audit tables.`)
      + ' Records arrive here about a minute after they happen.'
      + (answer['searched-to'] ? ` Searched back to ${at(answer['searched-to'])}.` : '');
    if (!shown && !answer['next-page-token']) feed.replaceChildren(h('p', {class: 'muted feed-none'}, 'No record matches. Try another kind, anyone, or an earlier time.'));
    more.replaceChildren();
    if (answer['next-page-token']) {
      more.append(h('button', {class: 'linkish', type: 'button', onclick: e => {
        e.currentTarget.disabled = true;
        page(answer['next-page-token'], gen).catch(error => more.replaceChildren(failure(error, {compact: true})));
      }}, answer['searched-to'] && records.length < 50 ? 'Search further back' : 'Show the next 50'));
    } else if (shown) {
      more.append('Every matching record the audit tables hold is shown. Older ones are in your SIEM and the archive.');
    }
  });
  let refusedOnce = false;
  function restart() {
    keepFilters(st);
    const my = ++gen;
    shown = 0;
    feed.replaceChildren(h('div', {'data-loading': ''}, h('span', {class: 'sk w60'})));
    more.replaceChildren();
    status.textContent = 'Reading the audit tables…';
    page(null, my).then(() => {
      const loading = feed.querySelector(':scope > [data-loading]');
      if (loading) loading.remove();
    }, error => {
      if (my !== gen) return;
      status.textContent = '';
      feed.replaceChildren(failure(error));
      if (!refusedOnce && (error.status === 403 || error.status === 503)) {
        refusedOnce = true;
        own.append(h('h2', {class: 'section-h'}, `${catalog} itself`),
          auditTab({catalog, kind: 'catalog', namespace: []}, {title: `Records about ${catalog}`}));
      }
    });
  }
  let typing = 0;
  const later = () => {
    clearTimeout(typing);
    typing = setTimeout(() => {
      st.who = who.value.trim();
      st.path = path.value.trim();
      restart();
    }, 350);
  };
  who.addEventListener('input', later);
  path.addEventListener('input', later);
  upTo.addEventListener('change', () => {
    st.upTo = upTo.value;
    restart();
  });
  reads.addEventListener('change', () => {
    st.auditReads = reads.checked;
    drawChips();
    restart();
  });
  restart();
  const el = h('div', {class: 'feed-box'},
    h('div', {class: 'feed-filters'},
      h('label', {class: 'ff'}, h('span', null, 'Table or namespace'), h('span', {class: 'field mono'}, icon('search'), path)),
      h('label', {class: 'ff'}, h('span', null, 'Who'), h('span', {class: 'field'}, who)),
      h('label', {class: 'ff'}, h('span', null, 'Up to'), h('span', {class: 'field select'}, upTo))),
    chipBox, h('label', {class: 'fine feed-reads'}, reads, ' Show audit reads', hidden), status, feed, more);
  function chooseKind(k) {
    st.kind = k;
    drawChips();
    restart();
    el.scrollIntoView({block: 'start'});
  }
  return {el, kinds};
}

function feedRow(catalog, r) {
  const type = short(r.type);
  const event = r.event || {};
  const o = r.object;
  const btn = h('button', {class: 'btn sm ghost', type: 'button', 'aria-expanded': 'false',
    'aria-label': `Record of ${at(r.time, true)}, ${r.principal || 'unknown'}, ${type}`}, 'Show record');
  const row = h('div', {class: 'feed-row' + (kindOf(r.type) === 'refusal' ? ' is-refused' : ''), 'data-type': type,
    'data-kind': kindOf(r.type), 'data-who': r.principal || '', 'data-event': r['event-id']},
  h('span', {class: 't'}, at(r.time, true)),
  h('span', {class: 'w'}, h('b', null, r.principal || '–'), r['end-user'] ? [' for ', h('b', null, r['end-user'])] : null,
    h('br'), h('span', {class: 'via'}, r.engine ? 'via ' + r.engine : '')),
  h('div', null, auditBadge(r.type, event.data), ' ', chainPill(r),
    h('p', {class: 'what'}, describe(type, event.data || {}, catalog) || r.operation || '',
      o && o.path && o.path.length ? [' · ', objectLink(catalog, o.kind, o.path, null)] : null)), btn);
  let detail = null;
  btn.addEventListener('click', () => {
    const open = btn.getAttribute('aria-expanded') === 'true';
    btn.setAttribute('aria-expanded', String(!open));
    btn.textContent = open ? 'Show record' : 'Hide record';
    if (open) {
      detail.remove();
    } else {
      detail = detail || recordDetail(catalog, r);
      row.append(detail);
    }
  });
  return row;
}

/**
 * Whether the archive check has verified the row's server run up to it; nothing when the row cannot be placed. A row
 * after a record the check reported missing stays unverified until that record arrives, which may be never, so it says
 * so rather than "yet".
 */
function chainPill(r) {
  const chain = r.chain;
  if (!chain || chain.status === 'no-archive' || chain.status === 'no-place') return null;
  if (chain.status === 'verified') return pill('ok', icon('check'), 'Chain verified');
  const gap = gapOf(r);
  if (gap === 'own') return pill('bad', icon('x'), 'Missing from archive');
  if (gap === 'below') return pill('bad', icon('x'), 'Earlier record missing');
  return pill('', icon('clock'), 'Not verified yet');
}

/** Where the gap the check reported in the row's run lies: at its own number, below it, or nowhere. */
function gapOf(r) {
  const m = r.chain && r.chain.missing;
  if (!m) return null;
  const n = Number(r['log-seq']);
  return Number(m.from) <= n && n <= Number(m.to) ? 'own' : 'below';
}

/** An object's kind as a label: "Table", "Namespace". */
const kindLabel = kind => {
  const k = String(kind || '').toLowerCase();
  return k ? k[0].toUpperCase() + k.slice(1) : 'Object';
};

function recordDetail(catalog, r) {
  const rid = r['request-id'];
  const fromKafka = Number(r.partition) >= 0;
  // A row that says which server run logged it can be found in that run's chain (backlog 272).
  const placed = r.chain && (r.chain.status === 'verified' || r.chain.status === 'unverified');
  const link = placed ? h('dd', {class: 'chain-link'}, h('button', {class: 'linkish', type: 'button',
    onclick: () => load(link, () => chainLink(r))}, 'Check this record')) : null;
  const o = r.object;
  return h('div', {class: 'rec'},
    h('dl', {class: 'facts rec-facts'},
      h('dt', null, 'Request'), h('dd', null, rid ? [code(rid), ' ', copyButton(rid)] : muted('none recorded')),
      // A record the audit table writer took from the log has no place in Kafka, and no link in the archive (backlog 205).
      h('dt', null, 'Read from'), h('dd', {'data-read-from': fromKafka ? 'kafka' : 'log'}, fromKafka
        ? [`Kafka, partition ${r.partition}, offset `, code(String(r.offset))] : ['the log ', code('nuna.audit'),
          r['log-run'] ? [`, record ${fmt(r['log-seq'])} of server run `, code(r['log-run']), ' ',
            copyButton(r['log-run'])] : null]),
      h('dt', null, 'Hash chain'), h('dd', null, chainLine(r, fromKafka)),
      link ? [h('dt', null, 'Archive'), link] : null,
      o && o.path && o.path.length ? [h('dt', null, kindLabel(o.kind)),
        h('dd', null, objectLink(catalog, o.kind, o.path, null))] : null,
      r.reason ? [h('dt', null, 'Reason'), h('dd', null, r.reason)] : null),
    r.event ? jsonPre(r.event) : h('p', {class: 'muted'}, 'This server does not know this record\'s type; its columns are above.'));
}

/** What the archive check says of the row's place in its server run's chain. */
function chainLine(r, fromKafka) {
  const status = r.chain && r.chain.status;
  const checked = r.chain && r.chain['checked-at'];
  if (status === 'no-archive') return 'no archive is configured';
  if (status === 'verified') {
    return `verified by the check at ${at(checked)}, with every earlier record of its server run`;
  }
  if (status === 'unverified') {
    const gap = gapOf(r);
    const m = r.chain.missing;
    if (gap === 'own') return 'the archive is missing this record; see Hash chain';
    if (gap === 'below') {
      return m.from === m.to
        ? `not verified: record ${fmt(m.from)} of its server run is missing from the archive; see Hash chain`
        : `not verified: records ${fmt(m.from)} to ${fmt(m.to)} of its server run are missing from the archive; see Hash chain`;
    }
    return checked ? `not verified yet: the check at ${at(checked)} had not verified its server run up to it`
      : 'not verified yet: no check has reached its server run';
  }
  // Each server run's records are chained apart (backlog 262): a row must say which run logged it to be placed.
  return fromKafka ? 'cannot be checked from this page: the Kafka copy does not say which server run logged it'
    : 'cannot be checked from this page: an older server logged it without numbering it';
}

/** A record's place in its server run's hash chain, read from the archive's segment that holds it. */
async function chainLink(r) {
  const c = await api.auditChain(r['log-run'], r['log-seq'], r['event-id']);
  if (!c.archived) return h('span', null, pill('', icon('clock'), 'Not archived yet'), ' the archive has not received it');
  // The place can differ from the number: the chain holds records as they arrived, repeats and all.
  const moved = Number(c.position) !== Number(c['log-seq']);
  return h('span', null,
    c['link-holds'] ? pill('ok', icon('check'), 'Hash matches') : pill('bad', icon('x'), 'Hash does not match'),
    ` place ${fmt(c.position)} in its server run's chain`,
    c.verified ? `; the check at ${at(c['checked-at'])} verified the chain through it` : '; the check has not reached it yet',
    moved ? [h('br'), h('span', {class: 'fine'},
      'its place differs from its number in the run because records before it were repeated or lost')] : null,
    h('br'), h('span', {class: 'fine'}, 'previous ', code(String(c['previous-hash'] || '').slice(0, 16) + '…'),
      ' · this ', code(String(c.hash || '').slice(0, 16) + '…'),
      c['next-hash'] ? [' · next ', code(String(c['next-hash']).slice(0, 16) + '…')] : ' · the newest in its chain'),
    h('br'), h('span', {class: 'fine'}, 'segment ', code(c.segment ? c.segment.key : '')));
}


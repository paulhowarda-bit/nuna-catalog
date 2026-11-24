// Audit in one catalog (item 125): every record in the catalog, newest first, from the audit tables, filtered by
// kind, person, path and time, each record with its request id, its place in the topic and in its hash chain; the
// last day's counts by kind and the busiest objects; and the archive check. These routes are for audit readers
// only (D23): anyone else is shown the refusal as the catalog answered it, with the catalog's own records below,
// read from the audit topic as before, for those who manage its grants.

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

/** When the archive check last ended, and whether every chain was whole. */
function archiveKpi(a) {
  const check = a.check;
  return h('div', {class: 'kpi', 'data-kpi': 'archive'}, h('p', {class: 'kpi-l'}, icon('check'), 'Archive checked'),
    h('p', {class: 'kpi-v'}, !a.configured ? '–' : check ? at(check['ended-at']) : 'not yet'),
    h('p', {class: 'kpi-d'}, !a.configured ? 'no archive is configured' : !check ? 'the first check runs a minute after start'
      : check.intact ? `${plural((check.chains || []).length, 'chain')} whole` : h('b', null, 'a chain is broken: see Hash chain')));
}

/** The archive and its hash chains: where it is, how long it is kept, how far each chain is verified. */
function chainPanel(a) {
  if (!a.configured) {
    return panel('Hash chain', null, h('p', {class: 'muted'}, 'No archive is configured on this server, so no chain is checked.'));
  }
  const check = a.check;
  const broken = check ? (check.chains || []).filter(c => c.broken || c.failed) : [];
  return h('div', {class: 'panel', id: 'hash-chain'}, h('h2', null, 'Hash chain ', h('span', {class: 'meta'}, 'checked hourly')),
    h('p', null, !check ? pill('', icon('clock'), 'Not checked yet') : check.intact
      ? pill('ok', icon('check'), 'Whole at ' + at(check['ended-at'])) : pill('bad', icon('x'), 'Broken')),
    facts([
      ['Chains', h('span', {title: 'One per partition of the audit topic'}, `${fmt((a.verified || []).length)} verified`)],
      ['Each record', 'hashes the one before it in its partition, with its sequence'],
      ['Archive', [code(`s3://${a.bucket}/${a.prefix || ''}`), ` · kept ${a.retention}`]],
      ['Last check', check ? `${at(check['started-at'])} to ${at(check['ended-at'])}` : null],
    ]),
    broken.length ? tbl('stack-line', ['Chain', 'What was found'], broken.map(c => h('tr', {'data-chain': c.chain},
      h('td', {class: 'name'}, c.chain), h('td', {class: 'wrap'}, c.broken || c.failed)))) : null);
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
  return h('a', {href: objectHref(target, tab)}, levels.join('.'));
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
    status.textContent = `${plural(shown, 'record')} shown, newest first, up to `
      + `${committed ? at(committed) : 'the last commit'}. Records arrive here about a minute after they happen.`
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
    chipBox, h('label', {class: 'fine feed-reads'}, reads, ' Show audit reads'), status, feed, more);
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
  h('div', null, auditBadge(r.type, event.data), ' ', chainPill(r.chain),
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

const chainPill = chain => !chain || chain.status === 'no-archive' ? null : chain.status === 'verified'
  ? pill('ok', icon('check'), 'Chain verified') : pill('', icon('clock'), 'Not checked yet');

/** An object's kind as a label: "Table", "Namespace". */
const kindLabel = kind => {
  const k = String(kind || '').toLowerCase();
  return k ? k[0].toUpperCase() + k.slice(1) : 'Object';
};

function recordDetail(catalog, r) {
  const rid = r['request-id'];
  // Without an archive there is no link to check, so none is offered (Atelier's re-walk, backlog 216).
  const archived = !(r.chain && r.chain.status === 'no-archive');
  const link = archived ? h('dd', {class: 'chain-link'}, h('button', {class: 'linkish', type: 'button', onclick: () => load(link, () => chainLink(r))},
    'Check its link')) : null;
  const o = r.object;
  return h('div', {class: 'rec'},
    h('dl', {class: 'facts rec-facts'},
      h('dt', null, 'Request'), h('dd', null, rid ? [code(rid), ' ', copyButton(rid)] : muted('none recorded')),
      h('dt', null, 'Kafka'), h('dd', null, `partition ${r.partition}, offset `, code(String(r.offset))),
      h('dt', null, 'Hash chain'), h('dd', null, r.chain && r.chain.status === 'verified'
        ? ['verified past this record', r.chain['checked-at'] ? ` by the check at ${at(r.chain['checked-at'])}` : '']
        : r.chain && r.chain.status === 'no-archive' ? 'no archive is configured'
          : 'not verified yet: the next hourly check will reach it'),
      link ? [h('dt', null, 'Archive'), link] : null,
      o && o.path && o.path.length ? [h('dt', null, kindLabel(o.kind)),
        h('dd', null, objectLink(catalog, o.kind, o.path, null))] : null,
      r.reason ? [h('dt', null, 'Reason'), h('dd', null, r.reason)] : null),
    r.event ? jsonPre(r.event) : h('p', {class: 'muted'}, 'This server does not know this record\'s type; its columns are above.'));
}

/** A record's place in its hash chain, read from the archive's segment that holds it. */
async function chainLink(r) {
  const c = await api.auditChain(r.partition, r.offset, r['event-id']);
  if (!c.archived) return h('span', null, pill('', icon('clock'), 'Not archived yet'), ' the archive has not received it');
  return h('span', null, c['link-holds'] ? pill('ok', icon('check'), 'Link holds') : pill('bad', icon('x'), 'Link broken'),
    ` sequence ${fmt(c.sequence)} of ${c.chain}`, h('br'),
    h('span', {class: 'fine'}, 'previous ', code(String(c['previous-hash'] || '').slice(0, 16) + '…'),
      ' · this ', code(String(c.hash || '').slice(0, 16) + '…'),
      c['next-hash'] ? [' · next ', code(String(c['next-hash']).slice(0, 16) + '…')] : null),
    h('br'), h('span', {class: 'fine'}, 'segment ', code(c.segment ? c.segment.key : '')));
}

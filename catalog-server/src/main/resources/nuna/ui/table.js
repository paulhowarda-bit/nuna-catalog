// A table's and a view's pages, each tab on its own route. The table's metadata comes from loadTable (with every
// snapshot, and no storage credentials), asked once a minute at most: every load is audited. Its metadata log is the
// catalog's commit journal, with who made each change (item 130), on its History tab. "Compact now", for a caller who
// may modify the table, and a request while it waits (item 114). Its Settings drawer changes the listed write settings
// (item 141). A view's Access tab is the table's, in words for a view and without the labels that do nothing to one
// (item 146).
// Read through a catalog branch or tag (item 109), a table loads through catalog@ref, its metadata log is the ref's,
// the tabs that are main's say so, and Compact now is not offered; a table's own refs are its "Iceberg refs".

import * as api from './api.js';
import {$, $$, at, badge, code, copyButton, copyText, edgeCue, facts, failure, fill, fmt, frozenPill, h, heldPill, icon, jsonPre,
  load, muted, panel, pill, plural, s, sizeWords, tabsNav, tbl, when} from './dom.js';
import {objectHref, warehouseOf} from './route.js';
import {keyOfRow, maintenanceOff, queue, queueOf, REASONS} from './queue.js';
import {policyKey, sourceWords, tableHealthTab} from './health.js';
import {accessTab, actionWords, auditTab, exportDialog, flaggedHere, governanceSummary, TRINO_WORDS} from './panels.js';
import {copyLinkButton, crumbsFor} from './browse.js';
import {askDialog, mayModify, requestPills} from './requests.js';
import {settingsTab, TABLE_SETTINGS} from './settings.js';
import {readingRef, readOnlyNote, refChrome, refNote} from './refs.js';
import {closedNote, openingTab, trailOpen, trailWords} from './access.js';

const pathOf = target => [...target.namespace, target.name].join('.');

// ---- loads, kept a minute ----

const loads = new Map();

const loadKey = target => warehouseOf(target) + '\u001f' + target.kind + '\u001f' + pathOf(target);

/** Forgets a load, so the next one reads what a change just committed. */
const forget = target => loads.delete(loadKey(target));

function loadOnce(target, loader) {
  const key = loadKey(target);
  const hit = loads.get(key);
  if (hit && Date.now() - hit.at < 60_000) return hit.promise;
  const promise = loader();
  loads.set(key, {at: Date.now(), promise});
  promise.catch(() => loads.delete(key));
  return promise;
}

const loadTable = target => loadOnce(target, () => api.table(warehouseOf(target), target.namespace, target.name));
const loadView = target => loadOnce(target, () => api.view(warehouseOf(target), target.namespace, target.name));

/** What a load that failed leaves: the catalog's answer, and that the other tabs are checked on their own. */
/** The catalog's answer for a ref that is not live (item 109): the band shows it, so no tab draws it again. */
const noLiveRef = error => error.status === 404 && error.type === 'NoSuchWarehouseException';

function refusedLoad(error, others, ref) {
  if (ref && noLiveRef(error)) return null;
  return [failure(error, {big: true}), error.status === 403 ? h('div', {class: 'notice'}, icon('info'),
    h('span', null, h('b', null, `You may still open ${others}:`), ' each tab is checked on its own.'))
    : null];
}

/**
 * A table flagged for row and column rules refuses the console's load, which is storage access, to everyone (D20): its
 * Overview, Schema and History say why instead of the refusal (Atelier, 2026-11-24; backlog 241).
 */
async function flaggedLoad(error, target) {
  if (error.status !== 403 || !(await api.governance(target).then(flaggedHere, () => false))) return null;
  return h('div', {class: 'notice', id: 'read-through-trino'}, icon('info'), h('span', null, TRINO_WORDS));
}

/**
 * A tab of main's that a page read through a ref still shows (item 109): with the note `what` on what is main's, or
 * nothing when the ref is not live, as `loaded` (the object's load through the ref) answers.
 */
async function throughRef(target, loaded, what, draw) {
  if (!target.ref) return draw();
  try {
    await loaded();
  } catch (error) {
    if (noLiveRef(error)) return null;
  }
  return [what ? refNote(await readingRef(target.catalog, target.ref) || target.ref, what) : null, await draw()];
}

function tabBody(main) {
  const body = h('div', {class: 'tab-body'});
  main.append(body);
  return body;
}

function addCount(tabs, key, n) {
  const a = $(`a[data-tab="${key}"]`, tabs);
  if (a && !$('.c', a)) a.append(' ', h('span', {class: 'c'}, fmt(n)));
}

// ---- the table page ----

// Six tabs, from ten (Atelier's review, S2; backlog 199): Schema holds partitioning and sort order, History the
// snapshots and the metadata log, one row per commit, Access the governance and grants. The properties are on Overview
// and change in the Settings drawer (tab=settings), which opens over it.
const TABLE_TABS = [['', 'Overview'], ['schema', 'Schema'], ['history', 'History'], ['access', 'Access'], ['maintenance', 'Maintenance'],
  ['audit', 'Audit']];

/**
 * The tab a link to a table or view opens: the one it names, tab=overview for Overview, else the one the caller opens
 * on (openingTab). Overview's own link names it when the caller opens elsewhere, so it is a tab away (backlog 245).
 */
const openedTab = (target, tab) => tab === 'overview' ? '' : tab ?? openingTab(target.catalog);
const tabHref = target => k => objectHref(target, k || (openingTab(target.catalog) ? 'overview' : null));

export function tablePage(target, tab, route) {
  const main = h('div', {class: 'nc-main', id: 'table-page'});
  const want = openedTab(target, tab);
  const key = want === 'settings' ? want : TABLE_TABS.some(([k]) => k === want) ? want : null;
  const title = h('div', {class: 'title-row'}, h('span', {class: 'kind'}, icon('table')), h('h1', {tabindex: '-1'}, target.name));
  const tabs = tabsNav('Table', TABLE_TABS.filter(([k]) => k !== 'audit' || trailOpen(target.catalog)), key === 'settings' ? '' : key,
    tabHref(target));
  // The export is main's metadata and journal, so a page read through a ref offers none (item 109; item 172).
  const acts = h('div', {class: 'side tbl-acts'}, copyLinkButton(),
    h('a', {class: 'btn sm', href: objectHref(target, 'settings'), 'data-act': 'settings'}, 'Settings'), target.ref ? null
      : h('button', {class: 'btn sm', type: 'button', 'data-act': 'export-meta', onclick: () => exportDialog(target)}, icon('down'), 'Export metadata'));
  const head = h('div', {class: 'ph'}, crumbsFor(target.catalog, target.namespace, target.name, target.ref), title, acts);
  main.append(head, tabs);
  const body = tabBody(main);
  refChrome(route, head);
  let metadata = loadTable(target);
  metadata.then(t => {
    const m = t.metadata;
    title.insertBefore(badge(`iceberg v${m['format-version']}`, 'strong'), title.children[2] || null);
    addCount(tabs, 'schema', (m.schemas || []).length);
  }, () => {});
  const reload = () => show();
  const afterChange = () => {
    forget(target);
    metadata = loadTable(target);
    show();
  };
  headMarks(target, title);
  // Compact now is main's: the worker compacts main only.
  if (!target.ref) mayModify(target).then(may => {
    if (may) acts.append(h('button', {class: 'btn sm', type: 'button', 'data-act': 'compact',
      onclick: () => askDialog(target, 'compaction', key === 'maintenance' ? reload : null)}, icon('play'), 'Compact now'));
  });
  const show = () => {
    if (key === null) {
      fill(body, h('div', {class: 'empty'}, h('h3', null, 'A table has no such tab')));
    } else if (key === 'maintenance') {
      load(body, () => throughRef(target, () => metadata, 'health', () => tableHealthTab(target, reload)));
    } else if (key === 'access') {
      load(body, () => throughRef(target, () => metadata, 'access', () => accessTab(target, reload)));
    } else if (key === 'audit' && !trailOpen(target.catalog)) {
      fill(body, closedNote(trailWords(target.catalog)));
    } else if (key === 'audit') {
      load(body, () => throughRef(target, () => metadata, 'audit', () => auditTab(target)));
    } else {
      load(body, async () => {
        let t;
        try {
          t = await metadata;
        } catch (error) {
          return await flaggedLoad(error, target) || refusedLoad(error, 'Access, Maintenance and Audit', target.ref);
        }
        const ref = await readingRef(target.catalog, target.ref);
        const shown = {'': overviewTab, schema: (x, y) => h('div', {class: 'stack'}, schemaTab(x, y), partitionTab(x, y)),
          history: historyTab,
          settings: (x, y) => [overviewTab(x, y), settingsDrawer(x, propertiesTab(x, y, afterChange, ref))]}[key](target, t);
        return target.ref && key === 'history' ? [refNote(ref || target.ref, 'snapshots'), shown] : shown;
      });
    }
  };
  show();
  return main;
}

/** The marks beside a table's name: tonight's queue, a hold, a DR freeze and its classification, as the catalog says them. */
function headMarks(target, title) {
  queue().then(rows => {
    const row = queueOf(rows, target.catalog).find(r => keyOfRow(r) === [target.catalog, pathOf(target)].join('.'));
    if (row) {
      const eq = (row.assessment.reasons || []).includes('equality-deletes');
      title.append(h('a', {href: objectHref(target, 'maintenance'), class: 'pill ' + (eq ? 'bad' : 'warn')}, 'Needs compaction'));
    }
  }, () => {});
  api.tableHealth(target).then(health => title.append(...requestPills(health.requests)), () => {});
  api.governance(target).then(g => {
    if ((g.holds || []).length) title.append(heldPill());
    const e = g.effective || {};
    if (e['dr-freeze']) title.append(frozenPill());
    if (e.classification) title.append(badge(String(e.classification)));
  }, () => {});
}

const snapshotsOf = m => (m.snapshots || []).slice().sort((a, b) => b['timestamp-ms'] - a['timestamp-ms']);
const currentOf = m => (m.snapshots || []).find(sn => sn['snapshot-id'] === m['current-snapshot-id']) || null;


function rowsWords(n) {
  const v = Number(n);
  if (!Number.isFinite(v)) return '–';
  if (v >= 1e9) return (v / 1e9).toFixed(2) + ' B';
  if (v >= 1e6) return (v / 1e6).toFixed(1) + ' M';
  return fmt(v);
}

function kpi(label, value, more) {
  return h('div', {class: 'kpi'}, h('p', {class: 'kpi-l'}, label), h('p', {class: 'kpi-v' + (value === '–' ? ' na' : '')}, value),
    more ? h('p', {class: 'kpi-d hide-sm'}, more) : null);
}

function propsDd(props) {
  const keys = Object.keys(props || {}).sort();
  if (!keys.length) return muted('none');
  const line = list => list.flatMap((k, i) => [i ? ' · ' : '', code(`${k}=${props[k]}`)]);
  if (keys.length <= 2) return line(keys);
  const dd = h('span', null, line(keys.slice(0, 2)), ' · ');
  dd.append(h('button', {class: 'linkish', type: 'button', 'data-act': 'props', onclick: () => fill(dd, line(keys))},
    `Show all ${keys.length} properties`));
  return dd;
}

function overviewTab(target, t) {
  const m = t.metadata;
  const sn = currentOf(m);
  const sum = (sn && sn.summary) || {};
  const schemas = m.schemas || [];
  const specs = m['partition-specs'] || [];
  const snaps = m.snapshots || [];
  const health = h('div');
  const gov = h('div');
  load(health, () => healthSummary(target));
  load(gov, async () => h('div', {class: 'panel'}, h('h2', null, h('a', {class: 'h-link', href: objectHref(target, 'access')}, 'Governance')),
    await governanceSummary(target)));
  const refs = Object.entries(m.refs || {});
  const by = h('span', {class: 'fine', id: 'current-by'});
  if (sn) {
    snapshotCommits(target, [sn['snapshot-id']]).then(found => {
      const e = found.get(String(sn['snapshot-id']));
      if (e) fill(by, h('br'), 'by ', byWords(e));
    }, () => {});
  }
  return h('div', {class: 'grid-21', id: 'overview-tab'},
    h('div', {class: 'stack'},
      h('div', {class: 'kpis'},
        kpi('Rows', rowsWords(sum['total-records']), sizeWords(sum['total-files-size'])),
        kpi('Data files', sum['total-data-files'] !== undefined ? fmt(sum['total-data-files']) : '–',
          sum['total-delete-files'] !== undefined ? `${fmt(sum['total-delete-files'])} delete files` : null),
        kpi(h('a', {href: objectHref(target, 'history')}, 'Snapshots'), fmt(snaps.length), target.ref ? `as @${target.ref} has them`
          : refs.length ? plural(refs.length, 'Iceberg ref') : 'no Iceberg refs'),
        kpi(h('a', {href: objectHref(target, 'schema')}, 'Schemas'), fmt(schemas.length),
          schemas.length > 1 ? plural(schemas.length - 1, 'change') : 'as created')),
      h('div', {class: 'panel'}, h('h2', null, 'Current state'), facts([
        ['Current snapshot', sn ? [code(String(sn['snapshot-id'])), ` · ${sum.operation || '–'} · ${when(sn['timestamp-ms'])}`,
          sum['engine-name'] ? [h('br'), h('span', {class: 'fine'}, `${sum['engine-name']} ${sum['engine-version'] || ''}`.trim())] : null, by]
          : muted('none: the table has no data yet')],
        ['Table UUID', code(m['table-uuid'] || '')],
        ['Location', code(m.location || '')],
        ['Metadata file', code(t['metadata-location'] || '')],
        ['Schema, spec, order', `schema ${m['current-schema-id']} of ${schemas.length} · partition spec ${m['default-spec-id']} of `
          + `${specs.length} · sort order ${m['default-sort-order-id']}`],
        ['Last sequence number', code(String(m['last-sequence-number'] ?? ''))],
        m['next-row-id'] !== undefined ? ['Next row id', code(String(m['next-row-id']))] : null,
        ['Last updated', when(m['last-updated-ms'])],
        ['Properties', [propsDd(m.properties), ' ', h('a', {href: objectHref(target, 'settings')}, 'Settings')]],
      ]))),
    h('div', {class: 'stack'}, health, gov,
      refs.length ? engineRefs(target, refs.map(([name, ref]) => [refBadge(name, ref.type),
        [ref['snapshot-id'] === m['current-snapshot-id'] ? 'current snapshot' : code(String(ref['snapshot-id'])),
          ref['max-ref-age-ms'] ? muted(` · kept ${Math.round(ref['max-ref-age-ms'] / 86_400_000)} days`) : null]])) : null));
}

/**
 * A table's own Iceberg refs (Atelier's review, F8; backlog 191): named apart from the catalog's branches and tags, and
 * folded while one of those is read, so a page read through @sample does not lead with this table's main.
 */
function engineRefs(target, rows) {
  const title = 'Engine-level refs in this table\'s metadata';
  const body = [h('p', {class: 'fine'}, 'Iceberg\'s own branches and tags, set by engines inside this table, not the catalog\'s.'),
    facts(rows)];
  if (!target.ref) return h('div', {class: 'panel', id: 'engine-refs'}, h('h2', null, title), body);
  return h('details', {class: 'panel fold', id: 'engine-refs'}, h('summary', null, h('h2', null, title, ' ',
    h('span', {class: 'meta'}, 'Iceberg\'s own, not the catalog\'s'))), body);
}

/** The table's settings and every property it has (item 141); through a branch a change commits on it, through a tag none is offered. */
function propertiesTab(target, t, reload, ref) {
  const m = t.metadata;
  const props = m.properties || {};
  if (target.ref && ref && ref.kind === 'tag') {
    return [refNote(ref, 'props'), readOnlyNote(ref, target.catalog, 'updateTable'),
      panel('Properties', 'in the metadata file the tag keeps', Object.keys(props).length
        ? facts(Object.entries(props).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => [code(k), String(v)])) : muted('No property is set.'))];
  }
  const on = target.ref ? ` on @${(ref && ref.name) || target.ref}` : '';
  const tab = settingsTab({target, settings: TABLE_SETTINGS, props, reload, needs: `A change here is a commit to the table${on}: the same rules apply as to an engine's commit, and it is audited.`,
    ctx: {formatVersion: m['format-version'], codec: props['write.parquet.compression-codec'] || 'zstd'},
    write: (updates, removals) => api.tableProperties(warehouseOf(target), target.namespace, target.name, m['table-uuid'],
      updates, removals)});
  return target.ref ? [refNote(ref || target.ref, 'props'), tab] : tab;
}

/**
 * The table's settings in a drawer over its Overview (Atelier, 2026-11-23: properties change behind Settings; backlog
 * 199). It has its own link, tab=settings, which old Properties links open; closing it goes back to the Overview.
 */
function settingsDrawer(target, content) {
  const back = objectHref(target, null);
  const title = h('h2', {id: 'settings-drawer-h', tabindex: '-1'}, `Settings of ${target.name}`);
  const drawer = h('aside', {class: 'drawer', id: 'settings-drawer', 'aria-labelledby': 'settings-drawer-h'},
    h('div', {class: 'drawer-h'}, title, h('a', {class: 'btn sm ghost', href: back, 'data-act': 'close-settings', 'aria-label': 'Close settings'},
      icon('x'))), content);
  drawer.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !document.querySelector('dialog[open]')) location.hash = back;
  });
  setTimeout(() => title.focus(), 0);
  return [h('a', {class: 'drawer-scrim', href: back, tabindex: '-1', 'aria-hidden': 'true'}), drawer];
}

/** A table's own Iceberg ref, set by an engine: one icon for them all, apart from the catalog's branches and tags (backlog 191). */
const refBadge = (name, type) => h('span', {title: `Iceberg ${type === 'tag' ? 'tag' : 'branch'} in this table's metadata`},
  badge([icon('iref'), name], type === 'tag' ? 'ref-t' : 'ref-b'));

async function healthSummary(target) {
  const [health, rows, policy] = await Promise.all([api.tableHealth(target), queue().catch(() => []),
    api.policy(target).catch(() => null)]);
  const off = maintenanceOff(target.catalog, policy, objectHref(target, 'maintenance'));
  const a = health.assessment;
  const c = health['last-compaction'];
  const inCatalog = queueOf(rows, target.catalog);
  const i = inCatalog.findIndex(r => keyOfRow(r) === [target.catalog, pathOf(target)].join('.'));
  const words = [];
  if (a && a['needs-compaction']) words.push((a.reasons || []).map(r => (REASONS[r] || [null, null, r])[2]).join(' '));
  words.push(c ? `Last compaction ${at(c['ended-at'] || c['started-at'])}, ${c.outcome}.` : 'No compaction has run for this table.');
  return h('div', {class: 'panel'}, h('h2', null, h('a', {class: 'h-link', href: objectHref(target, 'maintenance')}, 'Maintenance'), ' ',
    target.ref ? h('span', {class: 'meta'}, 'main\'s') : a ? h('span', {class: 'meta'}, 'assessed ' + at(a['assessed-at'])) : null),
  target.ref ? h('p', {class: 'fine'}, `@${target.ref}'s tables are never compacted or expired; every file it reaches is kept.`) : null,
  off ? h('p', {class: 'fine', id: 'summary-maintenance-off'}, off) : null,
  h('p', null, !a ? pill('', 'Not assessed yet') : a['needs-compaction']
    ? pill((a.reasons || []).includes('equality-deletes') ? 'bad' : 'warn', `Needs compaction · score ${a.score}`)
    : pill('ok', icon('check'), 'No compaction needed'),
  i >= 0 ? [' ', badge(`${ordinalWord(i + 1)} tonight`)] : null),
  h('p', {class: 'muted fine'}, words.join(' ')));
}

const ordinalWord = n => n + (n % 10 === 1 && n % 100 !== 11 ? 'st' : n % 10 === 2 && n % 100 !== 12 ? 'nd' : n % 10 === 3 && n % 100 !== 13 ? 'rd' : 'th');

// ---- schema ----

function typeWords(t) {
  if (typeof t === 'string') return t;
  if (!t) return '';
  if (t.type === 'struct') return 'struct';
  if (t.type === 'list') return `list<${typeWords(t.element)}>`;
  if (t.type === 'map') return `map<${typeWords(t.key)}, ${typeWords(t.value)}>`;
  return String(t.type);
}

function nestedFields(t) {
  if (!t || typeof t === 'string') return null;
  if (t.type === 'struct') return t.fields;
  if (t.type === 'list') return nestedFields(t.element);
  if (t.type === 'map') return nestedFields(t.value);
  return null;
}

/** A schema's fields, nested ones after their parent, each with its depth. */
export function flatten(fields, depth = 0, out = []) {
  for (const f of fields || []) {
    out.push({id: f.id, name: f.name, type: typeWords(f.type), required: !!f.required, doc: f.doc, depth});
    const nested = nestedFields(f.type);
    if (nested) flatten(nested, depth + 1, out);
  }
  return out;
}

function schemaTab(target, t) {
  const m = t.metadata;
  const all = (m.schemas || []).slice().sort((a, b) => a['schema-id'] - b['schema-id']);
  const curId = m['current-schema-id'];
  const shown = all.slice(-6);
  const fieldsOf = id => flatten((all.find(x => x['schema-id'] === id) || {}).fields);
  const curAt = all.findIndex(x => x['schema-id'] === curId);
  let sel = all.length > 1 ? [all[Math.max(0, curAt - 1)]['schema-id'], curId] : [curId, curId];
  let last = curId;
  const tl = h('div', {class: 'schema-tl n' + Math.max(2, shown.length)});
  const diff = h('div', {class: 'panel', id: 'schema-diff'});
  const draw = () => {
    fill(tl, shown.map((sc, i) => {
      const id = sc['schema-id'];
      const flat = flatten(sc.fields);
      const top = (sc.fields || []).length;
      const prev = i ? flatten(shown[i - 1].fields) : null;
      const node = h('button', {class: 'node', type: 'button', 'aria-pressed': String(sel.includes(id)),
        'aria-label': `Schema ${id}, ${plural(top, 'column')}${id === curId ? ', current' : ''}`}, String(id));
      node.addEventListener('click', () => {
        if (id !== last) {
          sel = [last, id].sort((a, b) => a - b);
          last = id;
          draw();
        }
      });
      let body;
      if (!prev) body = h('p', null, (sc.fields || []).map(f => f.name).join(', '));
      else {
        const before = new Map(prev.map(f => [f.id, f]));
        const after = new Set(flat.map(f => f.id));
        body = h('p', {class: 'chg'}, flat.filter(f => !before.has(f.id)).map(f => badge('+ ' + f.name)),
          prev.filter(f => !after.has(f.id)).map(f => badge('− ' + f.name)),
          flat.filter(f => before.has(f.id) && before.get(f.id).name !== f.name).map(f => badge(`${before.get(f.id).name} → ${f.name}`)),
          flat.filter(f => before.has(f.id) && before.get(f.id).type !== f.type).map(f => badge(`${f.name}: ${f.type}`)));
      }
      return h('div', {class: 'stl' + (id === curId ? ' cur' : '') + (sel.includes(id) ? ' sel' : '')}, node,
        h('h4', null, plural(top, 'column'), flat.length > top ? `, ${fmt(flat.length - top)} nested` : '',
          id === curId ? [' ', pill('accent', 'Current')] : null), body);
    }));
    const [a, b] = sel;
    const before = a === b ? null : new Map(fieldsOf(a).map(f => [f.id, f]));
    const rows = fieldsOf(b);
    const after = new Set(rows.map(f => f.id));
    const removed = before ? [...before.values()].filter(f => !after.has(f.id)) : [];
    const added = before ? rows.filter(f => !before.has(f.id)).length : 0;
    const renamed = before ? rows.filter(f => before.has(f.id) && before.get(f.id).name !== f.name).length : 0;
    const retyped = before ? rows.filter(f => before.has(f.id) && before.get(f.id).type !== f.type).length : 0;
    fill(diff, h('h2', null, a === b ? `Schema ${b} ` : `Schema ${a} → schema ${b} `, h('span', {class: 'meta'}, a === b
      ? `${plural(rows.length, 'field')}, as created`
      : [h('span', {class: 'diff-add'}, `${plural(added, 'field')} added`), ` · ${removed.length} removed · ${renamed} renamed · ${retyped} retyped`])),
    tbl('schema', [['Id', 'num'], 'Field', 'Type', 'Required', 'Doc'], [
      ...rows.map(f => {
        const old = before && before.get(f.id);
        const isNew = before && !old;
        const change = isNew ? 'added' : old && old.name !== f.name ? `was ${old.name}` : old && old.type !== f.type ? `was ${old.type}` : null;
        return h('tr', {class: isNew ? 'added' : change ? 'changed' : null, 'data-field': f.id},
          h('td', {class: 'num'}, String(f.id)),
          h('td', {class: 'name field-name' + (f.depth ? ' indent-' + Math.min(f.depth, 3) : '')}, f.name, change ? [' ', h('span', {class: 'diff-add'}, change)] : null),
          h('td', {class: 'name'}, f.type), h('td', null, f.required ? 'Required' : 'Optional'), h('td', {class: f.doc ? null : 'muted'}, f.doc || '–'));
      }),
      ...removed.map(f => h('tr', {class: 'removed', 'data-field': f.id}, h('td', {class: 'num'}, String(f.id)),
        h('td', {class: 'name field-name' + (f.depth ? ' indent-' + Math.min(f.depth, 3) : '')}, f.name, ' ', h('span', {class: 'diff-del'}, 'removed')),
        h('td', {class: 'name'}, f.type), h('td', null, f.required ? 'Required' : 'Optional'), h('td', {class: f.doc ? null : 'muted'}, f.doc || '–'))),
    ]));
  };
  draw();
  return [panel('Schemas', all.length > 1 ? [`current: ${curId} · pick two to compare`, all.length > shown.length
    ? ` · the newest ${shown.length} of ${all.length}` : ''] : 'one, as created', tl), diff,
  h('div', {class: 'notice'}, icon('info'), h('span', null, h('b', null, 'Iceberg keeps no date for a schema.'),
    ' The commit that added one is on ', h('a', {href: objectHref(target, 'history')}, 'History'), ', with who made it.'))];
}

// ---- snapshots and refs ----

function refsBySnapshot(m) {
  const by = new Map();
  for (const [name, ref] of Object.entries(m.refs || {})) {
    if (!by.has(ref['snapshot-id'])) by.set(ref['snapshot-id'], []);
    by.get(ref['snapshot-id']).push([name, ref.type]);
  }
  return by;
}

const opOf = sn => (sn.summary || {}).operation || '–';
const hm = ms => {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};
const dayOf = ms => new Date(ms).toLocaleDateString('en-GB', {day: 'numeric', month: 'short'});

/** The newest eight snapshots on main's line, the older ones folded into a gap, with tags and branches marked. */
function lanes(target, list, m, onPick) {
  const W = 1000;
  const show = list.slice(0, 8).reverse();
  const older = list.length - show.length;
  const refs = refsBySnapshot(m);
  const svg = s('svg', {viewBox: `0 0 ${W} 150`, role: 'img', 'aria-label': `Snapshot history of ${target.name}: `
    + `${older ? `${fmt(older)} older snapshots, then ` : ''}the newest ${show.length}`});
  const x0 = older ? 430 : 60;
  const step = show.length > 1 ? (930 - x0) / (show.length - 1) : 0;
  svg.append(s('text', {class: 'tb', x: 16, y: 70}, 'main'));
  if (older) {
    const oldest = list[list.length - 1];
    svg.append(s('line', {class: 'main', x1: 60, y1: 66, x2: 90, y2: 66}), s('line', {class: 'gap', x1: 96, y1: 66, x2: 424, y2: 66}),
      s('text', {class: 'gap-t', x: 262, y: 56, 'text-anchor': 'middle'}, `${fmt(older)} older, from ${dayOf(oldest['timestamp-ms'])}`),
      s('circle', {class: 'snap', cx: 60, cy: 66, r: 6}),
      s('text', {class: 't', x: 60, y: 92, 'text-anchor': 'middle'}, dayOf(oldest['timestamp-ms'])));
  }
  svg.append(s('line', {class: 'main', x1: x0, y1: 66, x2: 930, y2: 66}));
  let tags = 0;
  let branch = null;
  show.forEach((sn, i) => {
    const x = x0 + i * step;
    const id = sn['snapshot-id'];
    const cur = id === m['current-snapshot-id'];
    const op = opOf(sn);
    const rep = op === 'replace';
    for (const [name, type] of refs.get(id) || []) {
      if (type === 'tag' && tags < 2) {
        tags++;
        const w = 14 + name.length * 8;
        svg.append(s('line', {class: 'pole', x1: x, y1: 18, x2: x, y2: 60}),
          s('path', {class: 'flag', d: `M${x} 18 H${x + w} L${x + w - 10} 28 L${x + w} 38 H${x} Z`}),
          s('text', {class: 'flag-t', x: x + 7, y: 32}, name));
      } else if (type === 'branch' && name !== 'main' && !branch) {
        branch = [name, x];
      }
    }
    const c = s('circle', {class: 'snap' + (cur ? ' cur' : '') + (op === 'delete' || op === 'overwrite' ? ' del' : '') + (rep ? ' rep' : ''),
      cx: x, cy: 66, r: cur ? 8 : 6, 'data-id': String(id)});
    c.append(s('title', null, `${id} · ${when(sn['timestamp-ms'])} · ${op}`));
    c.addEventListener('click', () => onPick(sn));
    svg.append(c, s('text', {class: cur || rep ? 'tb' : 't', x, y: cur || rep ? 44 : 92, 'text-anchor': 'middle'},
      rep ? `${hm(sn['timestamp-ms'])} compaction` : hm(sn['timestamp-ms'])));
  });
  if (branch) {
    const [name, x] = branch;
    svg.append(s('path', {class: 'br', d: `M${x} 66 C ${x + 20} 66, ${x + 20} 118, ${x + 42} 118 H 990`}),
      s('text', {class: 'br-t', x: 990, y: 140, 'text-anchor': 'end'}, name));
  }
  const box = h('div', {class: 'lanes'}, svg);
  requestAnimationFrame(() => {
    box.scrollLeft = box.scrollWidth;
    edgeCue(box);
  });
  return box;
}

const SHORT = ['operation', 'added-data-files', 'deleted-data-files', 'added-records', 'deleted-records', 'added-delete-files',
  'added-position-deletes', 'added-equality-deletes', 'total-records', 'total-data-files', 'total-delete-files', 'engine-name', 'engine-version'];

// ---- history: one row per commit, with the snapshot it made (Atelier, 2026-11-23; backlog 199) ----

const signed = (add, del) => {
  const a = Number(add) || 0;
  const d = Number(del) || 0;
  if (a && d) return `+${fmt(a)} −${fmt(d)}`;
  if (d) return '−' + fmt(d);
  return a ? '+' + fmt(a) : '0';
};

/**
 * The table's history: each commit the catalog's journal keeps, newest first, with who made it and the snapshot it
 * added, if any; the snapshot lanes above, and the selected snapshot's summary and time-travel SQL beside. A commit
 * that added no snapshot (a schema or spec change, a property) shows none.
 */
function historyTab(target, t) {
  const m = t.metadata;
  const props = m.properties || {};
  const list = snapshotsOf(m);
  const byId = new Map(list.map(sn => [String(sn['snapshot-id']), sn]));
  const refs = refsBySnapshot(m);
  const kept = (m['metadata-log'] || []).length + 1;
  let current = currentOf(m) || list[0] || null;
  let whole = false;
  const box = h('div', {class: 'grid-21', id: 'history-tab'});
  const tbody = h('tbody');
  const more = h('p', {class: 'fine'});
  const logMeta = h('span', {class: 'meta'}, 'newest first');
  const meta = h('span', {class: 'meta'});
  const raw = h('div');
  const sql = h('button', {class: 'btn sm', type: 'button', onclick: e => copyText(
    `SELECT * FROM ${target.catalog}.${pathOf(target)} FOR VERSION AS OF ${current['snapshot-id']}`, e.currentTarget)}, icon('copy'), 'Copy time-travel SQL');
  const wholeB = h('button', {class: 'btn sm ghost', type: 'button'}, 'Show the whole summary');
  const select = sn => {
    current = sn;
    const id = String(sn['snapshot-id']);
    for (const tr of $$('tr[data-id]', tbody)) tr.classList.toggle('is-sel', tr.dataset.id === id);
    for (const c of $$('.lanes circle[data-id]', box)) c.classList.toggle('is-sel', c.dataset.id === id);
    meta.textContent = `${id.slice(0, 4)}…${id.slice(-4)} · ${when(sn['timestamp-ms'])} · ${opOf(sn)}`;
    const summary = sn.summary || {};
    const shownSummary = whole ? Object.assign({'snapshot-id': sn['snapshot-id'], 'parent-snapshot-id': sn['parent-snapshot-id'],
      'sequence-number': sn['sequence-number'], 'schema-id': sn['schema-id'], 'manifest-list': sn['manifest-list']}, {summary})
      : Object.fromEntries(SHORT.filter(k => summary[k] !== undefined).map(k => [k, summary[k]]));
    fill(raw, jsonPre(shownSummary));
  };
  wholeB.addEventListener('click', () => {
    whole = !whole;
    wholeB.textContent = whole ? 'Show the short summary' : 'Show the whole summary';
    select(current);
  });
  const row = e => {
    const added = (Array.isArray(e.updates) ? e.updates : []).find(u => u.action === 'add-snapshot' && u.snapshot);
    const id = added ? String(added.snapshot['snapshot-id']) : null;
    const sn = id ? byId.get(id) || added.snapshot : null;
    const sum = (sn && sn.summary) || {};
    const btn = h('button', {class: 'btn sm ghost', type: 'button', 'aria-expanded': 'false',
      'aria-label': `Version ${e['new-version']}${e.branch ? ` on ${e.branch === 'main' ? 'main' : '@' + e.branch}` : ''}, what it changed`}, 'Show');
    // Through a ref, each entry names its line: the branch's own versions number from 1, so main's can share them.
    const line = e.branch ? h('span', {class: 'mlog-line'}, e.branch === 'main' ? 'main' : '@' + e.branch) : null;
    const tr = h('tr', {class: e['new-location'] === t['metadata-location'] ? 'is-cur' : null, 'data-version': String(e['new-version']),
      'data-line': e.branch || null, 'data-id': id},
      h('td', {class: 'num'}, line, fmt(e['new-version'])), h('td', {class: 'tnum'}, when(e['committed-at'])),
      h('td', {class: 'wrap'}, changeWords(e.updates, e['new-version'])),
      h('td', {class: 'name snap'}, sn ? [h('button', {class: 'row-pick', type: 'button', 'aria-label': `Snapshot ${id}, ${opOf(sn)}`,
        onclick: () => select(sn)}, id), ' ', muted('· ' + opOf(sn))] : muted('–')),
      h('td', {class: 'num'}, sn ? signed(sum['added-records'], sum['deleted-records']) : null),
      h('td', {class: 'by'}, byWords(e)),
      h('td', null, sn ? (refs.get(sn['snapshot-id']) || []).flatMap(([name, type], i) => [i ? ' ' : '', refBadge(name, type)]) : null),
      h('td', null, btn));
    const det = h('tr', {class: 'mlog-det', hidden: true}, h('td', {colspan: '8'},
      h('p', {class: 'fine'}, code(e['new-location'] || '')), jsonPre({'commit-id': e['commit-id'], updates: e.updates || []})));
    btn.addEventListener('click', () => {
      const open = det.hidden;
      det.hidden = !open;
      btn.setAttribute('aria-expanded', String(open));
      btn.textContent = open ? 'Hide' : 'Show';
    });
    return [tr, det];
  };
  let shown = 0;
  let id = null;
  const page = token => (id || (id = api.governance(target).then(g => g.target.id))).then(x => api.metadataLog(target.catalog, x, token, target.ref)).then(answer => {
    const entries = answer.journal || [];
    tbody.append(...entries.flatMap(row));
    shown += entries.length;
    if (current) select(current);
    const next = answer['next-page-token'];
    logMeta.textContent = `${plural(shown, 'commit')} shown · newest first`;
    const stops = !next && answer.ref && answer.ref['log-stops-at-cut'];
    more.replaceChildren(next ? `Older commits follow. ` : stops
      ? `The versions at and below @${answer.ref.name}'s point, ${fmt(answer.ref['cut-version'])}, came from a branch or tag that has ended, `
        + 'whose own commits the catalog no longer reads. '
      : `Every commit the journal keeps is shown. `);
    if (next) {
      more.append(h('button', {class: 'linkish', type: 'button', onclick: e => {
        e.currentTarget.disabled = true;
        page(next).catch(error => more.replaceChildren(failure(error, {compact: true})));
      }}, 'Show the next 25'));
    }
  });
  const log = h('div', {class: 'panel', id: 'metadata-log'});
  load(log, async () => {
    await page(null);
    return [h('h2', null, 'Commits ', logMeta),
      tbl('stack-line mlog', [['Version', 'num'], 'Written', 'Change', 'Snapshot', ['Rows', 'num'], 'By', 'Iceberg refs', ''], tbody), more,
      h('p', {class: 'fine'}, 'Each row is one commit: who made it, what it changed and the snapshot it added. The catalog keeps '
        + 'every commit, even after the table\'s own list drops its file.')];
  });
  const expiry = h('div');
  load(expiry, async () => {
    const pol = await api.policy(target);
    const r = pol.resolved || {};
    const fact = k => [policyKey(k), r[k] ? [h('b', null, String(r[k].value)), ' · ', sourceWords(r[k].source),
      r[k].reason ? [h('br'), h('span', {class: 'fine'}, `"${r[k].reason}"`)] : null] : muted('–')];
    const on = r['snapshot-expiry.enabled'] && String(r['snapshot-expiry.enabled'].value) === 'true';
    return h('div', {class: 'panel'}, h('h2', null, 'Snapshot expiry ', on ? pill('ok', 'On') : pill('held', icon('lock'), 'Off')),
      facts(['snapshot-expiry.enabled', 'snapshot-expiry.max-snapshot-age', 'snapshot-expiry.min-snapshots-to-keep'].map(fact), 'one'),
      h('p', {class: 'fine'}, 'A tagged snapshot stays as long as its tag keeps it.'));
  });
  const span = list.length ? (() => {
    const first = dayOf(list[list.length - 1]['timestamp-ms']);
    const last = dayOf(list[0]['timestamp-ms']);
    return first === last ? last : `${first} to ${last}`;
  })() : null;
  box.append(
    h('div', {class: 'stack'},
      list.length ? panel('Snapshots and Iceberg refs', `${plural(list.length, 'snapshot')} · ${span}`, lanes(target, list, m, select),
        h('p', {class: 'fine only-sm'}, 'Opens at the newest snapshot; older ones are to the left.'))
        : h('div', {class: 'empty'}, h('h3', null, 'No snapshot yet'), h('p', null, 'No commit has written data to the table.')),
      log),
    h('div', {class: 'stack'},
      current ? h('div', {class: 'panel', id: 'selected-snapshot'}, h('h2', null, 'Selected snapshot ', meta), raw,
        h('div', {class: 'refusal-acts'}, sql, wholeB)) : null,
      expiry,
      panel('Current file', null, facts([['File', code(t['metadata-location'] || '')], ['Written', when(m['last-updated-ms'])],
        ['Format version', String(m['format-version'])]])),
      panel('Kept', `${plural(kept, 'file')} in the table's metadata`, facts([
        [h('span', {title: 'write.metadata.previous-versions-max'}, 'Earlier metadata files listed'), props['write.metadata.previous-versions-max']
          ? [props['write.metadata.previous-versions-max'], ' · table'] : '100 · built-in'],
      ], 'one'), h('p', {class: 'fine'}, 'Every change writes a new metadata file; the table keeps the current one and those before it, '
        + 'up to the limit. A file that falls off the list is removed by the orphan scan.'))));
  if (current) select(current);
  return box;
}

// ---- partitioning ----

function namesById(m) {
  const names = new Map();
  for (const sc of m.schemas || []) for (const f of flatten(sc.fields)) if (!names.has(f.id)) names.set(f.id, f.name);
  return names;
}

function partitionTab(target, t) {
  const m = t.metadata;
  const names = namesById(m);
  const specs = (m['partition-specs'] || []).slice().sort((a, b) => b['spec-id'] - a['spec-id']);
  const orders = (m['sort-orders'] || []).slice().sort((a, b) => b['order-id'] - a['order-id']);
  const byId = new Map(specs.map(sp => [sp['spec-id'], sp]));
  const specPanel = sp => {
    const id = sp['spec-id'];
    const prev = byId.get(id - 1);
    const before = new Set(prev ? (prev.fields || []).map(f => f['field-id']) : []);
    const fields = sp.fields || [];
    return h('div', {class: 'panel', 'data-spec': id}, h('h2', null, `Spec ${id} `, id === m['default-spec-id'] ? pill('accent', 'Default') : null,
      h('span', {class: 'meta'}, fields.length ? plural(fields.length, 'field') : 'unpartitioned')),
    fields.length ? h('div', {class: 'spec'}, fields.map(f => {
      const isNew = prev && !before.has(f['field-id']);
      return h('div', {class: 'xf' + (isNew ? ' new' : '')}, h('span', {class: 'src'}, `${names.get(f['source-id']) || '?'} · ${f['source-id']}`),
        h('span', {class: 'arr'}, '→'), h('span', {class: 'fn'}, f.transform), h('span', {class: 'arr'}, '→'),
        h('span', {class: 'out'}, `${f.name} · ${f['field-id']}`), isNew ? h('span', {class: 'diff-add'}, `added in spec ${id}`) : null);
    })) : h('p', {class: 'muted'}, 'Every data file sits in one partition.'));
  };
  const nth = i => ['1st', '2nd', '3rd'][i] || `${i + 1}th`;
  const orderPanel = o => panel(`Sort order ${o['order-id']}`, o['order-id'] === m['default-sort-order-id'] ? 'default' : null,
    (o.fields || []).length ? facts(o.fields.map((f, i) => [nth(i), [code(names.get(f['source-id']) || String(f['source-id'])),
      ` · ${f.transform} · ${f.direction === 'desc' ? 'descending' : 'ascending'} · ${f['null-order'] === 'nulls-last' ? 'nulls last' : 'nulls first'}`]]))
      : h('p', {class: 'muted'}, 'Unsorted: files keep the order rows arrived in.'));
  return h('div', {class: 'grid-21'}, h('div', {class: 'stack'}, specs.map(specPanel)),
    h('div', {class: 'stack'}, orders.map(orderPanel),
      specs.length > 1 ? h('div', {class: 'notice'}, icon('maint'), h('span', null, h('b', null, 'Old files keep the spec they were written with.'),
        ` Compaction writes the default spec, so older files move to spec ${m['default-spec-id']} as the worker rewrites them.`)) : null));
}

// ---- metadata log ----

/**
 * What one commit changed, in a few words: its update actions, each once, in order. A create, a registration and a
 * revive (a registration of a dropped table's file, at a later version) keep their request instead of a list of updates;
 * all three are main's. A commit on a branch, its first included, keeps the engine's list of updates like any commit:
 * the branch's version 1 is made from the cut's file, not created.
 */
function changeWords(updates, version) {
  if (updates && !Array.isArray(updates)) {
    return !updates['metadata-location'] ? 'created' : Number(version) > 1 ? 'restored' : 'registered';
  }
  const merge = Array.isArray(updates) ? updates.find(u => u.action === 'merge-branch') : null;
  if (merge) return `Merged @${merge.branch} (its version ${fmt(merge['branch-version'])})`;
  const words = actionWords(updates);
  return words.length ? words.join(', ') : muted('no update');
}

/** Who made a commit: the principal, the person an engine acted for, and the engine. */
/**
 * The journal's commit that added each snapshot asked for (backlog 186), by snapshot id: the metadata log is read a page
 * at a time, newest first, until every one is found or 200 commits are read. A snapshot whose commit the journal no longer
 * holds, or lies further back, is left out, and its By is blank.
 */
async function snapshotCommits(target, ids) {
  const want = new Set(ids.map(String));
  const found = new Map();
  const id = (await api.governance(target)).target.id;
  let token = null;
  for (let read = 0; read < 8 && want.size; read++) {
    const answer = await api.metadataLog(target.catalog, id, token, target.ref);
    for (const e of answer.journal || []) {
      for (const u of Array.isArray(e.updates) ? e.updates : []) {
        const added = u.action === 'add-snapshot' && u.snapshot ? String(u.snapshot['snapshot-id']) : null;
        if (added && want.delete(added)) found.set(added, e);
      }
    }
    token = answer['next-page-token'];
    if (!token) break;
  }
  return found;
}

function byWords(e) {
  return [h('b', null, e.principal || '–'), e['end-user'] ? [' for ', h('b', null, e['end-user'])] : null,
    e.engine ? [' ', muted('via ' + e.engine)] : null];
}

// ---- the view page ----

const VIEW_TABS = [['', 'Overview'], ['versions', 'Versions'], ['access', 'Access'], ['audit', 'Audit']];

export function viewPage(target, tab, route) {
  const main = h('div', {class: 'nc-main', id: 'view-page'});
  const want = openedTab(target, tab);
  const key = VIEW_TABS.some(([k]) => k === want) ? want : null;
  const title = h('div', {class: 'title-row'}, h('span', {class: 'kind'}, icon('view')), h('h1', {tabindex: '-1'}, target.name));
  const tabs = tabsNav('View', VIEW_TABS.filter(([k]) => k !== 'audit' || trailOpen(target.catalog)), key, tabHref(target));
  const side = h('div', {class: 'side'}, copyLinkButton());
  const head = h('div', {class: 'ph'}, crumbsFor(target.catalog, target.namespace, target.name, target.ref), title, side);
  main.append(head, tabs);
  const body = tabBody(main);
  refChrome(route, head);
  const loaded = loadView(target);
  loaded.then(v => {
    title.append(badge('view', 'strong'));
    addCount(tabs, 'versions', (v.metadata.versions || []).length);
  }, () => {});
  const show = () => {
    if (key === null) fill(body, h('div', {class: 'empty'}, h('h3', null, 'A view has no such tab')));
    else if (key === 'access') load(body, () => throughRef(target, () => loaded, null, () => accessTab(target, show)));
    else if (key === 'audit' && !trailOpen(target.catalog)) fill(body, closedNote(trailWords(target.catalog)));
    else if (key === 'audit') load(body, () => throughRef(target, () => loaded, null, () => auditTab(target)));
    else {
      load(body, async () => {
        let v;
        try {
          v = await loaded;
        } catch (error) {
          return refusedLoad(error, 'Access and Audit', target.ref);
        }
        return key === 'versions' ? versionsTab(v) : viewOverview(target, v);
      });
    }
  };
  show();
  return main;
}

function versionOf(m, id) {
  return (m.versions || []).find(x => x['version-id'] === id) || null;
}

function sqlOf(version) {
  return (version.representations || []).filter(r => r.type === 'sql');
}

function representations(version) {
  return sqlOf(version).map(r => h('div', {class: 'sql', 'data-dialect': r.dialect}, h('p', {class: 'fine sql-head'}, code(r.dialect)),
    h('pre', {class: 'raw'}, r.sql)));
}

function viewOverview(target, v) {
  const m = v.metadata;
  const cur = versionOf(m, m['current-version-id']) || {};
  const schema = (m.schemas || []).find(x => x['schema-id'] === cur['schema-id']) || {};
  const cols = flatten(schema.fields);
  const sqls = sqlOf(cur);
  return h('div', {class: 'grid-21'},
    h('div', {class: 'stack'},
      panel('Definition', `version ${cur['version-id']} · ${sqls.map(r => r.dialect).join(' and ') || 'no SQL'}`, representations(cur),
        sqls.length ? h('div', {class: 'refusal-acts'}, copyButton(sqls[0].sql, 'Copy the SQL')) : null),
      panel('Schema', plural(cols.length, 'column'), tbl('', [['Id', 'num'], 'Column', 'Type'], cols.map(f => h('tr', null,
        h('td', {class: 'num'}, String(f.id)), h('td', {class: 'name' + (f.depth ? ' indent-1' : '')}, f.name), h('td', {class: 'name'}, f.type)))))),
    h('div', {class: 'stack'}, panel('Current state', null, facts([
      ['View UUID', code(m['view-uuid'] || '')],
      ['Current version', `${cur['version-id']} · ${when(cur['timestamp-ms'])}`],
      ['Written by', (cur.summary || {})['engine-name'] ? `${cur.summary['engine-name']} ${cur.summary['engine-version'] || ''}`.trim() : null],
      ['Default namespace', code((cur['default-namespace'] || []).join('.'))],
      ['Location', code(m.location || '')],
      ['Metadata file', code(v['metadata-location'] || '')],
      ['Properties', propsDd(m.properties)],
    ]))));
}

function versionsTab(v) {
  const m = v.metadata;
  const list = (m.versions || []).slice().sort((a, b) => b['version-id'] - a['version-id']);
  return list.map(ver => h('article', {class: 'panel version', 'data-version': ver['version-id']},
    h('h2', null, `Version ${ver['version-id']} `, ver['version-id'] === m['current-version-id'] ? pill('accent', 'Current') : null,
      h('span', {class: 'meta'}, `${when(ver['timestamp-ms'])}${(ver.summary || {})['engine-name'] ? ' · ' + ver.summary['engine-name'] : ''}`
        + ` · schema ${ver['schema-id']}`)),
    representations(ver)));
}

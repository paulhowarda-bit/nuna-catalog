// The catalog's browse pages: the tree on the left, and on the right the catalog, a namespace, a table or a view.
// The catalog has Contents and Properties (item 131); its Governance, Grants and Audit are the rail's pages of those
// names. A namespace has Contents, Properties (Iceberg REST), Governance, Grants and Audit. Contents marks what is
// held, frozen, regulated or closed until classified (item 128), and gives each table its format, snapshot count and
// last commit from the catalog's own listing of them (backlog 137), which reads no metadata file; each child namespace
// gets its totals over the tables beneath it: how many, their snapshots, and the newest commit (backlog 147).
// Read through a catalog branch or tag (r=, item 109) the catalog, a namespace, a table and a view say so four
// ways: the top chip, the first crumb catalog@ref, the "Reading @ref" selector and a band; the catalog has a
// Branches and tags tab (refs.js).

import * as api from './api.js';
import {$, at, badge, code, copyButton, copyText, facts, fill, fmt, h, icon, load, muted, panel, pill, plural, tabsNav, tbl,
  when, opensRow} from './dom.js';
import {catalogHref, objectHref, pageHref, warehouseOf} from './route.js';
import {queue, queueOf} from './queue.js';
import {reveal, treeOf} from './tree.js';
import {accessTab, auditTab, governanceMarks, markPills} from './panels.js';
import {tablePage, viewPage} from './table.js';
import {NAMESPACE_SETTINGS, settingsTab} from './settings.js';
import {readingRef, readOnlyNote, refChrome, refNote, refPage, refsPage} from './refs.js';
import {closedNote, trailOpen, trailWords} from './access.js';

/** The path above a page: the catalog (catalog@ref through a ref), then each namespace; a long one folds its far end on a phone. */
export function crumbsFor(catalog, levels, last, ref) {
  const nav = h('nav', {class: 'crumbs', 'aria-label': 'Path'});
  const items = [[ref ? `${catalog}@${ref}` : catalog, catalogHref(catalog, null, ref)]];
  levels.forEach((p, i) => items.push([p, objectHref({catalog, kind: 'namespace', namespace: levels.slice(0, i + 1), ref})]));
  const count = items.length + (last ? 1 : 0);
  const far = count > 3 ? count - 3 : 0;
  if (far) nav.append(h('span', {class: 'ell'}, h('a', {href: items[far - 1][1], 'aria-label': 'Up the path'}, '…'), h('span', {class: 'sep'}, '/')));
  items.forEach(([label, url], i) => {
    const cls = i < far ? 'far' : null;
    const isCur = !last && i === items.length - 1;
    nav.append(isCur ? h('span', {class: 'cur'}, label) : h('a', {href: url, class: cls}, label));
    if (!isCur) nav.append(h('span', {class: 'sep' + (cls ? ' far' : '')}, '/'));
  });
  if (last) nav.append(h('span', {class: 'cur'}, last));
  return nav;
}

export function copyLinkButton() {
  return h('button', {class: 'btn sm', type: 'button', onclick: e => copyText(location.href, e.currentTarget)}, icon('link'), 'Copy link');
}

export function notFound(what) {
  return h('div', {class: 'nc-main'}, h('div', {class: 'ph'}, h('h1', {tabindex: '-1'}, 'Nothing at this path')),
    h('div', {class: 'empty'}, h('h3', null, what || 'The console has no page here'),
      h('p', null, 'It may have been renamed or dropped, or the link was cut short.'),
      h('div', {class: 'refusal-acts'}, h('button', {class: 'btn sm', type: 'button', 'data-act': 'goto'}, icon('search'), 'Go to a path'))));
}

/**
 * The browse layout for route `r` in `slot`: the catalog's tree is kept when the page beside it changes, so its
 * open levels and filter stay as they were.
 */
export function browse(slot, r) {
  // A ref's own page (k=ref) names the ref in n, and reads main's tree; every other page reads r's.
  const st = treeOf(r.c, r.k === 'ref' ? null : r.r);
  let box = $(':scope > .browse', slot);
  if (!box || box.dataset.catalog !== r.c) box = h('div', {class: 'browse', 'data-catalog': r.c});
  if (st.el.parentNode !== box) {
    const old = $(':scope > .tree', box);
    if (old) old.replaceWith(st.el);
    else box.prepend(st.el);
  }
  const target = {catalog: r.c, kind: r.k, namespace: r.ns, name: r.n, ref: r.r};
  let main;
  if (r.k === 'table' && r.n) main = tablePage(target, r.tab, r);
  else if (r.k === 'view' && r.n) main = viewPage(target, r.tab, r);
  else if (r.k === 'namespace' && r.ns.length) main = namespacePage(target, r.tab, r);
  else if (r.k === 'catalog' && r.tab === 'refs') main = refsPage(r.c);
  else if (r.k === 'catalog') main = catalogPage(r.c, r.tab, r);
  else if (r.k === 'ref' && r.n) main = refPage(r.c, r.n, r.tab);
  else main = notFound();
  const old = $(':scope > .nc-main', box);
  if (old) old.replaceWith(main);
  else box.append(main);
  reveal(st, r.k === 'catalog' || !['table', 'view', 'namespace'].includes(r.k) ? {catalog: r.c, kind: 'catalog', namespace: []} : target);
  return box;
}

// ---- the contents of a level ----

function queuedUnder(rows, levels) {
  return rows.filter(r => levels.every((l, i) => r.namespace[i] === l));
}

function queuePill(rows) {
  if (!rows.length) return muted('–');
  const eq = rows.some(r => (r.assessment.reasons || []).includes('equality-deletes'));
  return pill(eq ? 'bad' : 'warn', `${rows.length} queued`);
}

/** A namespace's tables as the catalog keeps them: [{name, format-version, snapshots, last-commit}]. */
const tableFacts = (catalog, levels) =>
  api.all(api.managed({catalog, namespace: levels}) + '/tables?pageSize=200', 'tables');

/**
 * The child namespaces of a level with their totals over the tables beneath them:
 * [{name, tables, snapshots, complete, newest-commit}].
 */
const namespaceTotals = (catalog, levels) =>
  api.all(api.managed({catalog, namespace: levels}) + '/namespaces?pageSize=200', 'namespaces');

/**
 * A level's contents. Through a ref the listing is the ref's (Iceberg REST through catalog@ref); the catalog's own
 * facts of each table and namespace totals are main's, so they are left out, and each row says what it is on the
 * ref from the branch's changed tables instead.
 */
async function contents(catalog, levels, ref) {
  const wh = warehouseOf({catalog, ref});
  const [ns, tables, views, q, gov, kept, totals, changed] = await Promise.all([api.namespaces(wh, levels),
    levels.length ? api.tables(wh, levels) : [], levels.length ? api.views(wh, levels) : [], queue().catch(() => []),
    governanceMarks(catalog), levels.length && !ref ? tableFacts(catalog, levels) : [], ref ? [] : namespaceTotals(catalog, levels),
    ref ? api.ref(catalog, ref).then(x => x.changed || [], () => []) : []]);
  return {ns, tables: tables.map(t => t.name), views: views.map(v => v.name), q: queueOf(q, catalog), gov,
    kept: new Map(kept.map(t => [t.name, t])), totals: new Map(totals.map(n => [n.name, n])), ref, changed};
}

/** The On @ref cell: changed here at its version, or as at the point; a namespace counts what changed beneath it. */
function onRefCell(x, path, isNamespace) {
  const key = path.join('.');
  if (isNamespace) {
    const n = x.changed.filter(t => [...t.namespace, t.name].join('.').startsWith(key + '.')).length;
    return n ? h('span', {class: 'on-ref'}, `${plural(n, 'table')} changed here`) : muted('—');
  }
  const t = x.changed.find(c => [...c.namespace, c.name].join('.') === key);
  return t ? h('span', {class: 'on-ref'}, pill('ok', 'changed here'), ' ', badge('v' + fmt(t.version), 'strong'))
    : h('span', {class: 'on-ref muted'}, `not changed on @${x.ref}`);
}

/** A table's last commit: when, then by whom; an engine acting for someone names them both (12:49 by lake). */
function lastCommit(c) {
  if (!c) return muted('–');
  const who = c['end-user'] ? `${c['end-user']} via ${c.principal}` : c.principal;
  return [h('span', {class: 'tnum', title: when(c['committed-at'])}, at(c['committed-at'])), ' ', muted('by ' + who)];
}

/** A namespace's newest commit beneath it: when, then in which table, by its path below the namespace (12:49 in rates). */
function newestCommit(c) {
  if (!c) return muted('–');
  return [h('span', {class: 'tnum', title: when(c['committed-at'])}, at(c['committed-at'])), ' ',
    muted('in ' + c.table.join('.'))];
}

/** A namespace's totals, its cells: a count after its name, its snapshots, its newest commit. A cut-short walk adds +. */
function totalsCells(t) {
  if (!t || !t.tables) return {count: null, snaps: muted('–'), last: muted('–'), title: null};
  const more = t.complete ? '' : '+';
  return {count: [' ', muted(`· ${fmt(t.tables)}${more} ${t.tables === 1 && !more ? 'table' : 'tables'}`)],
    snaps: fmt(t.snapshots) + more, last: newestCommit(t['newest-commit']),
    title: t.complete ? null : 'Counted over the first 1,000 tables and namespaces beneath it'};
}

function contentsTable(catalog, levels, x) {
  const rows = [
    ...x.ns.map(n => {
      const t = totalsCells(x.totals.get(n[n.length - 1]));
      return h('tr', {class: 'opens', onclick: opensRow, 'data-kind': 'namespace', 'data-name': n[n.length - 1], title: t.title},
        h('td', {class: 'name'}, h('a', {href: objectHref({catalog, kind: 'namespace', namespace: n, ref: x.ref})}, n[n.length - 1]), t.count),
        h('td', null, badge([icon('ns'), 'namespace'])), h('td', {class: 'fmt'}, muted('–')),
        h('td', {class: 'queue'}, queuePill(queuedUnder(x.q, n))),
        h('td', {class: 'gov'}, markPills(x.gov.get('namespace:' + n.join('.'))) || muted('–')),
        h('td', {class: 'num snaps'}, t.snaps), h('td', {class: 'last'}, t.last), x.ref ? h('td', null, onRefCell(x, n, true)) : null);
    }),
    ...x.tables.map(name => {
      const mine = x.q.filter(r => r.name === name && r.namespace.join('.') === levels.join('.'));
      const kept = x.kept.get(name) || {};
      return h('tr', {class: 'opens', onclick: opensRow, 'data-kind': 'table', 'data-name': name},
        h('td', {class: 'name'}, h('a', {href: objectHref({catalog, kind: 'table', namespace: levels, name, ref: x.ref})}, name)),
        h('td', null, badge([icon('table'), 'table'])),
        h('td', {class: 'fmt'}, kept['format-version'] ? badge('v' + kept['format-version'], 'strong') : muted('–')),
        h('td', {class: 'queue'}, mine.length ? h('a', {href: objectHref({catalog, kind: 'table', namespace: levels, name, ref: x.ref}, 'maintenance')}, queuePill(mine))
          : muted('–')),
        h('td', {class: 'gov'}, markPills(x.gov.get('table:' + [...levels, name].join('.'))) || muted('–')),
        h('td', {class: 'num snaps'}, kept.snapshots === undefined ? muted('–') : fmt(kept.snapshots)),
        h('td', {class: 'last'}, lastCommit(kept['last-commit'])), x.ref ? h('td', null, onRefCell(x, [...levels, name], false)) : null);
    }),
    ...x.views.map(name => h('tr', {class: 'opens', onclick: opensRow, 'data-kind': 'view', 'data-name': name},
      h('td', {class: 'name'}, h('a', {href: objectHref({catalog, kind: 'view', namespace: levels, name, ref: x.ref})}, name)),
      h('td', null, badge([icon('view'), 'view'])), h('td', {class: 'fmt'}, muted('–')), h('td', {class: 'queue'}, muted('–')),
      h('td', {class: 'gov'}, markPills(x.gov.get('view:' + [...levels, name].join('.'))) || muted('–')),
      h('td', {class: 'num snaps'}, muted('–')), h('td', {class: 'last'}, muted('–')),
      x.ref ? h('td', null, onRefCell(x, [...levels, name], false)) : null)),
  ];
  if (!rows.length) return h('div', {class: 'empty'}, h('h3', null, levels.length ? 'Nothing in this namespace' : 'No namespace in this catalog'),
    h('p', null, 'Or nothing your groups may see. If you expected something here, ask a grant admin.'));
  const heads = ['Name', 'Kind', 'Format', 'Tonight\'s queue', 'Governance', ['Snapshots', 'num'],
    x.ref ? 'Last commit here' : 'Last commit', x.ref ? `On @${x.ref}` : null].filter(Boolean);
  // A column with nothing on any row says nothing, so it goes (Atelier's review, F16; backlog 193).
  const blank = td => ['', '–', '—'].includes(td.textContent.trim());
  const empty = heads.map((_, i) => i > 1 && rows.every(r => blank(r.children[i])));
  for (const r of rows) [...r.children].forEach((td, i) => { if (empty[i]) td.remove(); });
  return h('div', {class: 'panel', id: 'contents'}, tbl('contents stack-line', heads.filter((_, i) => !empty[i]), rows),
    h('p', {class: 'fine'}, 'Only what your groups may see is listed. ', x.ref
      ? `Through @${x.ref}, format, snapshots and last commit are left out: open a table to see it as @${x.ref} has it.`
      : 'Open a table for its maintenance, access and history.'));
}

const countWords = (n, w) => n ? plural(n, w) : `no ${w}s`;

// ---- the catalog ----

// A catalog's tabs are its own: what is in it and its properties. Governance, Audit, Branches and tags and the rest
// are the rail's catalog-wide pages (Atelier, 2026-11-23 and 2026-11-24; backlogs 199 and 221).
const CATALOG_TABS = ['', 'properties'];

function catalogPage(catalog, tab, route) {
  const ref = route.r;
  const main = h('div', {class: 'nc-main', id: 'catalog-page'});
  const key = CATALOG_TABS.includes(tab || '') ? tab || '' : null;
  load(main, async () => {
    let x = null;
    try {
      x = await contents(catalog, [], ref);
    } catch (error) {
      // A link naming no live ref: the band says so, as the catalog answered it.
      if (!ref || error.status !== 404) throw error;
    }
    const side = h('div', {class: 'side'}, copyButton(catalog, 'Copy name'), copyLinkButton());
    const head = h('div', {class: 'ph'},
      h('div', {class: 'title-row'}, h('span', {class: 'kind'}, icon('db')), h('h1', {tabindex: '-1'}, catalog), h('span', {class: 'muted'}, 'catalog')),
      x ? h('p', {class: 'sub'}, `${countWords(x.ns.length, 'namespace')} · `,
        x.q.length ? `${plural(x.q.length, 'table')} queued for tonight` : 'nothing you may read is queued for tonight') : null,
      side);
    const tabs = tabsNav('Catalog', [['', 'Contents', x ? x.ns.length : null], ['properties', 'Properties']],
      key, k => catalogHref(catalog, k || null, ref));
    refChrome(Object.assign({}, route, {r: ref}), head);
    return [head, tabs,
      key === null ? h('div', {class: 'empty'}, h('h3', null, 'A catalog has no such tab'))
        : key === 'properties' ? loading(() => catalogProperties(catalog))
          : x ? contentsTable(catalog, [], x) : null,
    ];
  });
  return main;
}

/** A catalog's properties: the boundary it was declared with and where its tables live (item 131). */
async function catalogProperties(catalog) {
  const p = await api.catalogProperties(catalog);
  const sp = p['storage-profile'] || {};
  return h('div', {class: 'grid-21'},
    h('div', {class: 'panel', id: 'catalog-properties'}, h('h2', null, 'Properties ', h('span', {class: 'meta'}, 'as the catalog was declared')),
      facts([
        ['Name', code(p.name || catalog)],
        ['Legal entity', p['legal-entity'] || null],
        ['Storage profile', sp.name ? [code(sp.name), sp.kind ? muted(' · ' + sp.kind) : null] : null],
        ['Endpoint', sp.endpoint ? code(sp.endpoint) : muted('the provider\'s default')],
        ['Region', sp.region || null],
        ['Table root', sp['table-root'] ? code(sp['table-root']) : null],
        ['Allowed prefixes', (sp['allowed-prefixes'] || []).length ? sp['allowed-prefixes'].flatMap((x, i) => [i ? h('br') : null, code(x)]) : null],
        ['Encryption', sp.encryption === 'sse-kms' ? 'SSE-KMS, with a key of its own' : sp.encryption === 'bucket-default'
          ? 'the bucket\'s default' : sp.encryption || null],
        ['KMS key set', p['kms-key-set'] ? code(p['kms-key-set']) : null],
      ])),
    h('div', {class: 'stack'}, h('div', {class: 'notice'}, icon('info'), h('span', null,
      h('b', null, 'Set when the deployment was configured; they can\'t be changed here.'),
      ' The catalog\'s owner and classification are under ', h('a', {href: pageHref('governance', catalog, 'holds')}, 'Governance'),
      ', and how its tables are compacted, expired and cleaned up is its ',
      h('a', {href: pageHref('maintenance', catalog), 'data-act': 'policy'}, 'maintenance policy'),
      '. Storage keys and roles are never shown.'))));
}

// ---- a namespace ----

const NS_TABS = ['', 'access', 'audit', 'properties'];

function namespacePage(target, tab, route) {
  const main = h('div', {class: 'nc-main', id: 'namespace-page'});
  const levels = target.namespace;
  const dotted = levels.join('.');
  const side = h('div', {class: 'side'}, copyButton(dotted, 'Copy path'), copyLinkButton());
  const head = h('div', {class: 'ph'}, crumbsFor(target.catalog, levels.slice(0, -1), levels[levels.length - 1], target.ref),
    h('div', {class: 'title-row'}, h('span', {class: 'kind'}, icon('ns')), h('h1', {tabindex: '-1'}, levels[levels.length - 1]),
      levels.length > 1 ? h('code', {class: 'muted'}, dotted) : null),
    h('p', {class: 'sub', 'data-sub': ''}, ''),
    side);
  const key = NS_TABS.includes(tab || '') ? tab || '' : null;
  const body = h('div', {class: 'tab-body'});
  main.append(head, tabsNav('Namespace', [['', 'Contents'], ['access', 'Access'], ['audit', 'Audit'], ['properties', 'Properties']]
    .filter(([k]) => k !== 'audit' || trailOpen(target.catalog)), key,
  k => objectHref(target, k || null)), body);
  const sub = $('[data-sub]', head);
  const reload = () => fill(body, namespaceTab(target, key, sub, reload));
  reload();
  refChrome(route, head);
  return main;
}

function namespaceTab(target, key, sub, reload) {
  const levels = target.namespace;
  if (key === null) return h('div', {class: 'empty'}, h('h3', null, 'A namespace has no such tab'));
  if (key === 'access') return accessTab(target, reload);
  if (key === 'audit') return trailOpen(target.catalog) ? auditTab(target) : closedNote(trailWords(target.catalog));
  if (key === 'properties') return loading(() => target.ref ? refPropertiesTab(target) : propertiesTab(target, reload));
  return loading(async () => {
    let owner;
    let x;
    try {
      [owner, x] = await Promise.all([api.governance(target).then(g => (g.effective || {}).owner, () => null),
        contents(target.catalog, levels, target.ref)]);
    } catch (error) {
      // A link naming no live ref: the band says so, as the catalog answered it.
      if (target.ref && error.status === 404 && error.type === 'NoSuchWarehouseException') return null;
      throw error;
    }
    fill(sub, `${countWords(x.tables.length, 'table')} · ${countWords(x.views.length, 'view')} · `
      + `${countWords(x.ns.length, 'child namespace')}`, owner ? [' · owner ', h('b', {'data-owner': ''}, String(owner))] : null);
    return contentsTable(target.catalog, levels, x);
  });
}

function loading(loader) {
  const box = h('div', {class: 'tab-body'});
  load(box, loader);
  return box;
}

/**
 * A namespace's properties read through a ref: main's, live, and changed on main only, so the console offers no
 * change and shows what the catalog would answer one (item 109).
 */
async function refPropertiesTab(target) {
  const [ns, r] = await Promise.all([api.namespace(target.catalog, target.namespace), readingRef(target.catalog, target.ref)]);
  const props = Object.entries(ns.properties || {}).sort(([a], [b]) => a.localeCompare(b));
  return h('div', {class: 'stack'}, refNote(r || target.ref, 'nsprops'),
    r ? readOnlyNote(r, target.catalog, 'updateProperties', true) : null,
    panel('Properties', 'main\'s, read now', props.length ? facts(props.map(([k, v]) => [code(k), String(v)])) : muted('No property is set.')));
}

/** A namespace's properties: its one setting, the comment, and every property read-only (item 141). */
async function propertiesTab(target, reload) {
  const ns = await api.namespace(target.catalog, target.namespace);
  return settingsTab({target, settings: NAMESPACE_SETTINGS, props: ns.properties || {}, ctx: {}, reload,
    needs: 'A change here is checked and audited like an engine\'s.',
    write: (updates, removals) => api.namespaceProperties(target.catalog, target.namespace, updates, removals)});
}

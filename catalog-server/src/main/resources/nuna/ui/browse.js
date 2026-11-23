// The catalog's browse pages: the tree on the left, and on the right the catalog, a namespace, a table or a view.
// The catalog has Contents and Properties (item 131); its Governance, Grants and Audit are the Governance and Audit
// pages. A namespace has Contents, Properties (Iceberg REST), Governance, Grants and Audit. Contents marks what is
// held, frozen, regulated or closed until classified (item 128), and gives each table its format, snapshot count and
// last commit from the catalog's own listing of them (backlog 137), which reads no metadata file; each child namespace
// gets its totals over the tables beneath it: how many, their snapshots, and the newest commit (backlog 147).

import * as api from './api.js';
import {$, at, badge, code, copyButton, copyText, facts, fill, fmt, h, icon, load, muted, pill, plural, tabsNav, tbl,
  when} from './dom.js';
import {catalogHref, objectHref, pageHref} from './route.js';
import {queue, queueOf} from './queue.js';
import {reveal, treeOf} from './tree.js';
import {auditTab, governanceMarks, governanceTab, grantsTab, markPills} from './panels.js';
import {tablePage, viewPage} from './table.js';
import {NAMESPACE_SETTINGS, settingsTab} from './settings.js';

/** The path above a page: the catalog, then each namespace; a long one folds its far end on a phone. */
export function crumbsFor(catalog, levels, last) {
  const nav = h('nav', {class: 'crumbs', 'aria-label': 'Path'});
  const items = [[catalog, catalogHref(catalog)]];
  levels.forEach((p, i) => items.push([p, objectHref({catalog, kind: 'namespace', namespace: levels.slice(0, i + 1)})]));
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
  const st = treeOf(r.c);
  let box = $(':scope > .browse', slot);
  if (!box || box.dataset.catalog !== r.c) box = h('div', {class: 'browse', 'data-catalog': r.c});
  if (st.el.parentNode !== box) box.prepend(st.el);
  const target = {catalog: r.c, kind: r.k, namespace: r.ns, name: r.n};
  let main;
  if (r.k === 'table' && r.n) main = tablePage(target, r.tab);
  else if (r.k === 'view' && r.n) main = viewPage(target, r.tab);
  else if (r.k === 'namespace' && r.ns.length) main = namespacePage(target, r.tab);
  else if (r.k === 'catalog') main = catalogPage(r.c, r.tab);
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

async function contents(catalog, levels) {
  const [ns, tables, views, q, gov, kept, totals] = await Promise.all([api.namespaces(catalog, levels),
    levels.length ? api.tables(catalog, levels) : [], levels.length ? api.views(catalog, levels) : [], queue().catch(() => []),
    governanceMarks(catalog), levels.length ? tableFacts(catalog, levels) : [], namespaceTotals(catalog, levels)]);
  return {ns, tables: tables.map(t => t.name), views: views.map(v => v.name), q: queueOf(q, catalog), gov,
    kept: new Map(kept.map(t => [t.name, t])), totals: new Map(totals.map(n => [n.name, n]))};
}

/** A table's last commit: when, then who; an engine acting for someone names them both. */
function lastCommit(c) {
  if (!c) return muted('–');
  const who = c['end-user'] ? `${c['end-user']} via ${c.principal}` : c.principal;
  return [h('span', {class: 'tnum', title: when(c['committed-at'])}, at(c['committed-at'])), ' ', muted('· ' + who)];
}

/** A namespace's newest commit beneath it: when, then the table, by its path below the namespace. */
function newestCommit(c) {
  if (!c) return muted('–');
  return [h('span', {class: 'tnum', title: when(c['committed-at'])}, at(c['committed-at'])), ' ',
    muted('· ' + c.table.join('.'))];
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
      return h('tr', {'data-kind': 'namespace', 'data-name': n[n.length - 1], title: t.title},
        h('td', {class: 'name'}, h('a', {href: objectHref({catalog, kind: 'namespace', namespace: n})}, n[n.length - 1]), t.count),
        h('td', null, badge([icon('ns'), 'namespace'])), h('td', {class: 'fmt'}, muted('–')),
        h('td', null, queuePill(queuedUnder(x.q, n))),
        h('td', {class: 'gov'}, markPills(x.gov.get('namespace:' + n.join('.'))) || muted('–')),
        h('td', {class: 'num snaps'}, t.snaps), h('td', {class: 'last'}, t.last));
    }),
    ...x.tables.map(name => {
      const mine = x.q.filter(r => r.name === name && r.namespace.join('.') === levels.join('.'));
      const kept = x.kept.get(name) || {};
      return h('tr', {'data-kind': 'table', 'data-name': name},
        h('td', {class: 'name'}, h('a', {href: objectHref({catalog, kind: 'table', namespace: levels, name})}, name)),
        h('td', null, badge([icon('table'), 'table'])),
        h('td', {class: 'fmt'}, kept['format-version'] ? badge('v' + kept['format-version'], 'strong') : muted('–')),
        h('td', null, mine.length ? h('a', {href: objectHref({catalog, kind: 'table', namespace: levels, name}, 'health')}, queuePill(mine))
          : muted('–')),
        h('td', {class: 'gov'}, markPills(x.gov.get('table:' + [...levels, name].join('.'))) || muted('–')),
        h('td', {class: 'num snaps'}, kept.snapshots === undefined ? muted('–') : fmt(kept.snapshots)),
        h('td', {class: 'last'}, lastCommit(kept['last-commit'])));
    }),
    ...x.views.map(name => h('tr', {'data-kind': 'view', 'data-name': name},
      h('td', {class: 'name'}, h('a', {href: objectHref({catalog, kind: 'view', namespace: levels, name})}, name)),
      h('td', null, badge([icon('view'), 'view'])), h('td', {class: 'fmt'}, muted('–')), h('td', null, muted('–')),
      h('td', {class: 'gov'}, markPills(x.gov.get('view:' + [...levels, name].join('.'))) || muted('–')),
      h('td', {class: 'num snaps'}, muted('–')), h('td', {class: 'last'}, muted('–')))),
  ];
  if (!rows.length) return h('div', {class: 'empty'}, h('h3', null, levels.length ? 'Nothing in this namespace' : 'No namespace in this catalog'),
    h('p', null, 'Or nothing you may describe: the catalog lists only what your groups may see.'));
  return h('div', {class: 'panel', id: 'contents'}, tbl('contents stack-line', ['Name', 'Kind', 'Format', 'Tonight\'s queue', 'Governance',
    ['Snapshots', 'num'], 'Last commit'], rows),
    h('p', {class: 'fine'}, 'Only what your groups may describe is listed. Format, snapshots and last commit are what the catalog '
      + 'keeps of each table\'s current metadata; its health, governance and history are on its own page. A namespace '
      + 'counts the tables beneath it that you may describe.'));
}

const countWords = (n, w) => n ? plural(n, w) : `no ${w}s`;

// ---- the catalog ----

const CATALOG_TABS = ['', 'properties'];

function catalogPage(catalog, tab) {
  const main = h('div', {class: 'nc-main', id: 'catalog-page'});
  const key = CATALOG_TABS.includes(tab || '') ? tab || '' : null;
  load(main, async () => {
    const x = await contents(catalog, []);
    return [
      h('div', {class: 'ph'},
        h('div', {class: 'title-row'}, h('span', {class: 'kind'}, icon('db')), h('h1', {tabindex: '-1'}, catalog), h('code', {class: 'muted'}, 'catalog')),
        h('p', {class: 'sub'}, `${countWords(x.ns.length, 'namespace')} · `,
          x.q.length ? `${plural(x.q.length, 'table')} queued for tonight` : 'nothing you may read is queued for tonight'),
        h('div', {class: 'side'}, copyButton(catalog, 'Copy name'), copyLinkButton())),
      tabsNav('Catalog', [['', 'Contents', x.ns.length], ['properties', 'Properties'], ['governance', 'Governance'], ['grants', 'Grants'],
        ['audit', 'Audit']], key,
      k => ({governance: pageHref('governance', catalog), grants: pageHref('governance', catalog, 'grants'),
        audit: pageHref('audit', catalog)})[k] || catalogHref(catalog, k || null)),
      key === null ? h('div', {class: 'empty'}, h('h3', null, 'A catalog has no such tab'))
        : key === 'properties' ? loading(() => catalogProperties(catalog)) : contentsTable(catalog, [], x),
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
    h('div', {class: 'stack'}, h('div', {class: 'notice'}, icon('info'), h('span', null, h('b', null, 'Set in the deployment\'s configuration.'),
      ' The Iceberg REST API has no catalog properties of its own, and the console sets none. A catalog\'s owner and '
      + 'classification are on its ', h('a', {href: pageHref('governance', catalog, 'holds')}, 'governance'),
      ', and how its tables are compacted, expired and cleaned is its ',
      h('a', {href: pageHref('maintenance', catalog), 'data-act': 'policy'}, 'maintenance policy'),
      '. The profile\'s keys and role are never shown.'))));
}

// ---- a namespace ----

const NS_TABS = ['', 'properties', 'governance', 'grants', 'audit'];

function namespacePage(target, tab) {
  const main = h('div', {class: 'nc-main', id: 'namespace-page'});
  const levels = target.namespace;
  const dotted = levels.join('.');
  const head = h('div', {class: 'ph'}, crumbsFor(target.catalog, levels.slice(0, -1), levels[levels.length - 1]),
    h('div', {class: 'title-row'}, h('span', {class: 'kind'}, icon('ns')), h('h1', {tabindex: '-1'}, levels[levels.length - 1]),
      h('code', {class: 'muted'}, dotted)),
    h('p', {class: 'sub', 'data-sub': ''}, ''),
    h('div', {class: 'side'}, copyButton(dotted, 'Copy path'), copyLinkButton()));
  const key = NS_TABS.includes(tab || '') ? tab || '' : null;
  const body = h('div', {class: 'tab-body'});
  main.append(head, tabsNav('Namespace', [['', 'Contents'], ['properties', 'Properties'], ['governance', 'Governance'],
    ['grants', 'Grants'], ['audit', 'Audit']], key, k => objectHref(target, k || null)), body);
  const sub = $('[data-sub]', head);
  const reload = () => fill(body, namespaceTab(target, key, sub, reload));
  reload();
  return main;
}

function namespaceTab(target, key, sub, reload) {
  const levels = target.namespace;
  if (key === null) return h('div', {class: 'empty'}, h('h3', null, 'A namespace has no such tab'));
  if (key === 'governance') return loading(() => governanceTab(target, reload));
  if (key === 'grants') return grantsTab(target);
  if (key === 'audit') return auditTab(target);
  if (key === 'properties') return loading(() => propertiesTab(target, reload));
  return loading(async () => {
    const [owner, x] = await Promise.all([api.governance(target).then(g => (g.effective || {}).owner, () => null),
      contents(target.catalog, levels)]);
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

/** A namespace's properties: its one setting, the comment, and every property read-only (item 141). */
async function propertiesTab(target, reload) {
  const ns = await api.namespace(target.catalog, target.namespace);
  return settingsTab({target, settings: NAMESPACE_SETTINGS, props: ns.properties || {}, ctx: {}, reload,
    needs: 'the properties update an engine sends',
    write: (updates, removals) => api.namespaceProperties(target.catalog, target.namespace, updates, removals)});
}

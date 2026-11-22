// The catalog's browse pages: the tree on the left, and on the right the catalog, a namespace, a table or a view.
// A namespace has Contents, Properties (Iceberg REST), Governance, Grants and Audit; the catalog's own Governance,
// Grants and Audit are the Governance and Audit pages. Waiting on other items, and left out until they land: the
// catalog's properties (131); each row's governance, snapshots and last commit in Contents (127, 128).

import * as api from './api.js';
import {$, badge, code, copyButton, copyText, dialogForm, field, fill, granted, h, icon, load, muted, openDialog, pill,
  plural, tabsNav, tbl, text} from './dom.js';
import {catalogHref, objectHref, pageHref} from './route.js';
import {queue, queueOf} from './queue.js';
import {reveal, treeOf} from './tree.js';
import {auditTab, governanceTab, grantsTab} from './panels.js';
import {tablePage, viewPage} from './table.js';

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
  else if (r.k === 'catalog') main = catalogPage(r.c);
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

async function contents(catalog, levels) {
  const [ns, tables, views, q] = await Promise.all([api.namespaces(catalog, levels), levels.length ? api.tables(catalog, levels) : [],
    levels.length ? api.views(catalog, levels) : [], queue().catch(() => [])]);
  return {ns, tables: tables.map(t => t.name), views: views.map(v => v.name), q: queueOf(q, catalog)};
}

function contentsTable(catalog, levels, x) {
  const rows = [
    ...x.ns.map(n => h('tr', {'data-kind': 'namespace', 'data-name': n[n.length - 1]},
      h('td', {class: 'name'}, h('a', {href: objectHref({catalog, kind: 'namespace', namespace: n})}, n[n.length - 1])),
      h('td', null, badge([icon('ns'), 'namespace'])),
      h('td', null, queuePill(queuedUnder(x.q, n))))),
    ...x.tables.map(name => {
      const mine = x.q.filter(r => r.name === name && r.namespace.join('.') === levels.join('.'));
      return h('tr', {'data-kind': 'table', 'data-name': name},
        h('td', {class: 'name'}, h('a', {href: objectHref({catalog, kind: 'table', namespace: levels, name})}, name)),
        h('td', null, badge([icon('table'), 'table'])),
        h('td', null, mine.length ? h('a', {href: objectHref({catalog, kind: 'table', namespace: levels, name}, 'health')}, queuePill(mine))
          : muted('–')));
    }),
    ...x.views.map(name => h('tr', {'data-kind': 'view', 'data-name': name},
      h('td', {class: 'name'}, h('a', {href: objectHref({catalog, kind: 'view', namespace: levels, name})}, name)),
      h('td', null, badge([icon('view'), 'view'])), h('td', null, muted('–')))),
  ];
  if (!rows.length) return h('div', {class: 'empty'}, h('h3', null, levels.length ? 'Nothing in this namespace' : 'No namespace in this catalog'),
    h('p', null, 'Or nothing you may describe: the catalog lists only what your groups may see.'));
  return h('div', {class: 'panel', id: 'contents'}, tbl('contents stack-line', ['Name', 'Kind', 'Tonight\'s queue'], rows),
    h('p', {class: 'fine'}, 'Only what your groups may describe is listed. A table\'s health, governance and snapshots are on its own page.'));
}

const countWords = (n, w) => n ? plural(n, w) : `no ${w}s`;

// ---- the catalog ----

function catalogPage(catalog) {
  const main = h('div', {class: 'nc-main', id: 'catalog-page'});
  load(main, async () => {
    const x = await contents(catalog, []);
    return [
      h('div', {class: 'ph'},
        h('div', {class: 'title-row'}, h('span', {class: 'kind'}, icon('db')), h('h1', {tabindex: '-1'}, catalog), h('code', {class: 'muted'}, 'catalog')),
        h('p', {class: 'sub'}, `${countWords(x.ns.length, 'namespace')} · `,
          x.q.length ? `${plural(x.q.length, 'table')} queued for tonight` : 'nothing you may read is queued for tonight'),
        h('div', {class: 'side'}, copyButton(catalog, 'Copy name'), copyLinkButton())),
      tabsNav('Catalog', [['', 'Contents', x.ns.length], ['governance', 'Governance'], ['grants', 'Grants'], ['audit', 'Audit']], '',
        k => ({governance: pageHref('governance', catalog, 'holds'), grants: pageHref('governance', catalog, 'grants'),
          audit: pageHref('audit', catalog)})[k] || catalogHref(catalog)),
      contentsTable(catalog, [], x),
    ];
  });
  return main;
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
    const [ns, x] = await Promise.all([api.namespace(target.catalog, levels), contents(target.catalog, levels)]);
    const owner = (ns.properties || {}).owner;
    fill(sub, `${countWords(x.tables.length, 'table')} · ${countWords(x.views.length, 'view')} · `
      + `${countWords(x.ns.length, 'child namespace')}`, owner ? [' · owner ', h('b', null, owner)] : null);
    return contentsTable(target.catalog, levels, x);
  });
}

function loading(loader) {
  const box = h('div', {class: 'tab-body'});
  load(box, loader);
  return box;
}

async function propertiesTab(target, reload) {
  const ns = await api.namespace(target.catalog, target.namespace);
  const props = ns.properties || {};
  const keys = Object.keys(props).sort();
  return h('div', {class: 'panel', id: 'properties'}, h('h2', null, 'Properties ', h('span', {class: 'meta'}, 'Iceberg REST, on the namespace')),
      keys.length ? tbl('props-tbl stack-line', ['Key', 'Value', ''], keys.map(k => h('tr', {'data-key': k},
        h('td', {class: 'name'}, k), h('td', {class: 'name wrap'}, props[k]),
        h('td', null, h('button', {class: 'btn sm ghost', type: 'button', onclick: () => removeProperty(target, k, reload)}, 'Remove')))))
        : h('p', {class: 'muted'}, 'No property set on this namespace.'),
      h('div', {class: 'refusal-acts'},
        h('button', {class: 'btn sm', type: 'button', onclick: () => setProperty(target, reload)}, 'Set a property'),
        h('span', {class: 'fine'}, 'Needs ', code('modify'), ' here.')));
}

function setProperty(target, reload) {
  openDialog(`Set a property on ${target.namespace.join('.')}`, dialogForm([
    field('Key', text('key', 'such as comment')),
    field('Value', text('value', '')),
  ], 'Set the property', async (form, out) => {
    const k = form.elements.key.value.trim();
    if (!k) {
      fill(out, h('p', {class: 'fine grant-hint'}, 'Name the key.'));
      return;
    }
    const answer = await api.namespaceProperties(target.catalog, target.namespace, {[k]: form.elements.value.value}, []);
    fill(out, granted(`Set ${k}`, answer));
    reload();
  }));
}

function removeProperty(target, k, reload) {
  openDialog(`Remove ${k}`, dialogForm(h('p', null, 'The property ', code(k), ' leaves ', code(target.namespace.join('.')), '.'),
    'Remove the property', async (form, out) => {
      const answer = await api.namespaceProperties(target.catalog, target.namespace, {}, [k]);
      fill(out, granted(`Removed ${k}`, answer));
      reload();
    }));
}

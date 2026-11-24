// The catalog's tree, on the left of every browse page: namespaces, tables and views, each level listed from
// Iceberg REST when it is first opened. A table in tonight's queue carries a mark, and so does whatever a hold, a DR
// freeze or a regulated flag is set on, from the catalog's governance summary and holds list (item 128). Read through
// a catalog branch or tag (r=, item 109), the tree lists each level through catalog@ref, names the ref beside the
// catalog, and its links keep it (item 173); each ref has a tree of its own, which marks the tables the branch changed
// and the namespaces above them (backlog 201).

import * as api from './api.js';
import {$, failure, h, icon} from './dom.js';
import {SEP, catalogHref, objectHref, remember, warehouseOf} from './route.js';
import {queue, queueOf} from './queue.js';
import {governanceMarks} from './panels.js';

const trees = new Map();

const keyOf = levels => levels.join(SEP);

/** The tree of one catalog as a ref reads it (main when `ref` is null), made once and kept while the console stays there. */
export function treeOf(catalog, ref = null) {
  const id = catalog + (ref ? '@' + ref : '');
  let st = trees.get(id);
  if (st) return st;
  const input = h('input', {type: 'text', 'aria-label': 'Filter names in the tree', placeholder: 'Filter names in the tree',
    spellcheck: 'false', autocomplete: 'off'});
  const ul = h('ul', {'aria-label': 'Namespaces and tables'});
  const el = h('div', {class: 'tree', 'data-catalog': catalog, 'data-ref': ref || null}, h('div', {class: 'tree-in'},
    h('label', {class: 'field'}, icon('search'), input), ul,
    h('p', {class: 'tree-key'}, [['m-bad', 'equality deletes'], ['m-warn', 'small files or deletes'], ['m-held', 'held'],
      ['m-frozen', 'DR freeze'], ['m-reg', 'regulated'], ref ? ['m-chg', `changed on @${ref}`] : null].filter(Boolean)
      .map(([c, w]) => h('span', null, h('i', {class: c}), w)))));
  st = {catalog, ref, el, ul, input, got: new Map(), asked: new Map(), open: new Set(), cur: 'catalog', marks: new Map(), gov: new Map(),
    changed: new Set()};
  trees.set(id, st);
  input.addEventListener('input', () => draw(st));
  queue().then(rows => {
    for (const r of queueOf(rows, catalog)) st.marks.set(keyOf([...r.namespace, r.name]), r.assessment.reasons || []);
    draw(st);
  }, () => {});
  ask(st, []);
  if (ref) {
    api.ref(catalog, ref).then(x => {
      for (const t of x.changed || []) st.changed.add(keyOf([...t.namespace, t.name]));
      draw(st);
    }, () => {});
  }
  return st;
}

/** A dot on a table the branch read changed, or on a namespace above one. */
function changedOf(st, key, isNamespace) {
  const hit = isNamespace ? [...st.changed].some(k => k.startsWith(key + SEP)) : st.changed.has(key);
  const words = isNamespace ? `a table beneath it changed on @${st.ref}` : `changed on @${st.ref}`;
  return hit ? h('span', {class: 'tree-chg', 'data-changed': '', title: words}, h('span', {class: 'vh'}, words)) : null;
}

/** Lists one level: its child namespaces, and its tables and views; the catalog's own level has namespaces only. */
function ask(st, levels) {
  const key = keyOf(levels);
  if (!st.asked.has(key)) {
    const c = st.catalog;
    const wh = warehouseOf({catalog: c, ref: st.ref});
    st.asked.set(key, Promise.all([api.namespaces(wh, levels), levels.length ? api.tables(wh, levels) : [],
      levels.length ? api.views(wh, levels) : []])
      .then(([ns, tables, views]) => {
        const kids = {ns: ns.map(x => x.slice()), tables: tables.map(t => t.name), views: views.map(v => v.name)};
        for (const n of kids.ns) remember({catalog: c, kind: 'namespace', namespace: n});
        for (const n of kids.tables) remember({catalog: c, kind: 'table', namespace: levels, name: n});
        for (const n of kids.views) remember({catalog: c, kind: 'view', namespace: levels, name: n});
        st.got.set(key, kids);
      }, error => st.got.set(key, {error}))
      .then(() => draw(st)));
  }
  return st.asked.get(key);
}

/** Opens the path down to `target` and marks it as the page shown. */
export async function reveal(st, target) {
  st.cur = target.kind === 'catalog' ? 'catalog' : target.kind + ':' + keyOf([...target.namespace, ...(target.name ? [target.name] : [])]);
  const upTo = target.kind === 'namespace' ? target.namespace.length : target.kind === 'catalog' ? 0 : target.namespace.length;
  for (let i = 1; i <= upTo; i++) st.open.add(keyOf(target.namespace.slice(0, i)));
  draw(st);
  governanceMarks(st.catalog).then(gov => {
    if (gov === st.gov) return;
    st.gov = gov;
    draw(st);
  });
  for (let i = 0; i <= upTo; i++) await ask(st, target.namespace.slice(0, i));
  const cur = $('[aria-current="true"]', st.el);
  if (cur && cur.scrollIntoView) cur.scrollIntoView({block: 'nearest'});
}

/**
 * The marks of a node: tonight's queue for a table, or for everything queued beneath a namespace; and a hold, a DR
 * freeze or a regulated flag set on the node itself. `node` is the tree's key for it, such as `table:a.b.t`.
 */
function markOf(st, key, isNamespace, node) {
  let eq = false;
  let other = false;
  if (key !== null) {
    for (const [k, reasons] of st.marks) {
      if (isNamespace ? !k.startsWith(key + SEP) : k !== key) continue;
      if (reasons.includes('equality-deletes')) eq = true;
      if (reasons.some(r => r !== 'equality-deletes')) other = true;
    }
  }
  const g = st.gov.get(node) || {};
  const words = [eq && 'queued with equality deletes', other && 'queued with small files or deletes', g.held && 'held',
    g.frozen && 'DR freeze', g.regulated && 'regulated'].filter(Boolean);
  if (!words.length) return null;
  const label = words.join(', ').replace(/^./, c => c.toUpperCase()) + '.';
  return h('span', {class: 'mk', role: 'img', 'aria-label': label, title: label},
    eq ? h('i', {class: 'm-bad'}) : null, other ? h('i', {class: 'm-warn'}) : null, g.held ? h('i', {class: 'm-held'}) : null,
    g.frozen ? h('i', {class: 'm-frozen'}) : null, g.regulated ? h('i', {class: 'm-reg'}) : null);
}

function draw(st) {
  const f = st.input.value.trim().toLowerCase();
  const c = st.catalog;
  const ref = st.ref || undefined;
  const items = [h('li', null, h('a', {class: 'node', href: catalogHref(c, null, ref), 'aria-current': st.cur === 'catalog' ? 'true' : null,
    'data-node': 'catalog'}, h('span', {class: 'tw'}), icon('db', 'ic ico-n'), h('span', {class: 'nm'}, c,
    ref ? h('span', {class: 'pick-ref', 'data-tree-ref': ref}, '@' + ref) : null),
    markOf(st, null, false, 'catalog')))];
  const nameHit = name => !f || name.toLowerCase().includes(f);
  const deepHit = levels => {
    if (nameHit(levels[levels.length - 1])) return true;
    const kids = st.got.get(keyOf(levels));
    if (!kids || kids.error) return false;
    return kids.tables.some(nameHit) || kids.views.some(nameHit) || kids.ns.some(deepHit);
  };
  const walk = (levels, depth) => {
    const kids = st.got.get(keyOf(levels));
    const d = 'd' + Math.min(depth, 5);
    if (!kids) {
      items.push(h('li', {class: 'tree-err'}, h('span', {class: 'node ' + d}, h('span', {class: 'loading-line'}, 'Loading…'))));
      return;
    }
    if (kids.error) {
      items.push(h('li', {class: 'tree-err'}, failure(kids.error, {compact: true})));
      return;
    }
    for (const n of kids.ns) {
      if (f && !deepHit(n)) continue;
      const key = keyOf(n);
      const open = f ? st.got.has(key) : st.open.has(key);
      const tw = h('span', {class: 'tw', 'aria-hidden': 'true'}, open ? '▾' : '▸');
      tw.addEventListener('click', e => {
        e.preventDefault();
        e.stopPropagation();
        if (st.open.has(key)) st.open.delete(key);
        else {
          st.open.add(key);
          ask(st, n);
        }
        draw(st);
      });
      items.push(h('li', null, h('a', {class: 'node ' + d, href: objectHref({catalog: c, kind: 'namespace', namespace: n, ref}),
        'aria-current': st.cur === 'namespace:' + key ? 'true' : null, 'aria-expanded': String(open), 'data-node': 'namespace:' + n.join('.')},
      tw, icon('ns', 'ic ico-n'), h('span', {class: 'nm'}, n[n.length - 1]), changedOf(st, key, true),
      markOf(st, key, true, 'namespace:' + n.join('.')))));
      if (open) walk(n, depth + 1);
    }
    for (const [kind, names] of [['table', kids.tables], ['view', kids.views]]) {
      for (const name of names) {
        if (!nameHit(name)) continue;
        const key = keyOf([...levels, name]);
        items.push(h('li', null, h('a', {class: 'node ' + d, href: objectHref({catalog: c, kind, namespace: levels, name, ref}),
          'aria-current': st.cur === kind + ':' + key ? 'true' : null, 'data-node': kind + ':' + [...levels, name].join('.')},
        h('span', {class: 'tw'}), icon(kind, 'ic ' + (kind === 'view' ? 'ico-v' : 'ico-t')), h('span', {class: 'nm'}, name),
        kind === 'table' ? changedOf(st, key, false) : null,
        markOf(st, kind === 'table' ? key : null, false, kind + ':' + [...levels, name].join('.')))));
      }
    }
  };
  walk([], 1);
  st.ul.replaceChildren(...items);
  // Go to a path finds a name typed alone in every catalog (backlog 198), so the filter's words go to it (backlog 208).
  if (f && items.length === 1) st.ul.append(h('li', {class: 'tree-none muted'}, `No open namespace in ${c} holds a matching name. Open more of the tree, or use Go to a path.`,
    ' ', h('button', {class: 'btn sm', type: 'button', 'data-act': 'goto', 'data-goto-words': st.input.value.trim()}, icon('search'), 'Go to a path')));
}

/** What the tree has listed beneath a level so far, or null. */
export const listed = (st, levels) => st.got.get(keyOf(levels)) || null;

/** Forgets a catalog's trees, every ref's, so the next look lists them again (after a change beneath them). */
export function forget(catalog) {
  for (const id of [...trees.keys()]) if (id === catalog || id.startsWith(catalog + '@')) trees.delete(id);
}

// The console: sign-in, the catalog tree on the left, the selected object on the right.

import * as api from './api.js';
import {backFromSignOut, claims, finishSignIn, signedIn, signedOut, signOut, startSignIn, useToken, whenSignedOut}
  from './auth.js';
import {fill, h, failure} from './dom.js';
import {detail} from './detail.js';
import {healthPage} from './health.js';

const SEP = '\u001f';
const main = document.getElementById('main');
const catalogPick = document.getElementById('catalog-pick');
const catalogSelect = document.getElementById('catalog-select');
const who = document.getElementById('who');
const signOutButton = document.getElementById('sign-out');
const healthButton = document.getElementById('health-link');

let config = {'dev-token-login': false, oidc: null};

// ---- theme: light, dark, or the system's; the choice is a display preference, not a credential ----

function applyTheme(theme) {
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
  else delete document.documentElement.dataset.theme;
}

function storedTheme() {
  try {
    return localStorage.getItem('nuna.theme');
  } catch {
    return null;
  }
}

applyTheme(storedTheme());
document.getElementById('theme-toggle').addEventListener('click', () => {
  const dark = document.documentElement.dataset.theme
    ? document.documentElement.dataset.theme === 'dark'
    : matchMedia('(prefers-color-scheme: dark)').matches;
  const next = dark ? 'light' : 'dark';
  applyTheme(next);
  try {
    localStorage.setItem('nuna.theme', next);
  } catch {
    // a display preference only
  }
});

// ---- where we are: kept in the URL fragment so a view can be reloaded or shared ----

/** {page: health | null, catalog, kind: catalog|namespace|table|view, namespace: [], name, tab} */
function route() {
  const p = new URLSearchParams(location.hash.slice(1));
  const namespace = p.get('ns') ? p.get('ns').split(SEP) : [];
  return {
    page: p.get('p') || null,
    catalog: p.get('c') || null,
    kind: p.get('k') || (namespace.length ? 'namespace' : 'catalog'),
    namespace,
    name: p.get('n') || null,
    tab: p.get('tab') || null,
  };
}

healthButton.addEventListener('click', () => {
  if (location.hash === '#p=health') show();
  else location.hash = '#p=health';
});

export function go(target, tab) {
  const p = new URLSearchParams();
  p.set('c', target.catalog);
  if (target.kind !== 'catalog') p.set('k', target.kind);
  if (target.namespace && target.namespace.length) p.set('ns', target.namespace.join(SEP));
  if (target.name) p.set('n', target.name);
  if (tab) p.set('tab', tab);
  const hash = '#' + p.toString();
  if (location.hash !== hash) location.hash = hash;
  else show();
}

/** Records the selected tab without re-rendering. */
export function rememberTab(tab) {
  const p = new URLSearchParams(location.hash.slice(1));
  if (p.get('tab') === tab) return;
  p.set('tab', tab);
  history.replaceState(null, '', '#' + p.toString());
}

// ---- sign-in ----

whenSignedOut(reason => signInScreen(reason));

// Forgets the token at once, then ends the IdP's session: the IdP sends the browser back here, to boot().
signOutButton.addEventListener('click', async () => {
  location.hash = '';
  signOutButton.hidden = true;
  fill(main, h('p', {class: 'loading'}, 'Signing out…'));
  const message = await signOut(config.oidc);
  if (message) signInScreen(message);
});

function signInScreen(message) {
  catalogPick.hidden = true;
  signOutButton.hidden = true;
  healthButton.hidden = true;
  layoutShown = false;
  fill(who);
  const parts = [h('h1', null, 'Sign in'),
    message ? h('p', {class: 'notice'}, message) : null];
  if (config.oidc) {
    const host = new URL(config.oidc.issuer).host;
    const status = h('div');
    parts.push(h('p', null, h('button', {type: 'button', class: 'primary', onclick: async () => {
      fill(status, h('p', {class: 'loading'}, 'Going to ' + host + '…'));
      try {
        await startSignIn(config.oidc);
      } catch (error) {
        fill(status, failure(error));
      }
    }}, 'Sign in with ' + host)), status);
  }
  if (config['dev-token-login']) {
    const box = h('textarea', {rows: 6, spellcheck: 'false', placeholder: 'eyJhbGciOi…', 'aria-label': 'Bearer token'});
    const status = h('div');
    parts.push(h('section', {class: 'dev-login'},
      h('h2', null, 'Development: paste a bearer token'),
      h('p', {class: 'muted'}, 'Shown because this server sets nuna.ui.dev-token-login=true. '
        + 'The token is kept in this tab only and sent as a bearer token; the server checks it on every call.'),
      box,
      h('p', null, h('button', {type: 'button', class: 'primary', onclick: () => {
        try {
          useToken(box.value);
          start();
        } catch (error) {
          fill(status, failure(error));
        }
      }}, 'Use this token')), status));
  }
  if (!config.oidc && !config['dev-token-login']) {
    parts.push(h('p', null, 'No sign-in is configured on this server. An operator sets nuna.ui.oidc.issuer '
      + 'and nuna.ui.oidc.client-id (or, for local development only, nuna.ui.dev-token-login=true).'));
  }
  fill(main, h('div', {class: 'signin'}, parts));
}

function showWho() {
  const c = claims() || {};
  const groups = Array.isArray(c.groups) ? c.groups : [];
  const expires = typeof c.exp === 'number' ? new Date(c.exp * 1000) : null;
  fill(who, h('span', {title: 'From the token, as the browser reads it; the server decides what it allows.'},
    // A person's name before an IdP's opaque ids: Keycloak's and Entra ID's sub are not meant to be read.
    h('strong', null, c.preferred_username || c.sub || c.oid || 'signed in'),
    groups.length ? h('span', {class: 'muted'}, ' · ' + groups.join(', ')) : null,
    expires ? h('span', {class: 'muted'}, ' · until ' + expires.toLocaleString()) : null));
  signOutButton.hidden = false;
  healthButton.hidden = false;
}

// ---- the tree ----

const nodes = new Map(); // key -> {expanded, children: {namespaces, tables, views} | null, error, li}
let currentCatalog = null;
/** Whether main holds the tree and pane, rather than the sign-in screen or the health page. */
let layoutShown = false;

function key(levels) {
  return levels.join(SEP);
}

function nodeState(levels) {
  const k = key(levels);
  if (!nodes.has(k)) nodes.set(k, {expanded: false, children: null, error: null});
  return nodes.get(k);
}

async function expand(levels) {
  const state = nodeState(levels);
  state.expanded = true;
  if (!state.children) {
    try {
      const [namespaces, tables, views] = await Promise.all([
        api.namespaces(currentCatalog, levels),
        levels.length ? api.tables(currentCatalog, levels) : Promise.resolve([]),
        levels.length ? api.views(currentCatalog, levels) : Promise.resolve([]),
      ]);
      const byName = (a, b) => a.localeCompare(b, undefined, {sensitivity: 'base'});
      state.children = {
        namespaces: namespaces.map(n => n[n.length - 1]).sort(byName),
        tables: tables.map(t => t.name).sort(byName),
        views: views.map(v => v.name).sort(byName),
      };
      state.error = null;
    } catch (error) {
      state.error = error;
    }
  }
}

function selected() {
  return route();
}

function isSelected(kind, levels, name) {
  const r = selected();
  return r.kind === kind && key(r.namespace) === key(levels) && (r.name || null) === (name || null);
}

function leaf(kind, levels, name) {
  const target = {catalog: currentCatalog, kind, namespace: levels, name};
  return h('li', {role: 'treeitem', class: 'leaf'},
    h('button', {type: 'button', class: 'node' + (isSelected(kind, levels, name) ? ' selected' : ''),
      'data-label': name.toLowerCase(), 'aria-label': kind + ' ' + name, onclick: () => go(target)},
    h('span', {class: 'icon icon-' + kind, 'aria-hidden': 'true'}, kind === 'table' ? 'T' : 'V'),
    h('span', {class: 'label'}, name)));
}

function namespaceItem(levels) {
  const state = nodeState(levels);
  const name = levels[levels.length - 1];
  const li = h('li', {role: 'treeitem', 'aria-expanded': String(state.expanded)});
  const toggle = h('button', {type: 'button', class: 'twisty', 'aria-label': (state.expanded ? 'Collapse ' : 'Expand ') + name,
    onclick: async () => {
      if (state.expanded) state.expanded = false;
      else await expand(levels);
      li.replaceWith(namespaceItem(levels));
    }}, state.expanded ? '▾' : '▸');
  li.appendChild(h('div', {class: 'row'}, toggle,
    h('button', {type: 'button', class: 'node' + (isSelected('namespace', levels, null) ? ' selected' : ''),
      'data-label': name.toLowerCase(), title: levels.join('.'),
      onclick: () => go({catalog: currentCatalog, kind: 'namespace', namespace: levels})},
    h('span', {class: 'icon icon-namespace', 'aria-hidden': 'true'}, '▪'),
    h('span', {class: 'label'}, name))));
  if (state.expanded) li.appendChild(children(levels));
  return li;
}

function children(levels) {
  const state = nodeState(levels);
  if (state.error) return h('div', {class: 'tree-error'}, failure(state.error));
  const c = state.children;
  if (!c) return h('p', {class: 'loading'}, 'Loading…');
  const items = [
    ...c.namespaces.map(n => namespaceItem([...levels, n])),
    ...c.tables.map(t => leaf('table', levels, t)),
    ...c.views.map(v => leaf('view', levels, v)),
  ];
  if (!items.length) return h('ul', {role: 'group'}, h('li', {class: 'muted empty'}, 'empty'));
  return h('ul', {role: 'group'}, items);
}

const treeBody = h('div', {class: 'tree-body'});
const filterBox = h('input', {type: 'search', placeholder: 'Filter loaded names', 'aria-label': 'Filter the tree',
  oninput: () => applyFilter()});

function applyFilter() {
  const text = filterBox.value.trim().toLowerCase();
  for (const li of treeBody.querySelectorAll('li')) li.classList.remove('filtered-out');
  if (!text) return;
  // Hide an item unless it or something below it matches.
  const items = [...treeBody.querySelectorAll('li')].reverse();
  for (const li of items) {
    const own = li.querySelector(':scope > .node, :scope > .row > .node');
    const matches = own && own.dataset.label.includes(text);
    const below = [...li.querySelectorAll(':scope > ul > li')].some(child => !child.classList.contains('filtered-out'));
    if (!matches && !below) li.classList.add('filtered-out');
  }
}

async function renderTree() {
  fill(treeBody, h('div', {class: 'row root-row'},
    h('button', {type: 'button', class: 'node' + (isSelected('catalog', [], null) ? ' selected' : ''),
      'aria-label': 'catalog ' + currentCatalog, onclick: () => go({catalog: currentCatalog, kind: 'catalog', namespace: []})},
    h('span', {class: 'icon icon-catalog', 'aria-hidden': 'true'}, '◆'),
    h('span', {class: 'label'}, currentCatalog))),
  children([]));
  applyFilter();
}

/** Opens every namespace above the selection, so a deep link shows where it is. */
async function reveal(levels) {
  await expand([]);
  for (let i = 1; i <= levels.length; i++) await expand(levels.slice(0, i));
}

// ---- layout ----

const tree = h('nav', {class: 'tree', 'aria-label': 'Catalog tree'},
  h('div', {class: 'tree-head'}, filterBox,
    h('button', {type: 'button', class: 'ghost small', title: 'Reload the tree', onclick: async () => {
      nodes.clear();
      await reveal(route().namespace);
      await renderTree();
    }}, 'Reload')),
  treeBody);
const pane = h('section', {class: 'pane', 'aria-live': 'polite'});

async function show() {
  if (!signedIn()) {
    signInScreen(claims() ? 'The token has expired. Sign in again.' : null);
    return;
  }
  const r = route();
  if (r.page === 'health') {
    layoutShown = false;
    healthButton.setAttribute('aria-current', 'page');
    fill(main, healthPage());
    return;
  }
  healthButton.removeAttribute('aria-current');
  if (!r.catalog || r.catalog !== currentCatalog || !layoutShown) {
    await chooseCatalog(r.catalog);
    return;
  }
  await reveal(r.namespace);
  await renderTree();
  detail(pane, r);
}

async function chooseCatalog(wanted) {
  let names;
  try {
    names = await api.catalogs();
  } catch (error) {
    fill(main, failure(error));
    return;
  }
  if (!names.length) {
    fill(main, h('p', null, 'No catalog to show: this deployment declares none, or you hold no privilege on any.'));
    return;
  }
  const chosen = names.find(n => n.toLowerCase() === (wanted || '').toLowerCase()) || names[0];
  fill(catalogSelect, names.map(n => h('option', {value: n, selected: n === chosen}, n)));
  catalogPick.hidden = false;
  if (currentCatalog !== chosen) {
    currentCatalog = chosen;
    nodes.clear();
  }
  fill(main, h('div', {class: 'layout'}, tree, pane));
  layoutShown = true;
  if (route().catalog !== chosen) go({catalog: chosen, kind: 'catalog', namespace: []});
  else show();
}

catalogSelect.addEventListener('change', () => go({catalog: catalogSelect.value, kind: 'catalog', namespace: []}));
window.addEventListener('hashchange', () => {
  if (signedIn()) show();
});

async function start() {
  showWho();
  currentCatalog = null;
  layoutShown = false;
  await show();
}

async function boot() {
  const signingOut = backFromSignOut();
  try {
    const response = await fetch('/ui/config.json', {cache: 'no-store', credentials: 'omit'});
    config = await response.json();
  } catch (error) {
    fill(main, failure(error));
    return;
  }
  if (config.oidc) {
    try {
      await finishSignIn(config.oidc);
    } catch (error) {
      signInScreen(error.message);
      return;
    }
  }
  if (signedIn()) await start();
  else signedOut(signingOut ? 'Signed out.' : claims() ? 'The token has expired. Sign in again.' : null);
}

boot();

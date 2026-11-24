// The console: sign-in, the shell (top bar, menu, catalog picker, theme, Go to a path) and the router. Each page
// lives in its own module and asks the catalog's own routes for what it shows; the server decides every permission.

import {backFromSignOut, claims, finishSignIn, signedIn, signedOut, signOut, startAgain, startSignIn, useToken, whenSignedOut}
  from './auth.js';
import * as api from './api.js';
import {auditOpen, groupWords, keepAccess, landingCatalogs, operates, readsData} from './access.js';
import {$, $$, at, failure, fill, fmt, h, icon, pill, plural, techDetails} from './dom.js';
import {href, objectHref, pageHref, parse, remembered, SEP, warehouseOf} from './route.js';
import {estate, keyOfRow, queue, queueOf, REASONS, targetOfRow} from './queue.js';
import {overviewPage} from './overview.js';
import {maintenancePage} from './maintenance.js';
import {healthPage} from './health.js';
import {governancePage} from './governance.js';
import {auditPage} from './audit.js';
import {browse, notFound} from './browse.js';
import {clearReading, readingControl} from './refs.js';

const root = document.documentElement;
const consoleEl = $('#console');
const slot = $('#slot');
const navEl = $('#nav');
const signinEl = $('#signin');
const gotoDlg = $('#goto');
const gotoIn = $('#goto-in');
const gotoList = $('#goto-list');
const gotoH = $('#goto-h');

let config = {'dev-token-login': false, oidc: null};
let catalogs = null;
let cat = null;
let prev = null;

// ---- theme: light, dark, or the system's; the choice is kept in this browser ----

const THEME = 'nuna.theme';
let theme = 'auto';
try {
  theme = ['light', 'dark', 'auto'].includes(localStorage.getItem(THEME)) ? localStorage.getItem(THEME) : 'auto';
} catch {
  theme = 'auto';
}

function applyTheme() {
  if (theme === 'auto') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', theme);
  for (const b of $$('[data-theme-pick]')) b.setAttribute('aria-pressed', String(b.dataset.themePick === theme));
}

function pickTheme(value) {
  theme = value;
  try {
    if (value === 'auto') localStorage.removeItem(THEME);
    else localStorage.setItem(THEME, value);
  } catch {
    // the choice then lasts this page only
  }
  applyTheme();
}

// ---- the catalog a sign-in opens: the one used last in this browser, else the one with the most tables the caller
// may read, else the first (Atelier's review, F2). Kept per viewer and per person signed in, so one who signs in
// after another in the same browser lands on their own (backlog 200); a convenience: it may come back empty. ----

const last = () => 'nuna.catalog.' + ((claims() || {}).sub || '');

function lastCatalog() {
  try {
    return localStorage.getItem(last());
  } catch {
    return null;
  }
}

function keepCatalog(c) {
  try {
    if (c && c !== lastCatalog()) localStorage.setItem(last(), c);
  } catch {
    // remembered for this page only
  }
}

// The audit tables' catalog is never a landing: an auditor's work is on Audit (Atelier's re-walk, 2026-11-23).
async function landingCatalog(all) {
  const names = landingCatalogs(all);
  const kept = lastCatalog();
  if (kept && names.includes(kept)) return kept;
  const counts = new Map(names.map(n => [n, 0]));
  if (names.some(auditOpen)) {
    // Home is Audit (homeHref): the catalog with the most records in the last day, not the first name (Atelier's
    // re-walk after phase 2: gary landed on an empty catalog's Audit).
    await Promise.all(names.filter(auditOpen).map(n => api.auditSummary(n).then(sum => {
      counts.set(n, Object.values(sum['by-type'] || {}).reduce((s, x) => s + Number(x || 0), 0));
    }, () => {})));
  } else {
    let rows = [];
    try {
      rows = await estate();
    } catch {
      // the estate only ranks the catalogs; without it the first is taken
    }
    for (const t of rows) if (counts.has(t.catalog)) counts.set(t.catalog, counts.get(t.catalog) + 1);
  }
  return names.reduce((best, n) => counts.get(n) > counts.get(best) ? n : best, names[0]);
}

/** Where a caller's work is (items 184 and 200): Audit for auditors and grant admins, Service for operators, Overview
 * for those who read data, in that order (Atelier, 2026-11-23), else the catalog. */
function homeHref(c) {
  if (auditOpen(c)) return pageHref('audit', c);
  if (operates()) return pageHref('service');
  if (readsData()) return pageHref('overview', c);
  return href({c});
}

/** A page the caller's groups cannot use, reached by a link: said in plain words, and the catalog is never asked. */
function closedPage(title, words) {
  return h('div', {class: 'nc-main', id: 'closed-page'}, h('div', {class: 'ph'}, h('h1', {tabindex: '-1'}, title)),
    h('div', {class: 'empty', 'data-closed': ''}, h('h3', null, words), h('p', null, `Your groups: ${groupWords()}. Ask a grant `
      + 'admin if your work needs it.')));
}

// ---- the menu: a rail on a wide screen, a drawer on a phone ----

let railPick = null;
const narrow = () => consoleEl.clientWidth <= 760;

function syncMenuButton() {
  if (narrow()) return;
  const rail = consoleEl.classList.contains('rail');
  const btn = $('.nc-menu');
  btn.setAttribute('aria-expanded', String(!rail));
  btn.setAttribute('aria-label', rail ? 'Show the menu labels' : 'Fold the menu to icons');
}

function menu(open) {
  const btn = $('.nc-menu');
  if (!narrow() && !consoleEl.classList.contains('menu-open')) {
    if (open === false) return;
    railPick = !consoleEl.classList.contains('rail');
    consoleEl.classList.toggle('rail', railPick);
    syncMenuButton();
    return;
  }
  const on = open ?? !consoleEl.classList.contains('menu-open');
  consoleEl.classList.toggle('menu-open', on);
  $('.scrim').hidden = !on;
  btn.setAttribute('aria-expanded', String(on));
  btn.setAttribute('aria-label', on ? 'Close the menu' : 'Open the menu');
  if (on) ($('a[aria-current="page"]', navEl) || $('a:not([hidden])', navEl)).focus();
}

function catMenu(open) {
  const btn = $('#catalog-pick');
  const m = $('#cat-menu');
  const on = open ?? m.hidden;
  m.hidden = !on;
  btn.setAttribute('aria-expanded', String(on));
  if (on) ($('[aria-checked="true"]', m) || $('button', m)).focus();
}

function closeMenus() {
  catMenu(false);
  if (consoleEl.classList.contains('menu-open')) menu(false);
}

function setChrome(navKey, ref) {
  consoleEl.classList.toggle('rail', !!railPick);
  syncMenuButton();
  // Service is the whole deployment's, so it offers no catalog to pick (Atelier's re-walk, 2026-11-23).
  $('.pick-wrap').hidden = navKey === 'service';
  // The catalog chip names the catalog; the Reading control beside it names the ref read (backlog 201).
  for (const b of $$('[data-cat]')) fill(b, cat || '–');
  for (const b of $$('[data-cat-pick]')) b.setAttribute('aria-checked', String(b.dataset.catPick === cat));
  // The ref read goes along to the pages that show main only, which say so beside it (Atelier, 2026-11-23).
  const hrefs = {overview: href({page: 'overview', c: cat, r: ref}), catalog: href({c: cat, r: ref}),
    maintenance: href({page: 'maintenance', c: cat, r: ref}), governance: href({page: 'governance', c: cat, r: ref}),
    audit: href({page: 'audit', c: cat, r: ref}), service: pageHref('service')};
  // Only what the caller's groups can use is drawn (item 184): Overview and Maintenance are about the tables they may
  // read, Audit is for audit readers and those who manage the catalog's grants, Service for platform operators.
  const open = {overview: readsData(), catalog: true, maintenance: readsData(), governance: true, audit: !!cat && auditOpen(cat),
    service: operates()};
  for (const a of $$('a[data-k]', navEl)) {
    const k = a.dataset.k;
    a.hidden = !open[k];
    a.href = hrefs[k];
    a.setAttribute('aria-label', $('.nl', a).textContent);
    if (k === navKey) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  }
  $('[data-brand]').href = cat ? homeHref(cat) : pageHref('service');
  const count = $('.count', navEl);
  queue().then(rows => {
    const n = queueOf(rows, cat).length;
    count.textContent = String(n);
    count.hidden = !n;
    count.setAttribute('aria-label', `${n} queued for tonight`);
  }, () => { count.hidden = true; });
}

/** Where switching to another catalog goes: the same page there; a table's or namespace's page goes to the catalog's. */
function switchHref(c) {
  const r = parse();
  if (r.page === 'service') return pageHref('overview', c);
  if (r.page) return pageHref(r.page, c, r.page === 'governance' ? r.tab : null);
  return href({c});
}

// ---- who is signed in ----

function showWho() {
  const c = claims() || {};
  const name = c.preferred_username || c.sub || c.oid || 'signed in';
  const groups = Array.isArray(c.groups) ? c.groups : [];
  const until = typeof c.exp === 'number' ? new Date(c.exp * 1000) : null;
  const initials = String(name).split(/[^A-Za-z0-9]+/).filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase() || '?';
  for (const n of $$('[data-initials]')) n.textContent = initials;
  const who = $('#who');
  who.title = (until ? 'Signed in until ' + at(until) + '. ' : '')
    + 'The catalog checks what you may do on every request.';
  fill($('.who-text', who), h('b', null, h('strong', null, name)), groups.length ? h('span', {class: 'muted'}, ' · ' + groups.join(', ')) : null);
  for (const p of $$('p[data-who]', navEl)) {
    p.textContent = `Signed in as ${name}${groups.length ? ' · ' + groups.join(', ') : ''}${until ? ' · until ' + at(until) : ''}`;
  }
}

function clearWho() {
  for (const n of $$('[data-initials], [data-who]')) n.replaceChildren();
}

// ---- sign-in ----

const brand = cls => h('span', {class: 'nc-brand' + (cls ? ' ' + cls : ''), 'aria-hidden': 'true'}, peak(), 'nuna');

function peak() {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('class', 'peak');
  svg.setAttribute('viewBox', '0 0 24 18');
  svg.setAttribute('aria-hidden', 'true');
  for (const [cls, d] of [['rock', 'M3 14 9.5 4l3.2 4.8 2.3-2.8L21 14'], ['ice', 'M1 14h22']]) {
    const p = document.createElementNS(NS, 'path');
    p.setAttribute('class', cls);
    p.setAttribute('d', d);
    svg.append(p);
  }
  return svg;
}

function horizon() {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('class', 'horizon');
  svg.setAttribute('viewBox', '0 0 600 150');
  svg.setAttribute('preserveAspectRatio', 'xMidYMax slice');
  svg.setAttribute('aria-hidden', 'true');
  for (const [cls, d] of [['sea', 'M0 92 H600 V150 H0 Z'], ['berg', 'M330 92 L392 30 L414 52 L432 38 L480 92 Z'],
    ['berg-under', 'M352 92 L458 92 L440 128 L372 132 Z'], ['wl', 'M0 92 H600']]) {
    const p = document.createElementNS(NS, 'path');
    p.setAttribute('class', cls);
    p.setAttribute('d', d);
    svg.append(p);
  }
  return svg;
}

const CLAIM = 'The Iceberg catalog that keeps every table in shape and every change on record.';

/** Forgets a half-finished sign-in and opens the sign-in screen afresh (backlog 193). */
const startAgainButton = () => h('button', {class: 'btn sm', type: 'button', 'data-act': 'start-again', onclick: () => {
  startAgain();
  location.replace(location.pathname);
}}, 'Start again');

function signInPage(message, again) {
  consoleEl.hidden = true;
  $('#booting').hidden = true;
  signinEl.hidden = false;
  clearWho();
  catalogs = null;
  prev = null;
  document.title = 'Sign in · nuna';
  const form = h('div', {class: 'signin-form'}, brand('only-sm'), h('p', {class: 'claim only-sm'}, CLAIM),
    h('h1', null, 'Sign in to nuna'),
    message ? h('p', {class: 'notice', role: 'status'}, icon('info'), h('span', null, message)) : null,
    again ? startAgainButton() : null);
  if (config.oidc) {
    const host = new URL(config.oidc.issuer).host;
    const status = h('div', {'aria-live': 'polite'});
    form.append(h('p', {class: 'muted'}, 'Your organisation\'s sign-in page opens, then brings you back here. Your sign-in lasts in '
      + 'this tab only.'),
    h('button', {class: 'btn primary lg', type: 'button', 'data-act': 'signin', onclick: async () => {
      fill(status, h('p', {class: 'loading-line'}, 'Going to ' + host + '…'));
      try {
        await startSignIn(config.oidc);
      } catch (error) {
        fill(status, failure(error, {title: 'Sign-in did not start'}), startAgainButton());
      }
    }}, icon('key'), 'Sign in with ' + host), status);
  }
  if (config['dev-token-login']) {
    const box = h('textarea', {class: 'tokenbox', rows: 4, spellcheck: 'false', placeholder: 'eyJhbGciOi…', 'aria-label': 'Bearer token'});
    const status = h('div', {'aria-live': 'polite'});
    if (config.oidc) form.append(h('div', {class: 'or'}, 'development only'));
    form.append(h('div', null,
      h('p', {class: 'fine', title: 'nuna.ui.dev-token-login=true'}, h('b', null, 'Paste a bearer token.'),
        ' For development only: this server allows signing in with one.'),
      box, h('p', {class: 'fine'}, 'Kept in this tab only.')),
    h('button', {class: 'btn' + (config.oidc ? '' : ' primary'), type: 'button', 'data-act': 'signin-token', onclick: () => {
      try {
        useToken(box.value);
        start();
      } catch (error) {
        fill(status, failure(error, {title: 'That token cannot be used'}));
      }
    }}, 'Use this token'), status);
  }
  if (!config.oidc && !config['dev-token-login']) {
    form.append(h('div', {class: 'notice'}, icon('info'), h('div', null, 'No sign-in is set up on this server. Ask whoever runs nuna '
      + 'to connect your organisation\'s sign-in.',
    techDetails(h('p', null, 'An operator sets ', h('code', null, 'nuna.ui.oidc.issuer'), ' and ', h('code', null, 'nuna.ui.oidc.client-id'),
      ' (or, for local development only, ', h('code', null, 'nuna.ui.dev-token-login=true'), ').')))));
  }
  fill(signinEl, h('main', {class: 'signin', id: 'signin-page'},
    h('div', {class: 'signin-side'}, brand(), h('h2', null, CLAIM),
      h('ul', {class: 'pts'}, [
        ['db', 'Four interchangeable stores.', 'PostgreSQL, Oracle, MongoDB or CockroachDB, each passing the same fault-injection suite.'],
        ['maint', 'Maintenance that respects the rules.', 'Snapshot expiry, orphan-file removal and compaction run under holds, freezes and recovery windows.'],
        ['audit', 'Every read, change and access refusal audited.', 'Changes reach a hash-chained archive kept at least five years.'],
        ['key', 'Credentials per table.', 'Short-lived storage credentials, scoped to the table\'s own prefix, only to callers allowed to read it.'],
      ].map(([ic, b, t]) => h('li', null, icon(ic), h('span', null, h('b', null, b), ' ', t)))),
      horizon()),
    form));
}

whenSignedOut(reason => signInPage(reason));

async function doSignOut() {
  history.replaceState(null, '', location.pathname);
  consoleEl.hidden = true;
  $('#booting').hidden = false;
  $('#booting').textContent = 'Signing out…';
  const message = await signOut(config.oidc);
  if (message) signInPage(message);
}

// ---- Go to a path ----

let gotoItems = [];
let gotoAt = 0;
let gotoTab = null;
let queued = [];
// The catalog's own search for the words typed (Atelier's review, S1; backlog 198): every name the caller may describe,
// in every catalog, whether or not the console has listed it. `searched` is the fragment the matches answer.
let found = [];
let searched = null;
let searchFailed = null;
let searchTimer = null;

const pathOfTarget = t => [...(t.namespace || []), ...(t.name ? [t.name] : [])].join('.');
const asItem = t => ({target: t, path: pathOfTarget(t)});

// ---- what the person opened lately, for Go to a path: kept per person in this browser, a convenience only ----

const recentKey = () => 'nuna.recent.' + ((claims() || {}).sub || '');

function recentTargets() {
  try {
    const list = JSON.parse(localStorage.getItem(recentKey()) || '[]');
    return Array.isArray(list) ? list.filter(t => t && catalogs && catalogs.some(c => c.name === t.catalog)) : [];
  } catch {
    return [];
  }
}

function keepRecent(t) {
  const key = x => [x.catalog, x.kind, pathOfTarget(x)].join(SEP);
  try {
    const list = [t, ...recentTargets().filter(x => key(x) !== key(t))].slice(0, 8);
    localStorage.setItem(recentKey(), JSON.stringify(list));
  } catch {
    // remembered for this page only
  }
}

/** The last word typed, most often the name itself, which the catalog searches for; every word must then match the whole
 * path, so `fx rates` finds rates in fx. */
const fragmentOf = words => words.length ? words[words.length - 1] : '';

function search(words) {
  clearTimeout(searchTimer);
  const fragment = fragmentOf(words);
  if (!fragment || fragment === searched) return;
  searchTimer = setTimeout(async () => {
    let rows;
    let failed = null;
    try {
      rows = await api.names(fragment);
    } catch (error) {
      rows = [];
      failed = error;
    }
    if (!gotoDlg.open || fragmentOf(gotoIn.value.trim().toLowerCase().split(/[\s./]+/).filter(Boolean)) !== fragment) return;
    found = rows.map(x => asItem(x.kind === 'namespace' ? {catalog: x.catalog, kind: 'namespace', namespace: [...x.namespace, x.name]}
      : {catalog: x.catalog, kind: x.kind, namespace: x.namespace, name: x.name}));
    searched = fragment;
    searchFailed = failed;
    gotoDraw();
  }, 150);
}

function gotoCandidates() {
  const seen = remembered(cat).map(x => ({target: x, path: [...x.namespace, ...(x.name ? [x.name] : [])].join('.')}));
  for (const r of queued) {
    const path = [...r.namespace, r.name].join('.');
    if (!seen.some(x => x.target.kind === 'table' && x.path === path)) seen.push({target: targetOfRow(r), path});
  }
  return seen;
}

/** A full dotted path typed into Go to a path, such as markets.fx.rates, or null. */
const dottedTyped = () => {
  const typed = gotoIn.value.trim();
  return /^[^\s./]+(\.[^\s./]+)+$/.test(typed) ? typed : null;
};

/** The ref the page shown reads, which Go to a path keeps (item 173): none off the browse pages and on a ref's own page. */
const readingNow = () => {
  const r = parse();
  return !r.page && r.k !== 'ref' ? r.r : null;
};

function gotoDraw() {
  const words = gotoIn.value.trim().toLowerCase().split(/[\s./]+/).filter(Boolean);
  const typedPath = dottedTyped();
  const all = gotoCandidates();
  const recents = recentTargets().map(asItem);
  const marks = new Map(queued.map(r => [keyOfRow(r), r]));
  const fragment = fragmentOf(words);
  const searching = !!fragment && searched !== fragment;
  if (!words.length) {
    // What the person opened lately comes first, then tonight's queue (Atelier's review, S1).
    const q = queued.map(r => ({target: targetOfRow(r), path: [...r.namespace, r.name].join('.')}));
    gotoH.textContent = recents.length ? (q.length ? 'Opened lately, then queued for tonight' : 'Opened lately')
      : q.length ? 'Queued for tonight, neediest first' : 'Type part of a name to search every catalog';
    gotoItems = [...recents.slice(0, q.length ? 4 : 9), ...q].slice(0, 9);
  } else {
    // Opened lately first, then what this catalog has listed, then the catalog's search across every catalog; each
    // once, and every word typed in its catalog and path.
    const hit = x => words.every(w => `${x.target.catalog || cat}.${x.path}`.toLowerCase().includes(w));
    const listed = all.map(x => ({...x, target: {catalog: cat, ...x.target}})).filter(hit)
      .sort((a, b) => (a.target.kind === 'namespace') - (b.target.kind === 'namespace') || a.path.length - b.path.length);
    const keyOf = x => [x.target.catalog || cat, x.target.kind, x.path].join(SEP);
    const seen = new Set();
    gotoItems = [...recents.filter(hit), ...listed, ...(searching ? [] : found.filter(hit))]
      .filter(x => !seen.has(keyOf(x)) && seen.add(keyOf(x))).slice(0, 9);
    gotoH.textContent = searching ? `${gotoItems.length ? plural(gotoItems.length, 'match', 'matches') + ' so far; s' : 'S'}earching every catalog…`
      : searchFailed ? 'Matches among the names listed so far; the catalog\'s search did not answer'
        : gotoItems.length ? `${gotoItems.length === 9 ? 'First 9' : gotoItems.length} match${gotoItems.length === 1 ? '' : 'es'}`
          : 'No match in any catalog';
    search(words);
    // A full dotted path opens even when the console has not listed it yet: Enter looks it up, level by level, as a
    // deep link does (Atelier's review, F1).
    if (typedPath && !gotoItems.some(x => x.path === typedPath)) {
      gotoItems = [{target: {kind: 'path'}, path: typedPath}, ...gotoItems].slice(0, 9);
      if (gotoItems.length === 1) gotoH.textContent = 'Not listed so far; Enter looks it up';
    }
  }
  gotoAt = Math.min(gotoAt, Math.max(0, gotoItems.length - 1));
  gotoList.replaceChildren(...gotoItems.map((it, i) => {
    const parts = it.path.split('.');
    const kind = it.target.kind;
    if (kind === 'path') {
      const li = h('li', {role: 'option', id: 'goto-o' + i, 'aria-selected': String(i === gotoAt), 'data-goto-path': it.path},
        icon('search', 'ic'), h('span', {class: 'gp'}, 'Open ', h('b', null, it.path)), h('span', {class: 'gx'}, h('span', {class: 'badge'}, 'look up')));
      li.addEventListener('click', () => gotoOpen(i));
      return li;
    }
    const there = it.target.catalog || cat;
    const row = kind === 'table' ? marks.get([there, it.path].join('.')) : null;
    const reasons = row ? row.assessment.reasons || [] : [];
    const extra = [there !== cat ? h('span', {class: 'badge', 'data-goto-catalog': there}, there) : null,
      ...(kind === 'namespace' ? [h('span', {class: 'badge'}, 'namespace')] : kind === 'view' ? [h('span', {class: 'badge'}, 'view')]
        : row ? [pill(reasons.includes('equality-deletes') ? 'bad' : 'warn', (REASONS[reasons[0]] || [null, 'Queued'])[1])] : [])];
    const li = h('li', {role: 'option', id: 'goto-o' + i, 'aria-selected': String(i === gotoAt), 'data-goto-target': `${there}:${it.path}`},
      icon(kind === 'namespace' ? 'ns' : kind, 'ic ' + (kind === 'namespace' ? 'ico-n' : kind === 'view' ? 'ico-v' : 'ico-t')),
      h('span', {class: 'gp'}, parts.slice(0, -1).map(x => [x + '.', h('wbr')]), h('b', null, parts[parts.length - 1])), h('span', {class: 'gx'}, extra));
    li.addEventListener('click', () => gotoOpen(i));
    li.addEventListener('pointermove', () => {
      if (gotoAt !== i) {
        gotoAt = i;
        gotoMark();
      }
    });
    return li;
  }));
  if (!gotoItems.length && words.length && !searching) {
    const example = (all.find(x => x.target.kind === 'table') || {}).path || 'namespace.table';
    gotoList.append(h('li', {class: 'none'}, searchFailed ? `Not found among the names listed so far in ${cat}. `
      + `Open a namespace, or type the full dotted path, such as ${example}.`
      : 'No namespace, table or view you may see has a name like that, in any catalog. Check the spelling, or type fewer letters.'));
  }
  gotoMark();
}

function gotoMark() {
  $$('li[role="option"]', gotoList).forEach((li, i) => li.setAttribute('aria-selected', String(i === gotoAt)));
  const cur = $('#goto-o' + gotoAt);
  if (cur) {
    gotoIn.setAttribute('aria-activedescendant', cur.id);
    cur.scrollIntoView({block: 'nearest'});
  } else {
    gotoIn.removeAttribute('aria-activedescendant');
  }
}

function gotoOpen(i) {
  const it = gotoItems[i];
  if (!it) return;
  if (it.target.kind === 'path') {
    lookUp(it.path);
    return;
  }
  gotoDlg.close();
  // The ref read goes along only within its own catalog.
  const there = it.target.catalog || cat;
  location.hash = objectHref({...it.target, catalog: there, ref: there === cat ? readingNow() : null}, gotoTab === 'audit' ? 'audit' : null);
}

const absent = error => {
  if (error && error.status === 404) return [];
  throw error;
};

/**
 * Finds a dotted path in the catalog shown, through the ref it reads: a namespace by that path, else a table or a view
 * named by its last part in the namespace above it. Null when there is none.
 */
async function find(path) {
  const parts = path.split('.');
  const ref = readingNow();
  const wh = warehouseOf({catalog: cat, ref});
  const found = {catalog: cat, ref};
  try {
    await api.namespace(wh, parts);
    return {...found, kind: 'namespace', namespace: parts};
  } catch (error) {
    absent(error);
  }
  if (parts.length < 2) return null;
  const levels = parts.slice(0, -1);
  const name = parts[parts.length - 1];
  const [tables, views] = await Promise.all([api.tables(wh, levels).catch(absent), api.views(wh, levels).catch(absent)]);
  if (tables.some(t => t.name === name)) return {...found, kind: 'table', namespace: levels, name};
  if (views.some(v => v.name === name)) return {...found, kind: 'view', namespace: levels, name};
  return null;
}

async function lookUp(path) {
  const asked = gotoIn.value;
  gotoH.textContent = `Looking up ${path} in ${cat}…`;
  let target;
  try {
    target = await find(path);
  } catch (error) {
    if (gotoIn.value !== asked) return;
    gotoH.textContent = `Could not look up ${path}`;
    gotoList.replaceChildren(h('li', {class: 'none'}, failure(error, {compact: true})));
    return;
  }
  if (!gotoDlg.open || gotoIn.value !== asked) return;
  if (!target) {
    gotoH.textContent = `No namespace, table or view ${path} in ${cat}`;
    gotoList.replaceChildren(h('li', {class: 'none', 'data-goto-none': path}, `${cat} has no namespace, table or view at ${path} that `
      + 'your groups may see. Check the spelling, or type part of the name to search what is listed.'));
    return;
  }
  gotoDlg.close();
  location.hash = objectHref(target, gotoTab === 'audit' ? 'audit' : null);
}

/** Opens Go to a path, with `words` already typed (the tree filter's, backlog 208). */
async function openGoto(tab = null, words = '') {
  if (!catalogs) return;
  closeMenus();
  gotoTab = tab;
  gotoIn.value = words;
  gotoAt = 0;
  queued = [];
  found = [];
  searched = null;
  searchFailed = null;
  gotoDraw();
  if (!gotoDlg.open) gotoDlg.showModal();
  gotoIn.focus();
  try {
    queued = queueOf(await queue(), cat);
    if (gotoDlg.open) gotoDraw();
  } catch {
    // the queue is a help here, not a need
  }
}

gotoIn.addEventListener('input', () => {
  gotoAt = 0;
  gotoDraw();
});
gotoIn.addEventListener('keydown', e => {
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    gotoAt = Math.min(gotoItems.length - 1, gotoAt + 1);
    gotoMark();
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    gotoAt = Math.max(0, gotoAt - 1);
    gotoMark();
  } else if (e.key === 'Enter') {
    e.preventDefault();
    gotoOpen(gotoAt);
  }
});
$('[data-goto-form]').addEventListener('submit', e => e.preventDefault());
gotoDlg.addEventListener('click', e => { if (e.target === gotoDlg) gotoDlg.close(); });

// ---- the router ----

const same = (a, b) => a && b && a.page === b.page && a.c === b.c && a.k === b.k && a.ns.join(SEP) === b.ns.join(SEP) && a.n === b.n && a.r === b.r;

function noCatalog() {
  return h('div', {class: 'nc-main'}, h('div', {class: 'ph'}, h('h1', {tabindex: '-1'}, 'No catalog to show')),
    h('div', {class: 'empty'}, h('h3', null, 'Your groups hold nothing in any catalog'),
      h('p', null, 'The catalog lists only the catalogs where your groups hold a grant. Ask a grant admin for one.')));
}

function render() {
  if (!catalogs) return;
  closeMenus();
  if (gotoDlg.open) gotoDlg.close();
  for (const d of $$('dialog.dlg')) d.close();
  const r = parse();
  const names = catalogs.map(c => c.name);
  const fallback = cat && names.includes(cat) ? cat : names[0] || null;
  if (!r.page && !r.c && (names.length || operates())) {
    if (!names.length) location.replace(pageHref('service'));
    else if (cat && names.includes(cat)) location.replace(homeHref(cat));
    else {
      slot.replaceChildren(h('p', {class: 'loading-line'}, 'Loading…'));
      landingCatalog(names).then(c => { if (!parse().page && !parse().c) location.replace(homeHref(c)); });
    }
    return;
  }
  clearReading();
  // Service is the whole deployment's: platform operators see it though they may hold nothing in any catalog.
  if (r.page !== 'service' && !names.length) {
    cat = null;
    setChrome(null);
    slot.replaceChildren(noCatalog());
    document.title = 'No catalog · nuna';
    prev = r;
    return;
  }
  if (r.c && !names.includes(r.c)) {
    cat = fallback;
    setChrome(null);
    slot.replaceChildren(notFound(`No catalog named ${r.c} that your groups may see`));
    document.title = 'Not found · nuna';
    prev = r;
    return;
  }
  cat = r.c || fallback;
  if (r.page !== 'service') keepCatalog(cat);
  const tabOnly = same(prev, r);
  let content;
  let navKey;
  let title;
  if (r.page === 'overview') {
    navKey = 'overview';
    title = 'Overview';
    content = overviewPage(cat);
  } else if (r.page === 'maintenance') {
    navKey = 'maintenance';
    title = 'Maintenance';
    content = maintenancePage(cat);
  } else if (r.page === 'governance') {
    navKey = 'governance';
    title = 'Governance';
    content = governancePage(cat, r.tab, r.g);
  } else if (r.page === 'audit') {
    navKey = 'audit';
    title = 'Audit';
    content = auditOpen(cat) ? auditPage(cat) : closedPage('Audit', `Audit is open to auditors and to those who manage ${cat}'s grants.`);
  } else if (r.page === 'service') {
    navKey = 'service';
    title = 'Service';
    content = operates() ? healthPage() : closedPage('Service', 'Service health is open to platform operators.');
  } else if (!r.page && r.c) {
    navKey = 'catalog';
    content = browse(slot, r);
    title = r.k === 'ref' && r.n ? '@' + r.n : r.n || (r.ns.length ? r.ns[r.ns.length - 1] : cat);
  } else {
    navKey = null;
    title = 'Not found';
    content = notFound();
  }
  if (!r.page && r.c && (r.k === 'table' || r.k === 'view') && r.n) keepRecent({catalog: r.c, kind: r.k, namespace: r.ns, name: r.n});
  else if (!r.page && r.c && r.k === 'namespace' && r.ns.length) keepRecent({catalog: r.c, kind: 'namespace', namespace: r.ns});
  const reading = !r.page && r.k !== 'ref' ? r.r : null;
  const carried = r.page && r.page !== 'service' ? r.r : null;
  setChrome(navKey, reading || carried);
  // A browse page that reads through a ref draws the Reading control itself (refChrome); every other page with a
  // catalog gets one whose refs open the catalog through them (Atelier's review, S4; backlog 201).
  const pageReads = navKey === 'catalog' && r.k !== 'ref' && !(!r.k && r.tab === 'refs');
  if (cat && r.page !== 'service' && !pageReads) readingControl({c: cat, r: carried}, !!carried);
  if (!slot.contains(content) || slot.firstElementChild !== content) slot.replaceChildren(content);
  document.title = `${title} · ${r.page === 'service' ? 'all catalogs' : cat}${reading ? '@' + reading : ''} · nuna`;
  if (tabOnly && prev.tab !== r.tab) {
    const t = $('.nc-main .tabs a[aria-current="page"]', slot);
    if (t) t.focus({preventScroll: true});
  } else if (!tabOnly) {
    window.scrollTo(0, 0);
    const h1 = $('h1', slot);
    if (h1 && prev) h1.focus({preventScroll: true});
  }
  prev = r;
}

async function start() {
  signinEl.hidden = true;
  signinEl.replaceChildren();
  consoleEl.hidden = false;
  $('#booting').hidden = true;
  showWho();
  fill(slot, h('p', {class: 'loading-line'}, 'Loading…'));
  try {
    const listed = await api.catalogs();
    keepAccess(listed);
    catalogs = listed.catalogs;
  } catch (error) {
    fill(slot, h('div', {class: 'nc-main'}, failure(error, {big: true})));
    return;
  }
  fill($('#cat-menu'), catalogs.map(c => h('button', {type: 'button', role: 'menuitemradio', 'aria-checked': 'false', 'data-cat-pick': c.name}, c.name)));
  render();
}

// ---- wiring ----

document.addEventListener('click', e => {
  const t = e.target.closest('[data-act], [data-cat-pick], [data-theme-pick]');
  if (!t) {
    if (!e.target.closest('.pick-wrap')) catMenu(false);
    return;
  }
  if (t.dataset.catPick) {
    catMenu(false);
    const c = t.dataset.catPick;
    if (c !== cat || parse().page === 'service') location.hash = switchHref(c);
    $('#catalog-pick').focus();
    return;
  }
  if (t.dataset.themePick) {
    pickTheme(t.dataset.themePick);
    return;
  }
  const act = t.dataset.act;
  if (act === 'signout') doSignOut();
  else if (act === 'menu') menu();
  else if (act === 'menu-close') menu(false);
  else if (act === 'pick') catMenu();
  else if (act === 'goto') openGoto(null, t.dataset.gotoWords || '');
  else if (act === 'goto-audit') openGoto('audit');
});

$('#cat-menu').addEventListener('keydown', e => {
  const items = $$('button', e.currentTarget);
  const i = items.indexOf(document.activeElement);
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    items[(i + (e.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length].focus();
  }
});

navEl.addEventListener('click', e => { if (e.target.closest('a') && consoleEl.classList.contains('menu-open')) menu(false); });

addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    if (!$('#cat-menu').hidden) {
      catMenu(false);
      $('#catalog-pick').focus();
    }
    if (consoleEl.classList.contains('menu-open')) {
      menu(false);
      $('.nc-menu').focus();
    }
    return;
  }
  if (!catalogs || e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey) return;
  if ((e.target instanceof Element && e.target.closest('input, textarea, select, [contenteditable]')) || $('dialog[open]')) return;
  if (e.key === '/') {
    e.preventDefault();
    openGoto();
  } else if (e.key === '?') {
    e.preventDefault();
    $('#keys').showModal();
  }
});

addEventListener('hashchange', () => { if (signedIn()) render(); });
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);
applyTheme();

async function boot() {
  const signingOut = backFromSignOut();
  try {
    const response = await fetch('/ui/config.json', {cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(15000)});
    config = await response.json();
  } catch (error) {
    $('#booting').hidden = true;
    signinEl.hidden = false;
    fill(signinEl, h('main', {class: 'nc-main'}, failure(error, {big: true, title: 'The console could not start: it could not load its settings.',
      tech: 'GET /ui/config.json'}),
      h('button', {class: 'btn sm', type: 'button', 'data-act': 'try-again', onclick: () => location.reload()}, 'Try again')));
    return;
  }
  if (config.oidc) {
    try {
      await finishSignIn(config.oidc);
    } catch (error) {
      signInPage(error.message, true);
      return;
    }
  }
  if (signedIn()) await start();
  else signedOut(signingOut ? 'Signed out.' : claims() ? 'Your sign-in has expired. Sign in again.' : null);
}

boot();

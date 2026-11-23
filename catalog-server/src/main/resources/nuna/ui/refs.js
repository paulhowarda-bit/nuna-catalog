// The catalog's branches and tags (item 26, D33; item 109), as Atelier's design draws them: chapter 6 of
// docs/design/console/wireframes.html and the prototype v14. A catalog ref is a rounded @name chip in its own hue;
// a table's own Iceberg ref keeps its square badge (table.js). Here: the catalog's Branches and tags tab, a ref's own
// page (Changes, Merge, Record; a tag's What it keeps), the selector and the band every page read through a ref
// carries, the notes on tabs that are main's, and the make, renew, delete and merge dialogs, each answer word for word
// as the catalog sent it. Where the design and the server differ, the server's answer wins (backlog/109.md).

import * as api from './api.js';
import {$, $$, at, badge, code, copyButton, edgeCue, facts, failure, fill, fmt, granted, h, icon, jsonPre, load, muted,
  openDialog, dialogForm, field, panel, pill, plural, s, select, tabsNav, tbl, when} from './dom.js';
import {href, objectHref, pageHref} from './route.js';

const DAY = 86_400_000;
const ms = iso => new Date(iso).getTime();
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
/** "21 Dec" */
const dayMon = iso => { const d = new Date(iso); return `${d.getDate()} ${MONTHS[d.getMonth()]}`; };
/** "21 Dec 06:41", always with the date. */
const refAt = iso => { const d = new Date(iso); return `${dayMon(iso)} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`; };
const daysTo = iso => Math.max(0, Math.ceil((ms(iso) - Date.now()) / DAY));
const untilWords = iso => { const n = daysTo(iso); return n ? `in ${plural(n, 'day')}` : 'today'; };
/** Whole days in an ISO-8601 duration as Java writes one (PT720H). */
const daysOf = (duration, fallback) => {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:[\d.]+S)?$/.exec(String(duration || ''));
  return m ? Math.round((Number(m[1] || 0) + Number(m[2] || 0) / 60) / 24) : fallback;
};
const WORDS = ['no', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten'];
const kindWord = r => r.kind === 'tag' ? 'tag' : 'branch';
const isMain = name => !name || String(name).toLowerCase() === 'main';

export const refHref = (c, name, tab) => href({c, k: 'ref', n: name, tab});
export const refsHref = c => href({c, tab: 'refs'});

// ---- the list, read again after any change and at most every 15 seconds ----

const lists = new Map();

/** {refs, limits, may} of a catalog, live and held alike: a held branch is marked expired. */
export function refsOf(catalog) {
  const hit = lists.get(catalog);
  if (hit && Date.now() - hit.at < 15_000) return hit.promise;
  const promise = api.refs(catalog, true);
  lists.set(catalog, {at: Date.now(), promise});
  promise.catch(() => lists.delete(catalog));
  return promise;
}

export const forgetRefs = catalog => lists.delete(catalog);
const live = list => list.refs.filter(r => !r.expired);
const held = list => list.refs.filter(r => r.expired);
export const findRef = (list, name) => live(list).find(r => r.name.toLowerCase() === String(name).toLowerCase()) || null;
const heldRef = (list, name) => held(list).find(r => r.name.toLowerCase() === String(name).toLowerCase()) || null;
const expiring = r => r.kind === 'branch' && !!r.expiring;
const mayRefs = list => !!(list.may && list.may.create);

/** What this session did to a ref that has since ended, shown on its page: merged or deleted. */
const RESULTS = new Map();
/** Results not yet shown: the page focuses each once, so focus does not fall to the body after a merge or delete. */
const FRESH = new Set();
const resultKey = (c, name) => c + '|' + String(name).toLowerCase();

/**
 * The time a ref's cut was taken: its own making, or that of the branch or tag it was made from, back to main. The
 * list names a ref's source by name only, so a ref found by that name but made after this one is a later ref that
 * took the name again: the walk stops there.
 */
function cutOf(r, list) {
  let at = r;
  for (let i = 0; i < 20 && !isMain(at.from); i++) {
    const from = list.refs.find(x => x.name.toLowerCase() === at.from.toLowerCase());
    if (!from || ms(from['created-at']) > ms(at['created-at'])) return null;
    at = from;
  }
  return at['created-at'];
}

// ---- the chip, the selector, the band and the notes ----

/** A catalog ref: a rounded chip, @ before the name; main is plain main. */
export function cref(r, o = {}) {
  const name = typeof r === 'string' ? r : r.name;
  const kind = typeof r === 'string' ? (isMain(r) ? 'main' : 'branch') : r.kind;
  return h('span', {class: 'cref ' + (kind === 'tag' ? 't' : kind === 'main' ? 'm' : 'b') + (o.big ? ' big' : '') + (o.warn ? ' warn' : '')},
    icon(kind === 'tag' ? 'tag' : kind === 'main' ? 'catalog' : 'branch'), kind === 'main' ? null : h('span', {class: 'at'}, '@'), name);
}

function madeFrom(r, list) {
  const from = isMain(r.from) ? 'main' : cref(live(list).find(x => x.name === r.from) || {name: r.from, kind: 'branch'});
  return ['Made from ', from, r['created-by'] ? [' by ', h('b', null, r['created-by'])] : null, ` on ${refAt(r['created-at'])}`];
}

/**
 * "Reading main ▾" beside a browse page's title: a disclosure of the catalog's refs, each a link to this page through
 * it, the one read marked current. A link naming a ref that is not live reads it struck through, with none current.
 */
function refPick(where, list) {
  const cur = where.r ? findRef(list, where.r) : null;
  const dead = !!where.r && !cur;
  const btn = h('button', {class: 'ref-pick' + (cur ? ' on ' + (cur.kind === 'tag' ? 't' : 'b') : ''), type: 'button',
    'aria-expanded': 'false', 'aria-controls': 'ref-menu', 'data-ref-pick': '',
    'aria-label': dead ? `Reading ${where.r}, which is not live. Choose a branch or tag`
      : `Reading ${cur ? kindWord(cur) + ' ' + cur.name : 'main'}. Choose a branch or tag`},
  h('span', {class: 'lbl'}, 'Reading'), dead ? h('span', {class: 'cref gone'}, icon('branch'), h('span', {class: 'at'}, '@'), where.r)
    : cur ? cref(cur) : cref('main'), icon('chev'));
  const item = r => {
    const on = !dead && (cur ? cur.name : null) === (r ? r.name : null);
    const sub = !r ? 'the catalog as it is now' : r.kind === 'tag' ? `tag · ${refAt(r['created-at'])} · read-only`
      : `branch · ${plural(r['changed-count'] || 0, 'table')} changed · expires ${expiring(r) ? untilWords(r['expires-at']) : dayMon(r['expires-at'])}`;
    return h('a', {'aria-current': on ? 'true' : null, href: href(Object.assign({}, where, {r: r ? r.name : null})),
      class: r && expiring(r) ? 'is-warn' : null}, h('b', null, r ? cref(r) : cref('main')), h('span', null, sub));
  };
  const branches = live(list).filter(r => r.kind === 'branch');
  const tags = live(list).filter(r => r.kind === 'tag');
  const menu = h('div', {class: 'menu ref-menu', id: 'ref-menu', hidden: true},
    item(null),
    branches.length ? [h('p', {class: 'mh'}, 'Branches'), branches.map(item)] : null,
    tags.length ? [h('p', {class: 'mh'}, 'Tags'), tags.map(item)] : null,
    h('a', {class: 'mfoot', href: refsHref(where.c)}, `Branches and tags of ${where.c}`, icon('right')));
  const wrap = h('div', {class: 'ref-wrap'}, btn, menu);
  const set = open => {
    menu.hidden = !open;
    btn.setAttribute('aria-expanded', String(open));
    if (open) ($('[aria-current="true"]', menu) || $('a', menu)).focus();
  };
  btn.addEventListener('click', e => { e.stopPropagation(); set(menu.hidden); });
  // The arrows, Home and End move between the links; Escape, or focus leaving it, closes it.
  menu.addEventListener('keydown', e => {
    const items = $$('a', menu);
    const i = items.indexOf(document.activeElement);
    const to = {ArrowDown: (i + 1) % items.length, ArrowUp: (i + items.length - 1) % items.length, Home: 0, End: items.length - 1}[e.key];
    if (to !== undefined) {
      e.preventDefault();
      items[to].focus();
    }
  });
  wrap.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !menu.hidden) { e.stopPropagation(); set(false); btn.focus(); }
  });
  wrap.addEventListener('focusout', e => { if (!menu.hidden && !wrap.contains(e.relatedTarget)) set(false); });
  const away = e => {
    if (!wrap.isConnected) { document.removeEventListener('click', away); return; }
    if (!wrap.contains(e.target)) set(false);
  };
  document.addEventListener('click', away);
  return wrap;
}

/** The band under a page's title while it reads through a ref. */
function refBand(r, where, list) {
  const back = h('a', {class: 'btn sm', href: href(Object.assign({}, where, {r: null}))}, 'Read main instead');
  const more = h('a', {class: 'btn sm ghost', href: refHref(where.c, r.name)}, `About @${r.name}`);
  const cut = cutOf(r, list);
  const cutWords = cut ? refAt(cut) : `the point @${r.from} was made from`;
  if (r.kind === 'tag') {
    return h('div', {class: 'ref-band t', role: 'note', 'data-ref-band': r.name}, h('span', {class: 'rb-ic'}, icon('lock')),
      h('div', {class: 'rb-txt'}, h('p', null, h('b', null, 'Reading the tag ', cref(r), `: ${where.c} as it was at ${cutWords}.`),
        ' Read-only: no engine and no console page can change anything through it.'),
      h('p', {class: 'fine'}, madeFrom(r, list), '. It never expires.')),
      h('div', {class: 'rb-acts'}, more, back));
  }
  const n = r['changed-count'] || 0;
  const may = mayRefs(list);
  return h('div', {class: 'ref-band b' + (expiring(r) ? ' warn' : ''), role: 'note', 'data-ref-band': r.name},
    h('span', {class: 'rb-ic'}, icon(expiring(r) ? 'clock' : 'branch')),
    h('div', {class: 'rb-txt'}, h('p', null, h('b', null, 'Reading the branch ', cref(r), '.'),
      ` ${plural(n, 'table')} changed on it; every other table reads as main had it at ${cutWords}.`),
    h('p', {class: 'fine'}, madeFrom(r, list), '. ', expiring(r)
      ? h('b', {class: 'warn-ink'}, `Expires ${untilWords(r['expires-at'])}, ${refAt(r['expires-at'])}.`)
      : `Expires ${refAt(r['expires-at'])} unless a commit or a renewal moves it.`)),
    h('div', {class: 'rb-acts'},
      may && expiring(r) ? h('button', {class: 'btn sm', type: 'button', onclick: () => renewDialog(where.c, r)}, 'Renew') : null,
      may ? h('a', {class: 'btn sm', href: refHref(where.c, r.name, 'merge')}, 'Preview the merge') : null, more, back));
}

/** A link that names a ref that is not live: the catalog's own answer for the warehouse, and why, where it is known. */
async function noRef(where, list) {
  let error = null;
  try {
    await api.prefix(`${where.c}@${where.r}`);
  } catch (e) {
    error = e;
  }
  const kept = heldRef(list, where.r);
  const why = kept ? [h('b', null, `@${kept.name} expired at ${refAt(kept['expires-at'])}`),
    '; its name is held until the worker ends it on its next run.'] : await endedWords(where.c, where.r);
  return h('div', {class: 'stack', 'data-ref-band': ''}, error ? failure(error) : null,
    h('p', {class: 'fine'}, why ? [why, ' '] : null, 'Read ', h('a', {href: href(Object.assign({}, where, {r: null}))}, `${where.c} on main`),
      ' or see its ', h('a', {href: refsHref(where.c)}, 'branches and tags'), '.'));
}

/**
 * The selector and the band for a browse page `where` (a route): the selector goes into `side`, the band after
 * `head`; both once the catalog's refs are read. A page the caller may not list refs on keeps neither.
 */
export function refChrome(where, side, head) {
  refsOf(where.c).then(async list => {
    // The page may still be going in: wait a task, then draw only into a page that is shown.
    await new Promise(done => setTimeout(done, 0));
    if (!head.parentNode) return;
    side.prepend(refPick(where, list));
    if (!where.r) return;
    const r = findRef(list, where.r);
    head.after(r ? refBand(r, where, list) : await noRef(where, list));
  }, () => {});
}

/** What a tab that is main's says when it is opened through a ref. */
export function refNote(r, what) {
  const name = typeof r === 'string' ? r : r.name;
  const chip = cref(typeof r === 'string' ? {name, kind: 'branch'} : r);
  const tag = typeof r !== 'string' && r.kind === 'tag';
  const words = {
    health: [h('b', null, 'Maintenance is main\'s.'), ` The worker compacts and expires main only; a ${tag ? 'tag' : 'branch'}'s `
      + `tables are never compacted or expired, and every file @${name} reaches is kept while it exists.`],
    governance: [h('b', null, 'Governance holds on every ref.'), ' Holds, freezes, classifications and the regulated area are main\'s '
      + 'record of the table, by its id: what you see here binds ', chip, ' too, and a merge is judged by it.'],
    grants: [h('b', null, 'Grants hold on every ref.'), ' Whoever may write this table on main may write it on ', chip,
      ', and no one else: a grant cannot name a branch yet (item 113).'],
    audit: [h('b', null, 'This is the table\'s whole audit trail.'), ' Records of reads and commits through a ref name it, as ',
      code('on @' + name), ' or ', code('through @' + name), '.'],
    props: tag ? [h('b', null, 'Read-only through a tag.'), ' These are the properties in the metadata file the tag keeps.']
      : [h('b', null, 'A change here commits on ', chip, '.'), ' Main keeps its own properties until a merge takes this file.'],
    nsprops: [h('b', null, 'Namespace properties are live.'), ' They are main\'s, read now, whichever ref you read; they change on main only.'],
    snapshots: [h('b', null, `Inside a catalog ${tag ? 'tag' : 'branch'}.`), ` These are the snapshots @${name} reaches. Inside the table, its own Iceberg `
      + 'branch is still called main: the catalog\'s ref chooses which metadata file is read, and that file\'s refs are Iceberg\'s.'],
  }[what];
  return h('div', {class: 'notice ref-note', 'data-ref-note': what}, icon('info'), h('span', null, words));
}

/** The catalog's answer to a change through a ref that a tag, or main alone, takes: shown as a note, not an error. */
export function readOnlyNote(r, catalog, operation, ns) {
  const tag = r.kind === 'tag';
  const message = tag ? `${operation} is refused on tag ${r.name} of catalog ${catalog}: a tag is read-only`
    : `${operation} is refused on branch ${r.name} of catalog ${catalog}: creates, drops, renames, registrations and namespace changes happen on main only`;
  return h('div', {class: 'ro-note'}, h('p', {class: 'refusal-h'}, ns ? 'What the catalog answers a change through ' : 'Read-only: a tag never changes. If an engine tries, the catalog answers',
    ns ? cref(r) : null),
  h('p', {class: 'refusal-code'}, '400 BadRequestException'), h('p', {class: 'refusal-msg'}, message),
  h('p', {class: 'fine'}, ns ? 'So the console offers no change here. Set it on main.' : 'So the console offers no change here. Read main to change a setting.'));
}

/** The ref a page reads, for its tabs: the list's record, or null while reading main or when it is not live. */
export async function readingRef(c, name) {
  if (!name) return null;
  try {
    return findRef(await refsOf(c), name);
  } catch {
    return null;
  }
}

// ---- audit words for a ref's record ----

/** A nuna.catalog.ref record in words (6.5). */
export function refWords(d) {
  const name = '@' + d.name;
  const kind = String(d.kind || '').toLowerCase() === 'tag' ? 'tag' : 'branch';
  const from = isMain(d.from) ? 'main' : '@' + d.from;
  const until = d.expiresAt ? refAt(d.expiresAt) : '';
  switch (String(d.action || '').toUpperCase()) {
    case 'CREATED': return kind === 'tag' ? `Made the tag ${name} from ${from}; it never expires`
      : `Made the branch ${name} from ${from}; it expires ${until} unless a commit or a renewal moves that`;
    case 'RENEWED': return `Renewed the branch ${name}: it now expires ${until}`;
    case 'DELETED': return kind === 'tag' ? `Deleted the tag ${name}` : `Deleted the branch ${name}; changes main never took are gone with it`;
    case 'MERGED': return `Merged the branch ${name} into main: ${plural((d.tables || []).length, 'table')} moved in one transaction, and the branch ended`;
    case 'EXPIRING': return `Warned that the branch ${name} expires ${until}`;
    case 'EXPIRED': return `Ended the branch ${name}, which expired ${until}`;
    default: return `${String(d.action || '').toLowerCase()} ${name}`;
  }
}

/**
 * A link to the ref a `nuna.catalog.ref` record names, added once the list shows that ref live and the same one (the
 * record's `ref` is its id), so a record of a deleted ref never links to a later ref that took its name.
 */
export function liveRefLink(c, d) {
  const slot = h('span', {class: 'ref-link'});
  if (!c || !d.ref) return slot;
  refsOf(c).then(list => {
    const r = live(list).find(x => x.id === d.ref);
    if (r) fill(slot, ' · ', h('a', {href: refHref(c, r.name)}, cref(r)));
  }, () => {});
  return slot;
}

/** The last day's ref records of a catalog from the audit feed; none when the caller may not read it. */
async function refRecords(c, pages = 2) {
  const out = [];
  let pageToken = null;
  try {
    for (let i = 0; i < pages; i++) {
      const answer = await api.auditFeed(c, {type: 'nuna.catalog.ref', from: new Date(Date.now() - DAY).toISOString(), pageToken});
      out.push(...(answer.records || []));
      pageToken = answer['next-page-token'];
      if (!pageToken) break;
    }
  } catch {
    return null;
  }
  return out;
}

/** Why a name no longer names a live ref, from the last day's audit records, when the caller may read them. */
async function endedWords(c, name) {
  const records = await refRecords(c);
  const last = (records || []).find(x => x.data && String(x.data.name).toLowerCase() === String(name).toLowerCase());
  if (!last) return null;
  const d = last.data;
  const who = (d.actor || {}).principal || 'someone';
  if (d.action === 'DELETED') return [h('b', null, `@${d.name} was deleted on ${refAt(last.time)} by ${who}.`), ' Its record stays in the ',
    h('a', {href: pageHref('audit', c)}, 'audit feed'), '.'];
  if (d.action === 'MERGED') return [h('b', null, `@${d.name} was merged into main on ${refAt(last.time)} by ${who}.`), ' Its record stays in the ',
    h('a', {href: pageHref('audit', c)}, 'audit feed'), '.'];
  if (d.action === 'EXPIRED') return [h('b', null, `@${d.name} expired on ${refAt(d.expiresAt || last.time)}`), ' and the worker ended it. Its record stays in the ',
    h('a', {href: pageHref('audit', c)}, 'audit feed'), '.'];
  return null;
}

// ---- the catalog's Branches and tags tab ----

/** Branches and tags {n}: the tab's label and count, once the list is read. */
export function refsCount(c) {
  return refsOf(c).then(list => live(list).length, () => null);
}

export async function refsTab(c) {
  const list = await refsOf(c);
  const refs = live(list);
  const br = refs.filter(r => r.kind === 'branch');
  const tg = refs.filter(r => r.kind === 'tag');
  const lim = list.limits || {};
  const may = mayRefs(list);
  const out = [];
  const warnDays = daysOf(lim['expiry-warning'], 7);
  out.push(h('div', {class: 'kpis refs-kpis'},
    h('div', {class: 'kpi'}, h('p', {class: 'kpi-l'}, icon('branch'), 'Open branches'),
      h('p', {class: 'kpi-v'}, fmt(br.length), h('small', null, ` of ${fmt(lim['max-branches'])}`)), h('p', {class: 'kpi-d'}, 'nuna.refs.max-branches')),
    h('div', {class: 'kpi'}, h('p', {class: 'kpi-l'}, icon('tag'), 'Tags'),
      h('p', {class: 'kpi-v'}, fmt(tg.length), h('small', null, ` of ${fmt(lim['max-tags'])}`)), h('p', {class: 'kpi-d'}, 'nuna.refs.max-tags')),
    h('div', {class: 'kpi' + (br.some(expiring) ? ' is-warn' : '')}, h('p', {class: 'kpi-l'}, icon('clock'), `Expiring within ${plural(warnDays, 'day')}`),
      h('p', {class: 'kpi-v'}, fmt(br.filter(expiring).length)),
      h('p', {class: 'kpi-d'}, br.some(expiring) ? br.filter(expiring).map(r => '@' + r.name).join(', ') : 'none')),
    h('div', {class: 'kpi'}, h('p', {class: 'kpi-l'}, 'Tables changed on branches'),
      h('p', {class: 'kpi-v'}, fmt(br.reduce((a, r) => a + (r['changed-count'] || 0), 0))),
      h('p', {class: 'kpi-d'}, `a merge moves at most ${fmt(lim['max-merge-tables'])}`))));
  if (!refs.length) {
    out.push(h('div', {class: 'empty refs-empty'}, h('h3', null, `No branch or tag in ${c} yet`),
      h('p', null, 'A tag keeps every table in the catalog as it is now, read-only, for as long as you keep it. A branch does the same '
        + 'and then takes commits of its own, which you merge into main when they are ready, all tables in one transaction.'),
      may ? h('div', {class: 'refusal-acts'}, h('button', {class: 'btn primary', type: 'button', 'data-act': 'ref-new',
        onclick: () => makeDialog(c, list)}, icon('branch'), 'Make a branch or tag')) : null,
      h('p', {class: 'fine'}, 'Engines read a ref as a warehouse of its own: ', code(`${c}@<name>`), '.')));
    out.push(await endedPanel(c, list), refsExplain(c, lim));
    return out;
  }
  if (lim['max-branches'] <= br.length) {
    out.push(h('div', {class: 'notice warn-note'}, icon('alert'), h('span', null,
      h('b', null, `${c} holds ${fmt(br.length)} of ${fmt(lim['max-branches'])} open branches.`),
      ' A new branch is refused until one is merged, deleted or expires. Tags are counted apart.')));
  }
  out.push(h('div', {class: 'panel refline-panel'}, h('h2', null, 'Every ref on one line ',
    h('span', {class: 'meta'}, 'main is the waterline; branches run beneath it to their expiry')), refLine(c, refs, list),
  h('p', {class: 'fine only-sm'}, 'Opens at today; earlier refs are to the left.')));
  const rows = refs.slice().sort((a, b) => (a.kind === b.kind ? 0 : a.kind === 'branch' ? -1 : 1)
    || (a.kind === 'branch' ? ms(a['expires-at']) - ms(b['expires-at']) : ms(b['created-at']) - ms(a['created-at'])))
    .map(r => refRow(c, r, list, may));
  const tbody = h('tbody', null, rows);
  const chipsBox = h('div', {class: 'chips', role: 'group', 'aria-label': 'Show'},
    [['all', 'All', refs.length], ['branch', 'Branches', br.length], ['tag', 'Tags', tg.length]].map(([f, l, n], i) =>
      h('button', {class: 'chip', type: 'button', 'data-f': f, 'aria-pressed': String(i === 0), onclick: e => {
        for (const o of $$('.chip', chipsBox)) o.setAttribute('aria-pressed', String(o === e.currentTarget));
        for (const tr of $$('tr', tbody)) tr.hidden = !(f === 'all' || tr.dataset.kind === f);
      }}, l, ' ', h('span', {class: 'n'}, fmt(n)))));
  out.push(h('div', {class: 'panel', id: 'refs'}, h('div', {class: 'panel-h'}, h('h2', null, 'Branches and tags ',
    h('span', {class: 'meta'}, `of ${c}, as GET …/refs lists them`)), chipsBox,
  may ? h('button', {class: 'btn sm primary', type: 'button', 'data-act': 'ref-new', onclick: () => makeDialog(c, list)}, icon('branch'), 'Make a branch or tag') : null),
  tbl('refs-tbl stack-line', ['Ref', 'Made', 'Expires', 'Changed tables', ''], tbody),
  may ? null : h('p', {class: 'fine', 'data-no-create': ''}, h('b', null, `Making, renewing, deleting and merging need create on ${c}.`),
    ' Your groups hold none, so the console offers you none of them; reading through a ref needs only what reading main needs.')));
  out.push(await endedPanel(c, list), refsExplain(c, lim));
  return out;
}

function refRow(c, r, list, may) {
  const n = r['changed-count'] || 0;
  const acts = h('div', {class: 'row-acts'},
    h('a', {class: 'btn sm ghost', href: href({c, r: r.name})}, 'Read'),
    may && r.kind === 'branch' ? h('a', {class: 'btn sm', href: refHref(c, r.name, 'merge')}, 'Merge…') : null,
    may && r.kind === 'branch' ? h('button', {class: 'btn sm ghost', type: 'button', onclick: () => renewDialog(c, r)}, 'Renew') : null,
    may ? h('button', {class: 'btn sm ghost danger', type: 'button', 'aria-label': `Delete @${r.name}`,
      onclick: () => deleteDialog(c, r, list)}, icon('x')) : null);
  const fromBranch = r.kind === 'tag' && !isMain(r.from);
  return h('tr', {'data-kind': r.kind, 'data-ref': r.name, class: expiring(r) ? 'is-warn' : null},
    h('td', {class: 'name'}, h('a', {href: refHref(c, r.name)}, cref(r))),
    h('td', null, h('span', {class: 'tnum'}, refAt(r['created-at'])), h('br'), h('span', {class: 'fine'},
      r['created-by'] ? ['by ', h('b', null, r['created-by']), ' '] : null, 'from ', isMain(r.from) ? 'main' : '@' + r.from)),
    h('td', null, r.kind === 'tag' ? muted('never') : [expiring(r) ? pill('warn', icon('clock'), `Expires ${untilWords(r['expires-at'])}`)
      : h('span', {class: 'tnum'}, refAt(r['expires-at'])), h('br'), h('span', {class: 'fine'},
      expiring(r) ? refAt(r['expires-at']) : untilWords(r['expires-at']))]),
    h('td', null, r.kind === 'tag' ? (fromBranch ? h('span', {class: 'fine'}, `as @${r.from} had them`) : muted('—'))
      : n ? h('a', {href: refHref(c, r.name)}, plural(n, 'table')) : muted('none yet')),
    h('td', {class: 'acts'}, acts));
}

async function endedPanel(c, list) {
  const records = (await refRecords(c)) || [];
  const warned = new Map(records.filter(x => x.data && x.data.action === 'EXPIRING').map(x => [String(x.data.name).toLowerCase(), x.time]));
  const rows = [
    ...held(list).map(r => h('tr', {class: 'is-warn', 'data-ended': r.name}, h('td', {class: 'name'}, cref(r, {warn: true})),
      h('td', null, pill('warn', icon('clock'), 'Expired'), ' ', h('span', {class: 'fine'}, `at ${refAt(r['expires-at'])}`)),
      h('td', null, h('span', {class: 'fine'}, 'Gone from every route. Its name is held until the worker ends it on its next run',
        warned.has(r.name.toLowerCase()) ? `; warned ${refAt(warned.get(r.name.toLowerCase()))}` : '', '.')))),
    ...records.filter(x => x.data && ['DELETED', 'EXPIRED', 'MERGED'].includes(x.data.action)).map(x => {
      const d = x.data;
      const who = (d.actor || {}).principal || '–';
      const label = d.action === 'EXPIRED' ? 'Expired, ended' : d.action === 'MERGED' ? 'Merged' : 'Deleted';
      const taken = findRef(list, d.name);
      const free = taken ? [' The name is taken again, by ', h('a', {href: refHref(c, taken.name)}, cref(taken)), '.'] : ' The name is free.';
      const words = d.action === 'EXPIRED' ? `Ended by the worker; it expired ${refAt(d.expiresAt || x.time)}. Its changes were never merged.`
        : d.action === 'MERGED' ? [`Merged into main by ${who}: ${plural((d.tables || []).length, 'table')} moved.`, free]
          : [`Deleted by ${who}.`, free];
      return h('tr', {'data-ended': d.name}, h('td', {class: 'name'}, h('span', {class: 'cref gone'},
        icon(String(d.kind).toLowerCase() === 'tag' ? 'tag' : 'branch'), h('span', {class: 'at'}, '@'), d.name)),
      h('td', null, pill(null, label), ' ', h('span', {class: 'fine'}, refAt(x.time))), h('td', null, h('span', {class: 'fine'}, words)));
    }),
  ];
  if (!rows.length) return null;
  return h('div', {class: 'panel', id: 'refs-ended'}, h('h2', null, 'Ended lately ',
    h('span', {class: 'meta'}, 'from the audit feed\'s ref records; the list leaves these out')), tbl('stack-line ended-tbl', ['Ref', 'What happened', ''], rows));
}

function refsExplain(c, lim) {
  const idle = daysOf(lim['branch-idle-expiry'], 30);
  const life = daysOf(lim['branch-max-life'], 180);
  const warn = daysOf(lim['expiry-warning'], 7);
  return h('div', {class: 'refs-explain'},
    panel('How engines reach a ref', null, h('p', {class: 'fine'}, 'Each ref is a warehouse of its own. Any Iceberg client reads it as it reads the catalog:'),
      h('pre', {class: 'raw'}, `warehouse=${c}@dev`), h('p', {class: 'fine'}, 'A tag lists only the read endpoints in its ', code('/v1/config'),
        ', so an engine refuses its own write before it is sent. Creates, drops and renames happen on main only.')),
    panel('Two kinds of ref', null, h('dl', {class: 'facts one'},
      h('dt', null, cref({name: 'dev', kind: 'branch'}), ' ', cref({name: 'matter-2026-114', kind: 'tag'})),
      h('dd', null, h('b', null, 'The catalog\'s branches and tags'), ', here: every table at once, read through ', code(`${c}@name`), '.'),
      h('dt', null, badge([icon('branch'), 'surveillance-review'], 'ref-b')),
      h('dd', null, h('b', null, 'Iceberg\'s own refs'), ', inside one table\'s metadata, made by an engine; on each table\'s page under ',
        h('i', null, 'Iceberg refs in this table'), '.'))),
    panel('Limits and expiry', 'nuna.refs.*', h('dl', {class: 'facts one'},
      h('dt', null, 'A branch expires'), h('dd', null, `${idle} days after its last commit or renewal, and never later than ${life} days after it `
        + `was made. ${WORDS[warn] || fmt(warn)} ${warn === 1 ? 'day' : 'days'} before, the catalog warns once on the change stream and marks it here.`),
      h('dt', null, 'A tag'), h('dd', null, 'never expires; delete it when it is no longer needed.'),
      h('dt', null, 'Files'), h('dd', null, 'Cleanup keeps every file any ref reaches. Maintenance runs on main only.'))));
}

/** The ref line: main as the waterline, tags as flags, each branch beneath it from its point to its expiry. */
function refLine(c, refs, list) {
  const now = Date.now();
  const brs = refs.filter(r => r.kind === 'branch').sort((a, b) => ms(a['created-at']) - ms(b['created-at']));
  const tags = refs.filter(r => r.kind === 'tag');
  const cut = r => ms(cutOf(r, list) || r['created-at']);
  const lo = Math.min(...refs.map(cut), now - 30 * DAY) - 2 * DAY;
  const hi = Math.max(...brs.map(r => ms(r['expires-at'])), now + 30 * DAY) + 3 * DAY;
  const W = 1100;
  const WL = 80;
  const LANE = 36;
  const H = WL + 30 + brs.length * LANE + 18;
  const x = m => 50 + (m - lo) / (hi - lo) * (W - 280);
  const svg = s('svg', {viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': `${c}'s refs: ${plural(brs.length, 'branch', 'branches')} running `
    + `beneath main to their expiry, ${plural(tags.length, 'tag')} marking points; now is ${refAt(new Date(now).toISOString())}`});
  const monday = new Date(now);
  monday.setHours(0, 0, 0, 0);
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
  for (let m = monday.getTime() - Math.ceil((monday.getTime() - lo) / (7 * DAY)) * 7 * DAY; m < hi; m += 7 * DAY) {
    if (m > lo) svg.append(s('line', {class: 'tick', x1: x(m), y1: 18, x2: x(m), y2: H - 14}), s('text', {class: 't', x: x(m) + 3, y: H - 2}, dayMon(m)));
  }
  svg.append(s('rect', {class: 'sea', x: 0, y: WL, width: W, height: H - WL - 16}));
  svg.append(s('line', {class: 'wl', x1: 0, y1: WL, x2: W, y2: WL}), s('text', {class: 'tb', x: 6, y: WL - 6}, 'main'));
  const nx = x(now);
  svg.append(s('line', {class: 'now', x1: nx, y1: 10, x2: nx, y2: H - 14}), s('text', {class: 'now-t', x: nx + 4, y: 12}, 'now'));
  const laneOf = name => brs.findIndex(b => b.name === name);
  let level = 0;
  tags.slice().sort((a, b) => ms(a['created-at']) - ms(b['created-at'])).forEach(t => {
    const li = isMain(t.from) ? -1 : laneOf(t.from);
    const base = li < 0 ? WL : WL + 30 + li * LANE;
    const top = li < 0 ? 22 + (level++ % 2) * 18 : base - 22;
    const X = x(ms(t['created-at']));
    const a = s('a', {href: refHref(c, t.name), class: 'rl-tag'});
    a.append(s('title', null, `@${t.name}, tag, ${refAt(t['created-at'])}`), s('line', {class: 'pole', x1: X, y1: top, x2: X, y2: base}),
      s('path', {class: 'flag', d: `M${X} ${top} h10 l-4 5 l4 5 h-10 z`}), s('text', {class: 'flag-t', x: X - 4, y: top + 8, 'text-anchor': 'end'}, '@' + t.name));
    svg.append(a);
  });
  brs.forEach((b, i) => {
    const y = WL + 30 + i * LANE;
    const x0 = x(cut(b));
    const xm = x(ms(b['created-at']));
    const xe = x(ms(b['expires-at']));
    const warn = expiring(b);
    const a = s('a', {href: refHref(c, b.name), class: 'rl-br' + (warn ? ' warn' : '')});
    a.append(s('title', null, `@${b.name}, branch from ${refAt(new Date(cut(b)).toISOString())}, ${plural(b['changed-count'] || 0, 'table')} changed, `
      + `expires ${refAt(b['expires-at'])}`));
    a.append(s('path', {class: 'br', d: `M${x0} ${WL} C ${x0 + 8} ${WL}, ${x0 + 4} ${y}, ${x0 + 14} ${y} H ${Math.max(xm, x0 + 14)}`}));
    a.append(s('line', {class: 'br-life', x1: Math.max(xm, x0 + 14), y1: y, x2: xe, y2: y}), s('line', {class: 'br-end', x1: xe, y1: y - 6, x2: xe, y2: y + 6}));
    const label = s('text', {class: 'br-t', x: xe + 8, y: y + 4}, '@' + b.name);
    label.append(s('tspan', {class: 'br-e'}, warn ? `  expires ${untilWords(b['expires-at'])}` : `  expires ${dayMon(b['expires-at'])}`));
    a.append(label);
    svg.append(a);
  });
  const box = h('div', {class: 'rl-scroll'}, svg);
  // Opens at today whenever the box gets a width, until the reader scrolls it. Not only the first time: the tab is
  // drawn before it is in the page, and the page is moved into place after, which puts a box's scroll back to its
  // start; a width that changes (a phone turned, a window narrowed to a phone's) changes where today is too.
  let moved = false;
  for (const e of ['pointerdown', 'wheel', 'keydown', 'touchstart']) box.addEventListener(e, () => { moved = true; }, {passive: true});
  new ResizeObserver(() => {
    if (moved || !box.clientWidth) return;
    box.scrollLeft = Math.max(0, nx / W * box.scrollWidth - box.clientWidth * 0.55);
  }).observe(box);
  edgeCue(box);
  return box;
}

// ---- a ref's own page ----

export function refPage(c, name, tab) {
  const main = h('div', {class: 'nc-main ref-page', id: 'ref-page'});
  const crumbs = h('nav', {class: 'crumbs', 'aria-label': 'Path'}, h('a', {href: href({c})}, c), h('span', {class: 'sep'}, '/'),
    h('a', {href: refsHref(c)}, 'Branches and tags'), h('span', {class: 'sep'}, '/'), h('span', {class: 'cur'}, '@' + name));
  const shown = load(main, async () => {
    let r;
    let list;
    try {
      [r, list] = await Promise.all([api.ref(c, name), refsOf(c)]);
    } catch (error) {
      const head = h('div', {class: 'ph'}, crumbs, h('div', {class: 'title-row'}, h('h1', {tabindex: '-1'}, '@' + name)));
      const done = RESULTS.get(resultKey(c, name));
      if (done && error.status === 404) return [head, done()];
      if (error.status !== 404) return [head, failure(error, {big: true})];
      const kept = list || await refsOf(c).catch(() => ({refs: []}));
      const holder = heldRef(kept, name);
      const why = holder ? [h('b', null, `@${holder.name} expired at ${refAt(holder['expires-at'])}.`),
        ' It is gone from every route, but holds its name until the worker ends it on its next run. Its changes were never merged.']
        : await endedWords(c, name);
      return [head, failure(error, {note: why || `No branch or tag has this name in ${c}.`})];
    }
    const may = !!(r.may && r.may.create);
    const changed = r.changed || [];
    const total = Math.max(changed.length, (findRef(list, r.name) || {})['changed-count'] || 0);
    // A branch's own versions number from 1 (item 105), so its version is its count of commits.
    const commits = changed.reduce((a, t) => a + (t.version || 0), 0);
    const cut = cutOf(r, list);
    const title = h('div', {class: 'title-row'}, h('h1', {tabindex: '-1', 'aria-label': `${kindWord(r)} @${r.name}`}, cref(r, {big: true})),
      badge(kindWord(r), 'strong'), expiring(r) ? pill('warn', icon('clock'), `Expires ${untilWords(r['expires-at'])}`) : null);
    const sub = h('p', {class: 'sub'}, madeFrom(r, list), '.');
    const side = h('div', {class: 'side'}, h('a', {class: 'btn sm primary', href: href({c, r: r.name})}, icon('catalog'), `Read ${c} on @${r.name}`),
      copyButton(`${c}@${r.name}`, 'Copy warehouse'),
      may && r.kind === 'branch' ? h('button', {class: 'btn sm', type: 'button', onclick: () => renewDialog(c, r)}, 'Renew') : null,
      may ? h('button', {class: 'btn sm ghost', type: 'button', 'data-act': 'ref-delete', onclick: () => deleteDialog(c, r, list)}, icon('x'), 'Delete') : null);
    const kpis = h('div', {class: 'kpis ref-kpis'},
      h('div', {class: 'kpi'}, h('p', {class: 'kpi-l'}, 'Point'), h('p', {class: 'kpi-v sm'}, cut ? refAt(cut) : '–'),
        h('p', {class: 'kpi-d'}, isMain(r.from) ? 'main as it was then' : `@${r.from}'s point; its commits to ${refAt(r['created-at'])}`)),
      r.kind === 'branch' ? h('div', {class: 'kpi'}, h('p', {class: 'kpi-l'}, 'Changed'), h('p', {class: 'kpi-v'}, fmt(total)),
        h('p', {class: 'kpi-d'}, total > changed.length ? `${plural(commits, 'commit')} on the ${fmt(changed.length)} you may describe`
          : `${plural(commits, 'commit')} on the branch`))
        : h('div', {class: 'kpi'}, h('p', {class: 'kpi-l'}, 'Changes'), h('p', {class: 'kpi-v'}, 'none'), h('p', {class: 'kpi-d'}, 'a tag is read-only')),
      h('div', {class: 'kpi' + (expiring(r) ? ' is-warn' : '')}, h('p', {class: 'kpi-l'}, 'Expires'),
        h('p', {class: 'kpi-v sm'}, r.kind === 'tag' ? 'never' : refAt(r['expires-at'])),
        h('p', {class: 'kpi-d'}, r.kind === 'tag' ? 'until deleted' : `${untilWords(r['expires-at'])} · at most ${dayMon(r['max-expires-at'])}`)),
      h('div', {class: 'kpi'}, h('p', {class: 'kpi-l'}, 'Warehouse'), h('p', {class: 'kpi-v sm mono'}, `${c}@${r.name}`),
        h('p', {class: 'kpi-d'}, r.kind === 'tag' ? 'read endpoints only' : 'reads and commits')));
    const tabs = r.kind === 'branch' ? [['', 'Changes', total], ['merge', 'Merge'], ['record', 'Record']] : [['', 'What it keeps'], ['record', 'Record']];
    const key = tabs.some(([k]) => k === (tab || '')) ? tab || '' : '';
    const body = h('div', {class: 'tab-body'});
    if (key === 'merge') load(body, () => mergeTab(c, r, list));
    else if (key === 'record') load(body, () => recordTab(c, r));
    else if (r.kind === 'branch') load(body, () => changesTab(c, r, total));
    else fill(body, tagKeeps(c, r, cut));
    return [h('div', {class: 'ph'}, crumbs, title, sub, side), kpis, tabsNav('Ref', tabs, key, k => refHref(c, r.name, k || null)), body];
  });
  shown.then(() => {
    const box = $('[data-result]', main);
    if (box && FRESH.delete(resultKey(c, name))) box.focus();
  });
  return main;
}

const pathOfItem = t => [...(t.namespace || []), t.name].join('.');
const tableHref = (c, t, ref, tab) => objectHref({catalog: c, kind: t.kind === 'view' ? 'view' : 'table', namespace: t.namespace, name: t.name, ref}, tab);

/** The dry run, which needs only a holding in the catalog; null when the catalog will not answer it. */
async function dryRun(c, name) {
  try {
    return await api.mergeRef(c, name, true);
  } catch (error) {
    return {error};
  }
}

async function changesTab(c, r, total) {
  const [read, plan] = await Promise.all([api.ref(c, r.name), dryRun(c, r.name)]);
  const changed = read.changed || [];
  const planned = new Map(((plan.body || {}).tables || []).map(t => [pathOfItem(t), t]));
  const rows = changed.map(t => {
    const p = planned.get(pathOfItem(t));
    const since = p ? (p['main-commits'] || {}).count : null;
    const times = p ? plural(since || p['main-version'] - p['cut-version'], 'time') : '';
    // A table main only maintained since the point still moves (rebased; Paul, 2026-11-22), so it is no warning.
    const state = !p ? muted('–') : p['dropped-at'] || p['main-version'] === undefined ? pill('bad', 'Dropped on main')
      : p['main-version'] === p['cut-version'] ? pill('ok', icon('check'), 'Main unchanged')
        : p.moves ? pill(null, `Main maintained ${times}`) : pill('warn', `Main moved ${times}`);
    return h('tr', {class: p && p.moves ? null : p ? 'is-warn' : null, 'data-path': pathOfItem(t)},
      h('td', {class: 'name'}, h('a', {href: tableHref(c, t, r.name)}, pathOfItem(t))),
      h('td', {class: 'num'}, fmt(t['cut-version'])), h('td', {class: 'num'}, h('b', null, fmt(t.version))),
      h('td', null, h('a', {href: tableHref(c, t, r.name, 'metadata-log')}, plural(t.version || 0, 'commit'))),
      h('td', null, state));
  });
  return [h('div', {class: 'panel', id: 'ref-changes'}, h('h2', null, 'What the branch changed ',
    h('span', {class: 'meta'}, `${plural(total, 'table')} with pointers of its own; GET …/refs/${r.name}`)),
  rows.length ? tbl('stack-line ref-changes', ['Table', ['At the point', 'num'], ['On the branch', 'num'], 'Its commits', 'Main since'], rows)
    : h('p', {class: 'muted'}, total ? 'None you may describe.' : 'None yet: the branch has not committed.'),
  rows.length && total > changed.length ? h('p', {class: 'muted', 'data-hidden-count': total - changed.length},
    `And ${fmt(total - changed.length)} more you may not describe.`) : null,
  h('p', {class: 'fine'}, 'At the point is main\'s version of each table then; on the branch, the branch\'s own, which number from 1. '
    + 'Every other table reads as main had it at the point. ',
    h('a', {href: refHref(c, r.name, 'merge')}, 'Preview the merge'), ' to see which tables would move.'),
  plan.error ? failure(plan.error, {compact: true}) : null)];
}

function tagKeeps(c, r, cut) {
  return h('div', {class: 'grid-21'},
    panel('What the tag keeps', null, facts([
      ['Tables and views', `every table and view in ${c} as at ${cut ? refAt(cut) : 'its point'}`
        + (isMain(r.from) ? '' : `, with @${r.from}'s own changes to that time`)],
      ['Files', 'every file these tables reach is kept from cleanup while the tag exists'],
    ]), h('div', {class: 'refusal-acts'}, h('a', {class: 'btn sm primary', href: href({c, r: r.name})}, `Read ${c} on @${r.name}`))),
    panel('Engines', null, h('pre', {class: 'raw'}, `warehouse=${c}@${r.name}`),
      h('p', {class: 'fine'}, 'Read-only credentials, and read endpoints only. A write through the tag is refused before it reaches a table:'),
      h('div', {class: 'refusal compact'}, h('p', {class: 'refusal-code'}, '400 BadRequestException'),
        h('p', {class: 'refusal-msg'}, `updateTable is refused on tag ${r.name} of catalog ${c}: a tag is read-only`))));
}

async function recordTab(c, r) {
  const where = panel('Where these records are', null, h('p', {class: 'fine'}, 'A change to a ref is one ', code('nuna.catalog.ref'),
    ' record whose subject is the ref\'s id, ', code(r.id.slice(0, 8) + '…'), ', so a deleted ref\'s name used again never shares its history. '
    + 'Commits and reads through the ref name it in their ', code('branch'), ' field.'));
  const records = [];
  let pageToken = null;
  try {
    for (let i = 0; i < 4; i++) {
      const answer = await api.auditFeed(c, {type: 'nuna.catalog.ref', pageToken});
      records.push(...(answer.records || []).filter(x => x.subject === r.id || (x.data && x.data.ref === r.id)));
      pageToken = answer['next-page-token'];
      if (!pageToken) break;
    }
  } catch (error) {
    return h('div', {class: 'grid-21'}, h('div', {class: 'panel'}, h('h2', null, `What happened to @${r.name}`), failure(error)), where);
  }
  return h('div', {class: 'grid-21'}, h('div', {class: 'panel', id: 'ref-record'}, h('h2', null, `What happened to @${r.name} `,
    h('span', {class: 'meta'}, 'newest first')),
  records.length ? h('div', {class: 'feed'}, records.map(x => h('div', {class: 'feed-row'}, h('span', {class: 't'}, when(x.time)),
    h('span', {class: 'w'}, h('b', null, ((x.data || {}).actor || {}).principal || '–')),
    h('div', null, badge(x.type), h('p', {class: 'what'}, refWords(x.data || {}))), h('span', null))))
    : h('p', {class: 'muted'}, pageToken ? ['Not in the newest ref records; see the ', h('a', {href: pageHref('audit', c)}, 'audit feed'), '.']
      : 'No record of it in the audit tables yet: they are about a minute behind.'),
  records.length && pageToken ? h('p', {class: 'fine'}, 'Older records are not in the newest ref records read here; see the ',
    h('a', {href: pageHref('audit', c)}, 'audit feed'), '.') : null), where);
}

// ---- the merge ----

const why = (x, r) => x.moves
  ? (x['main-version'] === x['cut-version']
    ? [h('b', null, 'Moves. '), `Changed only on the branch: main is still at version ${fmt(x['cut-version'])}, the point's, so it takes the file of `
      + `@${r.name}'s version ${fmt(x['branch-version'])} as its version ${fmt(x.version)}.`]
    : [h('b', null, 'Moves, over main\'s maintenance. '), `Main went from version ${fmt(x['cut-version'])} to ${fmt(x['main-version'])} since the `
      + `point by compaction or snapshot expiry alone, so it takes @${r.name}'s change over them as its version ${fmt(x.version)}.`])
  : x['dropped-at'] || x['main-version'] === undefined
    ? [h('b', null, 'Conflict: dropped on main. '), x['dropped-at'] ? `It was dropped on ${refAt(x['dropped-at'])}. ` : '',
      'Main has no table to move, and a merge never makes one. ', muted(`The catalog: ${x.conflict}.`)]
    : [h('b', null, 'Conflict: changed on both. '), `Main went from version ${fmt(x['cut-version'])} to ${fmt(x['main-version'])} since the point, `
      + `${plural((x['main-commits'] || {}).count || x['main-version'] - x['cut-version'], 'commit')}`
      + ((x['main-commits'] || {}).by ? `; the latest by ${x['main-commits'].by.join(', ')}` : '')
      + `. @${r.name}'s version ${fmt(x['branch-version'])} was made from the point's version ${fmt(x['cut-version'])}, so taking it would undo main's. `,
    muted(`The catalog: ${x.conflict}.`)];

function mergeMap(x, r) {
  const W = 520;
  const H = 56;
  const y0 = 16;
  const y1 = 42;
  const dropped = !x.moves && (x['dropped-at'] || x['main-version'] === undefined);
  const own = x['branch-version'] || 0;
  const since = Math.min(60, (x['main-commits'] || {}).count || Math.max(0, (x['main-version'] || x['cut-version']) - x['cut-version']));
  const svg = s('svg', {viewBox: `0 0 ${W} ${H}`, class: 'mm', role: 'img', 'aria-label': `${pathOfItem(x)}: ${plural(own, 'commit')} on the branch; `
    + `${dropped ? 'dropped on main' : since ? plural(since, 'commit') + ' on main since the point' : 'no commit on main since the point'}`});
  const span = W - 120;
  svg.append(s('line', {class: 'mm-main' + (dropped ? ' gone' : ''), x1: 0, y1: y0, x2: dropped ? W - 60 : W - 30, y2: y0}));
  svg.append(s('path', {class: 'mm-br', d: `M24 ${y0} C 34 ${y0}, 30 ${y1}, 44 ${y1} H ${W - 52}`}));
  svg.append(s('circle', {class: 'mm-cut', cx: 24, cy: y0, r: 4.5}));
  for (let i = 0; i < since; i++) {
    const X = 54 + (i + 0.5) / since * span;
    svg.append(s('line', {class: 'mm-mc', x1: X, y1: y0 - 6, x2: X, y2: y0 + 6}));
  }
  for (let i = 0; i < Math.min(own, 60); i++) svg.append(s('circle', {class: 'mm-bc', cx: 54 + (i + 0.5) / Math.min(own, 60) * span, cy: y1, r: 4}));
  if (x.moves) {
    svg.append(s('path', {class: 'mm-arrow', d: `M${W - 52} ${y1} C ${W - 36} ${y1}, ${W - 40} ${y0}, ${W - 26} ${y0}`}),
      s('path', {class: 'mm-head', d: `M${W - 30} ${y0 - 4} l6 4 l-6 4`}));
  } else {
    const dx = dropped ? W - 60 : W - 26;
    svg.append(s('path', {class: 'mm-x', d: `M${dx - 5} ${y0 - 5} l10 10 M${dx + 5} ${y0 - 5} l-10 10`}));
  }
  return svg;
}

/** A moving table's checks as a commit on main: the caller's right, then main's commit rules; the icon is a cross if either fails. */
function mergeChecks(x) {
  const refused = (x.right && !x.right.allowed) || (x.verdict && !x.verdict.allowed);
  const right = x.right ? (x.right.allowed ? `your groups hold ${x.right.needs}` : h('b', null, `your groups hold no ${x.right.needs}`)) : null;
  const rules = x.verdict && !x.verdict.allowed ? h('b', null, `refused: ${x.verdict.message}`) : 'main\'s commit rules pass';
  return h('p', {class: 'mm-checks fine'}, icon(refused ? 'x' : 'check'), ' Judged as a commit on main: ', right, right ? ', ' : null, rules, '.');
}

async function mergeTab(c, r, list) {
  const done = RESULTS.get(resultKey(c, r.name));
  let answer;
  try {
    answer = await api.mergeRef(c, r.name, true);
  } catch (error) {
    return [done ? done() : null, failure(error, {big: true})];
  }
  const plan = answer.body;
  const tables = plan.tables || [];
  const moves = tables.filter(x => x.moves);
  const conflicts = tables.filter(x => !x.moves);
  const verdictOf = plan.answer || {};
  const blocked = verdictOf.code !== 200;
  const may = mayRefs(list);
  const rightsMissing = moves.filter(x => x.right && !x.right.allowed).map(pathOfItem);
  const lim = list.limits || {};
  const tried = at(Date.now(), true);
  const verdict = blocked
    ? h('div', {class: 'refusal merge-verdict', role: 'status', 'data-verdict': 'blocked'},
      h('p', {class: 'refusal-h'}, icon('x'), verdictOf.type === 'RefMergeConflictException'
        ? `@${r.name} cannot merge: ${plural(conflicts.length, 'table')} changed on main too`
        : `@${r.name} cannot merge now`),
      h('p', {class: 'refusal-code'}, `${verdictOf.code} ${verdictOf.type || ''} · POST …/refs/${r.name}/merge, dry run`),
      h('p', {class: 'refusal-msg'}, verdictOf.message || ''),
      h('p', {class: 'refusal-meta'}, h('span', null, 'X-Request-ID ' + (answer.requestId || '')), h('span', null, tried),
        h('span', null, 'a dry run: nothing moved, nothing audited')),
      h('p', {class: 'fine'}, verdictOf.type === 'RefMergeConflictException' ? 'Every table moves or none does. While one conflicts, the merge is refused whole.'
        : verdictOf.type === 'RefLimitException' ? `@${r.name} changed ${plural(plan.changed, 'table')}; a merge moves at most ${fmt(plan['max-tables'])}.`
          : 'Main\'s rules refuse one table\'s change, so the catalog would refuse the merge whole.'))
    : h('div', {class: 'granted merge-verdict', role: 'status', 'data-verdict': 'ready'},
      h('p', {class: 'refusal-h'}, icon('check'), `Ready: ${plural(moves.length, 'table')} would move to main in one transaction`),
      h('p', {class: 'fine'}, moves.every(x => x['main-version'] === x['cut-version'])
        ? 'Main has not committed to any of them since the point, so main takes each one as the branch has it.'
        : 'Main has committed only its own maintenance to them since the point, so main takes each one as the branch changed it.'),
      h('p', {class: 'refusal-meta'}, h('span', null, `200 OK · POST …/refs/${r.name}/merge, dry run`), h('span', null, 'X-Request-ID ' + (answer.requestId || '')),
        h('span', null, tried), h('span', null, 'nothing moved, nothing audited')));
  const rows = tables.map(x => h('div', {class: 'mm-row ' + (x.moves ? 'moves' : x['dropped-at'] || x['main-version'] === undefined ? 'dropped' : 'moved'),
    'data-path': pathOfItem(x)},
  h('div', {class: 'mm-name'}, h('a', {href: tableHref(c, x, r.name)}, code(pathOfItem(x))), h('p', {class: 'fine'}, why(x, r))),
  h('div', {class: 'mm-vis'}, mergeMap(x, r)),
  h('dl', {class: 'mm-v'}, h('dt', null, 'point'), h('dd', null, fmt(x['cut-version'])), h('dt', null, 'main'),
    h('dd', null, x['main-version'] === undefined ? 'dropped' : fmt(x['main-version'])), h('dt', null, '@' + r.name), h('dd', null, fmt(x['branch-version']))),
  x.moves ? mergeChecks(x) : null));
  const raw = h('div', {hidden: true}, jsonPre(plan));
  const rawB = h('button', {class: 'btn sm ghost', type: 'button', 'aria-expanded': 'false', onclick: () => {
    raw.hidden = !raw.hidden;
    rawB.setAttribute('aria-expanded', String(!raw.hidden));
    rawB.textContent = raw.hidden ? 'Show the catalog\'s answer' : 'Hide the catalog\'s answer';
  }}, 'Show the catalog\'s answer');
  const tagsFrom = live(list).filter(t => t.kind === 'tag' && t.from === r.name);
  const steps = blocked && conflicts.length ? h('p', {class: 'fine'}, h('b', null, `Nothing moves while ${plural(conflicts.length, 'table')} ${conflicts.length === 1 ? 'conflicts' : 'conflict'}.`),
    moves.length ? ` The catalog refuses the merge whole; the other ${plural(moves.length, 'table')} ${moves.length === 1 ? 'waits' : 'wait'} with it. Once nothing conflicts, the merge would:`
      : ' Once nothing conflicts, the merge would:') : null;
  const steps2 = h('ol', {class: 'tx-steps'},
    tables.map(x => h('li', null, 'Main\'s pointer for ', code(pathOfItem(x)), x.moves
      ? ` moves from version ${fmt(x['main-version'])} to ${fmt(x.version)}, naming the file @${r.name} wrote at its version ${fmt(x['branch-version'])}.`
      : x['main-version'] === undefined || x['dropped-at'] ? ' cannot move: main dropped the table.'
        : ` cannot move: main is at version ${fmt(x['main-version'])}, not the point's ${fmt(x['cut-version'])}.`,
    x.moves ? null : [' ', pill('bad', 'conflicts now')])),
    plan.hidden ? h('li', null, `${plural(plan.hidden, 'more table')} you may not describe ${plan.hidden === 1 ? 'moves or conflicts' : 'move or conflict'} with them.`) : null,
    h('li', null, cref(r), ' ends: its name is free and ', code(`${c}@${r.name}`), ' answers 404.',
      tagsFrom.length ? [' Tags made from it, ', tagsFrom.map(t => cref(t)), ', stay.'] : ''),
    h('li', null, 'The audit gets one ', code('nuna.catalog.ref'), ` record (merged, ${plural(tables.length + (plan.hidden || 0), 'table')}) and one commit record per table, on main.`));
  const btn = h('button', {class: 'btn primary', type: 'button', 'data-act': 'ref-merge', disabled: blocked || !plan['may-merge'] ? true : null,
    onclick: () => mergeDialog(c, r, plan)}, icon('branch'), `Merge @${r.name} into main`);
  const actBox = h('div', {class: 'panel merge-act'}, h('h2', null, 'In one transaction'), steps, steps2,
    !blocked && may && rightsMissing.length ? h('p', {class: 'fine warn-ink'}, h('b', null, `Your groups hold no write on ${rightsMissing.join(', ')}.`),
      ' The catalog would refuse the merge with 403, so the console offers none.') : null,
    h('p', {class: 'fine'}, `Every table moves or none does; at most ${fmt(plan['max-tables'] || lim['max-merge-tables'])} tables a merge (nuna.refs.max-merge-tables). `
      + 'If main commits to one of them between this preview and the merge, the catalog plans again, and the new conflict refuses the merge with 409.'),
    may ? h('div', {class: 'refusal-acts'}, btn, blocked ? h('span', {class: 'fine'}, 'Refused while a table conflicts.')
      : h('span', {class: 'fine'}, 'Needs create on ', code(c), ' and write on each table.'))
      : h('p', {class: 'fine'}, h('b', null, `Merging needs create on ${c}`), ' and write on every table it moves. Your groups hold none, so the console offers no merge.'));
  const changedOnBoth = conflicts.filter(x => !(x['dropped-at'] || x['main-version'] === undefined));
  const settle = blocked && conflicts.length ? panel('What you can do', null, h('ul', {class: 'fine settle'},
    changedOnBoth.length ? h('li', null, h('b', null, 'Start again from main: '), `make a branch from main now and redo @${r.name}'s change to `
      + `${changedOnBoth.map(x => x.name).join(', ')} on it. Main's newer commits come with it.`) : null,
    changedOnBoth.length < conflicts.length ? h('li', null, h('b', null, 'A dropped table cannot come back by merge. '), 'If it should exist, make it on main again first.') : null,
    h('li', null, h('b', null, 'Keep it as it is: '), `@${r.name} stays readable until it expires, ${refAt(r['expires-at'])}, or tag @${r.name} to keep what it holds readable after it expires.`)),
  may ? h('div', {class: 'refusal-acts'}, h('button', {class: 'btn sm', type: 'button', onclick: () => makeDialog(c, list, {kind: 'branch', name: r.name + '-2'})},
    icon('branch'), 'Make a branch from main')) : null) : null;
  return [done ? done() : null, h('div', {class: 'grid-21 merge-grid'},
    h('div', {class: 'stack'}, verdict,
      h('div', {class: 'panel'}, h('div', {class: 'panel-h'}, h('h2', null, 'Table by table ',
        h('span', {class: 'meta'}, `${plural(moves.length, 'move')} · ${plural(conflicts.length, 'conflict')}`)), rawB),
      h('p', {class: 'mm-key fine'}, h('i', {class: 'k-cut'}), 'the point (the cut: main as it was when the branch was made) ', h('i', {class: 'k-bc'}),
        `a commit on @${r.name} `, h('i', {class: 'k-mc'}), 'a commit on main since ', h('i', {class: 'k-mv'}), 'moves to main ', h('i', {class: 'k-x'}), 'conflict'),
      h('div', {class: 'mm-rows'}, rows), raw)),
    h('div', {class: 'stack'}, actBox, settle))];
}

function mergeDialog(c, r, plan) {
  const moves = (plan.tables || []).filter(x => x.moves);
  const body = h('div', null, h('p', null, `${plural(moves.length + (plan.hidden || 0), 'table')} move to main in one transaction, and `, cref(r), ' ends.'),
    tbl('', ['Table', ['Main now', 'num'], ['Main after', 'num']], moves.map(x => h('tr', null, h('td', {class: 'name'}, pathOfItem(x)),
      h('td', {class: 'num'}, fmt(x['main-version'])), h('td', {class: 'num'}, h('b', null, fmt(x.version)))))),
    h('p', {class: 'fine'}, 'Main\'s later commits build on these. Nothing on main is lost: every earlier version stays in each table\'s commit journal.'),
    dialogForm(null, 'Merge into main', async (form, out) => {
      let answer;
      try {
        answer = await api.mergeRef(c, r.name, false);
      } catch (error) {
        // The refusal stays in the dialog; the preview behind it reads again once the dialog closes.
        fill(out, failure(error, {note: error.status === 409 && error.type === 'RefMergeConflictException'
          ? 'Not a denial: the merge record is not written. Main moved since the preview, which reads again when you close this.' : null}));
        if (error.status === 409) {
          $('button[type=submit]', form).hidden = true;
          form.closest('dialog').addEventListener('close', () => window.dispatchEvent(new HashChangeEvent('hashchange')), {once: true});
        }
        return;
      }
      forgetRefs(c);
      const moved = (answer.body || {}).tables || [];
      const was = new Map(moves.map(x => [pathOfItem(x), x]));
      const time = at(Date.now(), true);
      const result = () => h('div', {class: 'stack', 'data-merged': r.name, 'data-result': '', tabindex: '-1'},
        granted(`Merged @${r.name} into main at ${time}`, answer, `${plural(moved.length, 'table')} moved in one transaction; the branch ended and ${c}@${r.name} answers 404.`),
        panel('What main has now', null, tbl('', ['Table', ['Main was', 'num'], ['Main is', 'num'], 'Its file'], moved.map(t => h('tr', null,
          h('td', {class: 'name'}, h('a', {href: tableHref(c, t, null, 'metadata-log')}, pathOfItem(t))),
          h('td', {class: 'num'}, fmt((was.get(pathOfItem(t)) || {})['main-version'] ?? t['main-version'])), h('td', {class: 'num'}, h('b', null, fmt(t.version))),
          h('td', null, h('span', {class: 'fine'}, `the one @${r.name} wrote at its version ${fmt(t['branch-version'])}`))))),
        h('p', {class: 'fine'}, 'The audit feed has the merge and one commit per table, with this request id.')));
      RESULTS.set(resultKey(c, r.name), result);
      FRESH.add(resultKey(c, r.name));
      form.closest('dialog').close();
      const target = refHref(c, r.name, 'merge');
      if (location.hash === target) window.dispatchEvent(new HashChangeEvent('hashchange'));
      else location.hash = target;
    }));
  // Focus starts on Cancel, so one Enter never merges.
  $('.dlg-acts .btn.ghost', openDialog(`Merge @${r.name} into main`, body, {wide: true})).focus();
}

// ---- make, renew and delete ----

export function makeDialog(c, list, preset = {}) {
  let kind = preset.kind || 'branch';
  const lim = list.limits || {};
  const idle = daysOf(lim['branch-idle-expiry'], 30);
  const life = daysOf(lim['branch-max-life'], 180);
  const seg = h('span', {class: 'seg', role: 'group', 'aria-labelledby': 'make-kind'}, ['branch', 'tag'].map(k =>
    h('button', {type: 'button', 'aria-pressed': String(k === kind), 'data-k': k}, icon(k), k === 'branch' ? 'Branch' : 'Tag')));
  const name = h('input', {name: 'name', type: 'text', placeholder: 'a name, such as release-2026-11', autocomplete: 'off', spellcheck: 'false',
    value: preset.name || null});
  const from = select('from', [['main', 'main, as it is now'], ...live(list).map(r => [r.name, `@${r.name} (${r.kind})`])], 'main');
  const hint = h('div', {class: 'ref-hint'});
  const draw = () => {
    for (const b of $$('button', seg)) b.setAttribute('aria-pressed', String(b.dataset.k === kind));
    const nm = name.value.trim() || '<name>';
    const br = live(list).filter(r => r.kind === 'branch').length;
    const tg = live(list).filter(r => r.kind === 'tag').length;
    const full = kind === 'branch' ? br >= lim['max-branches'] : tg >= lim['max-tags'];
    hint.classList.toggle('full', full);
    fill(hint, full ? h('p', {class: 'fine warn-ink'}, h('b', null, kind === 'branch' ? `${c} holds ${fmt(br)} of ${fmt(lim['max-branches'])} branches.`
      : `${c} holds ${fmt(tg)} of ${fmt(lim['max-tags'])} tags.`), kind === 'branch' ? ' The catalog refuses another until one is merged, deleted or expires.'
      : ' The catalog refuses another until one is deleted.') : null,
    h('p', {class: 'fine'}, kind === 'branch'
      ? `A branch keeps every table in ${c} as it is now and takes commits of its own. It expires ${idle} days after its last commit or renewal, `
        + `never later than ${life} days from now. ${fmt(br)} of ${fmt(lim['max-branches'])} branches are open.`
      : `A tag keeps every table in ${c} as it is now, read-only. It never expires. ${fmt(tg)} of ${fmt(lim['max-tags'])} tags are used.`),
    h('p', {class: 'fine'}, 'Engines reach it as ', code(`warehouse=${c}@${nm}`), '.'));
  };
  seg.addEventListener('click', e => {
    const b = e.target.closest('button');
    if (b) { kind = b.dataset.k; draw(); }
  });
  name.addEventListener('input', draw);
  draw();
  // Not field(): a label around the buttons would name the first of them, so the group names itself.
  const kindField = h('div', {class: 'dlg-f'}, h('span', {id: 'make-kind'}, 'Kind'), seg);
  const body = h('div', null, dialogForm([kindField, field('Name', h('span', {class: 'field mono'}, name),
    'Letters, digits, dot, dash, underscore; no @ and no /. Names compare without case.'),
  field('From', from, 'A ref made from a branch takes that branch as it is now.'), hint], 'Make it', async (form, out) => {
    const nm = name.value.trim();
    if (!nm) { fill(out, h('p', {class: 'fine grant-hint'}, 'Name the ref.')); return; }
    const source = form.elements.from.value;
    let answer;
    try {
      answer = await api.makeRef(c, nm, kind, source);
    } catch (error) {
      const taken = findRef(list, nm);
      const kept = heldRef(list, nm);
      const note = error.type === 'RefLimitException' ? [h('b', null, kind === 'branch' ? 'Merge or delete a branch first' : 'Delete a tag first'),
        ', or ask an operator to raise the setting.']
        : error.type === 'AlreadyExistsException' && kept ? [h('b', null, `@${kept.name} expired at ${refAt(kept['expires-at'])}.`),
          ' The worker ends it on its next run; the name is free then.']
          : error.type === 'AlreadyExistsException' ? [h('b', null, 'Names compare without case.'), ' Pick another',
            taken ? [', or ', h('a', {href: refHref(c, taken.name)}, `open @${taken.name}`)] : null, '.'] : null;
      fill(out, failure(error, {note: note ? h('span', null, note) : null}));
      return;
    }
    forgetRefs(c);
    const made = answer.body || {name: nm};
    fill(out, granted(`Made the ${kind} @${made.name}`, answer, h('span', null, h('a', {href: refHref(c, made.name)}, `Open @${made.name}`), ' · ',
      h('a', {href: href({c, r: made.name})}, `Read ${c} on @${made.name}`))));
    $('button[type=submit]', form).hidden = true;
    refreshSoon();
  }));
  openDialog('Make a branch or tag', body);
}

export async function renewDialog(c, r) {
  const list = await refsOf(c).catch(() => ({limits: {}}));
  const idle = daysOf((list.limits || {})['branch-idle-expiry'], 30);
  const life = daysOf((list.limits || {})['branch-max-life'], 180);
  const next = Math.min(Date.now() + idle * DAY, ms(r['max-expires-at']));
  const atCap = next <= ms(r['expires-at']);
  const body = h('div', null, facts([
    ['Now', `expires ${refAt(r['expires-at'])}, ${untilWords(r['expires-at'])}`],
    ['After', atCap ? h('b', null, 'unchanged: it is at the end of its whole life') : [h('b', null, `expires ${refAt(new Date(next).toISOString())}`),
      `, ${idle} days from now`]],
    ['Whole life', `never later than ${refAt(r['max-expires-at'])}, ${life} days after it was made`],
  ]), dialogForm(null, 'Renew', async (form, out) => {
    const answer = await api.renewRef(c, r.name);
    forgetRefs(c);
    const after = (answer.body || {})['expires-at'];
    fill(out, after && after === r['expires-at']
      ? granted(`@${r.name} is unchanged`, answer, 'A renewal never shortens a branch\'s life, and this one is at its end. Merge it, or make a new branch.')
      : granted(`Renewed @${r.name}: it expires ${after ? refAt(after) : ''}`, answer));
    $('button[type=submit]', form).hidden = true;
    refreshSoon();
  }));
  openDialog(`Renew @${r.name}`, body);
}

export function deleteDialog(c, r, list) {
  // The list's refs carry changed-count; one ref's own answer (its page) lists them as changed instead.
  const n = Math.max(r['changed-count'] || 0, (r.changed || []).length);
  const kids = live(list).filter(t => t.from === r.name);
  const body = h('div', null,
    h('p', null, r.kind === 'tag' ? ['Engines reading ', code(`${c}@${r.name}`), ' get 404 from then on. The tables themselves are untouched.']
      : n ? [h('b', null, `Its changes to ${plural(n, 'table')} were never merged and are lost.`), ' Main never took them. Engines reading ', code(`${c}@${r.name}`), ' get 404.']
        : ['It has no changes of its own. Engines reading ', code(`${c}@${r.name}`), ' get 404.']),
    h('p', {class: 'fine'}, 'Files only this ref reaches become unreferenced; the orphan scan removes them once they are older than its minimum age. The name is free at once.',
      kids.length ? [' ', kids.map(k => cref(k)), kids.length === 1 ? ' was made from it and stays.' : ' were made from it and stay.'] : ''),
    dialogForm(null, `Delete @${r.name}`, async (form, out) => {
      const answer = await api.deleteRef(c, r.name);
      forgetRefs(c);
      const time = at(Date.now(), true);
      RESULTS.set(resultKey(c, r.name), () => h('div', {'data-result': '', tabindex: '-1'}, granted(`Deleted @${r.name} at ${time}`, answer,
        h('span', null, 'It is gone from every route; its record stays in the ', h('a', {href: pageHref('audit', c)}, 'audit feed'), '.'))));
      FRESH.add(resultKey(c, r.name));
      fill(out, granted(`Deleted @${r.name}`, answer));
      $('button[type=submit]', form).hidden = true;
      refreshSoon(r.name);
    }));
  $('button[type=submit]', body).classList.add('danger');
  // Focus starts on Cancel, so one Enter never deletes.
  $('.dlg-acts .btn.ghost', openDialog(`Delete the ${kindWord(r)} @${r.name}`, body)).focus();
}

/** Draws the page again once a dialog's answer has been read; a page read through a deleted ref goes to main. */
function refreshSoon(deleted) {
  const dlg = $('dialog.dlg');
  const again = () => {
    const p = new URLSearchParams(location.hash.slice(1));
    if (deleted && (p.get('r') || '').toLowerCase() === deleted.toLowerCase()) {
      p.delete('r');
      location.hash = '#' + p.toString();
    } else {
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    }
  };
  if (dlg) dlg.addEventListener('close', again, {once: true});
  else again();
}

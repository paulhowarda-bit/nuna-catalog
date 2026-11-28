// Building the page. Every string from the catalog goes in as text (a Text node or an attribute value), never as
// markup, and nothing is styled inline: this module makes the elements, and it has no innerHTML and no style.
// The shapes (panels, tables, refusals, dialogs) are the prototype's (docs/design/console/prototype.html, D45).

const NS = 'http://www.w3.org/2000/svg';

export const $ = (q, r = document) => r.querySelector(q);
export const $$ = (q, r = document) => Array.from(r.querySelectorAll(q));

/**
 * h('div', {class: 'x', onclick: fn}, 'text', child, [more]) -> HTMLElement. Attributes: on* take functions;
 * everything else is set with setAttribute; null, undefined and false are skipped, true is an empty value.
 * Children may be strings, numbers, nodes, arrays, or null/undefined/false (skipped).
 */
export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  setAttrs(el, attrs);
  append(el, children);
  return el;
}

/** An SVG element; numbers are rounded to two places. */
export function s(tag, attrs, text) {
  const el = document.createElementNS(NS, tag);
  for (const [name, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (name.startsWith('on') && typeof value === 'function') el.addEventListener(name.slice(2), value);
    else if (name === 'style') throw new Error('not allowed: style');
    else el.setAttribute(name, typeof value === 'number' ? String(+value.toFixed(2)) : String(value));
  }
  if (text !== null && text !== undefined) el.textContent = String(text);
  return el;
}

function setAttrs(el, attrs) {
  for (const [name, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (name.startsWith('on') && typeof value === 'function') {
      el.addEventListener(name.slice(2), value);
    } else if (name === 'style' || name === 'innerHTML' || name === 'outerHTML') {
      throw new Error('not allowed: ' + name);
    } else {
      el.setAttribute(name, value === true ? '' : String(value));
    }
  }
}

function append(el, children) {
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    if (Array.isArray(child)) append(el, child);
    else if (child instanceof Node) el.appendChild(child);
    else el.appendChild(document.createTextNode(String(child)));
  }
}

/** Replaces an element's children. */
export function fill(el, ...children) {
  el.replaceChildren();
  append(el, children);
  return el;
}

/** An icon from the page's sprite. */
export function icon(id, cls = 'ic') {
  const svg = s('svg', {class: cls, 'aria-hidden': 'true'});
  svg.append(s('use', {href: '#i-' + id}));
  return svg;
}

// ---- words and numbers ----

export const fmt = n => Number(n).toLocaleString('en-GB');
export const plural = (n, word, words) => `${fmt(n)} ${n === 1 ? word : words || word + 's'}`;

/** A size in bytes, in the largest unit that keeps it at 1 or more. */
export function sizeWords(bytes) {
  const n = Number(bytes);
  if (!Number.isFinite(n)) return null;
  const units = ['bytes', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${i ? v.toFixed(v < 10 ? 1 : 0) : v} ${units[i]}`;
}
export const ordinal = n => n + (n % 10 === 1 && n % 100 !== 11 ? 'st' : n % 10 === 2 && n % 100 !== 12 ? 'nd'
  : n % 10 === 3 && n % 100 !== 13 ? 'rd' : 'th');
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const p2 = n => String(n).padStart(2, '0');

function dateOf(value) {
  if (value === null || value === undefined || value === '') return null;
  const date = typeof value === 'number' ? new Date(value) : new Date(String(value));
  return Number.isNaN(date.getTime()) ? null : date;
}

/** A time from epoch milliseconds or an ISO string, as local date and time to the second. */
export function when(value) {
  const date = dateOf(value);
  if (!date) return value === null || value === undefined ? '' : String(value);
  return `${date.getFullYear()}-${p2(date.getMonth() + 1)}-${p2(date.getDate())} `
    + `${p2(date.getHours())}:${p2(date.getMinutes())}:${p2(date.getSeconds())}`;
}

/** A short local time: "21 Nov 06:50", or "06:50:12" today when `seconds`. */
export function at(value, seconds) {
  const date = dateOf(value);
  if (!date) return value === null || value === undefined ? '' : String(value);
  const now = new Date();
  const time = `${p2(date.getHours())}:${p2(date.getMinutes())}` + (seconds ? ':' + p2(date.getSeconds()) : '');
  if (date.toDateString() === now.toDateString()) return time;
  return `${date.getDate()} ${MONTHS[date.getMonth()]}${date.getFullYear() === now.getFullYear() ? '' : ' ' + date.getFullYear()} ${time}`;
}

/** A duration in milliseconds, in words a person reads: "41 s", "4 m 12 s", "2 h 5 m". */
export function took(ms) {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return '';
  const sec = Math.round(ms / 1000);
  if (sec < 60) return sec + ' s';
  if (sec < 3600) return `${Math.floor(sec / 60)} m ${sec % 60} s`;
  return `${Math.floor(sec / 3600)} h ${Math.floor(sec % 3600 / 60)} m`;
}

// ---- shapes ----

export const muted = text => h('span', {class: 'muted'}, text);
export const code = text => h('code', null, text);
export const pill = (cls, ...kids) => h('span', {class: 'pill' + (cls ? ' ' + cls : '')}, kids);
export const badge = (text, cls) => h('span', {class: 'badge' + (cls ? ' ' + cls : '')}, text);
export const heldPill = (text = 'Held') => pill('held', icon('lock'), text);
export const frozenPill = (text = 'Frozen') => pill('frozen', icon('snow'), text);

/** A panel: a titled box, with an optional note on the right of the title. */
export function panel(title, meta, ...kids) {
  return h('div', {class: 'panel'}, h('h2', null, title, meta ? [' ', h('span', {class: 'meta'}, meta)] : null), kids);
}

/**
 * A table row that opens what its name links to (backlog 189): it shows a hover background and a chevron, and a click
 * anywhere on it but another control follows the name's link.
 */
export function opensRow(e) {
  if (e.target.closest('a, button, input, select, summary, label')) return;
  const a = e.currentTarget.querySelector('td.name a');
  if (a) a.click();
}

/** A table in its scrolling wrapper. Heads are text or [text, class]; rows are <tr>s or a tbody. */
export function tbl(cls, heads, rows, attrs) {
  // Each cell carries its column's name, which a phone's card shows beside the value (backlog 193).
  const labels = heads.map(x => Array.isArray(x) ? x[0] : x);
  for (const r of Array.isArray(rows) ? rows : []) {
    if (r instanceof HTMLTableRowElement && r.children.length === labels.length) {
      [...r.children].forEach((td, i) => { if (labels[i]) td.dataset.label = labels[i]; });
    }
  }
  return h('div', {class: 'wrap-tbl'}, h('table', Object.assign({class: 'tbl compact' + (cls ? ' ' + cls : '')}, attrs || {}),
    h('thead', null, h('tr', null, heads.map(x => Array.isArray(x) ? h('th', {class: x[1]}, x[0]) : h('th', null, x)))),
    rows instanceof HTMLTableSectionElement ? rows : h('tbody', null, rows)));
}

/** A label/value list. Values that are null or empty show as a dash. */
export function facts(pairs, cls) {
  return h('dl', {class: 'facts' + (cls ? ' ' + cls : '')}, pairs.filter(Boolean).map(([label, value]) => [
    h('dt', null, label),
    h('dd', null, value === null || value === undefined || value === '' ? muted('–') : value),
  ]));
}

/** A score bar, its length an SVG attribute: the policy forbids inline styles. */
export function bar(score, max, bad) {
  const svg = s('svg', {class: 'bar', viewBox: '0 0 90 8', 'aria-hidden': 'true'});
  const width = Math.max(2, Math.min(90, 90 * (Number(score) || 0) / (max || 10)));
  svg.append(s('rect', {class: 'tr', width: 90, height: 8, rx: 2}), s('rect', {class: 'fg' + (bad ? ' bad' : ''), width, height: 8, rx: 2}));
  return svg;
}

/**
 * A page head: eyebrow, title, a line under it, and controls on the right. When a brand pack's mark stands without
 * the product's name beside it (nuna.ui.brand.show-name=false), the eyebrow starts with the name (backlog 333).
 */
export function pageHead({eyebrow, title, sub, side, crumbs, hero} = {}) {
  const named = document.documentElement.dataset.nameOut;
  return h('div', {class: 'ph' + (hero ? ' hero' : '')}, crumbs || null,
    eyebrow ? h('p', {class: 'eyebrow'}, named ? named + ' · ' + eyebrow : eyebrow) : null,
    title ? h('h1', {tabindex: '-1'}, title) : null,
    sub ? h('p', {class: 'sub'}, sub) : null,
    side ? h('div', {class: 'side'}, side) : null);
}

/** A placeholder while something loads. */
export function skeleton(title) {
  return h('div', {class: 'panel', 'data-loading': ''}, title ? h('h2', null, title) : null,
    h('div', {class: 'stack sk-stack'}, h('span', {class: 'sk w60'}), h('span', {class: 'sk w80'}), h('span', {class: 'sk w40'})));
}

/** Runs an async loader into a container, showing progress and any failure in place. */
export async function load(container, loader, title) {
  fill(container, skeleton(title));
  try {
    fill(container, await loader());
  } catch (error) {
    fill(container, failure(error));
  }
  return container;
}

// ---- refusals and answers, as the catalog sent them ----

/**
 * An API failure as the server said it: status and type, its message word for word, and the request id the
 * server stamped on the answer, never reworded or hidden. A 403 is a refusal; anything else an error.
 */
/**
 * What an answer box keeps for whoever asks support: the status, the route and the request id, folded behind one
 * "Technical details" so the box leads with what happened in plain words (Atelier's review, F10; backlog 188).
 */
export const techDetails = (...kids) => h('details', {class: 'tech'}, h('summary', null, 'Technical details'), kids);

export function failure(error, opts = {}) {
  if (error && error.status) {
    const refused = error.status === 403;
    const rid = error.requestId;
    return h('div', {class: 'refusal' + (opts.big ? ' big' : '') + (opts.compact ? ' compact' : ''), role: 'alert'},
      h('p', {class: 'refusal-h'}, icon(refused ? 'x' : 'alert'),
        refused ? 'Refused by the catalog' : 'The catalog answered with an error'),
      h('p', {class: 'refusal-msg'}, error.message || ''),
      opts.note ? h('p', {class: 'fine'}, opts.note) : null,
      refused ? h('p', {class: 'fine'}, 'The refusal is recorded in the audit.') : null,
      opts.big ? h('div', {class: 'refusal-acts'}, h('button', {class: 'btn sm', type: 'button',
        onclick: e => copyText(location.href, e.currentTarget)}, icon('link'), 'Copy link to this page')) : null,
      techDetails(h('p', {class: 'refusal-code'}, `${error.status} ${error.type || ''}`.trim()),
        h('p', {class: 'refusal-meta'},
          h('span', {'data-rid': rid || ''}, rid ? 'X-Request-ID ' + rid : 'No request id came back'),
          h('span', null, at(error.at || Date.now(), true)),
          refused ? h('span', null, 'audited as a denial') : null),
        rid ? h('div', {class: 'refusal-acts'}, copyButton(rid, 'Copy request id')) : null));
  }
  // No status: the network failed (api.js marks it), or the console's own code did, after the catalog answered or
  // before it asked. A caller that knows what was going on (signing in) names it in `opts.title`, and the request it
  // made in `opts.tech`, for Technical details.
  const title = opts.title || (error && error.unreached ? 'The request did not reach the catalog' : 'The console failed to show this');
  return h('div', {class: 'refusal' + (opts.compact ? ' compact' : ''), role: 'alert'},
    h('p', {class: 'refusal-h'}, icon('alert'), title),
    h('p', {class: 'refusal-msg'}, String(error && error.message || error)),
    opts.tech ? techDetails(h('p', {class: 'refusal-code'}, opts.tech)) : null);
}

/** A change the catalog accepted, with its status and request id. */
export function granted(title, answer, more) {
  return h('div', {class: 'granted', role: 'status'}, h('p', {class: 'refusal-h'}, icon('check'), title),
    more ? h('p', {class: 'fine'}, more) : null,
    techDetails(h('p', {class: 'refusal-meta'}, h('span', null, answer.status + (answer.statusText ? ' ' + answer.statusText : '')),
      h('span', {'data-rid': answer.requestId || ''}, answer.requestId ? 'X-Request-ID ' + answer.requestId : ''),
      h('span', null, at(Date.now(), true)), h('span', null, 'audited'))));
}

// ---- copying, toasts and dialogs ----

const toastEl = () => document.getElementById('toast');
let toastTimer = 0;

export function toast(message) {
  const el = toastEl();
  if (!el) return;
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 3200);
}

export async function copyText(text, button) {
  let ok = false;
  try {
    await navigator.clipboard.writeText(text);
    ok = true;
  } catch {
    const area = h('textarea', {class: 'vh', 'aria-hidden': 'true'}, text);
    document.body.append(area);
    area.select();
    try {
      ok = document.execCommand('copy');
    } catch {
      ok = false;
    }
    area.remove();
  }
  const label = button && [...button.childNodes].find(n => n.nodeType === 3 && n.textContent.trim());
  if (!label) {
    toast(ok ? 'Copied' : 'Could not copy; select the text instead');
    return;
  }
  const was = label.textContent;
  label.textContent = ok ? 'Copied' : 'Could not copy';
  setTimeout(() => { label.textContent = was; }, 1500);
}

export function copyButton(text, label = 'Copy') {
  return h('button', {class: 'btn sm ghost', type: 'button', onclick: e => copyText(text, e.currentTarget)}, icon('copy'), label);
}

/** One modal dialog at a time, built in place and removed when it closes; focus goes back where it was. */
export function openDialog(title, body, {wide} = {}) {
  for (const d of $$('dialog.dlg')) d.remove();
  const opener = document.activeElement;
  const dlg = h('dialog', {class: 'dlg' + (wide ? ' wide' : ''), 'aria-labelledby': 'dlg-h'});
  const close = h('button', {class: 'btn sm ghost dlg-x', type: 'button', 'aria-label': 'Close', onclick: () => dlg.close()}, icon('x'));
  dlg.append(h('div', {class: 'dlg-head'}, h('h2', {id: 'dlg-h'}, title), close), h('div', {class: 'dlg-body'}, body));
  dlg.addEventListener('close', () => {
    dlg.remove();
    if (opener && opener.isConnected) opener.focus({preventScroll: true});
  });
  dlg.addEventListener('click', e => { if (e.target === dlg) dlg.close(); });
  document.body.append(dlg);
  dlg.showModal();
  const first = $('input:not([type=hidden]), select, textarea', dlg) || $('.dlg-acts .btn', dlg);
  if (first) first.focus();
  return dlg;
}

export const field = (label, input, hint) => h('label', {class: 'dlg-f'}, h('span', null, label), input,
  hint ? h('span', {class: 'fine'}, hint) : null);

export const select = (name, options, value, attrs) => h('span', {class: 'field mono select'},
  h('select', Object.assign({name}, attrs || {}), options.map(o => {
    const [v, l] = Array.isArray(o) ? o : [o, o];
    return h('option', {value: v, selected: v === value}, l);
  })));

export const text = (name, placeholder, value, attrs) => h('span', {class: 'field mono'},
  h('input', Object.assign({name, type: 'text', placeholder, value: value || null, autocomplete: 'off', spellcheck: 'false'}, attrs || {})));

/**
 * A dialog's form: its fields, a primary button and Cancel; `onOk(form, out)` writes its answer into `out`. A form
 * `onOk` marks `data-done` keeps its button off, so the next change starts from what the page redrew.
 */
export function dialogForm(fields, okLabel, onOk) {
  const out = h('div', {class: 'dlg-out', 'aria-live': 'polite'});
  const ok = h('button', {class: 'btn primary', type: 'submit'}, okLabel);
  const form = h('form', {class: 'dlg-form', novalidate: true}, fields,
    h('div', {class: 'dlg-acts'}, ok, h('button', {class: 'btn ghost', type: 'button', onclick: () => form.closest('dialog').close()}, 'Cancel')),
    out);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    ok.disabled = true;
    try {
      await onOk(form, out);
    } catch (error) {
      fill(out, failure(error));
    } finally {
      ok.disabled = form.hasAttribute('data-done');
    }
  });
  return form;
}

// ---- JSON, drawn as text nodes ----

function jsonNodes(v, indent = '') {
  if (Array.isArray(v)) {
    if (!v.length) return ['[]'];
    const out = ['[\n'];
    v.forEach((x, i) => out.push(indent + '  ', ...jsonNodes(x, indent + '  '), i < v.length - 1 ? ',\n' : '\n'));
    out.push(indent + ']');
    return out;
  }
  if (v && typeof v === 'object') {
    const keys = Object.keys(v);
    if (!keys.length) return ['{}'];
    const out = ['{\n'];
    keys.forEach((k, i) => out.push(indent + '  ', h('span', {class: 'k'}, JSON.stringify(k)), ': ',
      ...jsonNodes(v[k], indent + '  '), i < keys.length - 1 ? ',\n' : '\n'));
    out.push(indent + '}');
    return out;
  }
  if (typeof v === 'number' || typeof v === 'bigint' || typeof v === 'boolean' || v === null) return [h('span', {class: 'n'}, String(v))];
  return [h('span', {class: 's'}, JSON.stringify(v))];
}

export const jsonPre = v => h('pre', {class: 'raw'}, ...jsonNodes(v));

// ---- behaviours the prototype gives scrolling boxes and drawings ----

/** Fades an edge that has more to scroll to. */
export function edgeCue(el) {
  const set = () => {
    const more = el.scrollWidth > el.clientWidth + 2;
    el.classList.toggle('more-r', more && el.scrollLeft + el.clientWidth < el.scrollWidth - 2);
    el.classList.toggle('more-l', more && el.scrollLeft > 2);
  };
  set();
  el.addEventListener('scroll', set, {passive: true});
  if ('ResizeObserver' in window) new ResizeObserver(set).observe(el);
}

/** Draws into `host` at its own width, and again when the width changes. */
export function sized(host, draw) {
  let last = 0;
  const run = () => {
    const w = Math.round(host.clientWidth);
    if (!w || Math.abs(w - last) < 2) return;
    last = w;
    for (const old of $$(':scope > svg, :scope > .ice-cap', host)) old.remove();
    draw(host, w);
  };
  requestAnimationFrame(run);
  if ('ResizeObserver' in window) new ResizeObserver(run).observe(host);
}

/** Chips that filter rows: each chip's data-f is a key; `keysOf(row)` lists a row's keys. */
export function chips(box, rows, keysOf) {
  for (const b of $$('.chip', box)) {
    b.addEventListener('click', () => {
      for (const o of $$('.chip', box)) o.setAttribute('aria-pressed', String(o === b));
      const f = b.dataset.f;
      for (const r of rows()) r.hidden = !(f === 'all' || keysOf(r).includes(f));
    });
  }
}

/** Tabs that are links to their pages; the shown one carries aria-current. */
export function tabsNav(label, list, current, hrefOf) {
  const nav = h('nav', {class: 'tabs', 'aria-label': label}, list.map(([key, text, count]) =>
    h('a', {href: hrefOf(key), 'aria-current': (current || '') === key ? 'page' : null, 'data-tab': key || 'overview'},
      text, count !== null && count !== undefined ? [' ', h('span', {class: 'c'}, fmt(count))] : null)));
  // On a phone the row scrolls sideways (the design's 6.9): its edges fade where tabs go on, and the current tab is
  // kept in view as the row gets its width and its tabs their counts, until the reader scrolls it.
  edgeCue(nav);
  const cur = nav.querySelector('[aria-current="page"]');
  if (cur && 'ResizeObserver' in window) {
    let moved = false;
    for (const e of ['pointerdown', 'wheel', 'touchstart']) nav.addEventListener(e, () => { moved = true; }, {passive: true});
    const watch = new ResizeObserver(() => {
      if (moved || !nav.clientWidth) return;
      const box = nav.getBoundingClientRect();
      const at = cur.getBoundingClientRect();
      if (at.right > box.right) nav.scrollLeft += at.right - box.right + 24;
      else if (at.left < box.left) nav.scrollLeft -= box.left - at.left + 24;
    });
    watch.observe(nav);
    for (const a of nav.children) watch.observe(a);
  }
  return nav;
}

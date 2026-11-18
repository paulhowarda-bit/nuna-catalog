// Building DOM nodes. Every string from the catalog goes in as text (a Text node or an attribute value),
// never as markup: this module is the only place elements are made, and it has no innerHTML.

/**
 * h('div', {class: 'x', onclick: fn, title: 't'}, 'text', child, [more]) -> HTMLElement.
 * Attributes: on* take functions; everything else is set with setAttribute. Children may be strings,
 * numbers, nodes, arrays, or null/undefined/false (skipped).
 */
export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
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
  append(el, children);
  return el;
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

/** A table: headers and rows of cells (strings or nodes). */
export function table(headers, rows, attrs) {
  return h('table', Object.assign({class: 'grid'}, attrs || {}),
    h('thead', null, h('tr', null, headers.map(text => h('th', null, text)))),
    h('tbody', null, rows));
}

/** A label/value list. Values that are null or empty show as a dash. */
export function facts(pairs) {
  return h('dl', {class: 'facts'}, pairs.filter(Boolean).map(([label, value]) => [
    h('dt', null, label),
    h('dd', null, value === null || value === undefined || value === '' ? h('span', {class: 'muted'}, '–') : value),
  ]));
}

export function badge(text, kind) {
  return h('span', {class: 'badge' + (kind ? ' badge-' + kind : '')}, text);
}

export function mono(text) {
  return h('code', {class: 'mono'}, text);
}

/** A time from epoch milliseconds or an ISO string, as local date and time. */
export function when(value) {
  if (value === null || value === undefined) return '';
  const date = typeof value === 'number' ? new Date(value) : new Date(String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  const pad = n => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** An API failure as the server said it: status, type and message, never reworded or hidden. */
export function failure(error) {
  if (error && error.status) {
    const refused = error.status === 403;
    return h('div', {class: 'alert' + (refused ? ' alert-refused' : ''), role: 'alert'},
      h('strong', null, refused ? 'Refused by the catalog' : 'The catalog answered with an error'),
      h('div', {class: 'alert-line'}, mono(`${error.status} ${error.type || ''}`.trim())),
      h('div', {class: 'alert-line'}, error.message || ''));
  }
  return h('div', {class: 'alert', role: 'alert'}, h('strong', null, 'Request failed'),
    h('div', {class: 'alert-line'}, String(error && error.message || error)));
}

/** Runs an async loader into a container, showing progress and any failure in place. */
export async function load(container, loader) {
  fill(container, h('p', {class: 'loading'}, 'Loading…'));
  try {
    const content = await loader();
    fill(container, content);
  } catch (error) {
    fill(container, failure(error));
  }
}

/** Tabs: [{id, label, render: () => Node | Promise<Node>}]; returns the element. */
export function tabs(list, selected, onSelect) {
  const body = h('div', {class: 'tab-body'});
  const bar = h('div', {class: 'tabs', role: 'tablist'});
  const show = id => {
    const tab = list.find(t => t.id === id) || list[0];
    for (const button of bar.children) {
      button.setAttribute('aria-selected', String(button.dataset.tab === tab.id));
    }
    if (onSelect) onSelect(tab.id);
    load(body, async () => tab.render());
  };
  for (const tab of list) {
    const button = h('button', {type: 'button', role: 'tab', class: 'tab', onclick: () => show(tab.id)}, tab.label);
    button.dataset.tab = tab.id;
    bar.appendChild(button);
  }
  show(selected);
  return h('div', {class: 'tabbed'}, bar, body);
}

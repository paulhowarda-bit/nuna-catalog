// Governance in one catalog: grants (made, listed by group, revoked and exported for an access review), and the
// catalog's own holds and freezes. Waiting on other items, and left out until they land: the summary with its
// classified, held and frozen figures, and the catalog-wide list of holds and freezes with what was lifted (128).

import * as api from './api.js';
import {at, code, failure, fill, granted, h, icon, load, muted, openDialog, pageHead, select, tabsNav, tbl, text,
  dialogForm, field, copyButton, jsonPre} from './dom.js';
import {href, pageHref} from './route.js';
import {governanceTab, PRIVILEGES, placeHold, revokeDialog} from './panels.js';

const DUTY = {describe: 'read', select: 'read', write: 'data', modify: 'data', create: 'data', 'manage-grants': 'grants', 'manage-tags': 'tags'};
const DUTIES = [['read', 'Reading', 'describe, select'], ['data', 'Data', 'write, modify, create'], ['grants', 'Grants', 'manage-grants'],
  ['tags', 'Tags', 'manage-tags']];

export function governancePage(catalog, tab, group) {
  const key = tab === 'holds' ? 'holds' : 'grants';
  const tabs = tabsNav('Governance', [['grants', 'Grants'], ['holds', 'Holds and freezes']], key, k => pageHref('governance', catalog, k));
  return key === 'holds' ? holdsPage(catalog, tabs) : grantsPage(catalog, tabs, group);
}

// ---- grants ----

function onWords(securable) {
  const path = [...(securable.namespace || []), ...(securable.name ? [securable.name] : [])].join('.');
  return path ? [code(path), ' ', muted('· ' + securable.type)] : muted('the whole catalog');
}

function grantsPage(catalog, tabs, group) {
  const main = h('div', {class: 'nc-main', id: 'governance-grants'});
  const result = h('div', {'aria-live': 'polite', id: 'grant-result'});
  const hint = h('p', {class: 'fine grant-hint', hidden: true});
  const list = h('div', {class: 'panel', id: 'grants-list'});
  const duties = h('div', {class: 'duties'});
  const dutiesH = h('h2', null, 'Duties');
  const groupIn = h('input', {name: 'g', type: 'text', placeholder: 'A group, such as demo-analysts', 'aria-label': 'Group',
    autocomplete: 'off', spellcheck: 'false', value: group || null, list: 'grant-groups'});
  const groups = h('datalist', {id: 'grant-groups'});
  const on = h('select', {name: 'on', 'aria-label': 'On'}, h('option', {value: ''}, catalog + ' (the whole catalog)'));
  const pv = h('select', {name: 'pv', 'aria-label': 'Privilege'}, h('option', {value: '', disabled: true, selected: true}, 'Choose a privilege'),
    PRIVILEGES.map(p => h('option', {value: p}, p)));
  const btn = h('button', {class: 'btn primary', type: 'submit'}, 'Grant');
  const form = h('form', {class: 'grant-form', novalidate: true},
    h('label', null, h('span', null, 'Group'), h('span', {class: 'field mono'}, groupIn), groups),
    h('label', null, h('span', null, 'Privilege'), h('span', {class: 'field mono select'}, pv)),
    h('label', null, h('span', null, 'On'), h('span', {class: 'field mono select'}, on)),
    btn);
  api.namespaces(catalog, []).then(ns => on.append(...ns.map(n => h('option', {value: n.join('\u001f')}, n.join('.')))), () => {});
  const targetOf = () => on.value ? {catalog, kind: 'namespace', namespace: on.value.split('\u001f')} : {catalog, kind: 'catalog', namespace: []};
  pv.addEventListener('change', () => { btn.textContent = 'Grant ' + pv.value; });

  /** The grants of the group in the field, or the caller's own groups' when it is empty. */
  const showList = () => {
    const g = groupIn.value.trim();
    load(list, async () => {
      const grants = await api.grantsOfGroups(catalog, g ? [g] : []);
      const held = new Set(grants.map(x => DUTY[x.privilege]));
      fill(dutiesH, g ? `Duties of ${g} in ${catalog}` : `Duties of your groups in ${catalog}`);
      fill(duties, DUTIES.map(([k, w, ps]) => h('div', {class: 'duty' + (held.has(k) ? ' held' : ''), 'data-duty': k},
        h('h4', null, w + (held.has(k) ? ' · granted' : '')), h('p', null, ps))));
      for (const x of grants) if (![...groups.children].some(o => o.value === x.group)) groups.append(h('option', {value: x.group}));
      return [h('h2', null, g ? `Grants to ${g} ` : 'Your groups\' grants ', h('span', {class: 'meta'}, `in ${catalog}`)),
        grants.length ? tbl('stack-line', ['Group', 'Privilege', 'On', 'Granted', ''], grants.map(x => h('tr', {'data-group': x.group,
          'data-privilege': x.privilege},
        h('td', {class: 'name'}, x.group), h('td', {class: 'name'}, x.privilege), h('td', null, onWords(x.securable)),
        h('td', null, h('span', {class: 'tnum'}, at(x['granted-at'])), ' ', muted(x['granted-by'])),
        h('td', null, h('button', {class: 'btn sm ghost', type: 'button', onclick: () => revokeDialog(catalog, x, showList)}, 'Revoke')))))
          : h('p', {class: 'muted'}, g ? `${g} holds no grant in ${catalog}.` : `Your groups hold no grant in ${catalog}.`),
        h('p', {class: 'fine'}, 'Type a group in the form to list its grants; reading another group\'s grants needs ', code('manage-grants'), '.')];
    });
  };
  groupIn.addEventListener('change', () => {
    const want = pageHref('governance', catalog, 'grants', groupIn.value.trim() || null);
    if (location.hash !== want) history.replaceState(null, '', want);
    fill(result);
    showList();
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const g = groupIn.value.trim();
    const missing = [[g, 'a group'], [pv.value, 'a privilege']].filter(([v]) => !v).map(x => x[1]);
    hint.hidden = !missing.length;
    if (missing.length) {
      hint.textContent = 'Choose ' + missing.join(' and ') + '.';
      return;
    }
    btn.disabled = true;
    try {
      const answer = await api.grant(targetOf(), g, pv.value);
      fill(result, granted(`Granted ${pv.value} on ${on.value ? on.value.split('\u001f').join('.') : catalog} to ${g}`, answer));
    } catch (error) {
      fill(result, failure(error));
    } finally {
      btn.disabled = false;
    }
    showList();
  });
  showList();
  main.append(pageHead({
    eyebrow: `${catalog} · governance`,
    title: `Grants in ${catalog}`,
    sub: 'Grants go to groups, never to people. A group that manages grants holds no other duty, and data and tags never share a group: '
      + 'the catalog refuses a grant that would break that, and its refusal shows here as it answered.',
    side: h('button', {class: 'btn sm', type: 'button', 'data-act': 'export-grants', onclick: () => exportGrants(catalog)}, icon('down'), 'Export every grant'),
  }), tabs, h('div', {class: 'grid-21'},
    h('div', {class: 'stack'},
      h('div', {class: 'panel'}, h('h2', null, 'Grant a privilege'), form, hint),
      result,
      h('div', {class: 'panel'}, dutiesH, duties, h('p', {class: 'fine duties-note'}, 'Grants stand alone: a group that manages grants is '
        + 'granted nothing else. Data and tags exclude each other; reading goes with either. So no group can both change data and decide '
        + 'how it is labelled or who may see it.'))),
    list));
  return main;
}

/** Every grant in the catalog, for an access review: as JSON or CSV. Needs manage-grants on the catalog. */
function exportGrants(catalog) {
  const out = h('div');
  const dlg = openDialog(`Every grant in ${catalog}`, out, {wide: true});
  load(out, async () => {
    const x = await api.exportGrants(catalog);
    const json = JSON.stringify(x, null, 2);
    const cell = v => /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
    const csv = ['group,privilege,type,namespace,name,granted-by,granted-at', ...(x.grants || []).map(g => [g.group, g.privilege,
      g.securable.type, (g.securable.namespace || []).join('.'), g.securable.name || '', g['granted-by'] || '', g['granted-at'] || '']
      .map(v => cell(String(v))).join(','))].join('\n');
    const urls = [URL.createObjectURL(new Blob([json], {type: 'application/json'})), URL.createObjectURL(new Blob([csv], {type: 'text/csv'}))];
    dlg.addEventListener('close', () => urls.forEach(u => URL.revokeObjectURL(u)));
    return [h('p', null, `${(x.grants || []).length} grants, exported by ${x['exported-by']} at ${at(x['exported-at'], true)}. The export is audited.`),
      h('div', {class: 'dlg-acts'},
        h('a', {class: 'btn sm primary', href: urls[1], download: `${catalog}-grants.csv`}, icon('down'), 'Download CSV'),
        h('a', {class: 'btn sm', href: urls[0], download: `${catalog}-grants.json`}, icon('down'), 'Download JSON'),
        copyButton(json, 'Copy the JSON')),
      jsonPre(Object.assign({}, x, {grants: (x.grants || []).slice(0, 5).concat((x.grants || []).length > 5
        ? [`… ${(x.grants || []).length - 5} more in the file`] : [])}))];
  });
}

// ---- holds and freezes ----

function holdsPage(catalog, tabs) {
  const main = h('div', {class: 'nc-main', id: 'governance-holds'});
  const target = {catalog, kind: 'catalog', namespace: []};
  const body = h('div', {class: 'tab-body'});
  const reload = () => load(body, () => governanceTab(target, reload));
  reload();
  main.append(pageHead({
    eyebrow: `${catalog} · governance`,
    title: `Holds and freezes on ${catalog}`,
    sub: 'A hold stops snapshot expiry, orphan removal, drops and purges on what it covers. A freeze stops every cleanup and write '
      + 'in its namespace until it is lifted.',
    side: h('button', {class: 'btn sm', type: 'button', onclick: () => holdOnPath(catalog)}, icon('lock'), 'Place a hold on a path'),
  }), tabs,
  h('div', {class: 'notice waiting'}, icon('info'), h('span', null, h('b', null, 'Below: the catalog\'s own governance.'),
    ' Each namespace\'s and table\'s Governance tab lists the holds on it and above it. The list of every hold and freeze in ',
    catalog, ', with what was lifted, comes with backlog 128.')),
  body);
  return main;
}

function holdOnPath(catalog) {
  openDialog(`Place a hold in ${catalog}`, h('div', null,
    h('p', null, 'Name a namespace or a table. The hold carries its reason for as long as it stands.'),
    dialogForm([
      field('Kind', select('kind', [['namespace', 'namespace'], ['table', 'table'], ['view', 'view']], 'namespace')),
      field('Path', text('path', 'such as equities.eu, or equities.eu.trades for a table')),
    ], 'Next', async (form, out) => {
      const kind = form.elements.kind.value;
      const parts = form.elements.path.value.trim().split('.').filter(Boolean);
      if (!parts.length || (kind !== 'namespace' && parts.length < 2)) {
        fill(out, h('p', {class: 'fine grant-hint'}, kind === 'namespace' ? 'Write the namespace\'s path.'
          : `Write the ${kind}'s path, its namespace first.`));
        return;
      }
      const target = kind === 'namespace' ? {catalog, kind, namespace: parts} : {catalog, kind, namespace: parts.slice(0, -1), name: parts[parts.length - 1]};
      form.closest('dialog').close();
      placeHold(target, () => { location.hash = href({c: catalog, k: kind, ns: target.namespace, n: target.name, tab: 'governance'}); });
    })));
}

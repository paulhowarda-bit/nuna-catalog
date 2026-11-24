// Governance in one catalog: the summary (tables classified, closed, held and frozen; the holds and freezes
// standing; each regulated area), grants (made, listed by group, revoked and exported for an access review), and
// every hold and freeze, standing and lifted, with the catalog's own governance (item 128).

import * as api from './api.js';
import {at, code, failure, fill, fmt, frozenPill, granted, h, heldPill, icon, load, muted, openDialog, pageHead, panel,
  pill, plural, select, tabsNav, tbl, text, dialogForm, field, copyButton, jsonPre, opensRow} from './dom.js';
import {href, objectHref, pageHref} from './route.js';
import {drFreeze, governanceTab, PRIVILEGES, placeHold, releaseHold, revokeDialog, targetOfOn} from './panels.js';
import {closedNote, may, operates} from './access.js';

const DUTY = {describe: 'read', select: 'read', write: 'data', modify: 'data', create: 'data', 'manage-grants': 'grants', 'manage-tags': 'tags'};
const DUTIES = [['read', 'Reading', 'describe, select'], ['data', 'Data', 'write, modify, create'], ['grants', 'Grants', 'manage-grants'],
  ['tags', 'Tags', 'manage-tags']];

export function governancePage(catalog, tab, group) {
  const key = tab === 'holds' || tab === 'grants' ? tab : '';
  const grants = may(catalog, 'manage-grants');
  const tabs = tabsNav('Governance', [['', 'Summary'], ['grants', 'Grants'], ['holds', 'Holds and freezes']]
    .filter(([k]) => k !== 'grants' || grants), key, k => pageHref('governance', catalog, k || null));
  if (key === 'holds') return holdsPage(catalog, tabs);
  if (key === 'grants' && !grants) {
    return h('div', {class: 'nc-main', id: 'governance-page'}, pageHead({eyebrow: `${catalog} · governance`, title: 'Grants'}), tabs,
      closedNote(`Grants in ${catalog} are open to those who manage its grants.`));
  }
  if (key === 'grants') return grantsPage(catalog, tabs, group);
  return summaryPage(catalog, tabs);
}

// ---- the summary ----

/** A level named by the summary or the holds list, as a link to its own page. */
function onLink(catalog, on) {
  if (on.kind === 'catalog') return [code(catalog), ' ', muted('· catalog')];
  const t = targetOfOn(catalog, on);
  return [h('a', {href: objectHref(t, 'governance')}, (on.path || []).join('.')), ' ', muted('· ' + on.kind)];
}

const pct = (n, of) => of ? Math.floor(100 * n / of) + '%' : '–';

/** The figures of the governance summary, for the summary page and the overview. */
export function governanceFigures(sum) {
  const t = sum.tables || {};
  return {classified: t.classified || 0, total: t.total || 0, closed: t.closed || 0, held: t.held || 0, frozen: t.frozen || 0,
    holds: sum.holds || 0, freezes: sum.freezes || 0, regulated: (sum.regulated || []).length};
}

function summaryPage(catalog, tabs) {
  const main = h('div', {class: 'nc-main', id: 'governance-summary'});
  const show = () => load(main, async () => {
    const [sum, hf] = await Promise.all([api.governanceSummary(catalog), api.holdsAndFreezes(catalog)]);
    const f = governanceFigures(sum);
    const standing = hf.standing || [];
    const regulated = sum.regulated || [];
    const closed = sum.closed || [];
    const kpi = (key, ic, label, value, more) => h('div', {class: 'kpi', 'data-kpi': key}, h('p', {class: 'kpi-l'}, icon(ic), label),
      h('p', {class: 'kpi-v'}, value), h('p', {class: 'kpi-d'}, more));
    return [
      pageHead({
        eyebrow: `${catalog} · governance`,
        title: [`${fmt(f.classified)} of ${plural(f.total, 'table')} classified. `, f.closed
          ? h('span', {class: 'q'}, `${plural(f.closed, 'table')} closed until a steward classifies ${f.closed === 1 ? 'it' : 'them'}.`)
          : 'None closed.'],
        sub: 'Classification, owner, retention class and the regulated flag are set here in the console, on the Governance tab '
          + 'of a catalog, namespace, table or view, each recorded with who set it and at which level; an outside data catalogue can '
          + 'push them too, if one is ever connected. The catalog enforces them, with holds and freezes, on every load, commit '
          + 'and maintenance job.',
      }),
      tabs,
      h('div', {class: 'kpis', id: 'governance-kpis'},
        kpi('classified', 'check', 'Classified', pct(f.classified, f.total), [h('b', null, fmt(f.classified)), ` of ${plural(f.total, 'table')}`]),
        kpi('holds', 'lock', 'Legal holds', String(f.holds), f.holds ? `on ${plural(f.held, 'table')} · nothing may be removed` : 'nothing held'),
        kpi('freezes', 'snow', 'DR freezes', String(f.freezes), f.freezes ? `${plural(f.frozen, 'table')} beneath` : 'none standing'),
        kpi('regulated', 'reg', 'Regulated areas', String(f.regulated), f.regulated
          ? `${plural(regulated.reduce((n, r) => n + (r.tables || 0), 0), 'table')}, ${fmt(f.closed)} closed` : 'none in this catalog')),
      h('div', {class: 'grid-2'},
        panel('Holds and freezes', 'standing now', standingTable(catalog, standing, show),
          h('p', {class: 'fine'}, 'A hold stops snapshot expiry, orphan removal, drops and purges. A freeze stops every cleanup and write '
            + 'beneath it until it is lifted. ', h('a', {href: pageHref('governance', catalog, 'holds')}, 'With what was lifted'))),
        h('div', {class: 'panel', id: 'regulated'}, h('h2', null, 'Regulated areas ', h('span', {class: 'meta'}, 'closed until classified')),
          regulated.length ? tbl('stack-line', ['Area', ['Tables', 'num'], ['Closed', 'num']], regulated.map(r => h('tr', {class: r.on.kind === 'catalog' ? null : 'opens',
            onclick: opensRow, 'data-on': (r.on.path || []).join('.')},
            h('td', {class: 'name'}, onLink(catalog, r.on)), h('td', {class: 'num'}, fmt(r.tables || 0)), h('td', {class: 'num'}, fmt(r.closed || 0)))))
            : h('p', {class: 'muted'}, 'No regulated area in this catalog: an unclassified table stays open.'),
          closed.length ? [h('h3', null, 'Closed until classified'), h('ul', {class: 'stack closed-list'}, closed.map(on =>
            h('li', {'data-on': (on.path || []).join('.')}, onLink(catalog, on))))] : null,
          h('p', {class: 'fine'}, 'A table in a regulated area is refused everything but describe until it has a classification of its own.'))),
    ];
  });
  show();
  return main;
}

/** The holds and freezes standing, each with what it is on, its reason as recorded, and a release or a lift. */
function standingTable(catalog, standing, reload) {
  if (!standing.length) return h('p', {class: 'muted'}, `No hold or freeze stands in ${catalog}.`);
  return tbl('holds', ['Kind', 'On', 'Reason, as recorded', 'Placed', ''], standing.map(x => {
    const level = targetOfOn(catalog, x.on);
    return h('tr', {class: x.on.kind === 'catalog' ? null : 'opens', onclick: opensRow, 'data-kind': x.kind,
      'data-on': (x.on.path || []).join('.')},
      h('td', null, x.kind === 'hold' ? heldPill('Hold') : frozenPill('Freeze')),
      h('td', {class: 'name'}, onLink(catalog, x.on)),
      h('td', {class: 'wrap'}, x.reason || muted('–')),
      h('td', null, h('span', {class: 'tnum'}, at(x['placed-at'])), h('br'), muted(x['placed-by'] || '')),
      h('td', null, x.kind === 'hold'
        ? may(catalog, 'manage-tags') ? h('button', {class: 'btn sm ghost', type: 'button', onclick: () => releaseHold(level, x, reload)}, 'Release') : null
        : operates() ? h('button', {class: 'btn sm ghost', type: 'button', onclick: () => drFreeze(level, true, reload)}, 'Lift') : null));
  }));
}

/**
 * The holds released and freezes lifted in the window, as the store keeps each (item 133), the most recent first; one
 * on a level dropped since is named by its kind and id, as there is no longer a path to it.
 */
function liftedTable(catalog, lifted) {
  return tbl('holds', ['Kind', 'On', 'Reason, as recorded', 'Placed', 'Lifted'], lifted.map(x => h('tr', {
    class: x.on.path === null || x.on.kind === 'catalog' ? null : 'opens', onclick: opensRow, 'data-kind': x.kind,
    'data-on': (x.on.path || []).join('.')},
  h('td', null, pill('', icon(x.kind === 'hold' ? 'lock' : 'snow'), x.kind === 'hold' ? 'Hold' : 'Freeze')),
  h('td', {class: 'name'}, x.on.path === null ? [code(x.on.id), ' ', muted(`· ${x.on.kind}, dropped since`)] : onLink(catalog, x.on)),
  h('td', {class: 'wrap'}, x.reason),
  h('td', null, h('span', {class: 'tnum'}, at(x['placed-at'])), h('br'), muted(x['placed-by'])),
  h('td', null, h('span', {class: 'tnum'}, at(x['lifted-at'])), h('br'), muted(x['lifted-by'])))));
}

/** The window the lifted list covers: every release and lift in it is listed. */
function historyWords(history) {
  return h('p', {class: 'fine', id: 'lifted-window'},
    `Every hold released and freeze lifted in the last ${history.days} days, since ${at(history.since)}.`);
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
  const lists = h('div', {class: 'stack'});
  const own = h('div', {class: 'tab-body'});
  const reload = () => {
    load(lists, async () => {
      const hf = await api.holdsAndFreezes(catalog);
      const standing = hf.standing || [];
      const lifted = hf.lifted || [];
      return [h('div', {class: 'panel', id: 'holds-standing'}, h('h2', null, 'Standing ', h('span', {class: 'meta'}, 'newest first')),
        standingTable(catalog, standing, reload)),
      h('div', {class: 'panel', id: 'holds-lifted'}, h('h2', null, 'Lifted and released ', h('span', {class: 'meta'}, 'most recent first')),
        lifted.length ? liftedTable(catalog, lifted)
          : h('p', {class: 'muted'}, `Nothing lifted or released in the last ${hf.history.days} days.`),
        historyWords(hf.history))];
    });
    load(own, () => governanceTab(target, reload));
  };
  reload();
  main.append(pageHead({
    eyebrow: `${catalog} · governance`,
    title: `Holds and freezes in ${catalog}`,
    sub: 'A hold stops snapshot expiry, orphan removal, drops and purges on what it covers. A freeze stops every cleanup and write '
      + 'in its namespace until it is lifted.',
    side: may(catalog, 'manage-tags')
      ? h('button', {class: 'btn sm', type: 'button', onclick: () => holdOnPath(catalog)}, icon('lock'), 'Place a hold on a path') : null,
  }), tabs, lists, h('h2', {class: 'section-h'}, `${catalog} itself`), own);
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

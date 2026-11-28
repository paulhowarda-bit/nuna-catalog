// Governance in one catalog: the summary (tables classified, closed, held and frozen; the holds and freezes
// standing; each regulated area), and every hold and freeze, standing and lifted, with the catalog's own governance
// (item 128). Grants (listed by group, revoked and exported for an access review, and made in the Grant access dialog,
// backlog 248) are the rail's own page for those who manage them (backlog 245).

import * as api from './api.js';
import {at, code, fill, fmt, frozenPill, h, heldPill, icon, load, muted, openDialog, pageHead, panel,
  pill, plural, select, tabsNav, tbl, text, dialogForm, field, copyButton, jsonPre, opensRow} from './dom.js';
import {href, objectHref, pageHref} from './route.js';
import {drFreeze, governanceTab, placeHold, privilegeCell, releaseHold, revokeDialog, targetOfOn} from './panels.js';
import {holdsIn, may, operates} from './access.js';
import {DUTY, grantAccess} from './grant.js';

const DUTIES = [['read', 'Reading', 'describe, select'], ['data', 'Data', 'write, modify, create'], ['grants', 'Grants', 'manage-grants'],
  ['tags', 'Tags', 'manage-tags']];

export function governancePage(catalog, tab) {
  if (!holdsIn(catalog) && operates()) return freezePage(catalog);
  const key = tab === 'holds' ? tab : '';
  const tabs = tabsNav('Governance', [['', 'Summary'], ['holds', 'Holds and freezes']], key,
    k => pageHref('governance', catalog, k || null));
  if (key === 'holds') return holdsPage(catalog, tabs);
  return summaryPage(catalog, tabs);
}

// ---- the summary ----

/** A level named by the summary or the holds list, as a link to its own page. */
function onLink(catalog, on) {
  if (on.kind === 'catalog') return [code(catalog), ' ', muted('· catalog')];
  const t = targetOfOn(catalog, on);
  return [h('a', {href: objectHref(t, 'access')}, (on.path || []).join('.')), ' ', muted('· ' + on.kind)];
}

/** A regulated mark's reason, its provenance as recorded; a mark the console made before it asked for one has none. */
const provenanceWords = p => !p || p === 'console' ? muted('No reason recorded') : p;

const pct = (n, of) => of ? Math.floor(100 * n / of) + '%' : '–';

/** The figures of the governance summary, for the summary page and the overview. */
export function governanceFigures(sum) {
  const t = sum.tables || {};
  return {classified: t.classified || 0, total: t.total || 0, closed: t.closed || 0, held: t.held || 0, frozen: t.frozen || 0,
    holds: sum.holds || 0, freezes: sum.freezes || 0, regulated: (sum.regulated || []).length, flagged: t.flagged || 0,
    rules: (sum.flagged || []).reduce((n, x) => n + (x.rules || 0), 0)};
}

function summaryPage(catalog, tabs) {
  const main = h('div', {class: 'nc-main', id: 'governance-summary'});
  const show = () => load(main, async () => {
    const [sum, hf] = await Promise.all([api.governanceSummary(catalog), api.holdsAndFreezes(catalog)]);
    const f = governanceFigures(sum);
    const standing = hf.standing || [];
    const regulated = sum.regulated || [];
    const closed = sum.closed || [];
    const flagged = sum.flagged || [];
    const kpi = (key, ic, label, value, more) => h('div', {class: 'kpi', 'data-kpi': key}, h('p', {class: 'kpi-l'}, icon(ic), label),
      h('p', {class: 'kpi-v'}, value), h('p', {class: 'kpi-d'}, more));
    return [
      pageHead({
        eyebrow: `${catalog} · governance`,
        title: [`${fmt(f.classified)} of ${plural(f.total, 'table')} classified. `, f.closed
          ? h('span', {class: 'q'}, `${plural(f.closed, 'table')} closed until a steward classifies ${f.closed === 1 ? 'it' : 'them'}.`)
          : 'None closed.'],
        sub: 'Classification, owner, retention class and the regulated flag are set on the Access tab of each namespace, table '
          + 'or view, and for the catalog itself under Holds and freezes, each recorded with who set it. The catalog enforces '
          + 'them, with holds and freezes, on every load, commit and maintenance job.',
      }),
      tabs,
      h('div', {class: 'kpis five', id: 'governance-kpis'},
        kpi('classified', 'check', 'Classified', pct(f.classified, f.total), [h('b', null, fmt(f.classified)), ` of ${plural(f.total, 'table')}`]),
        kpi('holds', 'lock', 'Legal holds', String(f.holds), f.holds ? `on ${plural(f.held, 'table')} · nothing may be removed` : 'nothing held'),
        kpi('freezes', 'snow', 'DR freezes', String(f.freezes), f.freezes ? `${plural(f.frozen, 'table')} beneath` : 'none standing'),
        kpi('regulated', 'reg', 'Regulated areas', String(f.regulated), f.regulated
          ? `${plural(regulated.reduce((n, r) => n + (r.tables || 0), 0), 'table')}, ${fmt(f.closed)} closed` : 'none in this catalog'),
        // Tables flagged for row and column rules, read through Trino (D20; Atelier, 2026-11-24; backlog 243).
        kpi('flagged', 'table', 'Flagged tables', String(f.flagged), f.flagged ? plural(f.rules, 'rule') : 'none in this catalog')),
      h('div', {class: 'grid-2'},
        panel('Holds and freezes', 'standing now', standingTable(catalog, standing, show),
          h('p', {class: 'fine'}, 'A hold stops snapshot expiry, orphan removal, drops and purges. A freeze stops every cleanup and write '
            + 'beneath it until it is lifted. ', h('a', {href: pageHref('governance', catalog, 'holds')}, 'With what was lifted'))),
        h('div', {class: 'panel', id: 'regulated'}, h('h2', null, 'Regulated areas ', h('span', {class: 'meta'}, 'closed until classified')),
          // Each area with why it is regulated and who said so, as its flag records them (backlog 213).
          regulated.length ? tbl('stack-line', ['Area', 'Reason, as recorded', 'Marked', ['Tables', 'num']], regulated.map(r => h('tr', {class: r.on.kind === 'catalog' ? null : 'opens',
            onclick: opensRow, 'data-on': (r.on.path || []).join('.')},
            h('td', {class: 'name'}, onLink(catalog, r.on)), h('td', {class: 'wrap', 'data-why': ''}, provenanceWords(r.provenance)),
            h('td', null, r['set-at'] ? h('span', {class: 'tnum'}, at(r['set-at'])) : muted('–'), h('br'), muted(r['set-by'] || '')),
            // Tables and closed in one cell, so the table fits its half of the page (Atelier's re-walk after 212 to 217).
            h('td', {class: 'num'}, fmt(r.tables || 0), h('br'), muted(`${fmt(r.closed || 0)} closed`)))))
            : h('p', {class: 'muted'}, 'No regulated area in this catalog: an unclassified table stays open.'),
          closed.length ? [h('h3', null, 'Closed until classified'), h('ul', {class: 'stack closed-list'}, closed.map(on =>
            h('li', {'data-on': (on.path || []).join('.')}, onLink(catalog, on))))] : null,
          h('p', {class: 'fine'}, 'Until a table in a regulated area has its own classification, people can find it but not read or change it.'))),
      h('div', {class: 'panel', id: 'flagged'}, h('h2', null, 'Flagged tables ', h('span', {class: 'meta'}, 'read through Trino')),
        flagged.length ? tbl('stack-line', ['Table', ['Rules', 'num']], flagged.map(x => h('tr', {class: 'opens', onclick: opensRow,
          'data-on': (x.on.path || []).join('.')},
        h('td', {class: 'name'}, onLink(catalog, x.on)), h('td', {class: 'num'}, fmt(x.rules || 0)))))
          : h('p', {class: 'muted'}, 'No table in this catalog is flagged.')),
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
      h('td', null, h('div', {class: 'row-acts'}, x.kind === 'hold'
        ? may(catalog, 'manage-tags') ? h('button', {class: 'btn sm ghost', type: 'button', onclick: () => releaseHold(level, x, reload)}, 'Release') : null
        : operates() ? h('button', {class: 'btn sm ghost', type: 'button', onclick: () => drFreeze(level, true, reload)}, 'Lift') : null)));
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

const pathOf = securable => [...(securable.namespace || []), ...(securable.name ? [securable.name] : [])].join('.');

function onWords(securable) {
  const path = pathOf(securable);
  return path ? [code(path), ' ', muted('· ' + securable.type)] : muted('the whole catalog');
}

/**
 * The rail's Grants, for those who manage the catalog's grants; it was Governance's tab until backlog 245. Grants are
 * made in the Grant access dialog (backlog 248); the page lists a group's grants. With no group named, it lists the
 * catalog's groups, each with its duties and a link to its grants, to whoever may list them (manage-grants on the
 * catalog, backlog 260), and anyone else their own groups' grants.
 */
export function grantsPage(catalog, group) {
  const main = h('div', {class: 'nc-main', id: 'governance-grants'});
  const list = h('div');
  const duties = h('div', {class: 'duties'});
  const dutiesH = h('h2', null, 'Duties');
  const groupIn = h('input', {name: 'g', type: 'search', placeholder: 'A group, such as demo-analysts', 'aria-label': 'Group',
    autocomplete: 'off', spellcheck: 'false', value: group || null, list: 'grant-groups'});
  const groups = h('datalist', {id: 'grant-groups'});
  const filter = h('form', {class: 'grants-filter', role: 'search'},
    h('label', null, h('span', null, 'List the grants of'), h('span', {class: 'field mono'}, groupIn)), groups);
  // The catalog's groups, when the caller may list them (asking first writes no denial): the field's suggestions, and
  // the list shown while no group is named. Null for someone who may not.
  const knownRead = api.mayOnCatalog(catalog, 'manage-grants').then(yes => yes ? api.grantGroups(catalog) : null)
    .catch(() => null);
  knownRead.then(known => {
    if (known) for (const g of known.groups) groups.append(h('option', {value: g.group}));
  });

  /** The grants of the group in the field, or the caller's own groups' when it is empty; `made` is a grant to mark. */
  const showList = made => {
    const g = groupIn.value.trim();
    load(list, async () => {
      const known = g ? null : await knownRead;
      if (known) {
        fill(dutiesH, 'Duties');
        fill(duties, DUTIES.map(([k, w, ps]) => h('div', {class: 'duty', 'data-duty': k}, h('h4', null, w), h('p', null, ps))));
        return groupsList(catalog, known);
      }
      const grants = await api.grantsOfGroups(catalog, g ? [g] : []);
      const held = new Set(grants.map(x => DUTY[x.privilege]));
      fill(dutiesH, g ? `Duties of ${g} in ${catalog}` : `Duties of your groups in ${catalog}`);
      fill(duties, DUTIES.map(([k, w, ps]) => h('div', {class: 'duty' + (held.has(k) ? ' held' : ''), 'data-duty': k},
        h('h4', null, w + (held.has(k) ? ' · granted' : '')), h('p', null, ps))));
      const isMade = x => made && !x.branch && x.group === made.group && x.privilege === made.privilege && x.securable.type === made.target.kind
        && pathOf(x.securable) === pathOf(api.securable(made.target));
      return [h('h2', null, g ? `Grants to ${g} ` : 'Your groups\' grants ', h('span', {class: 'meta'}, `in ${catalog}`)),
        grants.length ? tbl('stack-line', ['Group', 'Privilege', 'On', 'Granted', ''], grants.map(x => h('tr', {'data-group': x.group,
          'data-privilege': x.privilege, 'data-path': pathOf(x.securable), 'data-branch': x.branch || null, class: isMade(x) ? 'is-new' : null},
        h('td', {class: 'name'}, x.group), privilegeCell(x), h('td', null, onWords(x.securable)),
        h('td', null, h('span', {class: 'tnum'}, at(x['granted-at'])), ' ', muted(x['granted-by'])),
        h('td', null, h('div', {class: 'row-acts'}, h('button', {class: 'btn sm ghost', type: 'button',
          onclick: () => revokeDialog(catalog, x, () => showList())}, 'Revoke'))))))
          : h('p', {class: 'muted'}, g ? `${g} holds no grant in ${catalog}.` : `Your groups hold no grant in ${catalog}.`)];
    });
  };
  /** Lists the grants of the group now in the field, kept in the link. */
  const listGroup = made => {
    const want = pageHref('grants', catalog, null, groupIn.value.trim() || null);
    if (location.hash !== want) history.replaceState(null, '', want);
    showList(made);
  };
  groupIn.addEventListener('change', () => listGroup());
  filter.addEventListener('submit', e => {
    e.preventDefault();
    listGroup();
  });
  showList();
  // A grant made in the dialog is shown in the list of its group, marked.
  const made = x => {
    groupIn.value = x.group;
    listGroup(x);
  };
  main.append(pageHead({
    eyebrow: `${catalog} · grants`,
    title: `Grants in ${catalog}`,
    sub: 'Grants go to groups, never to people. A group that manages grants holds no other duty, and data and tags never share a group: '
      + 'the catalog refuses a grant that would break that.',
    side: [h('button', {class: 'btn sm primary', type: 'button', 'data-act': 'grant-access',
      onclick: () => grantAccess({catalog, kind: 'catalog', namespace: []}, {pick: true, after: made})}, icon('key'), 'Grant access'),
    h('button', {class: 'btn sm', type: 'button', 'data-act': 'export-grants', onclick: () => exportGrants(catalog)}, icon('down'), 'Export every grant')],
  }), h('div', {class: 'grid-21'},
    h('div', {class: 'panel', id: 'grants-list'}, filter, list),
    h('div', {class: 'stack'},
      h('div', {class: 'panel'}, dutiesH, duties, h('p', {class: 'fine duties-note'}, 'Grants stand alone: a group that manages grants is '
        + 'granted nothing else. Data and tags exclude each other; reading goes with either. So no group can both change data and decide '
        + 'how it is labelled or who may see it.')))));
  return main;
}

/** Each duty's word on the page, by the name the groups route gives it. */
const DUTY_WORD = Object.fromEntries(DUTIES.map(([k, w]) => [k, w]));

/**
 * The catalog's groups, each with its duties and the privileges it holds, its name a link to its grants; then the
 * groups the deployment's configuration sets, which hold no grant (backlog 260).
 */
function groupsList(catalog, known) {
  const configured = [...known.grantAdmins.map(g => [g, 'manages grants on every catalog']),
    ...known.operators.map(g => [g, 'operates the platform'])];
  return [h('h2', null, 'Groups ', h('span', {class: 'meta'}, `in ${catalog}`)),
    known.groups.length ? tbl('stack-line grants-groups', ['Group', 'Duties', 'Privileges'], known.groups.map(g =>
      h('tr', {'data-group': g.group},
        h('td', {class: 'name'}, h('a', {href: pageHref('grants', catalog, null, g.group)}, g.group)),
        h('td', null, (g.duties || []).map(d => pill('', DUTY_WORD[d] || d))),
        h('td', {class: 'mono'}, (g.privileges || []).join(', ')))))
      : h('p', {class: 'muted'}, `No group holds a grant in ${catalog} yet.`),
    configured.length ? h('p', {class: 'fine muted grants-configured'}, 'Set by the deployment, holding no grant: ',
      configured.map(([g, words], i) => [i ? '; ' : '', code(g), ` ${words}`])) : null];
}

/** Every grant in the catalog, for an access review: as JSON or CSV. Needs manage-grants on the catalog. */
function exportGrants(catalog) {
  const out = h('div');
  const dlg = openDialog(`Every grant in ${catalog}`, out, {wide: true});
  load(out, async () => {
    const x = await api.exportGrants(catalog);
    const json = api.toJson(x, 2);
    const cell = v => /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
    const csv = ['group,privilege,type,namespace,name,branch,granted-by,granted-at', ...(x.grants || []).map(g => [g.group, g.privilege,
      g.securable.type, (g.securable.namespace || []).join('.'), g.securable.name || '', g.branch || '', g['granted-by'] || '',
      g['granted-at'] || '']
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
  // A platform operator freezes a namespace here as well as on its Access tab, which was the only way to it
  // (Atelier, 2026-11-24; backlog 221).
  const acts = [may(catalog, 'manage-tags')
    ? h('button', {class: 'btn sm', type: 'button', onclick: () => holdOnPath(catalog)}, icon('lock'), 'Place a hold on a path') : null,
    operates() ? h('button', {class: 'btn sm', type: 'button', 'data-act': 'freeze-path', onclick: () => freezeOnPath(catalog, reload)},
      icon('snow'), 'Freeze a namespace for DR') : null].filter(Boolean);
  main.append(pageHead({
    eyebrow: `${catalog} · governance`,
    title: `Holds and freezes in ${catalog}`,
    sub: 'A hold stops snapshot expiry, orphan removal, drops and purges on what it covers. A freeze stops every cleanup and write '
      + 'in its namespace until it is lifted.',
    side: acts.length ? acts : null,
  }), tabs, lists, h('h2', {class: 'section-h'}, `${catalog} itself`), own);
  return main;
}

/**
 * Governance for a platform operator who holds nothing in the catalog (backlog 325): the DR freeze alone. The summary
 * and the holds list need describe, which he holds nowhere, so they are not asked for. Item 323 lists here the
 * standing freezes he may lift, in `#operator-freezes`, once the catalog lets an operator read them (D55).
 */
function freezePage(catalog) {
  const main = h('div', {class: 'nc-main', id: 'governance-freeze'});
  const freezes = h('div', {class: 'stack', id: 'operator-freezes'});
  main.append(pageHead({
    eyebrow: `${catalog} · governance`,
    title: `DR freezes in ${catalog}`,
    sub: 'A freeze stops every cleanup and write in its namespace until it is lifted. As a platform operator you freeze a '
      + 'namespace here by its path. The catalog\'s holds, classifications and summary are for those whose groups hold a '
      + 'grant in it.',
    side: h('button', {class: 'btn sm', type: 'button', 'data-act': 'freeze-path', onclick: () => freezeOnPath(catalog, () => {})},
      icon('snow'), 'Freeze a namespace for DR'),
  }), freezes);
  return main;
}

/** A namespace named by its path, then the DR freeze dialog for it: platform operators only. */
function freezeOnPath(catalog, reload) {
  openDialog(`Freeze a namespace in ${catalog} for DR`, h('div', null,
    h('p', null, 'Name the namespace. The freeze stops every commit and cleanup beneath it until it is lifted.'),
    dialogForm([field('Namespace', text('path', 'such as equities.eu'))], 'Next', async (form, out) => {
      const parts = form.elements.path.value.trim().split('.').filter(Boolean);
      if (!parts.length) {
        fill(out, h('p', {class: 'fine grant-hint'}, 'Write the namespace\'s path.'));
        return;
      }
      form.closest('dialog').close();
      drFreeze({catalog, kind: 'namespace', namespace: parts}, false, reload);
    })));
}

function holdOnPath(catalog) {
  openDialog(`Place a hold in ${catalog}`, h('div', null,
    h('p', null, 'Name a namespace, table or view. The hold carries its reason for as long as it stands.'),
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

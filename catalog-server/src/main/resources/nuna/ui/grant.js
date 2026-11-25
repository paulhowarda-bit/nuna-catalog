// The Grant access dialog (Atelier, 2026-11-24; backlog 248): one dialog for the Grants page and every Access tab. A
// group picked from those the catalog knows (backlog 247), each with its duties; the access in plain words, only what
// means something on the object; the object fixed on an Access tab, found by a path search on the Grants page; and
// before Grant, one live line from the grant's preview (backlog 246) and the grants that already reach the object.
// Group names are anyone's free text: they go on the page as text only, as everything does (dom.js).

import * as api from './api.js';
import {auditOpen} from './access.js';
import {claims} from './auth.js';
import {code, failure, fill, granted, h, icon, muted, openDialog, pill} from './dom.js';
import {dotted, pageHref} from './route.js';

const EVERY_KIND = ['catalog', 'namespace', 'table', 'view'];

/**
 * Each privilege in plain words, in the order offered, with the kinds it means something on: create only where
 * something can be made inside (the catalog refuses it on a table or view, backlog 246), and write never on a view,
 * which has no rows (backlog 258 makes the catalog refuse it too).
 */
export const ACCESS = [
  ['describe', 'See it', EVERY_KIND],
  ['select', 'Read its data', EVERY_KIND],
  ['write', 'Read and write rows', ['catalog', 'namespace', 'table']],
  ['modify', 'Change it (schema, properties, maintenance)', EVERY_KIND],
  ['create', 'Create tables, views and namespaces in it', ['catalog', 'namespace']],
  ['manage-tags', 'Set labels and holds', EVERY_KIND],
  ['manage-grants', 'Manage grants', EVERY_KIND],
];

/** The duties of D18, as the groups route names them, in the words of the Grants page's Duties panel. */
const DUTY_WORDS = {read: 'Reading', data: 'Data', grants: 'Grants', tags: 'Tags'};

/** The duty each privilege belongs to (D18). */
export const DUTY = {describe: 'read', select: 'read', write: 'data', modify: 'data', create: 'data', 'manage-grants': 'grants',
  'manage-tags': 'tags'};

/** The ladder: each gives the ones before it, so a group holding modify above already reads what is beneath. */
const RUNGS = ['describe', 'select', 'write', 'modify'];
const covers = (held, wanted) => held === wanted
  || (RUNGS.includes(held) && RUNGS.includes(wanted) && RUNGS.indexOf(held) >= RUNGS.indexOf(wanted));

const NEW_GROUP = 'The catalog can\'t list your directory\'s groups; check the spelling.';
const NEW_HERE = (group, catalog) => `${group} holds no grant in ${catalog} yet. The catalog can't list your directory's groups, `
  + 'so check the spelling.';

const nameOf = target => target.kind === 'catalog' ? target.catalog : dotted(target);
const pathOf = securable => [...(securable.namespace || []), ...(securable.name ? [securable.name] : [])].join('.');
const inside = target => target.kind === 'catalog' || target.kind === 'namespace';

/** What the group will be able to do once granted, as the live line says it. */
function willWords(privilege, target) {
  const n = nameOf(target);
  const all = inside(target);
  switch (privilege) {
    case 'describe': return all ? `see every table and view in ${n}, now and later, but not read their data` : `see ${n}, but not read its data`;
    case 'select': return all ? `read every table and view in ${n}, now and later` : `read ${n}`;
    case 'write': return all ? `read and write rows in every table in ${n}, now and later` : `read and write rows in ${n}`;
    case 'modify': return all ? `read, write and change every table and view in ${n}, now and later: rows, schema, properties and maintenance`
      : target.kind === 'view' ? `read ${n} and change its definition and properties`
        : `read, write and change ${n}: its rows, schema, properties and maintenance`;
    case 'create': return `create tables, views and namespaces in ${n}`;
    case 'manage-tags': return all ? `set labels and holds on ${n} and everything in it, now and later` : `set labels and holds on ${n}`;
    case 'manage-grants': return all ? `manage grants on ${n} and everything in it, now and later` : `manage grants on ${n}`;
    default: return `hold ${privilege} on ${n}`;
  }
}

/**
 * Why separation of duties refuses `privilege` to `group`, from what the dialog knows of its duties, in the order the
 * catalog checks (Separation.java); null when the dialog does not know enough to say.
 */
function separationWords(group, privilege, catalog, known) {
  if (!known) return null;
  if (known.operators.includes(group)) return `${group} operates the platform, so it holds no grant.`;
  if (known.grantAdmins.includes(group)) return `${group} manages grants on every catalog, so it holds no other grant.`;
  const held = new Set((known.groups.find(g => g.group === group) || {}).duties || []);
  const wanted = DUTY[privilege];
  if (wanted === 'grants' && ['read', 'data', 'tags'].some(d => held.has(d))) {
    return `${group} already holds other access in ${catalog}, and a group that manages grants holds nothing else.`;
  }
  if (held.has('grants') && wanted !== 'grants') return `${group} manages grants in ${catalog}, so it holds nothing else.`;
  if (wanted === 'data' && held.has('tags')) return `${group} sets labels and holds in ${catalog}, and data and tags never share a group.`;
  if (wanted === 'tags' && held.has('data')) return `${group} changes data in ${catalog}, and data and tags never share a group.`;
  return null;
}

/** A day as the live line says it: "today", or "on 12 Nov" (with the year when it is not this one). */
function onDay(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return 'today';
  return 'on ' + d.toLocaleDateString('en-GB', {day: 'numeric', month: 'short',
    year: d.getFullYear() === now.getFullYear() ? undefined : 'numeric'});
}

/**
 * The groups the catalog knows, from its grants (backlog 247), with the deployment's grant-admin and operator groups;
 * null when the caller may not list them. Only manage-grants on the catalog itself lists them, so the dialog asks the
 * batch check first, which is not audited, rather than collect a denial for a caller who manages a namespace's grants.
 */
async function knownGroups(catalog) {
  return await api.mayOnCatalog(catalog, 'manage-grants') ? api.grantGroups(catalog) : null;
}

/** The grants that reach each object the dialog has asked about, read once per object. */
function reachCache() {
  const byKey = new Map();
  return target => {
    const key = [target.kind, dotted(target)].join('\u001f');
    if (!byKey.has(key)) byKey.set(key, api.effectiveGrants(target).then(x => x.grants || [], () => []));
    return byKey.get(key);
  };
}

/**
 * Opens Grant access on `target`. On an Access tab the object is fixed; with `pick` (the Grants page) it starts on the
 * catalog and a path search changes it. `group` and `privilege` fill the dialog in, as Maintenance's "Grant write to
 * demo-maintenance" does (backlog 252); the person still presses Grant. `after({group, privilege, target})` runs once a
 * grant is made, and `closed()` when the dialog closes after one.
 */
export function grantAccess(target, {pick, after, closed, group, privilege} = {}) {
  const catalog = target.catalog;
  let on = target;
  let known;
  let stopped = false;
  let seq = 0;
  let timer = 0;
  const reach = reachCache();
  const own = new Set(((claims() || {}).groups) || []);

  // ---- group ----
  const groupIn = h('input', {name: 'g', type: 'text', placeholder: 'Pick one below, or type a group', 'aria-label': 'Group',
    autocomplete: 'off', spellcheck: 'false', value: group || null});
  const groupList = h('div', {class: 'grp-pick', role: 'group', 'aria-label': `Groups in ${catalog}`});
  const newGroup = h('p', {class: 'fine grp-new', 'data-new-group': '', hidden: true});
  const drawGroups = () => {
    const typed = groupIn.value.trim();
    // A name the catalog does not know may be a new group or a misspelling; the catalog cannot tell which.
    newGroup.hidden = !typed || known === undefined || !!known && (known.groups.some(g => g.group === typed)
      || known.grantAdmins.includes(typed) || known.operators.includes(typed));
    fill(newGroup, known ? NEW_HERE(typed, catalog) : NEW_GROUP);
    if (known === undefined) return fill(groupList, muted('Reading the groups that hold a grant here…'));
    if (known === null) return fill(groupList);
    const words = typed.toLowerCase();
    const shown = g => !words || g.toLowerCase().includes(words);
    const groups = known.groups.filter(g => shown(g.group));
    const configured = [...known.grantAdmins.map(g => [g, 'Manages grants on every catalog']),
      ...known.operators.map(g => [g, 'Operates the platform'])].filter(([g]) => shown(g));
    return fill(groupList, groups.map(g => h('button', {class: 'grp', type: 'button', 'data-group': g.group,
      'aria-pressed': String(g.group === typed), onclick: () => {
        groupIn.value = g.group;
        changed();
        const again = [...groupList.querySelectorAll('button.grp')].find(b => b.dataset.group === g.group);
        if (again) again.focus();
      }},
    h('span', {class: 'grp-n'}, g.group),
    h('span', {class: 'grp-d'}, (g.duties || []).map(d => pill('', DUTY_WORDS[d] || d)),
      own.has(g.group) ? pill('accent', 'Yours') : null))),
    configured.length ? h('p', {class: 'fine muted grp-cfg-h'}, 'Set by the deployment: these hold no grant.') : null,
    configured.map(([g, words]) => h('span', {class: 'grp cfg', 'aria-disabled': 'true', 'data-group': g,
      title: 'Set by the deployment\'s configuration: it can be granted nothing.'},
    h('span', {class: 'grp-n'}, g), h('span', {class: 'grp-d'}, pill('', words), own.has(g) ? pill('accent', 'Yours') : null))),
    // A name matching none is new or misspelt, which the line under the list says.
    !groups.length && !configured.length && !typed ? muted('No group holds a grant in this catalog yet.') : null);
  };
  const groupsRead = knownGroups(catalog).then(k => { known = k; }, () => { known = null; }).then(drawGroups);
  drawGroups();

  // ---- access ----
  const accessBox = h('div', {class: 'acc-pick', role: 'radiogroup', 'aria-label': 'Access'});
  const chosen = () => (accessBox.querySelector('input:checked') || {}).value || '';
  let preset = privilege;
  const drawAccess = () => {
    const was = chosen() || preset || '';
    preset = null;
    fill(accessBox, ACCESS.filter(([, , kinds]) => kinds.includes(on.kind)).map(([p, words]) => h('label', {class: 'acc'},
      h('input', {type: 'radio', name: 'pv', value: p, checked: p === was, onchange: changed}),
      // A view has no rows, schema or maintenance of its own: changing it is its definition and properties.
      h('span', null, p === 'modify' && on.kind === 'view' ? 'Change it (definition, properties)' : words), ' ',
      h('code', {class: 'acc-k'}, p))));
  };

  // ---- on ----
  const onNow = h('p', {class: 'on-now'});
  const drawOn = () => fill(onNow, on.kind === 'catalog' ? [code(catalog), ' ', muted('· the whole catalog')]
    : [code(dotted(on)), ' ', muted('· ' + on.kind)],
  pick && on.kind !== 'catalog' ? [' ', h('button', {class: 'btn sm ghost', type: 'button', 'data-act': 'whole-catalog',
    onclick: () => choose({catalog, kind: 'catalog', namespace: []})}, 'Use the whole catalog')] : null);
  const choose = t => {
    on = t;
    drawOn();
    drawAccess();
    changed();
  };
  let onPart = [h('span', {class: 'dlg-l'}, 'On'), onNow];
  if (pick) {
    const search = h('input', {type: 'search', name: 'path', 'aria-label': 'Find a namespace, table or view',
      placeholder: 'Find a namespace, table or view',
      autocomplete: 'off', spellcheck: 'false'});
    const hits = h('div', {class: 'on-hits', 'aria-live': 'polite'});
    let searchTimer = 0;
    let asked = '';
    const find = () => {
      clearTimeout(searchTimer);
      const words = search.value.trim().toLowerCase().split(/[\s./]+/).filter(Boolean);
      if (!words.length) {
        asked = '';
        return fill(hits);
      }
      const fragment = words[words.length - 1];
      asked = search.value;
      searchTimer = setTimeout(async () => {
        let rows;
        try {
          rows = await api.names(fragment);
        } catch (error) {
          if (asked === search.value) fill(hits, failure(error, {compact: true}));
          return;
        }
        if (asked !== search.value) return;
        const found = rows.filter(x => x.catalog === catalog).map(x => x.kind === 'namespace'
          ? {catalog, kind: 'namespace', namespace: [...x.namespace, x.name]} : {catalog, kind: x.kind, namespace: x.namespace, name: x.name})
          .filter(t => words.every(w => dotted(t).toLowerCase().includes(w)));
        fill(hits, found.length ? found.slice(0, 12).map(t => h('button', {class: 'on-hit', type: 'button', 'data-path': dotted(t),
          'data-kind': t.kind, onclick: () => {
            search.value = '';
            asked = '';
            fill(hits);
            choose(t);
          }}, code(dotted(t)), ' ', muted('· ' + t.kind)))
          : muted(`Nothing in ${catalog} you may see is named like “${search.value.trim()}”.`));
      }, 150);
    };
    search.addEventListener('input', find);
    search.addEventListener('keydown', e => {
      if (e.key !== 'Enter') return;
      e.preventDefault();
      const first = hits.querySelector('button.on-hit');
      if (first) first.click();
    });
    onPart = [...onPart, h('span', {class: 'field mono'}, search), hits];
  }

  // ---- the live line, the Grant button and the answer ----
  const line = h('div', {class: 'grant-line', 'aria-live': 'polite', 'data-answer': 'none'});
  const ok = h('button', {class: 'btn primary', type: 'submit', disabled: true}, 'Grant');
  const out = h('div', {class: 'dlg-out', 'aria-live': 'polite', id: 'grant-result'});
  const say = (answer, ...kids) => {
    line.dataset.answer = answer;
    fill(line, kids);
  };

  /** Asks the preview again for what is chosen now, a moment after the last change, and draws its answer. */
  function changed() {
    clearTimeout(timer);
    const n = ++seq;
    fill(out);
    drawGroups();
    const group = groupIn.value.trim();
    const privilege = chosen();
    ok.disabled = true;
    if (!group || !privilege) return say('none', h('p', {class: 'muted'}, 'Pick a group and the access to grant.'));
    // After a refusal of the preview itself (a 403 or a 404, each an audited denial), the dialog stops asking: the
    // grant route decides, and its answer is shown as the catalog gave it.
    if (stopped) {
      ok.disabled = false;
      return say('none');
    }
    say('checking', muted('Checking…'));
    const asked = on;
    timer = setTimeout(async () => {
      let answer;
      try {
        answer = await api.previewGrant(asked, group, privilege);
      } catch (error) {
        if (n !== seq) return;
        stopped = error.status === 403 || error.status === 404;
        ok.disabled = false;
        say('error', failure(error, {compact: true}));
        return;
      }
      const reached = answer.answer === 'admitted' ? await reach(asked) : [];
      // A refusal is said in the group's own duties, so the line waits for the groups to be read.
      await groupsRead;
      if (n === seq) draw(answer, group, privilege, asked, reached);
    }, 300);
  }

  function draw(answer, group, privilege, target, reached) {
    const n = nameOf(target);
    if (answer.answer === 'unchanged') {
      return say('unchanged', h('p', null, `Already held: ${privilege} on ${n}, granted by ${answer['granted-by']} ${onDay(answer['granted-at'])}.`));
    }
    if (answer.answer === 'refused') {
      // The dialog says why in the group's own terms when it knows them; only when it does not is the catalog's reason
      // shown, beneath its general rule.
      const plain = answer.refusal === 'self-grant'
        ? `You belong to ${group}. No one grants to their own group: ask another grant admin.`
        : answer.refusal === 'separation-of-duties' ? separationWords(group, privilege, catalog, known) : null;
      const words = plain || (answer.refusal === 'separation-of-duties'
        ? `${group} can't be given this. A group that manages grants holds nothing else, and data and tags never share a group.`
        : `${privilege} means nothing on a ${target.kind}.`);
      return say('refused', h('div', {class: 'refusal compact', role: 'alert', 'data-refusal': answer.refusal},
        h('p', {class: 'refusal-h'}, icon('x'), words),
        plain ? null : h('p', {class: 'fine'}, `The catalog says “${answer.reason}”.`)));
    }
    ok.disabled = false;
    const through = reached.find(g => g.group === group && covers(g.privilege, privilege));
    return say('admitted', h('p', null, `${group} will ${willWords(privilege, target)}.`),
      through ? h('p', {class: 'fine', 'data-through': ''}, `It already has this through ${through.privilege} on `
        + `${through.securable.type === 'catalog' ? catalog : pathOf(through.securable)}, granted by ${through['granted-by']} `
        + `${onDay(through['granted-at'])}. A grant here stands if that one is revoked.`) : null);
  }

  // Once a grant is made there is nothing to cancel: the button says Close from then on, whatever is picked after.
  let made = false;
  const cancel = h('button', {class: 'btn ghost', type: 'button', onclick: () => form.closest('dialog').close()}, 'Cancel');
  const form = h('form', {class: 'dlg-form grant-dlg', novalidate: true},
    h('div', {class: 'dlg-f'}, h('span', {class: 'dlg-l'}, 'Group'), h('span', {class: 'field mono'}, groupIn), groupList, newGroup),
    h('div', {class: 'dlg-f', role: 'group', 'aria-label': 'Access'}, h('span', {class: 'dlg-l'}, 'Access'),
      h('p', {class: 'fine muted'}, 'From See it to Change it, each includes those above it.'), accessBox),
    h('div', {class: 'dlg-f'}, onPart),
    // The answer and Grant stay in view at the dialog's foot however far the lists above it scroll.
    h('div', {class: 'grant-foot'}, line, out, h('div', {class: 'dlg-acts'}, ok, cancel)));
  groupIn.addEventListener('input', changed);
  groupIn.addEventListener('keydown', e => { if (e.key === 'Enter') e.preventDefault(); });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const group = groupIn.value.trim();
    const privilege = chosen();
    if (ok.disabled || !group || !privilege) return;
    ok.disabled = true;
    const target = on;
    try {
      const answer = await api.grant(target, group, privilege);
      const dlg = form.closest('dialog');
      fill(out, granted(`Granted ${privilege} on ${nameOf(target)} to ${group}.`, answer, ['It is in the audit trail.',
        auditOpen(catalog) ? [' ', h('a', {href: pageHref('audit', catalog) + '&kind=grant', 'data-act': 'see-record',
          onclick: () => dlg.close()}, 'See the record')] : null]));
      say('done');
      if (!made && closed) dlg.addEventListener('close', () => closed(), {once: true});
      made = true;
      fill(cancel, 'Close');
      if (after) after({group, privilege, target});
    } catch (error) {
      fill(out, failure(error));
      ok.disabled = false;
    }
  });
  drawOn();
  drawAccess();
  changed();
  openDialog(pick ? `Grant access in ${catalog}` : `Grant access on ${nameOf(target)}`, form);
}

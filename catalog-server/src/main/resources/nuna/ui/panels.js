// The tabs every catalog object shares, each one its own render function on its own route: governance (with
// holds, the four labels people set here and a DR freeze), grants (every grant that reaches the object, revoked here
// and made in the Grant access dialog, grant.js, and who may use it), the audit trail, and the dialogs those open; and
// the catalog's holds, freezes and regulated areas as marks on the tree and in Contents (item 128). The catalog-wide audit feed with its hash chain is the Audit
// page (audit.js, item 125).

import * as api from './api.js';
import {may, operates} from './access.js';
import {$$, badge, code, dialogForm, facts, field, fill, granted, h, heldPill, frozenPill, icon, jsonPre, load,
  muted, openDialog, panel, pill, plural, select, tbl, techDetails, text, when, at, copyButton} from './dom.js';
import {dotted, objectHref, pageHref} from './route.js';
import {liveRefLink, refWords} from './refs.js';
import {heldHere} from './requests.js';
import {rulesPanel, yourView} from './rules.js';
import {grantAccess} from './grant.js';
import {keptPanel} from './kept.js';

const CLASSES = ['public', 'internal', 'confidential', 'restricted'];
const ATTRIBUTES = ['classification', 'owner', 'retention-class', 'regulated', 'row-column-policy'];
/** Each attribute in words, its key one hover away (Atelier's words table, T10). */
const ATTRIBUTE_LABELS = {classification: 'Classification', owner: 'Owner', 'retention-class': 'Retention class',
  regulated: 'Regulated area', 'row-column-policy': 'Flagged for row and column rules', 'maintenance-policy': 'Maintenance policy'};
const attributeLabel = name => ATTRIBUTE_LABELS[name] ? h('span', {title: name}, ATTRIBUTE_LABELS[name]) : name;
/** An attribute's value in words: the two flags are Yes or No, not true or false (backlog 213; Atelier, 2026-11-24). */
const attributeValue = (name, v) => (name === 'regulated' || name === 'row-column-policy') && /^(true|false)$/i.test(String(v))
  ? (String(v).toLowerCase() === 'true' ? 'Yes' : 'No') : String(v);
/** The maintenance policy is a link to where it reads in words, its stored form in the title (the 27 Nov review, item 5). */
const policyLink = (target, v) => h('a', {href: target.kind === 'table' ? objectHref(target, 'maintenance') : pageHref('maintenance', target.catalog),
  title: String(v), 'data-act': 'see-policy'}, 'See the policy');

const what = target => target.kind === 'catalog' ? target.catalog : dotted(target);
const lastWord = target => target.name || (target.namespace.length ? target.namespace[target.namespace.length - 1] : target.catalog);

// ---- governance ----

/** A level as the governance summary and the holds list name it, {kind, id, path}, as a console target. */
export function targetOfOn(catalog, on) {
  const path = on.path || [];
  if (on.kind === 'catalog') return {catalog, kind: 'catalog', namespace: []};
  if (on.kind === 'namespace') return {catalog, kind: 'namespace', namespace: path};
  return {catalog, kind: on.kind, namespace: path.slice(0, -1), name: path[path.length - 1]};
}

/** The key the tree gives a node: `catalog`, or a kind and a dotted path, such as `table:a.b.t`. */
export const keyOfOn = on => on.kind === 'catalog' ? 'catalog' : on.kind + ':' + (on.path || []).join('.');

const marks = new Map();

/**
 * The catalog's holds, freezes, regulated areas and closed tables, by tree key: {held, frozen, regulated, closed}.
 * Read once a minute at most; empty when the caller may not read them (they need describe on the catalog).
 */
export function governanceMarks(catalog) {
  const hit = marks.get(catalog);
  if (hit && Date.now() - hit.at < 60_000) return hit.promise;
  const promise = Promise.all([api.holdsAndFreezes(catalog), api.governanceSummary(catalog)]).then(([hf, sum]) => {
    const by = new Map();
    const mark = (on, k) => {
      const key = keyOfOn(on);
      if (!by.has(key)) by.set(key, {});
      by.get(key)[k] = true;
    };
    for (const x of hf.standing || []) mark(x.on, x.kind === 'freeze' ? 'frozen' : 'held');
    for (const r of sum.regulated || []) mark(r.on, 'regulated');
    for (const on of sum.closed || []) mark(on, 'closed');
    return by;
  }, () => new Map());
  marks.set(catalog, {at: Date.now(), promise});
  return promise;
}

/** Forgets the marks, so the next look reads them again (after a hold or freeze changes). */
export const forgetMarks = catalog => marks.delete(catalog);

/** The pills of one object's marks, for Contents. */
export function markPills(m) {
  if (!m) return null;
  const out = [m.held ? heldPill() : null, m.frozen ? frozenPill() : null, m.regulated ? badge('regulated') : null,
    m.closed ? pill('bad', 'Closed until classified') : null].filter(Boolean);
  return out.length ? out.flatMap((x, i) => i ? [' ', x] : [x]) : null;
}

/** The catalog and each namespace above `target`, each with its governance answer, by level id. */
async function levelsAbove(target) {
  const above = [{catalog: target.catalog, kind: 'catalog', namespace: []}];
  const depth = target.kind === 'namespace' ? target.namespace.length - 1 : target.kind === 'catalog' ? -1 : target.namespace.length;
  for (let i = 1; i <= depth; i++) above.push({catalog: target.catalog, kind: 'namespace', namespace: target.namespace.slice(0, i)});
  const answers = await Promise.allSettled(above.map(a => api.governance(a)));
  const levels = new Map();
  answers.forEach((answer, i) => { if (answer.status === 'fulfilled') levels.set(answer.value.target.id, above[i]); });
  return levels;
}

function levelLabel(level, hereId, levels) {
  const [kind, id] = String(level).split(':');
  if (id === hereId) return h('span', {'data-level': 'here'}, 'this ' + kind);
  const t = levels.get(id);
  return h('span', {class: 'muted', title: level}, 'inherited from ' + kind + ' ',
    t ? h('a', {href: objectHref(t, 'access')}, what(t)) : code((id || '').slice(0, 8)));
}

/** A table's, view's, namespace's or catalog's governance answer, with the levels it names. */
export async function governanceOf(target) {
  const g = await api.governance(target);
  const named = [...(g.holds || []), ...(g.attributes || [])].some(x => !String(x.level).endsWith(':' + g.target.id));
  const levels = named ? await levelsAbove(target).catch(() => new Map()) : new Map();
  levels.set(g.target.id, target);
  return {g, levels};
}

/**
 * Whether the table itself is flagged for row and column rules (D20): only its own flag set true closes its storage to
 * all but a trusted engine, so a flag on a level above does not count.
 */
export const flaggedHere = g => (g.attributes || []).some(a => a.attribute === 'row-column-policy'
  && String(a.level).endsWith(':' + g.target.id) && String(a.value).toLowerCase() === 'true');

/** What a flagged table's Overview, Schema and History say in place of the refused load (Atelier, 2026-11-24; backlog 241). */
export const TRINO_WORDS = 'Its data is read through Trino, which applies each group\'s rule. The console shows no rows.';

/**
 * Whether a table's load was refused because the table is closed until classified (D27): in a regulated area, with no
 * classification of its own. Told from the table's own answer, not from the catalog's marks, which need describe on
 * the catalog (Atelier, 2026-11-27; backlog 324). The catalog answers it as a ForbiddenException, as every refusal.
 */
export const closedRefusal = error => !!error && error.status === 403
  && /closed until it is classified/.test(String(error.message || ''));

/** The regulated area a closed table is in, in words: its nearest level marked regulated, or null when unread. */
async function regulatedArea(target) {
  const {g, levels} = await governanceOf(target);
  const a = (g.attributes || []).find(x => x.attribute === 'regulated' && String(x.value).toLowerCase() === 'true');
  const t = a && levels.get(String(a.level).split(':')[1]);
  return t ? (t.kind === 'catalog' ? t.catalog : what(t)) : null;
}

/**
 * The one notice a closed table's tabs show in place of its load (backlog 324): closed, why, and who opens it; for a
 * caller who may manage tags, Set a classification, the Access tab's dialog. `reload` runs once it is set.
 */
export async function closedNotice(target, reload) {
  const area = await regulatedArea(target).catch(() => null);
  const tags = may(target.catalog, 'manage-tags');
  return h('div', {class: 'notice warn-note', id: 'closed-until-classified'}, icon('lock'),
    h('div', {class: 'stack'},
      h('span', null, h('b', null, 'Closed until classified.'), ` ${target.name} is in `,
        area ? ['the regulated area ', h('b', null, area)] : 'a regulated area', ' and has no classification of its own, so nobody can read or change it. ',
        'A steward opens it by setting a classification.'),
      tags ? h('div', null, h('button', {class: 'btn sm primary', type: 'button', 'data-act': 'classify-closed',
        onclick: () => classify(target, null, reload)}, 'Set a classification')) : null));
}

/** The governance facts on a table's overview: its holds, a DR freeze, its classification and owner, with where each was set. */
export async function governanceSummary(target) {
  const {g, levels} = await governanceOf(target);
  const here = g.target.id;
  const e = g.effective || {};
  const attr = name => (g.attributes || []).find(a => a.attribute === name);
  const withLevel = name => e[name] === undefined || e[name] === null ? null
    : [String(e[name]), ' ', attr(name) ? h('span', {class: 'muted'}, '· ', levelLabel(attr(name).level, here, levels)) : null];
  const holds = g.holds || [];
  return facts([
    ['Legal hold', holds.length ? holds.map((x, i) => [i ? h('br') : null, heldPill(x.reason), h('br'),
      h('span', {class: 'fine'}, `placed by ${x['placed-by']}, ${at(x['placed-at'])}`)]) : muted('none')],
    ['DR freeze', e['dr-freeze'] ? frozenPill(String(e['dr-freeze'])) : muted('none')],
    ['Classification', withLevel('classification')],
    ['Owner', withLevel('owner')],
  ]);
}

/** The governance tab: what is in force and where each value comes from, the holds, and what the catalog enforces. */
export async function governanceTab(target, reload) {
  const {g, levels} = await governanceOf(target);
  const here = g.target.id;
  const effective = g.effective || {};
  const freeze = effective['dr-freeze'];
  const holds = g.holds || [];
  const attrs = g.attributes || [];
  const view = target.kind === 'view';
  // The row and column policy counts only on the table it is set on, never from a level above (backlog 239).
  const nearest = name => attrs.find(a => a.attribute === name && (name !== 'row-column-policy' || String(a.level).endsWith(':' + here)));
  const ownRegulated = attrs.some(a => a.attribute === 'regulated' && String(a.level).endsWith(':' + here) && a.value === 'true');
  const flagged = flaggedHere(g);
  const valueWithSource = name => {
    const a = nearest(name);
    const v = effective[name];
    if (v === undefined || v === null) return null;
    return [attributeValue(name, v), ' ', a ? h('span', {class: 'muted'}, '· ', levelLabel(a.level, here, levels), a.provenance ? ' · ' + a.provenance : '') : null];
  };
  const status = h('div', {class: 'status-row'},
    freeze ? h('p', {class: 'status-frozen'}, frozenPill('DR freeze in force'), ' ', h('span', {class: 'fine'}, String(freeze)))
      : h('p', {class: 'status-free'}, pill('ok', 'No DR freeze'), ' ', muted(view ? 'Its definition may be replaced.' : 'Commits are allowed.')),
    holds.length ? h('p', {class: 'status-held'}, heldPill(plural(holds.length, 'legal hold')), ' ',
      h('span', {class: 'fine'}, view ? 'It cannot be dropped or purged.' : 'Snapshots cannot be removed; it cannot be dropped or purged.'))
      : h('p', null, pill('', 'No legal hold')));
  // Changing labels and holds needs manage-tags, a DR freeze a platform operator: drawn only for those (item 184).
  const tags = may(target.catalog, 'manage-tags');
  const freezes = target.kind === 'namespace' && operates();
  const left = h('div', {class: 'panel', id: 'governance'}, h('h2', null, 'Governance ', h('span', {class: 'meta'}, 'each value with where it was set')),
    status,
    facts(ATTRIBUTES.map(name => [attributeLabel(name), valueWithSource(name)]), 'effective'),
    !tags ? null : h('div', {class: 'refusal-acts', 'data-acts': 'labels'},
      h('button', {class: 'btn sm', type: 'button', 'data-label': 'classification', onclick: () => classify(target, effective.classification, reload)},
        effective.classification ? 'Change the classification' : 'Set a classification'),
      h('button', {class: 'btn sm', type: 'button', 'data-label': 'owner', onclick: () => setOwner(target, effective.owner, reload)},
        effective.owner ? 'Change the owner' : 'Set the owner'),
      // A retention class keeps snapshots, which a view has none of; a regulated mark closes tables only (item 146).
      !view ? h('button', {class: 'btn sm', type: 'button', 'data-label': 'retention-class',
        onclick: () => setRetentionClass(target, effective['retention-class'], reload)},
      effective['retention-class'] ? 'Change the retention class' : 'Set a retention class') : null,
      target.kind === 'catalog' || target.kind === 'namespace' ? h('button', {class: 'btn sm', type: 'button', 'data-label': 'regulated',
        onclick: () => setRegulated(target, ownRegulated, effective.regulated === 'true', reload)},
      ownRegulated ? 'Clear the regulated mark' : 'Mark as a regulated area') : null,
      // The row and column flag is a table's own (backlog 239): set and removed here, on the table only (backlog 241).
      target.kind === 'table' ? h('button', {class: 'btn sm', type: 'button', 'data-label': 'row-column-policy',
        onclick: () => setFlag(target, flagged, reload)}, flagged ? 'Remove the flag' : 'Flag for row and column rules') : null),
    !tags && !freezes ? null : h('div', {class: 'refusal-acts'},
      tags ? h('button', {class: 'btn sm', type: 'button', onclick: () => placeHold(target, reload)}, icon('lock'),
        target.kind === 'namespace' ? 'Place a hold here' : 'Place a hold') : null,
      freezes ? h('button', {class: 'btn sm', type: 'button', onclick: () => drFreeze(target, !!freeze, reload)},
        icon('snow'), freeze ? 'Lift the DR freeze' : 'Freeze for DR') : null));
  const holdsPanel = panel('Legal holds', holds.length ? 'nearest level first' : null, holds.length
    ? tbl('holds', ['Reason, as recorded', 'Level', 'Placed', ''], holds.map(hold => h('tr', {'data-hold': hold.id},
      h('td', null, hold.reason), h('td', null, levelLabel(hold.level, here, levels)),
      h('td', null, h('span', {class: 'tnum'}, at(hold['placed-at'])), ' ', muted(hold['placed-by'])),
      h('td', null, tags ? h('button', {class: 'btn sm ghost', type: 'button', onclick: () =>
        releaseHold(levels.get(String(hold.level).split(':')[1]) || target, hold, reload)}, 'Release') : null))))
    : h('p', {class: 'muted'}, 'None, here or above.'));
  const attributes = panel('Attributes', 'nearest level first', attrs.length
    ? tbl('attributes stack-line', ['Attribute', 'Value', 'Level', 'Provenance', 'Set'], attrs.map(a => h('tr', null,
      h('td', {class: ATTRIBUTE_LABELS[a.attribute] ? null : 'name'}, attributeLabel(a.attribute)), h('td', {class: 'wrap'}, a.attribute === 'maintenance-policy' ? policyLink(target, a.value) : attributeValue(a.attribute, a.value)), h('td', null, levelLabel(a.level, here, levels)),
      h('td', {class: 'muted wrap'}, a.provenance), h('td', null, h('span', {class: 'tnum'}, at(a['set-at'])), ' ', muted(a['set-by'])))))
    : h('p', {class: 'muted'}, 'No attribute set here or above.'));
  const enforced = panel('What the catalog enforces', target.kind === 'namespace' || target.kind === 'catalog' ? 'beneath it' : 'on every call',
    facts([
      // A view has no snapshots or data files: what it enforces is on replacing its definition, and on its drop.
      view ? ['Replacing the definition', freeze ? [h('b', null, 'Refused'), ' · frozen until the DR freeze is lifted']
        : [h('b', null, 'Allowed'), ' · to groups holding modify']]
        : ['Commits', freeze ? [h('b', null, 'Refused'), ' · frozen until the DR freeze is lifted'] : [h('b', null, 'Allowed'), ' · to groups holding write']],
      view ? null : ['Snapshot expiry', holds.length ? [h('b', null, 'Off'), ' · under legal hold'] : freeze ? [h('b', null, 'Off'), ' · frozen for DR']
        : [h('b', null, 'On'), ' · as its maintenance policy sets']],
      ['Drop and purge', holds.length ? [h('b', null, 'Refused'), ' · under legal hold'] : [h('b', null, 'Allowed'), ' · to groups holding modify']],
    ]));
  return h('div', {class: 'stack'}, h('div', {class: 'grid-2'}, left, h('div', {class: 'stack'}, enforced, holdsPanel)), attributes);
}

// The four labels people set here (D27, amended 2026-11-22): each through the push API's route, recorded with the
// console as its source; an outside tool that pushes one later records its own.

const RECORDED = 'The change is recorded as set from the console, under your name, in the audit trail.';

/** Where a label set on `target` reaches: the object itself, or everything beneath a catalog or namespace. */
const reachWords = target => target.kind === 'catalog' || target.kind === 'namespace'
  ? `Everything beneath this ${target.kind} inherits it, unless it has its own. ` : '';

function classify(target, current, reload) {
  openDialog(`Classify ${lastWord(target)} (${target.kind})`, h('div', null,
    h('p', null, 'The classification says how sensitive the data is. Engines receive it with every load. ' + reachWords(target)
      + (target.kind === 'view' ? '' : 'A table in a regulated area stays closed until it has a classification of its own.')),
    h('p', {class: 'fine'}, RECORDED),
    dialogForm([field('Classification', select('cls', CLASSES, current || 'confidential'))], 'Set the classification', async (form, out) => {
      const value = form.elements.cls.value;
      const answer = await api.setAttribute(target, 'classification', value, 'console');
      forgetMarks(target.catalog);
      fill(out, granted(`${what(target)} is ${value}`, answer));
      reload();
    })));
}

function setOwner(target, current, reload) {
  openDialog(`Set the owner of ${lastWord(target)} (${target.kind})`, h('div', null,
    h('p', null, `The owner is the person or team answerable for this ${target.kind}. Engines receive it with every load. `
      + reachWords(target)),
    h('p', {class: 'fine'}, RECORDED),
    dialogForm([field('Owner', text('owner', 'A person or team, such as desk-a', current))], 'Set the owner', async (form, out) => {
      const value = form.elements.owner.value.trim();
      if (!value) {
        fill(out, h('p', {class: 'fine grant-hint'}, 'Name the person or team.'));
        return;
      }
      const answer = await api.setAttribute(target, 'owner', value, 'console');
      fill(out, granted(`${what(target)} is owned by ${value}`, answer));
      reload();
    })));
}

/** An ISO-8601 duration as Java writes one (PT720H, PT1H30M), in words: 30 days, 90 minutes. */
function ageWords(iso) {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(iso || '');
  if (!m) return iso;
  const seconds = (+(m[1] || 0)) * 3600 + (+(m[2] || 0)) * 60 + (+(m[3] || 0));
  if (seconds && seconds % 86400 === 0) return plural(seconds / 86400, 'day');
  if (seconds && seconds % 3600 === 0) return plural(seconds / 3600, 'hour');
  if (seconds && seconds % 60 === 0) return plural(seconds / 60, 'minute');
  return plural(seconds, 'second');
}

/** Sets a retention class, chosen from those the server is configured with (its maintenance policy names them). */
function setRetentionClass(target, current, reload) {
  const body = h('div');
  openDialog(`Set the retention class of ${lastWord(target)} (${target.kind})`, h('div', null,
    h('p', null, 'A retention class says how long snapshots must be kept: no commit and no maintenance job may remove a '
      + 'snapshot younger than its class\'s minimum age, whatever the maintenance policy asks. Engines receive it with '
      + 'every load. ' + reachWords(target)),
    h('p', {class: 'fine'}, RECORDED), body));
  load(body, async () => {
    const classes = (await api.policy(target))['retention-classes'] || [];
    if (!classes.length) {
      return h('p', {class: 'muted', 'data-none': '', title: 'nuna.governance.retention-classes'}, 'No retention classes are set '
        + 'up on this server yet. Ask a platform operator to add them.');
    }
    const options = classes.map(c => [c.name, c['minimum-snapshot-age']
      ? `${c.name} · snapshots kept at least ${ageWords(c['minimum-snapshot-age'])}` : `${c.name} · no minimum age`]);
    const chosen = classes.some(c => c.name === current) ? current : classes[0].name;
    return dialogForm([field('Retention class', select('rc', options, chosen))], 'Set the retention class', async (form, out) => {
      const value = form.elements.rc.value;
      const answer = await api.setAttribute(target, 'retention-class', value, 'console');
      fill(out, granted(`${what(target)} is in retention class ${value}`, answer));
      reload();
    });
  });
}

/**
 * Marks a catalog or namespace as a regulated area, or clears its own mark (set to false, so the clearing is recorded),
 * with the reason as its provenance, which Governance shows beside the area (Atelier, 2026-11-23; backlog 213).
 */
function setRegulated(target, marked, inherited, reload) {
  const words = marked
    ? ['Clearing the mark opens the tables beneath this ' + target.kind + ' that have no classification of their own, '
      + 'unless a level above is regulated too.']
    : ['A regulated area closes every table beneath this ' + target.kind + ' that has no classification of its own: it '
      + 'can be found and described, but not read, written or changed until someone classifies it, and it cannot be '
      + 'unregistered.', inherited ? ' A level above already marks it regulated; a mark here keeps it so if that one is cleared.' : ''];
  openDialog(marked ? `Clear the regulated mark on ${lastWord(target)}` : `Mark ${lastWord(target)} as a regulated area`, h('div', null,
    h('p', null, ...words),
    h('p', {class: 'fine'}, 'The reason is recorded with the mark, under your name, in the audit trail.'),
    dialogForm([field('Reason, as it will be recorded', text('reason', marked ? 'Compliance review: no regulated data left here'
      : 'Compliance register: holds customer personal data'))], marked ? 'Clear the mark' : 'Mark as regulated', async (form, out) => {
      const reason = form.elements.reason.value.trim();
      if (!reason) {
        fill(out, h('p', {class: 'fine grant-hint'}, marked ? 'Write the reason: the audit record carries it.'
          : 'Write the reason: the area carries it for as long as it is marked.'));
        return;
      }
      const answer = await api.setAttribute(target, 'regulated', marked ? 'false' : 'true', reason);
      forgetMarks(target.catalog);
      fill(out, granted(marked ? `${what(target)} is no longer a regulated area` : `${what(target)} is a regulated area`, answer));
      reload();
    })));
}

/**
 * Flags a table for row and column rules, or removes its flag (D20; backlog 241). The catalog refuses the removal while
 * the grant admin's rules stand on the table, which the dialog says in words.
 */
function setFlag(target, flagged, reload) {
  openDialog(flagged ? `Remove the flag on ${lastWord(target)}` : `Flag ${lastWord(target)} for row and column rules`, h('div', null,
    h('p', null, flagged
      ? 'Removing the flag lets any client that may read this table read its files again. While rules stand it is '
        + 'refused: the grant admin removes them first.'
      : 'Once flagged, only a trusted engine reads or writes its files, and the console shows no rows. Until the grant '
        + 'admin sets rules, Trino shows every row to anyone who may select.'),
    h('p', {class: 'fine'}, RECORDED),
    dialogForm(null, flagged ? 'Remove the flag' : 'Flag for row and column rules', async (form, out) => {
      let answer;
      try {
        answer = flagged ? await api.removeAttribute(target, 'row-column-policy')
          : await api.setAttribute(target, 'row-column-policy', 'true', 'console');
      } catch (error) {
        if (error.status !== 409 || error.type !== 'RowColumnRulesExistException') throw error;
        fill(out, h('div', {class: 'refusal compact', role: 'alert', 'data-rules-stand': ''},
          h('p', {class: 'refusal-h'}, icon('x'), 'Rules stand: the grant admin removes them first.')));
        return;
      }
      fill(out, granted(flagged ? `${what(target)} is no longer flagged` : `${what(target)} is flagged for row and column rules`, answer));
      reload();
    })));
}

export function placeHold(target, reload) {
  openDialog(`Place a hold on ${lastWord(target)}`, h('div', null,
    h('p', null, target.kind === 'view'
      ? 'A legal hold stops this view being dropped or purged. Its definition can still be replaced. Only a steward can release it.'
      : 'A legal hold stops snapshot expiry, orphan removal, drops and purges on what it covers. Compaction still '
        + 'runs. Only a steward can release it.'),
    dialogForm([field('Reason, as it will be recorded', text('reason', 'Matter 2026-1xx: what the hold is for'))], 'Place the hold',
      async (form, out) => {
        const reason = form.elements.reason.value.trim();
        if (!reason) {
          fill(out, h('p', {class: 'fine grant-hint'}, 'Write the reason: the hold carries it for as long as it stands.'));
          return;
        }
        const answer = await api.placeHold(target, reason);
        forgetMarks(target.catalog);
        fill(out, granted(`Held: ${what(target)}`, answer));
        reload();
      })));
}

/** Releases a hold, after a word in a dialog, on the level it was placed on. */
export function releaseHold(level, hold, reload) {
  openDialog('Release a hold', h('div', null,
    h('p', null, 'Releasing ', h('b', null, hold.reason), ' on ', code(what(level)), level.kind === 'view'
      ? ' lets the view be dropped and purged again. The hold and its release stay in the audit trail.'
      : ' lets the worker expire snapshots and remove files there again. The hold and its release stay in the audit trail.'),
    dialogForm(null, 'Release the hold', async (form, out) => {
      const answer = await api.releaseHold(level, hold.id);
      forgetMarks(level.catalog);
      fill(out, granted('Released', answer));
      reload();
    })));
}

/** Sets or lifts a DR freeze, after a word in a dialog: platform operators only. */
export function drFreeze(target, frozen, reload) {
  openDialog(frozen ? `Lift the DR freeze on ${lastWord(target)}` : `Freeze ${lastWord(target)} for DR`, h('div', null,
    h('p', null, frozen ? 'Lifting the freeze lets commits and cleanup run again beneath this namespace.'
      : 'A DR freeze stops every commit and cleanup beneath this namespace until it is lifted. Platform operators set it.'),
    dialogForm(frozen ? null : [field('Reason, as it will be recorded', text('reason', 'DR reconcile in progress'))],
      frozen ? 'Lift the freeze' : 'Freeze', async (form, out) => {
        const reason = frozen ? null : form.elements.reason.value.trim();
        if (!frozen && !reason) {
          fill(out, h('p', {class: 'fine grant-hint'}, 'Write the reason.'));
          return;
        }
        const answer = frozen ? await api.unfreeze(target) : await api.freeze(target, reason);
        forgetMarks(target.catalog);
        fill(out, granted(frozen ? 'Freeze lifted' : `Frozen: ${what(target)}`, answer));
        reload();
      })));
}

// ---- export ----

/**
 * Export: the object's pointer, its metadata and the commit journal, as one JSON file; needs select. Read through a catalog
 * ref, it is the ref's: its metadata and the journal as it reads it, in a file named {path}@{ref} (item 172).
 * `objectId` answers the object's id as the page reads it: through a ref, the one the ref holds under that name, which
 * main may have dropped, or replaced with another of the same name, since.
 */
export async function exportDialog(target, objectId) {
  const out = h('div', {class: 'dlg-out'});
  const at = target.ref ? '@' + target.ref : '';
  const dlg = openDialog(`Export ${lastWord(target)}${at}`, h('div', null,
    h('p', null, (target.ref ? `The metadata, every snapshot and snapshot ref, and the commit journal of this ${target.kind} as ${at} reads it,`
      : 'The current metadata, every snapshot and snapshot ref, and the catalog\'s commit journal for this ' + target.kind + ',')
      + ' as one JSON file. The data files stay where they are; a restore registers it again from this file.'), out), {wide: true});
  await load(out, async () => {
    const x = await api.exportObject(target.catalog, await objectId(), target.ref);
    const json = api.toJson(x, 2);
    const url = URL.createObjectURL(new Blob([json], {type: 'application/json'}));
    dlg.addEventListener('close', () => URL.revokeObjectURL(url));
    const preview = Object.assign({}, x, {metadata: x.metadata ? '… the table\'s metadata, in the file' : x.metadata,
      journal: Array.isArray(x.journal) && x.journal.length > 3 ? [...x.journal.slice(0, 3), `… ${x.journal.length - 3} more in the file`] : x.journal});
    return [jsonPre(preview), h('div', {class: 'dlg-acts'},
      h('a', {class: 'btn sm primary', href: url, download: `${target.catalog}.${dotted(target)}${at}.export.json`}, icon('down'), 'Download the file'),
      copyButton(json, 'Copy the JSON'))];
  });
}

// ---- grants ----

function onWords(securable) {
  if (!securable) return '';
  const path = [...(securable.namespace || []), ...(securable.name ? [securable.name] : [])].join('.');
  return [securable.type, ' ', path ? code(path) : muted('whole catalog'),
    securable.state && securable.state !== 'active' ? [' ', badge(securable.state)] : null];
}

function specOf(securable) {
  const spec = {type: securable.type};
  if (securable.type !== 'catalog') spec.namespace = securable.namespace;
  if (securable.name) spec.name = securable.name;
  return spec;
}

/** A grant's privilege, and the branch it holds on when it names one (backlog 113); a grant without one holds on every ref. */
export const privilegeCell = g => h('td', {class: 'name'}, g.privilege, g.branch ? muted(` on @${g.branch} only`) : null);

/** A revoke, after a word in a dialog: what goes, from whom. */
export function revokeDialog(catalog, g, after) {
  const only = g.branch ? ` on @${g.branch}` : '';
  openDialog(`Revoke ${g.privilege}${only} from ${g.group}`, h('div', null,
    h('p', null, `${g.group} loses ${g.privilege}${only} on `, onWords(g.securable), '. The grant and its revocation stay in the audit trail.'),
    dialogForm(null, 'Revoke', async (form, out) => {
      const answer = await api.revoke(catalog, specOf(g.securable), g.group, g.privilege, g.branch);
      fill(out, granted(`Revoked ${g.privilege} from ${g.group}.`, answer));
      after(answer);
    })));
}

/** Where a grant that reaches `target` was made: here, on the catalog, or on a namespace above. */
function grantedOn(target, securable) {
  const path = [...(securable.namespace || []), ...(securable.name ? [securable.name] : [])];
  const mine = [...target.namespace, ...(target.name ? [target.name] : [])];
  if (securable.type === target.kind && path.join('\u001f') === mine.join('\u001f')) return ['here', h('b', null, 'here')];
  if (securable.type === 'catalog') return ['catalog', `catalog ${target.catalog}`];
  return [securable.type, [securable.type + ' ', h('a', {href: objectHref({catalog: target.catalog, kind: securable.type,
    namespace: securable.namespace || [], name: securable.name}, 'grants')}, path.join('.'))]];
}

const READS = ['select', 'write', 'modify'];
const WRITES = ['write', 'modify'];

/**
 * The grants tab of a catalog object: every grant that reaches it from the catalog down, who may read it, change
 * its data and manage its grants, a grant form, and the caller's own groups' grants.
 */
// ---- access: what the caller may do here first, then the object's governance and its grants (backlog 199) ----

const RUNGS = ['describe', 'select', 'write', 'modify'];
const RUNG_WORDS = {
  describe: 'See it, but not read its data',
  select: 'Read its data',
  write: 'Read its data and write rows to it',
  modify: 'Read and write its data, and change it: its schema, properties and maintenance',
};

/**
 * What the caller's groups' grants allow on the object, in words (Atelier, 2026-11-23: Access leads with it); on a
 * table closed until classified, nothing until it is (backlog 324).
 */
async function youMay(target, closed = false) {
  const held = await heldHere(target);
  const privileges = new Set(held.map(g => g.privilege));
  const rung = RUNGS.filter(x => privileges.has(x)).pop();
  const lines = [];
  if (rung) lines.push(RUNG_WORDS[rung]);
  if (privileges.has('create') && target.kind === 'namespace') lines.push('Create tables, views and namespaces in it');
  if (privileges.has('manage-tags')) lines.push('Set its labels and holds');
  if (privileges.has('manage-grants') || may(target.catalog, 'manage-grants')) lines.push('Grant on it, and see who holds what');
  const groups = [...new Set(held.map(g => g.group))].sort();
  return h('div', {class: 'panel', id: 'you-may'}, h('h2', null, 'What you may do ', h('span', {class: 'meta'}, `on this ${target.kind}`)),
    closed && lines.length ? h('p', {'data-closed': ''}, h('b', null, 'Nothing until it is classified; then:')) : null,
    lines.length ? h('ul', {class: 'may-list'}, lines.map(l => h('li', null, icon('check'), l + '.')))
      : h('p', {class: 'muted'}, `Your groups hold no grant that reaches this ${target.kind}.`),
    h('p', {class: 'fine'}, groups.length ? `Through your groups' grants: ${groups.join(', ')}. ` : '',
      'The catalog decides each request; this is what your grants allow.'));
}

/**
 * A table's, view's or namespace's Access tab: Grant access at its top for those who manage the catalog's grants
 * (backlog 248), what the caller may do, a reader's view of a flagged table, then its governance, a table's row
 * filters and column masks (backlog 243), then its grants. A table closed until classified (`closed`, its load's
 * answer) shows the closed notice in place of the reader's view, and a classification set here reloads through
 * `reopen`, which reads the table again (backlog 324).
 */
export function accessTab(target, reload, {closed = Promise.resolve(false), reopen = reload} = {}) {
  const you = h('div');
  const gov = h('div');
  const shut = h('div');
  const view = h('div');
  load(you, async () => youMay(target, await closed));
  load(gov, async () => governanceTab(target, await closed ? reopen : reload));
  closed.then(async isClosed => {
    if (isClosed) fill(shut, await closedNotice(target, reopen));
    else fill(view, yourView(target, heldHere(target)));
  });
  // Which branches and tags keep a table or view, after its governance (item 279): from item 170's read alone.
  const kept = target.kind === 'table' || target.kind === 'view' ? h('div') : null;
  if (kept) load(kept, () => keptPanel(target));
  const [grants, listAgain] = grantsPart(target);
  // Once the dialog closes after a grant, the grant's row is brought to the middle of the view, once the list has it.
  let listed = Promise.resolve(null);
  const showMade = () => listed.then(reach => {
    const row = reach && reach.querySelector('tr.is-new');
    if (row && row.isConnected) row.scrollIntoView({block: 'center',
      behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'});
  });
  const acts = may(target.catalog, 'manage-grants') ? h('div', {class: 'refusal-acts access-acts'},
    h('button', {class: 'btn sm primary', type: 'button', 'data-act': 'grant-access', onclick: () => grantAccess(target,
      {after: made => { listed = listAgain(made); }, closed: showMade})}, icon('key'), 'Grant access')) : null;
  return h('div', {class: 'stack', id: 'access-tab'}, acts, shut, you, view, gov, kept, rulesPanel(target), grants);
}

/** The Access tab's grants, and what lists them again once the dialog makes one, marking it. */
function grantsPart(target) {
  const reach = h('div');
  const who = h('div');
  const mine = h('div');
  // Who holds what here, and granting, are for those who manage the catalog's grants (item 184); anyone else sees
  // their own groups' grants, which the catalog shows them, and is never refused for browsing.
  const manages = may(target.catalog, 'manage-grants');
  const ownGrants = () => load(mine, async () => {
    const list = await api.grantsOfGroups(target.catalog, []);
    return list.length ? tbl('stack-line', ['Group', 'Privilege', 'On', 'Granted'], list.map(g => h('tr', null,
      h('td', {class: 'name'}, g.group), privilegeCell(g), h('td', null, onWords(g.securable)),
      h('td', null, h('span', {class: 'tnum'}, at(g['granted-at'])), ' ', muted(g['granted-by'])))))
      : h('p', {class: 'muted'}, 'Your groups hold no grant in this catalog.');
  });
  if (!manages) {
    ownGrants();
    return [h('div', {class: 'stack grants-here', 'data-grants': 'own'}, panel('Your groups\' grants', `in ${target.catalog}`, mine,
      h('p', {class: 'fine'}, `Who else holds what on this ${target.kind}, and granting, are open to those who manage `
        + `${target.catalog}'s grants.`))), () => {}];
  }
  /** Lists the grants again; `made`, a grant the dialog just made here, is marked. Answers the reach list once drawn. */
  const refresh = made => {
    const effective = api.effectiveGrants(target);
    const drawn = load(reach, async () => {
      const list = (await effective).grants || [];
      return panel(`Grants that reach this ${target.kind}`, `${plural(list.length, 'grant')} · from the catalog down`,
        list.length ? tbl('stack-line grants-reach', ['Group', 'Privilege', 'Granted on', 'Granted', ''], list.map(g => {
          const [on, words] = grantedOn(target, g.securable);
          const isMade = !!made && on === 'here' && g.group === made.group && g.privilege === made.privilege && !g.branch;
          return h('tr', {'data-group': g.group, 'data-privilege': g.privilege, 'data-on': on, 'data-branch': g.branch || null,
            class: isMade ? 'is-new' : null},
            h('td', {class: 'name'}, g.group), privilegeCell(g), h('td', null, words),
            h('td', null, h('span', {class: 'tnum'}, at(g['granted-at'])), ' ', muted(g['granted-by'])),
            h('td', null, h('button', {class: 'btn sm ghost', type: 'button', onclick: () => revokeDialog(target.catalog, g, () => refresh())}, 'Revoke')));
        })) : h('p', {class: 'muted'}, `No grant reaches this ${target.kind}.`),
        h('p', {class: 'fine'}, 'A grant on the catalog or a namespace reaches everything beneath it. The ladder is describe, '
          + 'select, write, modify: each gives the ones before it. Revoke takes a grant away where it was made.'));
    });
    load(who, async () => {
      const answer = await effective;
      const list = answer.grants || [];
      const groups = ps => [...new Set(list.filter(g => ps.includes(g.privilege)).map(g => g.group))].sort();
      const names = ps => groups(ps).length ? groups(ps).join(', ') : muted('no group');
      const admins = answer['grant-admin-groups'] || [];
      const managers = groups(['manage-grants']);
      const manage = [managers.join(', '), admins.length ? `${admins.join(', ')}, on every catalog` : ''].filter(Boolean).join('; ');
      return panel('Who may', null, facts([
        ['Read', names(READS)],
        ['Change data', names(WRITES)],
        ['Manage tags', names(['manage-tags'])],
        ['Manage grants', manage || muted('no group')],
      ], 'who-may'));
    });
    ownGrants();
    return drawn;
  };
  refresh();
  return [h('div', {class: 'grid-21 grants-here'}, reach,
    h('div', {class: 'stack'}, who,
      panel('Your groups\' grants', `in ${target.catalog}`, mine))), refresh];
}

// ---- the audit trail ----

/** Iceberg's update actions in words (Atelier's words table, T5); an action not listed shows as it is. */
const ACTION_WORDS = {
  'add-snapshot': 'snapshot added', 'remove-snapshots': 'snapshots expired',
  'set-snapshot-ref': 'ref moved', 'remove-snapshot-ref': 'ref removed',
  'add-schema': 'schema added', 'set-current-schema': 'schema changed', 'remove-schemas': 'old schemas removed',
  'add-spec': 'partition spec added', 'set-default-spec': 'partitioning changed', 'remove-partition-specs': 'old specs removed',
  'add-sort-order': 'sort order added', 'set-default-sort-order': 'sort order changed',
  'set-properties': 'properties set', 'remove-properties': 'properties removed',
  'set-location': 'location set', 'upgrade-format-version': 'format upgraded', 'assign-uuid': 'id assigned',
  'set-statistics': 'statistics updated', 'remove-statistics': 'statistics updated',
  'set-partition-statistics': 'statistics updated', 'remove-partition-statistics': 'statistics updated',
};

/** A commit's update actions in words, each once, in order; beside a snapshot added, main moving to it goes unsaid. */
export function actionWords(updates) {
  const actions = (updates || []).map(u => u.action);
  const added = actions.includes('add-snapshot');
  return [...new Set(actions.filter(a => !(added && a === 'set-snapshot-ref')).map(a => ACTION_WORDS[a] || a))];
}

/** A record's branch field: main, or the catalog ref it went through, as @name (item 109). */
const onRef = branch => !branch || branch === 'main' ? 'main' : '@' + branch;

/** A record's words; given its catalog, a ref record also links to its ref while that ref is live (item 109). */
export function describe(type, d, catalog) {
  switch (type) {
    case 'commit': return `version ${d.before ? d.before.version : '–'} → ${d.after ? d.after.version : '–'} on ${onRef(d.branch)}; `
      + (Array.isArray(d.updates) ? actionWords(d.updates).join(', ') : '');
    case 'read': return `loaded version ${d.served ? d.served.version : '–'} of ${onRef(d.branch)}`;
    case 'ref': return catalog ? [refWords(d), liveRefLink(catalog, d)] : refWords(d);
    case 'denial': return `refused ${d.operation}${d.privilege ? ' (needs ' + d.privilege + ')' : ''}: ${d.reason}`;
    case 'grant': return `${String(d.action).toLowerCase()} ${d.privilege} to ${d.group}`;
    case 'governance': return `${String(d.action).toLowerCase()} ${d.attribute}${d.value != null ? ' = ' + d.value : ''}`;
    case 'hold': return `hold ${String(d.action).toLowerCase()}: ${d.reason}`;
    case 'credential-issue': return `${d.access} credentials for ${d.locationPrefix}, until ${when(d.expiresAt)}`;
    case 'drop': return `dropped; purge after ${when(d.purgeAfter)}`;
    case 'undrop': return 'undropped';
    case 'purge': return 'purged';
    case 'rename': return `renamed from ${(d.fromPath || []).join('.')}`;
    case 'namespace': return `namespace ${String(d.action).toLowerCase()}`;
    case 'audit-read': return `read ${d.returned} audit records`;
    case 'export': return `exported ${d.returned} ${d.what === 'grants' ? 'grants' : 'row and column rules'}`;
    // Who and which table are on the row already; the sentence names the group, as a grant's does (Atelier, 2026-11-24).
    case 'row-column-rule': return `${String(d.action).toUpperCase() === 'REMOVED' ? 'removed' : 'set'} the rule for ${d.group}`;
    default: return '';
  }
}

/** Every record type that changes something, for the Audit page's Changes figure. */
export const CHANGES = ['commit', 'governance', 'hold', 'grant', 'row-column-rule', 'label', 'drop', 'undrop', 'rename', 'namespace', 'ref', 'purge',
  'purge-claim', 'file-deletion', 'maintenance-exemption', 'unregister', 'storage-setup', 'engine', 'break-glass'];

/**
 * The kinds audit records are filtered by (Atelier's review, F7; backlog 187), each as [key, the chip's word, a
 * record's badge, its record types], and the one map every audit view takes its words from, so a badge, a chip and a
 * By kind row of one kind say the same and their counts add up (Atelier's re-walk after phase 2, backlog 214).
 * Governance holds a hold and every governance record: a DR freeze (D37), a label, a maintenance policy.
 */
export const AUDIT_KINDS = [
  ['all', 'All', null, null],
  ['grant', 'Grants', 'Grant', ['grant']],
  ['rule', 'Row and column rules', 'Rule', ['row-column-rule']],
  ['hold', 'Governance', 'Governance', ['hold', 'governance']],
  ['ref', 'Branches and tags', 'Branch or tag', ['ref']],
  ['commit', 'Commits', 'Commit', ['commit']],
  ['change', 'Other changes', 'Change', CHANGES.filter(t => !['grant', 'row-column-rule', 'hold', 'governance', 'ref', 'commit'].includes(t))],
  ['read', 'Reads', 'Read', ['read', 'audit-read', 'export']],
  ['cred', 'Credentials', 'Credential', ['credential-issue']],
  ['refusal', 'Refusals', 'Refusal', ['denial', 'auth-failure']],
];

/** A record type without its prefix: `commit` for `nuna.catalog.commit`. */
export const shortType = type => String(type || '').replace(/^nuna\.catalog\./, '');

/** The kind a record type belongs to, null for a type no kind names. */
export const auditKindOf = type => (AUDIT_KINDS.find(([k, , , types]) => k !== 'all' && types.includes(shortType(type))) || [null])[0];

/** A record's badge: its kind's word in the UI font, a ref's own kind for a ref record, the full type its title. */
export function auditBadge(type, data) {
  const kind = AUDIT_KINDS.find(([k]) => k === auditKindOf(type));
  const refKind = String((data || {}).kind || '').toLowerCase();
  const word = shortType(type) === 'ref' && (refKind === 'branch' || refKind === 'tag') ? (refKind === 'tag' ? 'Tag' : 'Branch')
    : kind ? kind[2] : 'Other';
  return h('span', {class: 'badge', title: type || ''}, word);
}

const KIND_OF = type => auditKindOf(type) || 'other';
const KINDS = [...AUDIT_KINDS.map(([k, label]) => [k, label]), ['other', 'Other']];

function feedRow(r) {
  const type = String(r.type || '').replace(/^nuna\.catalog\./, '');
  const d = r.data || {};
  const actor = d.actor || {};
  const btn = h('button', {class: 'btn sm ghost', type: 'button', 'aria-expanded': 'false',
    'aria-label': `Record of ${when(r.time)}, ${actor.principal || 'unknown'}, ${type}`}, 'Show record');
  const row = h('div', {class: 'feed-row' + (type === 'denial' ? ' is-refused' : ''), 'data-type': type, 'data-kind': KIND_OF(type),
    'data-who': actor.principal || ''},
  h('span', {class: 't'}, when(r.time)),
  h('span', {class: 'w'}, h('b', null, actor.principal || '–'), actor.endUser ? [' for ', h('b', null, actor.endUser)] : null,
    h('br'), h('span', {class: 'via'}, actor.engine ? 'via ' + actor.engine : '')),
  h('div', null, auditBadge(r.type, d), h('p', {class: 'what'}, describe(type, d))), btn);
  btn.addEventListener('click', () => {
    const open = btn.getAttribute('aria-expanded') === 'true';
    btn.setAttribute('aria-expanded', String(!open));
    btn.textContent = open ? 'Show record' : 'Hide record';
    if (open) $$('.rec', row).forEach(x => x.remove());
    else row.append(h('div', {class: 'rec'}, facts([['Record id', [code(r.id || ''), ' ', r.id ? copyButton(r.id) : null]],
      ['Subject', code(r.subject || '')]], 'rec-facts'), jsonPre(r)));
  });
  return row;
}

/**
 * An object's recent audit records: from the audit table where the server has one, which holds every record the log
 * does, otherwise from the audit topic (backlog 197). Needs manage-grants on it.
 */
export function auditTab(target, {title} = {}) {
  const feed = h('div', {class: 'feed', 'data-feed': ''});
  const status = h('p', {class: 'fine feed-status', role: 'status'});
  const windowBox = h('div');
  const chipBox = h('div', {class: 'chips', role: 'group', 'aria-label': 'Filter by kind'});
  const who = h('select', {name: 'who', 'aria-label': 'Who'});
  const limit = h('select', {name: 'limit', 'aria-label': 'How many'}, [25, 50, 100, 200].map(n => h('option', {value: n, selected: n === 50}, 'latest ' + n)));
  let kind = 'all';
  const apply = () => {
    let shown = 0;
    for (const row of $$('.feed-row', feed)) {
      row.hidden = !((kind === 'all' || row.dataset.kind === kind) && (!who.value || row.dataset.who === who.value));
      if (!row.hidden) shown++;
    }
    status.textContent = `${plural(shown, 'record')} shown, newest first. Reading the trail is itself audited.`;
  };
  const run = () => load(feed, async () => {
    const asked = Number(limit.value);
    const body = await api.recentAudit(target, asked);
    const records = body.records || [];
    const fromTable = body.source === 'table';
    const w = body.window || {};
    const t = body.table || {};
    const counts = {};
    for (const r of records) {
      const k = KIND_OF(String(r.type || '').replace(/^nuna\.catalog\./, ''));
      counts[k] = (counts[k] || 0) + 1;
    }
    fill(chipBox, KINDS.filter(([k]) => k === 'all' || counts[k]).map(([k, label]) => h('button', {class: 'chip', type: 'button',
      'data-f': k, 'aria-pressed': String(k === kind), onclick: e => {
        kind = k;
        for (const c of $$('.chip', chipBox)) c.setAttribute('aria-pressed', String(c === e.currentTarget));
        apply();
      }}, label + ' ', h('span', {class: 'n'}, String(k === 'all' ? records.length : counts[k])))));
    const people = [...new Set(records.map(r => ((r.data || {}).actor || {}).principal).filter(Boolean))].sort();
    fill(who, h('option', {value: ''}, 'Anyone'), people.map(p => h('option', {value: p}, p)));
    fill(windowBox, fromTable ? panel('How far back this goes', null, facts([
      // Not complete: a full page may have older records behind it; a shorter one means the reader's scan budget ran
      // out, which a larger page would not help.
      ['Complete', body.complete ? 'Yes: every record about it is shown, up to about a minute ago.'
        : [h('b', null, 'No.'), records.length < asked
          ? ' Only the newest part of the audit table was searched: search the SIEM for the full trail.'
          : asked < 200 ? ' There may be older records about it: show the latest 200, or search the SIEM for the full trail.'
            : ' There may be older records about it: search the SIEM for the full trail.']],
    ]), techDetails(facts([
      ['Table', code(t.name || '')],
      ['Last commit', t['committed-at'] ? at(t['committed-at']) : 'none yet'],
    ]), h('p', {class: 'fine window'}, `Read from ${t.name} as of its last commit: its writer commits about once a minute.`)))
    : panel('How far back this goes', null, facts([
      ['Complete', w.complete ? 'Yes: every record still kept is shown.'
        : [h('b', null, 'No.'), ' Older records may exist beyond the scanned window: search the SIEM for the full trail.']],
    ]), techDetails(facts([
      ['Topic', [code(w.topic || ''), `, partition ${w.partition}`]],
      ['Offsets', [code(String(w['from-offset'])), ' to ', code(String(w['to-offset']))]],
      ['Scan limit', String(w['scan-limit'] ?? '')],
    ]), h('p', {class: 'fine window'}, `Read from ${w.topic}, partition ${w.partition}, offsets ${w['from-offset']} to ${w['to-offset']}.`))));
    setTimeout(apply);
    return records.length ? records.map(feedRow)
      : h('p', {class: 'muted feed-none'}, !fromTable ? `No audit record about this ${target.kind} in the scanned window.`
        : body.complete ? `No audit record about this ${target.kind}, up to about a minute ago.`
          : `No audit record about this ${target.kind} in the newest part of the audit table.`);
  });
  who.addEventListener('change', apply);
  limit.addEventListener('change', run);
  run();
  return h('div', {class: 'grid-21', id: 'audit'},
    h('div', {class: 'panel'}, h('h2', null, title || 'Audit trail', ' ', h('span', {class: 'meta'}, 'newest first')),
      h('div', {class: 'feed-box'},
        h('div', {class: 'feed-filters'},
          h('label', {class: 'ff'}, 'Kind', chipBox),
          h('label', {class: 'ff'}, 'Who', h('span', {class: 'field select'}, who)),
          h('label', {class: 'ff'}, 'How many', h('span', {class: 'field select'}, limit))),
        h('div', {class: 'refusal-acts'}, h('button', {class: 'btn sm', type: 'button', onclick: run}, 'Refresh')),
        status, feed)),
    h('div', {class: 'stack'}, windowBox,
      panel('Archive', null, h('p', {class: 'fine'}, 'Changes reach a hash-chained archive kept at least seven years. '
        + 'Each record\'s place in the chain is on the Audit page.'))));
}

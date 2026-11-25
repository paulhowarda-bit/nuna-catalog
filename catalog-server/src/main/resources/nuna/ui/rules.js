// Row filters and column masks on a flagged table (D20, backlog 115), in Atelier's design (2026-11-24; backlog 243):
// the Access tab's panel between Governance and Grants, where the grant admin lists, adds, edits and removes each
// group's rule and a steward sees how many rules there are and for which groups; and a reader's own view of the table
// under What you may do. No filter or mask expression reaches anyone but the grant admin: the server decides that,
// and this module draws only what each route answered.

import * as api from './api.js';
import {claims} from './auth.js';
import {may} from './access.js';
import {at, code, dialogForm, facts, failure, fill, granted, h, icon, load, muted, openDialog, panel, plural, select, tbl, text} from './dom.js';
import {flaggedHere} from './panels.js';

/** The longest filter or mask the catalog keeps (RuleRoutes.MAX_EXPRESSION). */
const MAX_EXPRESSION = 4000;

const TITLE = 'Row filters and column masks';
const NOT_FLAGGED = 'Rules need the flag. A steward sets it, so only trusted engines read the files.';
const NO_RULES = 'Flagged. No rules yet: Trino shows every row to anyone who may select; other tools get no files.';
const FIRST_RULE = 'Groups without a rule will see no rows.';
const OWN_GROUP = 'Another grant admin sets this group\'s rule.';
const STEWARD_NOTE = 'The grant admin sets each group\'s rules.';
const NULL_MASK = 'NULL';
const STARS_MASK = '\'***\'';
const CUSTOM = 'custom';
const MASKS = [[NULL_MASK, 'NULL'], [STARS_MASK, '\'***\''], [CUSTOM, 'A custom expression']];

/** The caller's groups, from their token. */
const ownGroups = () => new Set(((claims() || {}).groups) || []);

/** A filter that lets every row through: none, or the `true` the catalog asks for when a rule only masks. */
const everyRow = filter => !filter || filter.trim().toLowerCase() === 'true';

/** A refusal in Atelier's words, in place of the catalog's own. */
const refusalWords = (key, words) => h('div', {class: 'refusal compact', role: 'alert', 'data-refused': key},
  h('p', {class: 'refusal-h'}, icon('x'), words));

// ---- the panel ----

/**
 * The Access tab's panel on a table, for the grant admin and the steward; nothing for anyone else, who never manage
 * rules and are never asked about them (item 184).
 */
export function rulesPanel(target) {
  if (target.kind !== 'table' || !(may(target.catalog, 'manage-grants') || may(target.catalog, 'manage-tags'))) return null;
  const box = h('div', {id: 'row-column-rules'});
  const show = () => load(box, () => rulesBody(target, show));
  show();
  return box;
}

async function rulesBody(target, show) {
  const g = await api.governance(target);
  if (!flaggedHere(g)) return panel(TITLE, null, h('p', {class: 'muted', 'data-rules': 'not-flagged'}, NOT_FLAGGED));
  if (may(target.catalog, 'manage-grants')) {
    const rules = (await api.rules(target)).rules || [];
    return grantAdminPanel(target, rules, show);
  }
  const counts = g['row-column-rules'];
  if (!counts) return null;
  return panel(TITLE, null, counts.count
    ? facts([['Rules', String(counts.count)], ['Groups', (counts.groups || []).join(', ')]], 'rule-counts')
    : h('p', {class: 'muted', 'data-rules': 'none'}, NO_RULES),
  h('p', {class: 'fine'}, STEWARD_NOTE));
}

function grantAdminPanel(target, rules, show) {
  const mine = ownGroups();
  const rows = rules.map(rule => h('tr', {'data-group': rule.group},
    h('td', {class: 'name'}, rule.group),
    h('td', {class: 'wrap', 'data-rows': ''}, everyRow(rule['row-filter']) ? 'Every row' : ['Only rows where ', code(rule['row-filter'])]),
    h('td', {class: 'wrap', 'data-masks': ''}, maskWords(rule['column-masks'])),
    h('td', null, h('span', {class: 'tnum'}, at(rule['set-at'])), ' ', muted(rule['set-by'])),
    h('td', null, mine.has(rule.group) ? h('span', {class: 'fine', 'data-own': ''}, OWN_GROUP) : h('span', {class: 'refusal-acts'},
      h('button', {class: 'btn sm ghost', type: 'button', 'data-act': 'edit-rule', onclick: () => ruleDialog(target, rule, rules, show)}, 'Edit'),
      h('button', {class: 'btn sm ghost', type: 'button', 'data-act': 'remove-rule', onclick: () => removeDialog(target, rule, rules, show)}, 'Remove')))));
  return panel(TITLE, rules.length ? plural(rules.length, 'rule') : null,
    rules.length ? tbl('stack-line rules', ['Group', 'Rows', 'Masked columns', 'Set by, when', ''], rows)
      : h('p', {class: 'muted', 'data-rules': 'none'}, NO_RULES),
    h('div', {class: 'refusal-acts'}, h('button', {class: 'btn sm', type: 'button', 'data-act': 'add-rule',
      onclick: () => ruleDialog(target, null, rules, show)}, 'Add a rule for a group')));
}

/** Each masked column with its mask, one to a line. */
function maskWords(masks) {
  const entries = Object.entries(masks || {});
  if (!entries.length) return muted('–');
  return entries.map(([column, mask], i) => [i ? h('br') : null, code(column), ' ', muted(mask)]);
}

// ---- the dialog ----

/** Adds a rule for a group, or edits one with its group fixed; the columns come from the table's schema (backlog 242). */
function ruleDialog(target, rule, rules, after) {
  const body = h('div');
  openDialog(rule ? `Edit the rule for ${rule.group}` : 'Add a rule for a group', body, {wide: true});
  load(body, async () => {
    const schema = await api.tableSchema(target);
    const columns = ((schema.schema || {}).fields || []).map(f => f.name);
    return ruleForm(target, rule, rules, columns, after);
  });
}

function ruleForm(target, rule, rules, columns, after) {
  const filter = rule ? rule['row-filter'] : null;
  const only = !!rule && !everyRow(filter);
  const radio = (value, label, checked) => h('label', null,
    h('input', {type: 'radio', name: 'rows', value, checked}), h('span', null, label));
  const predicate = h('textarea', {name: 'filter', class: 'expr', rows: 3, 'aria-label': 'Only rows where', spellcheck: 'false',
    placeholder: 'desk = \'EQ\'', disabled: !only}, only ? filter : '');
  const lines = h('div', {class: 'mask-lines'});
  const addLine = (column, mask) => lines.append(maskLine(columns, column, mask));
  for (const [column, mask] of Object.entries(rule ? rule['column-masks'] || {} : {})) addLine(column, mask);
  const group = rule
    ? text('group', null, rule.group, {readonly: true, 'aria-label': 'Group'})
    : text('group', 'A group, such as demo-analysts', null, {'aria-label': 'Group'});
  const form = dialogForm([
    !rule && !rules.length ? h('p', {class: 'fine', 'data-first-rule': ''}, FIRST_RULE) : null,
    h('label', {class: 'dlg-f'}, h('span', null, 'Group'), group),
    h('fieldset', {class: 'dlg-set rows-choice'}, h('legend', null, 'Rows'),
      radio('every', 'Every row', !only), radio('only', 'Only rows where', only), predicate),
    h('fieldset', {class: 'dlg-set'}, h('legend', null, 'Masked columns'), lines,
      h('div', {class: 'refusal-acts'}, h('button', {class: 'btn sm', type: 'button', 'data-act': 'add-mask',
        onclick: () => addLine('', NULL_MASK)}, 'Mask a column'))),
  ], 'Save the rule', async (f, out) => {
    const chosen = {
      group: f.elements.group.value.trim(),
      only: f.querySelector('input[name="rows"]:checked').value === 'only',
      filter: predicate.value.trim(),
      masks: [...lines.children].map(line => ({column: line.querySelector('select[name="column"]').value,
        mask: line.querySelector('select[name="mask"]').value, custom: line.querySelector('input[name="custom"]').value.trim()})),
    };
    const problems = check(chosen, columns);
    if (problems.length) {
      fill(out, h('div', {class: 'fine grant-hint', 'data-hints': ''}, problems.map(p => h('p', null, p))));
      return;
    }
    const body = {group: chosen.group};
    const masks = Object.fromEntries(chosen.masks.map(m => [m.column, m.mask === CUSTOM ? m.custom : m.mask]));
    if (chosen.only) body['row-filter'] = chosen.filter;
    else if (!chosen.masks.length) body['row-filter'] = 'true';
    if (chosen.masks.length) body['column-masks'] = masks;
    let answer;
    try {
      answer = await api.setRule(target, body);
    } catch (error) {
      if (error.status === 409 && error.type === 'NotFlaggedException') {
        fill(out, refusalWords('not-flagged', NOT_FLAGGED));
        return;
      }
      if (error.status === 403 && /group they belong to/.test(error.message || '')) {
        fill(out, refusalWords('own-group', `You belong to ${chosen.group}. ${OWN_GROUP}`));
        return;
      }
      throw error;
    }
    fill(out, granted(`Saved the rule for ${chosen.group}`, answer));
    after();
  });
  form.addEventListener('change', e => {
    if (e.target.name === 'rows') predicate.disabled = e.target.value !== 'only';
  });
  return form;
}

/** One masked column: the column, picked from the schema, and its mask: NULL, '***' or an expression of one's own. */
function maskLine(columns, column, mask) {
  const known = !column || columns.includes(column);
  const kind = mask === NULL_MASK || mask === STARS_MASK ? mask : mask ? CUSTOM : NULL_MASK;
  const custom = h('span', {class: 'field mono custom', hidden: kind !== CUSTOM},
    h('input', {name: 'custom', type: 'text', placeholder: 'An SQL expression, such as substr(trader, 1, 1)', 'aria-label': 'Expression',
      autocomplete: 'off', spellcheck: 'false', value: kind === CUSTOM ? mask : null}));
  const options = [['', 'Choose a column'], ...columns, ...(known ? [] : [column])];
  const line = h('div', {class: 'mask-line', 'data-mask-line': ''},
    select('column', options, column || '', {'aria-label': 'Column'}),
    select('mask', MASKS, kind, {'aria-label': 'Mask'}),
    h('button', {class: 'btn sm ghost', type: 'button', 'aria-label': 'Remove this column', onclick: () => line.remove()}, icon('x')),
    custom);
  line.querySelector('select[name="mask"]').addEventListener('change', e => { custom.hidden = e.target.value !== CUSTOM; });
  return line;
}

/** What the form lacks before it is sent: the fields, the 4,000-character limit, and each column in the schema. */
function check(chosen, columns) {
  const problems = [];
  const long = x => x.length > MAX_EXPRESSION;
  if (!chosen.group) problems.push('Name the group.');
  if (chosen.only && !chosen.filter) problems.push('Write which rows the group sees.');
  if (chosen.only && long(chosen.filter)) problems.push('The row filter is longer than 4,000 characters.');
  const seen = new Set();
  for (const m of chosen.masks) {
    if (!m.column) {
      problems.push('Choose a column on each line.');
      continue;
    }
    if (!columns.includes(m.column)) problems.push(`${m.column} is not in the table's schema.`);
    if (seen.has(m.column)) problems.push(`${m.column} is masked twice.`);
    seen.add(m.column);
    if (m.mask === CUSTOM && !m.custom) problems.push(`Write the expression that masks ${m.column}.`);
    if (m.mask === CUSTOM && long(m.custom)) problems.push(`The mask of ${m.column} is longer than 4,000 characters.`);
  }
  return [...new Set(problems)];
}

/** Removes a group's rule, after saying what follows: that group sees no rows, or, for the last rule, everyone sees every row. */
function removeDialog(target, rule, rules, after) {
  const last = rules.length === 1;
  openDialog(`Remove the rule for ${rule.group}`, h('div', null,
    h('p', {'data-follows': ''}, last ? 'Everyone with select will see every row through Trino.' : `${rule.group} will see no rows.`),
    h('p', {class: 'fine'}, 'The rule and its removal stay in the audit trail.'),
    dialogForm(null, 'Remove the rule', async (form, out) => {
      const answer = await api.removeRule(target, rule.group);
      fill(out, granted(`Removed the rule for ${rule.group}`, answer));
      after();
    })));
}

// ---- a reader's own view ----

const ROWS = {all: 'You see every row.', filtered: 'Your rows are filtered.', none: 'You see no rows.'};

/**
 * "Your view of this table", under What you may do, on a flagged table for a caller who may select it: whether their
 * rows are filtered and which columns are masked for them, never the expressions (backlog 242). Nothing for a caller
 * who may not select it, who is never asked, so browsing writes no denial.
 */
export function yourView(target, held) {
  const box = h('div');
  if (target.kind !== 'table') return box;
  (async () => {
    const privileges = new Set((await held).map(g => g.privilege));
    if (!['select', 'write', 'modify'].some(p => privileges.has(p))) return;
    let view;
    try {
      view = await api.myRuleView(target);
    } catch (error) {
      fill(box, failure(error, {compact: true}));
      return;
    }
    if (!view.flagged) return;
    const masked = view['masked-columns'] || [];
    fill(box, h('div', {class: 'panel', id: 'your-view'}, h('h2', null, 'Your view of this table'),
      h('p', {'data-view': view.rows}, 'Read through Trino only. ', ROWS[view.rows] || '',
        masked.length ? ` Masked for you: ${masked.join(', ')}.` : '')));
  })();
  return box;
}

// Which branches and tags keep a table or view, and taking it out of them (item 279; Atelier, 2026-11-25): the Access
// tab's "Kept by branches and tags" panel, the control in item 170's "Dropped on main, read on main" state, and the
// dialog both open, over item 156's route. Drawn from item 170's read alone (api.objectRefs), never from the table's
// load or its governance, so it works for a tag steward without select and for a table main has dropped. Every name
// the catalog gives goes in as text (dom.js h()).

import * as api from './api.js';
import {$, $$, dialogForm, failure, fill, granted, h, openDialog} from './dom.js';
import {objectHref} from './route.js';
import {cref, dayMon} from './refs.js';

const sameRef = (a, b) => String(a).toLowerCase() === String(b).toLowerCase();
const keeps = (o, name) => (o.refs || []).some(x => sameRef(x.name, name));
const dropped = o => o.state !== 'active';
const pathOf = o => [...(o.namespace || []), o.name].join('.');
const kindOf = o => o.kind === 'view' ? 'view' : 'table';

/**
 * The objects at the page's path whose refs the panel lists, each by its own id (Atelier, 2026-11-25): through a ref,
 * the one it keeps, never main's new table of that name; on main, main's live one, or with none live every dropped
 * one, newest first, since a name dropped, made again and dropped again is two objects.
 */
export function shownObjects(objects, target) {
  if (target.ref) {
    const kept = objects.filter(o => keeps(o, target.ref));
    if (kept.length) return kept;
  }
  const active = objects.find(o => !dropped(o));
  return active ? [active] : objects;
}

/** A ref's row words: `tag · made {d Mon}` or `branch · expires {d Mon}`, its own version, and the page's own ref. */
function rowWords(x, target) {
  if (x.expired) return `branch · past its expiry ${dayMon(x['expires-at'])}; the worker ends it at its next run`;
  return (x.kind === 'tag' ? `tag · made ${dayMon(x['created-at'])}` : `branch · expires ${dayMon(x['expires-at'])}`)
    + (x['own-version'] != null ? ` · changed it: its version ${x['own-version']}` : '')
    + (target.ref && sameRef(x.name, target.ref) ? ' · you are reading it here' : '');
}

/** The object as a console target, read through `ref`. */
const objectTarget = (target, o, ref) => ({catalog: target.catalog, kind: kindOf(o), namespace: o.namespace || target.namespace,
  name: o.name, ref});

/** Whether a legal hold or a DR freeze is known on a live object, from main's governance; a dropped one has none there. */
async function heldOrFrozen(target, o) {
  if (dropped(o)) return null;
  try {
    const g = await api.governance(objectTarget(target, o, null));
    if ((g.holds || []).length) return 'held';
    if ((g.effective || {})['dr-freeze']) return 'frozen';
  } catch {
    // Unknown: the button shows, and the route refuses a hold or a freeze in the dialog, verbatim.
  }
  return null;
}

const HELD = 'Under a legal hold, so its branches and tags keep it until the hold is released.';
const FROZEN = 'Frozen for DR reconcile: nothing takes it out of a branch or tag until the freeze ends.';
const STEWARD = 'A tag steward, holding manage-tags on it, can take it out of them so cleanup can delete its files.';

/** The button, or the hold, freeze or steward line in its place; nothing for a reader of a live object. */
export async function keptActs(target, o) {
  const n = (o.refs || []).length;
  if (!(o.may || {}).remove) return dropped(o) && n ? h('p', {class: 'fine', 'data-kept-steward': ''}, STEWARD) : null;
  if (!n) return null;
  const stop = await heldOrFrozen(target, o);
  if (stop === 'held') return h('p', {class: 'fine', 'data-kept-held': ''}, HELD);
  if (stop === 'frozen') return h('p', {class: 'fine', 'data-kept-frozen': ''}, FROZEN);
  return h('div', {class: 'refusal-acts'}, h('button', {class: 'btn sm', type: 'button', 'data-act': 'take-out',
    onclick: () => takeOutDialog(target, o)}, 'Take out of branches and tags'));
}

/** One object's panel: title, lead, a row per ref, then the control. */
async function objectPanel(target, o) {
  const kind = kindOf(o);
  const refs = o.refs || [];
  const n = refs.length;
  const lead = !n ? h('p', {class: 'muted', 'data-kept-none': ''}, `No branch or tag keeps this ${kind}.`)
    : dropped(o) ? h('p', {'data-kept-lead': 'dropped'}, `Main dropped this ${kind} on ${dayMon(o['dropped-at'])}. These still read it and keep `
      + 'its files from cleanup; once none does, cleanup can purge it and delete its files.'
      + (o['purge-after'] ? ` Its purge time is ${dayMon(o['purge-after'])}.` : ''))
      : h('p', {'data-kept-lead': 'live'}, `Each reads this ${kind} as it was at its point, or as the branch has changed it, and keeps its `
        + 'files from cleanup.');
  const rows = n ? h('ul', {class: 'kept-list'}, refs.map(x => h('li', {'data-kept-row': x.name, class: x.expired ? 'expired' : null},
    h('a', {href: objectHref(objectTarget(target, o, x.name))}, cref(x, {warn: !!x.expired})), ' ',
    h('span', {class: 'fine'}, rowWords(x, target))))) : null;
  return h('div', {class: 'panel kept-panel', 'data-kept-object': o.id},
    h('h2', null, 'Kept by branches and tags', n ? [' ', h('span', {class: 'meta'}, String(n))] : null),
    lead, rows, await keptActs(target, o));
}

/**
 * The Access tab's panel (`id="kept-by-refs"`, after governance): one per object the page shows (shownObjects), or the
 * read's refusal as the catalog answered it.
 */
export async function keptPanel(target) {
  let answer;
  try {
    answer = await api.objectRefs(target);
  } catch (error) {
    return h('div', {class: 'stack', id: 'kept-by-refs'}, h('div', {class: 'panel kept-panel'},
      h('h2', null, 'Kept by branches and tags'), failure(error, {compact: true})));
  }
  const shown = shownObjects(answer.objects || [], target);
  // The catalog reads only part of a namespace's dropped tables (item 170): with none found, or only dropped ones
  // shown, an earlier one may be missing. Item 170's words, as on its "Dropped on main" state.
  const cut = !!answer['cut-short'];
  if (!shown.length) {
    return h('div', {class: 'stack', id: 'kept-by-refs'}, h('div', {class: 'panel kept-panel'}, h('h2', null, 'Kept by branches and tags'),
      cut ? h('p', {class: 'muted', 'data-kept-cut': ''}, 'The catalog reads only part of a namespace\'s dropped tables and found none '
        + 'at this path among them; one dropped earlier may still be kept by a branch or tag.') : null));
  }
  return h('div', {class: 'stack', id: 'kept-by-refs'}, await Promise.all(shown.map(o => objectPanel(target, o))),
    cut && shown.every(dropped) ? h('p', {class: 'fine', 'data-kept-cut': ''}, 'The catalog reads only part of a namespace\'s dropped '
      + 'tables and stopped before the end, so an earlier table at this path, and the branches and tags that keep it, may be '
      + 'missing here.') : null);
}

/**
 * Item 170's "Dropped on main, read on main" state leaves a `[data-kept-acts]` slot per dropped object, named by its
 * id: each gets that object's button, or the steward line.
 */
export async function fillKeptSlots(root, target) {
  await Promise.all($$('[data-kept-acts]', root).map(async slot => {
    try {
      const o = ((await api.objectRefsById(target.catalog, slot.dataset.keptActs)).objects || [])[0];
      if (o) fill(slot, await keptActs(target, o));
    } catch {
      // The state's own words stand; the slot stays empty.
    }
  }));
}

// ---- the dialog ----

const NONE = 'Choose which branches and tags to take it out of.';

/** The submit's label, which follows the choice. */
function okWords(every, ticked) {
  if (every) return 'Take it out of every one';
  if (ticked.length === 1) return `Take it out of @${ticked[0]}`;
  if (ticked.length > 1) return `Take it out of ${ticked.length} branches and tags`;
  return 'Take it out';
}

/** `@a, @b` as chips. */
const chipList = refs => refs.flatMap((x, i) => [i ? ', ' : null, cref(x)]);

/** Why a refusal happened, for the cases the console can say more about; null for the rest. */
function refusalNote(error) {
  if (error.type === 'NoSuchRefException' || (error.type === 'NoSuchObjectException' && / on ref /.test(error.message || ''))) {
    return ['The branches and tags changed since this list was read. The list above is read again; choose again.', true];
  }
  if (error.type === 'NoSuchObjectException') return ['It may have been purged since this page was read.', false];
  return [null, false];
}

/** What a fresh read of the object says is left, after a removal. */
function leftWords(o, after) {
  const still = (after && after.refs) || [];
  if (still.length) return h('span', null, chipList(still), ' still keep it.');
  if (!after) return null;
  return dropped(o) ? 'No branch or tag keeps it now. Cleanup can purge it and delete its files.'
    : 'Main still has it; no branch or tag keeps it now.';
}

/** Takes object `o` (by its id) out of the refs the caller chooses: every one, or those ticked. */
export function takeOutDialog(target, o) {
  const path = pathOf(o);
  let refs = o.refs || [];
  let tookFromPage = false;
  const choice = h('fieldset', {class: 'dlg-set kept-choice'});
  const ok = () => $('button[type=submit]', body);
  const chosen = () => {
    const every = !!$('input[name="which"][value="every"]:checked', choice);
    const ticked = $$('input[name="ref"]:checked', choice).map(x => x.value);
    return {every, some: !!$('input[name="which"][value="some"]:checked', choice), ticked};
  };
  const relabel = () => {
    const c = chosen();
    ok().textContent = okWords(c.every, c.some ? c.ticked : []);
  };
  const drawChoice = () => {
    fill(choice, h('legend', null, 'Take it out of'),
      h('label', {class: 'kept-every'}, h('input', {type: 'radio', name: 'which', value: 'every'}),
        h('span', null, `Every branch and tag that keeps it (${refs.length})`,
          h('span', {class: 'fine'}, 'Those that keep it when the catalog runs this, a branch past its expiry included.'))),
      h('label', {class: 'kept-some'}, h('input', {type: 'radio', name: 'which', value: 'some'}), h('span', null, 'Only the ones I tick')),
      h('ul', {class: 'kept-ticks'}, refs.map(x => h('li', null, h('label', null,
        h('input', {type: 'checkbox', name: 'ref', value: x.name, disabled: !!x.expired}),
        h('span', null, cref(x, {warn: !!x.expired}), ' ', h('span', {class: 'fine'}, rowWords(x, target)),
          x.expired ? h('span', {class: 'fine'}, 'Past its expiry: Every takes it out, or the worker ends it at its next run.') : null))))));
  };
  // Ticking a box selects "Only the ones I tick".
  choice.addEventListener('change', e => {
    if (e.target.name === 'ref' && e.target.checked) $('input[name="which"][value="some"]', choice).checked = true;
    relabel();
  });
  drawChoice();
  const live = !dropped(o);
  const owned = refs.some(x => x.kind === 'branch' && x['own-version'] != null);
  const body = h('div', null,
    h('p', null, h('b', null, `Each branch and tag you choose stops keeping ${path}.`),
      ' Reads of it through them answer 404, as if they had never held it, and they no longer keep its files from cleanup.'),
    h('p', null, live ? 'Main still has it. Files that only these reached are left to the orphan scan, which removes them once they '
      + 'are older than its minimum age.'
      : `Main dropped it on ${dayMon(o['dropped-at'])}. Once no branch or tag keeps it, cleanup can purge it and delete its files.`),
    owned ? h('p', {'data-kept-own': ''}, 'A branch\'s own commits to it go too, so a merge can no longer bring them to main.') : null,
    h('p', null, h('b', null, 'This cannot be undone:'), ' no branch or tag can take it back. Each ref\'s record in the audit names the change.'),
    dialogForm([choice], okWords(false, []), async (form, out) => {
      const c = chosen();
      const ticked = c.some ? c.ticked : [];
      if (!c.every && !ticked.length) { fill(out, h('p', {class: 'fine grant-hint'}, NONE)); return; }
      let answer;
      try {
        answer = await api.removeFromRefs(target.catalog, o.id, c.every ? {'all-refs': true} : {refs: ticked});
      } catch (error) {
        const [note, again] = refusalNote(error);
        fill(out, failure(error, {note}));
        if (again) {
          try {
            const fresh = ((await api.objectRefsById(target.catalog, o.id)).objects || [])[0];
            refs = fresh ? fresh.refs || [] : [];
          } catch {
            // The refusal stands as the catalog sent it.
          }
          drawChoice();
          relabel();
        }
        return;
      }
      const took = (answer.body || {}).refs || [];
      tookFromPage = !!target.ref && took.some(x => sameRef(x.name, target.ref));
      let after = null;
      try {
        after = ((await api.objectRefsById(target.catalog, o.id)).objects || [])[0] || {refs: []};
      } catch (error) {
        after = error.status === 404 ? {refs: []} : null;
      }
      fill(out, took.length ? granted(h('span', null, `Took ${path} out of `, chipList(took)), answer, leftWords(o, after))
        : granted('Nothing to take out', answer, `No branch or tag kept ${path} when the catalog looked; nothing changed.`));
      form.setAttribute('data-done', '');
      ok().hidden = true;
    }));
  ok().classList.add('danger');
  const dlg = openDialog(`Take ${o.name} out of branches and tags`, body);
  dlg.classList.add('kept-dlg');
  // On close the page reads again; a page read through a ref the table just left goes to the same object on main.
  dlg.addEventListener('close', () => {
    if (!$('[data-done]', dlg)) return;
    if (tookFromPage) {
      location.hash = objectHref({...target, ref: null}, new URLSearchParams(location.hash.slice(1)).get('tab'));
    } else {
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    }
  });
  // Focus starts on Cancel, so one Enter never removes anything.
  $('.dlg-acts .btn.ghost', dlg).focus();
  return dlg;
}

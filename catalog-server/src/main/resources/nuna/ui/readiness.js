// What the maintenance worker needs to maintain a catalog (backlog 252, Atelier's design): the four steps item 251's
// route proves (grant, storage, store, policy), each with a pill, one line, who does it and an action. Two of them are
// edits outside the catalog (the worker's settings, the store's policy: D21, D30), so the console only proves them,
// shows the gap and hands the work to whoever may do it. Each action is drawn only for a caller the catalog would let
// do it, so no button here draws an audited refusal (catalog-security, 2026-11-24).

import * as api from './api.js';
import {at, code, copyText, fill, h, icon, pill, plural} from './dom.js';
import {may, operates} from './access.js';
import {grantAccess} from './grant.js';
import {ownGrants} from './requests.js';
import {pageHref} from './route.js';
import {setupDialogFor} from './storage.js';

const STEPS = ['grant', 'storage', 'store', 'policy'];

/**
 * What the caller holds on the catalog itself, from their own groups' grants and the catalog list, so asking writes no
 * denial: whether they may read its readiness (describe on it, or a platform operator), grant on it, and set its
 * policy. A grant admin holds manage-grants on every catalog without a grant of their own (D18): the catalog list says
 * they may manage grants there while none of their grants gives it.
 */
export async function rightsOn(catalog) {
  const own = await ownGrants(catalog);
  const here = new Set(own.filter(g => g.securable && g.securable.type === 'catalog').map(g => g.privilege));
  const admin = may(catalog, 'manage-grants') && !own.some(g => g.privilege === 'manage-grants');
  return {read: operates() || admin || here.size > 0, grant: admin || here.has('manage-grants'), modify: here.has('modify')};
}

/**
 * The catalog's readiness for a caller who may read it, else null, so a caller the route would refuse is never sent:
 * {ready, rights}, or {error, rights} when the catalog answered with an error.
 */
export async function readinessOf(catalog) {
  const rights = await rightsOn(catalog);
  if (!rights.read) return null;
  try {
    return {ready: await api.readiness(catalog), rights};
  } catch (error) {
    return {error, rights};
  }
}

const notDone = ready => STEPS.filter(step => (ready[step] || {}).state !== 'done');

/**
 * The page head once a worker has reported and a step is not done: the worker can't maintain the catalog yet while
 * its write access or its policy is missing; with only the storage steps left, it maintains it but can't delete its
 * old files. Null otherwise.
 */
export function stepsWords(catalog, ready) {
  if (!ready || !ready['reported-at']) return null;
  const left = notDone(ready);
  if (!left.length) return null;
  return left.includes('grant') || left.includes('policy')
    ? `The worker can't maintain ${catalog} yet: ${left.length} of 4 steps left.`
    : `The worker maintains ${catalog} but can't delete its old files: ${left.length} of 4 steps left.`;
}

/** "a", "a and b", "a, b and c". */
const listWords = items => items.length < 2 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;

const bucketOf = prefix => String(prefix).replace(/^[a-z0-9]+:\/\//, '').split('/')[0];

const JOB_WORDS = {'snapshot-expiry': 'snapshot expiry', 'orphan-removal': 'orphan removal', compaction: 'compaction',
  'manifest-rewrite': 'manifest rewrite'};

const PILLS = {
  done: () => pill('ok', icon('check'), 'Done'),
  missing: () => pill('bad', icon('x'), 'Missing'),
  refused: () => pill('bad', icon('x'), 'Refused'),
  'not-known': () => pill('', icon('clock'), 'Not known'),
  waits: () => pill('', icon('clock'), 'Waits on step 2'),
  'no-answer': () => pill('warn', icon('alert'), 'No answer'),
};

const NOT_KNOWN = 'No maintenance worker has reported, so the catalog can\'t check this.';

/**
 * Steps 1 to 3 while they are not known: no scan at all, or a newest scan that reported no storage (`scanned-at` without
 * `reported-at`), as when the worker could not get a token just after a restart (items 275, 277). That one says when,
 * and promises no time, since a worker of an earlier release never reports its storage.
 */
const notKnown = ready => ready['scanned-at']
  ? `The worker's last scan, at ${at(ready['scanned-at'])}, did not report its access, so the catalog can't check this `
    + 'until a scan does.'
  : NOT_KNOWN;

/**
 * The panel "What the worker needs to maintain <catalog>", while any step is not done; null when every one is.
 * `answer` is `readinessOf`'s; `editPolicy` opens the catalog's policy, for a caller whose groups hold modify on it, and
 * `reload` redraws the page once a grant is made.
 */
export function readinessPanel(catalog, answer, {editPolicy, reload}) {
  const {ready, rights} = answer;
  const left = notDone(ready);
  if (!left.length) return null;
  const names = !!ready.names;
  const reported = ready['reported-at'];
  const unknownLine = notKnown(ready);
  const storage = `${catalog}'s storage`;
  const askNames = () => h('p', {class: 'fine step-ask'}, `Ask someone with modify on ${catalog} or a platform operator.`);
  // What to add and What to apply open the operator's dialog for the catalog's profile (backlog 254); Open Worker
  // storage opens Service on the Worker storage panel, at the profile's row.
  const operatorAction = (act, words) => act === 'open-worker-storage'
    ? h('a', {class: 'btn sm', href: workerStorageHref(ready), 'data-act': act}, words)
    : h('button', {class: 'btn sm', type: 'button', 'data-act': act, onclick: () => setupDialogFor(catalog)}, words);
  const note = noteButton(catalog, ready);
  // An action for a step done outside the catalog: an operator's dialog or link, the note for others who may see names,
  // else whom to ask.
  const outside = (act, words) => !names ? askNames() : operates() ? operatorAction(act, words) : note();

  // 1. Write access.
  const grant = ready.grant || {};
  const groups = grant.groups || [];
  const writers = groups.filter(g => g.write);
  let grantLine;
  if (grant.state === 'unknown') grantLine = unknownLine;
  else if (!names || !groups.length) {
    grantLine = grant.state === 'done' ? `The worker holds write on ${catalog}.`
      : `The worker holds no write on ${catalog}, so it can't read or commit its tables.`;
  } else if (grant.state === 'done') {
    grantLine = groups.length === 1 ? `${writers[0].group}, the worker's group, holds write on ${catalog}.`
      : `${writers[0].group}, one of the worker's groups, holds write on ${catalog}.`;
  } else {
    grantLine = groups.length === 1
      ? `${groups[0].group}, the worker's group, holds no write on ${catalog}, so the worker can't read or commit its tables.`
      : `None of the worker's groups (${groups.map(g => g.group).join(', ')}) holds write on ${catalog}, so the worker `
        + 'can\'t read or commit its tables.';
  }
  // Item 248's dialog, filled in: the worker's one group, or none when it carries several, since the first may be a broad
  // one (Atelier, 2026-11-24); "Read and write rows" on the catalog. The person still presses Grant.
  const grantTo = groups.length === 1 ? groups[0].group : null;
  const grantAction = grant.state === 'missing' ? (!names ? askNames() : rights.grant
    ? h('button', {class: 'btn sm', type: 'button', 'data-act': 'grant-write', onclick: () => grantAccess(
      {catalog, kind: 'catalog', namespace: []}, {group: grantTo, privilege: 'write', closed: reload})},
    grantTo ? `Grant write to ${grantTo}` : `Grant write on ${catalog}`)
    : h('p', {class: 'fine step-ask'}, 'Ask a grant admin.')) : null;

  // 2. Storage settings.
  const st = ready.storage || {};
  const prefixes = (st.prefixes || []).map(p => p.prefix);
  const storageLine = st.state === 'unknown' ? unknownLine // since item 256 what the worker can lack is a store identity
    : st.state === 'done' ? `The worker's settings cover ${names ? listWords(prefixes) : storage}.`
      : (names ? noIdentity(st) : `The worker holds no identity for the store that holds ${catalog}'s tables.`)
        + ' Until it does, files that expiry and compaction replace are kept, and no orphan is found.';


  // 3. Store permission.
  const store = ready.store || {};
  const time = at(reported);
  const since = at(store['deletes-since']);
  const each = store.prefixes || [];
  const listRefused = each.filter(p => p.listed === 'refused');
  const deleteRefused = each.filter(p => (p['delete-refused'] || 0) > 0);
  const noAnswer = each.filter(p => p.listed === 'failed');
  let storeState;
  let storeLine;
  if (store.state === 'unknown' && !reported) {
    storeState = 'not-known';
    storeLine = unknownLine;
  } else if (store.state === 'unknown' && store.code) {
    storeState = 'no-answer';
    storeLine = names
      ? `The store did not answer the worker's listing of ${listWords(noAnswer.map(p => p.prefix))} at ${time}: ${store.code}.`
      : `The store did not answer the worker's listing in ${storage} at ${time}: ${store.code}.`;
  } else if (store.state === 'unknown') {
    storeState = 'waits';
    storeLine = 'The worker checks this at its first scan once step 2 is done.';
  } else if (store.state === 'refused') {
    storeState = 'refused';
    if (!names) {
      storeLine = store.code === 'NoSuchBucket' ? `The store has no bucket for ${storage}.`
        : `The store refused some of the worker's requests in ${storage}: ${store.code}.`;
    } else if (listRefused.length && listRefused.every(p => p['list-code'] === 'NoSuchBucket')) {
      storeLine = `The store has no bucket ${listWords([...new Set(listRefused.map(p => bucketOf(p.prefix)))])}.`;
    } else if (listRefused.length) {
      storeLine = `The store refused the worker's listing of ${listWords(listRefused.map(p => p.prefix))} at ${time}: `
        + `${store.code}.`;
    } else {
      const n = deleteRefused.reduce((sum, p) => sum + Number(p['delete-refused']), 0);
      storeLine = `The store refused ${n} of the worker's deletes in ${listWords(deleteRefused.map(p => p.prefix))} since `
        + `${since}: ${store.code}.`;
    }
  } else {
    storeState = store.state;
    const deleted = each.reduce((sum, p) => sum + Number(p.deleted || 0), 0);
    storeLine = names ? `The store let the worker list ${listWords(prefixes)} at ${time}`
      + (deleted ? ` and delete ${plural(deleted, 'file')} since ${since}.` : '.')
      : `The store let the worker list ${storage} at ${time}.`;
  }
  const storeAction = storeState === 'not-known' || storeState === 'done' ? null
    : storeState === 'no-answer' ? outside('open-worker-storage', 'Open Worker storage')
      : outside('what-to-apply', 'What to apply');

  // 4. Maintenance policy.
  const policy = ready.policy || {};
  const jobs = (policy.jobs || []).map(j => JOB_WORDS[j] || j);
  const policyLine = policy.state === 'done' ? `On in ${catalog}: ${jobs.join(', ')}.` : `Every job is off in ${catalog}'s policy.`;
  const policyAction = policy.state === 'done' ? null : rights.modify
    ? h('button', {class: 'btn sm', type: 'button', 'data-act': 'edit-policy', onclick: editPolicy}, 'Edit the catalog\'s policy')
    : h('p', {class: 'fine step-ask'}, 'Ask someone with ', code('modify'), ` on ${catalog}.`);

  const row = (step, n, title, state, line, who, action) => h('li', {class: 'step', 'data-step': step, 'data-state': state},
    h('div', {class: 'step-pill'}, PILLS[state] ? PILLS[state]() : pill('', state)),
    h('div', {class: 'step-body'},
      h('h3', null, `${n}. ${title}`),
      h('p', {class: 'step-line'}, line),
      h('p', {class: 'fine step-who'}, 'Who: ', who),
      action ? h('div', {class: 'step-act'}, action) : null));
  const pillOf = s => s === 'unknown' ? 'not-known' : s;
  const done = STEPS.length - left.length;
  return h('div', {class: 'panel', id: 'readiness'},
    h('h2', null, `What the worker needs to maintain ${catalog} `, h('span', {class: 'meta'},
      `${done} of 4 done` + (reported ? ` · as of its scan at ${time}` : ''))),
    h('ol', {class: 'steps'},
      row('grant', 1, 'Write access', pillOf(grant.state), grantLine, 'a grant admin.', grantAction),
      row('storage', 2, 'Storage settings', pillOf(st.state), storageLine, 'a platform operator, in the worker\'s settings.',
        st.state === 'missing' ? outside('what-to-add', 'What to add') : null),
      row('store', 3, 'Store permission', storeState, storeLine,
        storeState === 'no-answer' ? 'a platform operator.' : 'a platform operator, in the store.', storeAction),
      row('policy', 4, 'Maintenance policy', policy.state, policyLine, ['someone with ', code('modify'), ` on ${catalog}.`],
        policyAction)),
    h('p', {class: 'fine step-foot'}, 'The catalog can\'t change the worker\'s settings or the store\'s policies: the worker\'s '
      + 'keys stay out of the catalog, and a catalog that could set store policies could undo the one that keeps delete from it.'));
}

/** The note for a platform operator, from what the readiness names: only for callers who may see names. */
export function noteText(catalog, ready) {
  const each = (ready.store || {}).prefixes || [];
  const prefixes = ((ready.storage || {}).prefixes || []).map(p => p.prefix);
  const parts = [`The maintenance worker can't delete old files in catalog ${catalog}.`,
    `Its tables are in ${listWords(prefixes)}.`];
  if ((ready.storage || {}).state === 'missing') parts.push(noIdentity(ready.storage));
  for (const p of each) {
    if (p.listed === 'refused' && p['list-code'] === 'NoSuchBucket') {
      parts.push(`The store has no bucket ${bucketOf(p.prefix)} (NoSuchBucket).`);
    } else if (p.listed === 'refused') {
      parts.push(`The store refused the worker's listing there (${p['list-code']}).`);
    } else if (p.listed === 'failed') {
      parts.push(`The store did not answer the worker's listing there (${p['list-code']}).`);
    } else if ((p['delete-refused'] || 0) > 0) {
      parts.push(`The store refused ${p['delete-refused']} of the worker's deletes there (${p['delete-code']}).`);
    }
  }
  parts.push(`What to apply is on the console's Service page, under Worker storage: ${location.origin}${location.pathname}`
    + workerStorageHref(ready));
  return parts.join(' ');
}

/**
 * "Copy a note for an operator": a fresh button each time it is drawn. Once copied it reads "Copied", and a line says
 * whom to send it to.
 */
function noteButton(catalog, ready) {
  return () => {
    const sent = h('p', {class: 'fine step-sent', hidden: true}, 'Send it to a platform operator.');
    const text = noteText(catalog, ready);
    const button = h('button', {class: 'btn sm', type: 'button', 'data-act': 'copy-note', 'data-note': text, onclick: async () => {
      await copyText(text, button);
      // copyText says "Copied" for a moment, or "Could not copy"; once copied, the button keeps saying so.
      if (button.textContent.trim() !== 'Copied') return;
      fill(button, icon('check'), 'Copied');
      sent.hidden = false;
    }}, icon('copy'), 'Copy a note for an operator');
    return h('div', {class: 'step-note'}, button, sent);
  };
}

/** Service, on the Worker storage panel at the catalog's profile's row (backlog 254). */
const workerStorageHref = ready => pageHref('service', null, 'worker-storage', (ready.storage || {}).profile);

/** Step 2's gap, with names (item 256): no identity for the store at the profile's endpoint, or for AWS S3's own. */
const noIdentity = storage => storage.endpoint ? `The worker holds no identity for the store at ${storage.endpoint}.`
  : 'The worker holds no identity for AWS S3.';

// What a platform operator sets up outside the catalog so the maintenance worker can maintain a storage profile's
// catalogs (backlog 254, Atelier's design and her answers of 2026-11-24): the dialog "Set up the worker's storage for
// <catalog>", opened from Maintenance's steps 2 and 3 and from each row of Service's "Worker storage" panel, both read
// from item 253's route. The catalog writes the settings and policies and applies none of them (D21, D30), so the
// dialog hands over the exact text to copy. The route answers platform operators only, and refuses everyone else with an
// audited 403, so only an operator is ever offered either.

import * as api from './api.js';
import {at, code, copyText, failure, h, icon, openDialog, panel, tbl} from './dom.js';

/** "a", "a and b", "a, b and c". */
const listWords = items => items.length < 2 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;

/** The catalogs a dialog opened from Service is for: up to three by name, then how many more; or the profile. */
export function catalogWords(profile) {
  const catalogs = profile.catalogs || [];
  if (!catalogs.length) return `profile ${profile.name}`;
  if (catalogs.length > 3) return `${catalogs.slice(0, 3).join(', ')} and ${catalogs.length - 3} more`;
  return listWords(catalogs);
}

/** A command or setting with each `<placeholder>` marked, for the operator to fill in. */
const marked = text => String(text).split(/(<[^<>]+>)/).filter(Boolean)
  .map(part => /^<[^<>]+>$/.test(part) ? h('mark', {class: 'fill-in'}, part) : part);

// Shown without the trailing newline the route's YAML ends with; copied as the route gives it.
const block = (kind, text) => h('pre', {class: 'raw setup-text', 'data-text': kind}, marked(String(text).replace(/\n+$/, '')));

/** A copy button: "Copied" for a moment once copied, as every copy button in the console. */
const copy = (act, words, text) => h('button', {class: 'btn sm', type: 'button', 'data-act': act,
  onclick: e => copyText(text, e.currentTarget)}, icon('copy'), words);

/** A policy as the route gives it, laid out to read. */
function pretty(policy) {
  try {
    return JSON.stringify(JSON.parse(policy), null, 2);
  } catch {
    return String(policy);
  }
}

/** The policy, folded behind its summary. */
const fold = (summary, policy) => h('details', {class: 'setup-fold', 'data-fold': ''}, h('summary', null, summary),
  block('policy', pretty(policy)));

/** "1. The worker's settings": only for a store the worker holds no identity on (item 256). */
function settingsBlock(profile) {
  const ws = profile['worker-settings'];
  return h('section', {class: 'setup-step', 'data-block': 'worker-settings'},
    h('h3', null, '1. The worker\'s settings'),
    h('p', null, `Add a profile for ${profile.endpoint}. Its key and secret are the worker's own on that store, never the `
      + 'catalog\'s:'),
    block('settings', ws.yaml),
    /^\s*ca-bundle:/m.test(ws.yaml) ? h('p', null, 'Put the store\'s CA bundle where the worker can read it, and give its '
      + 'path as ca-bundle.') : null,
    h('p', null, `Put the secret in the release's secrets as ${ws.secret}, then upgrade the release.`
      + (ws.note ? ' On AWS S3 with a role for the worker (IRSA or Pod Identity), leave both keys out.' : '')),
    h('div', {class: 'dlg-acts'}, copy('copy-settings', 'Copy the settings', ws.yaml)));
}

/** "2. The store's policy": the policies with their commands for the store's kind, or the setting it lacks. */
function storeBlock(profile) {
  const store = profile.store || {};
  const body = [];
  if (store.state === 'unprotected') {
    body.push(h('p', null, `The catalog's profile ${profile.name} names no maintenance principal, so the catalog generates no `
      + 'delete policy for it and nothing stops other keys deleting there. Name the worker\'s storage identity in the '
      + 'catalog\'s settings, then upgrade the catalog and come back:'),
    block('setting', store.setting),
    h('div', {class: 'dlg-acts'}, copy('copy-setting', 'Copy the setting', store.setting)));
  } else {
    const policies = store.policies || [];
    for (const p of policies.filter(x => x.to)) {
      body.push(h('div', {class: 'setup-policy', 'data-policy': p.name},
        h('p', null, `As a store admin, let the worker's user list and delete beneath ${listWords(profile.prefixes || [])}:`),
        block('commands', p.commands.join('\n')),
        h('div', {class: 'dlg-acts'}, copy('copy-commands', 'Copy the commands', p.commands.join('\n')),
          copy('copy-policy', 'Copy the policy', pretty(p.policy))),
        fold(`The policy, to save as ${p.file}`, p.policy)));
    }
    for (const p of policies.filter(x => x.except)) {
      body.push(h('div', {class: 'setup-policy', 'data-policy': p.name},
        h('p', null, `Then attach ${p.name} to every other user of the store, the catalog's own included, so only the `
          + 'worker deletes there:'),
        block('commands', p.commands.join('\n')),
        h('p', {class: 'fine'}, 'Run the attach line once for each user.'),
        h('div', {class: 'dlg-acts'}, copy('copy-commands', 'Copy the commands', p.commands.join('\n')),
          copy('copy-policy', 'Copy the policy', pretty(p.policy))),
        fold(`The policy, to save as ${p.file}`, p.policy)));
    }
    for (const p of policies.filter(x => x.bucket)) {
      const shared = (p.profiles || []).length > 1 ? ` It serves profiles ${listWords(p.profiles)}: apply it once.` : '';
      body.push(h('div', {class: 'setup-policy', 'data-policy': p.bucket},
        h('p', null, `As an identity that may, put this policy on bucket ${p.bucket}. A bucket holds one policy: merge these `
          + 'statements into any it already has.' + shared),
        block('commands', p.commands.join('\n')),
        h('div', {class: 'dlg-acts'}, copy('copy-commands', 'Copy the command', p.commands.join('\n')),
          copy('copy-policy', 'Copy the policy', pretty(p.policy))),
        fold('The policy, to merge into the bucket\'s own', p.policy)));
    }
  }
  return h('section', {class: 'setup-step', 'data-block': 'store'}, h('h3', null, '2. The store\'s policy'), body);
}

/**
 * The dialog "Set up the worker's storage for <catalog>" for one profile of the route's answer: the worker's settings
 * while the worker holds no identity on the profile's store, then the store's policy. `forWords` is the catalog of the
 * page it was opened from, or `catalogWords` of the profile.
 */
export function setupDialog(forWords, profile) {
  const settings = !!profile['worker-settings'];
  // While the newest scan reported no storage (items 275, 277), the route gives no worker settings, though the worker
  // may yet need them.
  const unknown = (profile.worker || {}).state === 'unknown';
  return openDialog(`Set up the worker's storage for ${forWords}`, h('div', {class: 'setup', 'data-profile': profile.name},
    h('p', {class: 'setup-intro'}, settings
      ? 'Two changes outside the catalog, one in the worker\'s settings and one in the store. The steps on the Maintenance '
        + 'page turn to Done at the worker\'s next scan after each.'
      : unknown ? 'One change outside the catalog, in the store, and perhaps one in the worker\'s settings: the worker\'s '
        + 'last scan did not report its access, so the catalog can\'t tell until a scan does.'
        : 'One change outside the catalog, in the store. Its step on the Maintenance page turns to Done at the worker\'s '
          + 'next scan after it.'),
    settings ? settingsBlock(profile) : null,
    storeBlock(profile),
    h('p', {class: 'fine setup-foot'}, 'The catalog writes these policies but never applies them: its own keys would then '
      + 'need the right to undo them.')), {wide: true});
}

/** Opens the dialog for a catalog's profile, from Maintenance: the route is read when it is asked for. */
export async function setupDialogFor(catalog) {
  try {
    const body = await api.storageSetup();
    const profile = (body.profiles || []).find(p => (p.catalogs || []).includes(catalog));
    if (!profile) throw new Error(`The catalog named no storage profile for ${catalog}.`);
    return setupDialog(catalog, profile);
  } catch (error) {
    return openDialog(`Set up the worker's storage for ${catalog}`, failure(error));
  }
}

// ---- Service's "Worker storage" panel ----

/**
 * The worker's identity on the profile's store: [state, words]. `unknown` when the newest scan reported no storage, as
 * just after a restart (items 275, 277): a worker reported, so not "No report yet".
 */
function identityOf(profile, reported, scanned) {
  if ((profile.worker || {}).state === 'unknown') {
    return ['not-known', `Not known: the worker's last scan, at ${at(scanned)}, did not report it`];
  }
  if (!reported) return ['no-report', 'No report yet'];
  return (profile.worker || {}).state === 'reported' ? ['identity', 'Has an identity'] : ['no-identity', 'No identity'];
}

/**
 * What the store answered the worker beneath the profile's prefixes: [state, words]. A refused listing first, then
 * refused deletes, a store that did not answer, a prefix not checked yet; else listed, at the worker's last report.
 */
export function storeOf(profile, reported) {
  const worker = profile.worker || {};
  const each = worker.state === 'reported' ? worker.prefixes || [] : [];
  const first = test => each.find(test);
  const listing = first(p => p.listed === 'refused');
  if (listing) return ['list-refused', `Listing refused: ${listing['list-code']}`];
  const deletes = first(p => (p['delete-refused'] || 0) > 0);
  if (deletes) return ['delete-refused', `Deletes refused: ${deletes['delete-code']}`];
  const listFailed = first(p => p.listed === 'failed');
  if (listFailed) return ['no-answer', `No answer: ${listFailed['list-code']}`];
  const deleteFailed = first(p => (p['delete-failed'] || 0) > 0);
  if (deleteFailed) return ['no-answer', `No answer: ${deleteFailed['delete-code']}`];
  if (!each.length || first(p => !p.listed)) return ['not-checked', 'Not checked'];
  return ['listed', `Listed ${at(reported)}`];
}

/** The delete policy: [state, words]. */
const policyOf = profile => (profile.store || {}).state === 'unprotected' ? ['unprotected', 'Unprotected']
  : ['generated', `Generated for ${profile['maintenance-principal']}; the catalog can't see whether it is applied`];

/** Whether something is not done for the profile: the worker's identity, the store's answer or its delete policy. */
const notDone = (profile, reported) => identityOf(profile, reported, null)[0] !== 'identity' || storeOf(profile, reported)[0] !== 'listed'
  || policyOf(profile)[0] === 'unprotected';

/**
 * Service's "Worker storage" panel: one row per catalog storage profile, those with something not done first, each
 * with What to set up. `focus` names a profile to bring into view and mark, as the link from Maintenance does.
 */
export async function workerStoragePanel(focus) {
  let body;
  try {
    body = await api.storageSetup();
  } catch (error) {
    if (error.status === 403) return null;
    throw error;
  }
  const reported = body['reported-at'];
  const scanned = body['scanned-at'];
  const profiles = (body.profiles || []).map((p, i) => ({p, i, open: notDone(p, reported)}))
    .sort((a, b) => (b.open - a.open) || (a.i - b.i)).map(x => x.p);
  const cell = ([state, words], cls) => h('td', {'data-state': state, class: cls || null}, words);
  const rows = profiles.map(p => h('tr', {'data-profile': p.name, class: p.name === focus ? 'is-sel' : null},
    h('td', {class: 'name'}, p.name),
    h('td', null, (p.catalogs || []).length ? (p.catalogs || []).join(', ') : h('span', {class: 'muted'}, '–')),
    h('td', {class: 'setup-prefixes'}, (p.prefixes || []).map(x => h('div', null, code(x)))),
    cell(identityOf(p, reported, scanned)),
    cell(storeOf(p, reported)),
    cell(policyOf(p)),
    h('td', null, h('button', {class: 'btn sm', type: 'button', 'data-act': 'what-to-set-up',
      onclick: () => setupDialog(catalogWords(p), p)}, 'What to set up'))));
  const box = h('div', {id: 'health-storage'}, panel('Worker storage', null,
    tbl('setup-tbl', ['Profile', 'Catalogs', 'Prefixes', 'Worker\'s identity', 'Store', 'Delete policy', ''], rows)));
  if (focus !== undefined) {
    requestAnimationFrame(() => (box.querySelector('tr.is-sel') || box).scrollIntoView({block: 'center'}));
  }
  return box;
}

// Maintenance: tonight's queue in one catalog, the catalog's maintenance policy (read, and set by those who may
// modify the catalog), and the worker's scans for platform operators. Waiting on other items, and left out until
// they land: last night's figures with their 30-night sparklines (126) and "Compact a table now" (114).

import * as api from './api.js';
import {dialogForm, failure, field, fill, granted, h, icon, load, openDialog, pageHead, panel, pill, plural, tbl, text}
  from './dom.js';
import {emptyQueue, lastAssessed, queue, queueChips, queueOf, queueTable} from './queue.js';
import {sourceWords, workerPanel} from './health.js';

/** The policy keys, in the order the prototype lists them; a key under an .enabled key is indented. */
const KEYS = ['snapshot-expiry.enabled', 'snapshot-expiry.max-snapshot-age', 'snapshot-expiry.min-snapshots-to-keep',
  'orphan-removal.enabled', 'orphan-removal.older-than', 'compaction.enabled', 'compaction.target-file-size-bytes',
  'compaction.min-input-files', 'compaction.delete-file-ratio', 'manifest-rewrite.enabled', 'manifest-rewrite.min-manifests'];

export function maintenancePage(catalog) {
  const main = h('div', {class: 'nc-main', id: 'maintenance'});
  load(main, async () => {
    const rows = queueOf(await queue(true), catalog);
    const assessed = lastAssessed(rows);
    const policy = h('div');
    const worker = h('div');
    load(policy, () => policyPanel(catalog, policy));
    load(worker, workerPanel);
    let queuePanel;
    if (rows.length) {
      const table = queueTable(rows, {id: 'neediest'});
      queuePanel = panel('Tonight\'s queue', 'equality deletes first, then the highest score', queueChips(rows, table), table);
    } else {
      queuePanel = emptyQueue(catalog);
    }
    return [
      pageHead({
        eyebrow: `${catalog} · maintenance`,
        title: rows.length ? `${plural(rows.length, 'table')} queued for tonight.` : `Every table you may read in ${catalog} is in shape.`,
        sub: 'The worker compacts the neediest first, in its cleanup window. Only the tables you may read are listed; '
          + 'each one\'s assessment is on its Health tab.',
        side: assessed ? pill('ok', icon('check'), 'Assessed ' + new Date(assessed).toLocaleTimeString('en-GB', {hour: '2-digit', minute: '2-digit'})) : null,
      }),
      queuePanel,
      h('div', {class: 'grid-2'}, policy, worker),
    ];
  });
  return main;
}

const isChild = key => !key.endsWith('.enabled');

async function policyPanel(catalog, host) {
  const target = {catalog, kind: 'catalog', namespace: []};
  const p = await api.policy(target);
  const resolved = p.resolved || {};
  const keys = [...KEYS.filter(k => resolved[k]), ...Object.keys(resolved).filter(k => !KEYS.includes(k)).sort()];
  return h('div', {class: 'panel', id: 'policy'}, h('h2', null, `Policy for ${catalog} `, h('span', {class: 'meta'}, 'each key with where it was set')),
    tbl('policy stack-line', ['Key', 'Value', 'Set by'], keys.map(key => h('tr', {'data-key': key},
      h('td', {class: 'name' + (isChild(key) ? ' indent-1' : '')}, isChild(key) ? key.slice(key.indexOf('.') + 1) : key),
      h('td', {class: 'name'}, resolved[key].value),
      h('td', null, sourceWords(resolved[key].source))))),
    h('div', {class: 'refusal-acts'},
      h('button', {class: 'btn sm', type: 'button', onclick: () => editPolicy(catalog, p, () => load(host, () => policyPanel(catalog, host)))},
        'Edit the catalog\'s policy'),
      h('span', {class: 'fine'}, 'Needs ', h('code', null, 'modify'), ` on ${catalog}. Namespaces and tables can set their own keys.`)));
}

/** The catalog's own keys, edited as text; an empty field leaves the key to the built-in value. */
function editPolicy(catalog, p, reload) {
  const own = p.policy || {};
  const resolved = p.resolved || {};
  const keys = [...KEYS, ...Object.keys(resolved).filter(k => !KEYS.includes(k)).sort()];
  const body = h('div', null,
    h('p', null, 'Keys set here apply to every table in ', h('code', null, catalog), ' that does not set its own. '
      + 'A guardrail, a hold or a freeze, still wins. Leave a key empty to use the built-in value.'),
    dialogForm(h('div', {class: 'dlg-grid'}, keys.map(key => field(key, text(key, resolved[key] ? resolved[key].value : '', own[key])))),
      'Save the policy', async (form, out) => {
        const keysSet = {};
        for (const el of form.elements) if (el.name && el.value.trim()) keysSet[el.name] = el.value.trim();
        const target = {catalog, kind: 'catalog', namespace: []};
        try {
          const answer = Object.keys(keysSet).length ? await api.setPolicy(target, keysSet) : await api.removePolicy(target);
          fill(out, granted(Object.keys(keysSet).length ? `Saved the policy for ${catalog}` : `Removed the policy of ${catalog}`,
            answer, 'The worker reads the policy at its next scan.'));
          reload();
        } catch (error) {
          fill(out, failure(error));
        }
      }));
  openDialog(`Policy for ${catalog}`, body, {wide: true});
}

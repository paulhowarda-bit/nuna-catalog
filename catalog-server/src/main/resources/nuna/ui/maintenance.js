// Maintenance: last night's figures with their 30-night sparklines and its jobs on their slots (item 126), tonight's
// queue in one catalog, the catalog's maintenance policy (read, and set by those who may modify the catalog), and
// the worker's scans for platform operators. "Compact a table now", for a caller whose groups hold modify in the
// catalog (item 114).

import * as api from './api.js';
import {lastNightPanel, nightKpis, thirtyNights} from './nights.js';
import {dialogForm, failure, field, fill, fmt, granted, h, icon, load, openDialog, pageHead, panel, pill, plural, tbl, text}
  from './dom.js';
import {catalogOff, emptyQueue, estate, estateCounts, lastAssessed, queue, queueChips, queueOf, queueTable, shapeWords} from './queue.js';
import {sourceWords, workerPanel} from './health.js';
import {mayModifyAny, pickDialog} from './requests.js';
import {nothingToRead} from './overview.js';
import {pageHref} from './route.js';

/** The policy keys, in the order the prototype lists them; a key under an .enabled key is indented. */
const KEYS = ['snapshot-expiry.enabled', 'snapshot-expiry.max-snapshot-age', 'snapshot-expiry.min-snapshots-to-keep',
  'orphan-removal.enabled', 'orphan-removal.older-than', 'compaction.enabled', 'compaction.target-file-size-bytes',
  'compaction.min-input-files', 'compaction.delete-file-ratio', 'manifest-rewrite.enabled', 'manifest-rewrite.min-manifests'];

export function maintenancePage(catalog) {
  const main = h('div', {class: 'nc-main', id: 'maintenance'});
  load(main, async () => {
    const [rows, everywhere, may] = await Promise.all([queue(true).then(all => queueOf(all, catalog)), estate(true),
      mayModifyAny(catalog)]);
    const tables = everywhere.filter(t => t.catalog === catalog);
    const assessed = lastAssessed(rows);
    // The worker assesses only where compaction is on, so an assessed table means a policy nearer it turns maintenance
    // on: the catalog's policy is said to have it off only while none is assessed.
    const off = tables.length && estateCounts(tables).unassessed === tables.length
      ? await catalogOff(catalog, tables, pageHref('maintenance', catalog)) : null;
    // The queue lists only assessed tables that need compaction; the estate says which tables are not assessed yet.
    const c = estateCounts(tables);
    const policy = h('div');
    const worker = h('div');
    const figures = h('div');
    const lastNight = h('div');
    load(policy, () => policyPanel(catalog, policy));
    load(worker, workerPanel);
    const nights = thirtyNights();
    load(figures, async () => nightKpis(await nights));
    load(lastNight, async () => lastNightPanel(await nights));
    let queuePanel;
    if (rows.length) {
      const table = queueTable(rows, {id: 'neediest'});
      queuePanel = panel('Tonight\'s queue', 'equality deletes first, then the highest score', queueChips(rows, table), table);
    } else {
      queuePanel = emptyQueue(catalog, c, !!off);
    }
    return [
      pageHead({
        eyebrow: `${catalog} · maintenance`,
        title: !c.total ? nothingToRead(catalog, everywhere, 'maintenance')
          : off ? off
            : rows.length
              ? `${plural(rows.length, 'table')} queued for tonight.` + (c.unassessed ? ` ${fmt(c.unassessed)} not assessed yet.` : '')
              : c.unassessed ? shapeWords(c) : `Every table you may read in ${catalog} is in shape.`,
        sub: 'The worker compacts the neediest first, in its cleanup window. Only the tables you may read are listed; '
          + 'each one\'s assessment is on its Maintenance tab.',
        side: [assessed ? pill('ok', icon('check'), 'Assessed ' + new Date(assessed).toLocaleTimeString('en-GB', {hour: '2-digit', minute: '2-digit'})) : null,
          may ? h('button', {class: 'btn sm', type: 'button', 'data-act': 'compact-pick', onclick: () => pickDialog(catalog, rows)},
            icon('play'), 'Compact a table now') : null],
      }),
      figures,
      queuePanel,
      h('div', {class: 'grid-2'}, lastNight, policy),
      worker,
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
  return h('div', {class: 'panel', id: 'policy', 'data-policy': ''}, h('h2', null, `Policy for ${catalog} `, h('span', {class: 'meta'}, 'each key with where it was set')),
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

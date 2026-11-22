// Audit in one catalog: the records whose subject is the catalog itself, read back from the audit topic (needs
// manage-grants on it). Each namespace's, table's and view's records are on its own Audit tab. Waiting on other
// items, and left out until they land: the catalog-wide feed of every record, its counts by kind and busiest
// objects, and the hash chain's and archive's checks (125).

import {h, icon, pageHead} from './dom.js';
import {auditTab} from './panels.js';

export function auditPage(catalog) {
  const main = h('div', {class: 'nc-main', id: 'audit-page'});
  main.append(pageHead({
    eyebrow: `${catalog} · audit`,
    title: `The audit trail of ${catalog} itself`,
    sub: 'Records about the catalog: its grants, its governance and the namespaces made at its top, newest first. Each record opens '
      + 'to the event as Kafka holds it, with its request id.',
    side: h('button', {class: 'btn sm', type: 'button', 'data-act': 'goto-audit'}, icon('search'), 'Open a table\'s audit tab'),
  }),
  h('div', {class: 'notice waiting'}, icon('info'), h('span', null, h('b', null, 'Every record in the catalog, in one feed, comes with backlog 125.'),
    ' Until then each table\'s, view\'s and namespace\'s Audit tab reads its own records.')),
  auditTab({catalog, kind: 'catalog', namespace: []}, {title: `Records about ${catalog}`}));
  return main;
}

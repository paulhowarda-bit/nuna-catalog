// The right-hand pane: what the selected catalog, namespace, table or view holds, and who may do what to it.

import * as api from './api.js';
import {badge, facts, fill, failure, h, load, mono, table, tabs, when} from './dom.js';
import {go, rememberTab} from './app.js';
import {tableHealthPanel} from './health.js';

const PRIVILEGES = ['describe', 'select', 'write', 'modify', 'create', 'manage-grants', 'manage-tags'];

export function detail(pane, r) {
  const target = {catalog: r.catalog, kind: r.kind, namespace: r.namespace, name: r.name};
  const list = r.kind === 'table' ? tableTabs(target)
    : r.kind === 'view' ? viewTabs(target)
      : r.kind === 'namespace' ? namespaceTabs(target) : catalogTabs(target);
  fill(pane, header(target), tabs(list, r.tab, rememberTab));
}

function header(target) {
  const crumbs = [h('button', {type: 'button', class: 'crumb', onclick: () => go({catalog: target.catalog, kind: 'catalog', namespace: []})},
    target.catalog)];
  target.namespace.forEach((level, i) => {
    crumbs.push(h('span', {class: 'crumb-sep'}, '›'));
    crumbs.push(h('button', {type: 'button', class: 'crumb', onclick: () => go({catalog: target.catalog, kind: 'namespace',
      namespace: target.namespace.slice(0, i + 1)})}, level));
  });
  if (target.name) {
    crumbs.push(h('span', {class: 'crumb-sep'}, '›'));
    crumbs.push(h('span', {class: 'crumb current'}, target.name));
  }
  return h('div', {class: 'pane-head'}, badge(target.kind, target.kind), h('nav', {class: 'crumbs', 'aria-label': 'Path'}, crumbs));
}

function once(loader) {
  let promise = null;
  return () => {
    if (!promise) promise = loader();
    return promise;
  };
}

function section(title, ...content) {
  return h('section', {class: 'block'}, h('h3', null, title), content);
}

function properties(map) {
  const entries = Object.entries(map || {}).sort(([a], [b]) => a.localeCompare(b));
  if (!entries.length) return h('p', {class: 'muted'}, 'No properties.');
  return table(['Key', 'Value'], entries.map(([k, v]) => h('tr', null, h('td', null, mono(k)), h('td', {class: 'wrap'}, v))));
}

// ---- catalog and namespace ----

function catalogTabs(target) {
  return [
    {id: 'overview', label: 'Overview', render: async () => {
      const prefix = await api.prefix(target.catalog);
      const top = await api.namespaces(target.catalog, []);
      return h('div', null, facts([
        ['Catalog', target.catalog],
        ['REST prefix', mono(prefix)],
        ['Top-level namespaces', String(top.length)],
      ]), h('p', {class: 'muted'}, 'Pick a namespace, table or view in the tree.'));
    }},
    {id: 'governance', label: 'Governance', render: () => governancePanel(target)},
    {id: 'grants', label: 'Grants', render: () => grantsPanel(target, true)},
    {id: 'audit', label: 'Audit', render: () => auditPanel(target)},
  ];
}

function namespaceTabs(target) {
  return [
    {id: 'overview', label: 'Overview', render: async () => {
      const [ns, children, tables, views] = await Promise.all([
        api.namespace(target.catalog, target.namespace),
        api.namespaces(target.catalog, target.namespace),
        api.tables(target.catalog, target.namespace),
        api.views(target.catalog, target.namespace),
      ]);
      const open = (kind, name) => go({catalog: target.catalog, kind, namespace: target.namespace, name});
      return h('div', null,
        facts([
          ['Namespace', mono(target.namespace.join('.'))],
          ['Depth', String(target.namespace.length)],
          ['Namespaces', String(children.length)],
          ['Tables', String(tables.length)],
          ['Views', String(views.length)],
        ]),
        section('Properties', properties(ns.properties)),
        tables.length || views.length ? section('Tables and views', table(['Kind', 'Name'], [
          ...tables.map(t => h('tr', null, h('td', null, badge('table', 'table')),
            h('td', null, h('button', {type: 'button', class: 'link', onclick: () => open('table', t.name)}, t.name)))),
          ...views.map(v => h('tr', null, h('td', null, badge('view', 'view')),
            h('td', null, h('button', {type: 'button', class: 'link', onclick: () => open('view', v.name)}, v.name)))),
        ])) : null);
    }},
    {id: 'governance', label: 'Governance', render: () => governancePanel(target)},
    {id: 'grants', label: 'Grants', render: () => grantsPanel(target, false)},
    {id: 'audit', label: 'Audit', render: () => auditPanel(target)},
  ];
}

// ---- tables ----

function tableTabs(target) {
  const loaded = once(() => api.table(target.catalog, target.namespace, target.name));
  const withTable = render => async () => render(await loaded());
  return [
    {id: 'overview', label: 'Overview', render: withTable(tableOverview)},
    {id: 'schema', label: 'Schema', render: withTable(m => schemaPanel(m.metadata))},
    {id: 'partitioning', label: 'Partitioning & sort', render: withTable(m => partitioning(m.metadata))},
    {id: 'snapshots', label: 'Snapshots', render: withTable(m => snapshots(m.metadata))},
    {id: 'history', label: 'Metadata history', render: withTable(history)},
    {id: 'health', label: 'Health', render: () => tableHealthPanel(target)},
    {id: 'governance', label: 'Governance', render: () => governancePanel(target)},
    {id: 'grants', label: 'Grants', render: () => grantsPanel(target, false)},
    {id: 'audit', label: 'Audit', render: () => auditPanel(target)},
  ];
}

function tableOverview(loaded) {
  const m = loaded.metadata;
  const current = (m.snapshots || []).find(s => s['snapshot-id'] === m['current-snapshot-id']);
  const v = m['format-version'];
  return h('div', null,
    facts([
      ['Format version', badge('v' + v, 'v' + v)],
      ['Table UUID', mono(m['table-uuid'])],
      ['Location', mono(m.location)],
      ['Metadata location', mono(loaded['metadata-location'])],
      ['Current snapshot', current ? h('span', null, mono(String(current['snapshot-id'])), ' · ',
        when(current['timestamp-ms']), ' · ', (current.summary || {}).operation || '') : 'none'],
      ['Last updated', when(m['last-updated-ms'])],
      ['Last sequence number', String(m['last-sequence-number'] ?? '')],
      v >= 3 ? ['Next row id', String(m['next-row-id'] ?? '')] : null,
      ['Current schema', 'id ' + m['current-schema-id'] + ' (' + (m.schemas || []).length + ' in all)'],
      ['Default partition spec', 'id ' + m['default-spec-id']],
      ['Default sort order', 'id ' + m['default-sort-order-id']],
      ['Snapshots', String((m.snapshots || []).length)],
    ]),
    loaded.credentialsDiscarded ? h('p', {class: 'notice'}, 'The catalog vended storage credentials with this load '
      + '(it vends by default, D21). The console does not use them and discarded them unread.') : null,
    section('Properties', properties(m.properties)));
}

function typeLabel(type) {
  if (typeof type === 'string') return type;
  if (type.type === 'struct') return 'struct';
  if (type.type === 'list') return 'list<' + typeLabel(type.element) + '>';
  if (type.type === 'map') return 'map<' + typeLabel(type.key) + ', ' + typeLabel(type.value) + '>';
  return JSON.stringify(type);
}

function fieldIds(type, into = new Set()) {
  if (!type || typeof type === 'string') return into;
  if (type.type === 'struct' || type.fields) {
    for (const f of type.fields || []) {
      into.add(f.id);
      fieldIds(f.type, into);
    }
  } else if (type.type === 'list') {
    into.add(type['element-id']);
    fieldIds(type.element, into);
  } else if (type.type === 'map') {
    into.add(type['key-id']);
    into.add(type['value-id']);
    fieldIds(type.key, into);
    fieldIds(type.value, into);
  }
  return into;
}

function schemaRows(type, depth, rows, previousIds, identifiers) {
  const row = (id, name, t, required, doc, extra) => rows.push(h('tr', {class: depth ? 'nested' : null},
    h('td', {class: 'num'}, String(id)),
    h('td', null, h('span', {class: 'indent indent-' + Math.min(depth, 8)}), h('span', {class: 'field-name'}, name),
      identifiers.has(id) ? badge('identifier', 'muted') : null,
      previousIds && !previousIds.has(id) ? badge('added', 'new') : null),
    h('td', null, mono(typeLabel(t))),
    h('td', null, required ? badge('required', 'required') : h('span', {class: 'muted'}, 'optional')),
    h('td', {class: 'wrap'}, doc || '', extra)));
  if (!type || typeof type === 'string') return;
  if (type.type === 'struct' || type.fields) {
    for (const f of type.fields || []) {
      const defaults = [];
      if (f['initial-default'] !== undefined) defaults.push(' initial default ' + JSON.stringify(f['initial-default']));
      if (f['write-default'] !== undefined) defaults.push(' write default ' + JSON.stringify(f['write-default']));
      row(f.id, f.name, f.type, f.required, f.doc, defaults.length ? h('span', {class: 'muted'}, defaults.join(';')) : null);
      schemaRows(f.type, depth + 1, rows, previousIds, identifiers);
    }
  } else if (type.type === 'list') {
    row(type['element-id'], 'element', type.element, type['element-required']);
    schemaRows(type.element, depth + 1, rows, previousIds, identifiers);
  } else if (type.type === 'map') {
    row(type['key-id'], 'key', type.key, true);
    schemaRows(type.key, depth + 1, rows, previousIds, identifiers);
    row(type['value-id'], 'value', type.value, type['value-required']);
    schemaRows(type.value, depth + 1, rows, previousIds, identifiers);
  }
}

/** One schema's fields; fields new since the previous schema are marked. */
function schemaTable(schemas, schema) {
  const ordered = [...schemas].sort((a, b) => a['schema-id'] - b['schema-id']);
  const index = ordered.findIndex(s => s['schema-id'] === schema['schema-id']);
  const previousIds = index > 0 ? fieldIds(ordered[index - 1]) : null;
  const rows = [];
  schemaRows(schema, 0, rows, previousIds, new Set(schema['identifier-field-ids'] || []));
  return table(['Id', 'Field', 'Type', 'Required', 'Doc'], rows, {class: 'grid schema'});
}

/** The current schema, and every earlier one by id. Shared by tables and views. */
function schemaPanel(m, currentId = m['current-schema-id']) {
  const schemas = m.schemas || [];
  const body = h('div');
  const pick = h('select', {'aria-label': 'Schema', onchange: () => showSchema(Number(pick.value))},
    [...schemas].sort((a, b) => b['schema-id'] - a['schema-id']).map(s => h('option', {value: s['schema-id'],
      selected: s['schema-id'] === currentId}, 'schema ' + s['schema-id'] + (s['schema-id'] === currentId ? ' (current)' : ''))));
  const showSchema = id => {
    const schema = schemas.find(s => s['schema-id'] === id);
    fill(body, h('p', {class: 'muted'}, (id === currentId ? 'Current schema. ' : 'An earlier schema. ')
      + 'Fields marked "added" are not in the schema before it.'), schemaTable(schemas, schema));
  };
  showSchema(currentId);
  return h('div', null, h('div', {class: 'toolbar'}, h('label', null, 'Schema ', pick),
    h('span', {class: 'muted'}, schemas.length + ' schema' + (schemas.length === 1 ? '' : 's'))), body);
}

function columnName(m, sourceId) {
  for (const schema of [...(m.schemas || [])].sort((a, b) => b['schema-id'] - a['schema-id'])) {
    const found = findField(schema, sourceId);
    if (found) return found;
  }
  return '#' + sourceId;
}

function findField(type, id, path = []) {
  if (!type || typeof type === 'string') return null;
  for (const f of type.fields || []) {
    if (f.id === id) return [...path, f.name].join('.');
    const inner = findField(f.type, id, [...path, f.name]);
    if (inner) return inner;
  }
  return null;
}

function partitioning(m) {
  const specs = [...(m['partition-specs'] || [])].sort((a, b) => b['spec-id'] - a['spec-id']);
  const orders = [...(m['sort-orders'] || [])].sort((a, b) => b['order-id'] - a['order-id']);
  return h('div', null,
    section('Partition specs', specs.map(spec => h('div', {class: 'sub'},
      h('h4', null, 'Spec ' + spec['spec-id'], spec['spec-id'] === m['default-spec-id'] ? badge('default', 'current') : null),
      spec.fields && spec.fields.length ? table(['Field id', 'Name', 'Source column', 'Transform'],
        spec.fields.map(f => h('tr', null, h('td', {class: 'num'}, String(f['field-id'] ?? '')), h('td', null, f.name),
          h('td', null, mono(columnName(m, f['source-id'] ?? (f['source-ids'] || [])[0]))), h('td', null, mono(f.transform)))))
        : h('p', {class: 'muted'}, 'Unpartitioned.')))),
    section('Sort orders', orders.map(order => h('div', {class: 'sub'},
      h('h4', null, 'Order ' + order['order-id'], order['order-id'] === m['default-sort-order-id'] ? badge('default', 'current') : null),
      order.fields && order.fields.length ? table(['Source column', 'Transform', 'Direction', 'Nulls'],
        order.fields.map(f => h('tr', null, h('td', null, mono(columnName(m, f['source-id'] ?? (f['source-ids'] || [])[0]))),
          h('td', null, mono(f.transform)), h('td', null, f.direction), h('td', null, f['null-order']))))
        : h('p', {class: 'muted'}, 'Unsorted.')))));
}

const SUMMARY_KEYS = ['added-records', 'deleted-records', 'total-records', 'added-data-files', 'deleted-data-files',
  'total-data-files', 'added-delete-files', 'total-delete-files'];

function snapshots(m) {
  const list = [...(m.snapshots || [])].sort((a, b) => b['timestamp-ms'] - a['timestamp-ms']
    || b['sequence-number'] - a['sequence-number']);
  const refsBySnapshot = new Map();
  for (const [name, ref] of Object.entries(m.refs || {})) {
    if (!refsBySnapshot.has(ref['snapshot-id'])) refsBySnapshot.set(ref['snapshot-id'], []);
    refsBySnapshot.get(ref['snapshot-id']).push([name, ref.type]);
  }
  const refs = Object.entries(m.refs || {}).sort(([a], [b]) => (a === 'main' ? -1 : b === 'main' ? 1 : a.localeCompare(b)));
  return h('div', null,
    section('Refs: branches and tags', refs.length ? table(['Name', 'Type', 'Snapshot', 'Retention'],
      refs.map(([name, ref]) => h('tr', null, h('td', null, mono(name)), h('td', null, badge(ref.type, ref.type)),
        h('td', null, mono(String(ref['snapshot-id']))),
        h('td', {class: 'muted'}, [
          ref['max-ref-age-ms'] ? 'max ref age ' + ref['max-ref-age-ms'] + ' ms' : null,
          ref['max-snapshot-age-ms'] ? 'max snapshot age ' + ref['max-snapshot-age-ms'] + ' ms' : null,
          ref['min-snapshots-to-keep'] ? 'keep ' + ref['min-snapshots-to-keep'] : null,
        ].filter(Boolean).join(', '))))) : h('p', {class: 'muted'}, 'No refs.')),
    section('Snapshots, newest first', list.length ? table(['', 'Snapshot', 'Parent', 'Time', 'Operation', 'Summary', 'Schema'],
      list.map(s => {
        const summary = s.summary || {};
        const current = s['snapshot-id'] === m['current-snapshot-id'];
        const rest = Object.entries(summary).filter(([k]) => k !== 'operation');
        return h('tr', {class: current ? 'current-row' : null},
          h('td', null, current ? badge('current', 'current') : null),
          h('td', {class: 'ids'}, mono(String(s['snapshot-id'])), (refsBySnapshot.get(s['snapshot-id']) || [])
            .map(([name, type]) => badge(name, type))),
          h('td', {class: 'ids'}, s['parent-snapshot-id'] ? mono(String(s['parent-snapshot-id'])) : h('span', {class: 'muted'}, '–')),
          h('td', {class: 'nowrap'}, when(s['timestamp-ms'])),
          h('td', null, badge(summary.operation || '?', 'op')),
          h('td', null, h('span', {class: 'summary'}, SUMMARY_KEYS.filter(k => summary[k] !== undefined)
            .map(k => k + ' ' + summary[k]).join(' · ')),
          rest.length ? h('details', null, h('summary', null, 'all ' + rest.length),
            table(['Key', 'Value'], rest.map(([k, v]) => h('tr', null, h('td', null, mono(k)), h('td', {class: 'wrap'}, v)))))
            : null),
          h('td', {class: 'num'}, String(s['schema-id'] ?? '')));
      })) : h('p', {class: 'muted'}, 'No snapshots yet.')));
}

function history(loaded) {
  const m = loaded.metadata;
  const log = [...(m['metadata-log'] || [])].sort((a, b) => b['timestamp-ms'] - a['timestamp-ms']);
  const snapshotLog = [...(m['snapshot-log'] || [])].sort((a, b) => b['timestamp-ms'] - a['timestamp-ms']);
  return h('div', null,
    section('Metadata files, newest first', table(['Time', 'Metadata file'], [
      h('tr', {class: 'current-row'}, h('td', {class: 'nowrap'}, when(m['last-updated-ms']), ' ', badge('current', 'current')),
        h('td', {class: 'wrap'}, mono(loaded['metadata-location']))),
      ...log.map(e => h('tr', null, h('td', {class: 'nowrap'}, when(e['timestamp-ms'])),
        h('td', {class: 'wrap'}, mono(e['metadata-file'])))),
    ])),
    section('Snapshot log (main), newest first', snapshotLog.length ? table(['Time', 'Snapshot'],
      snapshotLog.map(e => h('tr', null, h('td', {class: 'nowrap'}, when(e['timestamp-ms'])),
        h('td', null, mono(String(e['snapshot-id'])))))) : h('p', {class: 'muted'}, 'Empty.')));
}

// ---- views ----

function viewTabs(target) {
  const loaded = once(() => api.view(target.catalog, target.namespace, target.name));
  const withView = render => async () => render(await loaded());
  return [
    {id: 'overview', label: 'Overview', render: withView(viewOverview)},
    {id: 'versions', label: 'Versions & SQL', render: withView(l => viewVersions(l.metadata))},
    {id: 'schema', label: 'Schema', render: withView(l => {
      const m = l.metadata;
      const version = (m.versions || []).find(v => v['version-id'] === m['current-version-id']);
      return schemaPanel(m, version ? version['schema-id'] : undefined);
    })},
    {id: 'governance', label: 'Governance', render: () => governancePanel(target)},
    {id: 'grants', label: 'Grants', render: () => grantsPanel(target, false)},
    {id: 'audit', label: 'Audit', render: () => auditPanel(target)},
  ];
}

function viewOverview(loaded) {
  const m = loaded.metadata;
  return h('div', null,
    facts([
      ['Format version', badge('v' + m['format-version'], 'v' + m['format-version'])],
      ['View UUID', mono(m['view-uuid'])],
      ['Location', mono(m.location)],
      ['Metadata location', mono(loaded['metadata-location'])],
      ['Current version', 'id ' + m['current-version-id'] + ' (' + (m.versions || []).length + ' in all)'],
    ]),
    section('Properties', properties(m.properties)));
}

function viewVersions(m) {
  const versions = [...(m.versions || [])].sort((a, b) => b['version-id'] - a['version-id']);
  return h('div', null, versions.map(v => {
    const current = v['version-id'] === m['current-version-id'];
    return h('article', {class: 'version' + (current ? ' current-version' : '')},
      h('h4', null, 'Version ' + v['version-id'], current ? badge('current', 'current') : null,
        h('span', {class: 'muted'}, ' · ' + when(v['timestamp-ms']) + ' · schema ' + v['schema-id'])),
      facts([
        ['Default namespace', mono((v['default-namespace'] || []).join('.'))],
        v['default-catalog'] ? ['Default catalog', v['default-catalog']] : null,
        ['Summary', Object.entries(v.summary || {}).map(([k, val]) => k + ' ' + val).join(' · ')],
      ]),
      (v.representations || []).map(rep => h('div', {class: 'sql'},
        h('div', {class: 'sql-head'}, badge(rep.type || 'sql', 'op'), ' ', rep.dialect || ''),
        h('pre', null, h('code', null, rep.sql || '')))));
  }));
}

// ---- governance (M7's routes, read only) ----

function levelLabel(level, target, names) {
  const [kind, id] = String(level).split(':');
  if (id === target) return h('span', null, 'this ' + kind);
  const name = names.get(id);
  return h('span', {class: 'muted', title: level}, 'inherited from ' + kind + ' ',
    name === undefined ? mono((id || '').slice(0, 8)) : mono(name));
}

/** Names for the ids of the levels above the target, read from each ancestor's own governance answer. */
async function ancestorNames(target, levels) {
  const names = new Map();
  const above = levels.filter(level => !String(level).endsWith(':' + target.id));
  if (!above.length) return names;
  const ancestors = [{kind: 'catalog', catalog: target.catalog, namespace: []}];
  const path = target.namespace || [];
  const depth = target.kind === 'namespace' ? path.length - 1 : path.length;
  for (let i = 1; i <= depth; i++) {
    ancestors.push({kind: 'namespace', catalog: target.catalog, namespace: path.slice(0, i)});
  }
  const answers = await Promise.allSettled(ancestors.map(a => api.governance(a)));
  answers.forEach((answer, i) => {
    if (answer.status === 'fulfilled') {
      const a = ancestors[i];
      names.set(answer.value.target.id, a.kind === 'catalog' ? a.catalog : a.namespace.join('.'));
    }
  });
  return names;
}

async function governancePanel(target) {
  const g = await api.governance(target);
  const here = g.target.id;
  const names = await ancestorNames({...target, id: here}, [...(g.holds || []), ...(g.attributes || [])].map(x => x.level))
    .catch(() => new Map());
  const effective = g.effective || {};
  const freeze = effective['dr-freeze'];
  const holds = g.holds || [];
  const shown = ['classification', 'owner', 'retention-class', 'regulated', 'row-column-policy'];
  return h('div', null,
    h('div', {class: 'status-row'},
      freeze ? h('div', {class: 'status status-frozen'}, h('strong', null, 'DR freeze in force'), h('div', null, freeze))
        : h('div', {class: 'status'}, h('strong', null, 'No DR freeze'), h('div', {class: 'muted'}, 'Commits are allowed.')),
      holds.length ? h('div', {class: 'status status-held'}, h('strong', null, holds.length + ' legal hold' + (holds.length > 1 ? 's' : '')),
        h('div', null, 'Snapshots cannot be removed; it cannot be dropped or purged.'))
        : h('div', {class: 'status'}, h('strong', null, 'No legal hold'))),
    section('Effective values', facts(shown.map(name => [name, effective[name] ?? null]))),
    section('Legal holds', holds.length ? table(['Reason', 'Level', 'Placed by', 'Placed at', 'Id'],
      holds.map(hold => h('tr', null, h('td', {class: 'wrap'}, hold.reason), h('td', null, levelLabel(hold.level, here, names)),
        h('td', null, hold['placed-by']), h('td', {class: 'nowrap'}, when(hold['placed-at'])), h('td', null, mono(hold.id)))))
      : h('p', {class: 'muted'}, 'None.')),
    section('Attributes, nearest level first', (g.attributes || []).length ? table(['Attribute', 'Value', 'Level', 'Provenance', 'Set by', 'Set at'],
      g.attributes.map(a => h('tr', null, h('td', null, mono(a.attribute)), h('td', {class: 'wrap'}, a.value),
        h('td', null, levelLabel(a.level, here, names)), h('td', {class: 'wrap muted'}, a.provenance),
        h('td', null, a['set-by']), h('td', {class: 'nowrap'}, when(a['set-at'])))))
      : h('p', {class: 'muted'}, 'No attributes set here or above.')));
}

// ---- grants (M5's management API) ----

function securableLabel(s) {
  if (!s) return '';
  const path = [...(s.namespace || []), ...(s.name ? [s.name] : [])].join('.');
  return h('span', null, badge(s.type, s.type), ' ', path ? mono(path) : h('span', {class: 'muted'}, 'whole catalog'),
    s.state && s.state !== 'active' ? badge(s.state, 'muted') : null);
}

function grantRows(catalog, grants, refresh, status) {
  return grants.map(g => h('tr', null,
    h('td', null, securableLabel(g.securable)),
    h('td', null, mono(g.group)),
    h('td', null, badge(g.privilege, 'priv')),
    h('td', null, g['granted-by']),
    h('td', {class: 'nowrap'}, when(g['granted-at'])),
    h('td', null, h('button', {type: 'button', class: 'danger small', onclick: async () => {
      if (!confirm(`Revoke ${g.privilege} from ${g.group}?`)) return;
      const spec = {type: g.securable.type};
      if (g.securable.type !== 'catalog') spec.namespace = g.securable.namespace;
      if (g.securable.name) spec.name = g.securable.name;
      try {
        await api.revoke(catalog, spec, g.group, g.privilege);
        fill(status, h('p', {class: 'ok'}, `Revoked ${g.privilege} from ${g.group}.`));
        refresh();
      } catch (error) {
        fill(status, failure(error));
      }
    }}, 'Revoke'))));
}

const GRANT_HEADERS = ['On', 'Group', 'Privilege', 'Granted by', 'Granted at', ''];

async function grantsPanel(target, byGroup) {
  const status = h('div', {class: 'status-line', 'aria-live': 'polite'});
  const onObject = h('div');
  const refresh = () => load(onObject, async () => {
    const grants = await api.grantsOn(target);
    return grants.length ? table(GRANT_HEADERS, grantRows(target.catalog, grants, refresh, status))
      : h('p', {class: 'muted'}, 'No grants on this ' + target.kind + ' itself.');
  });
  refresh();

  const group = h('input', {type: 'text', placeholder: 'group, e.g. demo-analysts', 'aria-label': 'Group', required: true});
  const privilege = h('select', {'aria-label': 'Privilege'}, PRIVILEGES.map(p => h('option', {value: p}, p)));
  const form = h('form', {class: 'inline-form', onsubmit: async event => {
    event.preventDefault();
    fill(status, h('p', {class: 'loading'}, 'Granting…'));
    try {
      await api.grant(target, group.value.trim(), privilege.value);
      fill(status, h('p', {class: 'ok'}, `Granted ${privilege.value} to ${group.value.trim()}.`));
      refresh();
    } catch (error) {
      fill(status, failure(error));
    }
  }}, h('span', null, 'Grant'), privilege, h('span', null, 'on this ' + target.kind + ' to'), group,
  h('button', {type: 'submit', class: 'primary'}, 'Grant'));

  const mine = h('div');
  load(mine, async () => {
    const grants = await api.grantsOfGroups(target.catalog, []);
    return grants.length ? table(GRANT_HEADERS.slice(0, 5), grants.map(g => h('tr', null, h('td', null, securableLabel(g.securable)),
      h('td', null, mono(g.group)), h('td', null, badge(g.privilege, 'priv')), h('td', null, g['granted-by']),
      h('td', {class: 'nowrap'}, when(g['granted-at'])))))
      : h('p', {class: 'muted'}, 'Your groups hold no grant in this catalog.');
  });

  return h('div', null,
    section('Grant or revoke', h('p', {class: 'muted'}, 'The catalog decides: a refusal, including a separation-of-duties '
      + 'conflict, is shown exactly as the server answers it.'), form, status),
    section('Grants on this ' + target.kind, h('p', {class: 'muted'}, 'Grants made on this ' + target.kind
      + ' itself. Grants on the levels above it apply too; they are listed there.'), onObject),
    byGroup ? section('Grants by group', groupLookup(target, status)) : null,
    section('Grants of your own groups in this catalog', mine));
}

function groupLookup(target, status) {
  const groups = h('input', {type: 'text', placeholder: 'groups, comma-separated', 'aria-label': 'Groups'});
  const out = h('div');
  const run = () => load(out, async () => {
    const names = groups.value.split(',').map(g => g.trim()).filter(Boolean);
    if (!names.length) return h('p', {class: 'muted'}, 'Name one or more groups.');
    const grants = await api.grantsOfGroups(target.catalog, names);
    return grants.length ? table(GRANT_HEADERS, grantRows(target.catalog, grants, run, status))
      : h('p', {class: 'muted'}, 'No grants for ' + names.join(', ') + ' in this catalog.');
  });
  return h('div', null, h('form', {class: 'inline-form', onsubmit: event => {
    event.preventDefault();
    run();
  }}, groups, h('button', {type: 'submit'}, 'List')), out);
}

// ---- audit trail ----

function actor(a) {
  if (!a) return h('span', {class: 'muted'}, '–');
  return h('span', null, h('strong', null, a.principal),
    a.endUser ? h('span', null, ' for ', h('strong', null, a.endUser)) : null,
    a.engine ? h('span', {class: 'muted'}, ' via ' + a.engine) : null);
}

function describe(type, d) {
  switch (type) {
    case 'commit': return `version ${d.before ? d.before.version : '–'} → ${d.after.version} on ${d.branch}; `
      + (Array.isArray(d.updates) ? d.updates.map(u => u.action).join(', ') : '');
    case 'read': return `loaded version ${d.served.version} of ${d.branch}`;
    case 'denial': return `refused ${d.operation}${d.privilege ? ' (needs ' + d.privilege + ')' : ''}: ${d.reason}`;
    case 'grant': return `${d.action.toLowerCase()} ${d.privilege} to ${d.group}`;
    case 'governance': return `${d.action.toLowerCase()} ${d.attribute}${d.value != null ? ' = ' + d.value : ''}`;
    case 'hold': return `hold ${d.action.toLowerCase()}: ${d.reason}`;
    case 'credential-issue': return `${d.access} credentials for ${d.locationPrefix}, until ${when(d.expiresAt)}`;
    case 'drop': return `dropped; purge after ${when(d.purgeAfter)}`;
    case 'undrop': return 'undropped';
    case 'purge': return 'purged';
    case 'rename': return `renamed from ${(d.fromPath || []).join('.')}`;
    case 'namespace': return `namespace ${String(d.action).toLowerCase()}`;
    case 'audit-read': return `read ${d.returned} audit records`;
    default: return '';
  }
}

async function auditPanel(target) {
  const out = h('div');
  const limit = h('select', {'aria-label': 'How many', onchange: () => run()},
    [25, 50, 100, 200].map(n => h('option', {value: n, selected: n === 50}, 'latest ' + n)));
  const run = () => load(out, async () => {
    const body = await api.recentAudit(target, limit.value);
    const w = body.window;
    const records = body.records || [];
    return h('div', null,
      records.length ? table(['Time', 'Type', 'Who', 'What', ''], records.map(r => {
        const type = String(r.type || '').replace(/^nuna\.catalog\./, '');
        const d = r.data || {};
        return h('tr', {class: type === 'denial' ? 'denied-row' : null},
          h('td', {class: 'nowrap'}, when(r.time)),
          h('td', null, badge(type, type === 'denial' ? 'refused' : 'op')),
          h('td', null, actor(d.actor)),
          h('td', {class: 'wrap'}, describe(type, d)),
          h('td', null, h('details', null, h('summary', null, 'record'), h('pre', {class: 'raw'}, JSON.stringify(r, null, 2)))));
      })) : h('p', {class: 'muted'}, 'No audit records about this ' + target.kind + ' in the scanned window.'),
      h('p', {class: 'muted window'}, `Read from ${w.topic}, partition ${w.partition}, offsets ${w['from-offset']} to `
        + `${w['to-offset']} (scan limit ${w['scan-limit']}). `
        + (w.complete ? 'That reaches the oldest record the topic still holds.'
          : 'Older records may exist beyond the scanned window: search the SIEM for the full trail.')));
  });
  run();
  return h('div', null, h('div', {class: 'toolbar'}, limit,
    h('button', {type: 'button', onclick: () => run()}, 'Refresh'),
    h('span', {class: 'muted'}, 'Newest first. Reading the trail is itself audited.')), out);
}

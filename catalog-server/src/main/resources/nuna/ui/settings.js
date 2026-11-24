// The settings a person may change from the console (D45, amended 2026-11-22; item 141): on a table, the write
// settings engines or nuna honour; on a namespace, its comment. Each has a label, what it does, and a value checked
// before it is sent. Every property is shown read-only beside them, whoever set it: an engine or tool may set any key,
// and the console takes no key it does not list. A change goes through the Iceberg REST route an engine uses (a
// table's commit, a namespace's properties), so every commit rule, the privilege and the audit trail apply to it.

import {code, dialogForm, field, fill, fmt, granted, h, icon, muted, openDialog, select, sizeWords, tbl, text} from './dom.js';
import {objectHref, pageHref} from './route.js';

const MB = 1024 * 1024;
const GB = 1024 * MB;

/** The levels each Parquet codec takes. */
const LEVELS = {zstd: [1, 22], gzip: [1, 9]};
const UNITS = {bytes: 1, b: 1, kb: 1024, mb: MB, gb: GB};

const WRITE_MODE = (what, verb) => ({
  key: `write.${what}.mode`, label: `How ${verb} rewrite rows`, kind: 'enum', choices: ['copy-on-write', 'merge-on-read'],
  def: 'copy-on-write',
  explain: `How engines carry out an SQL ${what.toUpperCase()}. Copy-on-write rewrites every data file a changed row is in: `
    + 'slower writes, fastest reads. Merge-on-read writes small delete files beside them instead: fast writes, and reads '
    + 'that merge the deletes until compaction folds them in. Spark honours it; Trino always merges on read.',
  check: (value, ctx) => value === 'merge-on-read' && ctx.formatVersion < 2
    ? 'Merge-on-read needs format version 2 or later, and this table is version 1.' : null,
});

/** A table's settings, in the order they are shown. */
export const TABLE_SETTINGS = [
  {key: 'write.format.default', label: 'File format of new data', kind: 'enum', choices: ['parquet', 'avro', 'orc'],
    def: 'parquet', explain: 'The format engines write new data files in. Files already written keep theirs, so a table may '
      + 'hold more than one. Parquet is the one every engine here reads fastest.'},
  {key: 'write.parquet.compression-codec', label: 'Parquet compression', kind: 'enum',
    choices: ['zstd', 'snappy', 'gzip', 'uncompressed'], def: 'zstd',
    explain: 'How engines compress the Parquet files they write. zstd, Iceberg\'s default, packs tightest for its speed; '
      + 'snappy is faster and larger; gzip is slower and smaller. lz4, brotli and lzo are not offered: engines ship no '
      + 'codec for brotli or lzo, and Parquet\'s lz4 is a framing other readers do not read.'},
  {key: 'write.parquet.compression-level', label: 'Parquet compression level', kind: 'int', def: null,
    defWords: 'the codec\'s own',
    explain: 'How hard zstd or gzip works: higher packs smaller and writes slower. snappy and uncompressed take no level.',
    range: ctx => LEVELS[ctx.codec],
    closed: ctx => LEVELS[ctx.codec] ? null : `${ctx.codec} takes no level.`},
  {key: 'write.target-file-size-bytes', label: 'Target data file size', kind: 'size', min: 16 * MB, max: 4 * GB,
    def: String(512 * MB), explain: 'The size engines aim for when they write a data file. Larger files mean fewer to plan '
      + 'and open; smaller ones suit tables written a little at a time. Compaction aims for its own target, the maintenance '
      + 'policy\'s compaction.target-file-size-bytes.', policy: true},
  {key: 'write.distribution-mode', label: 'How rows are spread before writing', kind: 'enum',
    choices: ['none', 'hash', 'range'], def: null, defWords: 'the engine\'s choice',
    explain: 'How Spark and Flink shuffle rows before writing. hash sends each partition\'s rows to one writer, so a '
      + 'partition gets few files; range also sorts them by the table\'s sort order; none writes rows where they are, '
      + 'fastest, with many small files in a partitioned table.'},
  WRITE_MODE('delete', 'deletes'),
  WRITE_MODE('update', 'updates'),
  WRITE_MODE('merge', 'merges'),
  {key: 'write.metadata.metrics.default', label: 'Column statistics kept', kind: 'enum',
    choices: ['none', 'counts', 'truncate(16)', 'full'], def: 'truncate(16)',
    explain: 'What engines record of each column in every data file, which lets a query skip files. counts keeps value and '
      + 'null counts; truncate(16) adds lower and upper bounds cut to 16 characters; full keeps whole bounds; none keeps '
      + 'nothing, and no file is skipped.'},
  {key: 'write.metadata.previous-versions-max', label: 'Earlier metadata files listed', kind: 'int', min: 1, max: 10000,
    def: '100', explain: 'The catalog writes a metadata file for every commit and lists this many earlier ones in the '
      + 'table\'s metadata log. A file that falls off the list is removed by the orphan scan. The catalog\'s commit journal '
      + 'keeps every commit whatever this says.'},
];

/** A namespace's settings. */
export const NAMESPACE_SETTINGS = [
  {key: 'comment', label: 'Description', kind: 'text', max: 1000, def: null, defWords: 'none',
    explain: 'What the namespace holds, in a sentence. Engines show it: Spark in DESCRIBE NAMESPACE, Trino as the schema\'s '
      + 'comment.'},
];

const PATHS = ['write.data.path', 'write.metadata.path', 'write.object-storage.path', 'write.folder-storage.path'];

/** Why a property the console does not list is read-only: a key it knows, or one set by an engine or tool. */
function tableNote(key, target) {
  if (PATHS.includes(key)) return 'Where engines write files. The catalog keeps it inside the table\'s location, which it assigns.';
  if (key === 'encryption.key-id') return 'Fixed when the table was created.';
  if (key === 'write.metadata.delete-after-commit.enabled') {
    return 'Changes nothing here: the catalog writes every metadata file, and neither it nor an engine deletes the files '
      + 'that fall off the log. The orphan scan removes them.';
  }
  if (key.startsWith('history.expire.')) {
    return ['Snapshot expiry follows the ', h('a', {href: objectHref(target, 'maintenance')}, 'maintenance policy'),
      ', not this property.'];
  }
  return null;
}

function namespaceNote(key, target) {
  if (key === 'location') return 'Changes nothing: the catalog assigns every table\'s location itself, never from its namespace.';
  if (key === 'owner') {
    return ['Not the owner the catalog enforces: that is the ', h('a', {href: objectHref(target, 'access')},
      'governance owner'), '.'];
  }
  return null;
}

// ---- values ----

/** A size in words a person reads, with its bytes. */
const sizeOf = bytes => `${sizeWords(bytes)} (${fmt(bytes)} bytes)`;

/** A size typed as bytes, or as a number and KB, MB or GB (each 1,024 of the one below). */
function parseSize(raw) {
  const m = /^(\d+(?:\.\d+)?)\s*(bytes|b|kb|mb|gb)?$/i.exec(raw);
  if (!m) return null;
  return Math.round(Number(m[1]) * (m[2] ? UNITS[m[2].toLowerCase()] : 1));
}

/** The range an integer setting takes for this object, or null when it takes none. */
const rangeOf = (s, ctx) => s.range ? s.range(ctx) : [s.min, s.max];

/** What a value typed or chosen becomes: {value} to send, or {error} to show without sending anything. */
export function checkValue(s, raw, ctx) {
  const typed = String(raw === undefined || raw === null ? '' : raw).trim();
  let value = typed;
  if (s.kind === 'enum') {
    if (!s.choices.includes(typed)) return {error: `Choose one of ${s.choices.join(', ')}.`};
  } else if (s.kind === 'int') {
    const [min, max] = rangeOf(s, ctx);
    if (!/^\d+$/.test(typed) || Number(typed) < min || Number(typed) > max) {
      return {error: `A whole number from ${fmt(min)} to ${fmt(max)}.`};
    }
    value = String(Number(typed));
  } else if (s.kind === 'size') {
    const bytes = parseSize(typed);
    if (bytes === null || bytes < s.min || bytes > s.max) {
      return {error: `A size from ${sizeWords(s.min)} to ${sizeWords(s.max)}, such as 512 MB.`};
    }
    value = String(bytes);
  } else if (s.kind === 'text') {
    if (!typed) return {error: 'Write the description, or reset it to have none.'};
    if (typed.length > s.max) return {error: `At most ${fmt(s.max)} characters; this has ${fmt(typed.length)}.`};
  }
  const more = s.check ? s.check(value, ctx) : null;
  return more ? {error: more} : {value};
}

/** A value as it is shown: a size in words, anything else as it was set. */
function shown(s, value) {
  if (s.kind === 'size' && /^\d+$/.test(value)) return [code(value), ' ', muted(sizeWords(Number(value)))];
  return code(value);
}

function defaultWords(s) {
  if (s.def === null) return s.defWords;
  return s.kind === 'size' ? sizeOf(Number(s.def)) : s.def;
}

// ---- the tab ----

/**
 * The properties of a table or namespace: the settings the console may change, then every property as it is set.
 * `write(updates, removals)` sends a change and resolves to the catalog's answer; `reload` redraws the tab after it.
 */
export function settingsTab({target, settings, props, ctx, write, reload, needs}) {
  const listed = new Map(settings.map(s => [s.key, s]));
  const rows = settings.map(s => {
    const set = Object.prototype.hasOwnProperty.call(props, s.key);
    const closed = s.closed ? s.closed(ctx) : null;
    return h('tr', {'data-setting': s.key},
      h('td', {class: 'setting'}, h('b', null, s.label), h('br'), code(s.key),
        h('p', {class: 'fine'}, s.explain, s.policy ? [' ', h('a', {href: objectHref(target, 'maintenance')}, 'See the policy'), '.'] : null)),
      h('td', {class: 'setting-v'}, set ? [shown(s, props[s.key]), ' ', muted('· set')] : muted(`${defaultWords(s)} · default`)),
      h('td', {class: 'setting-acts'}, closed ? h('span', {class: 'fine'}, closed)
        : h('button', {class: 'btn sm', type: 'button', 'data-act': 'change', onclick: () => change(s, props, ctx, target, write, reload)},
          'Change'),
      set ? h('button', {class: 'btn sm ghost', type: 'button', 'data-act': 'reset', onclick: () => reset(s, target, write, reload)},
        'Reset') : null));
  });
  const keys = Object.keys(props).sort();
  const noteFor = target.kind === 'table' ? tableNote : namespaceNote;
  const all = keys.map(k => h('tr', {'data-key': k},
    h('td', {class: 'name'}, k), h('td', {class: 'name wrap'}, props[k]),
    h('td', {class: 'prop-note fine'}, listed.has(k) ? ['A setting: ', h('b', null, listed.get(k).label), ', above.']
      : noteFor(k, target) || 'Set outside the console, by an engine or tool. Read-only here.')));
  const where = target.kind === 'table' ? 'the table' : 'the namespace';
  return [
    h('div', {class: 'panel', id: 'settings'}, h('h2', null, 'Settings ', h('span', {class: 'meta'}, `what the console may change on ${where}`)),
      tbl('settings stack-line', ['Setting', 'Value', ''], rows),
      h('p', {class: 'fine'}, 'A change is ', needs, ', and is audited like any other. Needs ', code('modify'), ' here.')),
    h('div', {class: 'panel', id: 'all-properties'}, h('h2', null, 'Every property ', h('span', {class: 'meta'}, `Iceberg REST, on ${where}`)),
      keys.length ? tbl('props-tbl stack-line', ['Key', 'Value', ''], all) : h('p', {class: 'muted'}, 'No property is set.'),
      h('p', {class: 'fine'}, 'Engines and tools may set any key; the console changes only the settings above.')),
    target.kind === 'table' ? h('div', {class: 'notice'}, icon('maint'), h('span', null, h('b', null, 'Snapshot expiry, compaction and orphan removal'),
      ' are set in the maintenance policy, not in properties: ', h('a', {href: objectHref(target, 'maintenance')}, 'this table\'s policy'), ', and ',
      h('a', {href: pageHref('maintenance', target.catalog)}, 'the catalog\'s'), '.')) : null,
  ];
}

function input(s, current, ctx) {
  if (s.kind === 'enum') return select('value', s.choices, s.choices.includes(current) ? current : s.choices[0]);
  const shownNow = s.kind === 'size' && /^\d+$/.test(current || '') ? sizeWords(Number(current)) : current;
  return text('value', s.kind === 'size' ? 'such as 512 MB' : s.kind === 'int' ? rangeOf(s, ctx).map(fmt).join(' to ') : '',
    shownNow, s.kind === 'text' ? {maxlength: String(s.max)} : {inputmode: s.kind === 'int' ? 'numeric' : null});
}

function hintOf(s, ctx) {
  if (s.kind === 'size') return `From ${sizeWords(s.min)} to ${sizeWords(s.max)}; KB, MB and GB are 1,024 of the one below. `
    + `Default ${sizeOf(Number(s.def))}.`;
  if (s.kind === 'int') return `A whole number from ${rangeOf(s, ctx).map(fmt).join(' to ')}. Default ${defaultWords(s)}.`;
  if (s.kind === 'text') return `At most ${fmt(s.max)} characters.`;
  return `Default ${defaultWords(s)}.`;
}

function change(s, props, ctx, target, write, reload) {
  const name = [...target.namespace, ...(target.name ? [target.name] : [])].join('.');
  openDialog(`${s.label} of ${name}`, h('div', null, h('p', null, s.explain), h('p', {class: 'fine'}, code(s.key)),
    dialogForm([field(s.label, input(s, props[s.key], ctx), hintOf(s, ctx))], 'Set it', async (form, out) => {
      const checked = checkValue(s, form.elements.value.value, ctx);
      if (checked.error) {
        fill(out, h('p', {class: 'fine grant-hint'}, checked.error));
        return;
      }
      const answer = await write({[s.key]: checked.value}, []);
      fill(out, granted(`${s.key} is ${checked.value}`, answer));
      reload();
    })));
}

function reset(s, target, write, reload) {
  openDialog(`Reset ${s.label}`, dialogForm(h('p', null, 'The property ', code(s.key), ' is removed, and the default applies '
    + `again: ${defaultWords(s)}.`),
  'Reset it', async (form, out) => {
    const answer = await write({}, [s.key]);
    fill(out, granted(`${s.key} is reset`, answer));
    reload();
  }));
}

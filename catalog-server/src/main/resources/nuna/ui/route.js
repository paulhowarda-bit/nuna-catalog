// Where the console is: kept in the URL fragment, so any page or tab can be reloaded or shared. A namespace's
// levels are joined by the unit separator 0x1F, as the catalog's own paths join them. `r` names the catalog's branch
// or tag a browse page reads (item 109): links within the catalog's, a namespace's, a table's and a view's pages keep
// it; every other page drops it. A ref's own page is k=ref with its name in n.

export const SEP = '\u001f';

// Links from before item 192 keep working: the deployment's Health page is Service now, and a table's Health tab
// Maintenance.
const OLD_PAGES = {health: 'service'};
const OLD_TABS = {health: 'maintenance'};

/** {page, c, k, ns: [], n, tab, g, r} from the fragment. A catalog with no page is that catalog's browse page. */
export function parse() {
  const p = new URLSearchParams(location.hash.slice(1));
  const ns = p.get('ns') ? p.get('ns').split(SEP) : [];
  const page = OLD_PAGES[p.get('p')] || p.get('p') || null;
  return {
    page,
    c: p.get('c') || null,
    k: p.get('k') || (ns.length ? 'namespace' : 'catalog'),
    ns,
    n: p.get('n') || null,
    tab: (!page && OLD_TABS[p.get('tab')]) || p.get('tab') || null,
    g: p.get('g') || null,
    r: p.get('r') || null,
  };
}

export function href(o) {
  const p = new URLSearchParams();
  if (o.page) p.set('p', o.page);
  if (o.c) p.set('c', o.c);
  if (o.k && o.k !== 'catalog') p.set('k', o.k);
  if (o.ns && o.ns.length) p.set('ns', o.ns.join(SEP));
  if (o.n) p.set('n', o.n);
  if (o.tab) p.set('tab', o.tab);
  if (o.g) p.set('g', o.g);
  if (o.r && o.page !== 'service') p.set('r', o.r);
  return '#' + p.toString();
}

/** A catalog, namespace, table or view's page: {catalog, kind, namespace, name, ref}; through the ref when one is named. */
export const objectHref = (target, tab) => href({c: target.catalog, k: target.kind, ns: target.namespace, n: target.name, tab,
  r: target.ref});

export const pageHref = (page, c, tab, g) => href({page, c: page === 'service' ? null : c, tab, g});

export const catalogHref = (c, tab, r) => href({c, tab, r});

/** The target a route names, for the API. */
export const targetOf = r => ({catalog: r.c, kind: r.k, namespace: r.ns, name: r.n, ref: r.r});

/** The warehouse Iceberg REST reads a target through: the catalog, or `catalog@ref` for a branch or tag (item 104). */
export const warehouseOf = target => target.ref ? `${target.catalog}@${target.ref}` : target.catalog;

/** A dotted path for people: catalog-relative, with the name last. */
export const dotted = target => [...(target.namespace || []), ...(target.name ? [target.name] : [])].join('.');

// ---- what the console has seen, for "Go to a path" ----

const seen = new Map();

/** Remembers a namespace, table or view the console has listed, so Go to a path can offer it. */
export function remember(target) {
  const key = target.catalog + SEP + target.kind + SEP + dotted(target);
  if (!seen.has(key)) seen.set(key, {kind: target.kind, catalog: target.catalog, namespace: target.namespace, name: target.name || null});
}

export function remembered(catalog) {
  return [...seen.values()].filter(x => x.catalog === catalog);
}

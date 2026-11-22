// Where the console is: kept in the URL fragment, so any page or tab can be reloaded or shared. A namespace's
// levels are joined by the unit separator 0x1F, as the catalog's own paths join them.

export const SEP = '\u001f';

/** {page, c, k, ns: [], n, tab, g} from the fragment. A catalog with no page is that catalog's browse page. */
export function parse() {
  const p = new URLSearchParams(location.hash.slice(1));
  const ns = p.get('ns') ? p.get('ns').split(SEP) : [];
  return {
    page: p.get('p') || null,
    c: p.get('c') || null,
    k: p.get('k') || (ns.length ? 'namespace' : 'catalog'),
    ns,
    n: p.get('n') || null,
    tab: p.get('tab') || null,
    g: p.get('g') || null,
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
  return '#' + p.toString();
}

/** A catalog, namespace, table or view's page: {catalog, kind, namespace, name}. */
export const objectHref = (target, tab) => href({c: target.catalog, k: target.kind, ns: target.namespace, n: target.name, tab});

export const pageHref = (page, c, tab, g) => href({page, c: page === 'health' ? null : c, tab, g});

export const catalogHref = (c, tab) => href({c, tab});

/** The target a route names, for the API. */
export const targetOf = r => ({catalog: r.c, kind: r.k, namespace: r.ns, name: r.n});

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

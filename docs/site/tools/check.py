#!/usr/bin/env python3
"""Check nuna docs pages against the writers' contract (docs/site/DESIGN.md, sections 4 and 5).

    python docs/site/tools/check.py                  every page in docs/site (tools/, assets/, img/ skipped)
    python docs/site/tools/check.py a.html b.html    just those pages
    python docs/site/tools/check.py --strict ...     warnings fail the run too
    python docs/site/tools/check.py --json ...       one JSON object with everything found

Errors break the contract and fail the run (exit 1). Warnings are wording to look at (DESIGN.md section 6) and links
to pages not written yet. The report ends with every gap, pending screenshot and pending diagram.
Standard library only.
"""
import html
import json
import os
import re
import sys
from html.parser import HTMLParser

SITE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {'tools', 'assets', 'img'}
VOID = {'meta', 'link', 'img', 'br', 'hr', 'input', 'source', 'col', 'area', 'base', 'wbr', 'embed', 'param', 'track'}

TYPES = {'home', 'section', 'tutorial', 'guide', 'concept', 'console-page', 'reference', 'api', 'command', 'runbook'}
NO_TAIL = {'home', 'section'}
H4_TYPES = {'api', 'reference'}

PLAIN = {'p', 'ul', 'ol', 'li', 'a', 'code', 'strong', 'em', 'kbd', 'var', 'dl', 'dt', 'dd', 'table', 'caption', 'thead',
         'tbody', 'tr', 'th', 'td', 'h2', 'h3', 'h4', 'summary', 'figcaption', 'img', 'abbr', 'sup', 'pre', 'svg'}
NEEDS_CLASS = {'b', 'span', 'div', 'aside', 'section', 'figure', 'details'}
SVG_CHILDREN = {'title', 'desc', 'g', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'path', 'text', 'tspan',
                'use'}
BLOCK = {'p', 'div', 'ul', 'ol', 'dl', 'table', 'pre', 'section', 'aside', 'figure', 'details', 'h2', 'h3', 'h4'}

BADGES = {'for', 'needs', 'audited', 'demo', 'internal', 'tested', 'untested'}
CALLOUTS = {'note', 'tip', 'warning', 'danger', 'demo', 'check'}
D_CLASSES = {'d-node', 'd-accent', 'd-group', 'd-store', 'd-edge', 'd-edge-dash', 'd-label', 'd-note', 'd-held',
             'd-frozen', 'd-ok', 'd-warn', 'd-bad'}
# element -> classes it may carry (a single class unless the rule says otherwise)
CLASSES = {
    'p': {'lead', 'doc-meta', 'callout-title', 'step-title', 'gap', 'shot-todo'},
    'b': {'ui'},
    'span': {'ui-path', 'method', 'badge', 'card-title', 'card-text'},
    'a': {'term'},
    'aside': {'callout'},
    'ol': {'steps'},
    'ul': {'cards'},
    'div': {'code', 'tabs', 'table-wrap'},
    'section': {'tab', 'endpoint', 'setting', 'see-also'},
    'table': {'params', 'responses'},
    'dl': {'facts', 'terms'},
    'details': {'doc-sources'},
    'figure': {'shot', 'diagram'},
}
LANGS = {'bash', 'powershell', 'json', 'yaml', 'properties', 'http', 'sql', 'python', 'java', 'scala', 'xml',
         'dockerfile', 'ini', 'text'}
METHODS = {'GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'}
TAB_GROUPS = {
    'client': [('console', 'Console'), ('curl', 'curl'), ('spark', 'Spark'), ('trino', 'Trino'), ('pyiceberg', 'PyIceberg'),
               ('kafka-connect', 'Kafka Connect'), ('singlestore', 'SingleStore'), ('java', 'Java')],
    'os': [('bash', 'Linux and macOS'), ('powershell', 'Windows (PowerShell)'), ('gitbash', 'Windows (Git Bash)')],
    'db': [('postgresql', 'PostgreSQL'), ('cockroachdb', 'CockroachDB'), ('oracle', 'Oracle'), ('mongodb', 'MongoDB')],
    'store': [('aws', 'AWS S3'), ('ceph', 'Ceph'), ('silo', 'Silo')],
}
FACTS = ['Type', 'Default', 'Allowed values', 'Environment variable', 'Helm', 'Read by', 'Restart', 'Since']
FIXED_IDS = {'What you need': 'what-you-need', 'What you did': 'what-you-did', 'Before you begin': 'before-you-begin',
             'If it fails': 'if-it-fails', 'Who sees it': 'who-sees-it', 'From the command line': 'from-the-command-line',
             'Endpoints': 'endpoints', 'Objects': 'objects', 'Synopsis': 'synopsis',
             'Environment variables': 'environment-variables', 'Exit status': 'exit-status', 'See also': 'see-also'}
ID_RE = re.compile(r'^[a-z0-9]+(-[a-z0-9]+)*$')
# global attributes every body element may carry
ATTRS = {
    'h2': {'id'}, 'h3': {'id'}, 'h4': {'id'},
    'a': {'href'}, 'img': {'src', 'alt', 'width', 'height', 'data-dark', 'loading'},
    'th': {'scope', 'colspan', 'rowspan'}, 'td': {'colspan', 'rowspan'}, 'abbr': {'title'}, 'ol': {'start'},
    'details': {'open'}, 'dt': {'id'},
    'figure': {'id', 'data-shot', 'data-shot-route', 'data-shot-user', 'data-shot-width', 'data-shot-state'},
    'div': {'data-lang', 'data-title', 'data-kind', 'data-group'},
    'section': {'data-tab', 'data-label'}, 'span': {'data-method'},
}
SVG_BANNED_ATTR = re.compile(r'^(style|fill|stroke|stroke-.*|font|font-.*|color|on.*)$')

WORDING = [
    (re.compile(r'\b(simply|easily|seamless(ly)?|powerful|robust|cutting-edge|world-class|best-in-class|blazing|'
                r'effortless(ly)?|leverage[sd]?|utili[sz]e[sd]?|enterprise-grade|out of the box|magic(al)?)\b', re.I),
     'hype word "{0}" (section 6.1 rule 3)'),
    (re.compile(r'\b(just|easy)\b', re.I), '"{0}": usually filler or hype (section 6.1 rule 3)'),
    (re.compile(r'(→|⇒|->|=>)'), 'arrow "{0}" in prose (section 6.1 rule 4)'),
    (re.compile(r'\bclick(s|ed|ing)?\b', re.I), '"{0}": say "select" (section 6.1 rule 11)'),
    (re.compile(r'\bD\d{1,3}\b'), 'decision row "{0}" (section 6.1 rule 9)'),
    (re.compile(r'\b(backlog|item \d+|worktree|Atelier|Paul)\b'), 'internal process word "{0}" (section 6.1 rule 9)'),
    (re.compile(r'\bshould\b(?! see\b)', re.I), '"{0}": say must, can or might (section 6.1 rule 7)'),
    (re.compile(r'\band/or\b|\be\.g\.|\bi\.e\.', re.I), '"{0}": write it out (section 6.1 rule 4)'),
    (re.compile(r'\blog(ged)? ?in\b|\blogin\b', re.I), '"{0}": say sign in (section 6.4)'),
    (re.compile(r'\bNuna\b|\bNUNA\b'), '"{0}": nuna is always lower case (section 6.4)'),
]
NO_WORDING = {'code', 'pre', 'kbd', 'var', 'svg', 'title', 'desc', 'text', 'tspan', 'b'}


class Node:
    __slots__ = ('tag', 'attrs', 'children', 'parent', 'line', 'closed')

    def __init__(self, tag, attrs, parent, line):
        self.tag, self.attrs, self.children, self.parent, self.line, self.closed = tag, attrs, [], parent, line, False

    @property
    def classes(self):
        return (self.attrs.get('class') or '').split()

    def elements(self):
        return [c for c in self.children if isinstance(c, Node)]

    def text(self):
        return ''.join(c if isinstance(c, str) else c.text() for c in self.children)

    def walk(self):
        for c in self.children:
            if isinstance(c, Node):
                yield c
                yield from c.walk()

    def first(self, tag=None, cls=None):
        for n in self.walk():
            if (tag is None or n.tag == tag) and (cls is None or cls in n.classes):
                return n
        return None

    def ancestors(self):
        n = self.parent
        while n is not None:
            yield n
            n = n.parent


class Tree(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Node('#root', {}, None, 1)
        self.cur = self.root
        self.problems = []
        self.doctype = None

    def handle_decl(self, decl):
        self.doctype = decl

    def handle_starttag(self, tag, attrs):
        line = self.getpos()[0]
        a = {}
        for k, v in attrs:
            if k in a:
                self.problems.append((line, f'<{tag}> has the attribute {k} twice'))
            a[k] = v if v is not None else ''
        if tag in BLOCK and self.cur.tag == 'p':
            self.problems.append((line, f'<{tag}> cannot sit inside a <p>; close the <p> first'))
        n = Node(tag, a, self.cur, line)
        self.cur.children.append(n)
        if tag in VOID:
            n.closed = True
        else:
            self.cur = n

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in VOID:
            self.cur.closed = True
            self.cur = self.cur.parent

    def handle_endtag(self, tag):
        line = self.getpos()[0]
        if tag in VOID:
            return
        n = self.cur
        while n is not None and n.tag != tag:
            n = n.parent
        if n is None:
            self.problems.append((line, f'</{tag}> closes nothing that is open'))
            return
        while self.cur is not n:
            self.problems.append((self.cur.line, f'<{self.cur.tag}> is never closed (it ends at </{tag}>, line {line})'))
            self.cur.closed = True
            self.cur = self.cur.parent
        n.closed = True
        self.cur = n.parent

    def handle_data(self, data):
        self.cur.children.append(data)

    def close(self):
        super().close()
        while self.cur is not self.root:
            self.problems.append((self.cur.line, f'<{self.cur.tag}> is never closed'))
            self.cur = self.cur.parent


def parse(path):
    with open(path, encoding='utf-8') as f:
        src = f.read()
    t = Tree()
    t.feed(src)
    t.close()
    return t, src


def load_nav():
    path = os.path.join(SITE, 'assets', 'nav.js')
    with open(path, encoding='utf-8') as f:
        src = f.read()
    data = json.loads(src.split('window.NUNA_NAV =', 1)[1].strip().rstrip(';'))
    pages = {}
    for p in data['root']:
        pages[p['u']] = dict(p, section=None)
    for band in data['bands']:
        for s in band['sections']:
            for p in s['pages']:
                pages[p['u']] = dict(p, section=s['id'])
    return data, pages


def rel(path):
    return os.path.relpath(os.path.abspath(path), SITE).replace(os.sep, '/')


_ids_cache = {}


def ids_of(path):
    if path not in _ids_cache:
        try:
            t, _ = parse(path)
            _ids_cache[path] = {n.attrs['id'] for n in t.root.walk() if 'id' in n.attrs}
        except (OSError, UnicodeDecodeError):
            _ids_cache[path] = set()
    return _ids_cache[path]


def norm(s):
    return re.sub(r'\s+', ' ', html.unescape(s)).strip()


class Page:
    def __init__(self, path, nav_pages, exempt=None):
        self.path = path
        self.rel = rel(path)
        self.nav = nav_pages
        self.errors, self.warnings = [], []
        self.gaps, self.shots, self.diagrams, self.unwritten = [], [], [], []
        # pages under tools/ (the fixture) are not in the site map, so the rules that need it are skipped
        self.in_tools = self.rel.split('/')[0] == 'tools' if exempt is None else exempt

    def err(self, line, msg):
        self.errors.append((line, msg))

    def warn(self, line, msg):
        self.warnings.append((line, msg))

    # ---------------------------------------------------------------- skeleton (section 4)
    def check(self):
        try:
            tree, src = parse(self.path)
        except UnicodeDecodeError:
            self.err(1, 'the file is not UTF-8')
            return self
        for line, msg in tree.problems:
            self.err(line, msg)
        if not (tree.doctype or '').lower() == 'doctype html':
            self.err(1, 'the file must start with <!doctype html>')
        root = tree.root
        htm = next((n for n in root.elements() if n.tag == 'html'), None)
        if htm is None:
            self.err(1, 'no <html> element')
            return self
        depth = self.rel.count('/')
        want_root = '' if depth == 0 else '../'
        if depth > 1:
            self.err(1, 'pages sit at most one folder deep (DESIGN.md section 1)')
        if htm.attrs.get('lang') != 'en':
            self.err(htm.line, '<html> needs lang="en"')
        if htm.attrs.get('data-root') != want_root:
            self.err(htm.line, f'<html> needs data-root="{want_root}" for a page at this depth')
        want_page = self.rel[:-5] if self.rel.endswith('.html') else self.rel
        if htm.attrs.get('data-page') != want_page:
            self.err(htm.line, f'<html> needs data-page="{want_page}"')
        R = want_root
        head = next((n for n in htm.elements() if n.tag == 'head'), None)
        body = next((n for n in htm.elements() if n.tag == 'body'), None)
        if head is None or body is None:
            self.err(htm.line, '<html> needs a <head> and a <body>')
            return self
        self.check_head(head, R)
        article = self.check_body(body, R)
        if article is None:
            return self
        self.check_article(article, head)
        self.check_links(root)
        return self

    def check_head(self, head, R):
        want = [('meta', {'charset': 'utf-8'}), ('meta', {'name': 'viewport', 'content': 'width=device-width, initial-scale=1'}),
                ('title', {}), ('meta', {'name': 'description'}),
                ('link', {'rel': 'icon', 'href': R + 'assets/favicon.svg', 'type': 'image/svg+xml'}),
                ('link', {'rel': 'stylesheet', 'href': R + 'assets/docs.css'}),
                ('script', {'src': R + 'assets/theme.js'}), ('script', {'src': R + 'assets/nav.js', 'defer': ''}),
                ('script', {'src': R + 'assets/docs.js', 'defer': ''})]
        got = head.elements()
        if len(got) != len(want):
            self.err(head.line, f'<head> must hold exactly the {len(want)} elements of the skeleton (it has {len(got)})')
        for n, (tag, attrs) in zip(got, want):
            if n.tag != tag:
                self.err(n.line, f'<head>: expected <{tag}> here, found <{n.tag}> (keep the skeleton\'s order)')
                continue
            for k, v in attrs.items():
                if n.attrs.get(k) != v:
                    self.err(n.line, f'<{tag}> needs {k}="{v}"')
            extra = set(n.attrs) - set(attrs) - ({'content'} if tag == 'meta' and attrs.get('name') == 'description' else set())
            if extra:
                self.err(n.line, f'<{tag}> has attributes the skeleton lacks: {", ".join(sorted(extra))}')
            if tag == 'script' and n.text().strip():
                self.err(n.line, 'no inline script')
            if tag == 'meta' and attrs.get('name') == 'description' and not n.attrs.get('content', '').strip():
                self.err(n.line, 'the description is empty: it is the lead as plain text')
            if tag == 'meta' and attrs.get('name') == 'description' and '<' in n.attrs.get('content', ''):
                self.err(n.line, 'the description is plain text: no markup')

    def check_body(self, body, R):
        els = body.elements()
        shape = [(n.tag, n.attrs.get('class'), n.attrs.get('id')) for n in els]
        want = [('a', 'skip', None), ('header', 'site-head', 'site-head'), ('div', 'site', None),
                ('footer', 'site-foot', 'site-foot')]
        if shape != want:
            self.err(body.line, '<body> must hold the skeleton\'s four parts in order: a.skip, header#site-head, '
                                'div.site, footer#site-foot, and nothing else')
            if len(els) < 3 or els[2].tag != 'div':
                return None
        skip, head, site = els[0], els[1], els[2]
        if skip.attrs.get('href') != '#main' or norm(skip.text()) != 'Skip to the content':
            self.err(skip.line, 'the skip link is <a class="skip" href="#main">Skip to the content</a>')
        hk = [(n.tag, n.attrs.get('class'), n.attrs.get('href')) for n in head.elements()]
        if hk != [('a', 'brand', R + 'index.html'), ('a', 'all-pages', R + 'all-pages.html')]:
            self.err(head.line, 'header#site-head holds exactly the skeleton\'s two links (brand, all pages)')
        if len(els) > 3 and els[3].children and any(isinstance(c, Node) or c.strip() for c in els[3].children):
            self.err(els[3].line, 'footer#site-foot stays empty: docs.js draws it')
        se = site.elements()
        sk = [(n.tag, n.attrs.get('class'), n.attrs.get('id')) for n in se]
        if sk != [('nav', 'site-nav', 'site-nav'), ('main', None, 'main'), ('aside', 'toc', 'toc')]:
            self.err(site.line, 'div.site holds nav#site-nav, main#main and aside#toc, in that order, and nothing else')
        for n in se:
            if n.tag == 'nav' and (n.attrs.get('aria-label') != 'Documentation' or n.children):
                self.err(n.line, 'nav#site-nav is empty, with aria-label="Documentation"')
            if n.tag == 'aside' and (n.attrs.get('aria-label') != 'On this page' or n.children):
                self.err(n.line, 'aside#toc is empty, with aria-label="On this page"')
        main = next((n for n in se if n.tag == 'main'), None)
        if main is None:
            return None
        if main.attrs.get('tabindex') != '-1':
            self.err(main.line, 'main needs tabindex="-1"')
        me = main.elements()
        if len(me) != 1 or me[0].tag != 'article' or me[0].classes != ['doc']:
            self.err(main.line, 'main holds one <article class="doc"> and nothing else')
            return next((n for n in me if n.tag == 'article'), None)
        return me[0]

    # ---------------------------------------------------------------- the article (section 5)
    def check_article(self, art, head):
        typ = art.attrs.get('data-type')
        self.type = typ
        if typ not in TYPES:
            self.err(art.line, f'data-type="{typ}" is no page type (section 3)')
        extra = set(art.attrs) - {'class', 'data-type', 'data-toc'}
        if extra:
            self.err(art.line, f'<article> carries only class, data-type and data-toc (found {", ".join(sorted(extra))})')
        if art.attrs.get('data-toc', 'h2') != 'h2':
            self.err(art.line, 'data-toc takes only the value "h2"')
        nav = self.nav.get(self.rel)
        els = art.elements()
        if not els or els[0].tag != 'header' or els[0].classes != ['doc-head']:
            self.err(art.line, 'the article starts with <header class="doc-head">')
            return
        dh = els[0]
        de = dh.elements()
        if not de or de[0].tag != 'h1' or len(de) < 2 or de[1].tag != 'p' or de[1].classes != ['lead']:
            self.err(dh.line, 'header.doc-head holds the h1, then p.lead, then optionally p.doc-meta')
        else:
            h1 = norm(de[0].text())
            if de[0].elements() or de[0].attrs:
                self.err(de[0].line, 'the h1 is plain text: no attributes, no markup')
            title = next((n for n in head.elements() if n.tag == 'title'), None)
            if title is not None and norm(title.text()) != h1 + ' · nuna docs':
                self.err(title.line, f'<title> must be "{h1} · nuna docs"')
            if nav and not self.in_tools:
                if h1 != nav['t']:
                    self.err(de[0].line, f'the h1 must be the site map\'s title: "{nav["t"]}"')
                if typ != nav['y']:
                    self.err(art.line, f'the site map says this page is a {nav["y"]}, not a {typ}')
            for n in de[2:]:
                home_part = typ == 'home' and n.classes and all(c.startswith('home-') for c in n.classes)
                if not (n.tag == 'p' and n.classes == ['doc-meta']) and not home_part:
                    self.err(n.line, 'after the lead, header.doc-head holds only p.doc-meta')
            for n in de[1:]:
                self.check_node(n, typ)
        if not self.in_tools and nav is None:
            self.err(art.line, f'{self.rel} is not in the site map (assets/nav.js); write only pages listed there')
        leads = [n for n in art.walk() if n.tag == 'p' and 'lead' in n.classes]
        if len(leads) != 1:
            self.err(art.line, f'exactly one p.lead per page (found {len(leads)})')
        body = els[1:]
        if typ not in NO_TAIL and not self.in_tools:
            if len(body) < 2 or not (body[-1].tag == 'details' and 'doc-sources' in body[-1].classes) \
                    or not (body[-2].tag == 'section' and 'see-also' in body[-2].classes):
                self.err(art.line, 'the article ends with section.see-also, then details.doc-sources (section 3)')
        ids = {}
        for n in art.walk():
            if 'id' in n.attrs:
                if n.attrs['id'] in ids:
                    self.err(n.line, f'id "{n.attrs["id"]}" is used twice (first on line {ids[n.attrs["id"]]})')
                ids.setdefault(n.attrs['id'], n.line)
        for n in body:
            self.check_node(n, typ)
        self.check_headings(body, typ)
        self.check_wording(art)

    def check_node(self, n, typ, in_svg=False):
        tag, cls = n.tag, n.classes
        if in_svg:
            if tag not in SVG_CHILDREN:
                self.err(n.line, f'<{tag}> is not allowed in a diagram (section 8.2)')
            for k in n.attrs:
                if SVG_BANNED_ATTR.match(k):
                    self.err(n.line, f'<{tag}> has {k}=: diagrams take colour and type only from the d-* classes')
            for c in cls:
                if c not in D_CLASSES:
                    self.err(n.line, f'class "{c}" is not a diagram class (section 8.2)')
            for c in n.elements():
                self.check_node(c, typ, True)
            return
        if tag in ('script', 'style', 'link', 'iframe', 'br', 'object', 'embed', 'form', 'input', 'button', 'hr',
                   'h1', 'h5', 'h6', 'header', 'footer', 'nav', 'main', 'article'):
            self.err(n.line, f'<{tag}> is not allowed in a page body')
            return
        for k in n.attrs:
            if k == 'style':
                self.err(n.line, f'<{tag}> has a style attribute: no inline style')
            elif k.startswith('on'):
                self.err(n.line, f'<{tag}> has {k}=: no scripts in pages')
        home_ok = typ == 'home' and cls and all(c.startswith('home-') for c in cls)
        if tag == 'svg':
            if not home_ok:  # the home page's decorative marks are not figures
                self.check_svg(n)
            return
        if tag not in PLAIN and tag not in NEEDS_CLASS:
            if not home_ok or tag not in {'div', 'section', 'span', 'nav', 'header'}:
                self.err(n.line, f'<{tag}> is not in the content contract (section 5)')
                return
        allowed_attrs = {'class'} | ATTRS.get(tag, set())
        if home_ok:
            allowed_attrs |= {'id', 'aria-label', 'aria-labelledby'}
        bad = [k for k in n.attrs if k not in allowed_attrs and k != 'style' and not k.startswith('on')]
        if bad:
            self.err(n.line, f'<{tag}> may not carry {", ".join(sorted(bad))}')
        if not home_ok:
            self.check_classes(n, typ)
        self.check_component(n, typ)
        for c in n.elements():
            self.check_node(c, typ)

    def check_classes(self, n, typ):
        tag, cls = n.tag, n.classes
        allowed = CLASSES.get(tag, set())
        if tag in NEEDS_CLASS and not cls:
            self.err(n.line, f'<{tag}> only appears as a component, with its class (section 5.22)')
            return
        if not cls:
            return
        if not allowed:
            self.err(n.line, f'<{tag}> takes no class (found "{" ".join(cls)}")')
            return
        main = cls[0]
        if main not in allowed:
            self.err(n.line, f'<{tag} class="{main}"> is not in the contract; {tag} takes {", ".join(sorted(allowed))}')
            return
        rest = cls[1:]
        if main == 'badge':
            if len(rest) != 1 or rest[0] not in BADGES:
                self.err(n.line, f'a badge has one kind: {", ".join(sorted(BADGES))}')
        elif main == 'callout':
            if len(rest) != 1 or rest[0] not in CALLOUTS:
                self.err(n.line, f'a callout has one kind: {", ".join(sorted(CALLOUTS))}')
        elif main in ('shot', 'diagram'):
            if rest not in ([], ['pending']):
                self.err(n.line, f'figure.{main} takes only "pending" besides')
        elif rest:
            self.err(n.line, f'<{tag} class="{main}"> takes no other class (found "{" ".join(rest)}")')

    def check_component(self, n, typ):
        tag, cls = n.tag, n.classes
        c0 = cls[0] if cls else None
        els = n.elements()
        if tag in ('h2', 'h3', 'h4'):
            for e in n.walk():
                ok = e.tag == 'code' or (e.tag == 'span' and 'method' in e.classes) or e.tag == 'var'
                if not ok:
                    self.err(e.line, f'<{e.tag}> inside a heading: headings hold text, and code for a name')
        if tag == 'a':
            if not n.attrs.get('href'):
                self.err(n.line, '<a> needs an href')
            elif re.fullmatch(r'\s*(here|this page|click here|this|link|more)\s*', n.text(), re.I):
                self.err(n.line, f'link text "{norm(n.text())}" must say where it goes (section 5.15)')
        if tag == 'b' and norm(n.text()) == '':
            self.err(n.line, 'an empty b.ui')
        if tag == 'span' and c0 == 'ui-path':
            parts = els
            if len(parts) < 2 or any(not (e.tag == 'b' and e.classes == ['ui']) for e in parts):
                self.err(n.line, 'span.ui-path holds two or more b.ui and nothing else')
            stray = norm(''.join(c for c in n.children if isinstance(c, str)))
            if stray:
                self.err(n.line, f'span.ui-path has text between its items ("{stray}"): the stylesheet draws separators')
        if tag == 'aside' and c0 == 'callout':
            if not els or not (els[0].tag == 'p' and els[0].classes == ['callout-title']):
                self.err(n.line, 'a callout starts with p.callout-title')
            for e in els[1:]:
                if e.tag not in ('p', 'ul', 'ol') and not (e.tag == 'div' and 'code' in e.classes):
                    self.err(e.line, f'a callout holds p, ul, ol and code blocks only (found <{e.tag}>)')
            sib = n.parent.elements()
            i = sib.index(n)
            if i + 1 < len(sib) and sib[i + 1].tag == 'aside' and 'callout' in sib[i + 1].classes:
                self.err(sib[i + 1].line, 'two callouts back to back (section 5.5)')
        if tag == 'p' and c0 in ('callout-title',) and not (n.parent.tag == 'aside'):
            self.err(n.line, 'p.callout-title belongs only inside a callout')
        if tag == 'ol' and c0 == 'steps':
            for li in els:
                if li.tag != 'li':
                    self.err(li.line, 'ol.steps holds only li')
                    continue
                le = li.elements()
                if not le or not (le[0].tag == 'p' and le[0].classes == ['step-title']):
                    self.err(li.line, 'each step starts with p.step-title')
                elif not norm(le[0].text()).endswith(('.', '?')):
                    self.err(le[0].line, 'a step title is one sentence ending with a full stop')
        if tag == 'p' and c0 == 'step-title' and not (n.parent.tag == 'li' and n.parent.parent is not None
                                                      and 'steps' in n.parent.parent.classes):
            self.err(n.line, 'p.step-title belongs only first in an li of ol.steps')
        if tag == 'div' and c0 == 'code':
            lang = n.attrs.get('data-lang')
            if lang not in LANGS:
                self.err(n.line, f'div.code needs data-lang, one of: {", ".join(sorted(LANGS))}')
            if 'data-kind' in n.attrs and n.attrs['data-kind'] != 'output':
                self.err(n.line, 'data-kind takes only "output"')
            if 'data-group' in n.attrs:
                self.err(n.line, 'data-group belongs on div.tabs')
            if len(els) != 1 or els[0].tag != 'pre' or len(els[0].elements()) != 1 or els[0].elements()[0].tag != 'code':
                self.err(n.line, 'div.code holds one <pre><code>…</code></pre> and nothing else')
            else:
                pre = els[0]
                if any(isinstance(c, str) and c.strip() for c in pre.children):
                    self.err(pre.line, 'text outside <code> inside <pre>')
                code = pre.elements()[0]
                txt = code.text()
                if txt.startswith('\n'):
                    self.err(code.line, 'the code starts right after <code>, with no blank first line')
                for e in code.walk():
                    if e.tag != 'var':
                        self.err(e.line, f'<{e.tag}> inside a code block (only <var> may be)')
                if n.attrs.get('data-kind') != 'output' and lang in ('bash', 'powershell'):
                    for i, ln in enumerate(txt.split('\n')):
                        if re.match(r'\s*(\$|PS>|PS [A-Z]:\\.*>|>)\s', ln):
                            self.err(code.line + i, 'no prompts in commands; put output in its own output block')
        if tag == 'pre' and n.parent.tag != 'div':
            self.err(n.line, '<pre> appears only inside div.code')
        if tag == 'div' and c0 == 'tabs':
            g = n.attrs.get('data-group')
            if g not in TAB_GROUPS:
                self.err(n.line, f'div.tabs needs data-group, one of: {", ".join(TAB_GROUPS)}')
                return
            order = [v for v, _ in TAB_GROUPS[g]]
            labels = dict(TAB_GROUPS[g])
            seen = []
            for t in els:
                if not (t.tag == 'section' and t.classes == ['tab']):
                    self.err(t.line, 'div.tabs holds only section.tab')
                    continue
                v = t.attrs.get('data-tab')
                if v not in labels:
                    self.err(t.line, f'data-tab="{v}" is not a value of group {g}: {", ".join(order)}')
                    continue
                if t.attrs.get('data-label') != labels[v]:
                    self.err(t.line, f'data-tab="{v}" takes data-label="{labels[v]}"')
                if v in seen:
                    self.err(t.line, f'tab "{v}" twice in one tab set')
                seen.append(v)
            if len(els) < 2:
                self.err(n.line, 'a tab set has two tabs at least')
            if seen != sorted(seen, key=order.index):
                self.err(n.line, f'tabs out of order; group {g} goes {", ".join(order)}')
        if tag == 'section' and c0 == 'tab' and not (n.parent.tag == 'div' and 'tabs' in n.parent.classes):
            self.err(n.line, 'section.tab belongs only inside div.tabs')
        if tag == 'div' and c0 == 'table-wrap':
            if len(els) != 1 or els[0].tag != 'table':
                self.err(n.line, 'div.table-wrap holds one table and nothing else')
        if tag == 'table':
            if not (n.parent.tag == 'div' and 'table-wrap' in n.parent.classes):
                self.err(n.line, 'every table sits in div.table-wrap')
            thead = next((e for e in els if e.tag == 'thead'), None)
            if thead is None:
                self.err(n.line, 'a table needs a thead')
            else:
                for th in thead.walk():
                    if th.tag == 'th' and th.attrs.get('scope') != 'col':
                        self.err(th.line, 'header cells in thead take scope="col"')
                    if th.tag == 'td':
                        self.err(th.line, 'thead holds th, not td')
            for cell in n.walk():
                if cell.tag in ('td', 'th'):
                    for e in cell.walk():
                        if e.tag in ('table', 'pre') or (e.tag == 'div' and 'code' in e.classes):
                            self.err(e.line, 'table cells hold inline content, p and ul only')
        if tag == 'section' and c0 == 'endpoint':
            h = els[0] if els else None
            if h is None or h.tag != 'h3':
                self.err(n.line, 'an endpoint block starts with its h3')
            else:
                m = h.first('span', 'method')
                code = next((e for e in h.elements() if e.tag == 'code'), None)
                if m is None or code is None:
                    self.err(h.line, 'the endpoint h3 holds span.method and the path in code')
                else:
                    meth = m.attrs.get('data-method')
                    if meth not in METHODS or norm(m.text()) != meth:
                        self.err(m.line, f'span.method needs data-method from {", ".join(sorted(METHODS))}, and the same text')
                hid = h.attrs.get('id', '')
                want = ['-parameters', '-body', '-responses', '-example']
                last = -1
                for e in els[1:]:
                    if e.tag == 'h4':
                        eid = e.attrs.get('id', '')
                        suf = next((s for s in want if eid == hid + s), None)
                        if suf is None:
                            self.err(e.line, f'an endpoint h4 id is the h3 id plus one of {", ".join(want)}')
                        elif want.index(suf) < last:
                            self.err(e.line, 'endpoint parts go Parameters, Request body, Responses, Example')
                        else:
                            last = want.index(suf)
        if tag == 'span' and c0 == 'method' and not any(a.tag == 'h3' for a in n.ancestors()):
            self.err(n.line, 'span.method belongs in an endpoint h3')
        if tag == 'section' and c0 == 'setting':
            h = els[0] if els else None
            if h is None or h.tag != 'h3' or not h.elements() or h.elements()[0].tag != 'code':
                self.err(n.line, 'a settings entry starts with an h3 holding the key in code')
            if len(els) < 2 or not (els[1].tag == 'dl' and els[1].classes == ['facts']):
                self.err(n.line, 'a settings entry\'s h3 is followed by dl.facts')
        if tag == 'dl' and c0 == 'facts':
            terms = [norm(e.text()) for e in els if e.tag == 'dt']
            for t in terms:
                if t not in FACTS:
                    self.err(n.line, f'"{t}" is not a facts row; rows are {", ".join(FACTS)}')
            known = [t for t in terms if t in FACTS]
            if known != sorted(known, key=FACTS.index):
                self.err(n.line, f'facts rows go in this order: {", ".join(FACTS)}')
        if tag == 'dt' and 'id' in n.attrs:
            if not (n.parent.tag == 'dl' and 'terms' in n.parent.classes) or not n.attrs['id'].startswith('term-'):
                self.err(n.line, 'only a dt of dl.terms takes an id, starting term-')
        if tag == 'ul' and c0 == 'cards':
            if typ not in ('section', 'home') and not self.in_tools:
                self.err(n.line, 'cards belong on section pages only')
            for li in els:
                a = li.elements()
                if li.tag != 'li' or len(a) != 1 or a[0].tag != 'a':
                    self.err(li.line, 'each card is <li><a href> with span.card-title and span.card-text')
                    continue
                kids = [(e.tag, e.attrs.get('class')) for e in a[0].elements()]
                if kids != [('span', 'card-title'), ('span', 'card-text')]:
                    self.err(a[0].line, 'a card link holds span.card-title then span.card-text')
        if tag == 'section' and c0 == 'see-also':
            if not els or els[0].tag != 'h2' or els[0].attrs.get('id') != 'see-also' or norm(els[0].text()) != 'See also':
                self.err(n.line, 'section.see-also starts with <h2 id="see-also">See also</h2>')
            lis = [e for e in els if e.tag == 'ul']
            if len(lis) != 1 or len(els) != 2:
                self.err(n.line, 'section.see-also holds its h2 and one ul')
            elif not 2 <= len(lis[0].elements()) <= 6:
                self.err(lis[0].line, 'See also has two to six links')
        if tag == 'details':
            if not els or els[0].tag != 'summary' or norm(els[0].text()) != 'How this page was checked':
                self.err(n.line, 'details.doc-sources starts with <summary>How this page was checked</summary>')
            if not any('commit' in e.text() for e in els[1:]):
                self.warn(n.line, '"How this page was checked" names no commit')
        if tag == 'summary' and n.parent.tag != 'details':
            self.err(n.line, '<summary> appears only in details.doc-sources')
        if tag == 'p' and c0 == 'gap':
            self.gaps.append((n.line, norm(n.text())))
        if tag == 'p' and c0 == 'doc-meta':
            for e in els:
                if not (e.tag == 'span' and 'badge' in e.classes):
                    self.err(e.line, 'p.doc-meta holds badges only')
            if not (n.parent.tag == 'header' or (n.parent.tag == 'section' and 'endpoint' in n.parent.classes)):
                self.err(n.line, 'p.doc-meta belongs in the page head or an endpoint block')
        if tag == 'span' and c0 == 'badge' and n.parent.tag != 'p':
            self.err(n.line, 'badges sit in p.doc-meta')
        if tag == 'figure':
            self.check_figure(n)
        if tag == 'img' and not any(a.tag == 'figure' for a in n.ancestors()):
            self.err(n.line, 'images appear only in figure.shot')
        if tag == 'li' and n.parent.tag not in ('ul', 'ol'):
            self.err(n.line, '<li> outside a list')

    def check_figure(self, n):
        fid = n.attrs.get('id', '')
        if not fid.startswith('fig-') or not ID_RE.match(fid):
            self.err(n.line, 'a figure needs id="fig-…" in lower case and hyphens')
        els = n.elements()
        if not els or els[-1].tag != 'figcaption' or not norm(els[-1].text()):
            self.err(n.line, 'a figure ends with a figcaption')
        cls = n.classes
        pending = 'pending' in cls
        shot_attrs = [k for k in n.attrs if k.startswith('data-shot')]
        if 'shot' in cls and pending:
            need = ['data-shot', 'data-shot-route', 'data-shot-user', 'data-shot-width', 'data-shot-state']
            for k in need:
                if not n.attrs.get(k, '').strip():
                    self.err(n.line, f'a pending screenshot needs {k}')
            if n.attrs.get('data-shot-width') not in ('1440', '390'):
                self.err(n.line, 'data-shot-width is 1440 or 390')
            stem = n.attrs.get('data-shot', '')
            if stem and not ID_RE.match(stem):
                self.err(n.line, 'data-shot is lower case and hyphens')
            route = n.attrs.get('data-shot-route', '')
            if route and not route.startswith('#'):
                self.err(n.line, 'data-shot-route is the fragment after /ui/, starting with #')
            if route and re.search(r'#/|[?]', route):
                self.err(n.line, 'data-shot-route uses route.js href() form: #c=…&k=…&ns=…&n=…&tab=… (no #/ path, no ?)')
            if len(els) != 1:
                self.err(n.line, 'a pending screenshot holds only its figcaption')
            self.shots.append((n.line, {k: n.attrs.get(k, '') for k in need} | {'id': fid,
                                                                                'caption': norm(els[-1].text()) if els else ''}))
        elif 'shot' in cls:
            if shot_attrs:
                self.err(n.line, 'a captured screenshot keeps no data-shot attributes')
            img = next((e for e in els if e.tag == 'img'), None)
            if img is None:
                self.err(n.line, 'figure.shot holds an img, then the figcaption')
            else:
                for k in ('src', 'alt', 'width', 'height'):
                    if not img.attrs.get(k, '').strip():
                        self.err(img.line, f'the screenshot img needs {k}')
                for k in ('src', 'data-dark'):
                    v = img.attrs.get(k)
                    if v and not os.path.exists(os.path.normpath(os.path.join(os.path.dirname(self.path), v))):
                        self.err(img.line, f'{k}="{v}" does not exist')
        elif 'diagram' in cls and pending:
            todo = next((e for e in els if e.tag == 'p' and 'shot-todo' in e.classes), None)
            if todo is None or not norm(todo.text()):
                self.err(n.line, 'a pending diagram holds p.shot-todo saying what to draw')
            self.diagrams.append((n.line, {'id': fid, 'draw': norm(todo.text()) if todo else '',
                                           'caption': norm(els[-1].text()) if els else ''}))
        elif 'diagram' in cls:
            if not any(e.tag == 'svg' for e in els):
                self.err(n.line, 'figure.diagram holds an svg, then the figcaption')
        if shot_attrs and 'shot' not in cls:
            self.err(n.line, 'data-shot attributes belong on figure.shot')

    def check_svg(self, n):
        fig = n.parent
        if fig.tag != 'figure' or 'diagram' not in fig.classes:
            self.err(n.line, 'an svg appears only in figure.diagram')
        fid = fig.attrs.get('id', '')
        if n.attrs.get('role') != 'img':
            self.err(n.line, 'the svg needs role="img"')
        if n.attrs.get('aria-labelledby') != f'{fid}-t {fid}-d':
            self.err(n.line, f'the svg needs aria-labelledby="{fid}-t {fid}-d"')
        if 'viewBox' not in n.attrs and 'viewbox' not in n.attrs:
            self.err(n.line, 'the svg needs a viewBox')
        for k in n.attrs:
            if k.lower() not in ('viewbox', 'role', 'aria-labelledby', 'class', 'xmlns'):
                self.err(n.line, f'the svg carries {k}=; it takes viewBox, role and aria-labelledby only')
        kids = n.elements()
        t = next((e for e in kids if e.tag == 'title'), None)
        d = next((e for e in kids if e.tag == 'desc'), None)
        if t is None or t.attrs.get('id') != f'{fid}-t' or not norm(t.text()):
            self.err(n.line, f'the svg needs <title id="{fid}-t">')
        if d is None or d.attrs.get('id') != f'{fid}-d' or not norm(d.text()):
            self.err(n.line, f'the svg needs <desc id="{fid}-d">')
        accents = sum(1 for e in n.walk() if 'd-accent' in e.classes)
        if accents > 1:
            self.warn(n.line, f'{accents} d-accent marks; a diagram has one (section 8.2)')
        for e in kids:
            if e.tag in ('title', 'desc'):
                continue
            self.check_node(e, getattr(self, 'type', None), True)
        for e in n.walk():
            if e.tag == 'use' or 'marker-end' in e.attrs:
                ref = e.attrs.get('marker-end', e.attrs.get('href', ''))
                if ref and ref not in ('url(#d-arrow)',):
                    self.err(e.line, 'the only marker is url(#d-arrow); diagrams use no other references')

    def check_headings(self, body, typ):
        level = 1
        for n in self._headings(body):
            lv = int(n.tag[1])
            hid = n.attrs.get('id')
            txt = norm(n.text())
            if hid is None:
                self.err(n.line, f'<{n.tag}> "{txt}" needs an id')
            else:
                long_ok = any(a.tag == 'section' and ({'setting', 'endpoint'} & set(a.classes)) for a in n.ancestors())
                if not ID_RE.match(hid):
                    self.err(n.line, f'id "{hid}": lower case letters, digits and single hyphens only')
                elif len(hid) > 48 and not long_ok:
                    self.err(n.line, f'id "{hid}" is longer than 48 characters')
            if txt in FIXED_IDS and hid != FIXED_IDS[txt] and lv == 2:
                self.err(n.line, f'"{txt}" takes the fixed id "{FIXED_IDS[txt]}"')
            if lv == 4 and typ not in H4_TYPES and not self.in_tools:
                self.err(n.line, 'h4 appears only on api and reference pages')
            if lv > level + 1:
                self.err(n.line, f'<{n.tag}> skips a level after h{level}')
            level = lv
            if not txt:
                self.err(n.line, 'an empty heading')
            elif txt.endswith((':', '.')):
                self.err(n.line, f'heading "{txt}" ends with "{txt[-1]}"')
            if len(txt) > 60 and not any(a.tag == 'section' and ({'setting', 'endpoint'} & set(a.classes))
                                         for a in n.ancestors()):
                self.warn(n.line, f'heading longer than 60 characters: "{txt}"')

    def _headings(self, nodes):
        for n in nodes:
            if n.tag in ('h2', 'h3', 'h4'):
                yield n
            elif n.tag != 'svg':
                yield from self._headings(n.elements())

    def check_wording(self, art):
        def visit(n):
            if n.tag in NO_WORDING or (n.tag == 'details' and 'doc-sources' in n.classes) \
                    or (n.tag == 'span' and 'card-text' in n.classes and self.rel == 'all-pages.html'):
                return
            for c in n.children:
                if isinstance(c, str):
                    for rx, msg in WORDING:
                        for m in rx.finditer(c):
                            self.warn(n.line, msg.format(m.group(0)))
                else:
                    visit(c)
        visit(art)

    # ---------------------------------------------------------------- links
    def check_links(self, root):
        own = {n.attrs['id'] for n in root.walk() if 'id' in n.attrs}
        for a in root.walk():
            if a.tag != 'a' or 'href' not in a.attrs:
                continue
            if any(x.tag == 'header' and x.attrs.get('id') == 'site-head' for x in a.ancestors()) or 'skip' in a.classes:
                continue
            href = a.attrs['href']
            if re.match(r'^https?://', href):
                continue
            if re.match(r'^[a-z][a-z0-9+.-]*:', href, re.I) or href.startswith('//'):
                self.err(a.line, f'link "{href}": only relative links and http(s) links to outside documentation')
                continue
            if href.startswith('/'):
                self.err(a.line, f'link "{href}" is rooted at /; make it relative')
                continue
            path, _, frag = href.partition('#')
            if not path:
                if frag and frag not in own:
                    self.err(a.line, f'link "#{frag}": no such id on this page')
                continue
            target = os.path.normpath(os.path.join(os.path.dirname(self.path), path))
            trel = os.path.relpath(target, SITE).replace(os.sep, '/')
            if trel.startswith('..'):
                self.err(a.line, f'link "{href}" leaves the docs site; name repository files in code instead')
                continue
            if not trel.endswith('.html'):
                if not os.path.exists(target):
                    self.err(a.line, f'link "{href}": no such file')
                continue
            if trel not in self.nav and not trel.startswith('tools/'):
                self.err(a.line, f'link "{href}": {trel} is not in the site map')
                continue
            if not os.path.exists(target):
                self.unwritten.append((a.line, href))
                self.warn(a.line, f'link "{href}": that page is not written yet')
                continue
            if frag and frag not in ids_of(target):
                self.err(a.line, f'link "{href}": {trel} has no id "{frag}"')


def collect(args):
    files = []
    targets = args or [SITE]
    for t in targets:
        t = os.path.abspath(t)
        if os.path.isdir(t):
            for dp, dns, fns in os.walk(t):
                dns[:] = sorted(d for d in dns if not (os.path.dirname(os.path.join(dp, d)) == SITE and d in SKIP_DIRS))
                files += [os.path.join(dp, f) for f in sorted(fns) if f.endswith('.html')]
        elif t.endswith('.html'):
            files.append(t)
        else:
            print(f'check.py: {t} is not an .html file or a folder', file=sys.stderr)
            sys.exit(2)
    return files


def main(argv):
    strict = '--strict' in argv
    as_json = '--json' in argv
    args = [a for a in argv if not a.startswith('--')]
    nav, nav_pages = load_nav()
    files = collect(args)
    pages = [Page(f, nav_pages).check() for f in files]
    whole = not args
    missing = [u for u in nav_pages if not os.path.exists(os.path.join(SITE, u))] if whole else []
    n_err = sum(len(p.errors) for p in pages)
    n_warn = sum(len(p.warnings) for p in pages)
    if as_json:
        out = {'files': len(pages), 'errors': n_err, 'warnings': n_warn, 'not_written': missing, 'pages': [
            {'file': p.rel, 'errors': p.errors, 'warnings': p.warnings, 'gaps': p.gaps, 'pending_shots': p.shots,
             'pending_diagrams': p.diagrams, 'links_to_unwritten': p.unwritten} for p in pages]}
        print(json.dumps(out, indent=1, ensure_ascii=False))
    else:
        for p in pages:
            for line, msg in sorted(p.errors):
                print(f'{p.rel}:{line}: error: {msg}')
            for line, msg in sorted(p.warnings):
                print(f'{p.rel}:{line}: warning: {msg}')
        gaps = [(p.rel, l, t) for p in pages for l, t in p.gaps]
        shots = [(p.rel, l, s) for p in pages for l, s in p.shots]
        diagrams = [(p.rel, l, d) for p in pages for l, d in p.diagrams]
        if gaps:
            print(f'\nGaps ({len(gaps)}):')
            for f, l, t in gaps:
                print(f'  {f}:{l}: {t}')
        if shots:
            print(f'\nPending screenshots ({len(shots)}):')
            for f, l, s in shots:
                print(f'  {f}:{l}: {s["data-shot"]} ({s["data-shot-width"]}, {s["data-shot-user"]}) {s["data-shot-route"]}')
        if diagrams:
            print(f'\nPending diagrams ({len(diagrams)}):')
            for f, l, d in diagrams:
                print(f'  {f}:{l}: {d["id"]}: {d["draw"]}')
        if missing:
            print(f'\nSite-map pages not written yet: {len(missing)}')
        print(f'\n{len(pages)} files, {n_err} errors, {n_warn} warnings')
    return 1 if n_err or (strict and n_warn) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))

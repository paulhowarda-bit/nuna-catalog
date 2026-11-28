// The nuna docs' chrome and components, drawn from the page's own markup (docs/site/DESIGN.md section 5) and the
// site map (nav.js). A classic script, so pages work from file:// where ES modules and fetch are blocked. No
// network: the search index is a script file beside this one, loaded the first time a reader searches.
(function () {
  'use strict';
  var doc = document, html = doc.documentElement;
  var ROOT = html.getAttribute('data-root') || '';
  var PAGE = html.getAttribute('data-page') || '';
  var HERE = PAGE + '.html';
  var NAV = window.NUNA_NAV || {root: [], bands: []};

  // ---------------------------------------------------------------- helpers
  function el(tag, attrs) {
    var n = doc.createElement(tag);
    if (attrs) for (var k in attrs) {
      if (attrs[k] == null || attrs[k] === false) continue;
      if (k === 'text') n.textContent = attrs[k];
      else if (k === 'html') n.innerHTML = attrs[k];
      else n.setAttribute(k, attrs[k] === true ? '' : attrs[k]);
    }
    for (var i = 2; i < arguments.length; i++) {
      var c = arguments[i];
      if (c == null) continue;
      n.appendChild(typeof c === 'string' ? doc.createTextNode(c) : c);
    }
    return n;
  }
  function $(sel, ctx) { return (ctx || doc).querySelector(sel); }
  function $$(sel, ctx) { return Array.prototype.slice.call((ctx || doc).querySelectorAll(sel)); }
  function store(k, v) {
    try { if (v === undefined) return localStorage.getItem(k); if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, v); }
    catch (e) { return null; }
  }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]; }); }
  function href(u) { return ROOT + u; }
  var ICONS = {
    menu: '<path d="M2.5 4h11M2.5 8h11M2.5 12h11"/>',
    x: '<path d="M4 4l8 8M12 4l-8 8"/>',
    search: '<circle cx="7" cy="7" r="4.5"/><path d="M10.5 10.5 14 14"/>',
    chev: '<path d="M4.5 6.5 8 10l3.5-3.5"/>',
    copy: '<rect x="5" y="5" width="9" height="9" rx="1.5"/><path d="M11 5V3.5c0-.8-.7-1.5-1.5-1.5h-6C2.7 2 2 2.7 2 3.5v6c0 .8.7 1.5 1.5 1.5H5"/>',
    check: '<path d="M3 8.5 6.5 12 13 4.5"/>',
    sun: '<circle cx="8" cy="8" r="3"/><path d="M8 1.5V3M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1 1M11.6 11.6l1 1M3.4 12.6l1-1M11.6 4.4l1-1"/>',
    moon: '<path d="M13 9.5A5.5 5.5 0 1 1 6.5 3a4.5 4.5 0 0 0 6.5 6.5z"/>',
    auto: '<circle cx="8" cy="8" r="5.5"/><path class="auto-half" d="M8 2.5a5.5 5.5 0 0 1 0 11z"/>'
  };
  function icon(name) {
    var s = doc.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('viewBox', '0 0 16 16'); s.setAttribute('class', 'ic'); s.setAttribute('aria-hidden', 'true');
    s.setAttribute('focusable', 'false'); s.innerHTML = ICONS[name];
    return s;
  }
  function peak() {
    var s = doc.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('viewBox', '0 0 24 18'); s.setAttribute('class', 'peak'); s.setAttribute('aria-hidden', 'true');
    s.innerHTML = '<path class="rock" d="M3 14 9.5 4l3.2 4.8 2.3-2.8L21 14"/><path class="ice" d="M1 14h22"/>';
    return s;
  }
  var live = el('div', {class: 'vh', 'aria-live': 'polite'});
  function say(t) { live.textContent = ''; setTimeout(function () { live.textContent = t; }, 30); }

  // ---------------------------------------------------------------- the site map, flattened
  var flat = [], byUrl = {};
  NAV.root.forEach(function (p) { if (p.u === 'index.html') flat.push({p: p, s: null, b: null}); });
  NAV.bands.forEach(function (b) {
    b.sections.forEach(function (s) { s.pages.forEach(function (p) { flat.push({p: p, s: s, b: b}); }); });
  });
  NAV.root.forEach(function (p) { if (p.u !== 'index.html') byUrl[p.u] = {p: p, s: null, b: null}; });
  flat.forEach(function (e) { byUrl[e.p.u] = e; });
  var me = byUrl[HERE] || null;
  var TYPES = {tutorial: 'Tutorial', guide: 'Guide', concept: 'Concept', 'console-page': 'Console page', reference: 'Reference',
    api: 'API', command: 'Command', runbook: 'Runbook', section: 'Section', home: 'Home'};
  var article = $('article.doc');
  var type = article ? article.getAttribute('data-type') : '';

  // ---------------------------------------------------------------- theme
  var THEMES = [['light', 'Light', 'sun'], ['dark', 'Dark', 'moon'], ['auto', 'Auto: matches your system setting', 'auto']];
  var mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  function themeNow() { return html.getAttribute('data-theme') || 'auto'; }
  function isDark() { var t = themeNow(); return t === 'dark' || (t === 'auto' && mq && mq.matches); }
  function setTheme(t) {
    if (t === 'auto') { html.removeAttribute('data-theme'); store('nuna.theme', null); }
    else { html.setAttribute('data-theme', t); store('nuna.theme', t); }
    $$('.seg button').forEach(function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-theme-pick') === t)); });
    shots();
  }
  function shots() {
    var dark = isDark();
    $$('.shot img[data-dark]').forEach(function (img) {
      if (!img.hasAttribute('data-light')) img.setAttribute('data-light', img.getAttribute('src'));
      var want = dark ? img.getAttribute('data-dark') : img.getAttribute('data-light');
      if (img.getAttribute('src') !== want) img.setAttribute('src', want);
    });
  }
  if (mq && mq.addEventListener) mq.addEventListener('change', shots);

  // ---------------------------------------------------------------- header
  function header() {
    var head = $('#site-head');
    if (!head) return;
    var seg = el('span', {class: 'seg', role: 'group', 'aria-label': 'Theme'});
    THEMES.forEach(function (t) {
      var b = el('button', {type: 'button', 'data-theme-pick': t[0], 'aria-pressed': String(themeNow() === t[0]), title: t[1], 'aria-label': t[1].split(':')[0]});
      b.appendChild(icon(t[2]));
      b.addEventListener('click', function () { setTheme(t[0]); });
      seg.appendChild(b);
    });
    var menu = el('button', {type: 'button', class: 'head-btn menu-open', 'aria-label': 'Open the page list', 'aria-expanded': 'false', 'aria-controls': 'site-nav'});
    menu.appendChild(icon('menu'));
    menu.addEventListener('click', function () { drawer(true); });
    var brand = el('a', {class: 'brand', href: href('index.html'), 'aria-label': 'nuna docs, home'}, peak(), el('span', {class: 'mark'}), 'nuna', el('span', {class: 'docs-tag', text: 'docs'}));
    var search = el('button', {type: 'button', class: 'head-btn search-open', 'aria-keyshortcuts': '/', 'aria-label': 'Search the docs'},
      icon('search'), el('span', {class: 'lbl', text: 'Search the docs'}), el('kbd', {class: 'k', 'aria-hidden': 'true', text: '/'}));
    search.addEventListener('click', openSearch);
    var all = el('a', {class: 'all-pages', href: href('all-pages.html'), text: 'All pages'});
    head.textContent = '';
    head.append(menu, brand, el('span', {class: 'head-spacer'}), search, all, seg);
  }

  // ---------------------------------------------------------------- side navigation
  function sidenav() {
    var nav = $('#site-nav');
    if (!nav) return;
    var close = el('button', {type: 'button', class: 'head-btn nav-close', 'aria-label': 'Close the page list'}, icon('x'));
    close.addEventListener('click', function () { drawer(false); });
    nav.appendChild(close);
    NAV.bands.forEach(function (b) {
      var band = el('div', {class: 'nav-band'}, el('p', {class: 'nav-band-title', text: b.title}));
      b.sections.forEach(function (s) {
        var idx = s.pages[0], open = me && me.s === s;
        var list = el('ul', {class: 'nav-pages', id: 'nav-' + s.id, hidden: !open});
        s.pages.slice(1).forEach(function (p) {
          list.appendChild(el('li', null, el('a', {href: href(p.u), 'aria-current': p.u === HERE ? 'page' : null, text: p.n || p.t})));
        });
        var tog = el('button', {type: 'button', class: 'nav-toggle', 'aria-expanded': String(!!open), 'aria-controls': 'nav-' + s.id,
          'aria-label': 'Show the pages of ' + s.title});
        tog.appendChild(icon('chev'));
        tog.addEventListener('click', function () {
          var o = tog.getAttribute('aria-expanded') !== 'true';
          tog.setAttribute('aria-expanded', String(o)); list.hidden = !o;
        });
        var row = el('div', {class: 'nav-sec-row'}, tog,
          el('a', {class: 'nav-sec-link', href: href(idx.u), 'aria-current': idx.u === HERE ? 'page' : null, text: s.title}));
        band.appendChild(el('div', {class: 'nav-sec'}, row, list));
      });
      nav.appendChild(band);
    });
    nav.appendChild(el('p', {class: 'nav-foot'}, el('a', {href: href('all-pages.html'), text: 'All pages'}), ' · press ', el('kbd', {text: '/'}), ' to search'));
    var cur = $('[aria-current="page"]', nav);
    if (cur && cur.scrollIntoView && window.innerWidth >= 960) {
      var r = cur.getBoundingClientRect();
      if (r.bottom > window.innerHeight - 40) nav.scrollTop = cur.offsetTop - window.innerHeight / 3;
    }
  }
  var scrim = null, lastFocus = null;
  function drawer(open) {
    var btn = $('.menu-open');
    html.classList.toggle('nav-open', open);
    if (btn) btn.setAttribute('aria-expanded', String(open));
    if (open) {
      lastFocus = doc.activeElement;
      scrim = el('div', {class: 'nav-scrim'});
      scrim.addEventListener('click', function () { drawer(false); });
      doc.body.appendChild(scrim);
      var f = $('#site-nav [aria-current="page"]') || $('#site-nav a');
      if (f) setTimeout(function () { f.focus(); }, 60);
    } else {
      if (scrim) { scrim.remove(); scrim = null; }
      if (lastFocus && lastFocus.focus) lastFocus.focus();
    }
  }

  // ---------------------------------------------------------------- page head, contents, anchors
  function crumbs() {
    var head = $('.doc-head');
    if (!head || type === 'home') return;
    var c = el('nav', {class: 'crumbs', 'aria-label': 'Breadcrumb'});
    if (me && me.s) {
      if (me.s.pages[0].u !== HERE) c.append(el('a', {href: href(me.s.pages[0].u), text: me.s.title}), el('span', {class: 'sep', 'aria-hidden': 'true', text: '/'}));
      else c.append(el('a', {href: href('index.html'), text: 'nuna docs'}), el('span', {class: 'sep', 'aria-hidden': 'true', text: '/'}), el('span', {text: me.b.title}));
    } else {
      c.append(el('a', {href: href('index.html'), text: 'nuna docs'}));
    }
    if (TYPES[type] && type !== 'section') c.append(el('span', {class: 'crumb-type', text: TYPES[type]}));
    head.insertBefore(c, head.firstChild);
  }
  var tocLinks = [];
  function contents() {
    if (!article) return;
    var onlyH2 = article.getAttribute('data-toc') === 'h2';
    var hs = $$(onlyH2 ? 'h2[id]' : 'h2[id], h3[id]', article).filter(function (h) { return !h.closest('.doc-head') && !h.closest('.tab'); });
    $$('h2[id], h3[id], h4[id]', article).forEach(function (h) {
      var a = el('a', {class: 'anchor', href: '#' + h.id, 'aria-label': 'Link to this section'}, '#');
      h.insertBefore(a, h.firstChild);
    });
    if (type === 'home' || type === 'section' || hs.length < 2) { doc.body.classList.add('no-toc'); return; }
    function list() {
      var ol = el('ol');
      hs.forEach(function (h) {
        var t = h.cloneNode(true); var a0 = $('.anchor', t); if (a0) a0.remove();
        ol.appendChild(el('li', {class: h.tagName.toLowerCase()}, el('a', {href: '#' + h.id, text: t.textContent.trim()})));
      });
      return ol;
    }
    var toc = $('#toc');
    if (toc) { var l = list(); toc.append(el('p', {class: 'toc-title', text: 'On this page'}), l); tocLinks = $$('a', l); }
    var head = $('.doc-head');
    if (head) head.appendChild(el('details', {class: 'toc-inline'}, el('summary', {text: 'On this page'}), list()));
    var ticking = false;
    function spy() {
      ticking = false;
      var on = 0, top = 110;
      for (var i = 0; i < hs.length; i++) { if (hs[i].getBoundingClientRect().top < top) on = i; else break; }
      if (window.innerHeight + window.scrollY >= doc.documentElement.scrollHeight - 4) on = hs.length - 1;
      tocLinks.forEach(function (a, i) { a.classList.toggle('on', i === on); });
    }
    window.addEventListener('scroll', function () { if (!ticking) { ticking = true; requestAnimationFrame(spy); } }, {passive: true});
    spy();
  }

  // ---------------------------------------------------------------- code blocks
  var LANG = {bash: 'Shell', powershell: 'PowerShell', json: 'JSON', yaml: 'YAML', properties: 'Properties', http: 'HTTP', sql: 'SQL',
    python: 'Python', java: 'Java', scala: 'Scala', xml: 'XML', dockerfile: 'Dockerfile', ini: 'INI', text: 'Text'};
  var S_STR = [/"(?:[^"\\\n]|\\.)*"/y, 't-s'], S_SQ = [/'(?:[^'\\\n]|\\.)*'/y, 't-s'], NUM = [/\b-?\d+(?:\.\d+)?\b/y, 't-n'];
  function kw(words) { return [new RegExp('\\b(?:' + words + ')\\b', 'y'), 't-k']; }
  function kwi(words) { return [new RegExp('\\b(?:' + words + ')\\b', 'iy'), 't-k']; }
  var RULES = {
    bash: [[/(?<=^|[\s;])#.*/my, 't-c'], S_SQ, S_STR, [/\$\{?[A-Za-z_][A-Za-z0-9_]*\}?|\$env:[A-Za-z_][A-Za-z0-9_]*/y, 't-v'],
      [/(?<=\s)--?[A-Za-z][\w-]*/y, 't-k']],
    json: [[/"(?:[^"\\\n]|\\.)*"(?=\s*:)/y, 't-k'], S_STR, NUM, [/\b(?:true|false|null)\b/y, 't-f']],
    yaml: [[/(?<=^|\s)#.*/my, 't-c'], [/^(\s*-?\s*)[\w.\/-]+(?=:(\s|$))/my, 't-k', 1], S_STR, S_SQ, [/\b(?:true|false|null|yes|no)\b/y, 't-f'], NUM],
    properties: [[/^\s*[#!].*/my, 't-c'], [/^\s*[^=:#\s][^=:\n]*(?=\s*[=:])/my, 't-k']],
    ini: [[/^\s*[#;].*/my, 't-c'], [/^\s*\[[^\]\n]+\]/my, 't-f'], [/^\s*[^=:#;\s\[][^=:\n]*(?=\s*[=:])/my, 't-k']],
    sql: [[/--.*/y, 't-c'], S_SQ, kwi('select|from|where|insert|into|values|create|table|view|alter|drop|as|and|or|not|null|join|left|on|group|by|order|limit|call|using|with|update|set|delete|merge|when|matched|then|branch|tag|version|of|timestamp|system_time|for|show|namespaces|tables|describe|replace|if|exists|partitioned|schema|database|catalog|use|in|is|distinct|case|else|end|union|all|count|sum|max|min|having|asc|desc'), NUM],
    python: [[/#.*/y, 't-c'], [/"""[\s\S]*?"""|'''[\s\S]*?'''/y, 't-s'], S_STR, S_SQ, kw('from|import|as|def|return|if|elif|else|for|in|while|with|class|None|True|False|and|or|not|print|lambda|try|except|raise'), NUM],
    java: [[/\/\/.*/y, 't-c'], [/\/\*[\s\S]*?\*\//y, 't-c'], S_STR, kw('public|private|protected|static|final|class|enum|record|interface|void|new|return|if|else|for|var|import|package|throws|try|catch|null|true|false|this'), NUM],
    scala: [[/\/\/.*/y, 't-c'], [/\/\*[\s\S]*?\*\//y, 't-c'], S_STR, kw('val|var|def|object|class|import|new|if|else|for|yield|null|true|false|case|match'), NUM],
    http: [[/^(?:GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS)\b/my, 't-k'], [/^[A-Za-z][\w-]*(?=:)/my, 't-f'], [/^HTTP\/[\d.]+ \d+.*/my, 't-k'], S_STR],
    dockerfile: [[/^\s*#.*/my, 't-c'], [/^\s*(?:FROM|RUN|COPY|ADD|ENV|ARG|WORKDIR|USER|EXPOSE|ENTRYPOINT|CMD|LABEL|VOLUME|HEALTHCHECK)\b/imy, 't-k'], S_STR, [/\$\{?[A-Za-z_]\w*\}?/y, 't-v']],
    xml: [[/<!--[\s\S]*?-->/y, 't-c'], [/<\/?[\w:.-]+/y, 't-k'], S_STR, [/\/?>/y, 't-k']]
  };
  RULES.powershell = [[/(?<=^|\s)#.*/my, 't-c'], S_SQ, S_STR, [/\$(?:env:)?[A-Za-z_][A-Za-z0-9_]*/y, 't-v'], [/(?<=\s)-[A-Za-z][\w-]*/y, 't-k']];
  function highlight(text, lang) {
    var rules = RULES[lang];
    if (!rules) return null;
    var out = '', plain = '', i = 0, n = text.length;
    while (i < n) {
      var hit = null;
      for (var r = 0; r < rules.length; r++) {
        var rx = rules[r][0]; rx.lastIndex = i;
        var m = rx.exec(text);
        if (m && m[0].length) { hit = [m, rules[r]]; break; }
      }
      if (!hit) { plain += text[i]; i++; continue; }
      var m0 = hit[0], cls = hit[1][1], keep = hit[1][2] ? m0[hit[1][2]] || '' : '';
      out += esc(plain) + esc(keep) + '<span class="' + cls + '">' + esc(m0[0].slice(keep.length)) + '</span>';
      plain = ''; i += m0[0].length;
    }
    return out + esc(plain);
  }
  function copyText(t) {
    if (navigator.clipboard && window.isSecureContext !== false) return navigator.clipboard.writeText(t).catch(function () { return legacyCopy(t); });
    return legacyCopy(t);
  }
  function legacyCopy(t) {
    return new Promise(function (ok, no) {
      var ta = el('textarea', {readonly: true, style: 'position:fixed;top:-1000px;opacity:0'}); ta.value = t;
      doc.body.appendChild(ta); ta.select();
      try { doc.execCommand('copy') ? ok() : no(); } catch (e) { no(e); }
      ta.remove();
    });
  }
  function codeBlocks() {
    $$('div.code').forEach(function (box) {
      var code = $('pre > code', box);
      if (!code) return;
      var lang = box.getAttribute('data-lang') || 'text', out = box.getAttribute('data-kind') === 'output';
      var bar = el('div', {class: 'code-bar'}, el('span', {class: 'code-lang', text: out ? 'Output' : (LANG[lang] || lang)}));
      if (box.getAttribute('data-title')) bar.appendChild(el('span', {class: 'code-title', text: box.getAttribute('data-title')}));
      if (!out) {
        var b = el('button', {type: 'button', class: 'code-copy', 'aria-label': 'Copy the code' + (box.getAttribute('data-title') ? ': ' + box.getAttribute('data-title') : '')},
          icon('copy'), el('span', {text: 'Copy'}));
        b.addEventListener('click', function () {
          copyText(code.textContent).then(function () {
            b.classList.add('done'); b.replaceChildren(icon('check'), el('span', {text: 'Copied'})); say('Copied');
            setTimeout(function () { b.classList.remove('done'); b.replaceChildren(icon('copy'), el('span', {text: 'Copy'})); }, 1600);
          }, function () { say('Copying did not work: select the code and copy it.'); });
        });
        bar.appendChild(b);
      }
      box.insertBefore(bar, box.firstChild);
      if (!out && !code.children.length) {
        var h = highlight(code.textContent, lang);
        if (h != null) code.innerHTML = h;
      }
    });
  }

  // ---------------------------------------------------------------- tabs
  var GROUP_LABEL = {client: 'Ways to do this', os: 'Operating system', db: 'Database', store: 'Object store'};
  var tabSets = [], tabSeq = 0;
  function tabs() {
    $$('div.tabs').forEach(function (box) {
      var group = box.getAttribute('data-group'), panels = $$(':scope > section.tab', box);
      if (panels.length < 1) return;
      var list = el('div', {class: 'tab-list', role: 'tablist', 'aria-label': GROUP_LABEL[group] || 'Variants'});
      var set = {box: box, group: group, panels: panels, buttons: []};
      panels.forEach(function (p, i) {
        var id = 'tab-' + (++tabSeq);
        var b = el('button', {type: 'button', role: 'tab', id: id + '-b', 'aria-controls': id, 'aria-selected': 'false', tabindex: '-1',
          'data-tab': p.getAttribute('data-tab'), text: p.getAttribute('data-label') || p.getAttribute('data-tab')});
        p.id = p.id || id; b.setAttribute('aria-controls', p.id);
        p.setAttribute('role', 'tabpanel'); p.setAttribute('aria-labelledby', id + '-b'); p.setAttribute('tabindex', '0');
        b.addEventListener('click', function () { choose(group, p.getAttribute('data-tab'), set); });
        b.addEventListener('keydown', function (e) {
          var k = e.key, j = i;
          if (k === 'ArrowRight') j = (i + 1) % panels.length; else if (k === 'ArrowLeft') j = (i - 1 + panels.length) % panels.length;
          else if (k === 'Home') j = 0; else if (k === 'End') j = panels.length - 1; else return;
          e.preventDefault(); set.buttons[j].focus(); choose(group, panels[j].getAttribute('data-tab'), set);
        });
        set.buttons.push(b); list.appendChild(b);
      });
      box.insertBefore(list, box.firstChild);
      box.classList.add('ready');
      tabSets.push(set);
      var want = store('nuna-docs-tab-' + group);
      show(set, panels.some(function (p) { return p.getAttribute('data-tab') === want; }) ? want : panels[0].getAttribute('data-tab'));
    });
  }
  function show(set, v) {
    set.panels.forEach(function (p, i) {
      var on = p.getAttribute('data-tab') === v;
      p.hidden = !on;
      set.buttons[i].setAttribute('aria-selected', String(on)); set.buttons[i].setAttribute('tabindex', on ? '0' : '-1');
    });
  }
  function choose(group, v, from) {
    var before = from ? from.box.getBoundingClientRect().top : 0;
    store('nuna-docs-tab-' + group, v);
    tabSets.forEach(function (s) {
      if (s.group === group && s.panels.some(function (p) { return p.getAttribute('data-tab') === v; })) show(s, v);
    });
    if (from) window.scrollBy(0, from.box.getBoundingClientRect().top - before);
    tables();
  }

  // ---------------------------------------------------------------- tables, figures, pager, footer
  function tables() {
    $$('.table-wrap').forEach(function (w) {
      var scrolls = w.scrollWidth > w.clientWidth + 1;
      w.classList.toggle('scrolls', scrolls);
      if (scrolls) {
        var cap = $('caption', w), h = w.previousElementSibling;
        w.setAttribute('tabindex', '0'); w.setAttribute('role', 'region');
        w.setAttribute('aria-label', cap ? cap.textContent.trim() : (h && /^H[234]$/.test(h.tagName) ? h.textContent.replace(/^#/, '').trim() : 'Table'));
      } else { w.removeAttribute('tabindex'); w.removeAttribute('role'); w.removeAttribute('aria-label'); }
    });
  }
  function markers() {
    if (!$('svg [marker-end]')) return;
    var s = doc.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('aria-hidden', 'true'); s.setAttribute('width', '0'); s.setAttribute('height', '0');
    s.style.position = 'absolute'; s.style.overflow = 'hidden';
    s.innerHTML = '<defs><marker id="d-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path class="d-arrowhead" d="M0 0L10 5L0 10z"/></marker></defs>';
    doc.body.insertBefore(s, doc.body.firstChild);
  }
  function pager() {
    if (!article || type === 'home') return;
    var i = -1;
    for (var k = 0; k < flat.length; k++) if (flat[k].p.u === HERE) i = k;
    if (i < 0) return;
    var prev = flat[i - 1], next = flat[i + 1];
    if (!prev && !next) return;
    var nav = el('nav', {class: 'pager', 'aria-label': 'Previous and next pages'});
    if (prev) nav.appendChild(el('a', {class: 'prev', href: href(prev.p.u), rel: 'prev'}, el('small', {text: 'Previous'}), el('span', {text: prev.p.n || prev.p.t})));
    if (next) nav.appendChild(el('a', {class: 'next', href: href(next.p.u), rel: 'next'}, el('small', {text: 'Next'}), el('span', {text: next.p.n || next.p.t})));
    article.appendChild(nav);
  }
  function footer() {
    var f = $('#site-foot');
    if (!f) return;
    f.appendChild(el('p', null, 'nuna ' + (NAV.version || '') + ' documentation. These pages open from disk and make no network requests. ',
      el('a', {href: href('all-pages.html'), text: 'All pages'})));
  }

  // ---------------------------------------------------------------- search
  var dlg = null, input = null, results = null, note = null, sel = -1, items = [], loading = false;
  function openSearch() {
    if (!dlg) buildSearch();
    if (!dlg.open) { dlg.showModal(); input.select(); }
    if (!window.NUNA_SEARCH && !loading) {
      loading = true;
      var s = el('script', {src: href('assets/search-index.js')});
      s.onload = function () { run(); };
      s.onerror = function () { loading = false; run(); };
      doc.head.appendChild(s);
    }
    run();
  }
  function buildSearch() {
    input = el('input', {type: 'search', role: 'combobox', 'aria-expanded': 'true', 'aria-controls': 'search-results', 'aria-autocomplete': 'list',
      autocomplete: 'off', spellcheck: 'false', placeholder: 'Search pages, settings, endpoints…', 'aria-label': 'Search the docs'});
    results = el('ul', {class: 'search-results', id: 'search-results', role: 'listbox', 'aria-label': 'Results'});
    note = el('p', {class: 'search-note'});
    dlg = el('dialog', {class: 'search-dlg', 'aria-label': 'Search the docs'},
      el('div', {class: 'search-field'}, icon('search'), input, el('kbd', {text: 'Esc'})), note, results,
      el('p', {class: 'search-foot'}, el('kbd', {html: '&uarr;'}), ' ', el('kbd', {html: '&darr;'}), ' to move, ', el('kbd', {text: 'Enter'}), ' to open.'));
    doc.body.appendChild(dlg);
    input.addEventListener('input', run);
    input.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown') { e.preventDefault(); pick(Math.min(sel + 1, items.length - 1)); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); pick(Math.max(sel - 1, 0)); }
      else if (e.key === 'Enter' && items[sel]) { e.preventDefault(); location.href = items[sel].getAttribute('data-href'); dlg.close(); }
    });
    dlg.addEventListener('click', function (e) { if (e.target === dlg) dlg.close(); });
  }
  function pick(i) {
    sel = i;
    items.forEach(function (li, j) { li.setAttribute('aria-selected', String(j === i)); });
    if (items[i]) { input.setAttribute('aria-activedescendant', items[i].id); items[i].scrollIntoView({block: 'nearest'}); }
  }
  function corpus() {
    var idx = window.NUNA_SEARCH && window.NUNA_SEARCH.pages ? window.NUNA_SEARCH.pages : null, byU = {};
    if (idx) idx.forEach(function (p) { byU[p.u] = p; });
    var all = flat.concat(NAV.root.filter(function (p) { return p.u !== 'index.html'; }).map(function (p) { return {p: p, s: null}; }));
    return all.map(function (e) {
      var x = byU[e.p.u];
      return {u: e.p.u, t: e.p.t, s: e.s ? e.s.title : '', y: e.p.y, d: (x && x.l) || e.p.d.replace(/`/g, ''), h: x ? x.h : [], written: !!x};
    });
  }
  function terms(q) { return q.toLowerCase().split(/\s+/).map(function (t) { return t.replace(/^[^\w$@.\/-]+|[^\w$@.\/-]+$/g, ''); }).filter(Boolean); }
  function hits(text, ts) {
    var low = text.toLowerCase(), score = 0;
    for (var i = 0; i < ts.length; i++) {
      var at = low.indexOf(ts[i]);
      if (at < 0) return -1;
      score += (at === 0 || /[^a-z0-9]/.test(low[at - 1])) ? 2 : 1;
    }
    return score;
  }
  function mark(text, ts) {
    var out = esc(text);
    ts.forEach(function (t) {
      if (t.length < 2) return;
      out = out.replace(new RegExp('(' + esc(t).replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'ig'), '<mark>$1</mark>');
    });
    return out;
  }
  function run() {
    if (!dlg) return;
    var q = input.value.trim(), ts = terms(q), list = corpus(), haveIndex = !!window.NUNA_SEARCH;
    results.textContent = ''; items = []; sel = -1;
    if (!ts.length) {
      note.textContent = haveIndex ? 'Search the ' + list.length + ' pages: titles, headings, settings and endpoints.'
        : (loading ? 'Loading the search index…' : 'The search index is not built yet, so only page titles are searched. Build it with: python docs/site/tools/index.py');
      note.hidden = false; return;
    }
    var found = [];
    list.forEach(function (p) {
      var whole = [p.t, p.d, p.s].concat(p.h.map(function (h) { return h[1] + ' ' + (h[3] || ''); })).join(' \u0001 ');
      if (hits(whole, ts) < 0) return;
      var st = hits(p.t, ts), sd = hits(p.d, ts);
      found.push({p: p, id: null, title: p.t, where: (p.s ? p.s + ' · ' : '') + (TYPES[p.y] || ''), text: p.d,
        score: (st > 0 ? 40 + st * 4 : 0) + (sd > 0 ? 8 + sd : 0) + 2});
      var n = 0;
      p.h.forEach(function (h) {
        var sh = hits(h[1], ts), ss = h[3] ? hits(h[1] + ' ' + h[3], ts) : -1, sp = hits(p.t + ' ' + h[1] + ' ' + (h[3] || ''), ts);
        if (sp < 0 || (sh < 0 && ss < 0)) return;
        if (n++ > 6) return;
        found.push({p: p, id: h[0], title: h[1], where: (p.s ? p.s + ' · ' : '') + p.t, text: h[3] || '',
          score: (sh > 0 ? 30 + sh * 4 : 0) + (ss > 0 ? 6 + ss : 0) + (h[2] === 2 ? 2 : 0)});
      });
    });
    found.sort(function (a, b) { return b.score - a.score; });
    var per = {};
    found = found.filter(function (f) { per[f.p.u] = (per[f.p.u] || 0) + 1; return per[f.p.u] <= 3; }).slice(0, 30);
    note.hidden = found.length > 0;
    if (!found.length) note.textContent = 'No page matches “' + q + '”.' + (haveIndex ? '' : ' Only page titles are searched until the index is built.');
    found.forEach(function (f, i) {
      var url = href(f.p.u) + (f.id ? '#' + f.id : '');
      var li = el('li', {role: 'option', id: 'sr-' + i, 'aria-selected': 'false', 'data-href': url});
      var a = el('a', {href: url, tabindex: '-1'});
      a.innerHTML = '<span class="sr-title">' + mark(f.title, ts) + '</span><span class="sr-where">' + esc(f.where) + '</span>' +
        (f.text ? '<span class="sr-text">' + mark(f.text, ts) + '</span>' : '');
      a.addEventListener('click', function () { dlg.close(); });
      li.appendChild(a); results.appendChild(li); items.push(li);
    });
    if (items.length) pick(0);
  }

  // ---------------------------------------------------------------- keys, print, start
  doc.addEventListener('keydown', function (e) {
    var t = e.target, typing = t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName));
    if ((e.key === '/' && !typing && !e.ctrlKey && !e.metaKey && !e.altKey) || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k')) {
      e.preventDefault(); openSearch();
    } else if (e.key === 'Escape' && html.classList.contains('nav-open')) {
      drawer(false);
    }
  });
  var opened = [];
  window.addEventListener('beforeprint', function () { opened = $$('details:not([open])'); opened.forEach(function (d) { d.open = true; }); });
  window.addEventListener('afterprint', function () { opened.forEach(function (d) { d.open = false; }); });
  var rt = null;
  window.addEventListener('resize', function () {
    clearTimeout(rt); rt = setTimeout(function () { tables(); if (window.innerWidth >= 960 && html.classList.contains('nav-open')) drawer(false); }, 120);
  });

  function start() {
    doc.body.appendChild(live);
    header(); sidenav(); crumbs(); contents(); codeBlocks(); tabs(); markers(); pager(); footer(); shots(); tables();
    if (location.hash.length > 1) {
      var target = doc.getElementById(decodeURIComponent(location.hash.slice(1)));
      if (target) {
        var panel = target.closest('section.tab');
        if (panel && panel.hidden) {
          var set = tabSets.filter(function (s) { return s.panels.indexOf(panel) >= 0; })[0];
          if (set) show(set, panel.getAttribute('data-tab'));
        }
        requestAnimationFrame(function () { target.scrollIntoView(); });
      }
    }
  }
  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', start); else start();
})();

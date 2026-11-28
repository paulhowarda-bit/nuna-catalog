// Runs before the page paints: marks that script runs (html.js) and applies the reader's chosen theme, so a dark
// page never flashes light. No choice means Auto: the stylesheet follows the system setting.
//
// It runs after the stylesheets have loaded, so it also reads the brand slot (backlog 333): assets/brand/brand.css is
// empty here and on disk, so the site is Ice line, and a server whose install sets a brand pack serves the pack there.
// A pack is off unless the reader turned it on in the console (nuna.brand=on, the console's button); a light-only
// pack, while on, pins light and hides the theme switch (docs.css).
(function () {
  var root = document.documentElement;
  root.classList.add('js');
  var t = null, off = true;
  try {
    t = localStorage.getItem('nuna.theme');
    off = localStorage.getItem('nuna.brand') !== 'on';
  } catch (e) { /* storage blocked: Auto, and any pack off */ }
  var sheet = document.querySelector('link[href$="assets/brand/brand.css"]');
  var props = getComputedStyle(root);
  var has = function (name) { return props.getPropertyValue(name).trim() !== ''; };
  if (sheet && has('--brand-pack')) {
    root.setAttribute('data-pack', '');
    if (has('--brand-light-only')) root.setAttribute('data-pack-light', '');
    if (has('--brand-mark')) root.setAttribute('data-pack-mark', '');
    root.setAttribute('data-brand', off ? 'off' : 'on');
    if (off) {
      sheet.disabled = true;
    } else {
      var icon = document.querySelector('link[rel="icon"]');
      if (icon) icon.setAttribute('href', icon.getAttribute('href').replace(/favicon\.svg$/, 'brand/favicon.svg'));
      if (root.hasAttribute('data-pack-light')) t = 'light';
    }
  }
  if (t === 'light' || t === 'dark') root.setAttribute('data-theme', t);
})();

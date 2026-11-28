// The brand slot (backlog 333), run before the page paints. brand/brand.css always loads: the jar's is empty, so the
// console is Ice line, and an install that sets nuna.ui.brand.dir serves its pack there. A pack says it is one with
// --brand-pack, and may say --brand-light-only and --brand-mark (docs/site/configure/brand.html). A classic script
// after the stylesheets runs once they have loaded, so it reads them, marks the page, and turns the pack's stylesheet
// off unless this viewer turned the pack on (the header's button, D56: off until then; kept as nuna.brand=on in this
// browser), all before the first paint. app.js turns it on and off again from the button.
(function () {
  var root = document.documentElement;
  var sheet = document.querySelector('link[data-brand-css]');
  var props = getComputedStyle(root);
  var has = function (name) { return props.getPropertyValue(name).trim() !== ''; };
  if (!sheet || !has('--brand-pack')) return;
  root.setAttribute('data-pack', '');
  if (has('--brand-light-only')) root.setAttribute('data-pack-light', '');
  if (has('--brand-mark')) root.setAttribute('data-pack-mark', '');
  var on = false;
  try {
    on = localStorage.getItem('nuna.brand') === 'on';
  } catch (e) {
    // storage blocked: the pack stays off
  }
  root.setAttribute('data-brand', on ? 'on' : 'off');
  if (on) {
    var icon = document.querySelector('link[rel="icon"]');
    if (icon) icon.setAttribute('href', 'brand/favicon.svg');
    if (root.hasAttribute('data-pack-light')) root.setAttribute('data-theme', 'light');
  } else {
    sheet.disabled = true;
  }
})();

// Runs before the page paints: marks that script runs (html.js) and applies the reader's chosen theme, so a dark
// page never flashes light. No choice means Auto: the stylesheet follows the system setting.
(function () {
  var root = document.documentElement;
  root.classList.add('js');
  try {
    var t = localStorage.getItem('nuna.theme');
    if (t === 'light' || t === 'dark') root.setAttribute('data-theme', t);
  } catch (e) { /* storage blocked: Auto */ }
})();

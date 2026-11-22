# Offline rules and the demo skeleton

## The single-file contract
- The finished demo is ONE .html file. It opens by double-click from a laptop with no network.
- Libraries and fonts: only via markers - `<!-- @vendor NAME -->` and `<!-- @font NAME -->` (catalog appended at
  the end of the knowledge base). The packager inlines them and verifies checksums.
- Brand images: `@asset:NAME` inside `src="..."` or `url(...)`.
- No `http(s)://` resources, no `fetch`, no `import()` from URLs, no web fonts from Google, no CDNs, no analytics.
- Inline SVG namespaces (`xmlns="http://www.w3.org/2000/svg"`) are fine - they are not requests.
- Data is generated in the page with a seeded PRNG (mulberry32), or embedded as a JSON `<script type="application/json">`.
- Storage: `try { localStorage... } catch {}` - only for conveniences (last beat, theme).

## Skeleton every demo starts from
```html
<!doctype html>
<html lang="en" data-mode="guided">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Ledger, live</title>
<!-- @font space-grotesk --><!-- @font instrument-sans --><!-- @font jetbrains-mono -->
<style>
:root { /* tokens: color (OKLCH), type scale, spacing, motion - see 05/06/07 */ }
/* ... */
@media (prefers-reduced-motion: reduce) { *,*::before,*::after { animation-duration:.01ms!important;
  animation-iteration-count:1!important; transition-duration:.01ms!important; } }
</style>
</head>
<body>
<header class="frame-top"><span class="mark">...</span><nav class="beats" aria-label="Chapters"></nav></header>
<main id="stage">
  <section class="beat" id="beat-1" data-title="The answer"> ... </section>
  <section class="beat" id="beat-2" data-title="Today"> ... </section>
</main>
<footer class="frame-bottom"><p class="disclosure">Simulated data - not real accounts or results.</p>
  <p class="hint" hidden>-> next &nbsp; B blackout &nbsp; R restart &nbsp; F full screen</p></footer>
<div class="blackout" hidden></div>
<!-- @vendor anime -->
<script>
(() => {
  const beats = [...document.querySelectorAll('.beat')];
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const params = new URLSearchParams(location.search);
  document.documentElement.dataset.mode = params.get('mode') === 'present' ? 'present' : 'guided';
  let i = Math.max(0, beats.findIndex(b => '#' + b.id === location.hash));
  const enter = { /* 'beat-3': () => runPeak() ... per-beat choreography; must be idempotent */ };
  function show(n, push = true) {
    n = Math.max(0, Math.min(beats.length - 1, n));
    const swap = () => { beats.forEach((b, k) => { b.hidden = k !== n; b.setAttribute('aria-current', k === n); });
      i = n; if (push) history.replaceState(null, '', '#' + beats[n].id); (enter[beats[n].id] || (() => {}))(); renderNav(); };
    (document.startViewTransition && !reduce) ? document.startViewTransition(swap) : swap();
  }
  function renderNav() { /* dots with aria-labels from data-title; current one highlighted */ }
  addEventListener('keydown', e => {
    if (e.target.closest('input, textarea, [contenteditable]')) return;
    const k = e.key;
    if (k === 'ArrowRight' || k === ' ' || k === 'PageDown') { e.preventDefault(); show(i + 1); }
    else if (k === 'ArrowLeft' || k === 'PageUp') show(i - 1);
    else if (k === 'Home' || k === 'Escape') show(0);
    else if (k === 'End') show(beats.length - 1);
    else if (/^[1-9]$/.test(k)) show(+k - 1);
    else if (k === 'r' || k === 'R') location.reload();
    else if (k === 'f' || k === 'F') document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen?.();
    else if (k === 'b' || k === 'B') document.querySelector('.blackout').hidden ^= 1;
    else if (k === 'h' || k === 'H') document.querySelector('.hint').hidden ^= 1;
    else if (k === 'p' || k === 'P') document.documentElement.dataset.mode = document.documentElement.dataset.mode === 'present' ? 'guided' : 'present';
  });
  show(i, false);
  const hint = document.querySelector('.hint'); hint.hidden = false; setTimeout(() => hint.hidden = true, 4000);
})();
</script>
</body></html>
```
- Beats are also reachable by clicking the chapter dots and (guided mode) by scrolling or Next/Back buttons.
- Present mode (`?mode=present` or P): larger type (`html[data-mode=present] { font-size: 125%; }`), callouts hidden,
  cursor hidden after 2 s idle.
- Make each beat's enter() idempotent - going back and forth must not stack animations (keep handles, `.revert()` or
  seek to end before replaying).

## File size budget
Fonts ~25-120 KB each, anime 118 KB, d3 280 KB, three 765 KB, echarts 1.1 MB, three-webgpu 1.2 MB (raw, before
base64 fonts). Target < 3 MB total; prefer CSS/SVG/canvas over a library you use once.

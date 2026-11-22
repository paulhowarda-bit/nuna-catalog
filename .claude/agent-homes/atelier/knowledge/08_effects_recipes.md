# Effects recipes - flashy, tested, with fallbacks

All recipes were rendered in headless Chromium on 2026-10-28 (see examples/effects_gallery). Use 1-3 per screen.
Every recipe already respects `prefers-reduced-motion` when combined with the global rule in 07_motion.md.

## 1. Rotating glow border (registered angle)
```css
@property --angle { syntax: "<angle>"; inherits: false; initial-value: 0deg; }
.glow-card { border: 1.5px solid transparent; border-radius: 20px;
  background:
    linear-gradient(var(--surface), var(--surface)) padding-box,
    conic-gradient(from var(--angle), var(--line) 0 55%, var(--accent) 70%, var(--accent-2) 80%, var(--line) 95%) border-box;
  box-shadow: 0 0 48px -10px oklch(from var(--accent) l c h / .5);
  animation: spin 5s linear infinite; }
@keyframes spin { to { --angle: 360deg; } }
```
The padding-box/border-box double background keeps the interior solid (no pseudo-element stacking traps). Use on
ONE hero card (the ask or the wow). Without @property support the border is static - still fine.

## 2. Aurora background (soft moving light)
```css
.aurora { position: fixed; inset: -20vmax; z-index: -1; filter: blur(80px) saturate(140%); opacity: .55; }
.aurora i { position: absolute; width: 45vmax; aspect-ratio: 1; border-radius: 50%;
  background: radial-gradient(circle, var(--c) 0%, transparent 65%); animation: drift var(--t) var(--ease-inout) infinite alternate; }
.aurora i:nth-child(1) { --c: var(--accent);   --t: 18s; left: 10%; top: 5%; }
.aurora i:nth-child(2) { --c: var(--accent-2); --t: 23s; left: 55%; top: 30%; }
.aurora i:nth-child(3) { --c: oklch(from var(--accent) l c calc(h + 60)); --t: 27s; left: 25%; top: 55%; }
@keyframes drift { to { transform: translate(8vmax, -6vmax) scale(1.15); } }
```
`<div class="aurora" aria-hidden="true"><i></i><i></i><i></i></div>`. Put the page background on `html` only -
a background on `body` paints OVER z-index:-1 layers and hides the aurora (same for background canvases). Keep text contrast checked against the
brightest blob. Low amplitude, slow (>= 15 s) - ambient, never distracting.

## 3. Film grain (texture = craft)
```css
.grain::after { content: ""; position: fixed; inset: 0; pointer-events: none; z-index: 50; opacity: .07;
  mix-blend-mode: overlay;
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='160' height='160'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='.9' numOctaves='3' stitchTiles='stitch'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>"); }
```
(The `http://www.w3.org/2000/svg` inside an inline SVG is a namespace, not a network request.)

## 4. Cursor spotlight on a grid of cards
```css
.spot { --x: 50%; --y: 50%; background:
  radial-gradient(600px circle at var(--x) var(--y), oklch(from var(--accent) l c h / .12), transparent 40%), var(--surface); }
```
```js
document.querySelectorAll('.spot').forEach(el => el.addEventListener('pointermove', e => {
  const r = el.getBoundingClientRect();
  el.style.setProperty('--x', `${e.clientX - r.left}px`); el.style.setProperty('--y', `${e.clientY - r.top}px`);
}));
```

## 5. 3D tilt card
```js
function tilt(el, max = 8) {
  el.style.transformStyle = 'preserve-3d';
  el.addEventListener('pointermove', e => { const r = el.getBoundingClientRect();
    const px = (e.clientX - r.left) / r.width - .5, py = (e.clientY - r.top) / r.height - .5;
    el.style.transform = `perspective(900px) rotateX(${-py * max}deg) rotateY(${px * max}deg)`; });
  el.addEventListener('pointerleave', () => el.style.transform = '');
}
```
CSS: `.tilt { transition: transform var(--dur-2) var(--ease-out); }` Children with `transform: translateZ(40px)` pop
forward. Skip on touch (`matchMedia('(hover: hover)')`) and under reduced motion.

## 6. Glass panel (with all fallbacks)
```css
.glass { background: oklch(from var(--surface) l c h / .62); border: 1px solid oklch(100% 0 0 / .08);
  -webkit-backdrop-filter: blur(16px) saturate(160%); backdrop-filter: blur(16px) saturate(160%); }
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) { .glass { background: var(--surface); } }
@media (prefers-reduced-transparency: reduce) { .glass { background: var(--surface); backdrop-filter: none; -webkit-backdrop-filter: none; } }
```
Sparingly: a nav bar, a modal, one floating card. Check text contrast over the busiest background behind it.

## 7. Live number with up/down flash
```js
function setNumber(el, next, fmt = v => v.toLocaleString('en-US')) {
  const prev = +el.dataset.v || 0; el.dataset.v = next;
  const o = { v: prev };
  anime.animate(o, { v: next, duration: 650, ease: 'outExpo', onUpdate: () => el.textContent = fmt(o.v) });
  el.classList.remove('up', 'down'); void el.offsetWidth; el.classList.add(next >= prev ? 'up' : 'down');
}
```
```css
.num { font-variant-numeric: tabular-nums; }
.num.up { animation: flashUp 900ms var(--ease-out); } .num.down { animation: flashDown 900ms var(--ease-out); }
@keyframes flashUp { from { background: oklch(from var(--up) l c h / .28); } to { background: transparent; } }
@keyframes flashDown { from { background: oklch(from var(--down) l c h / .28); } to { background: transparent; } }
```
Pair with an arrow glyph (+ / U+2212) - never color alone. Numbers never overshoot their true value.

## 8. Text scramble (decode effect for one headline word)
```js
function scramble(el, text, ms = 900) {
  const glyphs = '01$%#@&*+=<>/\\', start = performance.now();
  (function frame(t) { const p = Math.min(1, (t - start) / ms), n = Math.floor(p * text.length);
    el.textContent = text.slice(0, n) + Array.from(text.slice(n), c => c === ' ' ? ' ' :
      glyphs[(Math.random() * glyphs.length) | 0]).join('');
    if (p < 1) requestAnimationFrame(frame); })(start);
}
```
Set `aria-label` to the final text so screen readers don't hear noise.

## 9. Headline rising from a slot (anime splitText)
See 06_typography.md. Add `.hero h1 { overflow: hidden; }` or wrap lines; stagger 15-25 ms per char, 40-60 ms
per word.

## 10. Magnetic button (for the ask)
```js
function magnetic(btn, strength = .25) {
  btn.addEventListener('pointermove', e => { const r = btn.getBoundingClientRect();
    btn.style.transform = `translate(${(e.clientX - r.left - r.width / 2) * strength}px, ${(e.clientY - r.top - r.height / 2) * strength}px)`; });
  btn.addEventListener('pointerleave', () => btn.style.transform = '');
}
```

## 11. Scroll progress bar (CSS with JS fallback)
```css
.progress { position: fixed; inset: 0 0 auto 0; height: 3px; background: var(--accent); transform-origin: 0 50%; transform: scaleX(var(--p, 0)); }
@supports (animation-timeline: scroll()) {
  .progress { animation: grow linear both; animation-timeline: scroll(root); }
  @keyframes grow { from { transform: scaleX(0); } to { transform: scaleX(1); } }
}
```
```js
if (!CSS.supports('animation-timeline: scroll()')) addEventListener('scroll', () => {
  const h = document.documentElement; h.style.setProperty('--p', h.scrollTop / (h.scrollHeight - h.clientHeight)); }, { passive: true });
```

## 12. Reveal on scroll (content visible by default!)
```css
@supports (animation-timeline: view()) {
  @media (prefers-reduced-motion: no-preference) {
    .reveal { animation: rise linear both; animation-timeline: view(); animation-range: entry 0% entry 80%; }
    @keyframes rise { from { opacity: 0; transform: translateY(40px); } to { opacity: 1; transform: none; } }
  }
}
```
Add `@media print { .reveal { animation: none; opacity: 1; } }` so printed/PDF copies show everything.
Use an `entry`-only range: elements near the end of the page may never reach a `cover` range and
would stay half-faded. JS fallback when unsupported: IntersectionObserver adds `.in` and a transition handles it. Never hide content
waiting for JS - if scripts fail, everything must still be visible.

## 13. View transition between beats
```js
function go(n) { const run = () => render(n);
  document.startViewTransition && !reduce ? document.startViewTransition(run) : run(); }
```
```css
::view-transition-old(root) { animation: 260ms var(--ease-in) both fadeOut; }
::view-transition-new(root) { animation: 420ms var(--ease-out) both riseIn; }
@keyframes fadeOut { to { opacity: 0; transform: translateY(-12px); } }
@keyframes riseIn { from { opacity: 0; transform: translateY(16px); } }
.hero-num { view-transition-name: hero-num; }  /* morphs the same element across beats */
```

## 14. Ticker tape marquee
```css
.tape { overflow: hidden; mask-image: linear-gradient(90deg, transparent, #000 8%, #000 92%, transparent); }
.tape > div { display: flex; gap: 3rem; width: max-content; animation: tape 40s linear infinite; }
@keyframes tape { to { transform: translateX(-50%); } }   /* content duplicated twice */
.tape:hover > div { animation-play-state: paused; }
```

## 15. Particle field (2D canvas, cheap)
```js
function particles(canvas, n = 140, color = getComputedStyle(document.documentElement).getPropertyValue('--accent')) {
  const ctx = canvas.getContext('2d'), dpr = Math.min(devicePixelRatio, 2), rand = anime.createSeededRandom(7);
  let w, h, pts, raf; const fit = () => { w = canvas.clientWidth; h = canvas.clientHeight;
    canvas.width = w * dpr; canvas.height = h * dpr; ctx.setTransform(dpr, 0, 0, dpr, 0, 0); };
  fit(); addEventListener('resize', fit);
  pts = Array.from({ length: n }, () => ({ x: rand(0, w), y: rand(0, h), vx: rand(-20, 20) / 100, vy: rand(-20, 20) / 100 }));
  const tick = () => { ctx.clearRect(0, 0, w, h); ctx.fillStyle = color; ctx.strokeStyle = color;
    for (const p of pts) { p.x = (p.x + p.vx + w) % w; p.y = (p.y + p.vy + h) % h;
      ctx.globalAlpha = .7; ctx.fillRect(p.x, p.y, 1.6, 1.6); }
    ctx.globalAlpha = .12; ctx.beginPath();
    for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) { const a = pts[i], b = pts[j];
      if (Math.abs(a.x - b.x) + Math.abs(a.y - b.y) < 90) { ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); } }
    ctx.stroke(); raf = requestAnimationFrame(tick); };
  if (!matchMedia('(prefers-reduced-motion: reduce)').matches) tick();
  document.addEventListener('visibilitychange', () => document.hidden ? cancelAnimationFrame(raf) : tick());
}
```
Canvas colors: `getComputedStyle` returns the token string; canvas accepts oklch() in current Chromium/Safari - for
safety convert brand colors to hex/rgb for canvas.

## 16. Shine sweep on gradient text
```css
.shine { background: linear-gradient(110deg, var(--text) 35%, var(--accent) 50%, var(--text) 65%) 0 0 / 250% 100%;
  -webkit-background-clip: text; background-clip: text; color: transparent; animation: sweep 3.5s var(--ease-inout) 1 both; }
@keyframes sweep { from { background-position: 100% 0; } to { background-position: 0 0; } }
```
Run once (not infinite) on the key phrase.

## 17. Before/after wipe (sales pattern)
```html
<div class="wipe" style="--pos:50%"><div class="before">...</div><div class="after">...</div>
  <input type="range" min="0" max="100" value="50" aria-label="Compare legacy and modern" oninput="this.parentNode.style.setProperty('--pos', this.value + '%')"></div>
```
```css
.wipe { position: relative; } .wipe > .after { position: absolute; inset: 0; clip-path: inset(0 0 0 var(--pos)); }
.wipe input { position: absolute; inset: 0; width: 100%; opacity: 0; cursor: ew-resize; }
.wipe::after { content: ""; position: absolute; top: 0; bottom: 0; left: var(--pos); width: 2px; background: var(--accent); }
```
Same scale and same window on both sides.

## 18. Scrubbable number in a sentence (Bret Victor)
```js
function scrub(el, { min, max, step = 1, onChange }) { let startX, startV;
  el.style.cursor = 'ew-resize'; el.tabIndex = 0; el.setAttribute('role', 'slider');
  el.setAttribute('aria-valuemin', min); el.setAttribute('aria-valuemax', max);
  const set = v => { v = Math.min(max, Math.max(min, Math.round(v / step) * step)); el.textContent = v;
    el.setAttribute('aria-valuenow', v); onChange(v); };
  el.addEventListener('pointerdown', e => { startX = e.clientX; startV = +el.textContent; el.setPointerCapture(e.pointerId); });
  el.addEventListener('pointermove', e => { if (startX != null) set(startV + (e.clientX - startX) / 4 * step); });
  el.addEventListener('pointerup', () => startX = null);
  el.addEventListener('keydown', e => { if (e.key === 'ArrowUp' || e.key === 'ArrowRight') set(+el.textContent + step);
    if (e.key === 'ArrowDown' || e.key === 'ArrowLeft') set(+el.textContent - step); });
}
```
Style: dotted underline in the accent. Keep a ghost of the baseline in the chart it drives.

## 19. Line that draws itself, then becomes data
```js
const path = document.querySelector('#signature');                 // SVG path built from the series
anime.animate(anime.svg.createDrawable(path), { draw: ['0 0', '0 1'], duration: 1400, ease: 'inOutQuad' });
```
Build `d` from data with `d3.line()` or by hand. This is the classic Bass move: the symbol (a line) becomes the
chart, later the navigation.

## Anti-recipes (never)
Purple gradient on white; glass everywhere; infinite pulsing KPIs; parallax on data; 3D pie charts; cursor trails;
autoplaying sound; anything that moves while someone is reading numbers.

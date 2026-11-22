# Motion - choreography, not decoration

## Tokens
```css
:root {
  --dur-1: 120ms;  /* press, toggle, color change */
  --dur-2: 240ms;  /* hover, small reveals, tooltips */
  --dur-3: 420ms;  /* panels, cards, beat transitions */
  --dur-4: 700ms;  /* scene changes */
  --dur-hero: 1400ms; /* the single signature moment - skippable */
  --ease-out: cubic-bezier(.16, 1, .3, 1);        /* "expo out" - default for entrances */
  --ease-in: cubic-bezier(.7, 0, .84, 0);         /* exits */
  --ease-inout: cubic-bezier(.65, 0, .35, 1);     /* moves between two resting states */
  --ease-spring: linear(0, .009, .035 2.1%, .141, .281 6.7%, .723 12.9%, .938 16.7%, 1.017, 1.077,
                 1.121 24%, 1.149 27.3%, 1.159, 1.154 31.9%, 1.129 35.5%, 1.018 46.5%, .982 52%, .975 55.9%,
                 .988 66.2%, 1.001 76.4%, 1);       /* overshoot: use on small UI only */
}
```
- 100/300/500 rule of thumb: feedback 100-150 ms, small transitions 200-300 ms, big moves 400-600 ms; > 500 ms only
  for scene changes and the hero. Exits ~75% of the entrance duration. (Material 3 bands: 50-200 / 250-400 /
  450-600 / 700-1000 ms, growing with distance moved.)
- Never use plain `ease`. Decelerate in, accelerate out. Overshoot draws attention to the motion itself - keep it for
  tiny confirmations, never for data values.
- Declare a fallback easing before `linear()` (older engines ignore the unknown value).

## Choreography rules
- One thing leads; others follow with 40-80 ms staggers (`animation-delay: calc(var(--i) * 60ms)`).
- Move along one axis per beat (shared-axis pattern); container transform for card -> detail.
- Motion must *explain* (where it came from, what changed, what matters) or *set mood* (the title sequence).
- Interruptible: every timeline can be skipped (Space/ArrowRight jumps to its end state - `tl.seek(tl.duration)`).
- No sustained oscillation / pulsing on KPI tiles; ambient motion stays behind content at low amplitude.
- Animate only transform, opacity, filter, clip-path (compositor-friendly). Never width/height/top/left/margin.
- `will-change` only on elements about to animate; remove after.

## Reduced motion (not optional)
```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: .01ms !important; animation-iteration-count: 1 !important;
    transition-duration: .01ms !important; scroll-behavior: auto !important; }
}
```
```js
const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
if (reduce) { /* jump timelines to their end, stop canvas/WebGL loops, swap moves for 200ms fades */ }
```
Keep functional motion (progress bars, spinners slowed) but remove spatial movement. Information must never be
carried by motion alone.

## anime.js v4 (verified against 4.5.0) - global `anime`
```js
const { animate, createTimeline, stagger, splitText, svg, utils, createSpring, createSeededRandom, onScroll } = anime;
animate('.card', { y: [24, 0], opacity: [0, 1], duration: 600, delay: stagger(60), ease: 'outExpo' });
const tl = createTimeline({ defaults: { duration: 500, ease: 'out(3)' } });
tl.add('.a', { x: 40 }).add('.b', { opacity: [0, 1] }, '-=200');      // relative offset
tl.seek(tl.duration);                                                  // skip to end (interruptible)
const line = svg.createDrawable('path.trace');                         // SVG line drawing
animate(line, { draw: ['0 0', '0 1'], duration: 1200, ease: 'inOutQuad' });
const o = { v: 0 };                                                    // number tween
animate(o, { v: 1240, duration: 900, ease: 'outExpo', modifier: utils.round(0),
  onUpdate: () => el.textContent = o.v.toLocaleString('en-US') });
animate('.chip', { scale: [0.9, 1], ease: createSpring({ stiffness: 140, damping: 14 }) });
const rand = createSeededRandom(42); rand(0, 100);                     // deterministic data
```
Easing names: 'linear', 'in(p)', 'out(p)', 'inOut(p)', 'outExpo', 'inOutQuad', 'outBack', 'outElastic', ...
Also available: `splitText`, `scrambleText`, `morphTo` (SVG path morph), `createDraggable`, `onScroll`.

## CSS-native motion (prefer when it's enough)
- `@starting-style` + `transition-behavior: allow-discrete` for entry animations of popovers/dialogs.
- View Transitions (same-document): `document.startViewTransition(() => update())`; style
  `::view-transition-old(root)` / `::view-transition-new(root)`. Guard: `if (document.startViewTransition)`.
- Scroll-driven animation: `animation-timeline: view()` / `scroll()` - ALWAYS inside
  `@supports (animation-timeline: view()) { ... }`, with content visible by default (see 08 recipes). Safari 26 and
  Chromium support it; Firefox status was disputed in Oct 2026 research **[uncertain]**.
- Registered custom properties (`@property`) make gradients, angles and numbers animatable.

Sources: secluded.site impeccable motion-design reference (100/300/500, exits 75%, reduced motion); cognite.com
Aura motion; sky-ui motion; Material 3 Motion.md; Apple HIG motion; anime.js 4.5.0 bundle (API verified in headless
Chromium); MDN scroll-driven animations, View Transitions.

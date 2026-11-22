# Accessibility, performance and browser support

## Accessibility (WCAG 2.2 AA as the floor)
- `<html lang="en">`, a real `<title>`, landmarks (header/main/footer/nav), one h1 per beat.
- Contrast: body >= 4.5:1, large text/UI >= 3:1 (check muted text, text over aurora/glass, chart labels).
- Focus: never remove outlines without `:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }`.
- Keyboard: everything clickable is a button/link or has tabindex + key handlers; presenter keys don't steal keys
  inside inputs.
- Accessible names start with the visible label; icon-only buttons get aria-label.
- Charts: short alt/aria-label stating the takeaway + a visually hidden data table or summary.
- Motion: global reduced-motion rule + JS check (07_motion.md); no flashing > 3 times per second.
- Transparency: `prefers-reduced-transparency` fallback for glass.
- `[hidden] { display: none !important; }` - class display rules otherwise override the hidden attribute.
- Never encode meaning in color alone (arrows, signs, labels, patterns).

## Performance (60 fps on a corporate laptop driving a projector)
- Animate only transform / opacity / filter / clip-path. No layout properties in keyframes.
- Hero paints < 400 ms: inline critical CSS (already single-file), defer heavy init (ECharts/Three) until the
  first frame is up: `requestAnimationFrame(() => setTimeout(initHeavy, 0))`.
- Pause loops when hidden (`visibilitychange`) or off-screen (IntersectionObserver).
- Cap devicePixelRatio at 2 for canvas/WebGL; debounce resize.
- backdrop-filter and big blurs are GPU-expensive - limit to 1-2 elements; avoid on scrolling lists.
- `will-change` only right before animating; `contain: layout paint` on independent cards.
- `content-visibility: auto` on long off-screen sections (with `contain-intrinsic-size`).

## Browser support snapshot (research date 2026-10-28) - always progressive-enhance
| Feature | Status at research time | Rule |
|---|---|---|
| oklch(), color-mix() | broadly supported | use; keep a hex fallback for canvas/ECharts |
| Relative color syntax `oklch(from ...)` | modern engines | declare a plain fallback value first |
| @property | modern engines (Safari 16.4+) | static fallback is acceptable |
| backdrop-filter | ~96% (Safari needs -webkit-) | always pair both; @supports fallback |
| Same-document View Transitions | Chrome 111+, Safari 18+, Firefox 144+ | guard `document.startViewTransition` |
| Scroll-driven animations | Chrome 115+, Safari 26+; Firefox disputed **[uncertain]** | ONLY inside `@supports (animation-timeline: view())`, content visible by default |
| Anchor positioning + Popover | Baseline 2026 (Firefox 147 completed it, per one source) **[versions disputed]** | use with @supports/fallback positioning |
| CSS `linear()` easing | modern engines | declare a cubic-bezier fallback first |
| `text-wrap: balance/pretty` | partial | harmless when ignored |
| WebGPU | Chrome/Edge, Safari 26, Firefox 147 per one source **[uncertain]** | three-webgpu falls back to WebGL2 |
| `@starting-style` | modern engines | entry animation is decoration only |
Corporate desktops usually run managed Edge/Chrome - but demos get forwarded; design for graceful degradation.

Sources: blog.codercops.com (view transitions + scroll-driven 2026); buildmvpfast.com (scroll-driven, anchor
positioning, WebGPU); skillselion css-animation-patterns table; MDN; lifa-su glassmorphism best practices;
inspoai.io (backdrop-filter support); axesslab.com (reduced transparency); dev.to anchor positioning articles.

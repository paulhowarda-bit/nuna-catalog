# Color - OKLCH tokens that pop and stay honest

## Why OKLCH
Perceptually uniform: equal L steps look equally lighter across hues, so palettes stay balanced and contrast is
predictable. Modern CSS supports `oklch(L C H)` plus relative color syntax to derive states from one token.

```css
:root {
  /* reference ramp */
  --bg:      oklch(15% 0.02 260);
  --surface: oklch(20% 0.025 260);
  --line:    oklch(32% 0.03 260);
  --text:    oklch(96% 0.01 260);
  --muted:   oklch(76% 0.02 260);     /* still >= 4.5:1 on --bg */
  --accent:  oklch(80% 0.16 165);     /* the ONE accent */
  --accent-2:oklch(78% 0.15 75);      /* rare secondary */
  --up:      oklch(78% 0.16 150);
  --down:    oklch(68% 0.19 25);
  /* derived states */
  --accent-hover: oklch(from var(--accent) calc(l + 0.06) c h);
  --accent-soft:  oklch(from var(--accent) l c h / 0.14);
  --accent-ink:   oklch(from var(--accent) 22% calc(c * 0.6) h);   /* text ON the accent */
}
```
Always also declare a plain fallback before any relative-color declaration (older engines drop the whole
declaration they can't parse): `background: oklch(83% 0.15 165); background: var(--accent-hover);`
`color-mix(in oklab, var(--accent) 30%, transparent)` is a widely supported alternative for tints.

## Rules
- **One accent per screen** (Von Restorff). Everything else neutral. A second accent only for a second meaning.
- Neutrals carry a tiny chroma (0.01-0.03) of the accent's hue - "tinted neutrals" feel designed; pure gray feels
  default.
- Contrast: body text >= 4.5:1; large text and UI parts >= 3:1. Check muted text especially.
- **Projector reality:** projectors wash out darks and low-chroma differences. For live rooms: background L <= 18%
  OR >= 96%, text L >= 94% (dark) or <= 25% (light), and no meaning carried by subtle tints. Offer a light theme
  for bright rooms (`?theme=light` or T key).
- Glow/neon works on dark: accent at L 75-85%, C 0.15-0.25; add `filter: drop-shadow(0 0 24px var(--accent-soft))`.
- Gradients: interpolate in OKLCH to avoid the gray dead zone: `linear-gradient(in oklch, A, B)`; keep them to
  backgrounds and hero type, never on data marks.
- Data colors: up/down hues must differ in *lightness* too (color-blind safety) and always pair with a sign/arrow.
  Categorical series: max 5-6 hues at matched L; sequential: one hue, L ramp; diverging: two hues meeting at a
  neutral midpoint.
- Avoid the clichés: purple-to-blue gradient on white, generic "SaaS indigo", rainbow charts.

## Palette starters (fresh direction - adapt, don't copy)
| Mood | bg | text | accent | accent-2 |
|---|---|---|---|---|
| Signal room (calm power) | oklch(14% 0.02 250) | oklch(96% 0.01 250) | oklch(82% 0.17 160) teal-green | oklch(80% 0.14 80) amber |
| Editorial heat | oklch(97% 0.01 80) paper | oklch(20% 0.02 60) | oklch(62% 0.22 30) vermilion | oklch(30% 0.05 260) ink |
| Night market | oklch(13% 0.03 290) | oklch(95% 0.01 290) | oklch(78% 0.2 330) magenta | oklch(85% 0.17 110) lime |
| Mineral | oklch(93% 0.01 200) | oklch(22% 0.03 220) | oklch(55% 0.13 200) deep cyan | oklch(70% 0.12 60) ochre |
| Terminal | oklch(10% 0 0) | oklch(92% 0 0) | oklch(85% 0.2 140) phosphor | oklch(75% 0.17 60) |

## Brand mode
Use the brand pack's tokens as the anchor hues. Derive tints/shades in OKLCH (keep the hue, move L and C). You may
add one neutral ramp. Flash comes from light (glows, gradients *within* brand hues), motion, scale and
composition - never from off-brand hues.

## Reduced transparency
```css
@media (prefers-reduced-transparency: reduce) { .glass { background: var(--surface); backdrop-filter: none; } }
```
Sources: 12daysofweb.dev (CSS color spaces), blog.openreplay.com (relative color syntax), dialtone.dialpad.com
(HSL->OKLCH migration), frontendchecklist.io (oklch tokens), skills.sh color-ops; WCAG 2.x contrast.

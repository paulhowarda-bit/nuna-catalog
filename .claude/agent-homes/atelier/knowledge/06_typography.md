# Typography - type is half the design

## Offline catalog (only these load; reference with `<!-- @font NAME -->`)
| Marker | family | Axes / weights | Personality | Good as |
|---|---|---|---|---|
| space-grotesk | Space Grotesk | wght 300-700 | techy, confident, quirky geometry | display, UI |
| bricolage-grotesque | Bricolage Grotesque | wght 200-800, opsz, wdth 75-100 | characterful, poster | display, chapter openers |
| unbounded | Unbounded | wght 200-900 | wide, loud, futuristic | big numerals, short headlines |
| sora | Sora | wght 100-800 | friendly-future, fintech | display + UI |
| inter-tight | Inter Tight | wght 100-900 (+ital) | dense, precise | UI labels, tight display |
| instrument-sans | Instrument Sans | wght 400-700, wdth | neutral, crisp | body, UI |
| fraunces (+ fraunces-italic) | Fraunces | wght 100-900, opsz 9-144, SOFT 0-100, WONK 0-1 | editorial, warm, expressive | display serif, pull quotes |
| instrument-serif (+ -italic) | Instrument Serif | 400 | condensed editorial | elegant headlines |
| jetbrains-mono | JetBrains Mono | wght 100-800 | engineering | numbers, code, tickers |
| geist-mono | Geist Mono | wght 100-900 | clean modern mono | numbers, labels |

Generic primary fonts (Inter, Roboto, Arial, Helvetica, system-ui, Open Sans, Montserrat, Poppins) read as
"AI default" - use them only as fallbacks. Note Inter **Tight** is a different, tighter cut and is fine.

## Pairings that work
- Space Grotesk (display) + Instrument Sans (text) + JetBrains Mono (numbers) - modern fintech.
- Fraunces opsz 144 (display) + Inter Tight (UI) + Geist Mono (data) - editorial authority.
- Unbounded (numerals, short heads) + Instrument Sans - loud, futurist.
- Instrument Serif italic (one emotive word) + Space Grotesk - fashion-editorial contrast.
- Bricolage Grotesque wdth 75 wght 800 (chapter openers) + Inter Tight - poster energy (Scher mode).
Max: one display, one text, one mono.

## Scale (Vignelli: few sizes, big contrast)
```css
:root {
  --step--1: clamp(0.8rem, 0.78rem + 0.1vw, 0.875rem);   /* labels, captions */
  --step-0:  clamp(1rem, 0.96rem + 0.2vw, 1.125rem);     /* body */
  --step-2:  clamp(1.5rem, 1.2rem + 1.2vw, 2.25rem);     /* section heads */
  --step-5:  clamp(3.5rem, 1.5rem + 7vw, 8.5rem);        /* hero, numbers */
}
```
Skip intermediate steps deliberately. Hero numerals can go to 12-20vw for a Scher moment.
Projector: body >= 20px equivalent at 1920 wide; labels >= 14px; never below 12px anywhere.

## Details that read as craft
- `font-variant-numeric: tabular-nums;` on every number that changes or aligns; `lining-nums` for figures in caps
  contexts; `font-feature-settings: "ss01"` only if you know the font has it.
- Tight display tracking: `letter-spacing: -0.02em` to `-0.04em` above 48px; open tracking (+0.06 to +0.12em) for
  small caps labels in uppercase - uppercase only for tiny labels.
- `text-wrap: balance` on headlines, `text-wrap: pretty` on paragraphs (ignored where unsupported - harmless).
- Line length 45-75 characters; line-height 1.05-1.15 for display, 1.5-1.6 for body.
- Hanging punctuation by hand: pull opening quotes into the margin with negative indent on hero quotes.
- Optical sizing: Fraunces `font-variation-settings: "opsz" 144` for big, `"opsz" 14` for small.

## Variable-font motion (cheap, flashy, on-brand)
```css
@property --w { syntax: "<number>"; inherits: true; initial-value: 300; }
.hero-word { font-variation-settings: "wght" var(--w); transition: --w 600ms cubic-bezier(.16,1,.3,1); }
.hero-word:hover, .hero-word.is-on { --w: 800; }
```
Fraunces `"SOFT" 100, "WONK" 1` on hover gives a playful morph; Bricolage `wdth` 75->100 makes headlines breathe.
Animate weight on short words only (reflow cost).

## Split-text reveals (anime.js v4)
```js
const { splitText, animate, stagger } = anime;
const { chars } = splitText('.hero h1', { chars: true });   // keeps accessible text automatically
animate(chars, { y: ['110%', '0%'], opacity: [0, 1], delay: stagger(18), duration: 700, ease: 'outExpo' });
```
Wrap the heading in `overflow: hidden` lines for the "rising from a slot" look. Reduced motion: skip, show text.

Sources: Fontsource package metadata (axes verified 2026-10-28); Anthropic frontend-design skill (generic-font
warning); Vignelli Canon (few sizes); MDN text-wrap, font-variant-numeric.

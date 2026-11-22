# Layout and composition

## Grid
```css
.grid { display: grid; grid-template-columns: repeat(12, minmax(0, 1fr)); column-gap: clamp(16px, 2vw, 24px);
  padding-inline: max(16px, 4vw); max-width: 1680px; margin-inline: auto; }
```
- 8px spacing scale: 4, 8, 12, 16, 24, 32, 48, 64, 96, 128. Section padding 96-160px desktop.
- Asymmetry (Swiss): hero claim on columns 1-7, air or the visual on 8-12. Centered symmetric layouts read as
  templates - use them only for the final ask, where the eye must land in one place.
- Align every text block's left edge to a column line. One dominant element per view.
- 30-40% empty space per screen (Reynolds). Air is what makes things look expensive.
- Mobile: single column, the dominant element first, presenter keys irrelevant - swipe/scroll beats.

## Composition archetypes for selling demos (pick one per demo, vary per beat)
1. **Cinematic hero** - full-bleed dark stage, one symbol animating, giant claim, tiny label. Opening beats.
2. **Big number** - one KPI at 12-20vw with a sparkline and a one-line meaning beneath. The proof beat.
3. **Split before/after** - two halves, same scale; the wipe handle is the interaction. The turn.
4. **Bento** - 5-7 cards of unequal size on the grid (one 2x2 hero card, the rest 1x1 / 2x1); card radius 16-24px,
   1px hairline borders, one card glowing. Capability overview.
5. **Editorial** - big serif headline, narrow text column, marginalia annotations, data inline. Narrative beats.
6. **Terminal / control room** - dense, mono numbers, 1px grid lines, near-monochrome + one signal color. For
   operations audiences who love density.
7. **Scrollytelling** - sticky visual on one side, steps scroll on the other; each step mutates the visual.
8. **Situation card** - a single framed card with 5-6 fields the viewer can edit; feeds later numbers.
9. **The ask** - centered, generous, one button, recap of three reasons in small type, share link.

## Presenter frame (persistent chrome, quiet)
- Top-left: tiny wordmark/title. Top-right: beat progress (dots or "3 / 7") + mode toggle.
- Bottom-left: persistent disclosure ("Simulated data - not real accounts or results"), small but >= 4.5:1.
- Bottom-right: hint chip ("-> next  B blackout  R restart") shown 4 s on load, then hidden (H toggles).
- Nothing else persists. The stage is for the story.

## Visual hierarchy test
Blur the screenshot (or squint): the claim and the ask must still be the two most visible things. If a decoration
wins, demote it.

## Responsive
- Design at 1920x1080 (projector) and 390x844 (phone) first; everything between interpolates with clamp().
- No horizontal scroll at 360px; 16px minimum side gutter; tap targets >= 44px.
- `100dvh` for full-screen stages (not 100vh); test landscape phones.

Sources: Muller-Brockmann/Swiss style; Reynolds (empty space); NN/g visual hierarchy; Wroblewski (mobile first).

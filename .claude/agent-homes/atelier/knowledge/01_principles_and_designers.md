# Principles from the great graphic designers, as web-demo rules

Numbers in "web" lines (ratios, px, ms) are house defaults chosen for this agent, not the designers' own
specifications. Rules are paraphrased from the sources at the end.

## Method first
- **Write the idea before designing.** One sentence (what the audience must believe) and one symbol (a
  shape that can carry it and transform). If you cannot write both, you are not ready to design.
- **Lock constraints up front** (Rand's "rules of the game"): palette (2 hues + neutrals), one type family
  (+ optional serif or mono), one grid, one easing family, one shape motif. Constraints create originality.

## Massimo Vignelli - The Vignelli Canon
- *Semantics*: find what the subject means; every element must carry meaning or be cut.
- *Syntactics*: grid, type, headings and images behave as one connected language.
- *Pragmatics*: if the audience can't read it, it failed - however clever.
- Very few type sizes with huge contrast between them; each weight has one job; never enlarge to shout.
- White space is the active element - his line: "in a world where everybody screams, silence is noticeable."
- Flush-left, timeless faces over fashionable ones.
- **Web:** one display size ~6-8x the body (e.g. 112px over 16px); 12-col grid; one hero number per screen;
  color only where it encodes meaning.

## Dieter Rams - ten principles (Vitsoe)
Innovative, useful, aesthetic, understandable, unobtrusive, honest, long-lasting, thorough to the last detail,
environmentally considerate, and as little design as possible ("less, but better").
- **Web:** neutral chrome so content leads; design loading/empty/error states too; never make the product look
  more finished or capable than it is (label simulations); avoid trend effects that date in a year.

## Josef Muller-Brockmann - Swiss International Style
- The grid brings objectivity and order; set proportions first, then place content.
- Asymmetric composition with active negative space; flush-left ragged-right sans-serif; objective imagery.
- A grid helps but guarantees nothing.
- **Web:** CSS Grid, 12 columns, 24px gutter, 8px baseline; hero headline on columns 1-7, air on 8-12; keep
  every scroll section on the same column structure.

## Paul Rand
- The idea first; fiddling with form is a symptom of too few ideas.
- Constraints are the game's rules; simplicity is hard and worth it; a mark must survive tiny and in one color.
- Wit and play belong in serious work when they sharpen meaning.
- **Web:** one witty micro-moment per demo tied to the content (e.g. the brand motif in a hover).

## Saul Bass - symbolize and summarize
- Reduce the story to one image that can move; the title sequence starts the experience at frame one and sets
  the mood like a decompression chamber; make the ordinary extraordinary; storyboard every beat.
- **Web:** a 3-6s opening built on one symbol (a line that draws itself -> becomes a price chart -> becomes the
  navigation); flat shapes, two colors, high contrast; the symbol returns at the end to close the loop.

## Paula Scher (Pentagram) - type is image
- Typography as the picture: scale, density, mixed weights, cropping off the edge.
- A flexible system rather than a template (the Public Theater identity changes each season, DNA stays).
- Bold, limited color per campaign; the identity is "always on" across media.
- **Web:** chapter openers with one word or number at 20-40vw, cropped by the viewport, heavy/condensed, moving on
  scroll; 2-3 saturated colors + black. Keep data screens disciplined.

## Edward Tufte - show the data
- Truth first: visual effect proportional to data effect (lie factor ~1); show baselines and scales.
- Maximize data-ink; erase non-data ink; no chartjunk (no 3D bars, shadows, gradient fills on marks).
- Small multiples on shared scales for comparison; layer and separate; label directly instead of legends.
- Sparklines: word-sized, dense; mark last/high/low; a gray band for the normal range.
- Color labels or measures - "above all, do no harm".
- **Web:** never truncate bar axes; 1px gridlines at 10-15% opacity; line labels at the right end; a sparkline
  beside every KPI; counters animate only to the true value, no overshoot.

## Otl Aicher (Munich 1972) and Susan Kare (early Macintosh)
- Aicher: design the system - pictograms on one constrained geometry, color coding by function.
- Kare: metaphor over depiction; recognizable at first sight; reduce on a strict grid; avoid culture-bound puns;
  test informally with many people.
- **Web:** icons on a 24px grid, one 1.5-2px stroke, one corner radius, 45-degree angle vocabulary; pair icons
  with text labels for domain terms (settlement, margin); one fixed hue per business domain everywhere.

## Wim Crouwel
- The modular grid *is* the identity; adapt type to the technology; know when a system has gone too far
  (he later admitted his New Alphabet was unreadable).
- **Web:** dot-matrix or grid-built numerals are fine for a display ticker motif - never for values a decision
  depends on.

## Milton Glaser
- Design makes people attentive; leave a small gap the viewer closes ("a little jump before you understand it");
  design for a feeling; judgment before tools.
- **Web:** one "jump" per demo - a reveal the audience completes in their head (e.g. a chart that resolves into
  the logo form). Decide the emotion of each screen (confidence, momentum, relief) before the palette.

## Stefan Sagmeister & Jessica Walsh
- Beauty is functional - people connect through emotion; visible care reads as beauty; purposeful play and risk.
- Provocation can backfire (Sagmeister's carved-skin AIGA poster drew criticism) - in a regulated firm, never
  shock imagery.
- **Web:** one tactile, crafted moment (a physically modeled transition, a variable-font animation) at the open or
  the close - never on a screen where numbers must be trusted.

## Kyle Cooper (title design) and Apple HIG motion
- Titles come out of the content and have a beginning, middle and end; make the audience feel the tone first;
  every frame should be a finished composition (executives screenshot things).
- Apple: motion must be purposeful, optional, brief and precise, interruptible; avoid sustained oscillation.

## Where they agree (apply always)
1. Find the one idea before choosing form. 2. Constraints produce better work. 3. Systems beat one-off screens.
4. Cut what carries no meaning. 5. Strong scale contrast, one dominant element per view.
6. Honesty is non-negotiable (in finance a distorted chart destroys trust). 7. Motion explains or sets mood,
and can be skipped. 8. Craft is visible and reads as quality.

## Productive tensions and how to resolve them for executive audiences
- Rams' restraint vs Sagmeister's provocation -> restraint wherever numbers must be believed; provocation only at
  the opening title and the closing ask; never shock.
- Swiss objectivity vs Scher's expressive type -> fixed grid; expressive type lives inside it at chapter breaks.
- Tufte density vs executive glanceability -> one big number first, depth on demand (sparkline, drill-down).
- Rand/Glaser wit vs Vignelli discipline -> one jump/pun per demo, tied to content.
- Crouwel experimentation vs legibility -> experimental type for headlines/tickers only.
- **Ratio:** ~80% trust surfaces, ~20% story moments. Every flashy moment must pass: does it explain, can it be
  skipped, does it respect reduced motion?

Sources: vitsoe.com/us/about/good-design; uxdesign.cc (Vignelli Canon); anthonyhobday.com design book notes;
openlab.citytech.cuny.edu (Muller-Brockmann); paulrand.design; eyemagazine.com "Symbolise and summarise";
typeroom.eu (Scher, Crouwel); pentagram.com; edwardtufte.com sparklines; Georgia Tech Tufte notes;
interface.fh-potsdam.de (Aicher); Stanford Kare interview; 99percentinvisible.org; smashingmagazine.com (Glaser);
whitewall.art & museumangewandtekunst.de (Sagmeister & Walsh); reverttosaved.com (Cooper);
developer.apple.com/design/human-interface-guidelines/motion. [uncertain]: the primary Vignelli Canon PDF could
not be fetched (secondary sources used); Muller-Brockmann's exact column/leading rules not verified.

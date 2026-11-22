# Working method and critic rubric

## Pipeline
1. **Intake** - fill the selling frame (03): audience, decision, core belief, pain, proof, setting, duration.
   Ask <= 4 questions; otherwise assume and state assumptions.
2. **Directions** - 3 genuinely different routes (disciplined-premium / expressive-editorial / cinematic), each
   with idea, symbol, signature moment, palette, type, motion, layout, risk. The human picks (or tweaks).
3. **Script** - beats (03 skeleton) and EVERY word on screen, written to the copy playbook (04). Before -> after
   table for any existing wording. Disclosure line. CTA.
4. **Build** - one HTML file with markers; tokens first; signature moment first; then beats; then polish.
5. **Lint** - automated offline checks (external URLs, reduced motion, @supports gates, contrast, copy, disclosure).
6. **Render** - screenshots desktop + mobile at ~0.7 s and ~3 s; console errors captured.
7. **Critique** - adversarial review against the rubric below, with the screenshots.
8. **Fix** - targeted edits; new version; repeat if blockers remain. Every change = new version file.

## Building order (prevents "polished but thin")
Content and story -> tokens -> layout skeleton -> real copy -> data -> the signature moment -> other motion ->
states (hover/focus/empty/error) -> reduced-motion/fallbacks -> polish (grain, glow) -> self-check.

## Critic rubric (score 1-10; ship when no dimension < 7 and no blocker)
| Dimension | 10 looks like | Instant fail (blocker) |
|---|---|---|
| concept | one idea + one symbol, carried through every beat | no discernible idea; a feature tour |
| wow | signature moment in <= 3 s that proves the claim; memorable | spectacle unrelated to the message |
| hierarchy | claim and ask dominate the blur test; one accent | everything shouts |
| typography | 3-4 sizes, strong contrast, tabular numbers, characterful faces | generic primary font, < 12px text |
| color | tinted neutrals + one accent; works on a projector | contrast < 3:1 on text |
| motion | purposeful, choreographed, interruptible, tokens | layout animation, infinite pulsing on data |
| copy | answer-first heads, precise labels, verb buttons, units/periods | placeholder copy, jargon, "Submit" |
| data_integrity | lie factor ~1, labels, sources, simulated disclosure | truncated bars, unlabeled simulated money |
| ux | 20-point checklist >= 32 | dead-looking controls, no way back |
| sell | selling skeleton, one ask, peak + designed ending | no ask; ends on clutter |
| accessibility | keyboard, focus, reduced motion, alt for charts | motion-only info; no reduced-motion |
| performance | 60 fps, hero < 400 ms, loops pause | console errors; > 15 MB |
| brand_fit (brand mode) | all musts honored, still flashy | protected colors/logo misused |

Issues must name the element and give the exact fix; copy issues quote old and new words.

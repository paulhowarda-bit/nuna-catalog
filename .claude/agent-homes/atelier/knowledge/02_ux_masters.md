# UX masters, as rules for live interactive demos

## Don Norman - Design of Everyday Things, Emotional Design
- Signifiers over affordances: everything operable must *look* operable (hover state, pointer cursor, focus ring).
  Nothing that looks interactive may be dead.
- Bridge the gulf of execution (how do I act?) with signifiers, constraints and natural mapping; bridge the gulf
  of evaluation (what happened?) with immediate, unambiguous feedback showing the new state.
- Natural mapping: the control sits beside what it changes and the effect lands in the same frame.
- Constraints: disable/hide moves that would break the story.
- Three emotional levels: visceral (the instant look - land the hero in the first second), behavioral (it works
  smoothly), reflective (what they retell - a closing frame stating what this means for the firm).
- Caveat: "attractive things work better" is about *perceived* ease, not proven performance.

## Jakob Nielsen - 10 heuristics (the demo-critical ones)
Visibility of status (never a silent wait); match the real world (brokerage words, not engineering jargon); user
control (Esc/Home returns to a known-good state); consistency (same word/color/gesture = same meaning); recognition
over recall (keep context visible); aesthetic minimalism; accelerators for experts (presenter shortcuts); plain
error messages with cached fallback, never a stack trace.

## Steve Krug - Don't Make Me Think
Self-evident screens; design for scanning and satisficing; clear visual hierarchy; conventions cost no thought;
obviously-clickable controls; omit needless words (no "Welcome to our innovative platform"); three mindless clicks
beat one hard one. **5-second test:** a cold viewer can state the screen's claim within 5 seconds. If a control
needs instructions, redesign it.

## Ben Shneiderman
- Mantra: overview first, zoom and filter, then details on demand (+ relate, history, extract).
- Dynamic queries update in < 100 ms; actions incremental and reversible.
- Feedback proportional to the action; dialogs that yield closure (each chapter ends with a "done" beat);
  user in control (no surprise auto-advance); reduce short-term memory load.

## Bret Victor - immediate connection, explorable explanations
- Change an input -> see the effect immediately. Expose the model's assumptions as controls ("a spreadsheet without
  the spreadsheet"). Explorable but *guided* - not an empty sandbox.
- Control time: pause, scrub, rewind, jump to events.
- Ladder of abstraction: step up (behavior across all values), step down (click the aggregate to see the concrete
  record).
- Keep the previous state visible as a ghost line after a what-if.
- **Demo:** draggable numbers inside sentences ("If volume rises to [3x]...") recompute the chart; 1-3 knobs per
  screen, clamped ranges, pre-computed responses.

## Laws of UX (Jon Yablonski)
| Law | Demo rule |
|---|---|
| Doherty threshold (<400 ms) | Hero insight paints < 400 ms; every click acknowledged < 100 ms |
| Fitts | Targets >= 44px, near the focal point; keyboard for the presenter |
| Hick | <= 5 visible options; highlight the recommended path |
| Jakob | Standard chart types, standard nav placement, time left-to-right |
| Peak-end | Design one peak and a deliberate final frame; never end on clutter or a spinner |
| Von Restorff | One accent per screen; signal with size/weight + color, not color alone |
| Miller | Chunk KPIs into 3-4 labelled groups (7+-2 is not a hard limit) |
| Tesler | The demo absorbs complexity; the presenter never types parameters live |
| Aesthetic-usability | Polish earns forgiveness - test the story in grayscale first |
| Goal-gradient / Zeigarnik | Visible chapter progress; open a loop early, close it at the peak |
| Serial position | Key KPI first or last; open and close on the same headline |
| Common region / proximity | Cards per domain; label within 8px of its value |
| Pragnanz | Simple geometric diagrams, no ornamental shapes |
NN/g response limits: 0.1 s feels instant, 1 s keeps flow, 10 s loses attention.

## Alan Cooper, Luke Wroblewski, Jared Spool
- Cooper: design for goals, not tasks; name ONE primary persona ("CEO: wants to know whether modernization reduces
  operational risk and when"); state the experience goal ("feels inevitable, not risky") and test against it.
- Wroblewski: constraints force prioritization; design for partial attention. **Demo:** design for the boardroom
  projector seen from 20 feet first - if it doesn't survive that, cut.
- Spool: intuitive = small gap between current and required knowledge. Executives have high domain knowledge and
  zero knowledge of your UI -> zero UI learning, domain-dense content, no novel interactions mid-demo.

## Apple HIG and Material 3 motion
- Apple: purposeful, optional, brief, precise, interruptible; don't animate frequent interactions; current
  principles hierarchy, harmony, consistency.
- Material 3 durations: short 50-200 ms, medium 250-400, long 450-600, extra-long 700-1000; duration grows with the
  distance/area moved; decelerate entering, accelerate exiting. Patterns: container transform (card -> detail),
  shared axis (sequential steps), fade-through (unrelated views).

## Nancy Duarte (Resonate) and Garr Reynolds (Presentation Zen)
- Duarte: alternate "what is" and "what could be" several times; the audience is the hero (don't open with
  yourself); contrast in narrative, visuals and pacing; one S.T.A.R. moment - Something They'll Always Remember -
  that *demonstrates* the big idea (e.g. kill a node live and the ledger keeps running), plus one repeatable line.
- Reynolds: restraint, simplicity, naturalness; high signal-to-noise; 30-40% empty space; picture superiority;
  a blackout key (B) so the room looks at the presenter; finish a little early.

## The 20-point UX demo checklist (score 0/1/2; >= 32 ship, 25-31 revise, < 25 redesign)
1. Cold viewer states the claim in 5 s. 2. Headline <= 12 words; body <= 40 words per screen. 3. Answers a named
persona's end goal. 4. Overview first; zoom/filter/details on demand. 5. Hero < 400 ms; click feedback < 100 ms.
6. Live visuals update < 100 ms (or pre-computed). 7. Every interactive element has hover + pointer + focus ring.
8. Controls adjacent to what they change. 9. State labels describe the current state in domain words.
10. Exactly one Von Restorff accent, not color-only. 11. <= 5 visible choices, recommended path highlighted.
12. KPIs in <= 4 bounded groups, labels next to values. 13. Standard charts and conventions. 14. What-ifs keep the
baseline visible. 15. Every animation purposeful, in duration bands, skippable. 16. prefers-reduced-motion honored;
no info carried by motion alone. 17. Progress/chapter indicator. 18. One key to reset; graceful failure. 19. What-is
/ what-could-be beats on presenter keys with one rehearsed S.T.A.R. moment. 20. Designed peak and final frame.

## Where wow fights usability - and the resolution
- Polish masks confusion -> test the story in grayscale first, polish last.
- Long cinematics vs. Doherty -> one long motion at the S.T.A.R. beat only, skippable; everything else < 400 ms.
- Many glowing accents -> one accent per screen.
- Novel gestures -> novelty in the visual, standard click/drag/keys for control.
- "Look how much data" -> density as one overview texture (heatmap); the claim in big type.
- Live manipulation risk -> 1-3 bounded knobs, pre-computed, reset key, offline data.
- Ambient particles/pulses -> behind content, low amplitude, off under reduced motion, no sustained oscillation.
- Spectacle unrelated to the message -> the wow must *prove* the claim.
- Fake delays to seem valuable -> never fake latency in a performance claim; label simulated timings.
- Dark "premium" palettes on washed-out projectors -> strong contrast, big type, test on the real room display.

Sources: jnd.org/signifiers-not-affordances; nngroup.com (two UX gulfs, 10 heuristics, response times, peak-end);
Krug publisher sample (Pearson); cs.umd.edu/~ben/goldenrules; Shneiderman 1996 paper; worrydream.com
(ExplorableExplanations, LadderOfAbstraction); lawsofux.com; lukew.com Mobile First; developer.apple.com HIG motion;
material-components-android Motion.md (M3 tokens); duarte.com blog; supersummary.com (Presentation Zen).
[uncertain]: Material's "informative/focused/expressive" wording from a secondary source; Krug chapter details from
the table of contents; Reynolds from a secondary summary.

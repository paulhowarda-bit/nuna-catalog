# Demos that SELL

A demo exists to move a specific person to a specific decision. Beauty that doesn't move the decision is waste.
Vendor benchmark numbers below (Navattic, Arcade, Storylane, Unbounce) are directional, not laws.

## Before designing: the selling frame (fill all of it)
- **Audience / persona:** one named primary viewer (e.g. "CIO", "Head of Operations"). Others are secondary.
- **Decision / ask:** the exact action - "Approve $X for phase 1 by <date>", "Pilot with two desks in Q1".
- **Core belief:** the one sentence they must leave believing.
- **Villain / pain:** what hurts today, in their words and their numbers.
- **Proof:** what we can show (test replay, pilot metric, benchmark) - with source and date.
- **Setting:** presented live in a room, sent as a link, or both. Duration (3-5 minutes is the sweet spot).

## Playbook (22 rules)
**Story**
1. Lead with the result - open on the outcome screen before any "how" (Great Demo!: "do the last thing first").
2. Open with a Situation card, not a company overview: role, critical business issue, root causes, capabilities
   needed, the delta (value at stake), the critical date. The viewer can confirm/edit it - it seeds later numbers.
3. Answer first, then three reasons (Pyramid Principle: situation -> complication -> question -> answer). Section
   headlines state conclusions ("Settlement risk drops 90%"), never topics ("Settlement").
4. Tell-Show-Tell each scene: say what's coming, show one workflow, say why it matters in business terms.
5. Problem -> product -> outcome. Never a feature tour. Cut steps that only navigate.
6. Alternate "what is" and "what could be"; widen the gap, then close it. End on the future + the ask.
7. Scene first, numbers second (Apple product-page pattern): show the human situation, then the proof number.
   One job per scroll section; visuals carry the weight, copy stays short.
8. Shortest path to value: the fewest clicks from a logical start to the outcome shown first.

**Pacing (self-guided link)**
9. About 9-13 steps and 2-4 minutes per flow. (Arcade 2026: completion peaks at 9-12 steps; Navattic 2026: 5-13
   optimal - vendors disagree at the short end.)
10. Put the first payoff before step 7 (viewers past step 7 are far more likely to finish - Arcade).
11. Split into 2-3 chapters / role views with a visible chapter menu (multi-flow demos complete more - Navattic).
12. Few words: ~25-30 words per callout, 10-20 on the first step; hotspot labels <= 60 characters; question-form
    labels attract clicks; no buzzwords.
13. Live HTML and images beat video for completion.

**The ask**
14. One primary ask (Unbounce: one CTA converted best vs two or three). Internal pitch: a specific decision.
15. CTA after the aha, at the end, and persistent but quiet in a corner. 1-2 CTAs, never 5.
16. Don't gate value behind forms.

**Clarity and memory**
17. 10-second test: the first screen says what this is, why it matters to *this* viewer, and what to do next.
18. Visual hierarchy builds toward the ask: <= 3 type sizes, <= 2 large elements, red reserved for danger, never
    color alone, blur/squint test - the ask must still dominate.
19. Personalize honestly: the viewer's role, business unit and real scale of numbers; never "Acme" filler. Build for
    the champion who forwards the link to ~5 colleagues.
20. One peak about two-thirds through; end on outcome + ask - never on Q&A, a disclaimer wall or a spinner.
21. Pre-empt objections: a "What about...?" drawer (risk, cost, timeline, dependencies).
22. Honor reduced motion; the story must work without spectacle (projector and phone).

## Narrative skeleton (3-5 minutes; map each beat to a presenter key)
| # | Beat | On screen | Invites |
|---|---|---|---|
| 0 | Hook / answer (0:00-0:15) | One-line conclusion + headline number, the outcome visual already alive | nothing - chapter menu visible |
| 1 | Situation (0:15-0:45) | Situation card (issue, causes, capabilities, delta, date) | confirm/edit inputs that seed later numbers |
| 2 | What is - the pain (0:45-1:30) | Today's process live: batch window, growing queue, heat map running hot | hotspots: "Why does this break at 4 PM?" |
| 3 | Cost of inaction (1:30-2:00) | Cost-of-delay clock from their inputs, assumptions visible | slider: wait 6 / 12 / 18 months? |
| 4 | What could be (2:00-3:00) | Same scenario on the new platform in <= 3 clicks, before/after wipe | drag the wipe; toggle legacy / modern |
| 5 | Peak - the wow (~3:00-3:30) | One undeniable proof (latency race, failover with zero lost records), source label | viewer triggers it: "Pull the plug" |
| 6 | Proof (3:30-4:00) | Pilot metrics, test results, comparables - each sourced and dated | expand to source |
| 7 | Value summary (4:00-4:30) | ROI: conservative / expected / optimistic, payback, risks named | adjust assumptions (conservative default) |
| 8 | The ask (4:30-5:00) | One decision, amount, date, next step; recap of three reasons | one primary button + share link |

## Persuasive interactive pattern library (each with its honesty rule)
1. **Live ROI / value slider** - proves value scales with *their* numbers. Default conservative inputs; show the
   formula; show costs as well as benefits; risk haircut; show a range, not a point.
2. **Cost-of-delay clock** - $/week derived from stated inputs, labelled "estimate". No fake deadlines.
3. **Before/after wipe** - same data, same scale, same window on both sides; never worst-day vs best-day.
4. **Risk heat map that cools** as the solution toggles on - show residual and *new* risks (migration, dual run).
5. **Latency / throughput race** - say replay vs simulation; state hardware, load, percentile (p99, not mean);
   "Replay of test <date>".
6. **Value-created counter** - tied to ROI inputs; pauses when off-screen; never counts from page load as fact.
7. **Resilience drill ("pull the plug")** - scripted from real test logs; label simulated parts.
8. **Process collapse** - 14 manual steps animate into 3; list what remains and who owns it.
9. **Role switcher** (CIO / Ops / Risk / Finance) - core numbers identical across roles; only emphasis changes.
10. **Scenario stress test** (3x volume day) - show where it degrades, not only where it passes.
Every component: a "Show assumptions" toggle, a visible source/date label, a reset.

## One build, two modes
- `?mode=present` (or press P): bigger type for projectors, callouts hidden, keyboard beats, optional speaker notes
  (S), blackout (B), jump to beat (1-9), reset (R), fullscreen (F).
- `?mode=guided` (default for links): callouts visible, chapter menu, persistent CTA, mobile layout.
- Beats are a deterministic state machine addressable by URL hash (`#beat-5`), so the presenter can jump to an
  executive's question and back.
- Resilience: everything inlined; no network; no spinners; animations pre-warmed; cursor hides on idle in present
  mode; reset key restores defaults.
- Live: the presenter triggers the peak by hand after the "Tell". Link: the viewer triggers it, early.
- Both modes end on the ask; the final keystroke lands on the decision frame and stays there through Q&A.

Sources: navattic.com State of the Interactive Product Demo 2026; arcade.software 2026 benchmarks; storylane.io;
goconsensus.com; demostack.com; greatdemo.blogspot.com (Situation Slide, last thing first); presalescollective.com
(Tell-Show-Tell); modelthinkers.com (Pyramid Principle); nngroup.com (dwell time, visual hierarchy, peak-end);
marketingprofs.com (Unbounce CTA data, 2018); blackswanfarming.com/cost-of-delay; forrester.com/policies/tei;
quarto.org reveal.js presenting. [uncertain]: vendor-reported benchmarks; Demo2Win and Great Demo books not read.

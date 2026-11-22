# Compliance and ethics guardrails (financial services)

Not legal advice. These are house design rules; client-facing material goes through the firm's Compliance review.

## FINRA Rule 2210 as the house standard (even for internal demos)
- Communications must be fair and balanced, have a sound basis, omit no material fact, and make no false,
  exaggerated, unwarranted, promissory or misleading claims. Risks and benefits in balance.
- Legends/footnotes only where they don't impair understanding -> disclosures visible, not buried.
- 2210(d)(1)(F) generally prohibits projections of performance (exceptions include hypothetical illustrations of
  mathematical principles and Rule 2214 investment analysis tools). The SEC approved an amendment on Oct 15, 2026
  permitting projected/targeted returns under conditions (reasonable basis, disclosure of assumptions and risks,
  policies, records; not for mass retail) - **not yet effective at research time; until FINRA announces the date,
  the prohibition stands** [check current status].
- Internal communications are excluded from the "institutional communication" definition, so a purely internal
  funding pitch is *probably* outside 2210 [inference] - but demos leak and get reused, so design as if it applies.

## Rule 2214 (interactive tools that project investment outcomes)
Must clearly and prominently explain methodology, assumptions and limitations; state that results may vary with each
use and over time; say results are hypothetical and not guarantees; describe the universe of investments considered.

## House rules for every demo
1. Persistent "Simulated data - not real customers, accounts or results" label on every screen with numbers.
2. Replays say "replay of test <date>"; simulations say "simulation"; modeled numbers say "modeled" + assumptions.
3. ROI/value: conservative default, formula visible, costs and risks shown with equal visual weight, ranges not points.
4. No real customer PII, account numbers or names - synthetic names from an obviously fictional set.
5. Sources and dates beside every proof claim. Keep the versioned file of exactly what was shown (the versioning
   system does this).
6. Use persuasion principles only truthfully: real social proof, real authority, real deadlines (an actual
   budget-cycle date), commitments the viewer would freely make.

## Never (deceptive patterns - deceptive.design / FTC 2022)
Fake urgency or countdowns; fake scarcity; fake testimonials or social proof; confirmshaming ("No, I prefer losing
money"); preselected opt-ins; costs hidden in tooltips; visual interference that hides a downside; nagging pop-ups;
exit-intent pop-ups; faked latency or "live" labels on replays; charts whose visual effect exceeds the data effect.

Sources: finra.org Rule 2210 and Rule 2214 text; FINRA 2026 annual regulatory oversight report (communications);
alston.com and mayerbrown.com (Oct 2026, SEC approval of 2210 amendment); FINRA Regulatory Notice 26-14;
deceptive.design/types; ftc.gov (Sept 2022 dark patterns report press release); influenceatwork.com.

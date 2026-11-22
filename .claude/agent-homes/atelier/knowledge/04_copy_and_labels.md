# Copy and labels - rewrite every word

Words are design material. The agent rewrites *all* on-screen text: headlines, subheads, labels, metric names,
axis titles, legend entries, buttons, tooltips, empty and error states, footnotes, aria-labels and the <title>.

## Rewrite checklist (run on every screen)
1. Headline states the answer, not the topic: a claim with a verb and a number ("Breaks fell 93% in 30 days"),
   never "Reconciliation overview".
2. One line, ~6-12 words, <= 60 characters.
3. Front-load meaning - scanners read about the first 2 words (~11 characters). Never start with "Introducing",
   "Overview of", "The".
4. Headlines stand alone - a forwarded screenshot must still make sense.
5. Split jobs: the screen headline carries the insight; the chart title stays neutral and names entity, measure,
   unit and period ("Equity trades - millions - Jan-Sep 2026").
6. Concrete numbers + baseline instead of adjectives ("significant", "massive", "lightning-fast" are banned).
7. Supporting points in threes, strongest first, no overlap.
8. Buttons are specific verbs (+ object): "Run replay", "Approve phase 1", "Compare plans". Never "Submit", "OK",
   "Go", or cute ("Let's do it!").
9. Link text names the destination - never "Click here" or a bare "Learn more". Say "select", not "click".
10. The accessible name starts with the visible label (WCAG 2.5.3): a "Run replay" button's aria-label begins
    "Run replay".
11. Plain language even for experts: grade 10-12, sentences < 20 words; define acronyms on first use (or expand on
    hover). Brokerage terms the audience uses are fine; engineering jargon is not.
12. Active voice, present tense for system behavior: "Sequencer replays every order".
13. Sentence case everywhere (headlines, buttons, axis titles). No ALL CAPS except fixed notation.
14. One term per concept across all screens (don't alternate trades / executions / fills). Keep a glossary.
15. No "we". "Unable to load positions", not "We're having trouble". No "Oops".
16. Empty states say what the area is, what will appear, and the one next action. Never "No data" before loading.
17. Errors sit next to the problem: what happened, how to fix, no blame, never color alone.
18. Tooltips give full precision + context: exact value, unit, timestamp, source. Tiles show rounded values.
19. Label simulated data on every screen, persistently - not buried in a footnote.
20. Projections are labelled "modeled", with key assumptions and "results may vary".
21. Balance each benefit with its limit/scope ("in simulation, 10K orders").
22. Charts get a short alt text naming the takeaway, plus a data table or long description.
23. Annotations say what happened and why it matters ("Fails spike at 3:58 PM ET: end-of-day batch").
24. The headline's direction matches the chart's (if lower is better, say "fell" and color it as good).
25. Questions make good hotspot labels ("Why does this break at 4 PM?").

## Before -> after (illustrative)
| Element | Before | After |
|---|---|---|
| Headline | Q3 Trading Volume Analysis | Q3 equity trades up 18% to 412M - options drove two-thirds |
| Button | Submit | Place order |
| Button | Execute | Run failover test |
| Link | Click here to learn more | See how T+1 cuts settlement fails |
| Metric | P99 Lat (ms) | Order latency, slowest 1% (ms) |
| Metric | Throughput | Trades processed per second |
| Axis | Count | Trades (millions) |
| Empty state | No data | No breaks today. Last reconciliation 6:05 AM ET. [View history] |
| Error | Error 500: Request failed | Positions didn't load. Retrying in 10 s. [Reload now] |
| Tooltip | Value: 1234567.891 | $1,234,568 settled - Oct 27, 2026, 4:00 PM ET - source: simulated ledger |
| Callout | Significant improvement in reconciliation | Daily breaks: 1,240 -> 85 (-93%) |
| Rate change | Margin rate increased 2% (5% -> 7%) | Margin rate up 2 percentage points (5% -> 7%) |
| Jargon | RPO=0 achieved via deterministic sequencer replay | Zero trades lost when a data center failed - in a 10K-order simulation |
| Superlative | Industry-leading, lightning-fast settlement | Median settlement posting: 3.1 s (simulated) |
| Toggle | Show/Hide Data | Show plan vs. actual |
| Projection | Savings: $40M/yr | Modeled savings: $40M a year (assumes 30% of mainframe MIPS retired; results may vary) |
| Banner | (none) | Simulated data - not real customers, accounts or results |
Only state assumptions that are real - never invent one to make a number look sourced.

## Number and unit formatting
| Topic | Rule |
|---|---|
| Separators | Commas from 4 digits: 1,093; 45,210 |
| Abbreviations | Tiles/axes only: $1.2M, 412K, $3.4B (capital, no space). Prose: "million". Tooltips: full value. Don't write 8.21K |
| Precision | 2-3 significant digits on tiles; same decimals within a column; align on the decimal point |
| Leading zero | 0.5%, never .5% |
| Negatives | True minus sign (U+2212) in charts/variances; parentheses ($1,240) in statement-style tables - pick one per table type |
| Positive variance | Explicit plus: +12%, +$3.1M |
| Good/bad color | Red bad / green good (IBCS) but never color alone - sign or word too |
| Percent | 18% (no space) |
| Points vs percent | 40% -> 44% is "+4 pp" (and +10%). Basis points: 1 pp = 100 bp |
| Small bases | Show absolute with relative: "+$20 (+400%)" |
| Ranges | En dash in tiles (Jan-Sep), "to" in prose |
| Dates | Oct 28, 2026 (no ordinals) |
| Times | 12-hour with zone: 4:00 PM ET |
| Periods | Always: YTD 2026, Q3 2026, trailing 30 days |
| Scenarios | Actual / plan / forecast / prior year: solid / outlined / hatched / lighter (IBCS) |
| Units | Once, in the axis title or subtitle ("$M"), not on every tick |
| Money | $ before the amount; cents only if non-zero or transactional |
| Sentence start | Never start a sentence with a numeral |
In code: `Intl.NumberFormat('en-US', {notation:'compact', maximumSignificantDigits:3})` for tiles;
`{style:'currency', currency:'USD'}` for money; `font-variant-numeric: tabular-nums` everywhere numbers align.

Sources: nngroup.com (first 2 words, F-pattern, plain language for experts, microcontent, error messages, empty
states); developer.apple.com HIG writing; Material communication codelab; learn.microsoft.com style guide (numbers,
capitalization, scannable content); GOV.UK A-Z style guide; styleguide.mailchimp.com; storytellingwithdata.com;
thinkcell.com; stephanieevergreen.com; ibcs.com (2018 working-group PDF); data.europa.eu (FT visual vocabulary);
w3.org WCAG 2.4.4, 2.5.3, 3.1.5 and complex-images tutorial; stylemanual.gov.au. [uncertain]: headline word count
and significant-digit rules are synthesized house rules; IBCS 2.0 may renumber rules.

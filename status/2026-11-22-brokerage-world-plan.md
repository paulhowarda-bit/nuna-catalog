# 2026-11-22, the brokerage world: a plan for the demo's data (D48)

Paul asked for a data generator built on his ledger kit v14, with research on what a brokerage does outside its
ledger. This branch records the plan he answered: `docs/design/brokerage-world.md`, decision D48 ("C: extend the
kit"), and items 150 to 154 (the generator's five phases) and 155 (kit v15 and v16). Markdown only; nothing is built.

Checked: the kit's engine and golden (`schemes_v20.py`, `events_v8.json`, 44 financial transactions, 289 journal
lines) were read, not run; the research's two surprising rule changes were checked against the downloaded rule text
where it was there (FINRA 4210's intraday margin deficit; the Section 31 rate rests on FINRA's notice of 2026-03-17).

Not covered: whether CUSIP issuer numbers 990 to 999 are reserved for internal use (a secondary source says so; CUSIP
Global Services' pages are silent), and the CockroachDB connector's setting for starting without a snapshot.

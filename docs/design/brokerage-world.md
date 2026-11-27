# The brokerage world: a data generator for the demo

Paul, 2026-11-22: "we need to create a data generator to truly demonstrate the nuna catalog capabilities", built on
his ledger kit v14 and on research into what a brokerage does outside its ledger. This is the plan he answered (D48).
Items 150 to 154 build it in five phases; item 155 extends the demo kit.

**Two kits (D48 as amended, 2026-11-22).** Paul's ledger kit is for building the sequencer. The generator has its own
kit, the demo kit, forked once from ledger kit v14 into `demo-datagen` and extended there; it never follows the ledger
kit, and its purpose is data that demonstrates the catalog's console. Where this document says "kit v15" or "kit v16"
below, read the demo kit's templates (item 155).

**Real time (D48 as amended, 2026-11-22).** Paul: "The demo should be real-time demo that is generating data like a
real-time eventing system would be. Data should be coming in through both kafka and singlestore." So the generator is a
live event source running in every `demo.sh up`, not a batch writer, and nothing is backfilled: where this document
says "Java writer", "backfill" or "the month", read the stream that "The generator" below describes.

## In one paragraph

One seeded simulation of a US self-clearing retail broker-dealer, by default 1,000 accounts over one calendar month
of trading days, produces every dataset, so every table joins to the others and the firm ties out. Every financial
transaction the ledger kit has a template for is posted through the kit's engine, ported to Java, into the four
sub-ledgers (SL-CA client accounting, SL-SHARE share lots, SL-BP buying power, SL-RCB running collected balance). The
engine's self-check halts on any broken control, so the generated ledger cannot be inconsistent. The rest of the firm
is generated in the demo system that would own it and reaches the catalog through the path the demo already has.

## The ledger kit

Paul's kit, kept outside this repo: the reference workbook `buy_sell_v114.xlsx` (95 checks), SBE schemas 11 (chart of
accounts), 12 (booking schemes), 13 (journal: Posting, PostingRejected, BalanceSnapshot) and 20 (financial
transactions), and the posting engine `schemes_v20.py`. Its SBE schemas, generators and workbook builders name a
real firm and its codenames, so nothing from the kit is committed here unless the estate guard passes it. The port
reads only `defs.py`, `schemes_v20.py` and `events_v8.json`, which carry none of the usual markers; check them against
the guard's whole deny list before use.

Templates in v14 (22): money movement 30 to 35 (cash deposit; ACH deposit initiated and settled; ACH withdrawal
initiated, settled and cancelled), long equities 40 to 47 (buy and sell accepted, executed, settled; buy and sell
cancelled), margin interest 51 and 52, short sales 60 to 65 (short sell and buy to cover accepted, executed, settled;
the acceptance carries the locate).

What the generator takes from the kit's design:

- Buying power, the running collected balance, selling power and SMA are computed, never posted (R17): Iceberg views
  over the end-of-day balances, not tables.
- v13 to v14 added the short-sale messages and the `locateId` field. The world starts on the v13 shape and adds the
  column mid-month: a real schema change for the catalog to show.
- A share lot is keyed by its buy fill's `financialTransactionId`; the tax lot is the same lot.

### Events the kit cannot book (D48: extend the kit)

A month at a brokerage has events v14 has no template for. Paul chose to extend the kit (item 155) rather than keep
the world inside the kit or show the events as unbooked. Until a template exists, the generator does not produce that
event. The scope, in the order the phases need it (a choice made here, Paul may overrule):

- **Kit v15, money and fees** (phase 3): wire deposit received; wire withdrawal initiated, settled and cancelled; an
  ACH deposit returned before and after settlement (Nacha return code on the message); a cash journal between two
  brokerage accounts; an account fee charged and reversed (wire fee, ACATS-out fee, hard-to-borrow fee on shorts);
  short sell and buy-to-cover cancelled (the kit's own "not done").
- **Kit v16, transfers and asset servicing** (phases 3 and 4): ACATS received (cash, and positions whose lots carry
  the delivering firm's cost and acquisition date) and ACATS delivered (lots out at remaining cost, no gain); a cash
  dividend on a long position (entitled at the ex-date, paid on the pay date); a payment in lieu charged to a short
  position; forward and reverse splits (lots rescaled, cost unchanged, cash in lieu of fractions).
- Left out of the world: options, funds, fixed income, IRA distributions, mergers and spin-offs, interest on credit
  balances, commissions (the world's broker charges none).

Item 155 built both lists at once, as templates 70 to 91; `backlog/155.md` lists them, their fields and the world's
rates.

## The firm beyond the ledger

What each function records, the rule that shapes it, and where it lands. Volumes are estimates for 1,000 accounts;
none comes from a published source.

| Function | Records | Shaped by | Cadence | Lives in | Phase |
|---|---|---|---|---|---|
| Onboarding and KYC | party (name, birth date, tax id and type, address, employment, control-person and PEP flags), account (type, IRA subtype, status), roles, suitability profile, beneficial owners, trusted contact, agreements | 31 CFR 1023.220, 1010.230; SEA 17a-3(a)(17), (a)(35); FINRA 4512 | 1 or 2 new accounts a day | PostgreSQL, `party` | 1, 3 |
| Security master | security (internal id, CUSIP, ISIN, ticker, type, issuer, marginable and house rate, easy or hard to borrow, threshold flag), symbology history | CUSIP and ISIN check digits | daily, about 300 securities | Java writer, `reference` | 1 |
| Market data | closing prices, bars, NBBO at each order event | Rules 605, 606; 10b-10 | daily; streams in live mode | Java writer, Kafka, `markets` | 1, 5 |
| Orders and routing | order (client order id, side incl. sell short, type, limit and stop, time in force, received and entered, solicited, locate id, channel), order events, routes | 17a-3(a)(6); FIX 11, 37, 38, 40, 44, 54, 59; CAT; Rule 606 | 200 to 600 orders a day | SingleStore, `trading` | 2 |
| Executions | exec id, last quantity and price, cumulative and leaves, venue, time; busts | FIX ExecutionReport; kit templates 41, 44, 61, 64 | 1.2 to 2 fills an order | SingleStore, then the ledger | 2 |
| Best execution | quality per order (NBBO at receipt, improvement, speed, fill rate), reviews | FINRA 5310 | daily, quarterly review | `trading` | 4 |
| Confirmations | one per trade | 10b-10 | end of day | `client_reporting` | 4 |
| Clearing | CNS net position per security and day, obligations, fails, close-outs | 15c6-1 (T+1); Reg SHO 204, 203(b)(3) | end of day | CockroachDB, `clearing` | 2 |
| Stock record | every position by security and location (DTC, segregation, box, loan, borrow, fail), count differences | 17a-3(a)(5); 17a-4 | end of day | CockroachDB, `clearing` | 2 |
| Locates | locate (security, quantity, source, expiry, orders covered), easy-to-borrow list | Reg SHO 203(b)(1) | one per short order | CockroachDB, `clearing` | 2 |
| Margin | house rate per security, account snapshot (equity, SMA, requirement, intraday margin level), calls and outcomes | Reg T 220.12; FINRA 4210, whose intraday margin replaced the pattern-day-trader rule from 2026-06-04 | intraday; calls end of day | SingleStore and views, `margin` | 4 |
| Money movement | bank relationship, transfer (ACH or wire, direction, status), ACH returns (R01, R02, R16), journals | Nacha return codes; kit 30 to 35, v15 | 10 to 30 a day, 1 to 3% returned | PostgreSQL, `money_movement` | 1, 3 |
| ACATS | transfer (control number, type, status, reject code), assets, residual credits | FINRA 11870 | a few a week | PostgreSQL, `money_movement` | 3 |
| Corporate actions and income | announcement (type, ex, record and pay dates, rates), entitlement, income payment, withholding | ISO 15022 MT564 / ISO 20022 seev; 1099-DIV holding period | daily | Java writer, `reference`, `asset_servicing` | 4 |
| Cost basis and tax | tax lot (acquired, quantity, cost, wash adjustment, covered), realization, 1099-B and 1099-DIV lines | IRC 6045; 1099-B instructions | a lot per fill; annual | `tax` | 4 |
| Fees | schedule, accrual per trade (Section 31, TAF, CAT fee) | Section 31 at $20.60 a million on sales from 2026-04-04 | per trade | `reference`; kit `secFee`, `finraTaf` | 1 |
| Statements | per account and period: balances, valued positions, activity, income, fees | FINRA 2231 | month end | `client_reporting` | 4 |
| Reserve | 15c3-3 computation (Exhibit A), reserve bank account, possession-or-control deficits | 15c3-3 | weekly | Oracle, `regulatory` | 4 |
| Net capital | computation, haircuts, minimum, excess | 15c3-1, 17a-5 | monthly | Oracle, `regulatory` | 4 |
| Regulatory reporting | CAT order events, CAIS records, blue sheets, Rule 605 and 606 reports | CAT IM spec 4.2.0 r4, CAIS 2.2.0 r4, 17a-25, 13h-1 | daily to quarterly | `regulatory` | 4 |
| AML | scenario, alert, case, SAR | 31 CFR 1023.320 | a few alerts a week | PostgreSQL, `compliance` | 3 |
| Sanctions screening | list version (synthetic, in OFAC's SDN layout), hits, dispositions | OFAC SDN spec; 31 CFR 501.603, 501.604 | onboarding, each wire, nightly | `compliance` | 3 |
| Surveillance | alerts by model (wash trades, spoofing, front-running), cases | FINRA 5270 | T+1 | `compliance` | 3 |
| Complaints | complaint, disposition | 17a-3(a)(18); FINRA 4530 | a few a month | PostgreSQL, `compliance` | 3 |
| General Ledger | GL accounts, journal entries, trial balance, reconciliations | 17a-3(a)(2), (a)(11) | daily, closed monthly | Oracle, `finance` | 4 |
| Digital channels | sessions, click events | none directly | 200 to 500 sessions a day | Kafka, `digital` | 5 |

Left out: advisory and rebalancing, options exercise and assignment, associated persons, escheatment, firm risk limits
beyond margin. Books and records are not modelled as data: under D24 no Iceberg table is a required record.

## Where each domain lives

| Demo technology | Plays | Holds | Path into the catalog |
|---|---|---|---|
| SingleStore `brokerage` (the sample `ops` it replaced is gone, item 151) | order management and intraday risk | orders (phase 1), order events, routes and executions (phase 2), intraday margin | the change feeds, `brokerage.orders` into `brokerage.trading.orders` and the order events, routes and executions, append-only, into `brokerage.trading` in one pipeline; Iceberg pipelines bring securities and closing prices back |
| PostgreSQL `backoffice` (CDC leg) | customer master, operations apps | party, KYC, payments, ACATS, complaints, AML cases | Debezium (`pgoutput`), Kafka, the Iceberg sink |
| Oracle `treasury` (CDC leg) | firm finance | GL, bank payments, reserve, net capital | Debezium (LogMiner), the Iceberg sink |
| CockroachDB `custody` (CDC leg) | books of record that survive a site loss | stock record, CNS, fails, locates, as they stand after the last end of day (phase 2: loaded by `Main custody` at each `cdc`, writing what changed) | Debezium's CockroachDB connector, the Iceberg sink, into `history.cockroach.clearing` |
| Kafka | event backbone | reference data, market data, the ledger's transactions, postings, rejections and balances, clearing (CNS, settlements, fails, stock record, locates), clickstream | one Iceberg sink connector (`brokerage-lake`) on plain JSON topics, routing each record to its table by its `lake_table` field; Kafka Connect runs in the base stack |
| The generator (`demo-datagen`: a bootstrap Job and a Deployment) | the firm's systems of origin | nothing; it sends each event as it happens | to Kafka and SingleStore; through the catalog it only makes tables and views, adds a column and tags each day's end |
| Spark (the engine matrix's image) | month-end batch | statements, tax, CAT, Rule 606 | Spark SQL `MERGE` into v3 tables; optional, as Spark needs Trino stopped |
| Trino and CloudBeaver | analysts, each as themselves | reconciliation views, ad hoc | reads with each person's token |

## The namespace tree

Under `lakehouse.brokerage`, beside the existing samples.

| Namespace | Tables | Governance |
|---|---|---|
| `reference` | securities, identifiers, calendar, venues, corporate_actions, fee_schedules | public; one table under a DR freeze |
| `markets` | prices_eod, bars_5m, quotes | internal; short snapshot retention, compaction |
| `party` | parties, accounts, account_roles, cip_checks, suitability_profiles, beneficial_owners, trusted_contacts; `history` | restricted, regulated; tax id, birth date and address flagged (D20, item 115) |
| `trading` | orders, order_events, routes, executions, best_ex_reviews | internal |
| `kafka` | order_events, routes, executions, as they went through Kafka while SingleStore was off (D50) | internal |
| `ledger` | chart_of_accounts, booking_schemes, financial_transactions, journal_lines, posting_rejections, balances_eod; views buying_power, running_collected_balance | confidential, regulated; a tag per business day |
| `clearing` | cns_obligations, settlements, fails, stock_record, locates | confidential |
| `recon` | views stock_record_vs_ledger, executions_vs_fills, cns_vs_fills (ties 1, 3 and 4; phase 2) | confidential |
| `margin` | house_requirements, account_margin_eod, margin_calls | confidential |
| `money_movement` | payments, ach_returns, acats_transfers | confidential, regulated; bank account numbers flagged |
| `asset_servicing` | entitlements, income_payments | internal |
| `compliance` | aml_alerts, aml_cases, sanctions_screens, surveillance_alerts, complaints | restricted, regulated; legal hold on aml_cases; read only by `demo-compliance` |
| `tax` | tax_lots, realized_gains, form_1099b, form_1099div | confidential |
| `client_reporting` | confirms, statements | confidential; a month-end tag |
| `regulatory` | cat_events, rule606_routing, reserve_computation, net_capital | confidential |
| `finance` | gl_journal, gl_balances, bank_accounts, bank_payments | confidential, regulated |
| `digital` | sessions, events | internal; 7-day snapshot retention, frequent compaction |
| `recon` | views only, one per tie | internal |

## What the catalog gets to show

- Five kinds of writer on one firm's month, every commit audited with its engine: SingleStore's feed, the Iceberg sink
  from Debezium and from plain topics, the Java library, Spark.
- Regulated namespaces (D27): `party.beneficial_owners` lands unclassified, is refused to everyone, and a steward
  classifies it during the demo. This replaces the refusal the toy `finance.ledger.accounts` shows today.
- Flagged tables for trusted engines only (D20), masks once item 115 lands: Trino reads tax ids, SingleStore and
  PyIceberg get 403.
- A legal hold (D24) on `compliance.aml_cases`; SAR confidentiality as a grant: a new person, `cora`, in a new group,
  `demo-compliance`, alone reads `compliance`.
- Time travel: one commit per business day; tags `eod-YYYY-MM-DD` and `month-end-YYYY-MM`; a trade busted after the
  close corrected on a branch and merged while the tag keeps what the statement said (catalog-wide refs with item 26).
- The month-end close as one multi-table commit (`/transactions/commit`): statements, balances and the GL snapshot.
- Schema and partition evolution: `locate_id` added mid-month; `journal_lines` from a day partition to month and
  account bucket at larger scale.
- Row-level changes: equality deletes from the change feed, v3 deletion vectors for busts and cancel-corrects, CDC
  histories with current-state views.
- Maintenance (D30) and the health dashboard (D38): streaming tables commit every few seconds and need it.
- Views in more than one dialect: the derived balances, current state over each history, the reconciliations.

## It ties out

Each tie is a view in `recon` that returns no rows on a consistent world; each seeded break makes its view return
exactly the seeded rows. A demo run checks both counts.

1. Share Position [Control] (3002) per account and security equals the stock record's customer position and the open
   tax lots' shares.
2. Securities at Cost (1002) equals the open tax lots' remaining cost, to the cent (R7c).
3. Every OMS execution is one ledger fill (41, 44, 61, 64) with the same fill id, price and quantity.
4. The firm's CNS net obligation per security and settlement date equals the net of its customers' fills.
5. Settled payments equal the ledger's money transactions; the bank's net equals net deposits per day.
6. The GL's Customer Cash Balances [Control] equals the sum of every account's Cash (1001) (ControlOfTotal).
7. Statements equal the ledger balances at the month-end tag.
8. 1099-B gains equal Realized Gain/Loss (1202) movements less wash-sale adjustments, per lot.
9. Every order, route and fill has its CAT event.

Seeded breaks: a fail to deliver aged into a Reg SHO close-out; a trade busted after the close; a payment closed twice
(rejected BalanceBelowZero, J16, so it shows in `posting_rejections`); a stale closing price; structuring deposits and
a sanctions match that raise alerts.

## The generator

- **World model.** The NYSE calendar with T+1; about 300 synthetic stocks and ETFs priced by a seeded random walk with
  market and sector factors; parties and accounts (cash, margin, IRA, joint, trust, entity) with personas (dormant, buy
  and hold, active trader, short seller on margin). Each day draws deposits, orders, partial fills, cancels and
  settlements; month end runs margin interest (51, 52), statements and the close.
- **Identifiers that cannot be real.** Tax ids in area 666, which SSA never issues (900 to 999 collides with ITINs);
  phones 555-0100 to 0199 (NANPA's fictitious range); email at `example.com` (RFC 2606). CUSIPs with issuer numbers
  990 to 999, which a secondary source calls reserved for internal use (not confirmed by CUSIP Global Services: flag
  it); proper check digits (CUSIP's double-add-double, Luhn on the ISIN `US` + CUSIP); no FIGIs. The sanctions list is
  synthetic, never OFAC's.
- **Deterministic.** Seed, anchor date and scale give the same events. The bootstrap fixes them in the `brokerage`
  namespace's properties; the anchor is three business days before the day the demo was stood up.
- **Live (D48 as amended).** `bootstrap`, a Job in `demo.sh up`, makes the topics, namespaces, tables and views (only
  what is missing). `run`, a Deployment, plays the world for ever on the real clock (Paul, 2026-11-23): the three
  catch-up days and today's earlier hours as fast as the sink takes them, then each event at its own time of day in
  New York, quiet overnight, over weekends and on holidays. It restarts the sink's task whenever Kafka Connect
  reports it failed. Kafka feeds go
  out as schemaless JSON (decimals as strings, dates as ISO strings, times as epoch milliseconds) on one-partition
  topics; the sink commits every 15 seconds. Orders are upserted into SingleStore as they change, and the change feed
  commits them on the same interval. At 17:00 the clock holds until each table that changed that day holds every row
  sent, then tags it `eod-YYYY-MM-DD`. `financial_transactions` gains `locate_id` (a schema change the console shows)
  just before the first short sale. A restart replays the world without sending and carries each topic on from its
  end offset.
- **Routes (D50, amended 2026-11-24; backlog 238).** The generator needs Kafka or SingleStore, not both (Paul,
  2026-11-24): `BROKERAGE_ROUTE` (both, kafka, singlestore) comes from demo.sh's switches `kafka` and `ss`. With
  SingleStore off, the order management system's events, routes and executions (SingleStore's own, item 151) go
  through topics `brokerage.oms.*` to `brokerage.kafka.<table>`, and the orders' upserts, which the order events
  repeat, nowhere; with Kafka off, every other feed is inserted into a SingleStore table of its own keyed by `seq`,
  the record's number in its feed, and an append-only change feed per table copies it to
  `brokerage.singlestore.<table>`, the sixteen in one pipeline so they share one extractor. Every feed but the
  orders is numbered, whichever way it is at home, and the reconciliation views read both ways' tables. A restart counts what each way carried (a topic's records
  since the world began, a table's rows, or, for the way that is off, its lake table's records), so it sends nothing
  twice across a change of route; demo.sh drains a way into the lake (`Main drain`) before turning it off. The
  end-of-day tags go on every table that changed that day, whichever way it went.
- **Scale.** Default 1,000 accounts. A business day is about 1,800 financial transactions, 15,700 journal lines, 16,400
  balance rows, 23,400 bars, 300 closing prices and 430 orders (1,300 order changes), about 58,000 rows, so a demo
  left running for a working day holds about 50 business days and 3 million rows. A month at that scale (the fast
  build's test) is 40,000 financial transactions and 346,000 journal lines.
- **Java, in a Gradle module `demo-datagen`** (a choice made here, Paul may overrule). The kit's engine is ported; the
  44 booking schemes (v14's 22, item 155's 22) and the chart are data the port reads, exported from the kit, so a new
  kit version changes data and only touches code where it adds a calculation. The port must reproduce
  `events_v8.json` (44 financial transactions, 289 journal lines) and the kit's own checks before it books anything. Java keeps the tests in the fast
  build with no new prerequisite, keeps dependencies on Maven for work, and the Iceberg Java library already writes
  what the tables need (v3 deletion vectors, views, tags, branches).

## Constraints

- Docker's 7.5 GB: the generator runs at 256 MB (384 MB limit) and Kafka Connect, now in the base stack, at 512 MB
  (1 GB limit); the change feed's pipeline reserves 384 MB inside SingleStore's pod. The CDC leg pauses the generator
  (scales it to 0) while it runs, and resumes it at `cdc stop`.
- The demo is shared (Atelier works against it): each phase lands for the overnight rebuild.
- Synthetic names and identifiers only; the kit enters through the estate guard.
- Dependencies from Maven only (Iceberg, JDBC drivers, the Kafka client).

## Sources

Read on 2026-11-22: eCFR 17 CFR parts 240 and 242, 12 CFR part 220, 31 CFR 1010.230, 1023.220, 1023.320 and part 501;
FINRA rules 2231, 4210, 4512, 4530, 5270, 5310, 11870 and the Information Notice of 2026-03-17 (Section 31); the CAT
Industry Member spec 4.2.0 r4 and CAIS spec 2.2.0 r4 (catnmsplan.com); DTCC's CNS page; the IRS instructions for
1099-B, 1099-DIV and W-2; Alpaca's Broker API documentation; BIAN Service Landscape V9.0; OFAC's SDN data
specification; Nacha's ISO 20022 returns guide; SSA POMS RM 10201.035; NANPA's 555 numbers; RFC 2606 and 6761. CUSIP
and ISIN details are from secondary sources (Wikipedia, Apache Commons' `CUSIPCheckDigit`).

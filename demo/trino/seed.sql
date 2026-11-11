-- Sample data for the console, loaded once through Trino (catalog "fid" = warehouse "lakehouse").
-- Deep namespaces, v2 and v3 tables, several snapshots each, evolved schemas, a changed partition spec,
-- a sort order, and a view with two versions. Nested namespaces are dotted schema names in Trino.

CREATE SCHEMA fid."markets";
CREATE SCHEMA fid."markets.equities";
CREATE SCHEMA fid."markets.equities.eu";
CREATE SCHEMA fid."markets.equities.eu.xetra";
CREATE SCHEMA fid."markets.equities.us";
CREATE SCHEMA fid."markets.fx";
CREATE SCHEMA fid."finance";
CREATE SCHEMA fid."finance.ledger";
CREATE SCHEMA fid."reference";

-- v2, partitioned by day and sorted by symbol; three appends, two schema changes (one a nested struct),
-- a row-level delete, and a second partition spec.
CREATE TABLE fid."markets.equities.eu.xetra".trades (
  trade_id bigint, symbol varchar, price decimal(18, 6), qty bigint, trade_ts timestamp(6) with time zone)
  WITH (format_version = 2, partitioning = ARRAY['day(trade_ts)'], sorted_by = ARRAY['symbol']);
INSERT INTO fid."markets.equities.eu.xetra".trades VALUES
  (1, 'SAP', 182.140000, 300, TIMESTAMP '2026-11-09 09:00:01 UTC'),
  (2, 'SIE', 171.020000, 120, TIMESTAMP '2026-11-09 09:00:04 UTC'),
  (3, 'ALV', 268.500000, 75, TIMESTAMP '2026-11-09 09:01:12 UTC');
INSERT INTO fid."markets.equities.eu.xetra".trades VALUES
  (4, 'SAP', 182.300000, 500, TIMESTAMP '2026-11-10 09:00:02 UTC'),
  (5, 'DTE', 23.115000, 2000, TIMESTAMP '2026-11-10 09:00:09 UTC');
ALTER TABLE fid."markets.equities.eu.xetra".trades ADD COLUMN venue varchar COMMENT 'MIC of the venue';
INSERT INTO fid."markets.equities.eu.xetra".trades VALUES
  (6, 'SAP', 182.410000, 250, TIMESTAMP '2026-11-10 09:05:00 UTC', 'XETR');
ALTER TABLE fid."markets.equities.eu.xetra".trades ADD COLUMN fees row(commission decimal(12, 4), tax decimal(12, 4));
INSERT INTO fid."markets.equities.eu.xetra".trades VALUES
  (7, 'SIE', 171.500000, 90, TIMESTAMP '2026-11-10 09:06:30 UTC', 'XETR', ROW(1.2500, 0.1000));
DELETE FROM fid."markets.equities.eu.xetra".trades WHERE trade_id = 3;
ALTER TABLE fid."markets.equities.eu.xetra".trades SET PROPERTIES partitioning = ARRAY['day(trade_ts)', 'bucket(symbol, 8)'];
INSERT INTO fid."markets.equities.eu.xetra".trades VALUES
  (8, 'ALV', 269.000000, 40, TIMESTAMP '2026-11-10 10:00:00 UTC', 'XETR', ROW(0.9000, 0.0500));

-- v3, three appends and an added column.
CREATE TABLE fid."markets.fx".rates (pair varchar, rate double, as_of timestamp(6) with time zone)
  WITH (format_version = 3, partitioning = ARRAY['month(as_of)']);
INSERT INTO fid."markets.fx".rates VALUES ('EURUSD', 1.0841, TIMESTAMP '2026-11-09 16:00:00 UTC'), ('GBPUSD', 1.2712, TIMESTAMP '2026-11-09 16:00:00 UTC');
INSERT INTO fid."markets.fx".rates VALUES ('EURUSD', 1.0855, TIMESTAMP '2026-11-10 16:00:00 UTC'), ('GBPUSD', 1.2699, TIMESTAMP '2026-11-10 16:00:00 UTC');
ALTER TABLE fid."markets.fx".rates ADD COLUMN source varchar;
INSERT INTO fid."markets.fx".rates VALUES ('USDJPY', 151.42, TIMESTAMP '2026-11-10 16:00:00 UTC', 'ECB');

CREATE TABLE fid."markets.equities.us".quotes (symbol varchar, bid double, ask double, quoted_at timestamp(6) with time zone)
  WITH (format_version = 3);
INSERT INTO fid."markets.equities.us".quotes VALUES ('AAPL', 227.10, 227.12, TIMESTAMP '2026-11-10 14:30:00 UTC');

-- The finance area is regulated (set below): accounts stays unclassified, so the catalog keeps it closed (D27).
CREATE TABLE fid."finance.ledger".journal (entry_id bigint, account varchar, amount decimal(18, 2), posted_at date)
  WITH (format_version = 3, partitioning = ARRAY['month(posted_at)']);
INSERT INTO fid."finance.ledger".journal VALUES (1, '4000-REV', 1250.00, DATE '2026-11-02'), (2, '1100-CASH', -1250.00, DATE '2026-11-02');
INSERT INTO fid."finance.ledger".journal VALUES (3, '6100-FEES', 42.50, DATE '2026-11-09');
CREATE TABLE fid."finance.ledger".accounts (account varchar, name varchar) WITH (format_version = 2);
INSERT INTO fid."finance.ledger".accounts VALUES ('4000-REV', 'Revenue'), ('1100-CASH', 'Cash'), ('6100-FEES', 'Fees');

CREATE TABLE fid."reference".countries (iso2 varchar, name varchar) WITH (format_version = 2);
INSERT INTO fid."reference".countries VALUES ('DE', 'Germany'), ('GB', 'United Kingdom'), ('US', 'United States');

-- A view, replaced once, so it has two versions.
CREATE VIEW fid."markets.equities.eu".xetra_daily AS
  SELECT symbol, date(trade_ts) AS trade_date, sum(qty) AS volume
  FROM fid."markets.equities.eu.xetra".trades GROUP BY symbol, date(trade_ts);
CREATE OR REPLACE VIEW fid."markets.equities.eu".xetra_daily AS
  SELECT symbol, date(trade_ts) AS trade_date, sum(qty) AS volume, avg(price) AS avg_price
  FROM fid."markets.equities.eu.xetra".trades GROUP BY symbol, date(trade_ts);

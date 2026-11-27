-- The CDC leg's CockroachDB source (demo/k8s/cdc.yaml, Service cockroach): custody, the brokerage world's clearing
-- books (item 151): the stock record by owner and location, CNS obligations, fails to deliver and locates, as they
-- stand after the world's last end of day. demo.sh's cdc loads them as login clearing (demo-datagen's Main cdc,
-- writing only what changed), and Debezium captures each change through a CockroachDB changefeed into the Iceberg
-- history tables history.cockroach.clearing.*. Run once as root, by the image's entrypoint, as the single node is
-- first started on an empty volume; the entrypoint then writes /cockroach/init_success, which the pod's readiness
-- probe waits for. (Before item 151 it held a toy positions.holdings.)
--
-- What Debezium needs (its CockroachDB connector guide): rangefeeds on, and a login with SELECT and CHANGEFEED on
-- the captured tables. updated_at is timestamp(3), as in backoffice.sql. The tables are demo-datagen's
-- (Custody.ddl(); a test holds this file to it).
SET CLUSTER SETTING kv.rangefeed.enabled = true;

CREATE DATABASE custody;
USE custody;
CREATE SCHEMA clearing;

CREATE TABLE clearing.stock_record (
    position_id STRING PRIMARY KEY,
    business_date DATE NOT NULL,
    security_id INT8 NOT NULL,
    symbol STRING NOT NULL,
    cusip STRING NOT NULL,
    side STRING NOT NULL,
    category STRING NOT NULL,
    brokerage_account INT8,
    quantity DECIMAL(18, 5) NOT NULL,
    close_price DECIMAL(18, 2) NOT NULL,
    market_value DECIMAL(18, 2) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT now()
);
CREATE TABLE clearing.cns_obligations (
    obligation_id STRING PRIMARY KEY,
    security_id INT8 NOT NULL,
    symbol STRING NOT NULL,
    cusip STRING NOT NULL,
    trade_date DATE NOT NULL,
    settlement_date DATE NOT NULL,
    buy_quantity INT8 NOT NULL,
    buy_amount DECIMAL(18, 2) NOT NULL,
    sell_quantity INT8 NOT NULL,
    sell_amount DECIMAL(18, 2) NOT NULL,
    short_sale_quantity INT8 NOT NULL,
    net_quantity INT8 NOT NULL,
    net_amount DECIMAL(18, 2) NOT NULL,
    direction STRING NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT now()
);
CREATE TABLE clearing.fails (
    fail_id STRING PRIMARY KEY,
    obligation_id STRING NOT NULL,
    security_id INT8 NOT NULL,
    symbol STRING NOT NULL,
    settlement_date DATE NOT NULL,
    quantity INT8 NOT NULL,
    status STRING NOT NULL,
    reason STRING NOT NULL,
    reg_sho_rule STRING NOT NULL,
    close_out_date DATE NOT NULL,
    close_out_price DECIMAL(18, 2),
    close_out_amount DECIMAL(18, 2),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT now()
);
CREATE TABLE clearing.locates (
    locate_id STRING PRIMARY KEY,
    brokerage_account INT8 NOT NULL,
    security_id INT8 NOT NULL,
    symbol STRING NOT NULL,
    quantity INT8 NOT NULL,
    source STRING NOT NULL,
    easy_to_borrow BOOL NOT NULL,
    borrow_rate DECIMAL(9, 6) NOT NULL,
    order_ref STRING NOT NULL,
    business_date DATE NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT now()
);

-- The books' loader (Main cdc, with the Secret demo-secrets' clearing).
CREATE USER clearing WITH PASSWORD '${PASSWORD_CLEARING}';
GRANT USAGE ON SCHEMA clearing TO clearing;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE clearing.stock_record, clearing.cns_obligations, clearing.fails,
    clearing.locates TO clearing;

-- Debezium's login (cockroach-source.json).
CREATE USER debezium WITH PASSWORD '${PASSWORD_DEBEZIUM}';
GRANT USAGE ON SCHEMA clearing TO debezium;
GRANT SELECT, CHANGEFEED ON TABLE clearing.stock_record, clearing.cns_obligations, clearing.fails, clearing.locates
    TO debezium;
GRANT SYSTEM VIEWCLUSTERSETTING TO debezium;

-- The demo's people, with their Keycloak passwords: everyone reads every table, ana also writes, creates,
-- alters and drops tables in the database, and gary and presenter are admins.
CREATE USER ana WITH PASSWORD '${PASSWORD_ANA}';
CREATE USER gary WITH PASSWORD '${PASSWORD_GARY}';
CREATE USER stella WITH PASSWORD '${PASSWORD_STELLA}';
CREATE USER oscar WITH PASSWORD '${PASSWORD_OSCAR}';
CREATE USER presenter WITH PASSWORD '${PASSWORD_PRESENTER}';
CREATE USER cora WITH PASSWORD '${PASSWORD_CORA}';
GRANT admin TO gary, presenter;
GRANT CREATE ON DATABASE custody TO ana;
GRANT ALL ON SCHEMA clearing TO ana;
GRANT ALL ON TABLE clearing.stock_record, clearing.cns_obligations, clearing.fails, clearing.locates TO ana;
GRANT USAGE ON SCHEMA clearing TO stella, oscar, cora;
GRANT SELECT ON TABLE clearing.stock_record, clearing.cns_obligations, clearing.fails, clearing.locates
    TO stella, oscar, cora;
-- Tables ana makes are read by the others too.
ALTER DEFAULT PRIVILEGES FOR ROLE ana GRANT SELECT ON TABLES TO stella, oscar, cora;

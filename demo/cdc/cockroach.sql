-- The CDC leg's CockroachDB source (demo/k8s/cdc.yaml, Service cockroach): custody holdings, which Debezium
-- captures through a CockroachDB changefeed into the Iceberg history table history.cockroach.positions.holdings.
-- Run once as root, by the image's entrypoint, as the single node is first started; the entrypoint then writes
-- /cockroach/init_success, which the pod's readiness probe waits for.
--
-- What Debezium needs (its CockroachDB connector guide): rangefeeds on, and a login with SELECT and CHANGEFEED on
-- the captured table. updated_at is timestamp(3), as in backoffice.sql.
SET CLUSTER SETTING kv.rangefeed.enabled = true;

CREATE DATABASE custody;
USE custody;
CREATE SCHEMA positions;

CREATE TABLE positions.holdings (
    holding_id   INT8 PRIMARY KEY,
    account      STRING        NOT NULL,
    isin         CHAR(12)      NOT NULL,
    quantity     DECIMAL(18, 4) NOT NULL,
    market_value DECIMAL(18, 2) NOT NULL,
    currency     CHAR(3)       NOT NULL,
    status       STRING        NOT NULL,
    updated_at   TIMESTAMP(3)  NOT NULL DEFAULT now()
);

INSERT INTO positions.holdings (holding_id, account, isin, quantity, market_value, currency, status) VALUES
    (9001, 'CUST-0001', 'DE0007164600',  1200, 219000.00, 'EUR', 'SETTLED'),
    (9002, 'CUST-0001', 'DE0005140008', 25000, 411250.00, 'EUR', 'SETTLED'),
    (9003, 'CUST-0002', 'DE0007236101',   300,  64860.00, 'EUR', 'PENDING'),
    (9004, 'CUST-0003', 'DE0008404005',   800, 286400.00, 'EUR', 'SETTLED');

-- Debezium's login (cockroach-source.json).
CREATE USER debezium WITH PASSWORD 'dbz';
GRANT USAGE ON SCHEMA positions TO debezium;
GRANT SELECT, CHANGEFEED ON TABLE positions.holdings TO debezium;
GRANT SYSTEM VIEWCLUSTERSETTING TO debezium;

-- The demo's people, with their Keycloak passwords: everyone reads every table, ana also writes, creates,
-- alters and drops tables in the database, and gary is an admin.
CREATE USER ana WITH PASSWORD 'ana-demo';
CREATE USER gary WITH PASSWORD 'gary-demo';
CREATE USER stella WITH PASSWORD 'stella-demo';
CREATE USER oscar WITH PASSWORD 'oscar-demo';
GRANT admin TO gary;
GRANT CREATE ON DATABASE custody TO ana;
GRANT ALL ON SCHEMA positions TO ana;
GRANT ALL ON TABLE positions.holdings TO ana;
GRANT USAGE ON SCHEMA positions TO stella, oscar;
GRANT SELECT ON TABLE positions.holdings TO stella, oscar;
-- Tables ana makes are read by the others too.
ALTER DEFAULT PRIVILEGES FOR ROLE ana GRANT SELECT ON TABLES TO stella, oscar;

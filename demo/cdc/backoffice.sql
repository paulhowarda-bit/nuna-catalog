-- The CDC leg's PostgreSQL source (demo/k8s/cdc.yaml, database backoffice): settlement instructions, which
-- Debezium captures into the Iceberg history table history.postgres.settlement.instructions. Run once, by the
-- image's entrypoint, as the database is made.
--
-- REPLICA IDENTITY FULL: a delete's event then carries the whole row, not only its key, so the history row of a
-- delete holds what was deleted. updated_at is timestamp(3), not timestamptz: Debezium sends timestamptz as a
-- string, and timestamp (with time.precision.mode=connect) as a timestamp the sink can type.
CREATE SCHEMA settlement;

CREATE TABLE settlement.instructions (
    instruction_id integer PRIMARY KEY,
    trade_ref      text           NOT NULL,
    isin           char(12)       NOT NULL,
    quantity       numeric(18, 4) NOT NULL,
    amount         numeric(18, 2) NOT NULL,
    currency       char(3)        NOT NULL,
    status         text           NOT NULL,
    updated_at     timestamp(3)   NOT NULL DEFAULT now()
);

ALTER TABLE settlement.instructions REPLICA IDENTITY FULL;

INSERT INTO settlement.instructions (instruction_id, trade_ref, isin, quantity, amount, currency, status) VALUES
    (5001, 'XETR-2026-11-09-000123', 'DE0007164600',  120, 21900.00, 'EUR', 'PENDING'),
    (5002, 'XETR-2026-11-09-000124', 'DE0005140008', 2500, 41125.00, 'EUR', 'PENDING'),
    (5003, 'XETR-2026-11-09-000125', 'DE0007236101',  300, 64860.00, 'EUR', 'MATCHED'),
    (5004, 'XETR-2026-11-09-000126', 'DE0008404005',   80, 28640.00, 'EUR', 'PENDING');

-- The demo's people, with their Keycloak passwords: everyone reads every table, ana also writes, creates,
-- alters and drops, and gary is a superuser. In PostgreSQL only an object's owner may alter or drop it, so
-- settlement_owner owns the schema and its table and ana is a member. backoffice stays the database's owner
-- and Debezium's login.
CREATE ROLE settlement_owner NOLOGIN;
ALTER SCHEMA settlement OWNER TO settlement_owner;
ALTER TABLE settlement.instructions OWNER TO settlement_owner;

CREATE ROLE ana LOGIN PASSWORD 'ana-demo';
GRANT pg_read_all_data, pg_write_all_data, settlement_owner TO ana;
GRANT CREATE ON DATABASE backoffice TO ana;
GRANT CREATE ON SCHEMA public TO ana;
CREATE ROLE gary LOGIN SUPERUSER PASSWORD 'gary-demo';
CREATE ROLE stella LOGIN PASSWORD 'stella-demo';
CREATE ROLE oscar LOGIN PASSWORD 'oscar-demo';
GRANT pg_read_all_data TO stella, oscar;

-- CloudBeaver's account (demo/k8s/cloudbeaver.yaml): it reads the table and changes nothing.
CREATE ROLE reader LOGIN PASSWORD 'reader-demo';
GRANT USAGE ON SCHEMA settlement TO reader;
GRANT SELECT ON settlement.instructions TO reader;

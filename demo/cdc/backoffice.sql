-- The CDC leg's PostgreSQL source (demo/k8s/cdc.yaml, database backoffice): the brokerage world's back office (item
-- 152), in three schemas: party (parties, accounts, roles, CIP checks, suitability profiles, beneficial owners, trusted
-- contacts), money_movement (payments, ACH returns, ACATS transfers) and compliance (AML alerts and cases,
-- complaints). Each table holds each record's latest version as it stood after the lake's last business day, which
-- demo-datagen's Main cdc writes at each demo.sh cdc, only what changed; Debezium captures each change into the
-- Iceberg history tables history.postgres.<schema>.<table>. Run once, by the image's entrypoint, as the database is
-- made on an empty volume.
--
-- The tables are demo-datagen's (nuna.datagen.live.Sources.ddl), which a test holds this file to. REPLICA IDENTITY
-- FULL: a delete's event then carries the whole row, not only its key, so the history row of a delete holds what was
-- deleted. Times are timestamp(3), not timestamptz: Debezium sends timestamptz as a string, and timestamp (with
-- time.precision.mode=connect) as a timestamp the sink can type; event_time is the world's time of the record's last
-- change, in UTC, and updated_at the load's.
CREATE SCHEMA party;
CREATE SCHEMA money_movement;
CREATE SCHEMA compliance;
CREATE TABLE party.parties (
    party_id bigint PRIMARY KEY,
    version integer,
    party_type varchar,
    legal_name varchar,
    first_name varchar,
    last_name varchar,
    birth_date date,
    tax_id varchar,
    tax_id_type varchar,
    address_line varchar,
    city varchar,
    state varchar,
    postal_code varchar,
    country varchar,
    email varchar,
    phone varchar,
    employment_status varchar,
    employer varchar,
    occupation varchar,
    control_person boolean,
    politically_exposed boolean,
    change_reason varchar,
    effective_date date,
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE party.parties REPLICA IDENTITY FULL;
CREATE TABLE party.accounts (
    brokerage_account bigint PRIMARY KEY,
    account_kind varchar,
    persona varchar,
    opened_on date,
    disposal_method varchar,
    base_currency_id bigint,
    status varchar,
    registration_type varchar,
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE party.accounts REPLICA IDENTITY FULL;
CREATE TABLE party.account_roles (
    role_id bigint PRIMARY KEY,
    brokerage_account bigint,
    party_id bigint,
    role varchar,
    effective_date date,
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE party.account_roles REPLICA IDENTITY FULL;
CREATE TABLE party.cip_checks (
    cip_id bigint PRIMARY KEY,
    party_id bigint,
    brokerage_account bigint,
    method varchar,
    evidence varchar,
    result varchar,
    checked_on date,
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE party.cip_checks REPLICA IDENTITY FULL;
CREATE TABLE party.suitability_profiles (
    brokerage_account bigint PRIMARY KEY,
    version integer,
    investment_objective varchar,
    risk_tolerance varchar,
    time_horizon varchar,
    liquidity_needs varchar,
    investment_experience varchar,
    annual_income_band varchar,
    net_worth_band varchar,
    liquid_net_worth_band varchar,
    change_reason varchar,
    effective_date date,
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE party.suitability_profiles REPLICA IDENTITY FULL;
CREATE TABLE party.beneficial_owners (
    owner_id bigint PRIMARY KEY,
    brokerage_account bigint,
    entity_party_id bigint,
    full_name varchar,
    birth_date date,
    tax_id varchar,
    address_line varchar,
    city varchar,
    state varchar,
    postal_code varchar,
    ownership_percent numeric(5, 2),
    control_person boolean,
    certified_on date,
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE party.beneficial_owners REPLICA IDENTITY FULL;
CREATE TABLE party.trusted_contacts (
    contact_id bigint PRIMARY KEY,
    version integer,
    brokerage_account bigint,
    full_name varchar,
    relationship varchar,
    phone varchar,
    email varchar,
    change_reason varchar,
    effective_date date,
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE party.trusted_contacts REPLICA IDENTITY FULL;
CREATE TABLE money_movement.payments (
    payment_id varchar PRIMARY KEY,
    version integer,
    brokerage_account bigint,
    method varchar,
    direction varchar,
    status varchar,
    amount numeric(18, 2),
    financial_transaction_id bigint,
    template_id integer,
    expected_settlement_date date,
    bank_routing_number varchar,
    bank_account_number varchar,
    counterparty_name varchar,
    contra_brokerage_account bigint,
    business_date date,
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE money_movement.payments REPLICA IDENTITY FULL;
CREATE TABLE money_movement.ach_returns (
    return_id varchar PRIMARY KEY,
    payment_id varchar,
    brokerage_account bigint,
    return_code varchar,
    return_reason varchar,
    amount numeric(18, 2),
    after_settlement boolean,
    financial_transaction_id bigint,
    business_date date,
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE money_movement.ach_returns REPLICA IDENTITY FULL;
CREATE TABLE money_movement.acats_transfers (
    acats_control_number varchar PRIMARY KEY,
    brokerage_account bigint,
    direction varchar,
    transfer_type varchar,
    contra_firm_number varchar,
    status varchar,
    requested_on date,
    settlement_date date,
    cash_amount numeric(18, 2),
    positions integer,
    shares numeric(18, 5),
    fee_amount numeric(18, 2),
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE money_movement.acats_transfers REPLICA IDENTITY FULL;
CREATE TABLE compliance.aml_alerts (
    alert_id varchar PRIMARY KEY,
    version integer,
    scenario varchar,
    brokerage_account bigint,
    party_id bigint,
    status varchar,
    amount numeric(18, 2),
    detail varchar,
    case_id varchar,
    business_date date,
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE compliance.aml_alerts REPLICA IDENTITY FULL;
CREATE TABLE compliance.aml_cases (
    case_id varchar PRIMARY KEY,
    version integer,
    case_type varchar,
    brokerage_account bigint,
    party_id bigint,
    status varchar,
    alert_ids varchar,
    opened_on date,
    filing_type varchar,
    filing_due date,
    filed_on date,
    filing_reference varchar,
    business_date date,
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE compliance.aml_cases REPLICA IDENTITY FULL;
CREATE TABLE compliance.complaints (
    complaint_id varchar PRIMARY KEY,
    version integer,
    brokerage_account bigint,
    party_id bigint,
    channel varchar,
    category varchar,
    product varchar,
    status varchar,
    received_on date,
    closed_on date,
    disposition varchar,
    business_date date,
    event_time timestamp(3),
    updated_at timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE compliance.complaints REPLICA IDENTITY FULL;

-- The demo's people, with their Keycloak passwords. Only cora (demo-compliance) and the superusers, gary and
-- presenter, read compliance, as in the catalog (docs/design/brokerage-world.md). ana reads, writes, creates, alters
-- and drops in party and money_movement; stella and oscar read them. In PostgreSQL only an object's owner may alter or
-- drop it, so backoffice_owner owns those two schemas and their tables and ana is a member. backoffice stays the
-- database's owner, demo-datagen's login (Main cdc) and Debezium's.
CREATE ROLE backoffice_owner NOLOGIN;
ALTER SCHEMA party OWNER TO backoffice_owner;
ALTER SCHEMA money_movement OWNER TO backoffice_owner;
DO $$
DECLARE t record;
BEGIN
    FOR t IN SELECT schemaname, tablename FROM pg_tables WHERE schemaname IN ('party', 'money_movement') LOOP
        EXECUTE format('ALTER TABLE %I.%I OWNER TO backoffice_owner', t.schemaname, t.tablename);
    END LOOP;
END $$;

CREATE ROLE ana LOGIN PASSWORD '${PASSWORD_ANA}';
GRANT backoffice_owner TO ana;
GRANT CREATE ON DATABASE backoffice TO ana;
GRANT CREATE ON SCHEMA public TO ana;
CREATE ROLE gary LOGIN SUPERUSER PASSWORD '${PASSWORD_GARY}';
CREATE ROLE presenter LOGIN SUPERUSER PASSWORD '${PASSWORD_PRESENTER}';
CREATE ROLE stella LOGIN PASSWORD '${PASSWORD_STELLA}';
CREATE ROLE oscar LOGIN PASSWORD '${PASSWORD_OSCAR}';
GRANT USAGE ON SCHEMA party, money_movement TO stella, oscar;
GRANT SELECT ON ALL TABLES IN SCHEMA party, money_movement TO stella, oscar;
CREATE ROLE cora LOGIN PASSWORD '${PASSWORD_CORA}';
GRANT USAGE ON SCHEMA compliance TO cora;
GRANT SELECT ON ALL TABLES IN SCHEMA compliance TO cora;
-- Everyone also reads the server's statistics, settings and activity (pg_monitor); gary and presenter have them as superusers.
GRANT pg_monitor TO ana, stella, oscar, cora;

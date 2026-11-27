-- The CDC leg's Oracle source (demo/k8s/cdc.yaml): Oracle Database Free, PDB FREEPDB1, table TREASURY.BANK_PAYMENTS,
-- the brokerage world's ACH and wire payments as the firm's bank sees them (item 152), which demo-datagen's Main cdc
-- writes at each demo.sh cdc as they stood after the lake's last business day, only what changed, and Debezium captures
-- through LogMiner into the Iceberg history table history.oracle.treasury.bank_payments. The table is demo-datagen's
-- (nuna.datagen.live.Sources.ddl), which a test holds this file to. Item 153 adds the firm's General Ledger beside it,
-- TREASURY.GL_JOURNAL and TREASURY.GL_BALANCES (one control account per customer money definition), captured the
-- same way into history.oracle.treasury.gl_journal and gl_balances.
--
-- An initialization script (/container-entrypoint-initdb.d): it runs as SYS in the root container once, at the
-- first start on an empty volume, onto which the pod copies the image's ready-made database (faststart); later starts
-- open the kept database without it (backlog 293). It stops the container if a statement fails.
--
-- What Debezium needs (its Oracle connector guide): archive log mode, minimal supplemental logging for the
-- database and all-column logging for the captured table, and the LogMiner user c##dbzuser with the guide's
-- grants. The pod has already cut the memory targets before the database first opened (cdc.yaml). The pod's
-- startup script, which the image runs after this, writes /tmp/cdc-ready, which its readiness probe waits for.
WHENEVER SQLERROR EXIT SQL.SQLCODE

HOST mkdir -p /opt/oracle/oradata/recovery_area
ALTER SYSTEM SET db_recovery_file_dest_size = 4G;
ALTER SYSTEM SET db_recovery_file_dest = '/opt/oracle/oradata/recovery_area' SCOPE = SPFILE;

SHUTDOWN IMMEDIATE
STARTUP MOUNT
ALTER DATABASE ARCHIVELOG;
ALTER DATABASE OPEN;
ALTER PLUGGABLE DATABASE ALL OPEN;
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;

-- The LogMiner user's tablespace, in the root and in the PDB, and the user (Debezium's guide, multi-tenant).
CREATE TABLESPACE logminer_tbs DATAFILE '/opt/oracle/oradata/FREE/logminer_tbs.dbf'
    SIZE 25M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;
ALTER SESSION SET CONTAINER = FREEPDB1;
CREATE TABLESPACE logminer_tbs DATAFILE '/opt/oracle/oradata/FREE/FREEPDB1/logminer_tbs.dbf'
    SIZE 25M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;
ALTER SESSION SET CONTAINER = CDB$ROOT;

CREATE USER c##dbzuser IDENTIFIED BY "${PASSWORD_DEBEZIUM}"
    DEFAULT TABLESPACE logminer_tbs QUOTA UNLIMITED ON logminer_tbs CONTAINER = ALL;
GRANT CREATE SESSION TO c##dbzuser CONTAINER = ALL;
GRANT SET CONTAINER TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$DATABASE TO c##dbzuser CONTAINER = ALL;
GRANT FLASHBACK ANY TABLE TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ANY TABLE TO c##dbzuser CONTAINER = ALL;
GRANT SELECT_CATALOG_ROLE TO c##dbzuser CONTAINER = ALL;
GRANT EXECUTE_CATALOG_ROLE TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ANY TRANSACTION TO c##dbzuser CONTAINER = ALL;
GRANT LOGMINING TO c##dbzuser CONTAINER = ALL;
GRANT CREATE TABLE TO c##dbzuser CONTAINER = ALL;
GRANT LOCK ANY TABLE TO c##dbzuser CONTAINER = ALL;
GRANT CREATE SEQUENCE TO c##dbzuser CONTAINER = ALL;
GRANT EXECUTE ON DBMS_LOGMNR TO c##dbzuser CONTAINER = ALL;
GRANT EXECUTE ON DBMS_LOGMNR_D TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$LOG TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$LOG_HISTORY TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$LOGMNR_LOGS TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$LOGMNR_CONTENTS TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$LOGMNR_PARAMETERS TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$LOGFILE TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$ARCHIVED_LOG TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$ARCHIVE_DEST_STATUS TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$TRANSACTION TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$MYSTAT TO c##dbzuser CONTAINER = ALL;
GRANT SELECT ON V_$STATNAME TO c##dbzuser CONTAINER = ALL;

-- The application's schema and tables, in the PDB; demo-datagen signs in as treasury.
ALTER SESSION SET CONTAINER = FREEPDB1;
CREATE USER treasury IDENTIFIED BY "${PASSWORD_TREASURY}" DEFAULT TABLESPACE users QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE TO treasury;

CREATE TABLE treasury.bank_payments (
    payment_id VARCHAR2(400) PRIMARY KEY,
    version NUMBER(9),
    brokerage_account NUMBER(18),
    rail VARCHAR2(400),
    direction VARCHAR2(400),
    status VARCHAR2(400),
    amount NUMBER(18, 2),
    firm_bank_account VARCHAR2(400),
    counterparty_routing_number VARCHAR2(400),
    counterparty_account_number VARCHAR2(400),
    counterparty_name VARCHAR2(400),
    value_date DATE,
    event_time TIMESTAMP(3),
    updated_at TIMESTAMP(3) DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE treasury.bank_payments ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;
CREATE TABLE treasury.gl_journal (
    journal_id VARCHAR2(400) PRIMARY KEY,
    business_date DATE,
    gl_account VARCHAR2(400),
    gl_account_name VARCHAR2(400),
    definition_id NUMBER(9),
    debit_total NUMBER(20, 2),
    credit_total NUMBER(20, 2),
    line_count NUMBER(18),
    posted_at TIMESTAMP(3),
    updated_at TIMESTAMP(3) DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE treasury.gl_journal ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;
CREATE TABLE treasury.gl_balances (
    balance_id VARCHAR2(400) PRIMARY KEY,
    business_date DATE,
    gl_account VARCHAR2(400),
    gl_account_name VARCHAR2(400),
    definition_id NUMBER(9),
    normal_side VARCHAR2(400),
    balance NUMBER(20, 2),
    updated_at TIMESTAMP(3) DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE treasury.gl_balances ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;

-- The demo's people, with their Keycloak passwords: everyone reads every table, ana also writes, creates,
-- alters and drops tables in any schema, and gary and presenter are DBAs.
CREATE USER ana IDENTIFIED BY "${PASSWORD_ANA}" DEFAULT TABLESPACE users QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, SELECT ANY TABLE, INSERT ANY TABLE, UPDATE ANY TABLE, DELETE ANY TABLE,
    CREATE ANY TABLE, ALTER ANY TABLE, DROP ANY TABLE TO ana;
CREATE USER gary IDENTIFIED BY "${PASSWORD_GARY}" DEFAULT TABLESPACE users QUOTA UNLIMITED ON users;
GRANT DBA TO gary;
CREATE USER presenter IDENTIFIED BY "${PASSWORD_PRESENTER}" DEFAULT TABLESPACE users QUOTA UNLIMITED ON users;
GRANT DBA TO presenter;
CREATE USER stella IDENTIFIED BY "${PASSWORD_STELLA}";
GRANT CREATE SESSION, SELECT ANY TABLE TO stella;
CREATE USER oscar IDENTIFIED BY "${PASSWORD_OSCAR}";
GRANT CREATE SESSION, SELECT ANY TABLE TO oscar;
CREATE USER cora IDENTIFIED BY "${PASSWORD_CORA}";
GRANT CREATE SESSION, SELECT ANY TABLE TO cora;
-- Everyone also reads the data dictionary and the V$ views (SELECT_CATALOG_ROLE); gary and presenter have them through DBA.
GRANT SELECT_CATALOG_ROLE TO ana, stella, oscar, cora;

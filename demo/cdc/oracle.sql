-- The CDC leg's Oracle source (demo/k8s/cdc.yaml): Oracle Database Free, PDB FREEPDB1, table TREASURY.PAYMENTS,
-- which Debezium captures through LogMiner into the Iceberg history table history.oracle.treasury.payments.
--
-- A startup script (/container-entrypoint-startdb.d): the image's database is made at build time (faststart),
-- so initialization scripts never run. It runs as SYS in the root container on every start of a container, and
-- every container starts from the image's database. It stops the container if a statement fails.
--
-- What Debezium needs (its Oracle connector guide): archive log mode, minimal supplemental logging for the
-- database and all-column logging for the captured table, and the LogMiner user c##dbzuser with the guide's
-- grants. The pod has already cut the memory targets before the database first opened (cdc.yaml). The last
-- statement writes /tmp/cdc-ready, which the pod's readiness probe waits for.
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

CREATE USER c##dbzuser IDENTIFIED BY dbz
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

-- The application's schema and table, in the PDB.
ALTER SESSION SET CONTAINER = FREEPDB1;
CREATE USER treasury IDENTIFIED BY "keel-test" DEFAULT TABLESPACE users QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE TO treasury;

CREATE TABLE treasury.payments (
    payment_id NUMBER(10)    PRIMARY KEY,
    account    VARCHAR2(34)  NOT NULL,
    amount     NUMBER(18, 2) NOT NULL,
    currency   CHAR(3)       NOT NULL,
    status     VARCHAR2(12)  NOT NULL,
    updated_at TIMESTAMP(3)  DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE treasury.payments ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;

INSERT INTO treasury.payments (payment_id, account, amount, currency, status)
    VALUES (7001, 'DE89370400440532013000', 21900.00, 'EUR', 'SCHEDULED');
INSERT INTO treasury.payments (payment_id, account, amount, currency, status)
    VALUES (7002, 'DE02120300000000202051', 41125.00, 'EUR', 'SCHEDULED');
INSERT INTO treasury.payments (payment_id, account, amount, currency, status)
    VALUES (7003, 'GB33BUKB20201555555555', 18250.00, 'GBP', 'RELEASED');
INSERT INTO treasury.payments (payment_id, account, amount, currency, status)
    VALUES (7004, 'FR1420041010050500013M02606', 9800.00, 'EUR', 'SCHEDULED');
COMMIT;

-- The demo's people, with their Keycloak passwords: everyone reads every table, ana also writes, creates,
-- alters and drops tables in any schema, and gary is a DBA.
CREATE USER ana IDENTIFIED BY "ana-demo" DEFAULT TABLESPACE users QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, SELECT ANY TABLE, INSERT ANY TABLE, UPDATE ANY TABLE, DELETE ANY TABLE,
    CREATE ANY TABLE, ALTER ANY TABLE, DROP ANY TABLE TO ana;
CREATE USER gary IDENTIFIED BY "gary-demo" DEFAULT TABLESPACE users QUOTA UNLIMITED ON users;
GRANT DBA TO gary;
CREATE USER stella IDENTIFIED BY "stella-demo";
GRANT CREATE SESSION, SELECT ANY TABLE TO stella;
CREATE USER oscar IDENTIFIED BY "oscar-demo";
GRANT CREATE SESSION, SELECT ANY TABLE TO oscar;

-- CloudBeaver's account (demo/k8s/cloudbeaver.yaml): it reads the table and changes nothing.
CREATE USER reader IDENTIFIED BY "reader-demo";
GRANT CREATE SESSION TO reader;
GRANT SELECT ON treasury.payments TO reader;

HOST touch /tmp/cdc-ready

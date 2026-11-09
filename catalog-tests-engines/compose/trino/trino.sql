-- Trino 484 against the catalog (catalog "fid"); nested namespaces are dotted schema names.
-- Each RESULT row is asserted by EngineMatrixTest; the CLI stops at the first failing statement.
CREATE SCHEMA IF NOT EXISTS fid."m8";
CREATE SCHEMA IF NOT EXISTS fid."m8.deep";
CREATE SCHEMA fid."m8.deep.trino";
SELECT 'RESULT', 'trino', 'ns.create', schema_name FROM fid.information_schema.schemata WHERE schema_name = 'm8.deep.trino';

CREATE TABLE fid."m8.deep.trino".t_v2 (id bigint, name varchar) WITH (format_version = 2);
SELECT 'RESULT', 'trino', 'v2.create', value FROM fid."m8.deep.trino"."t_v2$properties" WHERE key = 'format-version';
INSERT INTO fid."m8.deep.trino".t_v2 VALUES (1, 'a'), (2, 'b'), (3, 'c');
SELECT 'RESULT', 'trino', 'v2.read', count(*), sum(id) FROM fid."m8.deep.trino".t_v2;
ALTER TABLE fid."m8.deep.trino".t_v2 ADD COLUMN note varchar;
INSERT INTO fid."m8.deep.trino".t_v2 VALUES (4, 'd', 'x');
SELECT 'RESULT', 'trino', 'v2.evolve', count(*), count(note) FROM fid."m8.deep.trino".t_v2;
DROP TABLE fid."m8.deep.trino".t_v2;
SELECT 'RESULT', 'trino', 'v2.drop', CASE WHEN count(*) = 0 THEN 'ok' ELSE 'still-listed' END FROM fid.information_schema.tables WHERE table_schema = 'm8.deep.trino' AND table_name = 't_v2';

CREATE TABLE fid."m8.deep.trino".t_v3 (id bigint, name varchar) WITH (format_version = 3);
SELECT 'RESULT', 'trino', 'v3.create', value FROM fid."m8.deep.trino"."t_v3$properties" WHERE key = 'format-version';
INSERT INTO fid."m8.deep.trino".t_v3 VALUES (1, 'a'), (2, 'b'), (3, 'c');
SELECT 'RESULT', 'trino', 'v3.read', count(*), sum(id) FROM fid."m8.deep.trino".t_v3;
ALTER TABLE fid."m8.deep.trino".t_v3 ADD COLUMN note varchar;
INSERT INTO fid."m8.deep.trino".t_v3 VALUES (4, 'd', 'x');
SELECT 'RESULT', 'trino', 'v3.evolve', count(*), count(note) FROM fid."m8.deep.trino".t_v3;
DROP TABLE fid."m8.deep.trino".t_v3;
SELECT 'RESULT', 'trino', 'v3.drop', CASE WHEN count(*) = 0 THEN 'ok' ELSE 'still-listed' END FROM fid.information_schema.tables WHERE table_schema = 'm8.deep.trino' AND table_name = 't_v3';

DROP SCHEMA fid."m8.deep.trino";
SELECT 'RESULT', 'trino', 'ns.drop', CASE WHEN count(*) = 0 THEN 'ok' ELSE 'still-listed' END FROM fid.information_schema.schemata WHERE schema_name = 'm8.deep.trino';

-- Spark's shared tables: v2, and v3 written by another engine.
SELECT 'RESULT', 'trino', 'cross.v2', count(*), count(note) FROM fid."m8.shared.spark".t_v2;
SELECT 'RESULT', 'trino', 'cross.v3', count(*), count(note) FROM fid."m8.shared.spark".t_v3;
SELECT 'RESULT', 'trino', 'cross.v3dv', count(*), sum(id) FROM fid."m8.shared.spark".t_v3_dv;

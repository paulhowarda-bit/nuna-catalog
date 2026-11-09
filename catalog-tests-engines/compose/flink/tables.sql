-- Flink 2.3 + Iceberg 1.12.0: create, write, read, evolve. RESULT rows are asserted by EngineMatrixTest.
CREATE DATABASE IF NOT EXISTS fid_root.m8;
CREATE DATABASE IF NOT EXISTS fid_m8.deep;
CREATE DATABASE fid.flink;

CREATE TABLE fid.flink.t_v2 (id BIGINT, name STRING) WITH ('format-version' = '2');
INSERT INTO fid.flink.t_v2 VALUES (1, 'a'), (2, 'b'), (3, 'c');
SELECT 'RESULT' AS r, 'flink' AS e, 'v2.read' AS c, COUNT(*) AS n, SUM(id) AS s FROM fid.flink.t_v2;
ALTER TABLE fid.flink.t_v2 ADD note STRING;
INSERT INTO fid.flink.t_v2 VALUES (4, 'd', 'x');
SELECT 'RESULT' AS r, 'flink' AS e, 'v2.evolve' AS c, COUNT(*) AS n, COUNT(note) AS s FROM fid.flink.t_v2;

CREATE TABLE fid.flink.t_v3 (id BIGINT, name STRING) WITH ('format-version' = '3');
INSERT INTO fid.flink.t_v3 VALUES (1, 'a'), (2, 'b'), (3, 'c');
SELECT 'RESULT' AS r, 'flink' AS e, 'v3.read' AS c, COUNT(*) AS n, SUM(id) AS s FROM fid.flink.t_v3;
ALTER TABLE fid.flink.t_v3 ADD note STRING;
INSERT INTO fid.flink.t_v3 VALUES (4, 'd', 'x');
SELECT 'RESULT' AS r, 'flink' AS e, 'v3.evolve' AS c, COUNT(*) AS n, COUNT(note) AS s FROM fid.flink.t_v3;

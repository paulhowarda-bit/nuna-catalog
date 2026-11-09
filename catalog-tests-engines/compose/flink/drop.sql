-- Flink: drop both tables and the namespace (RESTRICT: refused while a table is left), then read Spark's
-- shared tables. run.sh checks each drop with the catalog itself.
DROP TABLE fid.flink.t_v2;
DROP TABLE fid.flink.t_v3;
DROP DATABASE fid.flink RESTRICT;

SELECT 'RESULT' AS r, 'flink' AS e, 'cross.v2' AS c, COUNT(*) AS n, COUNT(note) AS s FROM fid_shared.spark.t_v2;
SELECT 'RESULT' AS r, 'flink' AS e, 'cross.v3' AS c, COUNT(*) AS n, COUNT(note) AS s FROM fid_shared.spark.t_v3;
SELECT 'RESULT' AS r, 'flink' AS e, 'cross.v3dv' AS c, COUNT(*) AS n, SUM(id) AS s FROM fid_shared.spark.t_v3_dv;

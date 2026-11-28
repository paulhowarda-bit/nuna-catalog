-- Backlog 327: each scan the maintenance worker reports names its failed jobs, at most 20, each with the table it
-- failed on and why (FailedJob.write: one a line, the kind, reason, table id, catalog, name, message and namespace
-- levels apart by tabs). NULL is a scan kept before this step, or reported to a server of the previous release,
-- which does not name the column. A nullable column with no default changes only the table's definition; those
-- servers' scan reports and reads keep working.
ALTER TABLE maintenance_scan ADD COLUMN failed_jobs TEXT;

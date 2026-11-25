-- Backlog 250: each scan the maintenance worker reports carries what it can reach, kept with the scan. worker_groups
-- holds the groups its token carried, one a line in order; worker_storage its storage identities, each with the
-- prefixes it covers there, whether the store let it list each, and the deletes there done, refused and failed
-- (WorkerStorage.write). NULL in either is a scan kept before this step, or reported to a server of the previous
-- release, which does not name the columns. Nullable columns with no default change only the table's definition;
-- those servers' scan reports and reads keep working.
ALTER TABLE maintenance_scan ADD COLUMN worker_groups TEXT;
ALTER TABLE maintenance_scan ADD COLUMN worker_storage TEXT;

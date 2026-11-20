-- D26 (amended 2026-11-19 and 2026-11-20, backlog 98): a storage profile records the KMS key every
-- write to its tables uses, so that a change of key is written and audited like any other change
-- to the profile. NULL when the profile names no key; a profile stored before this step reads as
-- naming none until a server of this release records it. A nullable column with no default changes
-- only the table's definition. Servers of the previous release do not name the column: their reads
-- and upserts of a profile keep working, and leave a recorded key as it is.
ALTER TABLE storage_profile ADD COLUMN kms_key TEXT;

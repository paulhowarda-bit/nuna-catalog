-- v1.x (D33, item 26's design, backlog 102): a catalog's branches and tags. The ref record of V3 gains its id, which a
-- branch's own pointers and journal rows are keyed by (never its name, so a name used again finds no old rows), when
-- and by whom it was made, and what from: from_ref is the ref it was made from, NULL for main, and from_name that
-- ref's name then, 'main' for main. base_position stays unused: a ref keeps its point as a cut, not as a journal
-- position. No release before this one writes ref, so the table is empty and the new columns can be NOT NULL.
--
-- ref_entry is a ref's cut: every namespace, and every active table and view with its main pointer, as of the moment
-- the ref was made, read by one INSERT ... SELECT so its statement snapshot holds every multi-table transaction
-- whole. object_id is the namespace's, table's or view's id; parent_id is the namespace that held it, or the catalog
-- for a top-level namespace; a namespace has no pointer columns. The cut never changes; deleting the ref deletes it.
-- Both tables are new or unused by the previous release, so its servers keep serving while this applies.
ALTER TABLE ref ADD COLUMN id UUID NOT NULL;
ALTER TABLE ref ADD COLUMN created_at TIMESTAMPTZ NOT NULL;
ALTER TABLE ref ADD COLUMN created_by TEXT NOT NULL;
ALTER TABLE ref ADD COLUMN from_ref UUID;
ALTER TABLE ref ADD COLUMN from_name TEXT NOT NULL;
CREATE UNIQUE INDEX ref_id ON ref (id);

CREATE TABLE ref_entry (
    ref_id            UUID             NOT NULL,
    object_id         UUID             NOT NULL,
    kind              TEXT             NOT NULL CHECK (kind IN ('NAMESPACE', 'TABLE', 'VIEW')),
    parent_id         UUID             NOT NULL,
    name_norm         TEXT COLLATE "C" NOT NULL,
    name              TEXT             NOT NULL,
    version           BIGINT           CHECK (version >= 1),
    metadata_location TEXT,
    sha256            TEXT,
    PRIMARY KEY (ref_id, object_id)
);
-- Listings and name lookups on a ref, as catalog_object_listing serves them on main.
CREATE INDEX ref_entry_listing ON ref_entry (ref_id, parent_id, kind, name_norm, object_id);
-- Which refs name an object: what a purge claim and cleanup ask (items 103 and 108).
CREATE INDEX ref_entry_object ON ref_entry (object_id);

-- v1.x (D33, item 26's design, backlog 102): a catalog's branches and tags. The ref record of V3 gains its id, which a
-- branch's own pointers and journal rows are keyed by (never its name, so a name used again finds no old rows), when
-- and by whom it was made, and what from: from_ref is the ref it was made from, NULL for main, and from_name that
-- ref's name then, 'main' for main. base_position stays unused: a ref keeps its point as a cut, not as a journal
-- position. No release before this one writes ref, so the table is empty and the new columns can be NOT NULL.
--
-- ref_entry is a ref's cut: every namespace, and every active table and view with its main pointer, as of the moment
-- the ref was made, read by one INSERT ... SELECT, which reads as of one SCN, so it holds every multi-table
-- transaction whole. object_id is the namespace's, table's or view's id; parent_id is the namespace that held it, or
-- the catalog for a top-level namespace; a namespace has no pointer columns. Names are copied as stored, through the
-- store's text codec. The cut never changes; deleting the ref deletes it. Both tables are new or unused by the
-- previous release, so its servers keep serving while this applies.
ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;

ALTER TABLE ref ADD (
    id         RAW(16)                  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR2(1000 CHAR)      NOT NULL,
    from_ref   RAW(16),
    from_name  VARCHAR2(1000 CHAR)      NOT NULL
);
CREATE UNIQUE INDEX ref_id ON ref (id);

CREATE TABLE ref_entry (
    ref_id            RAW(16)             NOT NULL,
    object_id         RAW(16)             NOT NULL,
    kind              VARCHAR2(9)         NOT NULL CONSTRAINT ref_entry_kind_ck CHECK (kind IN ('NAMESPACE', 'TABLE', 'VIEW')),
    parent_id         RAW(16)             NOT NULL,
    name_norm         VARCHAR2(1000 CHAR) NOT NULL,
    name              VARCHAR2(1000 CHAR) NOT NULL,
    version           NUMBER(19)          CONSTRAINT ref_entry_version_ck CHECK (version >= 1),
    metadata_location VARCHAR2(4000),
    sha256            VARCHAR2(64),
    CONSTRAINT ref_entry_pk PRIMARY KEY (ref_id, object_id)
);
-- Listings and name lookups on a ref, as catalog_object_listing serves them on main.
CREATE INDEX ref_entry_listing ON ref_entry (ref_id, parent_id, kind, name_norm, object_id);
-- Which refs name an object: what a purge claim and cleanup ask (items 103 and 108).
CREATE INDEX ref_entry_object ON ref_entry (object_id);

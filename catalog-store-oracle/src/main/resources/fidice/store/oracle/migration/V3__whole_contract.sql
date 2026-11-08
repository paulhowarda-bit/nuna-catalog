-- M2: the whole storage contract (docs/storage-design.md section 9). Only additions and relaxed
-- constraints, so nodes of the previous release keep serving while this applies (D8.7).
--
-- Every text value is stored through the store's text codec: Oracle stores '' as NULL, so the
-- store writes '' as CHR(0) and prefixes CHR(0) to a value that starts with one. NOT NULL keeps its
-- meaning, NULL and '' stay apart, and byte order is kept. Normalized names compare as bytes: the
-- store's self-check reports a session whose NLS_SORT is not BINARY.
--
-- A DDL statement waits up to a minute for the previous release's open transactions on a table
-- instead of failing at once (ORA-00054); indexes on live tables are built ONLINE.
ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;

-- Catalogs gain their boundary (D28). Catalogs made before M2 have none until set.
ALTER TABLE catalog ADD (
    storage_profile_id RAW(16),
    kms_key_set        VARCHAR2(4000),
    legal_entity       VARCHAR2(4000)
);

CREATE TABLE storage_profile (
    id       RAW(16)        CONSTRAINT storage_profile_pk PRIMARY KEY,
    name     VARCHAR2(4000) NOT NULL,
    kind     VARCHAR2(30)   NOT NULL,
    endpoint VARCHAR2(4000),
    sts_role VARCHAR2(4000) NOT NULL
);

-- A profile's allowed location prefixes, in the order given (Oracle has no array column).
CREATE TABLE storage_profile_location (
    profile_id RAW(16)        NOT NULL,
    ordinal    NUMBER(10)     NOT NULL,
    location   VARCHAR2(4000) NOT NULL,
    CONSTRAINT storage_profile_location_pk PRIMARY KEY (profile_id, ordinal)
);

-- parent_id is the parent namespace, or the catalog for a top-level namespace. Namespaces never
-- move and a namespace with children cannot be dropped, so a namespace's ancestors are found by
-- walking parent_id (CONNECT BY) rather than stored.
CREATE TABLE namespace (
    id         RAW(16)             CONSTRAINT namespace_pk PRIMARY KEY,
    catalog_id RAW(16)             NOT NULL,
    parent_id  RAW(16)             NOT NULL,
    name_norm  VARCHAR2(1000 CHAR) NOT NULL,
    name       VARCHAR2(1000 CHAR) NOT NULL,
    version    NUMBER(19)          NOT NULL CONSTRAINT namespace_version_ck CHECK (version >= 1),
    CONSTRAINT namespace_name_uk UNIQUE (parent_id, name_norm)
);

CREATE TABLE namespace_property (
    namespace_id RAW(16)             NOT NULL,
    key          VARCHAR2(1000 CHAR) NOT NULL,
    value        CLOB                NOT NULL,
    CONSTRAINT namespace_property_pk PRIMARY KEY (namespace_id, key)
);

-- Tables and views (D25). Names are unique among active objects: the function-based index holds
-- only active rows, since rows whose every key expression is NULL are not indexed.
CREATE TABLE catalog_object (
    id            RAW(16)                  CONSTRAINT catalog_object_pk PRIMARY KEY,
    catalog_id    RAW(16)                  NOT NULL,
    namespace_id  RAW(16)                  NOT NULL,
    name_norm     VARCHAR2(1000 CHAR)      NOT NULL,
    name          VARCHAR2(1000 CHAR)      NOT NULL,
    kind          VARCHAR2(5)              NOT NULL CONSTRAINT catalog_object_kind_ck CHECK (kind IN ('TABLE', 'VIEW')),
    state         VARCHAR2(7)              NOT NULL
                  CONSTRAINT catalog_object_state_ck CHECK (state IN ('ACTIVE', 'DROPPED', 'PURGED')),
    dropped_at    TIMESTAMP WITH TIME ZONE,
    purge_after   TIMESTAMP WITH TIME ZONE,
    location_root VARCHAR2(4000)           NOT NULL
);
CREATE UNIQUE INDEX catalog_object_active_name ON catalog_object (
    CASE WHEN state = 'ACTIVE' THEN namespace_id END,
    CASE WHEN state = 'ACTIVE' THEN name_norm END);
CREATE INDEX catalog_object_listing ON catalog_object (namespace_id, kind, state, name_norm, id);
CREATE INDEX catalog_object_purge ON catalog_object (state, purge_after, id);

-- The namespace that holds an object and that namespace's ancestors, kept so a dropped object
-- still knows what held it after its namespace is gone (legal holds, D24).
CREATE TABLE object_path (
    object_id    RAW(16) NOT NULL,
    namespace_id RAW(16) NOT NULL,
    CONSTRAINT object_path_pk PRIMARY KEY (object_id, namespace_id)
);

-- Projections above the piece budget (D8.5): piece 0 stays in pointer.projection, the rest are
-- here, keyed by the pointer version they belong to. A previous-release write bumps the version
-- and leaves no pieces for it, so its whole projection in the pointer row is read as is.
CREATE TABLE projection_piece (
    object_id RAW(16)            NOT NULL,
    branch    VARCHAR2(255 CHAR) NOT NULL,
    version   NUMBER(19)         NOT NULL,
    piece     NUMBER(10)         NOT NULL CONSTRAINT projection_piece_piece_ck CHECK (piece >= 1),
    bytes     BLOB               NOT NULL,
    CONSTRAINT projection_piece_pk PRIMARY KEY (object_id, branch, version, piece)
);

CREATE INDEX journal_by_pointer ON journal (object_id, branch, new_version) ONLINE;

-- Key columns are sized so every key fits Oracle's index key limit on an 8 KiB block.
CREATE TABLE grant_record (
    catalog_id     RAW(16)                  NOT NULL,
    securable_kind VARCHAR2(9)              NOT NULL,
    securable_id   RAW(16)                  NOT NULL,
    group_name     VARCHAR2(1000 CHAR)      NOT NULL,
    privilege      VARCHAR2(255 CHAR)       NOT NULL,
    granted_by     VARCHAR2(1000 CHAR)      NOT NULL,
    granted_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT grant_record_pk PRIMARY KEY (securable_id, group_name, privilege)
);
CREATE INDEX grant_by_group ON grant_record (catalog_id, group_name, securable_id, privilege);

CREATE TABLE governance_attribute (
    target_kind VARCHAR2(9)              NOT NULL,
    target_id   RAW(16)                  NOT NULL,
    kind        VARCHAR2(30)             NOT NULL,
    value       VARCHAR2(4000)           NOT NULL,
    provenance  VARCHAR2(4000)           NOT NULL,
    set_by      VARCHAR2(1000 CHAR)      NOT NULL,
    set_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT governance_attribute_pk PRIMARY KEY (target_id, kind)
);

CREATE TABLE legal_hold (
    id          RAW(16)                  CONSTRAINT legal_hold_pk PRIMARY KEY,
    target_kind VARCHAR2(9)              NOT NULL,
    target_id   RAW(16)                  NOT NULL,
    reason      VARCHAR2(4000)           NOT NULL,
    placed_by   VARCHAR2(1000 CHAR)      NOT NULL,
    placed_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX legal_hold_target ON legal_hold (target_id);

-- flags holds the engine's flags sorted and joined by single spaces; a flag is one word.
CREATE TABLE trusted_engine (
    issuer         VARCHAR2(1000 CHAR) NOT NULL,
    client_id      VARCHAR2(500 CHAR)  NOT NULL,
    end_user_claim VARCHAR2(1000 CHAR) NOT NULL,
    flags          VARCHAR2(4000)      NOT NULL,
    CONSTRAINT trusted_engine_pk PRIMARY KEY (issuer, client_id)
);

-- Branch and tag records exist from v1 (D33, build-plan 2.4); their operations ship in v1.x.
-- base_position is the journal position the ref starts from, in the encoding v1.x's tag protocol
-- fixes (section 8).
CREATE TABLE ref (
    catalog_id    RAW(16)                  NOT NULL,
    name_norm     VARCHAR2(1000 CHAR)      NOT NULL,
    name          VARCHAR2(1000 CHAR)      NOT NULL,
    kind          VARCHAR2(6)              NOT NULL CONSTRAINT ref_kind_ck CHECK (kind IN ('BRANCH', 'TAG')),
    base_position VARCHAR2(4000),
    expires_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ref_pk PRIMARY KEY (catalog_id, name_norm)
);

-- Idempotency claims (D10): a claim row has no status yet. The previous release writes done
-- rows only, which leave the new columns empty.
ALTER TABLE idempotency MODIFY (status NULL);
ALTER TABLE idempotency ADD (
    change_id   RAW(16),
    lease_until TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idempotency_expiry ON idempotency (expires_at) ONLINE;

-- The outbox relay lease: one relay drains at a time (D23). outbox.commit_id holds the commit or
-- change id.
CREATE TABLE outbox_relay (
    singleton   NUMBER(1) CONSTRAINT outbox_relay_pk PRIMARY KEY
                          CONSTRAINT outbox_relay_singleton_ck CHECK (singleton = 1),
    relay_id    RAW(16),
    lease_until TIMESTAMP WITH TIME ZONE
);
INSERT INTO outbox_relay (singleton) VALUES (1);

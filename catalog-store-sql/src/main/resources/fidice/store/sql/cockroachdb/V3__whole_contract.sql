-- M2: the whole storage contract (docs/storage-design.md section 9). Only additions and relaxed
-- constraints, so nodes of the previous release keep serving while this applies (D8.7).
-- Normalized names sort in byte order: CockroachDB compares STRING as bytes, the order PostgreSQL
-- gets from COLLATE "C", which CockroachDB rejects as an unknown locale. A 32-bit column is INT4:
-- CockroachDB's INTEGER is 64 bits.

-- Catalogs gain their boundary (D28). Catalogs made before M2 have none until set.
ALTER TABLE catalog ADD COLUMN storage_profile_id UUID;
ALTER TABLE catalog ADD COLUMN kms_key_set TEXT;
ALTER TABLE catalog ADD COLUMN legal_entity TEXT;

CREATE TABLE storage_profile (
    id                UUID   PRIMARY KEY,
    name              TEXT   NOT NULL,
    kind              TEXT   NOT NULL,
    endpoint          TEXT,
    sts_role          TEXT   NOT NULL,
    allowed_locations TEXT[] NOT NULL
);

-- parent_id is the parent namespace, or the catalog for a top-level namespace. Namespaces never
-- move, so ancestors (outermost first, excluding the catalog) is fixed at creation.
CREATE TABLE namespace (
    id         UUID               PRIMARY KEY,
    catalog_id UUID               NOT NULL,
    parent_id  UUID               NOT NULL,
    ancestors  UUID[]             NOT NULL,
    name_norm  TEXT               NOT NULL,
    name       TEXT               NOT NULL,
    version    BIGINT             NOT NULL CHECK (version >= 1),
    UNIQUE (parent_id, name_norm)
);

CREATE TABLE namespace_property (
    namespace_id UUID NOT NULL,
    key          TEXT NOT NULL,
    value        TEXT NOT NULL,
    PRIMARY KEY (namespace_id, key)
);

-- Tables and views (D25). namespace_path is the namespace and its ancestors, kept on the object
-- so a dropped object still knows what held it after its namespace is gone.
CREATE TABLE catalog_object (
    id             UUID             PRIMARY KEY,
    catalog_id     UUID             NOT NULL,
    namespace_id   UUID             NOT NULL,
    namespace_path UUID[]           NOT NULL,
    name_norm      TEXT             NOT NULL,
    name           TEXT             NOT NULL,
    kind           TEXT             NOT NULL CHECK (kind IN ('TABLE', 'VIEW')),
    state          TEXT             NOT NULL CHECK (state IN ('ACTIVE', 'DROPPED', 'PURGED')),
    dropped_at     TIMESTAMPTZ,
    purge_after    TIMESTAMPTZ,
    location_root  TEXT             NOT NULL
);
CREATE UNIQUE INDEX catalog_object_active_name ON catalog_object (namespace_id, name_norm) WHERE state = 'ACTIVE';
CREATE INDEX catalog_object_listing ON catalog_object (namespace_id, kind, state, name_norm, id);
CREATE INDEX catalog_object_purge ON catalog_object (purge_after, id) WHERE state = 'DROPPED';

-- Projections above the piece budget (D8.5): piece 0 stays in pointer.projection, the rest are
-- here, keyed by the pointer version they belong to. A previous-release write bumps the version
-- and leaves no pieces for it, so its whole projection in the pointer row is read as is.
CREATE TABLE projection_piece (
    object_id UUID    NOT NULL,
    branch    TEXT    NOT NULL,
    version   BIGINT  NOT NULL,
    piece     INT4    NOT NULL CHECK (piece >= 1),
    bytes     BYTEA   NOT NULL,
    PRIMARY KEY (object_id, branch, version, piece)
);

CREATE INDEX journal_by_pointer ON journal (object_id, branch, new_version);

CREATE TABLE grant_record (
    catalog_id     UUID             NOT NULL,
    securable_kind TEXT             NOT NULL,
    securable_id   UUID             NOT NULL,
    group_name     TEXT             NOT NULL,
    privilege      TEXT             NOT NULL,
    granted_by     TEXT             NOT NULL,
    granted_at     TIMESTAMPTZ      NOT NULL,
    PRIMARY KEY (securable_id, group_name, privilege)
);
CREATE INDEX grant_by_group ON grant_record (catalog_id, group_name, securable_id, privilege);

CREATE TABLE governance_attribute (
    target_kind TEXT        NOT NULL,
    target_id   UUID        NOT NULL,
    kind        TEXT        NOT NULL,
    value       TEXT        NOT NULL,
    provenance  TEXT        NOT NULL,
    set_by      TEXT        NOT NULL,
    set_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (target_id, kind)
);

CREATE TABLE legal_hold (
    id          UUID        PRIMARY KEY,
    target_kind TEXT        NOT NULL,
    target_id   UUID        NOT NULL,
    reason      TEXT        NOT NULL,
    placed_by   TEXT        NOT NULL,
    placed_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX legal_hold_target ON legal_hold (target_id);

CREATE TABLE trusted_engine (
    issuer         TEXT   NOT NULL,
    client_id      TEXT   NOT NULL,
    end_user_claim TEXT   NOT NULL,
    flags          TEXT[] NOT NULL,
    PRIMARY KEY (issuer, client_id)
);

-- Branch and tag records exist from v1 (D33, build-plan 2.4); their operations ship in v1.x.
-- base_position is the journal position the ref starts from, in the encoding v1.x's tag protocol
-- fixes (section 8).
CREATE TABLE ref (
    catalog_id    UUID             NOT NULL,
    name_norm     TEXT             NOT NULL,
    name          TEXT             NOT NULL,
    kind          TEXT             NOT NULL CHECK (kind IN ('BRANCH', 'TAG')),
    base_position TEXT,
    expires_at    TIMESTAMPTZ,
    PRIMARY KEY (catalog_id, name_norm)
);

-- Idempotency claims (D10): a claim row has no status yet. The previous release writes done
-- rows only, which leave the new columns empty.
ALTER TABLE idempotency ALTER COLUMN status DROP NOT NULL;
ALTER TABLE idempotency ALTER COLUMN body DROP NOT NULL;
ALTER TABLE idempotency ADD COLUMN change_id UUID;
ALTER TABLE idempotency ADD COLUMN lease_until TIMESTAMPTZ;
CREATE INDEX idempotency_expiry ON idempotency (expires_at);

-- The outbox relay lease: one relay drains at a time (D23). outbox.commit_id holds the commit or
-- change id.
CREATE TABLE outbox_relay (
    singleton   BOOLEAN PRIMARY KEY CHECK (singleton),
    relay_id    UUID,
    lease_until TIMESTAMPTZ
);
INSERT INTO outbox_relay (singleton) VALUES (true);

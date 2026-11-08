-- M1: the commit core (docs/storage-design.md section 2), as on PostgreSQL, with three changes for
-- CockroachDB (section 5.4): its JSON type is JSONB, which rewrites the client's text, so JSON
-- text is kept in STRING columns; its INTEGER is 64 bits, so a 32-bit column is INT4; and seq
-- comes from a sequence that hands out one number per call (CACHE 1), never unique_rowid(), so
-- the outbox keeps the order section 9 (b) needs.

-- One row per (object, branch); branch is 'main' for every v1 commit (D33).
CREATE TABLE pointer (
    object_id         UUID        NOT NULL,
    branch            TEXT        NOT NULL,
    metadata_location TEXT        NOT NULL,
    version           BIGINT      NOT NULL CHECK (version >= 1),
    sha256            TEXT        NOT NULL,
    projection        BYTEA       NOT NULL,
    last_commit_id    UUID        NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (object_id, branch)
);

-- One row per swap.
CREATE SEQUENCE journal_seq CACHE 1;
CREATE TABLE journal (
    commit_id    UUID        NOT NULL,
    object_id    UUID        NOT NULL,
    branch       TEXT        NOT NULL,
    seq          BIGINT      NOT NULL DEFAULT nextval('journal_seq'),
    committed_at TIMESTAMPTZ NOT NULL,
    principal    TEXT        NOT NULL,
    end_user     TEXT,
    engine       TEXT,
    requirements STRING      NOT NULL,
    updates      STRING      NOT NULL,
    old_location TEXT,
    new_location TEXT        NOT NULL,
    old_version  BIGINT,
    new_version  BIGINT      NOT NULL,
    PRIMARY KEY (commit_id, object_id, branch)
);
ALTER SEQUENCE journal_seq OWNED BY journal.seq;

CREATE SEQUENCE outbox_seq CACHE 1;
CREATE TABLE outbox (
    seq        BIGINT      NOT NULL DEFAULT nextval('outbox_seq') PRIMARY KEY,
    commit_id  UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    type       TEXT        NOT NULL,
    payload    STRING      NOT NULL
);
ALTER SEQUENCE outbox_seq OWNED BY outbox.seq;

CREATE TABLE idempotency (
    key          TEXT        PRIMARY KEY,
    principal    TEXT        NOT NULL,
    request_hash TEXT        NOT NULL,
    status       INT4        NOT NULL,
    body         TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);

-- PostgreSQL's durability barrier (section 5.1). CockroachDB needs none (section 5.4); the
-- previous release's findCommit still increments it.
CREATE TABLE durability_barrier (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    counter   BIGINT  NOT NULL
);
INSERT INTO durability_barrier (singleton, counter) VALUES (true, 0);

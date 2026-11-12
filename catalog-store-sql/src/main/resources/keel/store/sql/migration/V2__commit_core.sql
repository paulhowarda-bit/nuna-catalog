-- M1: the commit core (docs/storage-design.md section 2).

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

-- One row per swap. JSON (not JSONB) keeps the client's text exactly as sent.
CREATE TABLE journal (
    commit_id    UUID        NOT NULL,
    object_id    UUID        NOT NULL,
    branch       TEXT        NOT NULL,
    seq          BIGINT      NOT NULL GENERATED ALWAYS AS IDENTITY,
    committed_at TIMESTAMPTZ NOT NULL,
    principal    TEXT        NOT NULL,
    end_user     TEXT,
    engine       TEXT,
    requirements JSON        NOT NULL,
    updates      JSON        NOT NULL,
    old_location TEXT,
    new_location TEXT        NOT NULL,
    old_version  BIGINT,
    new_version  BIGINT      NOT NULL,
    PRIMARY KEY (commit_id, object_id, branch)
);

CREATE TABLE outbox (
    seq        BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commit_id  UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    type       TEXT        NOT NULL,
    payload    JSON        NOT NULL
);

CREATE TABLE idempotency (
    key          TEXT        PRIMARY KEY,
    principal    TEXT        NOT NULL,
    request_hash TEXT        NOT NULL,
    status       INTEGER     NOT NULL,
    body         TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);

-- findCommit commits an increment here before answering COMMITTED: with synchronous commit,
-- that returns only once the standby has flushed WAL past the commit it found (section 5.1).
CREATE TABLE durability_barrier (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    counter   BIGINT  NOT NULL
);
INSERT INTO durability_barrier (singleton, counter) VALUES (true, 0);

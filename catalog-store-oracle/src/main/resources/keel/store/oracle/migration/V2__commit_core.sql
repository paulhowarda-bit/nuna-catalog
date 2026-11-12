-- M1: the commit core (docs/storage-design.md section 2).
-- JSON is a CLOB checked IS JSON, not the JSON type: the client's text stays exactly as sent (the
-- JSON type stores binary OSON), the check runs on 19c, and LogMiner-based CDC reads a CLOB.

-- One row per (object, branch); branch is 'main' for every v1 commit (D33).
CREATE TABLE pointer (
    object_id         RAW(16)                  NOT NULL,
    branch            VARCHAR2(255 CHAR)       NOT NULL,
    metadata_location VARCHAR2(4000)           NOT NULL,
    version           NUMBER(19)               NOT NULL CONSTRAINT pointer_version_ck CHECK (version >= 1),
    sha256            VARCHAR2(64)             NOT NULL,
    projection        BLOB                     NOT NULL,
    last_commit_id    RAW(16)                  NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pointer_pk PRIMARY KEY (object_id, branch)
);

-- One row per swap. ORDER keeps seq growing with time across RAC instances, which would otherwise
-- hand out values from separate caches.
CREATE TABLE journal (
    commit_id    RAW(16)                  NOT NULL,
    object_id    RAW(16)                  NOT NULL,
    branch       VARCHAR2(255 CHAR)       NOT NULL,
    seq          NUMBER(19)               GENERATED ALWAYS AS IDENTITY (ORDER) NOT NULL,
    committed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    principal    VARCHAR2(1000 CHAR)      NOT NULL,
    end_user     VARCHAR2(1000 CHAR),
    engine       VARCHAR2(255 CHAR),
    requirements CLOB                     NOT NULL CONSTRAINT journal_requirements_json CHECK (requirements IS JSON),
    updates      CLOB                     NOT NULL CONSTRAINT journal_updates_json CHECK (updates IS JSON),
    old_location VARCHAR2(4000),
    new_location VARCHAR2(4000)           NOT NULL,
    old_version  NUMBER(19),
    new_version  NUMBER(19)               NOT NULL,
    CONSTRAINT journal_pk PRIMARY KEY (commit_id, object_id, branch)
);

CREATE TABLE outbox (
    seq        NUMBER(19)               GENERATED ALWAYS AS IDENTITY (ORDER) CONSTRAINT outbox_pk PRIMARY KEY,
    commit_id  RAW(16)                  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    type       VARCHAR2(255 CHAR)       NOT NULL,
    payload    CLOB                     NOT NULL CONSTRAINT outbox_payload_json CHECK (payload IS JSON)
);

-- body may be NULL: Oracle stores an empty string as NULL, and an empty body (204) is a real answer.
CREATE TABLE idempotency (
    key          VARCHAR2(255 CHAR)       CONSTRAINT idempotency_pk PRIMARY KEY,
    principal    VARCHAR2(1000 CHAR)      NOT NULL,
    request_hash VARCHAR2(128)            NOT NULL,
    status       NUMBER(10)               NOT NULL,
    body         CLOB,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Oracle shows a commit to other sessions before its redo is written. When findCommit finds a
-- commit in the journal without Transaction Guard, it commits an increment here before answering
-- COMMITTED; that commit returns only once the redo (and, with Data Guard SYNC, the standby) holds
-- everything before it.
CREATE TABLE durability_barrier (
    singleton NUMBER(1)  CONSTRAINT durability_barrier_pk PRIMARY KEY
                         CONSTRAINT durability_barrier_singleton_ck CHECK (singleton = 1),
    counter   NUMBER(19) NOT NULL
);
INSERT INTO durability_barrier (singleton, counter) VALUES (1, 0);

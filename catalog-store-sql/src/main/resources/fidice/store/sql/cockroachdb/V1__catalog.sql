-- M0: catalogs only. M1 adds the full logical model (build-plan.md 2.4).
CREATE TABLE catalog (
    id         UUID        PRIMARY KEY,
    name_norm  TEXT        NOT NULL UNIQUE,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

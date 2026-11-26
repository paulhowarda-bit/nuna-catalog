-- Backlog 113 (D33, amended 2026-11-25): a grant may name a branch, and then holds only on requests through it.
-- Such grants live in a table of their own, so grant_record and its key are as the previous release left them, and
-- its servers keep granting, revoking and reading the grants that hold on every ref; they never see a grant that
-- names a branch, so they refuse what it alone would allow. any_grant reads both, ref_scope '*' marking a grant on
-- every ref. Deleting or merging a branch deletes its grants.
CREATE TABLE ref_grant (
    catalog_id     UUID             NOT NULL,
    ref_id         UUID             NOT NULL,
    securable_kind TEXT             NOT NULL,
    securable_id   UUID             NOT NULL,
    group_name     TEXT NOT NULL,
    privilege      TEXT NOT NULL,
    granted_by     TEXT             NOT NULL,
    granted_at     TIMESTAMPTZ      NOT NULL,
    PRIMARY KEY (securable_id, group_name, privilege, ref_id)
);
CREATE INDEX ref_grant_by_group ON ref_grant (catalog_id, group_name, securable_id, privilege, ref_id);
CREATE INDEX ref_grant_by_ref ON ref_grant (ref_id);

CREATE VIEW any_grant AS
    SELECT catalog_id, securable_kind, securable_id, group_name, privilege, granted_by, granted_at,
           CAST('*' AS TEXT) AS ref_scope
    FROM grant_record
    UNION ALL
    SELECT catalog_id, securable_kind, securable_id, group_name, privilege, granted_by, granted_at,
           CAST(ref_id AS TEXT) AS ref_scope
    FROM ref_grant;

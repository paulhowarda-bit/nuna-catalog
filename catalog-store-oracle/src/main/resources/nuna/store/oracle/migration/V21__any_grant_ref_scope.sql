-- Backlog 288: V20 wrote any_grant's branch ref_scope with REGEXP_REPLACE, but its back-references \1 to \5 went into
-- the file as the control characters 0x01 to 0x05, so every grant that names a branch read back as those characters,
-- not its ref id. V20 stays as it landed, so a database that applied it still validates; this replaces the view with
-- one that cuts the hex with SUBSTR, so its text holds no escapes to lose. ref_scope is unchanged otherwise: '*' for a
-- grant on every ref, else the ref id in lower-case hex with dashes, as Java writes a UUID.
ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;

CREATE OR REPLACE VIEW any_grant AS
    SELECT catalog_id, securable_kind, securable_id, group_name, privilege, granted_by, granted_at,
           CAST('*' AS VARCHAR2(36)) AS ref_scope
    FROM grant_record
    UNION ALL
    SELECT catalog_id, securable_kind, securable_id, group_name, privilege, granted_by, granted_at,
           CAST(LOWER(SUBSTR(RAWTOHEX(ref_id), 1, 8) || '-' || SUBSTR(RAWTOHEX(ref_id), 9, 4) || '-'
                || SUBSTR(RAWTOHEX(ref_id), 13, 4) || '-' || SUBSTR(RAWTOHEX(ref_id), 17, 4) || '-'
                || SUBSTR(RAWTOHEX(ref_id), 21, 12)) AS VARCHAR2(36)) AS ref_scope
    FROM ref_grant;

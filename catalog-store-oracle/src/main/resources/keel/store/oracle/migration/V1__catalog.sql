-- M0: catalogs only. M1 adds the commit core (V2).
-- UUIDs are RAW(16), most significant byte first: Oracle's own GUID form (SYS_GUID), half the size
-- of the text form in every key and index, and RAW compares as unsigned bytes, which is the key
-- order of D15. Every migration runs on 19c as well as 23ai/26ai.
CREATE TABLE catalog (
    id         RAW(16)                  CONSTRAINT catalog_pk PRIMARY KEY,
    name_norm  VARCHAR2(255 CHAR)       NOT NULL CONSTRAINT catalog_name_norm_uk UNIQUE,
    name       VARCHAR2(255 CHAR)       NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

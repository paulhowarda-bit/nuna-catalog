-- D24, D37, D45 (backlog 133): a legal hold released and a DR freeze lifted are kept, so a catalog's history of
-- them is complete (the console's holds page lists the lifts of the last 30 days). One row per release or lift,
-- written in the change's transaction, named by its change id and never changed: kind HOLD, with the hold's id, or
-- DR_FREEZE, with none; the level it was on, and the catalog that level lies in, so the history outlives a level
-- dropped since; the reason, who placed it and when, as the hold or freeze recorded them; and who lifted it and
-- when, by the catalog's clock. The table is new and no server of the previous release reads or writes it, so those
-- servers keep serving while the first server of this release creates it: no table they use is altered or locked.
-- A release or lift one of them makes during the upgrade is not kept here (its audit record is).
CREATE TABLE governance_lift (
    id          UUID        PRIMARY KEY,
    catalog_id  UUID        NOT NULL,
    kind        TEXT        NOT NULL,
    hold_id     UUID,
    target_kind TEXT        NOT NULL,
    target_id   UUID        NOT NULL,
    reason      TEXT        NOT NULL,
    placed_by   TEXT        NOT NULL,
    placed_at   TIMESTAMPTZ NOT NULL,
    lifted_by   TEXT        NOT NULL,
    lifted_at   TIMESTAMPTZ NOT NULL
);
-- A catalog's lifts over a window, the most recent first.
CREATE INDEX governance_lift_by_catalog ON governance_lift (catalog_id, lifted_at);

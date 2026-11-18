package nuna.catalog.iceberg;

import nuna.catalog.iceberg.Outcome.BadRequest;
import nuna.catalog.iceberg.Outcome.NewMetadata;
import nuna.catalog.iceberg.Outcome.NoChange;
import nuna.catalog.iceberg.Outcome.NotFound;
import nuna.catalog.iceberg.Outcome.RequirementFailed;
import java.util.List;

/**
 * One golden case: a request in {@code golden/<name>.request.json}, applied to the metadata that case
 * {@code base} produced (none for a create), with the outcome iceberg-core's reference handlers give it.
 * Bases come before the cases that use them.
 */
record GoldenCase(String name, Kind kind, String base, Class<? extends Outcome> expected, String uuid) {

    enum Kind { CREATE_TABLE, COMMIT_TABLE, CREATE_VIEW, COMMIT_VIEW, REGISTER_VIEW }

    static final String V1_UUID = "a1000000-0000-4000-8000-000000000001";
    static final String V2_UUID = "a2000000-0000-4000-8000-000000000002";
    static final String V3_UUID = "a3000000-0000-4000-8000-000000000003";
    static final String VIEW_UUID = "b1000000-0000-4000-8000-000000000001";

    static final List<GoldenCase> ALL = List.of(
            createTable("v3-create", V3_UUID),
            commit("v3-append", "v3-create"),
            commit("v3-append-2", "v3-append"),
            commit("v3-evolve-schema", "v3-append-2"),
            commit("v3-evolve-spec", "v3-evolve-schema"),
            commit("v3-sort-order", "v3-evolve-spec"),
            commit("v3-properties-location", "v3-sort-order"),
            commit("v3-statistics", "v3-properties-location"),
            commit("v3-refs", "v3-statistics"),
            commit("v3-drop-ref-stats", "v3-refs"),
            commit("v3-encryption", "v3-drop-ref-stats"),
            commit("v3-expire", "v3-encryption"),
            commit("v3-cleanup", "v3-expire"),
            commit("v3-rollback", "v3-append-2"),
            commit("v3-no-op", "v3-append-2", NoChange.class),
            commit("v3-geo-unknown", "v3-append-2"),
            createTable("v2-create", V2_UUID),
            commit("v2-append", "v2-create"),
            commit("v2-upgrade-v3", "v2-append"),
            commit("v2-stale-sequence", "v2-append", RequirementFailed.class),
            createTable("v1-create", V1_UUID),
            commit("v1-append", "v1-create"),
            commit("v1-upgrade-v2", "v1-append"),
            commit("v3-create-transaction", null),
            commit("fail-assert-create", "v3-create", RequirementFailed.class),
            commit("fail-table-uuid", "v3-create", RequirementFailed.class),
            commit("fail-ref-snapshot-id", "v3-append", RequirementFailed.class),
            commit("fail-ref-created-concurrently", "v3-append", RequirementFailed.class),
            commit("fail-last-assigned-field-id", "v3-create", RequirementFailed.class),
            commit("fail-current-schema-id", "v3-create", RequirementFailed.class),
            commit("fail-last-assigned-partition-id", "v3-create", RequirementFailed.class),
            commit("fail-default-spec-id", "v3-create", RequirementFailed.class),
            commit("fail-default-sort-order-id", "v3-create", RequirementFailed.class),
            commit("v3-stale-first-row-id", "v3-append", RequirementFailed.class),
            commit("not-found", null, NotFound.class),
            commit("bad-unknown-schema", "v3-create", BadRequest.class),
            commit("bad-unknown-required", "v3-create", BadRequest.class),
            new GoldenCase("view-create", Kind.CREATE_VIEW, null, NewMetadata.class, VIEW_UUID),
            commitView("view-replace", "view-create", NewMetadata.class),
            commitView("view-rollback", "view-replace", NewMetadata.class),
            commitView("view-no-op", "view-create", NoChange.class),
            commitView("fail-view-uuid", "view-create", RequirementFailed.class),
            // A registered view's metadata is its file as it is: the base, byte for byte (D13).
            new GoldenCase("view-register", Kind.REGISTER_VIEW, "view-replace", NewMetadata.class, null));

    static GoldenCase named(String name) {
        return ALL.stream().filter(c -> c.name.equals(name)).findFirst().orElseThrow();
    }

    boolean expectsMetadata() {
        return expected == NewMetadata.class;
    }

    boolean isView() {
        return kind == Kind.CREATE_VIEW || kind == Kind.COMMIT_VIEW || kind == Kind.REGISTER_VIEW;
    }

    @Override
    public String toString() {
        return name;
    }

    private static GoldenCase createTable(String name, String uuid) {
        return new GoldenCase(name, Kind.CREATE_TABLE, null, NewMetadata.class, uuid);
    }

    private static GoldenCase commit(String name, String base) {
        return commit(name, base, NewMetadata.class);
    }

    private static GoldenCase commit(String name, String base, Class<? extends Outcome> expected) {
        return new GoldenCase(name, Kind.COMMIT_TABLE, base, expected, null);
    }

    private static GoldenCase commitView(String name, String base, Class<? extends Outcome> expected) {
        return new GoldenCase(name, Kind.COMMIT_VIEW, base, expected, null);
    }
}

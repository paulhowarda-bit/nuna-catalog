package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which journaled commits changed a table's rows (backlog 330): appends, overwrites and deletes, merges, main moved to
 * another snapshot and registrations; not compaction, expiry, a create or a change that adds no snapshot.
 */
class DataChangesTest {

    private static boolean changes(String updates) {
        return DataChanges.changesData(Management.JSON.readTree(updates));
    }

    private static String added(String operation) {
        return "[{\"action\":\"add-snapshot\",\"snapshot\":{\"snapshot-id\":7,\"summary\":{\"operation\":\"" + operation
                + "\"}}},{\"action\":\"set-snapshot-ref\",\"ref-name\":\"main\",\"snapshot-id\":7,\"type\":\"branch\"}]";
    }

    @Test
    void aSnapshotThatAppendsOverwritesOrDeletesRowsChangesData() {
        assertThat(changes(added("append"))).isTrue();
        assertThat(changes(added("overwrite"))).isTrue();
        assertThat(changes(added("delete"))).isTrue();
        assertThat(changes("[{\"action\":\"add-snapshot\",\"snapshot\":{\"snapshot-id\":7}}]"))
                .as("a snapshot that names no operation is not proved to keep the rows").isTrue();
    }

    @Test
    void compactionKeepsTheRows() {
        assertThat(changes(added("replace"))).isFalse();
    }

    @Test
    void aCommitThatAddsNoSnapshotChangesNoData() {
        assertThat(changes("[{\"action\":\"remove-snapshots\",\"snapshot-ids\":[1,2]}]")).as("an expiry").isFalse();
        assertThat(changes("[{\"action\":\"set-properties\",\"updates\":{\"k\":\"v\"}}]")).isFalse();
        assertThat(changes("[{\"action\":\"add-schema\",\"schema\":{}},{\"action\":\"set-current-schema\","
                + "\"schema-id\":-1}]")).isFalse();
        assertThat(changes("[{\"action\":\"set-snapshot-ref\",\"ref-name\":\"audit\",\"snapshot-id\":3,"
                + "\"type\":\"tag\"}]")).as("a tag made").isFalse();
        assertThat(changes("[]")).isFalse();
    }

    @Test
    void mainMovedToAnotherSnapshotOrABranchMergedChangesData() {
        assertThat(changes("[{\"action\":\"set-snapshot-ref\",\"ref-name\":\"main\",\"snapshot-id\":3,"
                + "\"type\":\"branch\"}]")).as("a rollback").isTrue();
        assertThat(changes("[{\"action\":\"merge-branch\",\"branch\":\"dev\",\"branch-version\":3}]")).isTrue();
    }

    @Test
    void aRegistrationChangesDataAndACreateDoesNot() {
        assertThat(changes("{\"name\":\"t\",\"metadata-location\":\"s3://b/t/metadata/1.metadata.json\"}")).isTrue();
        assertThat(changes("{\"name\":\"t\",\"schema\":{}}")).isFalse();
    }
}

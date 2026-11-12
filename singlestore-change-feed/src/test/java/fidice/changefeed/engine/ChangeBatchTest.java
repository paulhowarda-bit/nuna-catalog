package fidice.changefeed.engine;

import static fidice.changefeed.engine.Fixtures.delete;
import static fidice.changefeed.engine.Fixtures.insert;
import static fidice.changefeed.engine.Fixtures.orders;
import static fidice.changefeed.engine.Fixtures.row;
import static fidice.changefeed.engine.Fixtures.transaction;
import static fidice.changefeed.engine.Fixtures.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fidice.changefeed.engine.ChangeBatch.Change;
import fidice.changefeed.engine.ObserveReader.Event;
import fidice.changefeed.engine.ObserveReader.Type;
import org.junit.jupiter.api.Test;

class ChangeBatchTest {

    @Test
    void takesInATransactionOnlyWhenItCommits() {
        ChangeBatch batch = new ChangeBatch(orders(2), new String[] {"a0", "b0"});
        batch.accept(new Event(Type.BEGIN_TRANSACTION, 1, "b1", null));
        batch.accept(insert(1, row(1, "SAP", 1, "1.000000", "NEW")));

        assertThat(batch.isEmpty()).isTrue();
        assertThat(batch.offsets()).containsExactly("a0", "b0");

        batch.accept(new Event(Type.COMMIT_TRANSACTION, 1, "b1", null));

        assertThat(batch.size()).isEqualTo(1);
        assertThat(batch.offsets()).containsExactly("a0", "b1");
    }

    @Test
    void keepsEachKeysLatestStateAndWhetherEarlierRowsGo() {
        ChangeBatch batch = new ChangeBatch(orders(1), new String[] {"a0"});
        transaction(batch, 0, "a1",
                insert(0, row(1, "SAP", 1, "1.000000", "NEW")),
                update(0, row(1, "SAP", 2, "1.000000", "NEW")),
                update(0, row(2, "SIE", 5, "2.000000", "FILLED")),
                delete(0, 3),
                delete(0, 4),
                insert(0, row(4, "DTE", 7, "3.000000", "NEW")));

        assertThat(batch.changes()).extracting(c -> c.values()[0], Change::deleted, Change::replacesEarlier).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, false, false),
                org.assertj.core.groups.Tuple.tuple(2L, false, true),
                org.assertj.core.groups.Tuple.tuple(3L, true, true),
                org.assertj.core.groups.Tuple.tuple(4L, false, true));
        assertThat(batch.changes()).filteredOn(c -> (Long) c.values()[0] == 1L).singleElement()
                .satisfies(c -> assertThat(c.values()[2]).isEqualTo(2));
    }

    @Test
    void offsetsCarryOnAfterTheBatchIsCleared() {
        ChangeBatch batch = new ChangeBatch(orders(2), new String[] {null, null});
        batch.startAt(0, "a5");
        batch.startAt(1, "b5");
        transaction(batch, 0, "a6", insert(0, row(1, "SAP", 1, "1.000000", "NEW")));
        batch.clear();

        assertThat(batch.isEmpty()).isTrue();
        assertThat(batch.offsets()).containsExactly("a6", "b5");
    }

    @Test
    void snapshotEventsAreNotTransactionEvents() {
        ChangeBatch batch = new ChangeBatch(orders(1), new String[] {"a0"});
        assertThatThrownBy(() -> batch.accept(new Event(Type.BEGIN_SNAPSHOT, 0, "a0", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

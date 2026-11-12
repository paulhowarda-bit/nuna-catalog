package fidice.changefeed.engine;

import fidice.changefeed.engine.ObserveReader.Event;
import fidice.changefeed.engine.ObserveReader.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The changes collected since the last commit: for each key touched, its latest row (or that it was deleted), and
 * the offset of the last transaction taken in from each partition.
 *
 * <p>A transaction is taken in only when its {@code CommitTransaction} arrives, so a batch never holds part of one
 * partition's transaction. A transaction that spans partitions arrives on each partition separately (with the same
 * transaction id) and SingleStore does not say how many partitions it touched, so its parts can land in two
 * consecutive Iceberg commits.
 */
final class ChangeBatch {

    /**
     * The latest state of a key in this batch: the last event's values (a delete's carry only the key columns),
     * whether that event was a delete, and whether rows committed earlier must be removed. A batch whose first event
     * for a key is an insert needs no removal: SingleStore's primary key means no row with that key existed, and any
     * earlier one was removed by an earlier commit.
     */
    record Change(Object[] values, boolean deleted, boolean replacesEarlier) {}

    private final int[] keyPositions;
    private final List<List<Event>> open;
    private final String[] offsets;
    private final Map<List<Object>, Change> changes = new LinkedHashMap<>();

    /** A batch starting after the given offsets, one per partition. */
    ChangeBatch(SourceTable table, String[] after) {
        keyPositions = table.primaryKey().stream().mapToInt(name -> {
            for (int i = 0; i < table.columns().size(); i++) {
                if (table.columns().get(i).name().equals(name)) {
                    return i;
                }
            }
            throw new IllegalStateException("primary key column " + name + " is not a column");
        }).toArray();
        open = new ArrayList<>();
        for (int p = 0; p < table.partitions(); p++) {
            open.add(new ArrayList<>());
        }
        offsets = after.clone();
    }

    /** Takes in a transaction event; snapshot events are the task's. */
    void accept(Event event) {
        List<Event> pending = open.get(event.partition());
        switch (event.type()) {
            case BEGIN_TRANSACTION -> pending.clear();
            case INSERT, UPDATE, DELETE -> pending.add(event);
            case COMMIT_TRANSACTION -> {
                pending.forEach(this::apply);
                pending.clear();
                offsets[event.partition()] = event.offset();
            }
            default -> throw new IllegalArgumentException("not a transaction event: " + event.type());
        }
    }

    /** Sets a partition's offset where its snapshot ended, before any of its transactions are taken in. */
    void startAt(int partition, String offset) {
        offsets[partition] = offset;
    }

    private void apply(Event event) {
        List<Object> key = new ArrayList<>(keyPositions.length);
        for (int position : keyPositions) {
            key.add(event.values()[position]);
        }
        Change earlier = changes.get(key);
        boolean replacesEarlier = earlier != null ? earlier.replacesEarlier() : event.type() != Type.INSERT;
        changes.put(key, new Change(event.values(), event.type() == Type.DELETE, replacesEarlier));
    }

    Collection<Change> changes() {
        return changes.values();
    }

    boolean isEmpty() {
        return changes.isEmpty();
    }

    /** Keys changed in the batch. */
    int size() {
        return changes.size();
    }

    String[] offsets() {
        return offsets.clone();
    }

    /** Empties the batch after a commit; offsets carry on from where it ended. */
    void clear() {
        changes.clear();
    }

    @Override
    public String toString() {
        return changes.size() + " keys, offsets " + Arrays.toString(offsets);
    }
}

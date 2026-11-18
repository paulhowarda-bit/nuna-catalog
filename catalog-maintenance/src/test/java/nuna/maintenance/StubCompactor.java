package nuna.maintenance;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A compactor that runs nothing: it records each Job the worker starts, once per table a night as Kubernetes would,
 * and answers each Job's state as the test sets it ({@link #finish}), {@link #state} until then.
 */
final class StubCompactor implements Compactor {

    /** A Job the worker started. */
    record Launched(Management.TableWork table, CompactionNeed need, CleanupWindow.Night night) {
    }

    final List<Launched> launched = new CopyOnWriteArrayList<>();
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    /** The state of a Job the test has not finished. */
    volatile State state;

    /** Jobs that succeed at once, freeing their slot for the next. */
    StubCompactor() {
        this(State.SUCCEEDED);
    }

    StubCompactor(State state) {
        this.state = state;
    }

    @Override
    public Launch launch(Management.TableWork table, CompactionNeed need, CleanupWindow.Night night) {
        if (launched.stream().anyMatch(job -> job.table().id().equals(table.id()) && job.night().equals(night))) {
            return Launch.EXISTS;
        }
        launched.add(new Launched(table, need, night));
        return Launch.STARTED;
    }

    @Override
    public State state(Management.TableWork table, CleanupWindow.Night night) {
        return states.getOrDefault(table.id(), state);
    }

    /** Sets the state of the table's Job. */
    void finish(UUID table, State state) {
        states.put(table, state);
    }

    /** How many Jobs were started for the table. */
    long launches(UUID table) {
        return launched.stream().filter(job -> job.table().id().equals(table)).count();
    }

    /** The tables Jobs were started for, in order. */
    List<UUID> order() {
        return launched.stream().map(job -> job.table().id()).toList();
    }
}

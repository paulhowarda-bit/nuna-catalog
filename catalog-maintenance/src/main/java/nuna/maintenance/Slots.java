package nuna.maintenance;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The compaction Jobs that may run at once ({@code nuna.maintenance.compaction.max-jobs}), numbered from 1, shared by
 * the night's Jobs and the requested ones (backlog 114), each held by the table its Job compacts: no table has two at
 * once.
 */
final class Slots {

    private final int max;
    private final Map<Integer, UUID> taken = new HashMap<>();

    Slots(int max) {
        this.max = max;
    }

    /** Whether a slot is free. */
    boolean free() {
        return taken.size() < max;
    }

    /** Whether a Job of the table holds a slot. */
    boolean holds(UUID table) {
        return taken.containsValue(table);
    }

    /** Takes the lowest free slot for a Job of the table; the caller has seen one {@link #free}. */
    int take(UUID table) {
        int slot = 1;
        while (taken.containsKey(slot)) {
            slot++;
        }
        taken.put(slot, table);
        return slot;
    }

    void release(int slot) {
        taken.remove(slot);
    }
}

package nuna.maintenance;

/**
 * Starts compaction of one table somewhere else (D30 (3)), once a night at most, and says how it went; the worker
 * does not wait for it.
 */
interface Compactor {

    /** Compaction is off: no image is configured. */
    Compactor NONE = new Compactor() {
        @Override
        public Launch launch(Management.TableWork table, CompactionNeed need, CleanupWindow.Night night) {
            return Launch.OFF;
        }

        @Override
        public State state(Management.TableWork table, CleanupWindow.Night night) {
            return State.SUCCEEDED;
        }
    };

    /** What came of asking. */
    enum Launch {
        /** A compaction of the table was started. */
        STARTED,
        /** Tonight's compaction of the table was started already, by this worker or one before a restart. */
        EXISTS,
        /** Compaction is not configured. */
        OFF
    }

    /** Where tonight's compaction of a table stands. */
    enum State {
        RUNNING,
        SUCCEEDED,
        FAILED,
        /** Stopped at its deadline, the window's end. */
        CUT_OFF
    }

    /**
     * Starts tonight's compaction of the table for this need, to run no later than the night's end; once a night:
     * asked again, it answers {@link Launch#EXISTS}.
     */
    Launch launch(Management.TableWork table, CompactionNeed need, CleanupWindow.Night night);

    /** Where the compaction it started tonight stands; one long gone counts as succeeded. */
    State state(Management.TableWork table, CleanupWindow.Night night);
}

package keel.maintenance;

/** Starts compaction of one table somewhere else (D30 (3)); the worker does not wait for it. */
interface Compactor {

    /** Compaction is off: no image is configured. */
    Compactor NONE = table -> Launch.OFF;

    /** What came of asking. */
    enum Launch {
        /** A compaction of the table was started. */
        STARTED,
        /** One is running, or finished less than the compaction interval ago. */
        RUNNING,
        /** Compaction is not configured. */
        OFF
    }

    Launch launch(Management.TableWork table);
}

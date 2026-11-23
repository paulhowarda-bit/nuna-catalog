package nuna.maintenance;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Starts compaction of one table somewhere else (D30 (3)), once a night at most and once a request (backlog 114), and
 * says how it went; the worker does not wait for it.
 */
interface Compactor {

    /** Compaction is off: no image is configured. */
    Compactor NONE = new Compactor() {
        @Override
        public Launch launch(Management.TableWork table, CompactionNeed need, Run run) {
            return Launch.OFF;
        }

        @Override
        public State state(Management.TableWork table, Run run) {
            return State.SUCCEEDED;
        }
    };

    /** What came of asking. */
    enum Launch {
        /** A compaction of the table was started. */
        STARTED,
        /** This run's compaction of the table was started already, by this worker or one before a restart. */
        EXISTS,
        /** Compaction is not configured. */
        OFF
    }

    /** Where a run's compaction of a table stands. */
    enum State {
        RUNNING,
        SUCCEEDED,
        FAILED,
        /** Stopped at its deadline. */
        CUT_OFF
    }

    /**
     * One compaction of a table: the key that names it, so it starts once whichever worker asks, and its deadline. A
     * night's is keyed by the night's date and stops at the window's end; a requested one (backlog 114) is keyed by
     * {@code r} and the time it was asked, in milliseconds in base 36, and stops at the request's deadline.
     */
    record Run(String key, Instant deadline) {

        static Run of(CleanupWindow.Night night) {
            return new Run(night.date().format(DateTimeFormatter.BASIC_ISO_DATE), night.closes());
        }

        static Run requested(Instant requestedAt, Instant deadline) {
            return new Run("r" + Long.toString(requestedAt.toEpochMilli(), Character.MAX_RADIX), deadline);
        }
    }

    /**
     * Starts this run's compaction of the table for this need, to stop at the run's deadline at the latest; once a run:
     * asked again, it answers {@link Launch#EXISTS}.
     */
    Launch launch(Management.TableWork table, CompactionNeed need, Run run);

    /** Where the compaction it started for this run stands; one long gone counts as succeeded. */
    State state(Management.TableWork table, Run run);

    /** Starts tonight's compaction of the table, to run no later than the night's end. */
    default Launch launch(Management.TableWork table, CompactionNeed need, CleanupWindow.Night night) {
        return launch(table, need, Run.of(night));
    }

    /** Where tonight's compaction of the table stands. */
    default State state(Management.TableWork table, CleanupWindow.Night night) {
        return state(table, Run.of(night));
    }
}

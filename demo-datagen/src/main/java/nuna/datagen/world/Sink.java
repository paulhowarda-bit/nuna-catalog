package nuna.datagen.world;

import java.time.LocalDate;

/** Where the world's events go, in business-time order. */
public interface Sink {

    void accept(Event event);

    /** Called once a business day's last event has been accepted. */
    default void endOfDay(LocalDate businessDate) {}

    /** Called after {@link #endOfDay} of a month's last business day, with what the month-end close publishes. */
    default void monthEnd(MonthEnd close) {}

    /** Called when a month already closed is corrected (a trade busted after the close), in business-time order. */
    default void restated(MonthEnd.Restatement restatement) {}
}

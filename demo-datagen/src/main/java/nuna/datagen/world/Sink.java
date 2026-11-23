package nuna.datagen.world;

import java.time.LocalDate;

/** Where the world's events go, in business-time order. */
public interface Sink {

    void accept(Event event);

    /** Called once a business day's last event has been accepted. */
    default void endOfDay(LocalDate businessDate) {}
}

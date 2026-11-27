package nuna.datagen.world;

import java.time.Instant;
import java.util.Map;

/** Where the world's order management system and clearing send their rows, at their business time. */
interface Emitter {
    void emit(Instant at, Feed feed, Map<String, Object> row);
}

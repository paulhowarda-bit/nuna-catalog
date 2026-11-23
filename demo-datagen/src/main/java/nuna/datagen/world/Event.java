package nuna.datagen.world;

import java.time.Instant;
import java.util.Map;

/**
 * One row the world produces, at its business time: a Kafka record for a Kafka feed, an upsert for SingleStore's
 * orders. Values are Long, Integer, BigDecimal, String, Boolean, LocalDate, Instant or a list of maps.
 */
public record Event(Instant at, Feed feed, Map<String, Object> row) {}

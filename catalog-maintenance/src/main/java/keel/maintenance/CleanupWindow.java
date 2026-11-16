package keel.maintenance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * The nightly cleanup window (D30, amended 2026-11-15): compaction and orphan scans start only inside it, and a
 * compaction Job runs no later than its end. One per worker deployment, as a start, an end and a named time zone, so
 * it follows the zone's daylight-saving changes.
 *
 * <p>An end before the start crosses midnight: 22:00 to 04:00 opens on one date and closes on the next. An end equal
 * to the start makes the window the whole day, each opening closing as the next opens. A start or end the clocks skip
 * (spring forward) falls as late as the skip, at the same instant the clock would have read it; one the clocks repeat
 * (fall back) is the first time the clock reads it, so the window never opens or closes twice in one night.
 *
 * @param start when it opens, local time in {@code zone}
 * @param end   when it closes, local time in {@code zone}
 * @param zone  a named zone, e.g. {@code Europe/London}
 */
public record CleanupWindow(LocalTime start, LocalTime end, ZoneId zone) {

    public CleanupWindow {
        Objects.requireNonNull(start, "keel.maintenance.cleanup-window.start: when the nightly window opens, e.g."
                + " \"01:00\"");
        Objects.requireNonNull(end, "keel.maintenance.cleanup-window.end: when the nightly window closes, e.g."
                + " \"05:00\"");
        Objects.requireNonNull(zone, "keel.maintenance.cleanup-window.zone: the window's time zone by name, e.g."
                + " Europe/London");
    }

    /**
     * One opening of the window.
     *
     * @param date   the date it opens on, in the window's zone; it names the night
     * @param opens  when it opens
     * @param closes when it closes: nothing heavy starts from then, and each compaction Job's deadline
     */
    public record Night(LocalDate date, Instant opens, Instant closes) {

        boolean contains(Instant instant) {
            return !instant.isBefore(opens) && instant.isBefore(closes);
        }
    }

    /** The opening that holds {@code instant}, or none when the window is closed then. */
    Optional<Night> night(Instant instant) {
        LocalDate today = LocalDate.ofInstant(instant, zone);
        // An opening that crosses midnight began the day before.
        for (LocalDate date : new LocalDate[] {today.minusDays(1), today}) {
            Night night = on(date);
            if (night.contains(instant)) {
                return Optional.of(night);
            }
        }
        return Optional.empty();
    }

    /** The opening that begins on {@code date}. */
    Night on(LocalDate date) {
        Instant opens = ZonedDateTime.of(date, start, zone).toInstant();
        Instant closes = ZonedDateTime.of(end.isAfter(start) ? date : date.plusDays(1), end, zone).toInstant();
        return new Night(date, opens, closes);
    }

    @Override
    public String toString() {
        return start + " to " + end + " " + zone;
    }
}

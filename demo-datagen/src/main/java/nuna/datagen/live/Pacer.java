package nuna.datagen.live;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import nuna.datagen.world.World;

/**
 * Holds each event back until its business time comes round on a compressed clock: a business day's 08:00 to 17:00 in
 * {@code dayLength} of wall time. Days before {@code pacedFrom} (the catch-up) and the gap between one day's close and
 * the next day's first event are not paced. Each day is anchored at its first paced event, so a restart mid-day carries
 * on at the same rate.
 */
public final class Pacer {

    private static final Duration BUSINESS_DAY = Duration.between(LocalTime.of(8, 0), LocalTime.of(17, 0));

    private final LocalDate pacedFrom;
    private final double speed;
    private LocalDate anchorDay;
    private Instant anchorBusiness;
    private long anchorWall;

    public Pacer(LocalDate pacedFrom, Duration dayLength) {
        this.pacedFrom = pacedFrom;
        this.speed = (double) BUSINESS_DAY.toNanos() / dayLength.toNanos();
    }

    /** Business seconds per wall second. */
    public double speed() {
        return speed;
    }

    public void await(Instant at) throws InterruptedException {
        LocalDate day = at.atZone(World.NEW_YORK).toLocalDate();
        if (day.isBefore(pacedFrom)) {
            return;
        }
        if (!day.equals(anchorDay)) {
            anchorDay = day;
            anchorBusiness = at;
            anchorWall = System.nanoTime();
            return;
        }
        long due = anchorWall + (long) (Duration.between(anchorBusiness, at).toNanos() / speed);
        long wait = due - System.nanoTime();
        if (wait > 0) {
            Thread.sleep(Duration.ofNanos(wait));
        }
    }
}

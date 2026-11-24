package nuna.datagen.live;

import nuna.datagen.world.Feed;

/**
 * Which of Kafka and SingleStore the generator writes to, from the demo's switches (D50, amended 2026-11-24):
 * {@code BROKERAGE_ROUTE} is {@code both}, {@code kafka} (SingleStore is off, so the orders go through Kafka) or
 * {@code singlestore} (Kafka is off, so every other feed goes through SingleStore).
 */
public enum Route {
    BOTH, KAFKA, SINGLESTORE;

    public static Route of(String name) {
        return switch (name.strip()) {
            case "both" -> BOTH;
            case "kafka" -> KAFKA;
            case "singlestore" -> SINGLESTORE;
            default -> throw new IllegalArgumentException("BROKERAGE_ROUTE is " + name
                + "; it takes both, kafka or singlestore");
        };
    }

    /** The path a feed takes on this route. */
    public Feed.Path pathOf(Feed feed) {
        return switch (this) {
            case BOTH -> feed.path;
            case KAFKA -> Feed.Path.KAFKA;
            case SINGLESTORE -> Feed.Path.SINGLESTORE;
        };
    }

    public boolean kafka() {
        return this != SINGLESTORE;
    }

    public boolean singlestore() {
        return this != KAFKA;
    }

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}

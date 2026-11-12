package keel.catalog.store;

import java.util.Objects;

/**
 * Who made a commit and what they asked for, journaled with every pointer swap (D5).
 *
 * @param principal        the normalized principal that called
 * @param endUser          the end user a trusted engine asserted, or null (D17)
 * @param engine           the calling engine, or null when unknown
 * @param requirementsJson the client's requirements, as JSON
 * @param updatesJson      the client's updates, as JSON
 */
public record JournalEntry(String principal, String endUser, String engine,
                           String requirementsJson, String updatesJson) {

    public JournalEntry {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(requirementsJson, "requirementsJson");
        Objects.requireNonNull(updatesJson, "updatesJson");
    }
}

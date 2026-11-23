package nuna.catalog.store;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A request to run one of a table's heavy maintenance jobs now, outside the nightly cleanup window too (D30, amended
 * 2026-11-20; backlog 114). One per table and job: it waits until the maintenance worker reports the job started, and
 * a request made after that replaces it.
 *
 * @param objectId    the table
 * @param job         which job
 * @param requestedBy who asked, as audit records name the principal
 * @param requestedAt when, by the catalog's clock; with the table and job it names the request
 * @param startedAt   when the worker reported the job started; empty while the request waits
 */
public record MaintenanceRequest(UUID objectId, Job job, String requestedBy, Instant requestedAt,
                                 Optional<Instant> startedAt) {

    /** The jobs a request may ask for: the two the cleanup window holds back. */
    public enum Job {
        COMPACTION,
        ORPHAN_SCAN
    }

    public MaintenanceRequest {
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(startedAt, "startedAt");
    }

    /** Whether the worker has yet to report the job started. */
    public boolean waiting() {
        return startedAt.isEmpty();
    }

    /** This request's place in the waiting order: by time asked, then table, then job. */
    public String token() {
        return PageToken.encode(requestedAt.toString(), objectId.toString(), job.name());
    }

    /** The time, table and job a token names. */
    public static List<Object> after(List<String> parts) {
        return List.of(Instant.parse(parts.get(0)), UUID.fromString(parts.get(1)), Job.valueOf(parts.get(2)).name());
    }
}

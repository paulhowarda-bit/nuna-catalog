package nuna.catalog.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Where an audit record was written to the log {@code nuna.audit} (D23, amended 2026-11-25; backlog 262): the server
 * run that wrote it and its number in that run. A run is one start of one catalog server; its records are numbered 1,
 * 2, 3 and so on as they are written, reads and changes alike, so a number missing from the archive is a record the
 * log path lost. A change the outbox relay writes twice is two log records, with two numbers and one event id.
 *
 * <p>It travels in the CloudEvent envelope as the extension attributes {@code nunarun} and {@code nunaseq}, the number
 * as a string since CloudEvents integers are 32 bits. The event as the outbox holds it carries none: the number is
 * given where the record is logged.
 *
 * @param run      the server run, made once when the server starts
 * @param sequence the record's number in the run, from 1
 */
public record LogStamp(UUID run, long sequence) {

    public LogStamp {
        Objects.requireNonNull(run, "run");
        if (sequence < 1) {
            throw new IllegalArgumentException("a run numbers its records from 1, not " + sequence);
        }
    }
}

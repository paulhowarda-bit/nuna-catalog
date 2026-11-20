package nuna.catalog.audit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One audit record as a row of the queryable audit table {@code events} (D23, amended 2026-11-20): the envelope's
 * attributes, who made the request, what it was about, the type's operation, privilege and reason where it has them,
 * the whole payload as JSON, and the record's place in the audit topic, which ties the row to its archive chain.
 * Plain Java values: the Iceberg side lives in {@code catalog-audit-tables}, not here (D2).
 *
 * @param eventId        the CloudEvents id
 * @param time           when it happened
 * @param type           the CloudEvents type, e.g. {@code nuna.catalog.read}
 * @param source         the deployment that produced it
 * @param principal      the principal that authenticated; null for an authentication failure, which has none
 * @param endUser        the end user a trusted engine asserted, or null
 * @param engine         the calling engine, or null
 * @param requestId      the request's id
 * @param catalog        the name of the catalog the record is about, or null
 * @param objectKind     {@code CATALOG}, {@code NAMESPACE}, {@code TABLE} or {@code VIEW}, or null
 * @param objectId       the object's stable id, or null
 * @param objectPath     namespace levels, then the table or view name, as given; null when there is no object
 * @param operation      the operation refused or exempted, where the type names one
 * @param privilege      the privilege granted, revoked or missing, where the type names one
 * @param reason         why: a denial's, an authentication failure's or a hold's reason
 * @param data           the payload, as the topic carries it under {@code data}
 * @param topicPartition the audit topic's partition the record came from: its archive chain
 * @param topicOffset    its offset there
 */
public record AuditRow(String eventId, Instant time, String type, String source, String principal, String endUser,
                       String engine, String requestId, String catalog, String objectKind, String objectId,
                       List<String> objectPath, String operation, String privilege, String reason, String data,
                       int topicPartition, long topicOffset) {

    public AuditRow {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(data, "data");
        objectPath = objectPath == null ? null : List.copyOf(objectPath);
    }
}

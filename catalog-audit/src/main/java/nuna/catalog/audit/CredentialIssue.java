package nuna.catalog.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * Storage credentials handed to a caller (D21). The credentials themselves are never recorded.
 * This event is published before the response is sent; if it cannot be published the
 * credentials are not handed out (D23).
 *
 * @param object         the table whose prefix the credentials cover
 * @param storageProfile the storage profile they came from
 * @param locationPrefix the prefix the session policy allows
 * @param access         read only, or read and write; {@code READ_WRITE_DELETE} names what maintenance
 *                       principals were vended before D30 (amended 2026-11-12), and is never vended now
 * @param sessionName    the STS session name, which carries the principal and request id
 * @param expiresAt      when the credentials expire
 * @param actor          who received them
 */
public record CredentialIssue(Securable object, String storageProfile, String locationPrefix, Access access,
                              String sessionName, Instant expiresAt, Actor actor) implements EventData {

    public enum Access { READ, READ_WRITE, READ_WRITE_DELETE }

    public CredentialIssue {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(storageProfile, "storageProfile");
        Objects.requireNonNull(locationPrefix, "locationPrefix");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(sessionName, "sessionName");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.CREDENTIAL_ISSUE;
    }

    @Override
    public String subject() {
        return object.id().toString();
    }
}

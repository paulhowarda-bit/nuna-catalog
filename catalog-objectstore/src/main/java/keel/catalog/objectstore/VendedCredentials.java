package keel.catalog.objectstore;

import java.time.Instant;
import java.util.Objects;

/**
 * Temporary credentials for one table's prefix. {@link #toString()} leaves out the secret and the token.
 *
 * @param location    the table location the session policy covers
 * @param access      what they may do there
 * @param sessionName the STS session name, which names the principal and request (D21)
 * @param expiresAt   when STS says they expire
 */
public record VendedCredentials(String accessKeyId, String secretAccessKey, String sessionToken, Instant expiresAt,
                                String location, Access access, String sessionName) {

    public VendedCredentials {
        Objects.requireNonNull(accessKeyId, "accessKeyId");
        Objects.requireNonNull(secretAccessKey, "secretAccessKey");
        Objects.requireNonNull(sessionToken, "sessionToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(sessionName, "sessionName");
    }

    @Override
    public String toString() {
        return "VendedCredentials[accessKeyId=" + accessKeyId + ", expiresAt=" + expiresAt + ", location=" + location
                + ", access=" + access + ", sessionName=" + sessionName + "]";
    }
}

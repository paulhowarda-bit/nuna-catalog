package fidice.catalog.server;

import fidice.catalog.iceberg.Wire;

/**
 * How every route and filter answers when the catalog store cannot be reached and nothing was applied:
 * 503 ServiceUnavailableException with Retry-After. The spec lets a client repeat even a non-idempotent
 * request when Retry-After is present; a commit whose outcome is unknown is never answered this way.
 */
public final class StoreOutage {

    public static final int STATUS = 503;
    /** Seconds to wait before sending the request again: about one database failover. */
    public static final String RETRY_AFTER_SECONDS = "2";

    private StoreOutage() {
    }

    public static String body(String message) {
        return Wire.error(STATUS, "ServiceUnavailableException", message);
    }
}

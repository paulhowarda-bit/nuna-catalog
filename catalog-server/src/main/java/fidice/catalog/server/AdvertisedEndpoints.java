package fidice.catalog.server;

import java.util.List;

/**
 * The endpoints list sent in {@code /v1/config} (D13). It names only routes this server
 * actually serves; {@code AdvertisedEndpointsTest} checks it against the registered routes.
 * {@code /v1/config} itself is never listed.
 *
 * <p>An empty list is not "nothing supported": iceberg-core 1.12.0 omits an empty list from the
 * JSON, and the Java client then assumes a legacy default set of 13 endpoints. M0 serves no
 * other route, so clients currently assume those defaults and get 404s; the list becomes
 * explicit when M4 adds routes.
 */
final class AdvertisedEndpoints {

    static final List<String> ALL = List.of();

    private AdvertisedEndpoints() {
    }
}

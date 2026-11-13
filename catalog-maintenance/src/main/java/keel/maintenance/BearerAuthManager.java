package keel.maintenance;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.iceberg.rest.HTTPHeaders;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.auth.AuthManager;
import org.apache.iceberg.rest.auth.AuthSession;
import org.apache.iceberg.rest.auth.DefaultAuthSession;

/**
 * Signs every request of Iceberg's REST client with the worker's current token ({@code rest.auth.type} names this
 * class). Iceberg builds the manager by name, so the token source is found through the catalog property
 * {@value #SOURCE}, which {@link #register} hands out.
 */
public final class BearerAuthManager implements AuthManager {

    /** The catalog property naming the registered token source. */
    static final String SOURCE = "keel.token-source";

    private static final Map<String, TokenSource> SOURCES = new ConcurrentHashMap<>();

    public BearerAuthManager(String name) {
    }

    /** Makes a token source known to the managers Iceberg builds; returns the value of {@value #SOURCE}. */
    static String register(TokenSource tokens) {
        String id = UUID.randomUUID().toString();
        SOURCES.put(id, tokens);
        return id;
    }

    static void unregister(String id) {
        SOURCES.remove(id);
    }

    @Override
    public AuthSession catalogSession(RESTClient client, Map<String, String> properties) {
        TokenSource tokens = SOURCES.get(properties.get(SOURCE));
        if (tokens == null) {
            throw new IllegalStateException("no token source " + properties.get(SOURCE) + " is registered");
        }
        return (DefaultAuthSession) () -> HTTPHeaders.of(Map.of("Authorization", "Bearer " + tokens.token()));
    }

    @Override
    public void close() {
    }
}

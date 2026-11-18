package nuna.maintenance;

import java.util.HashMap;
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
 * class), and adds the current trace ({@link TraceHeaders}), so the catalog's spans for the request join the worker's
 * (backlog 49). Iceberg builds the manager by name, so the token source is found through the catalog property
 * {@value #SOURCE}, which {@link #register} hands out.
 */
public final class BearerAuthManager implements AuthManager {

    /** The catalog property naming the registered token source. */
    static final String SOURCE = "nuna.token-source";

    /** A token source and the trace headers to send with it. */
    private record Source(TokenSource tokens, TraceHeaders trace) {
    }

    private static final Map<String, Source> SOURCES = new ConcurrentHashMap<>();

    public BearerAuthManager(String name) {
    }

    /**
     * Makes a token source, and where the current trace comes from, known to the managers Iceberg builds; returns the
     * value of {@value #SOURCE}.
     */
    static String register(TokenSource tokens, TraceHeaders trace) {
        String id = UUID.randomUUID().toString();
        SOURCES.put(id, new Source(tokens, trace));
        return id;
    }

    static void unregister(String id) {
        SOURCES.remove(id);
    }

    @Override
    public AuthSession catalogSession(RESTClient client, Map<String, String> properties) {
        Source source = SOURCES.get(properties.get(SOURCE));
        if (source == null) {
            throw new IllegalStateException("no token source " + properties.get(SOURCE) + " is registered");
        }
        return (DefaultAuthSession) () -> {
            Map<String, String> headers = new HashMap<>(source.trace().current());
            headers.put("Authorization", "Bearer " + source.tokens().token());
            return HTTPHeaders.of(headers);
        };
    }

    @Override
    public void close() {
    }
}

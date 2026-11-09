package org.apache.iceberg.rest;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * The 1.12.0 test-fixtures {@link RESTCatalogServer} in process, over in-memory SQLite: the reference our
 * responses are diffed against (D31, F1). Its constructor is package-private, hence this package. The
 * caller gives its storage, so it writes to the same object store as our server (the default local
 * warehouse needs Hadoop's native tools on Windows).
 */
public final class ReferenceServer {

    private static URI uri;

    private ReferenceServer() {
    }

    public static synchronized URI start(Map<String, String> catalogProperties) throws Exception {
        if (uri != null) {
            return uri;
        }
        int port = freePort();
        Map<String, String> config = new HashMap<>(catalogProperties);
        config.put(RESTCatalogServer.REST_PORT, String.valueOf(port));
        RESTCatalogServer server = new RESTCatalogServer(config);
        server.start(false);
        uri = URI.create("http://localhost:" + port);
        return uri;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

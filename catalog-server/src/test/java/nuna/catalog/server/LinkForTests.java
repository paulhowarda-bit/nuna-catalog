package nuna.catalog.server;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.testcontainers.containers.Network;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Toxiproxy between the server and its database, as the store tests' {@code OracleFree} and {@code MongoReplicaSet}
 * put it between their stores and theirs: the database joins {@link #network()} under an alias, the server connects
 * to {@link #host()}:{@link #port()}, and {@link #cut()} disables the proxy, which closes every open connection and
 * refuses new ones, until {@link #heal()} resets it. The fixtures themselves are package-private to their store
 * modules' tests, which nothing shares; this is their proxy, made the same way. One per container; Testcontainers
 * removes it at exit.
 */
final class LinkForTests {

    private static final int LINK_PORT = 8666;

    private final Network network = Network.newNetwork();
    private final ToxiproxyContainer toxiproxy;
    private final ToxiproxyClient client;
    private Proxy proxy;

    LinkForTests() {
        toxiproxy = new ToxiproxyContainer(DockerImageName.parse(System.getProperty("toxiproxy.image")))
                .withNetwork(network);
        toxiproxy.start();
        client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
    }

    /** The network the database joins. */
    Network network() {
        return network;
    }

    /** Opens the link to {@code alias}:{@code port} on {@link #network()}; once, after the database starts. */
    void to(String alias, int port) {
        proxy = toxi(() -> client.createProxy("database", "0.0.0.0:" + LINK_PORT, alias + ":" + port));
    }

    String host() {
        return toxiproxy.getHost();
    }

    int port() {
        return toxiproxy.getMappedPort(LINK_PORT);
    }

    void cut() {
        toxi(() -> {
            proxy.disable();
            return null;
        });
    }

    void heal() {
        toxi(() -> {
            client.reset();
            return null;
        });
    }

    private interface ToxiproxyCall<T> {
        T call() throws IOException;
    }

    private static <T> T toxi(ToxiproxyCall<T> call) {
        try {
            return call.call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

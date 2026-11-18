package nuna.catalog.server.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Asking the other servers: each address of each entry is asked with the caller's own token, and a peer that cannot be
 * reached, answers with a refusal, or answers something other than a server's health gives no figures and says why.
 */
class PeersTest {

    static final String ANSWER = """
            {"server":{"id":"%s","name":"%s","started-at":"2027-03-01T00:00:00Z"},
             "from":"2027-03-01T00:00:00Z","to":"2027-03-01T12:00:00Z",
             "figures":{"alerts.metadata-integrity":2,"load":10,"load.le.50":10,
              "vends.failed.ForbiddenException":1}}""";

    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void eachPeerIsAskedWithTheCallersTokenAndSaysWhyWhenItGivesNothing() throws IOException {
        String good = serve(200, ANSWER.formatted("6f1c4d55-29a7-4b6c-9a1e-4a9e1b0c2d3e", "catalog-1"));
        String refusing = serve(403, "{\"error\":{\"message\":\"no\",\"type\":\"ForbiddenException\",\"code\":403}}");
        String odd = serve(200, "{\"server\":{\"id\":\"x\"}}");
        String negative = serve(200, ANSWER.formatted("6f1c4d55-29a7-4b6c-9a1e-4a9e1b0c2d3f", "catalog-2")
                .replace("\"load\":10", "\"load\":-1"));
        String dead = "127.0.0.1:" + freePort();

        try (Peers peers = new Peers(new HealthSettings(List.of(good, refusing, odd, negative, dead,
                "no-such-host.invalid:8181"), Duration.ofSeconds(3)))) {
            List<Peers.Answer> answers = peers.ask("Bearer operator-token");

            assertThat(answers).hasSize(6);
            Peers.Answered answered = (Peers.Answered) answers.stream().filter(a -> a.address().equals(good))
                    .findFirst().orElseThrow();
            assertThat(answered.window().name()).isEqualTo("catalog-1");
            assertThat(answered.window().figures()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "alerts.metadata-integrity", 2.0, "load", 10.0, "load.le.50", 10.0,
                    "vends.failed.ForbiddenException", 1.0));
            assertThat(why(answers, refusing)).isEqualTo("answered 403 ForbiddenException");
            assertThat(why(answers, odd)).isEqualTo("answered something other than a server's health");
            assertThat(why(answers, negative)).isEqualTo("answered something other than a server's health");
            assertThat(why(answers, dead)).isEqualTo("could not connect");
            assertThat(why(answers, "no-such-host.invalid:8181")).isEqualTo("the name did not resolve");
        }
        assertThat(tokens).hasSize(4).containsOnly("Bearer operator-token");
    }

    @Test
    void anEntryIsAHostAndAPort() {
        for (String entry : List.of("catalog-peers.lake.svc", "http://catalog:8181", "catalog:8181/x")) {
            assertThatThrownBy(() -> new HealthSettings(List.of(entry), Duration.ofSeconds(1))).as(entry)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new HealthSettings(List.of("catalog-peers.lake.svc:8181"), Duration.ofSeconds(1)).peers())
                .containsExactly("catalog-peers.lake.svc:8181");
    }

    /** A peer on 127.0.0.1 answering {@code status} and {@code body} to the server route; returns host:port. */
    private String serve(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(CatalogHealthRoutes.SERVER, exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        servers.add(server);
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    private static String why(List<Peers.Answer> answers, String address) {
        return answers.stream().filter(a -> a.address().equals(address)).map(a -> ((Peers.Silent) a).why())
                .findFirst().orElseThrow();
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}

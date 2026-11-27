package nuna.datagen.live;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Restarts the sink's task whenever Kafka Connect reports it failed. Kafka Connect never restarts a failed task by
 * itself, and the Iceberg sink's task fails for good on one refused connection: on 2026-11-23 the catalog restarted
 * twice and each time nothing reached the lake until the task was restarted by hand (backlog 179). A world that runs
 * for days on the real clock needs its sink back without anyone watching.
 *
 * <p>A watch can wait on a gate (item 152): the sink of {@code brokerage.party.beneficial_owners}, which lands
 * unclassified in a regulated namespace, fails while the catalog refuses the table (D27), and restarting it then would
 * only fail it again, each try a denial in the audit log. Its watch restarts it once the table has a classification of
 * its own ({@link #classified}).
 */
public final class SinkWatch {

    static final Duration EVERY = Duration.ofMinutes(1);

    private static final Pattern FAILED = Pattern.compile("\"state\"\s*:\s*\"FAILED\"");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String connector;
    private final URI status;
    private final URI restart;
    private final BooleanSupplier gate;
    private boolean unreachable;
    private boolean waiting;

    SinkWatch(String connectUrl, String connector, BooleanSupplier gate) {
        this.connector = connector;
        this.status = URI.create(connectUrl + "/connectors/" + connector + "/status");
        this.restart = URI.create(connectUrl + "/connectors/" + connector + "/restart?includeTasks=true&onlyFailed=true");
        this.gate = gate;
    }

    /** Watches the connector on a daemon thread, once a minute, for as long as the generator runs. */
    public static void start(String connectUrl, String connector) {
        start(connectUrl, connector, () -> true);
    }

    /** As {@link #start(String, String)}, restarting the connector only while the gate is open. */
    public static void start(String connectUrl, String connector, BooleanSupplier gate) {
        SinkWatch watch = new SinkWatch(connectUrl, connector, gate);
        Thread t = new Thread(() -> {
            while (true) {
                watch.check();
                try {
                    Thread.sleep(EVERY);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "sink-watch-" + connector);
        t.setDaemon(true);
        t.start();
    }

    /** Whether a connector's status (Kafka Connect's GET /connectors/{name}/status) shows it or a task failed. */
    static boolean failed(String status) {
        return FAILED.matcher(status).find();
    }

    /**
     * Whether a table's governance (the catalog's GET .../tables/{table}/governance) shows a classification set on the
     * table itself, not one it inherits: only that opens a table in a regulated namespace (D27).
     */
    static boolean classified(String governance) {
        JsonNode body = JSON.readTree(governance);
        String own = body.path("target").path("kind").asString() + ":" + body.path("target").path("id").asString();
        for (JsonNode a : body.path("attributes")) {
            if (a.path("attribute").asString().equals("classification") && a.path("level").asString().equals(own)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A gate open while a table has a classification of its own, asked of the catalog's management API with the
     * generator's token; closed while the catalog cannot be asked.
     */
    public static BooleanSupplier whenClassified(String catalogUri, String warehouse, String token, String table) {
        int dot = table.lastIndexOf('.');
        URI governance = URI.create(catalogUri + "/management/v1/catalogs/" + warehouse + "/namespaces/"
            + URLEncoder.encode(table.substring(0, dot).replace('.', '\u001f'), StandardCharsets.UTF_8) + "/tables/"
            + table.substring(dot + 1) + "/governance");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return () -> {
            try {
                HttpResponse<String> answer = http.send(HttpRequest.newBuilder(governance)
                    .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
                return answer.statusCode() == 200 && classified(answer.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                return false;
            }
        };
    }

    void check() {
        try {
            HttpResponse<String> answer = http.send(HttpRequest.newBuilder(status).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
            unreachable = false;
            if (answer.statusCode() != 200 || !failed(answer.body())) {
                waiting = false;
                return;
            }
            if (!gate.getAsBoolean()) {
                if (!waiting) {
                    System.out.println("the sink " + connector + " has failed; it is restarted once its table is"
                        + " classified");
                    waiting = true;
                }
                return;
            }
            waiting = false;
            HttpResponse<String> restarted = http.send(HttpRequest.newBuilder(restart).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            System.out.println("WARN the sink " + connector + " had failed; asked Kafka Connect to restart it: HTTP "
                + restarted.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!unreachable) {
                System.out.println("WARN cannot reach Kafka Connect at " + status + ": " + e);
                unreachable = true;
            }
        }
    }
}

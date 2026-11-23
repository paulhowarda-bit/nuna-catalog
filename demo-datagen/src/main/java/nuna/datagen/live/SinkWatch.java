package nuna.datagen.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Restarts the sink's task whenever Kafka Connect reports it failed. Kafka Connect never restarts a failed task by
 * itself, and the Iceberg sink's task fails for good on one refused connection: on 2026-11-23 the catalog restarted
 * twice and each time nothing reached the lake until the task was restarted by hand (backlog 179). A world that runs
 * for days on the real clock needs its sink back without anyone watching.
 */
public final class SinkWatch {

    static final Duration EVERY = Duration.ofMinutes(1);

    private static final Pattern FAILED = Pattern.compile("\"state\"\\s*:\\s*\"FAILED\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String connector;
    private final URI status;
    private final URI restart;
    private boolean unreachable;

    SinkWatch(String connectUrl, String connector) {
        this.connector = connector;
        this.status = URI.create(connectUrl + "/connectors/" + connector + "/status");
        this.restart = URI.create(connectUrl + "/connectors/" + connector + "/restart?includeTasks=true&onlyFailed=true");
    }

    /** Watches the connector on a daemon thread, once a minute, for as long as the generator runs. */
    public static void start(String connectUrl, String connector) {
        SinkWatch watch = new SinkWatch(connectUrl, connector);
        Thread t = new Thread(() -> {
            while (true) {
                watch.check();
                try {
                    Thread.sleep(EVERY);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "sink-watch");
        t.setDaemon(true);
        t.start();
    }

    /** Whether a connector's status (Kafka Connect's GET /connectors/{name}/status) shows it or a task failed. */
    static boolean failed(String status) {
        return FAILED.matcher(status).find();
    }

    void check() {
        try {
            HttpResponse<String> answer = http.send(HttpRequest.newBuilder(status).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
            unreachable = false;
            if (answer.statusCode() != 200 || !failed(answer.body())) {
                return;
            }
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

package keel.catalog.load;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One catalog server as its own JVM, started from a runnable jar with command-line properties, its output
 * in {@code build/m8/logs/<name>.log}. Stopping it ends the process at once: a load balancer drains a
 * server before it is stopped, which the tests do by taking it out of rotation first.
 */
final class ServerProcess implements AutoCloseable {

    private final String name;
    private final Process process;
    private final int port;
    private final Path log;

    private ServerProcess(String name, Process process, int port, Path log) {
        this.name = name;
        this.process = process;
        this.port = port;
        this.log = log;
    }

    /** @param jvmOptions extra JVM options, before {@code -jar}; {@code properties} go after it */
    static ServerProcess start(String name, Path jar, List<String> jvmOptions, List<String> properties) {
        int port = freePort();
        Path logs = Path.of(System.getProperty("m8.work"), "logs");
        Path log = logs.resolve(name + ".log");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx1g", "-XX:+UseZGC"));
        command.addAll(jvmOptions);
        command.addAll(List.of("-jar", jar.toString(), "--server.port=" + port));
        command.addAll(properties);
        try {
            Files.createDirectories(logs);
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            // A test JVM that exits without closing its stack would otherwise leave the server running.
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
            return new ServerProcess(name, process, port, log);
        } catch (IOException e) {
            throw new IllegalStateException("server " + name + " did not start", e);
        }
    }

    URI uri() {
        return URI.create("http://localhost:" + port);
    }

    String name() {
        return name;
    }

    boolean alive() {
        return process.isAlive();
    }

    /** Waits until the server answers its config route, up to three minutes. */
    void awaitReady(String token, String warehouse) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("server " + name + " exited with " + process.exitValue() + "; see " + log);
            }
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri().resolve("/v1/config?warehouse="
                        + warehouse)).header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(5)).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 500) {
                    return;
                }
            } catch (IOException e) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            pause(Duration.ofMillis(300));
        }
        throw new IllegalStateException("server " + name + " not ready in 3 minutes; see " + log);
    }

    /** Ends the process at once, as a crash or an orchestrator's kill would. */
    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    Path log() {
        return log;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

package fidice.catalog.engines;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs {@code docker} and {@code docker compose} for the engine matrix, logging every command and its output. */
final class Compose {

    /** One finished command: its exit code and its combined stdout and stderr. */
    record Result(int exit, String output) {
    }

    private final Path file;
    private final Map<String, String> env;
    private final Path log;

    Compose(Path file, Map<String, String> env, Path log) {
        this.file = file;
        this.env = Map.copyOf(env);
        this.log = log;
    }

    /** {@code docker compose <args>}; fails unless it exits 0. */
    String compose(Duration timeout, String... args) {
        Result result = composeResult(timeout, args);
        if (result.exit() != 0) {
            throw new IllegalStateException("docker compose " + String.join(" ", args) + " exited " + result.exit()
                    + ":\n" + result.output());
        }
        return result.output();
    }

    Result composeResult(Duration timeout, String... args) {
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-f", file.toString()));
        command.addAll(List.of(args));
        return run(timeout, command, null);
    }

    /** Plain {@code docker <args>}; fails unless it exits 0. */
    String docker(Duration timeout, String... args) {
        Result result = dockerResult(timeout, args);
        if (result.exit() != 0) {
            throw new IllegalStateException("docker " + String.join(" ", args) + " exited " + result.exit()
                    + ":\n" + result.output());
        }
        return result.output();
    }

    Result dockerResult(Duration timeout, String... args) {
        return dockerResult(timeout, null, args);
    }

    /**
     * Plain {@code docker <args>} with {@code input} (when not null) as its stdin. Text with double quotes
     * goes this way: on Windows a quote inside a command-line argument does not survive.
     */
    Result dockerResult(Duration timeout, Path input, String... args) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        return run(timeout, command, input);
    }

    private Result run(Duration timeout, List<String> command, Path input) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(env);
        append("$ " + String.join(" ", command) + (input == null ? "" : " < " + input));
        if (input != null) {
            builder.redirectInput(input.toFile());
        }
        try {
            Process process = builder.start();
            if (input == null) {
                process.getOutputStream().close();
            }
            StringBuilder output = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> copy(process.getInputStream(), output));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                reader.join(5000);
                append(output.toString());
                throw new IllegalStateException("timed out after " + timeout + ": " + String.join(" ", command)
                        + "\n" + output);
            }
            reader.join();
            String text = output.toString();
            append(text);
            append("(exit " + process.exitValue() + ")");
            return new Result(process.exitValue(), text);
        } catch (IOException e) {
            throw new IllegalStateException("could not run " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + command, e);
        }
    }

    private static void copy(InputStream in, StringBuilder output) {
        try (in) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                synchronized (output) {
                    output.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            synchronized (output) {
                output.append("\n(output read failed: ").append(e.getMessage()).append(")\n");
            }
        }
    }

    private void append(String text) {
        try {
            Files.writeString(log, text + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + log, e);
        }
    }
}

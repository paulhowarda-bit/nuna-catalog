package keel.catalog.engines;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the command-line tools the engine matrix drives ({@code kind}, {@code docker}, {@code kubectl},
 * {@code helm}), with the run's environment, logging every command and its output.
 */
final class Cli {

    /** One finished command: its exit code and its combined stdout and stderr. */
    record Result(int exit, String output) {
    }

    private final Map<String, String> env;
    private final Path log;

    Cli(Map<String, String> env, Path log) {
        this.env = Map.copyOf(env);
        this.log = log;
    }

    /** Runs the command; fails unless it exits 0. */
    String ok(Duration timeout, String... command) {
        return ok(timeout, null, command);
    }

    /**
     * Runs the command with {@code input} (when not null) as its stdin; fails unless it exits 0. Text with
     * double quotes goes this way: on Windows a quote inside a command-line argument does not survive.
     */
    String ok(Duration timeout, Path input, String... command) {
        Result result = run(timeout, input, command);
        if (result.exit() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " exited " + result.exit() + ":\n"
                    + result.output());
        }
        return result.output();
    }

    Result run(Duration timeout, Path input, String... command) {
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
            throw new IllegalStateException("could not run " + List.of(command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + List.of(command), e);
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

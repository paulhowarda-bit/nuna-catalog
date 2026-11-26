package nuna.catalog.tests.s3;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * nuna's local Kubernetes cluster as the tests that run pods use it: kind cluster {@code nuna} on Podman, made and
 * started by {@code deploy/kind/kind.sh up} (backlog 287; Docker Desktop's Kubernetes until 2026-11-26). Its pods run
 * images only from the node's own store, so a test puts the images Podman built or pulled there with {@link #load},
 * as {@code kind.sh load} does for the demo.
 */
public final class KindCluster {

    /** The kubectl context of the cluster {@code deploy/kind/kind.sh} makes. */
    public static final String CONTEXT = "kind-nuna";

    private KindCluster() {
    }

    /**
     * Puts each image from Podman's store on the node of the kind cluster of kubectl context {@code context}, unless
     * the node has it already, under the name a pod gives it ({@link #ref}). Does nothing for a context that is not
     * a kind cluster's: its nodes must see the images some other way.
     *
     * @param scratch a folder for the image archive on its way to the node
     */
    public static void load(String context, Path scratch, List<String> images) {
        if (!context.startsWith("kind-")) {
            return;
        }
        String node = context.substring("kind-".length()) + "-control-plane";
        for (String image : images) {
            String ref = ref(image);
            String id = run(Duration.ofMinutes(1), null, "podman", "image", "inspect", "--format", "{{.Id}}", image)
                    .strip();
            String there = runAllowingFailure(Duration.ofMinutes(1), "podman", "exec", node, "crictl", "inspecti", "-o",
                    "go-template", "--template", "{{.status.id}}", ref).strip();
            if (there.equals("sha256:" + id)) {
                continue;
            }
            Path archive = scratch.resolve("image.tar");
            try {
                run(Duration.ofMinutes(1), null, "podman", "tag", id, ref);
                Files.deleteIfExists(archive);
                run(Duration.ofMinutes(20), null, "podman", "save", "-o", archive.toString(), ref);
                run(Duration.ofMinutes(20), archive, "podman", "exec", "-i", node, "ctr", "--namespace=k8s.io",
                        "images", "import", "--all-platforms", "--digests", "--snapshotter=overlayfs", "-");
                Files.deleteIfExists(archive);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** The name a node gives an image a pod names: Docker Hub's, for a name without a registry. */
    public static String ref(String image) {
        int slash = image.indexOf('/');
        if (slash < 0) {
            return "docker.io/library/" + image;
        }
        String first = image.substring(0, slash);
        if (first.contains(".") || first.contains(":") || first.equals("localhost")) {
            return image;
        }
        return "docker.io/" + image;
    }

    private static String runAllowingFailure(Duration timeout, String... command) {
        try {
            return run(timeout, null, command);
        } catch (IllegalStateException failed) {
            return "";
        }
    }

    /** Runs a command, with {@code input} as its stdin when not null; returns its output, or fails with it. */
    private static String run(Duration timeout, Path input, String... command) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (input != null) {
            builder.redirectInput(input.toFile());
        }
        try {
            Process process = builder.start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(String.join(" ", command) + " ran past " + timeout);
            }
            String text = output.get(1, TimeUnit.MINUTES);
            if (process.exitValue() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " exited " + process.exitValue() + ":\n"
                        + text);
            }
            return text;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(String.join(" ", command) + ": its output could not be read", e);
        }
    }

    private static String read(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

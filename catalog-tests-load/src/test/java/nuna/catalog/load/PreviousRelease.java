package nuna.catalog.load;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The runnable jar of the previous release ({@code m8.previous-release}, a commit of this repository), built
 * once from a {@code git archive} of that commit into {@code build/m8/previous}, without installing anything,
 * with the Gradle wrapper in its own tree.
 */
final class PreviousRelease {

    private PreviousRelease() {
    }

    static synchronized Path jar() {
        String commit = System.getProperty("m8.previous-release");
        Path home = Path.of(System.getProperty("m8.work"), "previous");
        Path jar = home.resolve("catalog-server-" + commit + ".jar");
        if (Files.exists(jar)) {
            return jar;
        }
        Path source = home.resolve("src-" + commit);
        try {
            Files.createDirectories(home);
            Path zip = home.resolve(commit + ".zip");
            run(Path.of(System.getProperty("m8.repo-root")), home.resolve("archive.log"),
                    List.of("git", "archive", "--format=zip", "-o", zip.toAbsolutePath().toString(), commit));
            unzip(zip, source);
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            List<String> build = windows
                    ? List.of("cmd", "/c", source.resolve("gradlew.bat").toAbsolutePath().toString(), "-q", "--no-daemon", ":catalog-server:bootJar")
                    : List.of("sh", "gradlew", "-q", "--no-daemon", ":catalog-server:bootJar");
            run(source, home.resolve("build.log"), build);
            try (var built = Files.list(source.resolve("catalog-server/build/libs"))) {
                Path exec = built.filter(p -> p.getFileName().toString().endsWith("-exec.jar")).findFirst()
                        .orElseThrow(() -> new IllegalStateException("the previous release built no exec jar"));
                Files.copy(exec, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            return jar;
        } catch (IOException e) {
            throw new IllegalStateException("could not build the previous release " + commit, e);
        }
    }

    /**
     * A server's {@code properties}, for one of the previous release. It predates the rename to nuna, so it reads its
     * settings under {@code keel.}; and it is told this release's topics, whose defaults the rename changed too, so old
     * and new servers write one audit stream and one change stream, as in a real rolling upgrade.
     */
    static List<String> settings(List<String> properties) {
        List<String> settings = new ArrayList<>();
        for (String property : properties) {
            if (property.startsWith("--nuna.")) {
                settings.add("--keel." + property.substring("--nuna.".length()));
            } else if (property.startsWith("--logging.level.nuna.")) {
                settings.add("--logging.level.keel." + property.substring("--logging.level.nuna.".length()));
            } else {
                settings.add(property);
            }
        }
        settings.add("--keel.audit.audit-topic=nuna.catalog.audit");
        settings.add("--keel.audit.change-topic=nuna.catalog.changes");
        return settings;
    }

    private static void run(Path directory, Path log, List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        try {
            if (!process.waitFor(20, TimeUnit.MINUTES) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IllegalStateException(String.join(" ", command) + " failed; see " + log);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void unzip(Path zip, Path target) throws IOException {
        try (InputStream in = Files.newInputStream(zip); ZipInputStream entries = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = entries.getNextEntry()) != null; ) {
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("archive entry outside the target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(entries, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}

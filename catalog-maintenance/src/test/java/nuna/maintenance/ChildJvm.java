package nuna.maintenance;

import nuna.catalog.tests.s3.TestS3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A test main started in a JVM of its own, on this JVM's class path, with its output and errors together. */
final class ChildJvm {

    private ChildJvm() {
    }

    /**
     * @param folder     where the class path's argument file goes: on Windows it is longer than a command line may be
     * @param jvmOptions options before the main class, e.g. {@code -Xmx1g}
     */
    static Process start(Path folder, List<String> jvmOptions, Class<?> main, List<String> arguments)
            throws IOException {
        Path classPath = Files.createTempFile(folder, "classpath", ".args");
        Files.writeString(classPath, "-cp \"" + System.getProperty("java.class.path").replace('\\', '/') + "\"");
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow(),
                "@" + classPath));
        command.addAll(TestS3.get().jvmOptions());
        command.addAll(jvmOptions);
        command.add(main.getName());
        command.addAll(arguments);
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    /** The worker's storage profile and token file as {@link PurgeProcess} and {@link OrphanScanProcess} take them. */
    static List<String> workerArguments(TestCatalog catalog, Path folder) throws IOException {
        Path token = Files.createTempFile(folder, "token", "");
        Files.writeString(token, catalog.workerTokens().token());
        WorkerSettings.StorageProfile profile = catalog.storageProfiles().get("test");
        return List.of(catalog.uri.toString(), token.toString(),
                profile.endpoint() == null ? "-" : profile.endpoint().toString(), profile.prefixes().getFirst(),
                profile.accessKeyId() == null ? "-" : profile.accessKeyId(),
                profile.secretAccessKey() == null ? "-" : profile.secretAccessKey());
    }

    /** The worker's storage, from the arguments {@link #workerArguments} made, starting at {@code from}. */
    static Storage storage(String[] args, int from) {
        java.net.URI endpoint = args[from].equals("-") ? null : java.net.URI.create(args[from]);
        String key = args[from + 2].equals("-") ? null : args[from + 2];
        String secret = args[from + 3].equals("-") ? null : args[from + 3];
        return Storage.of(java.util.Map.of("test", new WorkerSettings.StorageProfile(List.of(args[from + 1]), endpoint,
                TestS3.REGION, endpoint != null, key, secret, null)));
    }

    /** No Spring in a child to configure Logback, whose own default logs everything. */
    static void quietLogging() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.WARN);
    }
}

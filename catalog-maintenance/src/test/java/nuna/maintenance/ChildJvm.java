package nuna.maintenance;

import nuna.catalog.tests.s3.TestS3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A test main started in a JVM of its own, on this JVM's class path, with its output and errors together. */
final class ChildJvm {

    /** On AWS, the worker's role, which {@link #storage} signs in as: a child's default chain is the run's sign-in. */
    private static final String WORKER_ROLE = "nuna.test.worker-role";

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
        TestS3 s3 = TestS3.get();
        command.addAll(s3.jvmOptions());
        if (s3.kind == TestS3.Kind.AWS) {
            command.add("-D" + WORKER_ROLE + "=" + s3.maintenancePrincipal());
        }
        command.addAll(jvmOptions);
        command.add(main.getName());
        command.addAll(arguments);
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    /**
     * The catalog, the worker's token file and its store identity, as {@link PurgeProcess} and
     * {@link OrphanScanProcess} take them.
     */
    static List<String> workerArguments(TestCatalog catalog, Path folder) throws IOException {
        Path token = Files.createTempFile(folder, "token", "");
        Files.writeString(token, catalog.workerTokens().token());
        WorkerSettings.Store store = catalog.stores().get("test");
        return List.of(catalog.uri.toString(), token.toString(), store.endpoint().toString(), store.kind().name(),
                store.accessKeyId() == null ? "-" : store.accessKeyId(),
                store.secretAccessKey() == null ? "-" : store.secretAccessKey());
    }

    /**
     * The worker's storage, from the arguments {@link #workerArguments} made, its store's starting at {@code from};
     * on AWS, as the worker's role {@link #start} names ({@link TestCatalog#asRole}). It reaches the prefixes the
     * catalog gives the worker, as at a scan.
     */
    static Storage storage(String[] args, int from) {
        String role = System.getProperty(WORKER_ROLE);
        Storage storage;
        if (role != null) {
            storage = TestCatalog.asRole(role);
        } else {
            String key = args[from + 2].equals("-") ? null : args[from + 2];
            String secret = args[from + 3].equals("-") ? null : args[from + 3];
            storage = Storage.of(java.util.Map.of("test", new WorkerSettings.Store(
                    nuna.catalog.objectstore.StoreKind.valueOf(args[from + 1]), java.net.URI.create(args[from]),
                    TestS3.REGION, key, secret, null)));
        }
        java.net.URI catalog = java.net.URI.create(args[0]);
        TokenSource tokens = new TokenSource.FileToken(Path.of(args[1]));
        storage.reach(new Management(catalog, tokens, java.net.http.HttpClient.newHttpClient(), 100)
                .storageProfiles());
        return storage;
    }

    /** No Spring in a child to configure Logback, whose own default logs everything. */
    static void quietLogging() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.WARN);
    }
}

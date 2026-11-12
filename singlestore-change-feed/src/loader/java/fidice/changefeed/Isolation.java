package fidice.changefeed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The class loader the change feed runs in. SingleStore puts every connector jar on one class path with its own
 * libraries, among them Iceberg 1.9, AWS SDK 2.25 and Jackson, so the feed's libraries (Iceberg 1.12 and what it
 * needs) travel inside the connector jar under {@code lib/} and are loaded from there first. Only the JDK and SLF4J
 * come from SingleStore's side, so the feed logs where the extractor logs.
 */
final class Isolation {

    static final String ENGINE = "fidice.changefeed.engine.ChangeFeed";
    private static final String[] PARENT_FIRST = {"java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.", "org.slf4j."};
    private static ClassLoader loader;

    private Isolation() {}

    static synchronized ClassLoader loader() {
        if (loader == null) {
            Path jar = ownJar();
            List<URL> urls = new ArrayList<>();
            for (Path lib : unpack(jar)) {
                try {
                    urls.add(lib.toUri().toURL());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            loader = new ChildFirst(urls.toArray(URL[]::new), Isolation.class.getClassLoader());
        }
        return loader;
    }

    /** Runs {@code call} with the feed's loader as the thread's context loader, as Iceberg and Hadoop expect. */
    static <T> T within(Callable<T> call) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader());
        try {
            return call.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static Path ownJar() {
        try {
            return Path.of(Isolation.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Copies {@code lib/*.jar} out of the connector jar into a directory named for the jar's size and time, once:
     * a class loader cannot read a jar inside a jar. Each file is written aside and moved into place, so two
     * extractors unpacking at once both end with whole files.
     */
    private static List<Path> unpack(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"),
                    "fidice-changefeed-" + Files.size(jar) + "-" + Files.getLastModifiedTime(jar).toMillis());
            Files.createDirectories(dir);
            List<Path> libs = new ArrayList<>();
            for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("lib/") || !entry.getName().endsWith(".jar")) {
                    continue;
                }
                Path target = dir.resolve(entry.getName().substring("lib/".length()));
                if (!Files.exists(target) || Files.size(target) != entry.getSize()) {
                    Path aside = Files.createTempFile(dir, "unpack", ".tmp");
                    try (InputStream in = file.getInputStream(entry)) {
                        Files.copy(in, aside, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(aside, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                libs.add(target);
            }
            if (libs.isEmpty()) {
                throw new IllegalStateException(jar + " has no lib/ jars; build it with :singlestore-change-feed:connectorJar");
            }
            return libs;
        } catch (IOException e) {
            throw new UncheckedIOException("unpacking " + jar, e);
        }
    }

    private static final class ChildFirst extends URLClassLoader {

        static {
            registerAsParallelCapable();
        }

        ChildFirst(URL[] urls, ClassLoader parent) {
            super("fidice-changefeed", urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String prefix : PARENT_FIRST) {
                if (name.startsWith(prefix)) {
                    return super.loadClass(name, resolve);
                }
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        @Override
        public URL getResource(String name) {
            URL url = findResource(name);
            return url != null ? url : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            // Our own first, then the parent's: service files must find our implementations.
            List<URL> all = new ArrayList<>();
            findResources(name).asIterator().forEachRemaining(all::add);
            if (!name.startsWith("META-INF/services/")) {
                getParent().getResources(name).asIterator().forEachRemaining(all::add);
            }
            return java.util.Collections.enumeration(all);
        }
    }
}

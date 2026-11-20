package nuna.catalog.tests.s3;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A private CA, as an on-prem store's certificate is signed by (backlog 21), and HTTPS servers in the test JVM that
 * present a certificate it signed for {@code 127.0.0.1} and {@code localhost}. Made once per test JVM with the JDK's
 * own {@code keytool}, valid for two days, in a temporary folder removed when the JVM exits: no key is committed and
 * nothing but the JDK is needed. The CA's certificate is {@link #bundle}, a PEM file, as a storage profile's
 * {@code ca-bundle} names one.
 */
public final class PrivateCa {

    private static final String PASSWORD = "nuna-test";
    private static PrivateCa shared;

    /** The CA's certificate as a PEM file. */
    public final Path bundle;
    /** The CA's certificate. */
    public final X509Certificate certificate;
    private final SSLContext serverTls;

    private PrivateCa(Path bundle, X509Certificate certificate, SSLContext serverTls) {
        this.bundle = bundle;
        this.certificate = certificate;
        this.serverTls = serverTls;
    }

    /** The test JVM's CA, made on first use. */
    public static synchronized PrivateCa shared() {
        if (shared == null) {
            shared = create();
        }
        return shared;
    }

    /** One answer to a request: its status, content type and body. */
    public record Answer(int status, String contentType, byte[] body) {

        public static Answer xml(String body) {
            return new Answer(200, "application/xml", body.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Starts an HTTPS server on {@code 127.0.0.1} presenting a certificate this CA signed. Each request is answered
     * with what {@code answers} gives for {@code "<method> <path and query>"}; {@link Server#requests} lists the
     * requests that got that far, so only ones whose client trusted the certificate.
     */
    public Server serve(Function<String, Answer> answers) {
        try {
            HttpsServer https = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(serverTls));
            List<String> requests = new CopyOnWriteArrayList<>();
            https.createContext("/", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    String request = exchange.getRequestMethod() + " " + exchange.getRequestURI();
                    requests.add(request);
                    Answer answer = answers.apply(request);
                    exchange.getResponseHeaders().set("Content-Type", answer.contentType());
                    int length = answer.body().length;
                    exchange.sendResponseHeaders(answer.status(), length == 0 ? -1 : length);
                    exchange.getResponseBody().write(answer.body());
                }
            });
            https.start();
            return new Server(https, URI.create("https://127.0.0.1:" + https.getAddress().getPort()), requests);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A running HTTPS server. */
    public static final class Server implements AutoCloseable {

        /** {@code https://127.0.0.1:<port>}, with nothing after it, as a storage profile's endpoint is written. */
        public final URI endpoint;
        /** The requests that reached the server, as {@code "<method> <path and query>"}. */
        public final List<String> requests;
        private final HttpsServer https;

        private Server(HttpsServer https, URI endpoint, List<String> requests) {
            this.https = https;
            this.endpoint = endpoint;
            this.requests = requests;
        }

        @Override
        public void close() {
            https.stop(0);
        }
    }

    private static PrivateCa create() {
        try {
            Path dir = Files.createTempDirectory("nuna-private-ca");
            dir.toFile().deleteOnExit();
            // Two days: a test run never outlasts it, and nothing made here is worth keeping.
            keytool(dir, "-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=nuna test CA", "-ext", "bc:c", "-validity", "2", "-keystore", "ca.p12");
            keytool(dir, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=localhost", "-validity", "2", "-keystore", "server.p12");
            keytool(dir, "-certreq", "-alias", "server", "-keystore", "server.p12", "-file", "server.csr");
            keytool(dir, "-gencert", "-alias", "ca", "-keystore", "ca.p12", "-infile", "server.csr",
                    "-outfile", "server.crt", "-ext", "san=dns:localhost,ip:127.0.0.1", "-validity", "2");

            X509Certificate ca = (X509Certificate) load(dir.resolve("ca.p12")).getCertificate("ca");
            X509Certificate signed;
            try (InputStream in = Files.newInputStream(dir.resolve("server.crt"))) {
                signed = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
            KeyStore server = load(dir.resolve("server.p12"));
            Key key = server.getKey("server", PASSWORD.toCharArray());
            server.setKeyEntry("server", key, PASSWORD.toCharArray(), new Certificate[] {signed, ca});
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(server, PASSWORD.toCharArray());
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(keys.getKeyManagers(), null, null);

            Path bundle = dir.resolve("ca.pem");
            Files.writeString(bundle, pem(ca), StandardCharsets.US_ASCII);
            for (String made : List.of("ca.p12", "server.p12", "server.csr", "server.crt", "ca.pem")) {
                dir.resolve(made).toFile().deleteOnExit();
            }
            return new PrivateCa(bundle, ca, tls);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A certificate as PEM text. */
    public static String pem(X509Certificate certificate) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyStore load(Path file) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, PASSWORD.toCharArray());
        }
        return store;
    }

    private static void keytool(Path dir, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                // A small JVM starts faster; keytool does little work.
                "-J-XX:TieredStopAtLevel=1", "-J-XX:+UseSerialGC"));
        command.addAll(List.of(args));
        command.addAll(List.of("-storetype", "PKCS12", "-storepass", PASSWORD));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (process.waitFor() != 0) {
                throw new IllegalStateException("keytool " + String.join(" ", args) + " failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

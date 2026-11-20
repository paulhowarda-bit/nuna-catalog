package nuna.catalog.objectstore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;

/**
 * The CAs one storage profile's S3 and STS clients trust beside the JVM's own (backlog 21): an on-prem store (MinIO,
 * Ceph) whose certificate a private CA signed needs that CA, and only that profile's clients should trust it. Read
 * once, at startup, from a PEM file of one or more certificates. Vended credentials carry no trust: an engine trusts
 * the store through its own setup.
 */
public final class CaBundle {

    private static final Pattern HEADER = Pattern.compile("-----BEGIN CERTIFICATE-----");

    private final List<X509Certificate> certificates;
    private final TrustManager[] trustManagers;

    private CaBundle(List<X509Certificate> certificates) {
        this.certificates = List.copyOf(certificates);
        this.trustManagers = withJvmCas(certificates);
    }

    /**
     * Reads a PEM file of one or more certificates; text between them, such as a label, is allowed.
     *
     * @throws IllegalArgumentException naming the file, when it cannot be read, holds no certificate, or holds one that
     *                                  does not parse
     */
    public static CaBundle read(Path path) {
        byte[] pem;
        try {
            pem = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read the CA bundle " + path + ": " + e, e);
        }
        List<X509Certificate> certificates;
        try {
            certificates = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem))
                    .stream().map(X509Certificate.class::cast).toList();
        } catch (CertificateException e) {
            throw new IllegalArgumentException("the CA bundle " + path + " is not PEM certificates: " + e.getMessage(),
                    e);
        }
        // The JDK's parser stops quietly at a certificate cut short, so count what the file says it holds.
        long headers = HEADER.matcher(new String(pem, StandardCharsets.ISO_8859_1)).results().count();
        if (certificates.size() < headers) {
            throw new IllegalArgumentException("the CA bundle " + path + " is not PEM certificates: only "
                    + certificates.size() + " of its " + headers + " certificates parse");
        }
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException("the CA bundle " + path + " holds no certificate");
        }
        return new CaBundle(certificates);
    }

    /** The certificates it holds, in file order. */
    public List<X509Certificate> certificates() {
        return certificates;
    }

    /**
     * Has the client this builder makes trust this bundle beside the JVM's CAs: it gets the SDK's default HTTP client
     * (Apache HttpClient 5), with SDK defaults, and these trust managers.
     */
    public void applyTo(SdkSyncClientBuilder<?, ?> builder) {
        builder.httpClientBuilder(Apache5HttpClient.builder().tlsTrustManagersProvider(() -> trustManagers));
    }

    /** Trust managers accepting a chain that the JVM's default trust store anchors, or that this bundle does. */
    TrustManager[] trustManagers() {
        return trustManagers;
    }

    private static TrustManager[] withJvmCas(List<X509Certificate> extra) {
        try {
            TrustManagerFactory jvm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            jvm.init((KeyStore) null);
            List<X509Certificate> all = new ArrayList<>();
            Arrays.stream(jvm.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                    .forEach(manager -> all.addAll(List.of(((X509TrustManager) manager).getAcceptedIssuers())));
            all.addAll(extra);
            KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
            anchors.load(null, null);
            for (int i = 0; i < all.size(); i++) {
                anchors.setCertificateEntry("ca-" + i, all.get(i));
            }
            TrustManagerFactory both = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            both.init(anchors);
            return both.getTrustManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("cannot build a trust store from the JVM's CAs and a CA bundle", e);
        }
    }
}

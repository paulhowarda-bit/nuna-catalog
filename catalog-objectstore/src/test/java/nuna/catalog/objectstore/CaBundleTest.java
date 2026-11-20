package nuna.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.tests.s3.PrivateCa;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading a storage profile's CA bundle (backlog 21): one PEM file of one or more certificates, read at startup; a file
 * that is missing, empty or not certificates fails with a message naming it. The bundle is trusted beside the JVM's
 * CAs, never instead of them. The certificates here are the JVM's own CAs and the test's private CA, so nothing is
 * committed and nothing reaches the network.
 */
class CaBundleTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a bundle of one certificate is read")
    void oneCertificate() throws IOException {
        X509Certificate ca = jvmCas().getFirst();
        Path file = write("one.pem", PrivateCa.pem(ca));

        assertThat(CaBundle.read(file).certificates()).containsExactly(ca);
    }

    @Test
    @DisplayName("a bundle of several certificates is read in file order, with labels between them")
    void severalCertificates() throws IOException {
        List<X509Certificate> cas = jvmCas().subList(0, 3);
        StringBuilder pem = new StringBuilder();
        for (X509Certificate ca : cas) {
            pem.append("# ").append(ca.getSubjectX500Principal().getName()).append('\n').append(PrivateCa.pem(ca))
                    .append('\n');
        }
        Path file = write("several.pem", pem.toString());

        assertThat(CaBundle.read(file).certificates()).containsExactlyElementsOf(cas);
    }

    @Test
    @DisplayName("a missing file fails, naming it")
    void missingFile() {
        Path file = dir.resolve("absent.pem");

        assertThatThrownBy(() -> CaBundle.read(file)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot read the CA bundle " + file);
    }

    @Test
    @DisplayName("an empty file fails, naming it: it would trust nothing more")
    void emptyFile() throws IOException {
        Path file = write("empty.pem", "");

        assertThatThrownBy(() -> CaBundle.read(file)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the CA bundle " + file + " holds no certificate");
    }

    @Test
    @DisplayName("a broken certificate, or a file of no certificates, fails, naming the file")
    void brokenFile() throws IOException {
        Path broken = write("broken.pem", "-----BEGIN CERTIFICATE-----\nnot base64!\n-----END CERTIFICATE-----\n");
        Path text = write("text.pem", "these are not the certificates you are looking for\n");
        String good = PrivateCa.pem(jvmCas().getFirst());
        Path truncated = write("truncated.pem", good + good.substring(0, good.length() / 2));

        for (Path file : List.of(broken, text, truncated)) {
            assertThatThrownBy(() -> CaBundle.read(file)).as(file.getFileName().toString())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("the CA bundle " + file + " is not PEM certificates: ");
        }
    }

    @Test
    @DisplayName("its trust managers trust the JVM's CAs and the bundle's, and nothing else")
    void trustsTheJvmCasBesideTheBundle() {
        X509Certificate ca = PrivateCa.shared().certificate;
        CaBundle bundle = CaBundle.read(PrivateCa.shared().bundle);

        TrustManager[] managers = bundle.trustManagers();
        assertThat(managers).hasSize(1).hasOnlyElementsOfType(X509TrustManager.class);
        List<X509Certificate> trusted = List.of(((X509TrustManager) managers[0]).getAcceptedIssuers());
        assertThat(trusted).containsAll(jvmCas()).contains(ca).hasSize(jvmCas().size() + 1);
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.US_ASCII);
    }

    private static List<X509Certificate> jvmCas() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            return Arrays.stream(factory.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                    .flatMap(manager -> Arrays.stream(((X509TrustManager) manager).getAcceptedIssuers())).toList();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

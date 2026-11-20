package nuna.catalog.objectstore;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.tests.s3.PrivateCa;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * A storage profile's CA bundle in the S3 and STS clients it opens (backlog 21), against an HTTPS server in the test
 * JVM whose certificate the test's private CA signed, as an on-prem store's would be: it answers a GET as S3 does and
 * a POST as STS's AssumeRole does. Without the bundle the JVM's CAs alone are trusted, and the handshake fails before
 * any request is sent; with it, both clients reach the server.
 */
class CaBundleHandshakeTest {

    private static final String JSON = "{\"format-version\":2}";
    private static final String STS = """
            <AssumeRoleResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
              <AssumeRoleResult>
                <Credentials>
                  <AccessKeyId>ASIAPRIVATECA</AccessKeyId>
                  <SecretAccessKey>secret</SecretAccessKey>
                  <SessionToken>token</SessionToken>
                  <Expiration>%s</Expiration>
                </Credentials>
              </AssumeRoleResult>
              <ResponseMetadata><RequestId>request-1</RequestId></ResponseMetadata>
            </AssumeRoleResponse>
            """.formatted(Instant.now().plus(Duration.ofMinutes(15)).truncatedTo(ChronoUnit.SECONDS));
    private static final AwsCredentialsProvider KEYS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("catalog", "catalog-secret"));
    private static final String TABLE = "s3://lake/tables/" + new UUID(0, 1);
    private static final String FILE = TABLE + "/metadata/00001-a.metadata.json";

    private PrivateCa.Server store;
    private StorageProfile profile;

    @BeforeEach
    void startStore() {
        store = PrivateCa.shared().serve(request -> request.startsWith("POST ")
                ? PrivateCa.Answer.xml(STS)
                : new PrivateCa.Answer(200, "application/json", JSON.getBytes(UTF_8)));
        profile = StorageProfile.of(StoreKind.CEPH, store.endpoint.toString(), "us-east-1", "s3://lake/tables",
                List.of("s3://lake/tables"));
    }

    @AfterEach
    void stopStore() {
        store.close();
    }

    @Test
    @DisplayName("without the profile's bundle, the S3 client refuses the store's certificate and sends nothing")
    void s3RefusesWithoutTheBundle() {
        try (MetadataFiles files = new MetadataFiles(profile, KEYS, Optional.empty())) {
            assertThatThrownBy(() -> files.read(FILE, sha256(), OnMismatch.FAIL))
                    .isInstanceOf(ObjectStoreException.class)
                    .hasStackTraceContaining("javax.net.ssl.SSLHandshakeException");
        }
        assertThat(store.requests).isEmpty();
    }

    @Test
    @DisplayName("with the profile's bundle, the S3 client trusts the store's certificate")
    void s3TrustsTheBundle() {
        try (MetadataFiles files = new MetadataFiles(profile, KEYS, Optional.of(bundle()))) {
            LoadedMetadata read = files.read(FILE, sha256(), OnMismatch.FAIL);

            assertThat(read.json()).isEqualTo(JSON);
        }
        assertThat(store.requests).hasSize(1);
        assertThat(store.requests.getFirst()).startsWith("GET /lake/tables/" + new UUID(0, 1) + "/metadata/");
    }

    @Test
    @DisplayName("without the profile's bundle, the STS client refuses the store's certificate and sends nothing")
    void stsRefusesWithoutTheBundle() {
        try (CredentialVendor vendor = vendor(Optional.empty())) {
            assertThatThrownBy(() -> vendor.vend(TABLE, Access.READ, identity()))
                    .isInstanceOf(ObjectStoreException.class)
                    .hasStackTraceContaining("javax.net.ssl.SSLHandshakeException");
        }
        assertThat(store.requests).isEmpty();
    }

    @Test
    @DisplayName("with the profile's bundle, the STS client trusts the store's certificate")
    void stsTrustsTheBundle() {
        try (CredentialVendor vendor = vendor(Optional.of(bundle()))) {
            VendedCredentials credentials = vendor.vend(TABLE, Access.READ, identity());

            assertThat(credentials.accessKeyId()).isEqualTo("ASIAPRIVATECA");
        }
        assertThat(store.requests).containsExactly("POST /");
    }

    private CredentialVendor vendor(Optional<CaBundle> caBundle) {
        return new CredentialVendor(profile, Optional.of("arn:aws:iam:::role/nuna-vending"), KEYS,
                CredentialVendor.MIN_LIFETIME, caBundle);
    }

    private static CaBundle bundle() {
        return CaBundle.read(PrivateCa.shared().bundle);
    }

    private static SessionIdentity identity() {
        return new SessionIdentity("alice@corp.example", Optional.empty(), "spark", "req-1", "trading",
                new UUID(0, 1));
    }

    private static String sha256() {
        return HexFormat.of().formatHex(MetadataFiles.sha256(JSON.getBytes(UTF_8)));
    }
}

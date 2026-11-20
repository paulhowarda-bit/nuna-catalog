package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.archive.ObjectLockArchive;
import nuna.catalog.core.CatalogError;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The reconcile's {@code accept-archived} option fails the request, before any table is walked, when the server has
 * no archive or cannot read it (backlog 10): a tip is never taken on the archive's word unless the archive was read.
 * Java only; {@code DrReconcileRouteTest} runs the route itself on PostgreSQL.
 */
class ReconcileArchiveOptionTest {

    @Test
    @DisplayName("a server with no archive answers 409, naming the setting")
    void noArchive() {
        assertThatThrownBy(() -> ReconcileRoutes.archived(Optional.empty()))
                .isInstanceOfSatisfying(CatalogError.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.type()).isEqualTo("ArchiveNotConfiguredException");
                })
                .hasMessageContaining("nuna.audit.archive.bucket is not set");
    }

    @Test
    @DisplayName("an archive whose store cannot be reached answers 503 with the store's failure")
    void archiveCannotBeRead() {
        S3Client unreachable = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(),
                new Class<?>[] {S3Client.class}, (proxy, method, args) -> {
                    throw SdkClientException.create("Unable to execute HTTP request: Connect to archive.test:9000 failed");
                });
        ObjectLockArchive archive = new ObjectLockArchive(unreachable, "audit-archive", "audit",
                ObjectLockArchive.MINIMUM_RETENTION, Clock.systemUTC());

        assertThatThrownBy(() -> ReconcileRoutes.archived(Optional.of(archive)))
                .isInstanceOfSatisfying(CatalogError.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.type()).isEqualTo("ServiceUnavailableException");
                })
                .hasMessageContaining("cannot be read").hasMessageContaining("archive.test:9000");
    }
}

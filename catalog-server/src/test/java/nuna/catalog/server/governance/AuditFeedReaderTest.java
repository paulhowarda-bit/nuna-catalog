package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.audittables.AuditTableOverS3;
import nuna.audittables.EventsReader;
import nuna.catalog.audit.AuditRow;
import nuna.catalog.core.Names;
import nuna.catalog.server.security.AuditCatalogSettings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Pointer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The audit feed's readers as the writer commits (backlog 149): {@link AuditFeed} opens a new reader of the audit
 * table whenever this catalog's pointer to it has moved, and closes the one it replaces, so Iceberg never finds its
 * {@code S3FileIO} unclosed (and warns, with a stack trace, as it collects it), while the S3 client every reader
 * shares stays open and the next reader reads through it.
 *
 * <p>The feed is the real one over a store double and the audit catalog's S3 client is a double serving a real
 * table's files ({@link AuditTableOverS3}), so this needs only Java.
 */
class AuditFeedReaderTest {

    private static final Instant T0 = Instant.parse("2026-11-22T07:00:00Z");
    private static final String READ = "nuna.catalog.read";

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "nuna_audit", "nuna_audit");
    private final NamespaceRecord namespace = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
            new Name(Names.normalize("audit"), "audit"), Map.of(), 1);
    private final CatalogObject events = CatalogObject.active(catalog.id(), UUID.randomUUID(), namespace.id(),
            new Name(AuditFeed.TABLE, AuditFeed.TABLE), CatalogObject.Kind.TABLE, "s3://audit/warehouse/events");
    private final AuditTableOverS3 audit = new AuditTableOverS3();
    private Pointer pointer;

    @Test
    void aReplacedReaderIsClosedAndTheSharedClientStaysOpen() {
        AuditRow first = AuditTableOverS3.row(T0.plusSeconds(10), READ, 0, 0);
        AuditRow second = AuditTableOverS3.row(T0.plusSeconds(20), READ, 0, 1);
        AuditFeed feed = new Feed();
        point(audit.append(first), 1);
        EventsReader before = feed.reader();
        assertThat(rows(feed)).containsExactly(first);

        point(audit.append(second), 2);
        EventsReader after = feed.reader();

        assertThat(after).as("the writer committed, so the feed reads the table again").isNotSameAs(before);
        assertThat(before.closed()).as("the reader replaced is closed, or Iceberg warns as it collects it")
                .isTrue();
        assertThat(audit.s3().closed()).as("closing it leaves the audit catalog's client, which every reader shares,"
                + " open").isFalse();
        assertThat(rows(feed)).as("the next reader reads through it").containsExactly(second, first);
        assertThat(feed.reader()).as("the pointer has not moved since").isSameAs(after);
        assertThat(after.closed()).isFalse();
    }

    private void point(String metadataLocation, long version) {
        pointer = new Pointer(metadataLocation, version, "0".repeat(64), new byte[0]);
    }

    private static List<AuditRow> rows(AuditFeed feed) {
        return feed.page(new EventsReader.Query("lake", Set.of(), null, List.of(), null, null, Optional.empty(), 50))
                .page().rows();
    }

    /** The feed, with the audit catalog's S3 client the double that serves the table's files. */
    private final class Feed extends AuditFeed {

        Feed() {
            super(new AuditCatalogSettings("nuna_audit", "auditors", "audit-writers"), "audit", store(), null, null,
                    Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC));
        }

        @Override
        S3Client s3(CatalogRecord auditCatalog) {
            return audit.s3();
        }
    }

    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "findNamespace" -> Optional.of(namespace)
                            .filter(n -> args[0].equals(catalog.id()) && args[1].equals(List.of("audit")));
                    case "findObject" -> Optional.of(events)
                            .filter(o -> args[0].equals(namespace.id()) && args[1].equals(AuditFeed.TABLE));
                    case "readPointer" -> Optional.ofNullable(pointer).filter(p -> args[0].equals(events.mainKey()));
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}

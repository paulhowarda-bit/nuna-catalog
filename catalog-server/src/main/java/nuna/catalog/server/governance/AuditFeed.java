package nuna.catalog.server.governance;

import nuna.audittables.EventsReader;
import nuna.catalog.core.Names;
import nuna.catalog.objectstore.CaBundle;
import nuna.catalog.server.security.AuditCatalogSettings;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Pointer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * The catalog-wide audit feed's reads (D45; backlog 125): the audit table {@code events} in the audit catalog
 * ({@code nuna.audit.tables.catalog}, in namespace {@code nuna.audit.tables.namespace}, by default {@code audit}),
 * read through the audit table writer's own reader ({@link EventsReader}; D23, amended 2026-11-22). The table is
 * found through this catalog's own pointer to it, so each read sees the snapshot the writer last committed, about a
 * minute behind the topic. Its files are read with the audit catalog's storage profile.
 *
 * <p>A day's counts read a day of rows, so they are kept for a minute and shared by every caller.
 */
@Component
class AuditFeed {

    static final String TABLE = "events";
    static final Duration SUMMARY_SPAN = Duration.ofDays(1);
    static final Duration SUMMARY_KEPT = Duration.ofMinutes(1);
    static final int BUSIEST = 10;

    /** A summary and when it was made. */
    record Counted(EventsReader.Summary summary, Instant at, Snapshot table) {
    }

    /** The table snapshot a read used. */
    record Snapshot(String name, Optional<Long> id, Optional<Instant> committedAt) {
    }

    /** One page and the snapshot it came from. */
    record Read(EventsReader.Page page, Snapshot table) {
    }

    private final AuditCatalogSettings tables;
    private final String namespace;
    private final CatalogStore store;
    private final CatalogStorage storage;
    private final StorageSettings storageSettings;
    private final Clock clock;
    private S3Client s3;
    private EventsReader reader;
    /** The metadata file {@code reader} read. */
    private String location;
    private Counted counted;

    AuditFeed(AuditCatalogSettings tables, @Value("${nuna.audit.tables.namespace:audit}") String namespace,
              CatalogStore store, CatalogStorage storage, StorageSettings storageSettings, Clock clock) {
        this.tables = tables;
        this.namespace = namespace;
        this.store = store;
        this.storage = storage;
        this.storageSettings = storageSettings;
        this.clock = clock;
    }

    /** The audit catalog, where one is named and exists; its readers may read the feed. */
    Optional<CatalogRecord> auditCatalog() {
        if (tables.catalog() == null || tables.catalog().isBlank()) {
            return Optional.empty();
        }
        return store.findCatalog(Names.normalize(tables.catalog()));
    }

    /** The catalog a feed is asked for, by its name; it need hold nothing there, as the tables' readers need not. */
    Optional<CatalogRecord> catalog(String name) {
        return store.findCatalog(Names.normalize(name));
    }

    /** One page of the feed. */
    Read page(EventsReader.Query query) {
        EventsReader events = reader();
        return new Read(events.page(query), snapshot(events));
    }

    /** The last day's counts kept from a read less than a minute ago, if there is one. */
    synchronized Optional<Counted> kept() {
        return Optional.ofNullable(counted).filter(c -> c.at().plus(SUMMARY_KEPT).isAfter(clock.instant()));
    }

    /** The last day's counts, read now. */
    Counted count() {
        EventsReader events = reader();
        Instant now = clock.instant();
        Counted fresh = new Counted(events.summary(now.minus(SUMMARY_SPAN), now, BUSIEST), now, snapshot(events));
        synchronized (this) {
            counted = fresh;
        }
        return fresh;
    }

    private static Snapshot snapshot(EventsReader events) {
        return new Snapshot(events.name(), events.snapshotId(), events.committedAt());
    }

    /**
     * The reader of the table as this catalog last committed it, opened again when the writer has committed since.
     *
     * @throws NotReady when the audit catalog, its namespace or the table is not there yet
     */
    synchronized EventsReader reader() {
        CatalogRecord catalog = auditCatalog().orElseThrow(() -> new NotReady("the audit catalog "
                + tables.catalog() + " is not configured on this server"
                + " (nuna.audit.tables.catalog)"));
        List<String> levels = Arrays.stream(namespace.split("\\.")).map(Names::normalize).toList();
        CatalogObject table = store.findNamespace(catalog.id(), levels)
                .flatMap(ns -> store.findObject(ns.id(), TABLE))
                .filter(o -> o.kind() == CatalogObject.Kind.TABLE)
                .orElseThrow(() -> new NotReady("the audit table " + catalog.name() + "." + namespace + "." + TABLE
                        + " does not exist yet: the audit table writer creates it"));
        Pointer pointer = store.readPointer(table.mainKey())
                .orElseThrow(() -> new NotReady("the audit table has no metadata yet"));
        String name = catalog.name() + "." + namespace + "." + TABLE;
        if (reader == null || !pointer.metadataLocation().equals(location)) {
            reader = EventsReader.open(name, pointer.metadataLocation(), s3(catalog));
            location = pointer.metadataLocation();
        }
        return reader;
    }

    private S3Client s3(CatalogRecord catalog) {
        if (s3 == null) {
            String profile = storage.forCatalog(catalog.id()).name();
            s3 = client(profile, storageSettings.profiles().get(profile));
        }
        return s3;
    }

    /**
     * An S3 client for a storage profile, as the archive's is made ({@code GovernanceWiring.archive}): its endpoint,
     * region and keys, and its CA bundle beside the JVM's CAs.
     */
    static S3Client client(String name, StorageSettings.Profile profile) {
        AwsCredentialsProvider credentials = profile.accessKeyId() == null
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(profile.accessKeyId(), profile.secretAccessKey()));
        S3ClientBuilder s3 = S3Client.builder()
                .region(Region.of(profile.region()))
                .credentialsProvider(credentials);
        if (profile.endpoint() != null && !profile.endpoint().isBlank()) {
            s3.endpointOverride(URI.create(profile.endpoint())).forcePathStyle(true);
        }
        if (profile.caBundle() != null) {
            try {
                CaBundle.read(profile.caBundle()).applyTo(s3);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("nuna.storage.profiles." + name + ".ca-bundle: " + e.getMessage(), e);
            }
        }
        return s3.build();
    }

    /** The feed cannot be read yet: the audit tables are not set up. */
    static final class NotReady extends RuntimeException {

        NotReady(String message) {
            super(message);
        }
    }
}

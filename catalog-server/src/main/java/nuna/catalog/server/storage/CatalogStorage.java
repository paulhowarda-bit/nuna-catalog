package nuna.catalog.server.storage;

import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.StorageSetup;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitRule;
import nuna.catalog.core.commit.TableFiles;
import nuna.catalog.objectstore.CaBundle;
import nuna.catalog.objectstore.CredentialVendor;
import nuna.catalog.objectstore.DeletePolicy;
import nuna.catalog.objectstore.MetadataFiles;
import nuna.catalog.objectstore.ProfileOverlap;
import nuna.catalog.objectstore.ProfileTableFiles;
import nuna.catalog.objectstore.StorageProfile;
import nuna.catalog.objectstore.StorageRules;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.tracing.ObservedTableFiles;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.WriteResult;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * Each catalog's storage (D21, D28): the profiles configured on this server, written to the store, and
 * the catalogs bound to them. At startup it refuses overlapping profiles, plain-http endpoints, CA bundles it
 * cannot read (backlog 21) and KMS keys that are not a key ARN in an AWS S3 profile's region (backlog 23), records
 * changed profiles, creates declared catalogs, and refuses a declared catalog the store binds differently.
 * The catalog never calls KMS: encryption at rest is the profile's KMS key, which S3 uses for each write that names
 * it (D26), or else the bucket's default key, and the key set is recorded.
 * Each profile's requests to its object store are observed (docs/observability/spans.md).
 */
public final class CatalogStorage implements AutoCloseable {

    /** One profile, ready to use: its guardrails, metadata files, commit rules and credential vendor. */
    public record Bound(String name, UUID id, StorageProfile profile, TableFiles files, List<CommitRule> rules,
                        CredentialVendor vendor) {
    }

    private static final Actor CONFIGURATION = new Actor("nuna-configuration", null, "catalog-server", "startup");

    private final CatalogStore store;
    private final Map<UUID, Bound> profiles = new LinkedHashMap<>();
    private final List<AutoCloseable> resources = new java.util.ArrayList<>();
    private final Map<UUID, Bound> catalogs = new ConcurrentHashMap<>();

    public CatalogStorage(StorageSettings settings, CatalogStore store, RestSettings rest, Clock clock,
                          ObservationRegistry observations) {
        this.store = store;
        Map<String, StorageProfile> parsed = new LinkedHashMap<>();
        Map<String, Optional<CaBundle>> caBundles = new LinkedHashMap<>();
        settings.profiles().forEach((name, profile) -> {
            parsed.put(name, parse(name, profile, settings.requireTls()));
            caBundles.put(name, caBundle(name, profile));
        });
        ProfileOverlap.requireDisjoint(parsed);
        settings.profiles().forEach((name, config) -> {
            StorageProfile profile = parsed.get(name);
            AwsCredentialsProvider credentials = config.accessKeyId() == null
                    ? DefaultCredentialsProvider.builder().build()
                    : StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey()));
            MetadataFiles files = new MetadataFiles(profile, credentials, caBundles.get(name));
            CredentialVendor vendor = new CredentialVendor(profile, Optional.ofNullable(config.stsRole()), credentials,
                    settings.credentialLifetime(), caBundles.get(name));
            resources.add(files);
            resources.add(vendor);
            TableFiles tableFiles = new ObservedTableFiles(new ProfileTableFiles(profile, files), name, observations);
            Bound bound = new Bound(name, profileId(name), profile, tableFiles, StorageRules.standard(tableFiles),
                    vendor);
            profiles.put(bound.id(), bound);
            record(bound, config, rest, clock);
        });
        settings.catalogs().forEach((name, catalog) -> declare(name, catalog, settings, rest, clock));
    }

    /** Every profile this server configures, in the order configured. */
    public List<Bound> profiles() {
        return List.copyOf(profiles.values());
    }

    /** The profile a catalog's tables live in. */
    public Bound forCatalog(UUID catalogId) {
        return catalogs.computeIfAbsent(catalogId, id -> {
            CatalogSettings settings = store.catalogSettings(id).orElseThrow(
                    () -> new IllegalStateException("catalog " + id + " has no storage profile"));
            Bound bound = profiles.get(settings.storageProfileId());
            if (bound == null) {
                throw new IllegalStateException("catalog " + id + " uses storage profile "
                        + settings.storageProfileId() + ", which this server does not configure");
            }
            return bound;
        });
    }

    @Override
    public void close() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    /** A profile's id is fixed by its name, so every server derives the same one. */
    static UUID profileId(String name) {
        return UUID.nameUUIDFromBytes(("nuna-storage-profile:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static StorageProfile parse(String name, StorageSettings.Profile config, boolean requireTls) {
        if (config.kind() == null || config.endpoint() == null || config.tableRoot() == null
                || config.allowedPrefixes() == null || config.allowedPrefixes().isEmpty()) {
            throw new IllegalStateException("nuna.storage.profiles." + name
                    + " needs kind, endpoint, table-root and allowed-prefixes");
        }
        if (requireTls && !config.endpoint().startsWith("https://")) {
            throw new IllegalStateException("nuna.storage.profiles." + name + ".endpoint must use https, got "
                    + config.endpoint());
        }
        if (config.maintenancePrincipal() != null) {
            try {
                DeletePolicy.requirePrincipal(config.kind(), config.maintenancePrincipal());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("nuna.storage.profiles." + name + ".maintenance-principal: "
                        + e.getMessage(), e);
            }
        }
        if (config.kmsKey() != null) {
            try {
                StorageProfile.requireKmsKey(config.kind(), config.region(), config.kmsKey());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("nuna.storage.profiles." + name + ".kms-key: " + e.getMessage(), e);
            }
        }
        return StorageProfile.of(config.kind(), config.endpoint(), config.region(), config.tableRoot(),
                config.allowedPrefixes(), Optional.ofNullable(config.kmsKey()));
    }

    /** The profile's CA bundle, read now so that one the server cannot use stops it starting. */
    private static Optional<CaBundle> caBundle(String name, StorageSettings.Profile config) {
        if (config.caBundle() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(CaBundle.read(config.caBundle()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("nuna.storage.profiles." + name + ".ca-bundle: " + e.getMessage(), e);
        }
    }

    private void record(Bound bound, StorageSettings.Profile config, RestSettings rest, Clock clock) {
        nuna.catalog.store.StorageProfile wanted = new nuna.catalog.store.StorageProfile(bound.id(), bound.name(),
                switch (config.kind()) {
                    case S3 -> nuna.catalog.store.StorageProfile.Kind.S3;
                    case MINIO -> nuna.catalog.store.StorageProfile.Kind.MINIO;
                    case CEPH -> nuna.catalog.store.StorageProfile.Kind.CEPH;
                },
                config.kind() == StoreKind.S3 ? Optional.empty() : Optional.of(config.endpoint()),
                config.stsRole() == null ? "" : config.stsRole(), config.allowedPrefixes());
        if (store.readStorageProfile(bound.id()).equals(Optional.of(wanted))) {
            return;
        }
        Map<String, String> set = new LinkedHashMap<>();
        set.put("kind", config.kind().name());
        set.put("endpoint", config.endpoint());
        set.put("sts-role", wanted.stsRole());
        set.put("allowed-prefixes", String.join(",", config.allowedPrefixes()));
        store.putStorageProfile(wanted, change(new StorageSetup(StorageSetup.Action.PROFILE_SET, bound.id(),
                bound.name(), set, CONFIGURATION), rest, clock));
    }

    private void declare(String name, StorageSettings.Catalog catalog, StorageSettings settings, RestSettings rest,
                         Clock clock) {
        if (catalog.profile() == null || catalog.kmsKeySet() == null || catalog.legalEntity() == null) {
            throw new IllegalStateException("nuna.storage.catalogs." + name
                    + " needs profile, kms-key-set and legal-entity");
        }
        if (!settings.profiles().containsKey(catalog.profile())) {
            throw new IllegalStateException("nuna.storage.catalogs." + name + ".profile names no profile: "
                    + catalog.profile());
        }
        CatalogSettings wanted = new CatalogSettings(profileId(catalog.profile()), catalog.kmsKeySet(),
                catalog.legalEntity());
        Optional<CatalogRecord> existing = store.findCatalog(Names.normalize(name));
        if (existing.isEmpty()) {
            CatalogRecord record = new CatalogRecord(UUID.randomUUID(), Names.normalize(name), name);
            WriteResult result = store.createCatalog(record, wanted, change(new StorageSetup(
                    StorageSetup.Action.CATALOG_CREATED, record.id(), name, Map.of("storage-profile", catalog.profile(),
                    "kms-key-set", catalog.kmsKeySet(), "legal-entity", catalog.legalEntity()), CONFIGURATION),
                    rest, clock));
            if (result instanceof WriteResult.Applied) {
                return;
            }
            // Another server created it first: it must hold what this one would have.
            existing = store.findCatalog(Names.normalize(name));
        }
        CatalogRecord record = existing.orElseThrow(() -> new IllegalStateException("catalog " + name
                + " could not be created"));
        Optional<CatalogSettings> held = store.catalogSettings(record.id());
        if (!held.equals(Optional.of(wanted))) {
            throw new IllegalStateException("catalog " + name + " is bound to " + held.map(Object::toString)
                    .orElse("no storage profile") + " in the store, but configured as " + wanted
                    + "; a catalog's boundary never changes (D28)");
        }
    }

    private static Change change(StorageSetup data, RestSettings rest, Clock clock) {
        CatalogEvent event = new CatalogEvent(UUID.randomUUID(), rest.auditSource(), clock.instant(), data);
        return new Change(UUID.randomUUID(), event.time(), List.of(new OutboxEvent(data.type().typeName(),
                new String(EventCodec.encode(event), StandardCharsets.UTF_8))), Optional.empty());
    }
}

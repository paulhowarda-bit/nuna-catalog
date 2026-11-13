package keel.catalog.server.storage;

import keel.catalog.audit.Actor;
import keel.catalog.audit.CatalogEvent;
import keel.catalog.audit.EventCodec;
import keel.catalog.audit.StorageSetup;
import keel.catalog.core.Names;
import keel.catalog.core.commit.CommitRule;
import keel.catalog.core.commit.TableFiles;
import keel.catalog.objectstore.CredentialVendor;
import keel.catalog.objectstore.DeletePolicy;
import keel.catalog.objectstore.MetadataFiles;
import keel.catalog.objectstore.ProfileOverlap;
import keel.catalog.objectstore.ProfileTableFiles;
import keel.catalog.objectstore.StorageProfile;
import keel.catalog.objectstore.StorageRules;
import keel.catalog.objectstore.StoreKind;
import keel.catalog.server.rest.RestSettings;
import keel.catalog.store.CatalogRecord;
import keel.catalog.store.CatalogSettings;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.Change;
import keel.catalog.store.OutboxEvent;
import keel.catalog.store.WriteResult;
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
 * the catalogs bound to them. At startup it refuses overlapping profiles and plain-http endpoints, records
 * changed profiles, creates declared catalogs, and refuses a declared catalog the store binds differently.
 * The catalog never calls KMS: encryption at rest is the bucket's default key, and the key set is recorded.
 */
public final class CatalogStorage implements AutoCloseable {

    /** One profile, ready to use: its guardrails, metadata files, commit rules and credential vendor. */
    public record Bound(String name, UUID id, StorageProfile profile, TableFiles files, List<CommitRule> rules,
                        CredentialVendor vendor) {
    }

    private static final Actor CONFIGURATION = new Actor("keel-configuration", null, "catalog-server", "startup");

    private final CatalogStore store;
    private final Map<UUID, Bound> profiles = new LinkedHashMap<>();
    private final List<AutoCloseable> resources = new java.util.ArrayList<>();
    private final Map<UUID, Bound> catalogs = new ConcurrentHashMap<>();

    public CatalogStorage(StorageSettings settings, CatalogStore store, RestSettings rest, Clock clock) {
        this.store = store;
        Map<String, StorageProfile> parsed = new LinkedHashMap<>();
        settings.profiles().forEach((name, profile) -> parsed.put(name, parse(name, profile, settings.requireTls())));
        ProfileOverlap.requireDisjoint(parsed);
        settings.profiles().forEach((name, config) -> {
            StorageProfile profile = parsed.get(name);
            AwsCredentialsProvider credentials = config.accessKeyId() == null
                    ? DefaultCredentialsProvider.builder().build()
                    : StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey()));
            MetadataFiles files = new MetadataFiles(profile, credentials);
            CredentialVendor vendor = new CredentialVendor(profile, Optional.ofNullable(config.stsRole()), credentials,
                    settings.credentialLifetime());
            resources.add(files);
            resources.add(vendor);
            TableFiles tableFiles = new ProfileTableFiles(profile, files);
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
        return UUID.nameUUIDFromBytes(("keel-storage-profile:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static StorageProfile parse(String name, StorageSettings.Profile config, boolean requireTls) {
        if (config.kind() == null || config.endpoint() == null || config.tableRoot() == null
                || config.allowedPrefixes() == null || config.allowedPrefixes().isEmpty()) {
            throw new IllegalStateException("keel.storage.profiles." + name
                    + " needs kind, endpoint, table-root and allowed-prefixes");
        }
        if (requireTls && !config.endpoint().startsWith("https://")) {
            throw new IllegalStateException("keel.storage.profiles." + name + ".endpoint must use https, got "
                    + config.endpoint());
        }
        if (config.maintenancePrincipal() != null) {
            try {
                DeletePolicy.requirePrincipal(config.kind(), config.maintenancePrincipal());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("keel.storage.profiles." + name + ".maintenance-principal: "
                        + e.getMessage(), e);
            }
        }
        return StorageProfile.of(config.kind(), config.endpoint(), config.region(), config.tableRoot(),
                config.allowedPrefixes());
    }

    private void record(Bound bound, StorageSettings.Profile config, RestSettings rest, Clock clock) {
        keel.catalog.store.StorageProfile wanted = new keel.catalog.store.StorageProfile(bound.id(), bound.name(),
                switch (config.kind()) {
                    case S3 -> keel.catalog.store.StorageProfile.Kind.S3;
                    case MINIO -> keel.catalog.store.StorageProfile.Kind.MINIO;
                    case CEPH -> keel.catalog.store.StorageProfile.Kind.CEPH;
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
            throw new IllegalStateException("keel.storage.catalogs." + name
                    + " needs profile, kms-key-set and legal-entity");
        }
        if (!settings.profiles().containsKey(catalog.profile())) {
            throw new IllegalStateException("keel.storage.catalogs." + name + ".profile names no profile: "
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

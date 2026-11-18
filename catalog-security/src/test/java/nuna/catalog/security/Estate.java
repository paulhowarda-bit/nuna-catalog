package nuna.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.ObjectCreation;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.WriteResult;
import nuna.catalog.store.sql.SqlCatalogStore;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** A fresh catalog on a shared PostgreSQL, with helpers to build namespaces, tables, grants and governance. */
final class Estate {

    private static final String ROOT = "s3://estate/tables";
    private static CatalogStore shared;

    final CatalogStore store = shared();
    final UUID catalogId = UUID.randomUUID();

    Estate() {
        UUID profileId = UUID.randomUUID();
        assertThat(store.putStorageProfile(new StorageProfile(profileId, "p-" + profileId, StorageProfile.Kind.CEPH,
                Optional.of("https://rgw.internal:8443"), "arn:aws:iam::1:role/catalog", List.of(ROOT)), change()))
                .isEqualTo(WriteResult.APPLIED);
        assertThat(store.createCatalog(new CatalogRecord(catalogId, "c-" + catalogId, "C-" + catalogId),
                new CatalogSettings(profileId, "kms-default", "acme"), change())).isEqualTo(WriteResult.APPLIED);
    }

    static synchronized CatalogStore shared() {
        if (shared == null) {
            PostgreSQLContainer postgres =
                    new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image", "postgres:18.7")));
            postgres.start();
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setUrl(postgres.getJdbcUrl());
            dataSource.setUser(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            shared = new SqlCatalogStore(dataSource);
            shared.migrate();
        }
        return shared;
    }

    static Change change() {
        return new Change(UUID.randomUUID(), Instant.now(), List.of(new OutboxEvent("test.setup", "{}")),
                Optional.empty());
    }

    Authorizer.Target catalog() {
        return new Authorizer.Target.Catalog(catalogId);
    }

    NamespaceRecord namespace(String name) {
        return namespace(catalogId, name);
    }

    NamespaceRecord namespace(NamespaceRecord parent, String name) {
        return namespace(parent.id(), name);
    }

    private NamespaceRecord namespace(UUID parentId, String name) {
        NamespaceRecord record = new NamespaceRecord(catalogId, UUID.randomUUID(), parentId,
                new Name(name.toLowerCase(Locale.ROOT), name), Map.of(), 1);
        assertThat(store.createNamespace(record, change())).isEqualTo(WriteResult.APPLIED);
        return record;
    }

    CatalogObject table(NamespaceRecord namespace, String name) {
        return object(namespace, name, CatalogObject.Kind.TABLE);
    }

    CatalogObject view(NamespaceRecord namespace, String name) {
        return object(namespace, name, CatalogObject.Kind.VIEW);
    }

    private CatalogObject object(NamespaceRecord namespace, String name, CatalogObject.Kind kind) {
        UUID id = UUID.randomUUID();
        CatalogObject object = CatalogObject.active(catalogId, id, namespace.id(),
                new Name(name.toLowerCase(Locale.ROOT), name), kind, ROOT + "/" + id);
        Pointer first = new Pointer(ROOT + "/" + id + "/metadata/v1.metadata.json", 1, "0".repeat(64), new byte[0]);
        CommitBatch batch = new CommitBatch(UUID.randomUUID(), Instant.now(), List.of(new PointerSwap(object.mainKey(),
                Optional.empty(), first, new JournalEntry("setup", null, "test", "[]", "[]"))),
                List.of(new OutboxEvent("test.setup", "{}")), Optional.empty());
        assertThat(store.createObject(new ObjectCreation(object, batch))).isEqualTo(WriteResult.APPLIED);
        return object;
    }

    void grant(Securable on, String group, Privilege privilege) {
        assertThat(store.putGrant(new Grant(catalogId, on, group, privilege.wireName()), "setup", change()))
                .isEqualTo(WriteResult.APPLIED);
    }

    void grantOnCatalog(String group, Privilege privilege) {
        grant(new Securable(Securable.Kind.CATALOG, catalogId), group, privilege);
    }

    void grant(NamespaceRecord on, String group, Privilege privilege) {
        grant(new Securable(Securable.Kind.NAMESPACE, on.id()), group, privilege);
    }

    void grant(CatalogObject on, String group, Privilege privilege) {
        grant(on.securable(), group, privilege);
    }

    void attribute(Securable target, GovernanceAttribute.Kind kind, String value) {
        assertThat(store.putAttribute(new GovernanceAttribute(target, kind, value, "governance-test", "steward",
                Instant.now()), change())).isEqualTo(WriteResult.APPLIED);
    }

    void regulated(NamespaceRecord namespace) {
        attribute(new Securable(Securable.Kind.NAMESPACE, namespace.id()), GovernanceAttribute.Kind.REGULATED, "true");
    }

    void classify(CatalogObject object, String classification) {
        attribute(object.securable(), GovernanceAttribute.Kind.CLASSIFICATION, classification);
    }

    void flagRowColumnPolicy(CatalogObject table) {
        attribute(table.securable(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY, "true");
    }
}

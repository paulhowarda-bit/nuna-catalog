package fidice.catalog.store.sql;

import static fidice.catalog.store.suite.Changes.change;
import static fidice.catalog.store.suite.Changes.creation;
import static fidice.catalog.store.suite.Changes.name;
import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.CatalogSettings;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.Name;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.PageRequest;
import fidice.catalog.store.StorageProfile;
import fidice.catalog.store.WriteResult;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Names compare and sort as the bytes of their normalized form (docs/storage-design.md 9 (d)):
 * PostgreSQL by COLLATE "C", CockroachDB by its STRING comparison. A linguistic
 * collation would list "ab" before "a-z", since it passes over the hyphen, and "ä" between "a"
 * and "b", and an accent-blind one would take "a" and "ä" for one name. The shared suite's names
 * sort the same either way, so this checks names that do not.
 */
final class ByteOrderNames {

    private static final List<String> NAMES = List.of("b", "ä", "ab", "a-z", "a_c", "Z9", "a");
    private static final Comparator<String> BYTES =
            (a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    private ByteOrderNames() {
    }

    static void run(CatalogStore store) {
        List<String> inByteOrder = NAMES.stream().map(display -> name(display).normalized()).sorted(BYTES).toList();
        assertThat(inByteOrder).as("the names tell byte order from a linguistic order")
                .isNotEqualTo(inByteOrder.stream().sorted(Collator.getInstance(Locale.US)).toList());

        NamespaceRecord parent = namespace(store, catalog(store), null, "byte-order");
        for (String display : NAMES) {
            namespace(store, parent.catalogId(), parent, display);
            CatalogObject table = CatalogObject.active(parent.catalogId(), UUID.randomUUID(), parent.id(), name(display),
                    CatalogObject.Kind.TABLE, "s3://suite-warehouse/" + UUID.randomUUID());
            assertThat(store.createObject(creation(table))).as("table %s", display).isEqualTo(WriteResult.APPLIED);
        }

        assertThat(store.listNamespaces(parent.id(), PageRequest.first(100)).items())
                .extracting(namespace -> namespace.name().normalized()).containsExactlyElementsOf(inByteOrder);
        assertThat(store.listObjects(parent.id(), CatalogObject.Kind.TABLE, CatalogObject.State.ACTIVE,
                PageRequest.first(100)).items()).extracting(object -> object.name().normalized())
                .containsExactlyElementsOf(inByteOrder);
        assertThat(store.findObject(parent.id(), "ä")).map(object -> object.name().display()).contains("ä");
        assertThat(store.findObject(parent.id(), "a")).map(object -> object.name().display()).contains("a");
    }

    private static UUID catalog(CatalogStore store) {
        StorageProfile profile = new StorageProfile(UUID.randomUUID(), "profile", StorageProfile.Kind.MINIO,
                Optional.of("https://minio.suite:9000"), "arn:aws:iam::000000000000:role/suite",
                List.of("s3://suite-warehouse/"));
        assertThat(store.putStorageProfile(profile, change())).isEqualTo(WriteResult.APPLIED);
        UUID id = UUID.randomUUID();
        Name name = name("Catalog-" + id);
        assertThat(store.createCatalog(new CatalogRecord(id, name.normalized(), name.display()),
                new CatalogSettings(profile.id(), "kms-suite", "suite-entity"), change())).isEqualTo(WriteResult.APPLIED);
        return id;
    }

    private static NamespaceRecord namespace(CatalogStore store, UUID catalogId, NamespaceRecord parent, String display) {
        NamespaceRecord namespace = new NamespaceRecord(catalogId, UUID.randomUUID(), parent == null ? catalogId
                : parent.id(), name(display), Map.of(), 1);
        assertThat(store.createNamespace(namespace, change())).as("namespace %s", display).isEqualTo(WriteResult.APPLIED);
        return namespace;
    }
}

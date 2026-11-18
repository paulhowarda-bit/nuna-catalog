package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Changes.change;
import static nuna.catalog.store.suite.Changes.creation;
import static nuna.catalog.store.suite.Changes.name;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.CommitHook;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.WriteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * Base of the group suites: one per operation group of {@link CatalogStore}, plus suite tests 7
 * to 10 (docs/storage-design.md section 9). A backend runs each as a {@code @Nested} class inside
 * its {@link CommitSuite}, which starts the backend and restores it after every test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class GroupSuite {

    /** The backend the enclosing commit suite started. */
    protected abstract CatalogStoreUnderTest underTest();

    protected CatalogStore store;
    protected Faults faults;
    protected final ExecutorService background = Executors.newCachedThreadPool();

    @BeforeAll
    void openStore() {
        store = underTest().store(CommitHook.NONE);
        faults = underTest().faults();
    }

    @AfterAll
    void stopThreads() {
        background.shutdownNow();
    }

    /** A new catalog with its own storage profile; returns its id. */
    protected UUID newCatalog() {
        StorageProfile profile = new StorageProfile(UUID.randomUUID(), "profile", StorageProfile.Kind.MINIO,
                Optional.of("https://minio.suite:9000"), "arn:aws:iam::000000000000:role/suite",
                List.of("s3://suite-warehouse/"));
        assertApplied(store.putStorageProfile(profile, change()));
        UUID id = UUID.randomUUID();
        Name name = name("Catalog-" + id);
        assertApplied(store.createCatalog(new CatalogRecord(id, name.normalized(), name.display()),
                new CatalogSettings(profile.id(), "kms-suite", "suite-entity"), change()));
        return id;
    }

    protected NamespaceRecord newNamespace(UUID catalogId, String display) {
        return newNamespace(catalogId, catalogId, display);
    }

    protected NamespaceRecord newNamespace(NamespaceRecord parent, String display) {
        return newNamespace(parent.catalogId(), parent.id(), display);
    }

    private NamespaceRecord newNamespace(UUID catalogId, UUID parentId, String display) {
        NamespaceRecord namespace = new NamespaceRecord(catalogId, UUID.randomUUID(), parentId, name(display), Map.of(), 1);
        assertApplied(store.createNamespace(namespace, change()));
        return namespace;
    }

    protected CatalogObject newObject(NamespaceRecord namespace, String display, CatalogObject.Kind kind) {
        CatalogObject object = CatalogObject.active(namespace.catalogId(), UUID.randomUUID(), namespace.id(),
                name(display), kind, "s3://suite-warehouse/" + UUID.randomUUID());
        assertApplied(store.createObject(creation(object)));
        return object;
    }

    protected CatalogObject newTable(NamespaceRecord namespace, String display) {
        return newObject(namespace, display, CatalogObject.Kind.TABLE);
    }

    protected static void assertApplied(WriteResult result) {
        assertThat(result).isEqualTo(WriteResult.APPLIED);
    }

    /**
     * Every item of a listing, page by page, checking that each page but the last is full. Gives
     * up after 10,000 pages, so a token that does not advance fails instead of looping.
     */
    protected static <T> List<T> allPages(int size, Function<PageRequest, Page<T>> list) {
        List<T> items = new ArrayList<>();
        PageRequest request = PageRequest.first(size);
        for (int pages = 0; pages < 10_000; pages++) {
            Page<T> page = list.apply(request);
            items.addAll(page.items());
            if (page.nextToken().isEmpty()) {
                return items;
            }
            assertThat(page.items()).as("a page before the last is full").hasSize(size);
            request = request.after(page);
        }
        throw new AssertionError("the listing did not end within 10,000 pages");
    }
}

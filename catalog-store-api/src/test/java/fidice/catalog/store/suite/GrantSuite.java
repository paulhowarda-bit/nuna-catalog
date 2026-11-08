package fidice.catalog.store.suite;

import static fidice.catalog.store.suite.Changes.change;
import static fidice.catalog.store.suite.Changes.fullChange;
import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.Change;
import fidice.catalog.store.Grant;
import fidice.catalog.store.GrantRecord;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.Securable;
import fidice.catalog.store.WriteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Grants to groups: put and revoke with their outbox rows, the batch check, and both listings. */
public abstract class GrantSuite extends GroupSuite {

    @Test
    @DisplayName("Grants: a put writes the grant and its outbox row; a second put is Unchanged and writes nothing")
    void putIsIdempotent() {
        UUID catalog = newCatalog();
        Grant grant = new Grant(catalog, new Securable(Securable.Kind.CATALOG, catalog), "analysts", "describe");
        Change put = fullChange();
        Change again = fullChange();

        assertThat(store.putGrant(grant, "grant-admin", put)).isEqualTo(WriteResult.APPLIED);
        assertThat(store.putGrant(grant, "someone-else", again)).isEqualTo(WriteResult.UNCHANGED);

        assertThat(underTest().changeRows(put)).isEqualTo(WrittenRows.allOf(put));
        assertThat(underTest().changeRows(again)).isEqualTo(WrittenRows.NONE);
        assertThat(store.grantsFor(Set.of(grant.securable()), Set.of("analysts")))
                .containsExactly(new GrantRecord(grant, "grant-admin", put.at()));
    }

    @Test
    @DisplayName("Grants: a revoke deletes the grant and writes its outbox row; revoking a missing grant writes nothing")
    void revokeWritesItsOutboxRow() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        Grant grant = new Grant(catalog, new Securable(Securable.Kind.NAMESPACE, namespace.id()), "analysts", "select");
        assertThat(store.putGrant(grant, "grant-admin", change())).isEqualTo(WriteResult.APPLIED);
        Change revoke = fullChange();
        Change missing = fullChange();

        assertThat(store.revokeGrant(grant, revoke)).isEqualTo(WriteResult.APPLIED);
        assertThat(store.revokeGrant(grant, missing)).isEqualTo(new WriteResult.Missing(WriteResult.Subject.GRANT));

        assertThat(underTest().changeRows(revoke)).isEqualTo(WrittenRows.allOf(revoke));
        assertThat(underTest().changeRows(missing)).isEqualTo(WrittenRows.NONE);
        assertThat(store.grantsFor(Set.of(grant.securable()), Set.of("analysts"))).isEmpty();
    }

    @Test
    @DisplayName("Grants: a grant on a securable that does not exist is Missing and writes nothing")
    void grantOnAMissingSecurable() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject view = newObject(namespace, "v", CatalogObject.Kind.VIEW);
        Change change = fullChange();

        assertThat(store.putGrant(new Grant(catalog, new Securable(Securable.Kind.TABLE, UUID.randomUUID()), "g", "select"),
                "admin", change)).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.putGrant(new Grant(catalog, new Securable(Securable.Kind.TABLE, view.id()), "g", "select"),
                "admin", change())).as("a view is not a table").isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.putGrant(new Grant(catalog, new Securable(Securable.Kind.NAMESPACE, UUID.randomUUID()), "g", "select"),
                "admin", change())).isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));
        assertThat(store.putGrant(new Grant(catalog, new Securable(Securable.Kind.CATALOG, UUID.randomUUID()), "g", "select"),
                "admin", change())).isEqualTo(new WriteResult.Missing(WriteResult.Subject.CATALOG));
        assertThat(underTest().changeRows(change)).isEqualTo(WrittenRows.NONE);
    }

    @Test
    @DisplayName("Grants: the batch check returns exactly the grants on the asked securables to the asked groups")
    void batchCheckReturnsExactlyTheMatches() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        Securable onCatalog = new Securable(Securable.Kind.CATALOG, catalog);
        Securable onNamespace = new Securable(Securable.Kind.NAMESPACE, namespace.id());
        List<Grant> grants = List.of(
                new Grant(catalog, onCatalog, "analysts", "describe"),
                new Grant(catalog, onNamespace, "analysts", "select"),
                new Grant(catalog, onNamespace, "engineers", "write"),
                new Grant(catalog, table.securable(), "engineers", "modify"),
                new Grant(catalog, table.securable(), "auditors", "select"));
        for (Grant grant : grants) {
            assertThat(store.putGrant(grant, "admin", change())).isEqualTo(WriteResult.APPLIED);
        }

        List<GrantRecord> found = store.grantsFor(Set.of(onCatalog, onNamespace, table.securable()), Set.of("analysts", "engineers"));

        assertThat(found).extracting(GrantRecord::grant).containsExactlyInAnyOrder(grants.get(0), grants.get(1),
                grants.get(2), grants.get(3));
        assertThat(store.grantsFor(Set.of(table.securable()), Set.of("nobody"))).isEmpty();
    }

    @Test
    @DisplayName("Grants: listing by groups and by securable pages through every grant once, in order")
    void listingsPageInOrder() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        List<Grant> analysts = new ArrayList<>();
        List<Grant> onNamespace = new ArrayList<>();
        Securable namespaceSecurable = new Securable(Securable.Kind.NAMESPACE, namespace.id());
        for (int i = 0; i < 6; i++) {
            CatalogObject table = newTable(namespace, "t" + i);
            for (String privilege : List.of("describe", "select")) {
                Grant grant = new Grant(catalog, table.securable(), "analysts", privilege);
                assertThat(store.putGrant(grant, "admin", change())).isEqualTo(WriteResult.APPLIED);
                analysts.add(grant);
            }
            Grant grant = new Grant(catalog, namespaceSecurable, "group-" + i, "select");
            assertThat(store.putGrant(grant, "admin", change())).isEqualTo(WriteResult.APPLIED);
            onNamespace.add(grant);
        }
        UUID otherCatalog = newCatalog();
        Grant other = new Grant(otherCatalog, new Securable(Securable.Kind.CATALOG, otherCatalog), "analysts", "x");
        assertThat(store.putGrant(other, "admin", change())).isEqualTo(WriteResult.APPLIED);

        List<Grant> byGroups = allPages(5, page -> store.listGrantsOfGroups(catalog, Set.of("analysts", "group-2"), page))
                .stream().map(GrantRecord::grant).toList();
        List<Grant> bySecurable = allPages(4, page -> store.listGrantsOn(namespaceSecurable, page))
                .stream().map(GrantRecord::grant).toList();

        List<Grant> expected = new ArrayList<>(analysts);
        expected.add(onNamespace.get(2));
        assertThat(byGroups).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected).doesNotContain(other);
        assertThat(byGroups).extracting(Grant::group).isSorted();
        assertThat(bySecurable).containsExactlyElementsOf(onNamespace);
    }

    @Test
    @DisplayName("Grants: of 8 concurrent puts of one grant exactly one applies, the rest are Unchanged")
    void concurrentPutsOfOneGrant() throws Exception {
        UUID catalog = newCatalog();
        Grant grant = new Grant(catalog, new Securable(Securable.Kind.CATALOG, catalog), "racers", "select");
        CyclicBarrier start = new CyclicBarrier(8);
        List<Future<WriteResult>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(background.submit(() -> {
                start.await();
                return store.putGrant(grant, "admin", change());
            }));
        }
        List<WriteResult> outcomes = new ArrayList<>();
        for (Future<WriteResult> result : results) {
            outcomes.add(result.get(60, TimeUnit.SECONDS));
        }

        assertThat(outcomes).filteredOn(WriteResult.APPLIED::equals).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.equals(WriteResult.APPLIED)).containsOnly(WriteResult.UNCHANGED);
    }
}

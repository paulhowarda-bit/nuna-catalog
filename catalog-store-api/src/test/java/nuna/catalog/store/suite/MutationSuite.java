package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Changes.change;
import static nuna.catalog.store.suite.Changes.fullChange;
import static nuna.catalog.store.suite.Changes.name;
import static nuna.catalog.store.suite.Changes.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PendingDeletion;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerHold;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefLimits;
import nuna.catalog.store.RefLock;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.TrustedEngine;
import nuna.catalog.store.WriteResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * Suite tests 2 and 3 for every non-commit mutation: it writes exactly its change records, or
 * nothing; a lost answer is unknown and carries the change id; a link cut just before COMMIT is
 * unknown and leaves nothing. A mutation that skips the commit hook, the checked COMMIT or a
 * change record fails here. The two commits that lock a ref, a commit on a branch and a merge
 * (backlog 103), run here too, as batches with the change's id, time and records.
 */
public abstract class MutationSuite extends GroupSuite {

    /** A mutation whose fixtures exist; it applies once, through {@code store}, as {@code change}. */
    @FunctionalInterface
    private interface Ready {
        WriteResult apply(CatalogStore store, Change change);
    }

    private record Mutation(String name, Supplier<Ready> prepare) {
    }

    private List<Mutation> mutations() {
        return List.of(
                new Mutation("createCatalog", () -> {
                    StorageProfile profile = profile();
                    UUID id = UUID.randomUUID();
                    return (target, change) -> target.createCatalog(new CatalogRecord(id, "m-" + id, "M-" + id),
                            new CatalogSettings(profile.id(), "kms", "entity"), change);
                }),
                new Mutation("putStorageProfile", () -> (target, change) -> target.putStorageProfile(
                        new StorageProfile(UUID.randomUUID(), "p", StorageProfile.Kind.MINIO, Optional.of("https://m"),
                                "role", List.of("s3://m/"), Optional.empty()), change)),
                new Mutation("createNamespace", () -> {
                    UUID catalog = newCatalog();
                    return (target, change) -> target.createNamespace(new NamespaceRecord(catalog, UUID.randomUUID(),
                            catalog, name("ns"), Map.of("owner", "a"), 1), change);
                }),
                new Mutation("updateNamespaceProperties", () -> {
                    NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
                    return (target, change) -> target.updateNamespaceProperties(namespace.id(), 1, Map.of("k", "v"), change);
                }),
                new Mutation("dropNamespace", () -> {
                    NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
                    return (target, change) -> target.dropNamespace(namespace.id(), change);
                }),
                onTable("renameObject", table -> (target, change) -> target.renameObject(table.id(), table.namespaceId(),
                        name("renamed"), change)),
                onTable("dropObject", table -> (target, change) -> target.dropObject(table.id(),
                        change.at().plus(Duration.ofDays(30)), change)),
                onTable("undropObject", table -> {
                    assertApplied(store.dropObject(table.id(), now().plus(Duration.ofDays(30)), change()));
                    return (target, change) -> target.undropObject(table.id(), table.namespaceId(), name("back"), change);
                }),
                onTable("unregisterObject", table -> {
                    Pointer current = store.readPointer(table.mainKey()).orElseThrow();
                    return (target, change) -> target.unregisterObject(table.id(), current, change);
                }),
                onTable("claimPurge", table -> {
                    assertApplied(store.dropObject(table.id(), now().minusSeconds(60), change()));
                    return (target, change) -> target.claimPurge(table.id(), "worker", change);
                }),
                onTable("completePurge", table -> {
                    assertApplied(store.dropObject(table.id(), now().minusSeconds(60), change()));
                    assertApplied(store.claimPurge(table.id(), "worker", change()));
                    return (target, change) -> target.completePurge(table.id(), "worker", change);
                }),
                onTable("recordDeletions", table -> (target, change) -> target.recordDeletions(table.id(),
                        List.of(table.locationRoot() + "/data/a.parquet"), change.at().plus(Duration.ofDays(7)), change)),
                onTable("completeDeletions", table -> {
                    assertApplied(store.recordDeletions(table.id(), List.of(table.locationRoot() + "/data/a.parquet"),
                            now().minusSeconds(60), change()));
                    List<UUID> ids = store.listDueDeletions(now(), PageRequest.first(1000)).items().stream()
                            .filter(deletion -> deletion.objectId().equals(table.id())).map(PendingDeletion::id).toList();
                    return (target, change) -> target.completeDeletions(table.id(), ids, change);
                }),
                new Mutation("putGrant", () -> {
                    UUID catalog = newCatalog();
                    Grant grant = new Grant(catalog, new Securable(Securable.Kind.CATALOG, catalog), "g", "select");
                    return (target, change) -> target.putGrant(grant, "admin", change);
                }),
                new Mutation("revokeGrant", () -> {
                    UUID catalog = newCatalog();
                    Grant grant = new Grant(catalog, new Securable(Securable.Kind.CATALOG, catalog), "g", "select");
                    assertApplied(store.putGrant(grant, "admin", change()));
                    return (target, change) -> target.revokeGrant(grant, change);
                }),
                onTable("putAttribute", table -> (target, change) -> target.putAttribute(new GovernanceAttribute(
                        table.securable(), GovernanceAttribute.Kind.OWNER, "team", "catalog-api", "admin", change.at()), change)),
                onTable("removeAttribute", table -> {
                    assertApplied(store.putAttribute(new GovernanceAttribute(table.securable(),
                            GovernanceAttribute.Kind.OWNER, "team", "catalog-api", "admin", now()), change()));
                    return (target, change) -> target.removeAttribute(table.securable(), GovernanceAttribute.Kind.OWNER, "steward", change);
                }),
                new Mutation("placeHold", () -> {
                    NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
                    return (target, change) -> target.placeHold(new LegalHold(UUID.randomUUID(),
                            new Securable(Securable.Kind.NAMESPACE, namespace.id()), "matter", "counsel", change.at()), change);
                }),
                new Mutation("releaseHold", () -> {
                    UUID catalog = newCatalog();
                    LegalHold hold = new LegalHold(UUID.randomUUID(), new Securable(Securable.Kind.CATALOG, catalog),
                            "matter", "counsel", now());
                    assertApplied(store.placeHold(hold, change()));
                    return (target, change) -> target.releaseHold(hold.id(), "counsel", change);
                }),
                new Mutation("putTrustedEngine", () -> (target, change) -> target.putTrustedEngine(
                        new TrustedEngine("https://idp.suite/" + UUID.randomUUID(), "trino", "sub", Set.of("shared")), change)),
                new Mutation("removeTrustedEngine", () -> {
                    TrustedEngine engine = new TrustedEngine("https://idp.suite/" + UUID.randomUUID(), "trino", "sub", Set.of());
                    assertApplied(store.putTrustedEngine(engine, change()));
                    return (target, change) -> target.removeTrustedEngine(engine.issuer(), engine.clientId(), change);
                }),
                new Mutation("createRef", () -> {
                    UUID catalog = newCatalog();
                    newTable(newNamespace(catalog, "ns"), "t");
                    return (target, change) -> target.createRef(Refs.branch(catalog, "b", change.at()), RefLimits.P11,
                            change);
                }),
                new Mutation("renewRef", () -> {
                    Ref branch = Refs.branch(newCatalog(), "b", now());
                    assertApplied(store.createRef(branch, RefLimits.P11, change()));
                    return (target, change) -> target.renewRef(branch.id(), change.at().plus(Duration.ofDays(60)), change);
                }),
                new Mutation("deleteRef", () -> {
                    UUID catalog = newCatalog();
                    newTable(newNamespace(catalog, "ns"), "t");
                    Ref branch = Refs.branch(catalog, "b", now());
                    assertApplied(store.createRef(branch, RefLimits.P11, change()));
                    return (target, change) -> target.deleteRef(branch.id(), change);
                }),
                new Mutation("commit on a branch", () -> {
                    UUID catalog = newCatalog();
                    CatalogObject table = newTable(newNamespace(catalog, "ns"), "t");
                    Ref branch = Refs.branch(catalog, "b", now());
                    assertApplied(store.createRef(branch, RefLimits.P11, change()));
                    PointerSwap own = Commits.advance(branch.key(table.id()), Optional.empty(), "on-b");
                    return (target, change) -> applied(target.commit(new CommitBatch(change.id(), change.at(),
                            List.of(own), List.of(), Optional.of(new RefLock.Hold(branch.id())), change.outbox(),
                            change.idempotency())));
                }),
                new Mutation("merge", () -> {
                    UUID catalog = newCatalog();
                    CatalogObject table = newTable(newNamespace(catalog, "ns"), "t");
                    Ref branch = Refs.branch(catalog, "b", now());
                    assertApplied(store.createRef(branch, RefLimits.P11, change()));
                    PointerSwap own = Commits.advance(branch.key(table.id()), Optional.empty(), "on-b");
                    applied(store.commit(new CommitBatch(UUID.randomUUID(), now(), List.of(own), List.of(),
                            Optional.of(new RefLock.Hold(branch.id())), List.of(), Optional.empty())));
                    PointerSwap toMain = Commits.advance(table.mainKey(), store.readPointer(table.mainKey()), "merged");
                    return (target, change) -> applied(target.commit(new CommitBatch(change.id(), change.at(),
                            List.of(toMain), List.of(new PointerHold(own.key(), own.next())),
                            Optional.of(new RefLock.End(branch.id())), change.outbox(), change.idempotency())));
                }));
    }

    /** A commit's answer as a change's: it must have committed. */
    private static WriteResult applied(CommitResult result) {
        assertThat(result).isInstanceOf(CommitResult.Committed.class);
        return WriteResult.APPLIED;
    }

    private Mutation onTable(String name, Function<CatalogObject, Ready> ready) {
        return new Mutation(name, () -> ready.apply(newTable(newNamespace(newCatalog(), "ns"), "t")));
    }

    private StorageProfile profile() {
        StorageProfile profile = new StorageProfile(UUID.randomUUID(), "p", StorageProfile.Kind.S3, Optional.empty(),
                "role", List.of(), Optional.empty());
        assertApplied(store.putStorageProfile(profile, change()));
        return profile;
    }

    @TestFactory
    @DisplayName("2 Atomicity: every mutation writes exactly its outbox events and idempotency result")
    Stream<DynamicTest> everyMutationWritesItsChangeRecords() {
        return mutations().stream().map(mutation -> dynamicTest(mutation.name(), () -> {
            Ready ready = mutation.prepare().get();
            Change change = fullChange();

            assertThat(ready.apply(store, change)).isEqualTo(WriteResult.APPLIED);

            assertThat(underTest().changeRows(change)).isEqualTo(WrittenRows.allOf(change));
        }));
    }

    @Tag("overnight")
    @TestFactory
    @DisplayName("3 Ambiguity: every mutation whose answer is lost is unknown, carries its change id, and was written")
    Stream<DynamicTest> everyMutationWithALostAnswerIsUnknown() {
        return mutations().stream().map(mutation -> dynamicTest(mutation.name(), () -> {
            Ready ready = mutation.prepare().get();
            Change change = fullChange();

            assertUnknownWith(ready, change, faults::dropResponses);

            assertThat(underTest().changeRows(change)).isEqualTo(WrittenRows.allOf(change));
        }));
    }

    @Tag("overnight")
    @TestFactory
    @DisplayName("2 Atomicity: every mutation whose link is cut just before COMMIT is unknown and wrote nothing")
    Stream<DynamicTest> everyMutationCutBeforeCommitWritesNothing() {
        return mutations().stream().map(mutation -> dynamicTest(mutation.name(), () -> {
            Ready ready = mutation.prepare().get();
            Change change = fullChange();

            assertUnknownWith(ready, change, faults::cutLink);

            assertThat(underTest().changeRows(change)).isEqualTo(WrittenRows.NONE);
        }));
    }

    private void assertUnknownWith(Ready ready, Change change, Runnable fault) {
        CatalogStore faulty = underTest().store(id -> {
            if (id.equals(change.id())) {
                fault.run();
            }
        });
        try {
            assertThatThrownBy(() -> ready.apply(faulty, change))
                    .isInstanceOfSatisfying(CommitOutcomeUnknownException.class,
                            unknown -> assertThat(unknown.commitId()).isEqualTo(change.id()));
        } finally {
            faults.healLink();
        }
    }
}

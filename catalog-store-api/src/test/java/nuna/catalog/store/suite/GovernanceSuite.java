package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Changes.change;
import static nuna.catalog.store.suite.Changes.fullChange;
import static nuna.catalog.store.suite.Changes.name;
import static nuna.catalog.store.suite.Changes.now;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.Change;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.TrustedEngine;
import nuna.catalog.store.WriteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Catalogs, storage profiles, governance attributes, legal holds and trusted engines. */
public abstract class GovernanceSuite extends GroupSuite {

    private StorageProfile newProfile(List<String> locations) {
        StorageProfile profile = new StorageProfile(UUID.randomUUID(), "profile", StorageProfile.Kind.S3,
                Optional.empty(), "arn:aws:iam::000000000000:role/governance", locations);
        assertApplied(store.putStorageProfile(profile, change()));
        return profile;
    }

    @Test
    @DisplayName("Governance: a catalog is created with its boundary and change records, and found by normalized name")
    void catalogIsCreatedWithItsBoundary() {
        StorageProfile profile = newProfile(List.of("s3://lake/"));
        UUID id = UUID.randomUUID();
        Name name = name("Finance-" + id);
        CatalogSettings settings = new CatalogSettings(profile.id(), "kms-finance", "Fidelity Finance Ltd");
        Change change = fullChange();

        assertThat(store.createCatalog(new CatalogRecord(id, name.normalized(), name.display()), settings, change))
                .isEqualTo(WriteResult.APPLIED);

        assertThat(store.findCatalog(name.normalized())).contains(new CatalogRecord(id, name.normalized(), name.display()));
        assertThat(store.catalogSettings(id)).contains(settings);
        assertThat(underTest().changeRows(change)).isEqualTo(WrittenRows.allOf(change));
        assertThat(store.catalogSettings(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("8 Names: a catalog whose name differs only in case is NameTaken; one without its profile is Missing")
    void catalogNameCollisionAndMissingProfile() {
        StorageProfile profile = newProfile(List.of());
        UUID first = UUID.randomUUID();
        Name name = name("Sales-" + first);
        assertApplied(store.createCatalog(new CatalogRecord(first, name.normalized(), name.display()),
                new CatalogSettings(profile.id(), "k", "e"), change()));
        Name upper = name(name.display().toUpperCase());
        Change collision = fullChange();
        Change missing = fullChange();

        assertThat(store.createCatalog(new CatalogRecord(UUID.randomUUID(), upper.normalized(), upper.display()),
                new CatalogSettings(profile.id(), "k", "e"), collision)).isEqualTo(new WriteResult.NameTaken(first));
        assertThat(store.createCatalog(new CatalogRecord(UUID.randomUUID(), "other-" + first, "Other-" + first),
                new CatalogSettings(UUID.randomUUID(), "k", "e"), missing))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.STORAGE_PROFILE));

        assertThat(underTest().changeRows(collision)).isEqualTo(WrittenRows.NONE);
        assertThat(underTest().changeRows(missing)).isEqualTo(WrittenRows.NONE);
        assertThat(store.findCatalog(name.normalized()).orElseThrow().name()).isEqualTo(name.display());
        assertThat(store.findCatalog("other-" + first)).isEmpty();
    }

    @Test
    @DisplayName("Governance: a storage profile keeps its allowed locations in order, and a put replaces it whole")
    void storageProfileIsReplacedWhole() {
        StorageProfile profile = newProfile(List.of("s3://b/", "s3://a/", "s3://c/"));
        StorageProfile replaced = new StorageProfile(profile.id(), "renamed", StorageProfile.Kind.CEPH,
                Optional.of("https://ceph.internal:7480"), "role-2", List.of("s3://z/", "s3://b/"));
        Change change = fullChange();

        assertThat(store.readStorageProfile(profile.id())).contains(profile);
        assertThat(store.putStorageProfile(replaced, change)).isEqualTo(WriteResult.APPLIED);

        assertThat(store.readStorageProfile(profile.id())).contains(replaced);
        assertThat(underTest().changeRows(change)).isEqualTo(WrittenRows.allOf(change));
        assertThat(store.readStorageProfile(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("Governance: an attribute is set, replaced per kind, read for many targets at once, and removed")
    void attributesAreSetReplacedAndRemoved() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        Securable onNamespace = new Securable(Securable.Kind.NAMESPACE, namespace.id());
        GovernanceAttribute retention = new GovernanceAttribute(table.securable(), GovernanceAttribute.Kind.RETENTION_CLASS,
                "seven-years", "governance-service", "steward", now());
        GovernanceAttribute replaced = new GovernanceAttribute(table.securable(), GovernanceAttribute.Kind.RETENTION_CLASS,
                "ten-years", "catalog-api", "steward-2", now());
        GovernanceAttribute classification = new GovernanceAttribute(table.securable(),
                GovernanceAttribute.Kind.CLASSIFICATION, "confidential", "scanner", "svc-scan", now());
        GovernanceAttribute owner = new GovernanceAttribute(onNamespace, GovernanceAttribute.Kind.OWNER, "team-a",
                "catalog-api", "admin", now());
        Change replace = fullChange();
        Change remove = fullChange();
        Change removeAgain = fullChange();

        assertApplied(store.putAttribute(retention, change()));
        assertApplied(store.putAttribute(classification, change()));
        assertApplied(store.putAttribute(owner, change()));
        assertThat(store.putAttribute(replaced, replace)).isEqualTo(WriteResult.APPLIED);

        assertThat(store.attributesOf(Set.of(table.securable(), onNamespace)))
                .containsExactlyInAnyOrder(replaced, classification, owner);
        assertThat(store.removeAttribute(table.securable(), GovernanceAttribute.Kind.CLASSIFICATION, remove))
                .isEqualTo(WriteResult.APPLIED);
        assertThat(store.removeAttribute(table.securable(), GovernanceAttribute.Kind.CLASSIFICATION, removeAgain))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.ATTRIBUTE));
        assertThat(store.attributesOf(Set.of(table.securable()))).containsExactly(replaced);
        assertThat(underTest().changeRows(replace)).isEqualTo(WrittenRows.allOf(replace));
        assertThat(underTest().changeRows(remove)).isEqualTo(WrittenRows.allOf(remove));
        assertThat(underTest().changeRows(removeAgain)).isEqualTo(WrittenRows.NONE);
    }

    @Test
    @DisplayName("Governance: every attribute kind round-trips, a 2,000-character value included")
    void everyKindRoundTrips() {
        Securable onCatalog = new Securable(Securable.Kind.CATALOG, newCatalog());
        List<GovernanceAttribute> all = new ArrayList<>();
        for (GovernanceAttribute.Kind kind : GovernanceAttribute.Kind.values()) {
            String value = kind == GovernanceAttribute.Kind.MAINTENANCE_POLICY ? "{\"k\":\"" + "v".repeat(1990) + "\"}"
                    : kind.name().toLowerCase();
            GovernanceAttribute attribute = new GovernanceAttribute(onCatalog, kind, value, "catalog-api", "op", now());
            assertApplied(store.putAttribute(attribute, change()));
            all.add(attribute);
        }
        assertThat(store.attributesOf(Set.of(onCatalog))).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    @DisplayName("Governance: an attribute or hold on a securable that does not exist is Missing and writes nothing")
    void attributeAndHoldNeedTheirTarget() {
        Securable nowhere = new Securable(Securable.Kind.VIEW, UUID.randomUUID());
        Change attribute = fullChange();
        Change hold = fullChange();

        assertThat(store.putAttribute(new GovernanceAttribute(nowhere, GovernanceAttribute.Kind.OWNER, "x", "p", "s", now()),
                attribute)).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.placeHold(new LegalHold(UUID.randomUUID(), new Securable(Securable.Kind.CATALOG, UUID.randomUUID()),
                "case 1", "counsel", now()), hold)).isEqualTo(new WriteResult.Missing(WriteResult.Subject.CATALOG));

        assertThat(underTest().changeRows(attribute)).isEqualTo(WrittenRows.NONE);
        assertThat(underTest().changeRows(hold)).isEqualTo(WrittenRows.NONE);
        assertThat(store.attributesOf(Set.of(nowhere))).isEmpty();
    }

    @Test
    @DisplayName("Governance: holds are placed, listed per target in one read, and released once")
    void holdsArePlacedAndReleased() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        Securable onCatalog = new Securable(Securable.Kind.CATALOG, catalog);
        Securable onNamespace = new Securable(Securable.Kind.NAMESPACE, namespace.id());
        LegalHold first = new LegalHold(UUID.randomUUID(), onNamespace, "matter 7", "counsel", now());
        LegalHold second = new LegalHold(UUID.randomUUID(), onNamespace, "matter 9", "counsel", now());
        LegalHold wide = new LegalHold(UUID.randomUUID(), onCatalog, "regulator", "counsel", now());
        Change place = fullChange();
        Change release = fullChange();
        Change releaseAgain = fullChange();

        assertThat(store.placeHold(first, place)).isEqualTo(WriteResult.APPLIED);
        assertApplied(store.placeHold(second, change()));
        assertApplied(store.placeHold(wide, change()));

        assertThat(store.holdsOn(Set.of(onNamespace, onCatalog))).containsExactlyInAnyOrder(first, second, wide);
        assertThat(store.releaseHold(first.id(), release)).isEqualTo(WriteResult.APPLIED);
        assertThat(store.releaseHold(first.id(), releaseAgain)).isEqualTo(new WriteResult.Missing(WriteResult.Subject.HOLD));
        assertThat(store.holdsOn(Set.of(onNamespace))).containsExactly(second);
        assertThat(underTest().changeRows(place)).isEqualTo(WrittenRows.allOf(place));
        assertThat(underTest().changeRows(release)).isEqualTo(WrittenRows.allOf(release));
        assertThat(underTest().changeRows(releaseAgain)).isEqualTo(WrittenRows.NONE);
    }

    @Test
    @DisplayName("Governance: a trusted engine is registered, replaced with new flags, listed and removed")
    void trustedEnginesAreRegisteredAndRemoved() {
        String issuer = "https://idp.suite/" + UUID.randomUUID();
        TrustedEngine trino = new TrustedEngine(issuer, "trino", "act_as", Set.of("shared", "row-filter"));
        TrustedEngine replaced = new TrustedEngine(issuer, "trino", "on_behalf_of", Set.of());
        TrustedEngine spark = new TrustedEngine(issuer, "spark", "sub", Set.of("shared"));
        Change replace = fullChange();
        Change remove = fullChange();
        Change removeAgain = fullChange();

        assertApplied(store.putTrustedEngine(trino, change()));
        assertApplied(store.putTrustedEngine(spark, change()));
        assertThat(store.findTrustedEngine(issuer, "trino")).contains(trino);
        assertThat(store.putTrustedEngine(replaced, replace)).isEqualTo(WriteResult.APPLIED);

        assertThat(store.findTrustedEngine(issuer, "trino")).contains(replaced);
        assertThat(store.listTrustedEngines()).contains(replaced, spark).doesNotContain(trino);
        assertThat(store.removeTrustedEngine(issuer, "spark", remove)).isEqualTo(WriteResult.APPLIED);
        assertThat(store.removeTrustedEngine(issuer, "spark", removeAgain))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.ENGINE));
        assertThat(store.findTrustedEngine(issuer, "spark")).isEmpty();
        assertThat(underTest().changeRows(replace)).isEqualTo(WrittenRows.allOf(replace));
        assertThat(underTest().changeRows(remove)).isEqualTo(WrittenRows.allOf(remove));
        assertThat(underTest().changeRows(removeAgain)).isEqualTo(WrittenRows.NONE);
    }

    @Test
    @DisplayName("Governance: 8 concurrent registrations of one new engine all apply, and one of them is kept, every round")
    void concurrentPutsOfOneNewEngineAllApply() throws Exception {
        for (int round = 0; round < 10; round++) {
            String issuer = "https://idp.suite/" + UUID.randomUUID();
            CyclicBarrier start = new CyclicBarrier(8);
            List<TrustedEngine> engines = new ArrayList<>();
            List<Future<WriteResult>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                TrustedEngine engine = new TrustedEngine(issuer, "trino", "sub", Set.of("writer-" + i));
                engines.add(engine);
                results.add(background.submit(() -> {
                    start.await();
                    return store.putTrustedEngine(engine, change());
                }));
            }
            for (Future<WriteResult> result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS)).as("round %d", round).isEqualTo(WriteResult.APPLIED);
            }

            assertThat(store.findTrustedEngine(issuer, "trino")).hasValueSatisfying(kept -> assertThat(engines).contains(kept));
        }
    }
}

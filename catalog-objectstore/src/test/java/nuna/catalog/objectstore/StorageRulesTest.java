package nuna.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.commit.CommitRule;
import nuna.catalog.core.commit.CommitRule.Rejection;
import nuna.catalog.iceberg.Facts;
import nuna.catalog.iceberg.Facts.TableFacts;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Name;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;

class StorageRulesTest {

    private static final StorageProfile PROFILE = StorageProfile.of(StoreKind.CEPH, "https://rgw.internal:8443",
            "us-east-1", "s3://lake/tables", List.of("s3://lake/tables"));
    private static final UUID ID = UUID.fromString("0d1c5b0a-6f3e-4c2a-9d7e-3b8f1a2c4e5f");
    private static final String ASSIGNED = "s3://lake/tables/" + ID;

    private final ProfileTableFiles files = new ProfileTableFiles(PROFILE,
            new MetadataFiles(PROFILE, AnonymousCredentialsProvider.create()));

    private static CommitRule.Change change(String location, Facts before, Map<String, String> properties) {
        CatalogObject object = CatalogObject.active(UUID.randomUUID(), ID, UUID.randomUUID(), new Name("t", "t"),
                CatalogObject.Kind.TABLE, location);
        TableFacts after = new TableFacts(ID.toString(), 2, location, properties, 0, 0, Map.of());
        return new CommitRule.Change(object, before, after, Instant.now(), new CommitRule.Governance() {
            @Override
            public boolean held() {
                return false;
            }

            @Override
            public boolean regulated() {
                return false;
            }

            @Override
            public Optional<GovernanceAttribute> nearest(GovernanceAttribute.Kind kind) {
                return Optional.empty();
            }
        });
    }

    private static TableFacts facts(Map<String, String> properties) {
        return new TableFacts(ID.toString(), 2, ASSIGNED, properties, 0, 0, Map.of());
    }

    @Test
    @DisplayName("a new or registered object must sit at the location assigned to its id")
    void assignedOnCreate() {
        assertThat(StorageRules.assignedOnCreate(change(ASSIGNED, null, Map.of()), files)).isEmpty();
        for (String other : List.of("s3://lake/tables", "s3://lake/tables/" + UUID.randomUUID(),
                ASSIGNED + "/nested", "s3://lake/tables/orders", "s3://lake/other/" + ID)) {
            assertThat(StorageRules.assignedOnCreate(change(other, null, Map.of()), files))
                    .as(other)
                    .contains(Rejection.badRequest("Location is assigned by the catalog: " + ASSIGNED + ", not " + other));
        }
    }

    @Test
    @DisplayName("an existing object is judged by the core assigned-location rule, not re-derived from the profile")
    void existingObjectsAreLeftToTheCoreRule() {
        String older = "s3://lake/tables/" + UUID.randomUUID();
        assertThat(StorageRules.assignedOnCreate(change(older, facts(Map.of()), Map.of()), files)).isEmpty();
    }

    @Test
    @DisplayName("no client sets or changes a file IO property such as an endpoint, a proxy or a signer")
    void noFileIoProperties() {
        for (String key : List.of("s3.endpoint", "s3.proxy-uri", "s3.signer.uri", "s3.access-key-id",
                "client.region", "client.assume-role.arn", "http-client.type", "io-impl", "py-io-impl",
                "gcs.service.host", "adls.sas-token.x")) {
            assertThat(StorageRules.noFileIoProperties(change(ASSIGNED, null, Map.of(key, "https://evil.example"))))
                    .as(key)
                    .contains(Rejection.badRequest("Property " + key
                            + " configures storage access, which the catalog alone sets"));
            assertThat(StorageRules.noFileIoProperties(change(ASSIGNED, facts(Map.of(key, "a")), Map.of(key, "b"))))
                    .as("changing " + key)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("ordinary properties pass, and a file IO property that was already there may stay")
    void otherPropertiesPass() {
        assertThat(StorageRules.noFileIoProperties(change(ASSIGNED, null, Map.of("write.format.default", "parquet",
                "comment", "s3.endpoint is not set here", "owner", "desk")))).isEmpty();
        assertThat(StorageRules.noFileIoProperties(change(ASSIGNED, facts(Map.of("s3.endpoint", "x")),
                Map.of("s3.endpoint", "x")))).isEmpty();
    }

    @Test
    @DisplayName("both rules are in the standard list")
    void standard() {
        assertThat(StorageRules.standard(files)).hasSize(2);
        assertThat(StorageRules.standard(files).get(0).judge(change("s3://lake/tables/x", null, Map.of())))
                .isPresent();
        assertThat(StorageRules.standard(files).get(1).judge(change(ASSIGNED, null, Map.of("s3.endpoint", "x"))))
                .isPresent();
    }
}

package keel.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProfileOverlapTest {

    private static StorageProfile profile(String... prefixes) {
        return StorageProfile.of(StoreKind.CEPH, "https://rgw.internal:8443", "us-east-1", prefixes[0],
                List.of(prefixes));
    }

    private static Map<String, StorageProfile> profiles(StorageProfile a, StorageProfile b) {
        Map<String, StorageProfile> profiles = new LinkedHashMap<>();
        profiles.put("a", a);
        profiles.put("b", b);
        return profiles;
    }

    @Test
    @DisplayName("prefixes that only share leading characters do not overlap: orders vs orders2")
    void siblingsWithACommonStem() {
        assertThatCode(() -> ProfileOverlap.requireDisjoint(profiles(profile("s3://lake/orders"),
                profile("s3://lake/orders2")))).doesNotThrowAnyException();
        assertThatCode(() -> ProfileOverlap.requireDisjoint(profiles(profile("s3://lake/t"),
                profile("s3://lake2/t")))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("two profiles may not share a prefix, nor may one's prefix contain the other's")
    void overlapIsRefused() {
        assertThatThrownBy(() -> ProfileOverlap.requireDisjoint(profiles(profile("s3://lake/t"),
                profile("s3://lake/t"))))
                .hasMessage("storage profiles a and b overlap: s3://lake/t and s3://lake/t");
        assertThatThrownBy(() -> ProfileOverlap.requireDisjoint(profiles(profile("s3://lake/t"),
                profile("s3://lake/t/eu"))))
                .hasMessageContaining("overlap");
        assertThatThrownBy(() -> ProfileOverlap.requireDisjoint(profiles(profile("s3://lake/x", "s3://lake/t/eu/x"),
                profile("s3://lake/t"))))
                .hasMessage("storage profiles a and b overlap: s3://lake/t/eu/x and s3://lake/t");
    }

    @Test
    @DisplayName("a location's owner is the profile whose prefix it lies strictly beneath")
    void owner() {
        Map<String, StorageProfile> profiles = profiles(profile("s3://lake/orders"), profile("s3://lake/orders2"));

        assertThat(ProfileOverlap.owner(profiles, ObjectLocation.parse("s3://lake/orders2/t1"))).contains("b");
        assertThat(ProfileOverlap.owner(profiles, ObjectLocation.parse("s3://lake/orders/t1"))).contains("a");
        assertThat(ProfileOverlap.owner(profiles, ObjectLocation.parse("s3://lake/orders"))).isEmpty();
        assertThat(ProfileOverlap.owner(profiles, ObjectLocation.parse("s3://lake/orders3/t1"))).isEmpty();
    }
}

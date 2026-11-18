package nuna.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ObjectLocationTest {

    @Test
    @DisplayName("a canonical location parses into bucket and segments and prints back unchanged")
    void canonicalLocationRoundTrips() {
        ObjectLocation location = ObjectLocation.parse("s3://lake/warehouse/t-1/metadata/00001-a.metadata.json");

        assertThat(location.bucket()).isEqualTo("lake");
        assertThat(location.segments()).containsExactly("warehouse", "t-1", "metadata", "00001-a.metadata.json");
        assertThat(location.key()).isEqualTo("warehouse/t-1/metadata/00001-a.metadata.json");
        assertThat(location).hasToString("s3://lake/warehouse/t-1/metadata/00001-a.metadata.json");
    }

    @Test
    @DisplayName("a bucket on its own is a location with no segments")
    void bucketOnly() {
        ObjectLocation location = ObjectLocation.parse("s3://lake.prod-1");

        assertThat(location.segments()).isEmpty();
        assertThat(location.key()).isEmpty();
        assertThat(location).hasToString("s3://lake.prod-1");
    }

    @Test
    @DisplayName("every S3 safe key character except * is accepted")
    void safeCharacters() {
        assertThat(ObjectLocation.parse("s3://lake/AZaz09!_.'()-").segments()).containsExactly("AZaz09!_.'()-");
        assertThat(ObjectLocation.parse("s3://lake/...").segments()).containsExactly("...");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("non-canonical spellings are rejected, not normalized")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            S3://lake/warehouse/t                 | scheme must be s3://
            s3a://lake/warehouse/t                | scheme must be s3://
            s3n://lake/warehouse/t                | scheme must be s3://
            https://lake.s3.amazonaws.com/warehouse | scheme must be s3://
            s3:/lake/warehouse/t                  | scheme must be s3://
            " s3://lake/warehouse/t"              | scheme must be s3://
            file:///lake/warehouse/t              | scheme must be s3://
            s3:///lake/warehouse/t                | is not a valid S3 bucket name
            s3://                                 | is not a valid S3 bucket name
            s3://evil@lake/warehouse/t            | is not a valid S3 bucket name
            s3://lake:443/warehouse/t             | is not a valid S3 bucket name
            s3://LAKE/warehouse/t                 | is not a valid S3 bucket name
            s3://la/warehouse/t                   | is not a valid S3 bucket name
            s3://-lake/warehouse/t                | is not a valid S3 bucket name
            s3://lake./warehouse/t                | is not a valid S3 bucket name
            s3://la..ke/warehouse/t               | is not a valid S3 bucket name
            s3://lake%2fwarehouse/t               | is not a valid S3 bucket name
            s3://lake?x/warehouse/t               | is not a valid S3 bucket name
            s3://lake/                            | empty path segment
            s3://lake/warehouse/                  | empty path segment
            s3://lake//warehouse                  | empty path segment
            s3://lake/warehouse//t                | empty path segment
            s3://lake/warehouse/..                | dot segment
            s3://lake/warehouse/../secrets        | dot segment
            s3://lake/warehouse/./t               | dot segment
            s3://lake/warehouse/%2e%2e/secrets    | has a character outside
            s3://lake/warehouse/..%2fsecrets      | has a character outside
            s3://lake/warehouse%2f..%2fsecrets    | has a character outside
            s3://lake/warehouse/%252e%252e        | has a character outside
            s3://lake/warehouse\\..\\secrets      | has a character outside
            s3://lake/warehouse/t?versionId=1     | has a character outside
            s3://lake/warehouse/t#x               | has a character outside
            s3://lake/warehouse/*                 | has a character outside
            s3://lake/warehouse/${aws:username}   | has a character outside
            s3://lake/warehouse/t;x               | has a character outside
            "s3://lake/warehouse/t "              | has a character outside
            "s3://lake/ware house/t"              | has a character outside
            s3://lake/warehouse/t+1               | has a character outside
            s3://lake/warehouse/a=b               | has a character outside
            """)
    void nonCanonicalSpellingsAreRejected(String location, String reason) {
        assertThatThrownBy(() -> ObjectLocation.parse(location))
                .isInstanceOf(LocationRejectedException.class)
                .hasMessageContaining(reason)
                .extracting(e -> ((LocationRejectedException) e).location()).isNotNull();
    }

    @ParameterizedTest(name = "U+{0}")
    @DisplayName("control, look-alike and non-ASCII characters are rejected")
    @CsvSource({
            "0000", "0009", "000A", "000D", "007F", "00A0", "2024", "2215", "FF0E", "FF0F", "0435", "200B", "FEFF"})
    void controlAndLookAlikeCharactersAreRejected(String codePoint) {
        String character = Character.toString(Integer.parseInt(codePoint, 16));

        assertThatThrownBy(() -> ObjectLocation.parse("s3://lake/warehouse/" + character + "t"))
                .isInstanceOf(LocationRejectedException.class)
                .hasMessageContaining("has a character outside");
        assertThatThrownBy(() -> ObjectLocation.parse("s3://lake/warehouse/" + character + character + "/t"))
                .isInstanceOf(LocationRejectedException.class)
                .hasMessageContaining("has a character outside");
    }

    @Test
    @DisplayName("child segments obey the same rules as parsed ones")
    void childIsValidated() {
        ObjectLocation root = ObjectLocation.parse("s3://lake/warehouse");

        assertThat(root.child("t-1")).hasToString("s3://lake/warehouse/t-1");
        assertThatThrownBy(() -> root.child("..")).isInstanceOf(LocationRejectedException.class);
        assertThatThrownBy(() -> root.child("a/b")).isInstanceOf(LocationRejectedException.class);
        assertThatThrownBy(() -> root.child("")).isInstanceOf(LocationRejectedException.class);
        assertThatThrownBy(() -> new ObjectLocation("lake", List.of("ok", "%2e"))).isInstanceOf(LocationRejectedException.class);
    }

    @ParameterizedTest(name = "{0} beneath {1}: {2}")
    @DisplayName("beneath compares bucket and whole segments, and excludes the prefix itself")
    @CsvSource(delimiter = '|', textBlock = """
            s3://lake/ab/x            | s3://lake/ab   | true
            s3://lake/ab/x/y          | s3://lake/ab   | true
            s3://lake/ab              | s3://lake      | true
            s3://lake/abc             | s3://lake/ab   | false
            s3://lake/abc/x           | s3://lake/ab   | false
            s3://lake/ab-x/y          | s3://lake/ab   | false
            s3://lake/ab.x/y          | s3://lake/ab   | false
            s3://lake/AB/x            | s3://lake/ab   | false
            s3://lake/a/b/x           | s3://lake/ab   | false
            s3://lake/ab              | s3://lake/ab   | false
            s3://lake                 | s3://lake      | false
            s3://lake                 | s3://lake/ab   | false
            s3://lakes/ab/x           | s3://lake/ab   | false
            s3://lake.evil/ab/x       | s3://lake/ab   | false
            s3://archive/ab/x         | s3://lake/ab   | false
            s3://lake/x/ab/y          | s3://lake/ab   | false
            """)
    void beneath(String location, String prefix, boolean beneath) {
        assertThat(ObjectLocation.parse(location).isBeneath(ObjectLocation.parse(prefix))).isEqualTo(beneath);
    }
}

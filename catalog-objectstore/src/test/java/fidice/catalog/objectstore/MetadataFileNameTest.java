package fidice.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetadataFileNameTest {

    private static final String UUID = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";

    @Test
    @DisplayName("file names follow Iceberg's <version>-<uuid>.metadata.json, zero-padded to five digits")
    void icebergNaming() {
        assertThat(MetadataFiles.fileName(1)).matches("00001-" + UUID + "\\.metadata\\.json");
        assertThat(MetadataFiles.fileName(42)).matches("00042-" + UUID + "\\.metadata\\.json");
        assertThat(MetadataFiles.fileName(123_456)).matches("123456-" + UUID + "\\.metadata\\.json");
        assertThat(MetadataFiles.fileName(1)).isNotEqualTo(MetadataFiles.fileName(1));
    }

    @Test
    @DisplayName("the version is written in ASCII digits whatever the JVM's default locale")
    void localeIndependent() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
            assertThat(MetadataFiles.fileName(42)).startsWith("00042-");
        } finally {
            Locale.setDefault(saved);
        }
    }
}

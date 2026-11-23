package nuna.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.commit.TableFiles;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetadataFileNameTest {

    private static final String UUID = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";

    private static final Optional<java.util.UUID> MAIN = Optional.empty();

    @Test
    @DisplayName("file names follow Iceberg's <version>-<uuid>.metadata.json, zero-padded to five digits")
    void icebergNaming() {
        assertThat(TableFiles.fileName(1, MAIN)).matches("00001-" + UUID + "\\.metadata\\.json");
        assertThat(TableFiles.fileName(42, MAIN)).matches("00042-" + UUID + "\\.metadata\\.json");
        assertThat(TableFiles.fileName(123_456, MAIN)).matches("123456-" + UUID + "\\.metadata\\.json");
        assertThat(TableFiles.fileName(1, MAIN)).isNotEqualTo(TableFiles.fileName(1, MAIN));
    }

    @Test
    @DisplayName("a branch's file names carry the branch's id after .branch-, so storage alone tells them from main's")
    void aBranchsFilesAreMarked() {
        java.util.UUID dev = java.util.UUID.fromString("0f5e2a9c-1b7d-4c3e-8a6f-2d9b4e7c1a03");
        String onDev = TableFiles.fileName(3, Optional.of(dev));

        assertThat(onDev).matches("00003-" + UUID + "\\.branch-" + dev + "\\.metadata\\.json");
        assertThat(TableFiles.onBranch("s3://lake/t/metadata/" + onDev)).isTrue();
        assertThat(TableFiles.onBranch("s3://lake/t/metadata/" + TableFiles.fileName(3, MAIN))).isFalse();
        assertThat(TableFiles.onBranch("s3://lake/t.branch-x/metadata/00001-a.metadata.json"))
                .as("only the file's own name counts").isFalse();
    }

    @Test
    @DisplayName("the version is written in ASCII digits whatever the JVM's default locale")
    void localeIndependent() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
            assertThat(TableFiles.fileName(42, MAIN)).startsWith("00042-");
        } finally {
            Locale.setDefault(saved);
        }
    }
}

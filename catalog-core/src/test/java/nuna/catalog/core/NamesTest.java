package nuna.catalog.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void caseDiffersOnlyInDisplay() {
        assertThat(Names.normalize("Trading")).isEqualTo(Names.normalize("TRADING")).isEqualTo("trading");
    }

    @Test
    void composedAndDecomposedFormsAreOneName() {
        assertThat(Names.normalize("Café")).isEqualTo(Names.normalize("Café"));
    }

    @Test
    void lowerCasingIgnoresTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr")); // Turkish lower-cases I to dotless ı
            assertThat(Names.normalize("TITLE")).isEqualTo("title");
        } finally {
            Locale.setDefault(saved);
        }
    }
}

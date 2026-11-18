package nuna.catalog.core;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Catalog, namespace, table and view names are case-insensitive and case-preserving (D8.6):
 * uniqueness and lookup use the normalized form, display uses the name as given.
 */
public final class Names {

    private Names() {
    }

    /** Unicode NFC, then locale-independent lower case. */
    public static String normalize(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}

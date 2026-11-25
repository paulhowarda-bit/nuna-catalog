package nuna.catalog.server.rest;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import java.util.List;
import java.util.function.Function;

/** A {@link TableColumns} for tests outside its package that build the routes without Spring or metadata files. */
public final class TableColumnsForTests {

    private TableColumnsForTests() {
    }

    /** Each table's current columns, as {@code columns} gives them. */
    public static TableColumns of(Function<CatalogObject, List<String>> columns) {
        return new TableColumns(null) {
            @Override
            public List<String> current(CatalogRecord catalog, CatalogObject table) {
                return columns.apply(table);
            }
        };
    }
}

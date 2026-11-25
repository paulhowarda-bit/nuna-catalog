package nuna.catalog.server.rest;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.commit.CommitEngine;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A table's columns as its current metadata on main names them, for the routes outside this package that check names
 * against them: a row filter's and column masks' (D20, backlog 240). Read as a load reads the file, through the
 * metadata cache and checked against the pointer's hash.
 */
@Component
public class TableColumns {

    private final Engines engines;

    TableColumns(Engines engines) {
        this.engines = engines;
    }

    /** The top-level columns of {@code table}'s current schema, in schema order. */
    public List<String> current(CatalogRecord catalog, CatalogObject table) {
        return switch (engines.reader(catalog).readCurrent(table)) {
            case CommitEngine.Current.Loaded loaded -> Metadata.columns(loaded.json());
            case CommitEngine.Current.Refused refused ->
                    throw CatalogError.of(500, refused.error().type(), refused.error().message());
        };
    }
}

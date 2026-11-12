package keel.changefeed.engine;

import java.util.List;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;

/**
 * One in-memory catalog for the whole test JVM, behind a class the feed can name in
 * {@code iceberg.catalog.catalog-impl}: the feed opens a new catalog each time it restarts, and must find its table.
 */
public final class SharedCatalog implements Catalog, SupportsNamespaces {

    static final InMemoryCatalog CATALOG = new InMemoryCatalog();

    static {
        CATALOG.initialize("shared", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "mem://shared"));
    }

    @Override
    public void initialize(String name, Map<String, String> properties) {}

    @Override
    public String name() {
        return CATALOG.name();
    }

    @Override
    public List<TableIdentifier> listTables(Namespace namespace) {
        return CATALOG.listTables(namespace);
    }

    @Override
    public TableBuilder buildTable(TableIdentifier identifier, Schema schema) {
        return CATALOG.buildTable(identifier, schema);
    }

    @Override
    public boolean tableExists(TableIdentifier identifier) {
        return CATALOG.tableExists(identifier);
    }

    @Override
    public boolean dropTable(TableIdentifier identifier, boolean purge) {
        return CATALOG.dropTable(identifier, purge);
    }

    @Override
    public void renameTable(TableIdentifier from, TableIdentifier to) {
        CATALOG.renameTable(from, to);
    }

    @Override
    public Table loadTable(TableIdentifier identifier) {
        return CATALOG.loadTable(identifier);
    }

    @Override
    public void createNamespace(Namespace namespace, Map<String, String> metadata) {
        CATALOG.createNamespace(namespace, metadata);
    }

    @Override
    public List<Namespace> listNamespaces(Namespace namespace) {
        return CATALOG.listNamespaces(namespace);
    }

    @Override
    public Map<String, String> loadNamespaceMetadata(Namespace namespace) {
        return CATALOG.loadNamespaceMetadata(namespace);
    }

    @Override
    public boolean dropNamespace(Namespace namespace) {
        return CATALOG.dropNamespace(namespace);
    }

    @Override
    public boolean setProperties(Namespace namespace, Map<String, String> properties) {
        return CATALOG.setProperties(namespace, properties);
    }

    @Override
    public boolean removeProperties(Namespace namespace, java.util.Set<String> properties) {
        return CATALOG.removeProperties(namespace, properties);
    }
}

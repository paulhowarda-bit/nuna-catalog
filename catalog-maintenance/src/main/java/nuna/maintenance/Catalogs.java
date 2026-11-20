package nuna.maintenance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;

/**
 * The worker as an Iceberg REST client of each catalog it maintains (D30 (1)): one client per catalog name, signed
 * in with the worker's token, reading and writing table files with the credentials the catalog vends it, which do
 * not include delete (D21). Each request carries the current trace, so the catalog's spans join the scan's.
 */
final class Catalogs implements AutoCloseable {

    private final URI uri;
    private final String source;
    private final Map<String, String> extra;
    private final Map<String, RESTCatalog> clients = new ConcurrentHashMap<>();

    /**
     * @param extra more client properties, over the worker's own: those that make its S3 clients trust the storage
     *              profiles' CA bundles ({@link Storage#clientProperties}), and tests' own
     * @param trace the current trace, sent with every request
     */
    Catalogs(URI uri, TokenSource tokens, Map<String, String> extra, TraceHeaders trace) {
        this.uri = uri;
        this.source = BearerAuthManager.register(tokens, trace);
        this.extra = Map.copyOf(extra);
    }

    /** The table as the catalog loads it now. */
    Table load(String catalog, Iterable<String> namespace, String name) {
        java.util.List<String> levels = new java.util.ArrayList<>();
        namespace.forEach(levels::add);
        return client(catalog).loadTable(TableIdentifier.of(Namespace.of(levels.toArray(String[]::new)), name));
    }

    private RESTCatalog client(String catalog) {
        return clients.computeIfAbsent(catalog, name -> {
            Map<String, String> properties = new HashMap<>();
            properties.put(CatalogProperties.URI, uri.toString());
            properties.put(CatalogProperties.WAREHOUSE_LOCATION, name);
            properties.put("rest.auth.type", BearerAuthManager.class.getName());
            properties.put(BearerAuthManager.SOURCE, source);
            properties.put(CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.aws.s3.S3FileIO");
            properties.put("http-client.type", "apache");
            properties.put("header.X-Iceberg-Access-Delegation", "vended-credentials");
            properties.putAll(extra);
            RESTCatalog client = new RESTCatalog();
            client.initialize("nuna-maintenance-" + name, properties);
            return client;
        });
    }

    @Override
    public void close() {
        try {
            for (RESTCatalog client : clients.values()) {
                client.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            clients.clear();
            BearerAuthManager.unregister(source);
        }
    }
}

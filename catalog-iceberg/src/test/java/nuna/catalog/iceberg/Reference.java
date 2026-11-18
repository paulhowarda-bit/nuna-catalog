package nuna.catalog.iceberg;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import nuna.catalog.iceberg.Outcome.BadRequest;
import nuna.catalog.iceberg.Outcome.NewMetadata;
import nuna.catalog.iceberg.Outcome.NoChange;
import nuna.catalog.iceberg.Outcome.NotFound;
import nuna.catalog.iceberg.Outcome.RequirementFailed;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.rest.CatalogHandlers;
import org.apache.iceberg.rest.RESTSerializers;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.RegisterViewRequestParser;
import org.apache.iceberg.rest.requests.UpdateTableRequestParser;

/**
 * iceberg-core 1.12.0's own reference behaviour: the request through CatalogHandlers, the handlers its
 * REST test server and fixture route to, over a fresh InMemoryCatalog. The result is the metadata file
 * that catalog wrote, byte for byte. The catalog defaults new tables to v3 through iceberg-core's own
 * {@code table-default.} catalog property, which is what D12 asks of ours.
 */
final class Reference {

    private static final Namespace NS = Namespace.of("ns");
    private static final TableIdentifier IDENT = TableIdentifier.of(NS, "t");
    private static final InMemoryFileIO FILES = new InMemoryFileIO();
    private static final ObjectMapper REQUESTS = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setPropertyNamingStrategy(new PropertyNamingStrategies.KebabCaseStrategy());

    static {
        RESTSerializers.registerAll(REQUESTS);
    }

    private Reference() {
    }

    static Outcome run(GoldenCase c) throws IOException {
        return run(c, Golden.request(c));
    }

    /** The reference outcome of {@code request} against the case's base. */
    static Outcome run(GoldenCase c, String request) throws IOException {
        InMemoryCatalog catalog = new InMemoryCatalog();
        catalog.initialize("reference", Map.of(
                CatalogProperties.WAREHOUSE_LOCATION, Golden.WAREHOUSE,
                CatalogProperties.TABLE_DEFAULT_PREFIX + TableProperties.FORMAT_VERSION, "3"));
        catalog.createNamespace(NS);
        String base = Golden.baseLocation(c);
        if (base != null) {
            FILES.addFile(base, Golden.baseJson(c).getBytes(StandardCharsets.UTF_8));
        }
        try {
            return switch (c.kind()) {
                case CREATE_TABLE -> written(CatalogHandlers.createTable(catalog, NS,
                        REQUESTS.readValue(request, CreateTableRequest.class)).tableMetadata().metadataFileLocation(),
                        null);
                case COMMIT_TABLE -> {
                    if (base != null) {
                        catalog.registerTable(IDENT, base);
                    }
                    yield written(CatalogHandlers.updateTable(catalog, IDENT, UpdateTableRequestParser.fromJson(request))
                            .tableMetadata().metadataFileLocation(), base);
                }
                case CREATE_VIEW -> written(CatalogHandlers.createView(catalog, NS,
                        REQUESTS.readValue(request, CreateViewRequest.class)).metadataLocation(), null);
                case COMMIT_VIEW -> {
                    catalog.registerView(IDENT, base);
                    yield written(CatalogHandlers.updateView(catalog, IDENT, UpdateTableRequestParser.fromJson(request))
                            .metadataLocation(), base);
                }
                // A register writes nothing: the outcome is the file the view now points at, the base itself.
                case REGISTER_VIEW -> written(CatalogHandlers.registerView(catalog, NS,
                        RegisterViewRequestParser.fromJson(request)).metadataLocation(), null);
            };
        } catch (CommitFailedException e) {
            return new RequirementFailed(e.getMessage());
        } catch (NoSuchTableException e) {
            return new NotFound();
        } catch (IllegalArgumentException | ValidationException e) {
            return new BadRequest(e.getMessage());
        }
    }

    /** The reference outcome of a table commit against any base, registered at {@code baseLocation}. */
    static Outcome commitTable(String baseJson, String baseLocation, String request) {
        InMemoryCatalog catalog = new InMemoryCatalog();
        catalog.initialize("reference", Map.of(CatalogProperties.WAREHOUSE_LOCATION, Golden.WAREHOUSE));
        catalog.createNamespace(NS);
        FILES.addFile(baseLocation, baseJson.getBytes(StandardCharsets.UTF_8));
        catalog.registerTable(IDENT, baseLocation);
        try {
            return written(CatalogHandlers.updateTable(catalog, IDENT, UpdateTableRequestParser.fromJson(request))
                    .tableMetadata().metadataFileLocation(), baseLocation);
        } catch (CommitFailedException e) {
            return new RequirementFailed(e.getMessage());
        } catch (IllegalArgumentException | ValidationException e) {
            return new BadRequest(e.getMessage());
        }
    }

    /**
     * The file the catalog now points at; the base itself means nothing was written. View metadata files
     * are gzip-compressed by default (write.metadata.compression-codec), so they are read through it.
     */
    private static Outcome written(String location, String base) {
        if (location.equals(base)) {
            return new NoChange();
        }
        boolean gzip = TableMetadataParser.Codec.fromFileName(location) == TableMetadataParser.Codec.GZIP;
        try (InputStream raw = FILES.newInputFile(location).newStream();
             InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
            return new NewMetadata(new String(in.readAllBytes(), StandardCharsets.UTF_8), null, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

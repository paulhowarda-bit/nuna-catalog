package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;

import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.StorageProfile;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A catalog's own properties, for the console's catalog page (D45, item 131): its boundary (D28), the storage
 * profile, KMS key set and legal entity it was declared with, and where its tables live. The Iceberg REST API has
 * nothing at the catalog level; a namespace's properties come from its Iceberg REST load.
 *
 * <p>Anyone who holds something in the catalog reads them, as the catalog list shows it to them; any other caller
 * is answered as for a catalog that does not exist, and the refusal is audited (D18, amended 2026-11-20).
 *
 * <p>Nothing secret is answered, and nothing that names an identity: the profile's access keys, its STS role, the
 * maintenance principal, its CA bundle's path and its KMS key ARN stay out. Whether writes use a KMS key is said,
 * not which.
 */
@RestController
class CatalogProperties {

    static final String PROPERTIES = CATALOG + "/properties";

    private final CatalogStore store;
    private final Management management;
    private final StorageSettings storage;

    CatalogProperties(CatalogStore store, Management management, StorageSettings storage) {
        this.store = store;
        this.management = management;
        this.storage = storage;
    }

    /**
     * {@code {"name", "kms-key-set", "legal-entity", "storage-profile": {"name", "kind", "endpoint", "region",
     * "table-root", "allowed-prefixes": [...], "encryption": "sse-kms" | "bucket-default"}}}.
     */
    @GetMapping(path = PROPERTIES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> read(@PathVariable String catalog, HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord record = management.catalog(catalog, request, "readCatalogProperties", null);
        CatalogSettings settings = store.catalogSettings(record.id()).orElseThrow(
                () -> new IllegalStateException("catalog " + record.name() + " has no storage profile"));
        StorageProfile profile = store.readStorageProfile(settings.storageProfileId()).orElseThrow(
                () -> new IllegalStateException("catalog " + record.name() + " names storage profile "
                        + settings.storageProfileId() + ", which the store does not hold"));
        StorageSettings.Profile configured = storage.profiles().get(profile.name());
        if (configured == null) {
            throw new IllegalStateException("catalog " + record.name() + " uses storage profile " + profile.name()
                    + ", which this server does not configure");
        }
        ObjectNode body = Management.JSON.createObjectNode();
        body.put("name", record.name());
        body.put("kms-key-set", settings.kmsKeySet());
        body.put("legal-entity", settings.legalEntity());
        ObjectNode out = body.putObject("storage-profile");
        out.put("name", profile.name());
        out.put("kind", profile.kind().name());
        out.put("endpoint", configured.endpoint());
        out.put("region", configured.region());
        out.put("table-root", configured.tableRoot());
        ArrayNode prefixes = out.putArray("allowed-prefixes");
        profile.allowedLocations().forEach(prefixes::add);
        out.put("encryption", profile.kmsKey().isPresent() ? "sse-kms" : "bucket-default");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(Management.JSON.writeValueAsString(body));
    }
}

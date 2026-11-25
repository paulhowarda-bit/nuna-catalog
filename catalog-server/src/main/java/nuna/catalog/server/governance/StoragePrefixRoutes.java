package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.ROOT;

import nuna.catalog.server.storage.StorageSettings;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where the maintenance worker lists and deletes (D30, amended 2026-11-24; item 256): the prefixes of every storage
 * profile that names a {@code maintenance-principal}, which the worker reads at each scan, for a maintenance principal
 * acting as itself only. A profile with none is left out: its store has no delete policy, so the worker's identity is
 * not the one that deletes there. Each profile gives its name, kind, endpoint and allowed prefixes and nothing else,
 * never a credential: the worker matches a profile to one of its own store identities by kind and endpoint, and takes
 * its region, keys and CA bundle from its own settings alone. A refusal is audited, as on every management route.
 */
@RestController
class StoragePrefixRoutes {

    static final String STORAGE_PREFIXES = ROOT + "/maintenance/storage-prefixes";

    private final StorageSettings settings;
    private final Management management;
    private final ManagementGuard guard;

    StoragePrefixRoutes(StorageSettings settings, Management management, ManagementGuard guard) {
        this.settings = settings;
        this.management = management;
        this.guard = guard;
    }

    /** {@code {"profiles": [{"name", "kind", "endpoint", "prefixes": [...]}, ...]}}, by name. */
    @GetMapping(path = STORAGE_PREFIXES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> prefixes(HttpServletRequest http) {
        guard.requireMaintenance(management.request(http), null, "listMaintenanceStoragePrefixes");
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode profiles = body.putArray("profiles");
        for (Map.Entry<String, StorageSettings.Profile> entry : new TreeMap<>(settings.profiles()).entrySet()) {
            StorageSettings.Profile profile = entry.getValue();
            if (profile.maintenancePrincipal() == null) {
                continue;
            }
            ObjectNode item = profiles.addObject();
            item.put("name", entry.getKey());
            item.put("kind", profile.kind().name().toLowerCase(Locale.ROOT));
            item.put("endpoint", profile.endpoint());
            ArrayNode prefixes = item.putArray("prefixes");
            profile.allowedPrefixes().forEach(prefixes::add);
        }
        return GovernanceRoutes.json(200, body);
    }
}

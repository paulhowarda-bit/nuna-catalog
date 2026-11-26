package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.ROOT;

import nuna.catalog.objectstore.ObjectLocation;
import nuna.catalog.objectstore.StorageProfile;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.MaintenanceScan;
import nuna.catalog.store.WorkerStorage;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a platform operator does outside the catalog so the maintenance worker can maintain each storage profile's
 * catalogs (backlog 253), for the console's "What to set up" (backlog 254): for each profile, its catalogs and
 * prefixes, what the worker last reported reaching on its store (backlog 250), the worker's settings for the store when
 * that report shows no identity of the worker's there (since item 256 a new bucket on a store the worker reaches needs
 * none; and none while the newest scan reported no storage, item 275), and the profile's delete policies with the commands that apply them, or the catalog setting that a profile
 * naming no maintenance principal lacks. The catalog writes all of it and applies none of it (D21, D30), and it never
 * holds or gives a key of the worker's: the settings mark where the worker's key goes. Platform operators only, as the
 * delete policies are; a refusal is audited, the read is not. Catalogs are named, never a namespace, table or view
 * (D18). Shape: docs/observability/table-health.md, "Storage setup".
 */
@RestController
class StorageSetupRoutes {

    static final String STORAGE_SETUP = ROOT + "/maintenance/storage-setup";
    /** Where the worker's access key goes in its settings: the catalog never holds one. */
    static final String KEY = "<the worker's access key on this store>";
    /** Where the worker's CA bundle goes, for a store whose certificate a private CA signed. */
    static final String CA_BUNDLE = "<the store's CA bundle in the worker's pod, from storageCaConfigMap>";
    /** Where a MinIO alias goes, which names the store to {@code mc}. */
    static final String ALIAS = "<alias>";
    /** Where each other user of a MinIO store goes. */
    static final String USER = "<user>";

    private final StorageSettings settings;
    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;

    StorageSetupRoutes(StorageSettings settings, CatalogStore store, Management management, ManagementGuard guard) {
        this.settings = settings;
        this.store = store;
        this.management = management;
        this.guard = guard;
    }

    /**
     * {@code {"scanned-at"?, "reported-at"?, "profiles": [{"name", "catalogs", "kind", "endpoint", "prefixes",
     * "maintenance-principal"?, "worker", "worker-settings"?, "store"}, ...]}}, by profile name. The worker's reach is
     * judged as readiness judges it ({@link ReadinessRoutes.Reported}): the listing from the newest scan when it
     * reported storage, the deletes from the newest such scan that has ended.
     */
    @GetMapping(path = STORAGE_SETUP, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> storageSetup(HttpServletRequest http) {
        guard.requireOperator(management.request(http), null, "getMaintenanceStorageSetup");
        List<MaintenanceScan> scans = store.listScans();
        ReadinessRoutes.Reported reported = ReadinessRoutes.Reported.of(scans);
        Optional<MaintenanceScan> last = reported.last();
        Optional<MaintenanceScan> ended = reported.ended();
        // The newest scan reported no storage, as when it could not read its storage prefixes (item 275): whether the
        // worker holds an identity on a store is not known, so it is told to add none.
        boolean unknown = !scans.isEmpty() && last.isEmpty();
        List<DeletePolicyRoutes.Protected> named = new ArrayList<>();
        settings.profiles().forEach((name, profile) -> {
            if (profile.maintenancePrincipal() != null) {
                named.add(new DeletePolicyRoutes.Protected(name, parse(profile), profile.maintenancePrincipal()));
            }
        });
        ArrayNode policies = DeletePolicyRoutes.policies(named);

        ObjectNode body = Management.JSON.createObjectNode();
        scans.stream().findFirst().ifPresent(scan -> body.put("scanned-at", ReadinessRoutes.reportedAt(scan)));
        last.ifPresent(scan -> body.put("reported-at", ReadinessRoutes.reportedAt(scan)));
        ArrayNode profiles = body.putArray("profiles");
        for (String name : new TreeSet<>(settings.profiles().keySet())) {
            StorageSettings.Profile profile = settings.profiles().get(name);
            List<String> prefixes = parse(profile).allowedPrefixes().stream().map(ObjectLocation::toString).toList();
            ObjectNode item = profiles.addObject();
            item.put("name", name);
            ArrayNode catalogs = item.putArray("catalogs");
            settings.catalogs().entrySet().stream().filter(catalog -> name.equals(catalog.getValue().profile()))
                    .map(Map.Entry::getKey).sorted(String.CASE_INSENSITIVE_ORDER).forEach(catalogs::add);
            item.put("kind", kind(profile));
            item.put("endpoint", profile.endpoint());
            ArrayNode listed = item.putArray("prefixes");
            prefixes.forEach(listed::add);
            if (profile.maintenancePrincipal() != null) {
                item.put("maintenance-principal", profile.maintenancePrincipal());
            }
            Optional<WorkerStorage> listing = last.flatMap(scan -> identity(scan, profile));
            item.set("worker", worker(prefixes, listing, ended.flatMap(scan -> identity(scan, profile)), unknown));
            if (listing.isEmpty() && !unknown) {
                item.set("worker-settings", workerSettings(name, profile));
            }
            item.set("store", profile.maintenancePrincipal() == null ? unprotected(name)
                    : generated(name, profile, policies));
        }
        return GovernanceRoutes.json(200, body);
    }

    /** The profile as the server parsed it at start, which it refused to start without. */
    private static StorageProfile parse(StorageSettings.Profile profile) {
        return StorageProfile.of(profile.kind(), profile.endpoint(), profile.region(), profile.tableRoot(),
                profile.allowedPrefixes());
    }

    private static String kind(StorageSettings.Profile profile) {
        return profile.kind().name().toLowerCase(Locale.ROOT);
    }

    /**
     * The worker's identity on the profile's store in the scan's report: the one of the profile's kind whose endpoint
     * is the profile's, compared as the worker compares them. The worker reports an identity once a prefix it covers
     * is the catalog's, so a store holding only profiles that name no maintenance principal is never reported.
     */
    private static Optional<WorkerStorage> identity(MaintenanceScan scan, StorageSettings.Profile profile) {
        Optional<String> endpoint = endpoint(profile.endpoint());
        return scan.storage().stream().flatMap(List::stream)
                .filter(reported -> reported.kind().equals(kind(profile)) && endpoint.isPresent()
                        && reported.endpoint().flatMap(StorageSetupRoutes::endpoint).equals(endpoint))
                .findFirst();
    }

    /**
     * An endpoint as the worker matches a profile to its store (item 256): scheme, host and port, the scheme's port
     * when none is written; empty for one the worker reported that is no URI.
     */
    private static Optional<String> endpoint(String text) {
        try {
            URI uri = URI.create(text);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return Optional.empty();
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
            return Optional.of(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * {@code reported} with what the store answered beneath each of the profile's prefixes, when the worker's report
     * shows an identity on its store; {@code unknown} when the newest scan reported no storage; {@code not-reported}
     * when it shows none, or no worker has reported.
     */
    private static ObjectNode worker(List<String> prefixes, Optional<WorkerStorage> listing,
                                     Optional<WorkerStorage> deleting, boolean unknown) {
        ObjectNode node = Management.JSON.createObjectNode().put("state", listing.isPresent() ? "reported"
                : unknown ? "unknown" : "not-reported");
        if (listing.isPresent()) {
            ArrayNode each = node.putArray("prefixes");
            prefixes.forEach(prefix -> each.add(ReadinessRoutes.reach(prefix, beneath(listing, prefix),
                    beneath(deleting, prefix))));
        }
        return node;
    }

    private static Optional<WorkerStorage.Prefix> beneath(Optional<WorkerStorage> identity, String prefix) {
        return identity.flatMap(reported -> WorkerStorage.covering(List.of(reported), prefix))
                .map(WorkerStorage.Covering::prefix);
    }

    /**
     * The worker's settings for a store it holds no identity on, to paste under {@code nuna.maintenance.storage}:
     * named after the profile, with its kind, endpoint and region, and the key's place marked; the secret's name; on
     * AWS S3, that a worker role (IRSA, Pod Identity) leaves the keys out.
     */
    private static ObjectNode workerSettings(String name, StorageSettings.Profile profile) {
        StringBuilder yaml = new StringBuilder().append(name).append(":\n")
                .append("  kind: ").append(kind(profile)).append('\n')
                .append("  endpoint: ").append(profile.endpoint()).append('\n')
                .append("  region: ").append(profile.region()).append('\n')
                .append("  access-key-id: ").append(KEY).append('\n');
        if (profile.caBundle() != null) {
            yaml.append("  ca-bundle: ").append(CA_BUNDLE).append('\n');
        }
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("store", name);
        node.put("yaml", yaml.toString());
        node.put("secret", "nuna.maintenance.storage." + name + ".secret-access-key");
        if (profile.kind() == StoreKind.S3) {
            node.put("note", "With a role for the worker (IRSA or Pod Identity), leave access-key-id out and add no"
                    + " secret: the worker signs in through the AWS SDK's default chain.");
        }
        return node;
    }

    /** No delete policy: the catalog setting that names the worker's identity on the store. */
    private static ObjectNode unprotected(String name) {
        return Management.JSON.createObjectNode().put("state", "unprotected")
                .put("setting", "nuna.storage.profiles." + name + ".maintenance-principal");
    }

    /**
     * The delete policies naming the profile, as {@code GET .../delete-policies} gives them, each with the commands
     * that apply it: on AWS S3 and Ceph its bucket's policy, with {@code aws s3api put-bucket-policy} ({@code
     * --endpoint-url} on Ceph); on MinIO the worker's policy ({@code to}) and every other user's ({@code except}),
     * each named after the profile, so two profiles on one store keep both, with {@code mc admin policy create} and
     * {@code attach}.
     */
    private static ObjectNode generated(String name, StorageSettings.Profile profile, ArrayNode policies) {
        ObjectNode node = Management.JSON.createObjectNode().put("state", "generated");
        ArrayNode mine = node.putArray("policies");
        for (JsonNode policy : policies) {
            if (policy.get("profiles").valueStream().noneMatch(listed -> listed.asString().equals(name))) {
                continue;
            }
            String text = policy.get("policy").asString();
            ObjectNode entry = mine.addObject().put("apply-as", policy.get("apply-as").asString());
            if (policy.has("bucket")) {
                String bucket = policy.get("bucket").asString();
                entry.put("bucket", bucket);
                entry.set("profiles", policy.get("profiles").deepCopy());
                entry.put("policy", text);
                entry.putArray("commands").add("aws s3api put-bucket-policy --bucket " + bucket
                        + (profile.kind() == StoreKind.CEPH ? " --endpoint-url " + profile.endpoint() : "")
                        + " --policy '" + text + "'");
            } else {
                String users = policy.has("to") ? "to" : "except";
                String principal = policy.get(users).asString();
                String policyName = (users.equals("to") ? "nuna-worker-" : "nuna-no-delete-") + name;
                entry.put(users, principal);
                entry.put("name", policyName);
                entry.put("file", policyName + ".json");
                entry.put("policy", text);
                entry.putArray("commands")
                        .add("mc admin policy create " + ALIAS + " " + policyName + " " + policyName + ".json")
                        .add("mc admin policy attach " + ALIAS + " " + policyName + " --user "
                                + (users.equals("to") ? principal : USER));
            }
        }
        return node;
    }
}

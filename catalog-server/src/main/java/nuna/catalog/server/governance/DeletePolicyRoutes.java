package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.ROOT;

import nuna.catalog.objectstore.DeletePolicy;
import nuna.catalog.objectstore.ObjectLocation;
import nuna.catalog.objectstore.StorageProfile;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.server.storage.StorageSettings;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The policies that leave delete to the maintenance worker alone (D30), for a platform operator to apply once
 * per store. The catalog never applies them itself: its own credentials would then need the right to undo them.
 */
@RestController
class DeletePolicyRoutes {

    static final String DELETE_POLICIES = ROOT + "/maintenance/delete-policies";

    /** One bucket on one store; profiles may share it with disjoint prefixes, and it holds one policy. */
    private record Bucket(StoreKind kind, URI endpoint, String region, String name) {
    }

    private final CatalogStorage storage;
    private final StorageSettings settings;
    private final Management management;
    private final ManagementGuard guard;

    DeletePolicyRoutes(CatalogStorage storage, StorageSettings settings, Management management, ManagementGuard guard) {
        this.storage = storage;
        this.settings = settings;
        this.management = management;
        this.guard = guard;
    }

    /**
     * One bucket policy per bucket on AWS S3 and Ceph, holding every profile's prefixes there, to apply with
     * PutBucketPolicy; it also lets each worker delete and list beneath its prefixes. Two user policies per MinIO
     * profile, since MinIO enforces no bucket policy on signed requests: one to attach to every user but the worker
     * ({@code except}), the catalog's own included, and one to attach to the worker ({@code to}), which lets it
     * delete and list beneath the prefixes. A profile with no {@code maintenance-principal} is listed as unprotected.
     */
    @GetMapping(path = DELETE_POLICIES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> deletePolicies(HttpServletRequest http) {
        guard.requireOperator(management.request(http), null, "getDeletePolicies");
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode policies = body.putArray("policies");
        ArrayNode unprotected = body.putArray("unprotected");
        List<Protected> named = new ArrayList<>();
        for (CatalogStorage.Bound bound : storage.profiles()) {
            String principal = settings.profiles().get(bound.name()).maintenancePrincipal();
            if (principal == null) {
                unprotected.add(bound.name());
            } else {
                named.add(new Protected(bound.name(), bound.profile(), principal));
            }
        }
        policies.addAll(policies(named));
        return GovernanceRoutes.json(200, body);
    }

    /** A profile that names a {@code maintenance-principal}: its name, its store and prefixes, and that principal. */
    record Protected(String name, StorageProfile profile, String principal) {
    }

    /**
     * The delete policies of {@code profiles}, in their order, as {@link #deletePolicies} lists them: each MinIO
     * profile's two user policies, then one bucket policy per bucket on AWS S3 and Ceph, naming every profile with
     * prefixes there. Shared with {@link StorageSetupRoutes}, so both routes give the same text.
     */
    static ArrayNode policies(List<Protected> profiles) {
        ArrayNode policies = Management.JSON.createArrayNode();
        Map<Bucket, List<String>> names = new LinkedHashMap<>();
        Map<Bucket, List<DeletePolicy.Worker>> workers = new LinkedHashMap<>();
        for (Protected each : profiles) {
            StorageProfile profile = each.profile();
            if (profile.kind() == StoreKind.MINIO) {
                userPolicy(policies, each.name(), profile, "except", each.principal(),
                        DeletePolicy.userPolicy(profile.allowedPrefixes()));
                userPolicy(policies, each.name(), profile, "to", each.principal(),
                        DeletePolicy.workerPolicy(profile.allowedPrefixes()));
            } else {
                profile.allowedPrefixes().stream()
                        .collect(Collectors.groupingBy(ObjectLocation::bucket, LinkedHashMap::new, Collectors.toList()))
                        .forEach((name, prefixes) -> {
                            Bucket bucket = new Bucket(profile.kind(), profile.endpoint(), profile.region(), name);
                            names.computeIfAbsent(bucket, b -> new ArrayList<>()).add(each.name());
                            workers.computeIfAbsent(bucket, b -> new ArrayList<>())
                                    .add(new DeletePolicy.Worker(each.principal(), prefixes));
                        });
            }
        }
        workers.forEach((bucket, each) -> {
            ObjectNode policy = policies.addObject();
            policy.put("apply-as", "bucket-policy");
            policy.put("endpoint", bucket.endpoint().toString());
            policy.put("bucket", bucket.name());
            ArrayNode listed = policy.putArray("profiles");
            names.get(bucket).forEach(listed::add);
            policy.put("policy", DeletePolicy.bucketPolicy(bucket.kind(), bucket.region(), bucket.name(), each));
        });
        return policies;
    }

    /** A MinIO user policy for one profile; {@code users} is {@code except} or {@code to}, naming the worker. */
    private static void userPolicy(ArrayNode policies, String name, StorageProfile profile, String users,
                                   String principal, String text) {
        ObjectNode policy = policies.addObject();
        policy.put("apply-as", "user-policy");
        policy.put("endpoint", profile.endpoint().toString());
        policy.putArray("profiles").add(name);
        policy.put(users, principal);
        policy.put("policy", text);
    }
}

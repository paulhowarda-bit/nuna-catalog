package keel.catalog.server.governance;

import static keel.catalog.server.governance.Management.ROOT;

import keel.catalog.objectstore.DeletePolicy;
import keel.catalog.objectstore.ObjectLocation;
import keel.catalog.objectstore.StorageProfile;
import keel.catalog.objectstore.StoreKind;
import keel.catalog.server.storage.CatalogStorage;
import keel.catalog.server.storage.StorageSettings;
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
     * PutBucketPolicy; one user policy per MinIO profile, to attach to every user but the worker, the catalog's own
     * included, since MinIO enforces no bucket policy on signed requests. A profile with no
     * {@code maintenance-principal} is listed as unprotected.
     */
    @GetMapping(path = DELETE_POLICIES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> deletePolicies(HttpServletRequest http) {
        guard.requireOperator(management.request(http), null, "getDeletePolicies");
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode policies = body.putArray("policies");
        ArrayNode unprotected = body.putArray("unprotected");
        Map<Bucket, List<String>> profiles = new LinkedHashMap<>();
        Map<Bucket, List<DeletePolicy.Worker>> workers = new LinkedHashMap<>();
        for (CatalogStorage.Bound bound : storage.profiles()) {
            String principal = settings.profiles().get(bound.name()).maintenancePrincipal();
            StorageProfile profile = bound.profile();
            if (principal == null) {
                unprotected.add(bound.name());
            } else if (profile.kind() == StoreKind.MINIO) {
                ObjectNode policy = policies.addObject();
                policy.put("apply-as", "user-policy");
                policy.put("endpoint", profile.endpoint().toString());
                policy.putArray("profiles").add(bound.name());
                policy.put("except", principal);
                policy.put("policy", DeletePolicy.userPolicy(profile.allowedPrefixes()));
            } else {
                profile.allowedPrefixes().stream()
                        .collect(Collectors.groupingBy(ObjectLocation::bucket, LinkedHashMap::new, Collectors.toList()))
                        .forEach((name, prefixes) -> {
                            Bucket bucket = new Bucket(profile.kind(), profile.endpoint(), profile.region(), name);
                            profiles.computeIfAbsent(bucket, b -> new ArrayList<>()).add(bound.name());
                            workers.computeIfAbsent(bucket, b -> new ArrayList<>())
                                    .add(new DeletePolicy.Worker(principal, prefixes));
                        });
            }
        }
        workers.forEach((bucket, each) -> {
            ObjectNode policy = policies.addObject();
            policy.put("apply-as", "bucket-policy");
            policy.put("endpoint", bucket.endpoint().toString());
            policy.put("bucket", bucket.name());
            ArrayNode names = policy.putArray("profiles");
            profiles.get(bucket).forEach(names::add);
            policy.put("policy", DeletePolicy.bucketPolicy(bucket.kind(), bucket.region(), bucket.name(), each));
        });
        return GovernanceRoutes.json(200, body);
    }
}

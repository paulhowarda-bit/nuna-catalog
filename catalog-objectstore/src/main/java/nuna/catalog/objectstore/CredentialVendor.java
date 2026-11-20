package nuna.catalog.objectstore;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.Tag;

/**
 * Vends temporary credentials for one table's prefix through STS AssumeRole, for one storage profile
 * (D21). The session policy confines them to the table; the session name and tags say who they are
 * for. On-prem stores (MinIO, Ceph) answer STS on their S3 endpoint; AWS uses the regional STS endpoint.
 */
public final class CredentialVendor implements AutoCloseable {

    /** D21: vended credentials live 15 to 60 minutes. */
    public static final Duration MIN_LIFETIME = Duration.ofMinutes(15);
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(60);

    private final StorageProfile profile;
    private final Optional<String> roleArn;
    private final Duration lifetime;
    private final StsClient sts;

    /**
     * @param roleArn     the role STS assumes; AWS and Ceph need one, MinIO derives the session from the
     *                    calling user and needs none
     * @param credentials the catalog's own credentials, which call STS
     * @param lifetime    how long each credential lives, within 15 to 60 minutes
     * @param caBundle    CAs the STS client trusts beside the JVM's: the profile's, for an on-prem store
     */
    public CredentialVendor(StorageProfile profile, Optional<String> roleArn, AwsCredentialsProvider credentials,
                            Duration lifetime, Optional<CaBundle> caBundle) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.roleArn = Objects.requireNonNull(roleArn, "roleArn");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.compareTo(MIN_LIFETIME) < 0 || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("credential lifetime must be 15 to 60 minutes, got " + lifetime);
        }
        if (roleArn.isEmpty() && profile.kind() != StoreKind.MINIO) {
            throw new IllegalArgumentException(profile.kind() + " needs an STS role to vend credentials");
        }
        // Every table lives at <table root>/<uuid>, so this is the longest policy the profile ever sends.
        SessionPolicy.forTable(profile.tableRoot().child(new UUID(0, 0).toString()), Access.READ_WRITE,
                profile.region());
        StsClientBuilder builder = StsClient.builder()
                .region(Region.of(profile.region()))
                .credentialsProvider(credentials);
        if (profile.kind() != StoreKind.S3) {
            builder.endpointOverride(profile.endpoint());
        }
        caBundle.ifPresent(bundle -> bundle.applyTo(builder));
        this.sts = builder.build();
    }

    /** How long each credential is asked to live. */
    public Duration lifetime() {
        return lifetime;
    }

    /**
     * Assumes the role with a session policy confined to {@code location}.
     *
     * @param location the table location; it must lie beneath an allowed prefix of the profile
     * @throws LocationRejectedException when it does not
     * @throws ObjectStoreException      when STS refuses or cannot be reached
     */
    public VendedCredentials vend(String location, Access access, SessionIdentity identity) {
        ObjectLocation table = profile.approve(location);
        String policy = SessionPolicy.forTable(table, access, profile.region());
        String sessionName = identity.sessionName();
        AssumeRoleResponse response;
        try {
            response = sts.assumeRole(request -> {
                request.roleSessionName(sessionName)
                        .policy(policy)
                        .durationSeconds((int) lifetime.toSeconds())
                        .tags(identity.tags().entrySet().stream()
                                .map(tag -> Tag.builder().key(tag.getKey()).value(tag.getValue()).build())
                                .toList());
                roleArn.ifPresent(request::roleArn);
            });
        } catch (SdkException e) {
            throw new ObjectStoreException("STS refused credentials for " + table + " (session " + sessionName + ")", e);
        }
        Credentials credentials = response.credentials();
        return new VendedCredentials(credentials.accessKeyId(), credentials.secretAccessKey(),
                credentials.sessionToken(), credentials.expiration(), table.toString(), access, sessionName);
    }

    @Override
    public void close() {
        sts.close();
    }
}

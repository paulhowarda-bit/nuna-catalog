package keel.catalog.iceberg;

import com.fasterxml.jackson.core.JsonProcessingException;
import keel.catalog.iceberg.RestRequest.Identifier;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.credentials.ImmutableCredential;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ImmutableLoadCredentialsResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadCredentialsResponseParser;
import org.apache.iceberg.rest.responses.UpdateNamespacePropertiesResponse;

/**
 * Iceberg REST response bodies for namespaces, listings and credentials, written with iceberg-core's
 * own response classes and its REST object mapping (P5). Table and view loads are in
 * {@link MetadataViews}.
 */
public final class RestResponse {

    private RestResponse() {
    }

    /** One vended storage credential: the location prefix it covers and its FileIO properties. */
    public record StorageCredential(String prefix, Map<String, String> config) {
        public StorageCredential {
            config = Map.copyOf(config);
        }
    }

    /** {@code GET .../namespaces}; {@code nextPageToken} is null on the last page. */
    public static String namespaces(List<List<String>> namespaces, String nextPageToken) {
        return write(ListNamespacesResponse.builder()
                .addAll(namespaces.stream().map(RestResponse::namespace).toList())
                .nextPageToken(nextPageToken)
                .build());
    }

    /** {@code GET .../namespaces/{namespace}}, and the create-namespace response, which has the same form. */
    public static String namespace(List<String> levels, Map<String, String> properties) {
        return write(GetNamespaceResponse.builder()
                .withNamespace(namespace(levels))
                // The builder probes containsKey(null), which immutable maps (Map.of) reject; copy first.
                .setProperties(new HashMap<>(properties))
                .build());
    }

    public static String propertiesUpdated(Collection<String> updated, Collection<String> removed,
                                           Collection<String> missing) {
        return write(UpdateNamespacePropertiesResponse.builder()
                // The builder probes contains(null), which immutable lists reject; copy into mutable ones.
                .addUpdated(new ArrayList<>(updated))
                .addRemoved(new ArrayList<>(removed))
                .addMissing(new ArrayList<>(missing))
                .build());
    }

    /** {@code GET .../tables} and {@code GET .../views}. */
    public static String identifiers(List<Identifier> identifiers, String nextPageToken) {
        return write(ListTablesResponse.builder()
                .addAll(identifiers.stream()
                        .map(id -> TableIdentifier.of(namespace(id.namespace()), id.name()))
                        .toList())
                .nextPageToken(nextPageToken)
                .build());
    }

    /** {@code GET .../tables/{table}/credentials}. */
    public static String credentials(List<StorageCredential> credentials) {
        return LoadCredentialsResponseParser.toJson(ImmutableLoadCredentialsResponse.builder()
                .addAllCredentials(MetadataViews.credentials(credentials))
                .build());
    }

    /** Dotted form of a namespace, as iceberg-core prints it in messages. */
    public static String dotted(List<String> levels) {
        return namespace(levels).toString();
    }

    static Namespace namespace(List<String> levels) {
        return Namespace.of(levels.toArray(String[]::new));
    }

    static ImmutableCredential credential(StorageCredential credential) {
        return ImmutableCredential.builder().prefix(credential.prefix()).config(credential.config()).build();
    }

    private static String write(Object response) {
        try {
            return Metadata.REQUESTS.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}

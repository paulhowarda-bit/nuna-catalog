package keel.catalog.server.tracing;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import keel.catalog.core.commit.TableFiles;

/**
 * One storage profile's metadata files with each request to the object store observed as {@value #NAME}
 * (docs/observability/spans.md): a span named {@code objectstore <method>} and a timer, tagged with the profile, the
 * method and the outcome. Never the location: it names the table.
 */
public final class ObservedTableFiles implements TableFiles {

    public static final String NAME = "keel.objectstore";

    private final TableFiles files;
    private final KeyValues profile;
    private final ObservationRegistry registry;

    /** @param profile the storage profile's configured name */
    public ObservedTableFiles(TableFiles files, String profile, ObservationRegistry registry) {
        this.files = files;
        this.profile = KeyValues.of("profile", profile);
        this.registry = registry;
    }

    /** Assigns a name; reaches no store. */
    @Override
    public String newLocation(UUID objectId) {
        return files.newLocation(objectId);
    }

    @Override
    public Written write(String locationRoot, long version, String json) {
        return observe("write", () -> files.write(locationRoot, version, json), written -> "written");
    }

    /** {@code intact}, or {@code mismatch} when the bytes read do not hash as expected. */
    @Override
    public Read read(String location, String expectedSha256) {
        return observe("read", () -> files.read(location, expectedSha256),
                read -> read.intact() ? "intact" : "mismatch");
    }

    /** Compares locations; reaches no store. */
    @Override
    public boolean isWithin(String location, String root) {
        return files.isWithin(location, root);
    }

    @Override
    public List<String> metadataFiles(String locationRoot) {
        return observe("metadataFiles", () -> files.metadataFiles(locationRoot), listed -> "listed");
    }

    @Override
    public List<String> tableLocations() {
        return observe("tableLocations", files::tableLocations, listed -> "listed");
    }

    private <T> T observe(String operation, Supplier<T> call, Function<? super T, String> outcome) {
        return Observed.call(registry, NAME, "objectstore " + operation, profile.and("operation", operation), call,
                outcome);
    }
}

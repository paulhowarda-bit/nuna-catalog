package keel.catalog.audit;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A storage profile set, or a catalog created on one (D21, D28), as the deployment's configuration
 * declares them. Credentials for the store are never recorded.
 *
 * @param action   what happened
 * @param id       the profile's or the catalog's id
 * @param name     its name
 * @param settings what was set, sorted by key: for a profile its kind, endpoint, role and allowed
 *                 locations; for a catalog its storage profile, KMS key set and legal entity
 * @param actor    who set it
 */
public record StorageSetup(Action action, UUID id, String name, Map<String, String> settings, Actor actor)
        implements EventData {

    public enum Action { PROFILE_SET, CATALOG_CREATED }

    public StorageSetup {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(actor, "actor");
        settings = Collections.unmodifiableMap(new TreeMap<>(settings));
    }

    @Override
    public EventType type() {
        return EventType.STORAGE_SETUP;
    }

    @Override
    public String subject() {
        return id.toString();
    }
}

package nuna.catalog.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A storage profile's id is derived from its name, and every catalog's binding in the store names that id (D28).
 * Renaming the project changed the seed once (keel to nuna, backlog 59), so a server of the new release refused every
 * catalog an older one had bound (backlog 117). The id is pinned here, so a change to what it is made from fails the
 * fast build instead of an upgrade.
 */
class ProfileIdTest {

    @Test
    @DisplayName("a profile's id is the one every release since the rename to nuna derives: changing it strands every"
            + " catalog bound in the store")
    void profileIdNeverChanges() {
        assertThat(CatalogStorage.profileId("s3"))
                .isEqualTo(UUID.fromString("cee40642-fb00-30d6-8291-93bd91ffcec3"));
    }
}

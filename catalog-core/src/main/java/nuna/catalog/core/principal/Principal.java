package nuna.catalog.core.principal;

import java.util.Set;

/**
 * One person or service as the catalog knows it: the same principal whichever trusted issuer's
 * token named it (D17a). Grants go to groups, never to individuals (D19), so the groups are what
 * authorization reads and the id is what the journal and audit record.
 *
 * @param id     stable identifier, from the claim the issuer's claim mapping names
 * @param groups the IdP groups the token carried
 */
public record Principal(String id, Set<String> groups) {

    public Principal {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a principal needs a non-blank id");
        }
        groups = Set.copyOf(groups);
    }
}

package fidice.catalog.store;

import java.util.Objects;
import java.util.Set;

/**
 * A registered shared engine that may assert the end user it acts for (D17): its client id at an
 * issuer, the token claim that carries the end user, and flags. Flags are single words.
 */
public record TrustedEngine(String issuer, String clientId, String endUserClaim, Set<String> flags) {

    public TrustedEngine {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(endUserClaim, "endUserClaim");
        flags = Set.copyOf(flags);
        for (String flag : flags) {
            if (flag.isBlank() || flag.chars().anyMatch(c -> c == ',' || Character.isWhitespace(c))) {
                throw new IllegalArgumentException("a flag is one word, got '" + flag + "'");
            }
        }
    }
}

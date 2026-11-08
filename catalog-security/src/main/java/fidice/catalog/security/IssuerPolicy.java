package fidice.catalog.security;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * One trusted token issuer (D16, D17a). The keys come only from the configured JWKS URI, never
 * from anything a token says. {@link SignatureAlgorithm} holds only asymmetric algorithms, so
 * neither {@code none} nor an HMAC algorithm can be allowed.
 *
 * @param issuer     the exact {@code iss} value
 * @param jwksUri    where the issuer publishes its signing keys
 * @param audiences  a token must name at least one of these in {@code aud}
 * @param algorithms the signature algorithms accepted from this issuer
 * @param claims     how this issuer's claims map to a principal
 */
public record IssuerPolicy(String issuer, URI jwksUri, Set<String> audiences,
                           Set<SignatureAlgorithm> algorithms, ClaimMapping claims) {

    public IssuerPolicy {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(jwksUri, "jwksUri");
        Objects.requireNonNull(claims, "claims");
        audiences = Set.copyOf(audiences);
        algorithms = Set.copyOf(algorithms);
        if (audiences.isEmpty()) {
            throw new IllegalArgumentException("issuer " + issuer + " accepts no audience");
        }
        // Spring falls back to RS256 when given no algorithms; an empty allow-list must not mean that.
        if (algorithms.isEmpty()) {
            throw new IllegalArgumentException("issuer " + issuer + " allows no signature algorithm");
        }
    }
}

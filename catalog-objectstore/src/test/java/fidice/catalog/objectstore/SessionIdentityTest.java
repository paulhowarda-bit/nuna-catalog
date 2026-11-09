package fidice.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionIdentityTest {

    private static final UUID TABLE = UUID.fromString("0d1c5b0a-6f3e-4c2a-9d7e-3b8f1a2c4e5f");
    private static final String REQUEST = "5f0e8a5c-1b2d-4e3f-8a9b-0c1d2e3f4a5b";

    @Test
    @DisplayName("the session name carries the principal and the request id")
    void nameCarriesPrincipalAndRequest() {
        SessionIdentity identity = new SessionIdentity("alice@corp.example", Optional.empty(), "spark", REQUEST,
                "trading", TABLE);

        assertThat(identity.sessionName()).isEqualTo("alice@corp.example_" + REQUEST);
    }

    @Test
    @DisplayName("an engine acting for a user is named by the user, and the tags carry both")
    void endUser() {
        SessionIdentity identity = new SessionIdentity("svc-trino", Optional.of("bob"), "trino", "r1", "trading",
                TABLE);

        assertThat(identity.sessionName()).isEqualTo("bob_r1");
        assertThat(identity.tags()).containsExactly(
                Map.entry("fidice-principal", "svc-trino"),
                Map.entry("fidice-end-user", "bob"),
                Map.entry("fidice-engine", "trino"),
                Map.entry("fidice-request", "r1"),
                Map.entry("fidice-catalog", "trading"),
                Map.entry("fidice-table", TABLE.toString()));
    }

    @Test
    @DisplayName("characters STS refuses are replaced, and long values are cut to the STS limits")
    void limits() {
        String principal = "CN=Alice Smith, OU=Trading/Desk#1 " + "p".repeat(300);
        SessionIdentity identity = new SessionIdentity(principal, Optional.empty(), "e", "req id;" + "9".repeat(60),
                "trading", TABLE);

        String name = identity.sessionName();
        assertThat(name).hasSize(SessionIdentity.NAME_MAX).matches("[\\w+=,.@-]+");
        assertThat(name).isEqualTo("CN=Alice-Smith,-OU=Trading-_req-id-" + "9".repeat(29));
        assertThat(identity.tags().get("fidice-principal"))
                .hasSize(SessionIdentity.TAG_VALUE_MAX)
                .startsWith("CN=Alice Smith, OU=Trading/Desk-1 ".replace(",", "-"));
        assertThat(identity.tags().values()).allMatch(value -> value.matches("[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]*"));
    }
}

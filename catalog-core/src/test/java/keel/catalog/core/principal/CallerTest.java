package keel.catalog.core.principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CallerTest {

    private static final Principal TRINO = new Principal("trino-platform", Set.of());
    private static final Principal ALICE = new Principal("alice@corp.example", Set.of("trading-readers"));

    @Test
    void authorizationIsForTheEndUserAnEngineActsFor() {
        Caller caller = new Caller(TRINO, "trino-client", true, Optional.of(ALICE));

        assertThat(caller.subject()).isEqualTo(ALICE);
        assertThat(caller.principal()).isEqualTo(TRINO);
    }

    @Test
    void authorizationIsForTheCallerItselfWhenNoEndUserIsAsserted() {
        Caller caller = new Caller(ALICE, "pyiceberg-client", false, Optional.empty());

        assertThat(caller.subject()).isEqualTo(ALICE);
    }

    @Test
    void aPrincipalNeedsAnId() {
        assertThatThrownBy(() -> new Principal(" ", Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Principal(null, Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupsAreCopied() {
        Set<String> groups = new HashSet<>(Set.of("trading-readers"));
        Principal principal = new Principal("alice@corp.example", groups);
        groups.add("trading-writers");

        assertThat(principal.groups()).containsExactly("trading-readers");
    }
}

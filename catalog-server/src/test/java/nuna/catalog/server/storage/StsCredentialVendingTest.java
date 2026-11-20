package nuna.catalog.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.server.rest.CredentialVending;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D21's delegation rule as amended 2026-11-19, with no container: any caller whose header asks for nothing but
 * {@code none} gets no credentials, and its vend reaches neither the catalog's storage profile, nor STS behind it,
 * nor the audit. Any other header, or none, goes on to the profile. The store here binds no catalog to a profile, so
 * a vend that goes on fails there, and its call to the store shows that it went on. {@code VendingRouteTest} shows the
 * same rule against real STS.
 */
class StsCredentialVendingTest {

    private static final Caller PERSON = new Caller(new Principal("dave", Set.of("traders")), "nuna-console", false,
            Optional.empty());
    private static final Caller ENGINE = new Caller(new Principal("svc-trino", Set.of()), "trino-prod", true,
            Optional.of(new Principal("dave", Set.of("traders"))));
    private static final StorageSettings NO_PROFILES = new StorageSettings(Map.of(), Map.of(), Duration.ofMinutes(30),
            100, true);

    private final List<String> storeCalls = new CopyOnWriteArrayList<>();
    private final List<CatalogEvent> audited = new CopyOnWriteArrayList<>();
    private final StsCredentialVending vending = new StsCredentialVending(
            new CatalogStorage(NO_PROFILES, store(), null, Clock.systemUTC(), ObservationRegistry.NOOP),
            audited::add, URI.create("urn:nuna:test"), NO_PROFILES, Duration.ofMinutes(5), Clock.systemUTC(),
            ObservationRegistry.NOOP);

    @Test
    @DisplayName("any caller that asks for none gets no credentials, and nothing reaches storage, STS or the audit")
    void anyCallerAskingForNoneGetsNothing() {
        for (Caller caller : List.of(PERSON, ENGINE)) {
            for (String header : List.of("none", " None ", "none, none")) {
                assertThat(vending.vend(request(caller, Optional.of(header))))
                        .as(caller.principal().id() + " asking '" + header + "'")
                        .isSameAs(CredentialVending.Vended.NONE);
            }
        }
        assertThat(storeCalls).as("the storage profile was never looked up").isEmpty();
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("without the header, or naming another mechanism too, every caller's vend goes on to storage")
    void anyOtherHeaderGoesOnToStorage() {
        for (Caller caller : List.of(PERSON, ENGINE)) {
            for (Optional<String> header : List.of(Optional.<String>empty(), Optional.of("vended-credentials"),
                    Optional.of("vended-credentials,none"))) {
                storeCalls.clear();
                assertThatThrownBy(() -> vending.vend(request(caller, header)))
                        .as(caller.principal().id() + " asking " + header)
                        .hasMessageContaining("has no storage profile");
                assertThat(storeCalls).containsExactly("catalogSettings");
            }
        }
        assertThat(audited).isEmpty();
    }

    private static CredentialVending.Request request(Caller caller, Optional<String> delegation) {
        return new CredentialVending.Request(new CatalogRecord(UUID.randomUUID(), "trading", "trading"),
                UUID.randomUUID(), List.of("desk", "t"), "s3://bucket/tables/t", caller, "req-1", delegation,
                "v1/trading/namespaces/desk/tables/t/credentials", false);
    }

    /** A store that binds no catalog to a storage profile, and records what it was asked. */
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> {
                    storeCalls.add(method.getName());
                    if (method.getName().equals("catalogSettings")) {
                        return Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}

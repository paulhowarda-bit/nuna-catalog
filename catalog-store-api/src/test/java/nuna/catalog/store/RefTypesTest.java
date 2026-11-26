package nuna.catalog.store;

import static nuna.catalog.store.suite.Changes.name;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.store.suite.Refs;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The ref types' own rules (D33, backlog 102): names, kinds and expiry, what a ref is made from, and P11's limits. */
class RefTypesTest {

    private static final UUID CATALOG = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private static Ref ref(String display, Ref.Kind kind, Optional<UUID> fromRef, String fromName,
                           Optional<Instant> expiresAt) {
        return new Ref(CATALOG, UUID.randomUUID(), name(display), kind, NOW, "svc", fromRef, fromName, expiresAt,
                Optional.empty());
    }

    @Test
    void aNameHoldsNoAtSignAndNoSlash() {
        for (String display : new String[] {"a@b", "a/b", "@", "/", "release@2026"}) {
            assertThatThrownBy(() -> Refs.tag(CATALOG, display, NOW)).as(display)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no @ and no /");
        }
        assertThatThrownBy(() -> Ref.checkName(new Name("plain", "pl@in"))).as("in the display form alone")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Refs.tag(CATALOG, "Release-2026.11_rc1", NOW).name().normalized()).isEqualTo("release-2026.11_rc1");
    }

    @Test
    void mainIsReservedInAnyCase() {
        for (String display : new String[] {"main", "Main", "MAIN"}) {
            assertThatThrownBy(() -> Refs.branch(CATALOG, display, NOW)).as(display)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("main line");
        }
        assertThat(Refs.branch(CATALOG, "mainline", NOW).name().normalized()).isEqualTo("mainline");
        assertThat(Refs.branch(CATALOG, "main-2", NOW).name().normalized()).isEqualTo("main-2");
    }

    @Test
    void aBranchExpiresAndATagNeverDoes() {
        assertThatThrownBy(() -> ref("b", Ref.Kind.BRANCH, Optional.empty(), Ref.MAIN, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ref("t", Ref.Kind.TAG, Optional.empty(), Ref.MAIN, Optional.of(NOW)))
                .isInstanceOf(IllegalArgumentException.class);
        Ref branch = Refs.branch(CATALOG, "b", NOW);
        Instant expiry = branch.expiresAt().orElseThrow();

        assertThat(expiry).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(branch.expiredAt(expiry.minusMillis(1))).isFalse();
        assertThat(branch.expiredAt(expiry)).as("gone at its expiry").isTrue();
        assertThat(branch.expiredAt(expiry.plusSeconds(1))).isTrue();
        assertThat(Refs.tag(CATALOG, "t", NOW).expiredAt(Instant.MAX)).isFalse();
    }

    @Test
    void aRefIsMadeFromMainOrFromAnotherRefByItsName() {
        UUID source = UUID.randomUUID();
        assertThatThrownBy(() -> ref("a", Ref.Kind.TAG, Optional.of(source), Ref.MAIN, Optional.empty()))
                .as("from a ref, named main").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ref("a", Ref.Kind.TAG, Optional.empty(), "dev", Optional.empty()))
                .as("from main, named otherwise").isInstanceOf(IllegalArgumentException.class);
        Ref dev = Refs.branch(CATALOG, "Dev", NOW);
        Ref fix = Refs.from(dev, "fix", Ref.Kind.TAG, NOW);

        assertThat(dev.fromRef()).isEmpty();
        assertThat(dev.fromName()).isEqualTo(Ref.MAIN);
        assertThat(fix.fromRef()).contains(dev.id());
        assertThat(fix.fromName()).isEqualTo("Dev");
        assertThat(fix.catalogId()).isEqualTo(CATALOG);
    }

    @Test
    void everyFieldIsRequired() {
        assertThatThrownBy(() -> new Ref(CATALOG, UUID.randomUUID(), name("a"), Ref.Kind.TAG, NOW, null,
                Optional.empty(), Ref.MAIN, Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class)
                .hasMessage("createdBy");
        assertThatThrownBy(() -> new Ref(null, UUID.randomUUID(), name("a"), Ref.Kind.TAG, NOW, "svc",
                Optional.empty(), Ref.MAIN, Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class)
                .hasMessage("catalogId");
    }

    @Test
    void aBranchsOwnPointersAreKeyedByItsIdNeverItsName() {
        Ref dev = Refs.branch(CATALOG, "dev", NOW);
        UUID object = UUID.randomUUID();

        assertThat(dev.key(object)).isEqualTo(new PointerKey(object, dev.id().toString()));
        assertThat(Refs.branch(CATALOG, "dev", NOW).key(object)).as("the same name again").isNotEqualTo(dev.key(object));
    }

    @Test
    void aTableOrViewKeepsAPointerAndANamespaceNone() {
        UUID ref = UUID.randomUUID();
        RefEntry.At at = new RefEntry.At(3, "s3://w/t/metadata/3.json", "ab");
        assertThatThrownBy(() -> new RefEntry(ref, UUID.randomUUID(), RefEntry.Kind.TABLE, CATALOG, name("t"),
                Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefEntry(ref, UUID.randomUUID(), RefEntry.Kind.NAMESPACE, CATALOG, name("n"),
                Optional.of(at))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefEntry.At(0, "s3://w", "ab")).isInstanceOf(IllegalArgumentException.class);

        assertThat(new RefEntry(ref, UUID.randomUUID(), RefEntry.Kind.VIEW, CATALOG, name("v"), Optional.of(at))
                .pointer()).contains(at);
        assertThat(RefEntry.of(CatalogObject.Kind.TABLE)).isEqualTo(RefEntry.Kind.TABLE);
        assertThat(RefEntry.of(CatalogObject.Kind.VIEW)).isEqualTo(RefEntry.Kind.VIEW);
    }

    @Test
    void p11AdmitsUpToItsLimitAndNoMore() {
        RefLimits p11 = RefLimits.P11;

        assertThat(p11.of(Ref.Kind.BRANCH)).isEqualTo(50);
        assertThat(p11.of(Ref.Kind.TAG)).isEqualTo(1_000);
        assertThat(p11.admitsAnother(Ref.Kind.BRANCH, 0)).isTrue();
        assertThat(p11.admitsAnother(Ref.Kind.BRANCH, 49)).as("the 50th").isTrue();
        assertThat(p11.admitsAnother(Ref.Kind.BRANCH, 50)).as("a 51st").isFalse();
        assertThat(p11.admitsAnother(Ref.Kind.TAG, 999)).as("the 1,000th").isTrue();
        assertThat(p11.admitsAnother(Ref.Kind.TAG, 1_000)).as("a 1,001st").isFalse();
        assertThat(p11.admitsAnother(Ref.Kind.TAG, 50)).as("tags have their own limit").isTrue();
        assertThat(new RefLimits(0, 0).admitsAnother(Ref.Kind.TAG, 0)).as("a limit of 0 admits none").isFalse();
        assertThatThrownBy(() -> new RefLimits(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefLimits(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}

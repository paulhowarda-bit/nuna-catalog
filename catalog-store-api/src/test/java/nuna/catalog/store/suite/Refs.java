package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Changes.name;

import nuna.catalog.store.Change;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Ref;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Builds the refs and the changes at a given time that the ref suite writes. */
public final class Refs {

    /** How long after its creation a suite branch expires (P11's 30 days). */
    public static final Duration LIFETIME = Duration.ofDays(30);

    private Refs() {
    }

    /** A new branch of the catalog, made from main at {@code at}. */
    public static Ref branch(UUID catalogId, String display, Instant at) {
        return ref(catalogId, display, Ref.Kind.BRANCH, Optional.empty(), at);
    }

    /** A new tag of the catalog, made from main at {@code at}. */
    public static Ref tag(UUID catalogId, String display, Instant at) {
        return ref(catalogId, display, Ref.Kind.TAG, Optional.empty(), at);
    }

    /** A new ref made from {@code source} at {@code at}, in the source's catalog. */
    public static Ref from(Ref source, String display, Ref.Kind kind, Instant at) {
        return ref(source.catalogId(), display, kind, Optional.of(source), at);
    }

    private static Ref ref(UUID catalogId, String display, Ref.Kind kind, Optional<Ref> source, Instant at) {
        return new Ref(catalogId, UUID.randomUUID(), name(display), kind, at, "svc-suite", source.map(Ref::id),
                source.map(ref -> ref.name().display()).orElse(Ref.MAIN),
                kind == Ref.Kind.BRANCH ? Optional.of(at.plus(LIFETIME)) : Optional.empty());
    }

    /** A change at {@code at}, which may be ahead of the clock: what the store compares a branch's expiry with. */
    public static Change changeAt(Instant at) {
        UUID id = UUID.randomUUID();
        return new Change(id, at, List.of(new OutboxEvent("suite.changed", "{\"change\":\"" + id + "\"}")),
                Optional.empty());
    }
}

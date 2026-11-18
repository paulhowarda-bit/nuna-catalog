package nuna.catalog.server.tracing;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Locale;
import java.util.Optional;
import nuna.catalog.store.CatalogStore;

/**
 * The catalog store with each call observed as {@value #NAME} (docs/observability/spans.md): every method of
 * {@link CatalogStore} is one transaction (one atomic unit), so each call is one span, named {@code store <method>},
 * and one timer, tagged with the backend, the method and the outcome. A method added to the store is observed with no
 * change here.
 */
public final class ObservedStore implements InvocationHandler {

    public static final String NAME = "nuna.store";

    private final CatalogStore store;
    private final KeyValues backend;
    private final ObservationRegistry registry;

    private ObservedStore(CatalogStore store, String backend, ObservationRegistry registry) {
        this.store = store;
        this.backend = KeyValues.of("backend", backend);
        this.registry = registry;
    }

    /** @param backend the configured backend, as {@code nuna.database.backend} names it */
    public static CatalogStore of(CatalogStore store, String backend, ObservationRegistry registry) {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, new ObservedStore(store, backend, registry));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return invoke(method, args);
        }
        return Observed.call(registry, NAME, "store " + method.getName(),
                backend.and("operation", method.getName()), () -> invoke(method, args), ObservedStore::outcome);
    }

    private Object invoke(Method method, Object[] args) {
        try {
            return method.invoke(store, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            if (e.getCause() instanceof Error failure) {
                throw failure;
            }
            throw new UndeclaredThrowableException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A result that is one of a sealed set of outcomes ({@code CommitResult}, {@code WriteResult}, {@code KeyState})
     * or an enum ({@code CommitLookup}) names its kind: {@code committed}, {@code conflict}, {@code name-taken},
     * {@code not-committed}. A read that may find nothing says {@code found} or {@code absent}; anything else is
     * {@code ok}.
     */
    static String outcome(Object result) {
        return switch (result) {
            case Optional<?> optional -> optional.isPresent() ? "found" : "absent";
            case Enum<?> constant -> constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
            case Record kind when kind.getClass().getEnclosingClass() != null
                    && kind.getClass().getEnclosingClass().isSealed() -> words(kind.getClass().getSimpleName());
            case null, default -> "ok";
        };
    }

    /** {@code NameTaken} as {@code name-taken}. */
    private static String words(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}

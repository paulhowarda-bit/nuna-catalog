package keel.catalog.core.commit;

import keel.catalog.store.CatalogStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real store behind a proxy that counts calls and runs injected faults: a fault sees the call's
 * arguments and decides whether, and when, the real call happens.
 */
final class FaultyStore {

    @FunctionalInterface
    interface Proceed {
        Object call() throws Throwable;
    }

    @FunctionalInterface
    interface Fault {
        Object apply(Object[] args, Proceed proceed) throws Throwable;
    }

    final CatalogStore real;
    final CatalogStore store;
    private final Map<String, Deque<Fault>> once = new ConcurrentHashMap<>();
    private final Map<String, Fault> always = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    FaultyStore(CatalogStore real) {
        this.real = real;
        this.store = (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> invoke(method, args));
    }

    /** The next call of {@code method} runs {@code fault}; faults queue in order. */
    synchronized void once(String method, Fault fault) {
        once.computeIfAbsent(method, m -> new ArrayDeque<>()).add(fault);
    }

    /** Every call of {@code method} with no queued fault runs {@code fault}. */
    void always(String method, Fault fault) {
        always.put(method, fault);
    }

    int calls(String method) {
        AtomicInteger count = calls.get(method);
        return count == null ? 0 : count.get();
    }

    private Object invoke(Method method, Object[] args) throws Throwable {
        calls.computeIfAbsent(method.getName(), m -> new AtomicInteger()).incrementAndGet();
        Fault fault = next(method.getName());
        Proceed proceed = () -> {
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return fault == null ? proceed.call() : fault.apply(args, proceed);
    }

    private synchronized Fault next(String method) {
        Deque<Fault> queued = once.get(method);
        Fault fault = queued == null ? null : queued.poll();
        return fault != null ? fault : always.get(method);
    }
}

package nuna.catalog.server.tracing;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A call to the database, the object store, STS or Kafka as an Observation (backlog 41,
 * docs/observability/spans.md): one span, a child of the span of the request that made it, and one timer of the same
 * name. Both carry the call's own keys, its {@value #OUTCOME}, and its {@value #EXCEPTION}: the simple name of the
 * exception it threw, or {@code none}.
 *
 * <p>A call made outside any observation, such as the outbox relay's every 250 ms, runs unobserved: each would start a
 * trace of its own.
 *
 * <p>A failed call's span records the exception's type, never its message, which can name a table, a principal or a
 * value ({@link CallFailed}).
 */
public final class Observed {

    /** What the call came to: a result's kind, or {@code error}. */
    public static final String OUTCOME = "outcome";
    /** The simple name of the exception the call threw, or {@code none}. */
    public static final String EXCEPTION = "exception";

    private Observed() {
    }

    /**
     * Runs {@code call} as the observation {@code name}, its span named {@code contextualName}.
     *
     * @param keys    bounded values only: never a person, a table path, a key or a token
     * @param outcome names a result's outcome, from a small fixed set
     */
    public static <T> T call(ObservationRegistry registry, String name, String contextualName, KeyValues keys,
                             Supplier<T> call, Function<? super T, String> outcome) {
        if (registry.getCurrentObservation() == null) {
            return call.get();
        }
        Observation observation = Observation.createNotStarted(name, registry)
                .contextualName(contextualName)
                .lowCardinalityKeyValues(keys)
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            T result = call.get();
            observation.lowCardinalityKeyValue(OUTCOME, outcome.apply(result));
            observation.lowCardinalityKeyValue(EXCEPTION, "none");
            return result;
        } catch (RuntimeException | Error e) {
            observation.lowCardinalityKeyValue(OUTCOME, "error");
            observation.lowCardinalityKeyValue(EXCEPTION, e.getClass().getSimpleName());
            observation.error(new CallFailed(e));
            throw e;
        } finally {
            observation.stop();
        }
    }

    /**
     * What a failed call's span records in place of the exception it threw: that exception's class name, with no
     * message, cause or stack trace of its own. Messages here can name a table's location, the principal an STS
     * session is for, or a value a database refused. Micrometer's timer tags a failure {@code error=CallFailed}; the
     * exception is in {@value #EXCEPTION}.
     */
    static final class CallFailed extends RuntimeException {

        CallFailed(Throwable failure) {
            super(failure.getClass().getName(), null, false, false);
        }
    }
}

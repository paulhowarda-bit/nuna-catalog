package nuna.catalog.tests.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.HttpRoute;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.config.ConnectionConfig;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.core5.function.Resolver;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.core5.util.TimeValue;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.NpipeSocketClientProviderStrategy;
import org.testcontainers.dockerclient.TransportConfig;
import org.testcontainers.shaded.com.github.dockerjava.core.DockerClientImpl;

/**
 * Testcontainers' Docker client on Windows (the named pipe {@code //./pipe/docker_engine}), changed in one way: it
 * never reuses a pooled connection older than {@link #TIME_TO_LIVE}.
 *
 * <p>Podman's API service closes a keep-alive connection idle for 10 s (measured on 2026-11-26; Docker Desktop never
 * did). docker-java's pool keeps connections forever and does not check one before reuse, so the first request after
 * a quiet spell went out on a closed connection. The HTTP client sends a GET again; a POST (create a container, exec)
 * it does not, and the test failed with {@code NoHttpResponseException: localhost:2375 failed to respond} (backlog
 * 291). Every test JVM picks this strategy through {@code TESTCONTAINERS_DOCKER_CLIENT_STRATEGY}, set by the root
 * build.
 */
public final class FreshPipeStrategy extends DockerClientProviderStrategy {

    /** Under Podman's 10 s: a connection made no longer ago than this has not been idle longer either. */
    static final TimeValue TIME_TO_LIVE = TimeValue.ofSeconds(5);

    private final NpipeSocketClientProviderStrategy pipe = new NpipeSocketClientProviderStrategy();
    private final AtomicBoolean fresh = new AtomicBoolean();

    @Override
    public String getDescription() {
        return pipe.getDescription() + ", reusing no connection older than " + TIME_TO_LIVE;
    }

    @Override
    public TransportConfig getTransportConfig() {
        return pipe.getTransportConfig();
    }

    @Override
    protected boolean isApplicable() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    // Persistable, as Testcontainers requires of a strategy it is told to use: it writes this class's name to
    // ~/.testcontainers.properties. A JVM without it on its path warns there and falls back to the plain pipe.

    @Override
    public DockerClient getDockerClient() {
        DockerClient client = super.getDockerClient();
        if (fresh.compareAndSet(false, true)) {
            limitConnectionAge(client);
        }
        return client;
    }

    /**
     * Gives the pool behind Testcontainers' client a time to live. The pool is built inside docker-java with none and
     * nothing sets one from outside, so it is reached by its fields: Testcontainers' header-adding client, docker-java's
     * Apache client, the HTTP client's connection manager. A Testcontainers upgrade that renames one fails here, at
     * the first container, naming it.
     */
    @SuppressWarnings("unchecked")
    static void limitConnectionAge(DockerClient client) {
        Object headers = ((DockerClientImpl) client).getHttpClient();
        Object apache = field(headers, "delegate");
        Object http = field(apache, "httpClient");
        var pool = (PoolingHttpClientConnectionManager) field(http, "connManager");
        var configs = (Resolver<HttpRoute, ConnectionConfig>) field(pool, "connectionConfigResolver");
        pool.setConnectionConfigResolver(route ->
                ConnectionConfig.copy(configs.resolve(route)).setTimeToLive(TIME_TO_LIVE).build());
    }

    private static Object field(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException e) {
                // declared higher up
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("No field " + name + " in " + owner.getClass().getName()
                + ": Testcontainers' Docker client changed, so FreshPipeStrategy cannot limit its connections' age");
    }
}

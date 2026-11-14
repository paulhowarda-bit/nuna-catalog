package keel.maintenance;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * In this test JVM, {@code host.docker.internal} resolves to the loopback address; every other name resolves as the
 * JDK resolves it. Pods of Docker Desktop's Kubernetes resolve that name to this machine through Docker's own DNS.
 * This machine resolves it through a hosts-file entry that Docker Desktop writes when it starts, and that entry
 * goes stale when the machine's address changes. The server {@link TestCatalog#forPods} starts here names its S3
 * store by that name, for the pods, and calls the store by it itself (for STS credentials), so here the name must
 * mean this machine. Registered in {@code META-INF/services}.
 */
public final class HostDockerInternal extends InetAddressResolverProvider {

    static final String NAME = "host.docker.internal";

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                if (NAME.equalsIgnoreCase(host)) {
                    return Stream.of(InetAddress.getLoopbackAddress());
                }
                return builtin.lookupByName(host, policy);
            }

            @Override
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                return builtin.lookupByAddress(address);
            }
        };
    }

    @Override
    public String name() {
        return NAME + " as loopback";
    }
}

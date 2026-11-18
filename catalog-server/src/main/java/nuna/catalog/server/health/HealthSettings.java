package nuna.catalog.server.health;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the console's health page ({@code nuna.health.*}, docs/observability/health-page.md). Each server holds
 * only its own meters, so the server a platform operator's browser reaches asks the others for theirs.
 *
 * @param peers       where the other servers are: {@code host:port} entries, each host resolved to every address it
 *                    has, each address asked. The Helm chart sets one, its headless Service, which resolves to every
 *                    ready server; empty means this server alone
 * @param peerTimeout how long a peer has to connect and answer before the page counts it as not answering
 */
@ConfigurationProperties("nuna.health")
public record HealthSettings(@DefaultValue List<String> peers, @DefaultValue("3s") Duration peerTimeout) {

    public HealthSettings {
        peers = List.copyOf(peers);
        for (String peer : peers) {
            URI uri = URI.create("http://" + peer);
            if (uri.getHost() == null || uri.getPort() < 0 || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null) {
                throw new IllegalArgumentException("nuna.health.peers holds host:port entries, not " + peer);
            }
        }
        if (peerTimeout.isNegative() || peerTimeout.isZero()) {
            throw new IllegalArgumentException("nuna.health.peer-timeout must be positive, not " + peerTimeout);
        }
    }
}

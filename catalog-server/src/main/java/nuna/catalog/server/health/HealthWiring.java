package nuna.catalog.server.health;

import io.micrometer.core.instrument.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The console's health page: the registry it reads, this server's window over it, and the other servers. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HealthSettings.class)
class HealthWiring {

    /**
     * A meter registry bean: Boot records every meter here as well, and without an exporting registry this is the
     * only one (Boot's own fallback registry steps aside for any registry bean).
     */
    @Bean
    HealthMeters healthMeters() {
        return new HealthMeters(Clock.SYSTEM);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    HealthWindow healthWindow(HealthMeters meters, java.time.Clock clock) {
        return new HealthWindow(meters, clock);
    }

    @Bean(destroyMethod = "close")
    Peers healthPeers(HealthSettings settings) {
        return new Peers(settings);
    }
}

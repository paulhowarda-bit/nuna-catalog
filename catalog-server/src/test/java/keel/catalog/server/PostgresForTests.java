package keel.catalog.server;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** A PostgreSQL container for server tests; the image is pinned in the parent POM. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresForTests {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")));
    }
}

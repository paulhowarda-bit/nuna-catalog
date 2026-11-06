package fidice.catalog.conformance;

import fidice.catalog.server.CatalogServer;

import java.net.URI;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** One catalog server on PostgreSQL for the whole test JVM, with the RCK's warehouse seeded. */
final class ConformanceServer {

    /** The warehouse name the RCK client asks for by default. */
    static final String WAREHOUSE = "rck_warehouse";

    private static URI uri;

    private ConformanceServer() {
    }

    static synchronized URI start() {
        if (uri != null) {
            return uri;
        }
        PostgreSQLContainer postgres =
                new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")));
        postgres.start();
        ConfigurableApplicationContext server = new SpringApplicationBuilder(CatalogServer.class)
                .properties(
                        "server.port=0",
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword())
                .run();
        server.getBean(JdbcTemplate.class).update(
                "INSERT INTO catalog (id, name_norm, name) VALUES (?, ?, ?)", UUID.randomUUID(), WAREHOUSE, WAREHOUSE);
        int port = server.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        uri = URI.create("http://localhost:" + port + "/");
        return uri;
    }
}

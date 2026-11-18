package nuna.catalog.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The catalog server: stateless nodes over one storage backend per deployment (D7). */
@SpringBootApplication
public class CatalogServer {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServer.class, args);
    }
}

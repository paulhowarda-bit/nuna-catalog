package nuna.catalog.server;

import com.mongodb.ConnectionString;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The catalog's database ({@code nuna.database.*}): one backend per deployment (D7), chosen when it is installed.
 * <ul>
 *   <li>{@code postgresql}, the default: PostgreSQL or CockroachDB, through {@code spring.datasource.*}.</li>
 *   <li>{@code oracle}: Oracle, single instance, RAC or Exadata, through {@code spring.datasource.*}. The URL names
 *       the catalog service, which has COMMIT_OUTCOME = TRUE and no FAILOVER_TYPE, and the driver's read timeout
 *       is set with {@code spring.datasource.hikari.data-source-properties.oracle.jdbc.ReadTimeout} (milliseconds).
 *       The JVM's locale must give NLS_SORT = BINARY, as English does.</li>
 *   <li>{@code mongodb}: a MongoDB replica set, through {@code nuna.database.mongodb.uri}. No
 *       {@code spring.datasource.*}: the server then has no JDBC database at all.</li>
 * </ul>
 * What each database needs of its catalog role is on the store's class: SqlCatalogStore, OracleCatalogStore and
 * MongoCatalogStore.
 *
 * <p>The server runs the store's self-check before anything else and refuses to start while it reports a finding
 * (D8.2): a durability setting that is unsafe for the catalog, such as no synchronous standby. The codes are in
 * docs/storage-design.md section 7.
 *
 * @param backend     which database holds the catalog
 * @param mongodb     the MongoDB connection; required for {@code mongodb}, unused otherwise
 * @param allowUnsafe the findings the server starts despite, each by its code, as
 *                    {@code [pg.no-sync-standby, pg.fsync-off]}; none by default. Only for a test or demo database
 *                    on a single node, never in production. Each finding allowed is logged as a warning at
 *                    startup; any other still refuses to start the server.
 */
@ConfigurationProperties("nuna.database")
public record DatabaseSettings(@DefaultValue("postgresql") Backend backend, Mongodb mongodb, Set<String> allowUnsafe) {

    public enum Backend { POSTGRESQL, ORACLE, MONGODB }

    public DatabaseSettings {
        if (backend == Backend.MONGODB && mongodb == null) {
            throw new IllegalArgumentException("nuna.database.mongodb.uri is required when nuna.database.backend"
                    + " is mongodb");
        }
        allowUnsafe = allowUnsafe == null ? Set.of() : Set.copyOf(allowUnsafe);
    }

    /**
     * @param uri a connection string that names the catalog's database and sets {@code socketTimeoutMS}, which
     *            bounds every wait on the database: {@code mongodb://h1,h2,h3/catalog?replicaSet=rs0&socketTimeoutMS=30000}.
     *            It carries the password, so it belongs in the deployment's Secret.
     */
    public record Mongodb(String uri) {

        public Mongodb {
            Objects.requireNonNull(uri, "nuna.database.mongodb.uri");
            // The messages never quote the string: it may hold a password.
            ConnectionString parsed = new ConnectionString(uri);
            if (parsed.getDatabase() == null) {
                throw new IllegalArgumentException("nuna.database.mongodb.uri must name the catalog's database,"
                        + " as in mongodb://h1,h2,h3/catalog?replicaSet=rs0&socketTimeoutMS=30000");
            }
            if (parsed.getSocketTimeout() == null || parsed.getSocketTimeout() <= 0) {
                throw new IllegalArgumentException("nuna.database.mongodb.uri must set socketTimeoutMS, which"
                        + " bounds every wait on the database");
            }
        }

        ConnectionString connectionString() {
            return new ConnectionString(uri);
        }
    }
}

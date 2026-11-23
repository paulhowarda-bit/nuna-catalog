package nuna.catalog.server.health;

import com.mongodb.client.MongoClient;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import nuna.catalog.server.DatabaseSettings;
import nuna.catalog.server.StoreSelfCheck;
import nuna.catalog.store.SafetyFinding;
import org.bson.Document;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The catalog's database as this server finds it, for Health's Store panel (backlog 139): the backend and its version,
 * asked on the live connection each time, and the self-check this server ran at start ({@link StoreSelfCheck}).
 *
 * <p>It is the one read outside the catalog store, and it reads nothing of the catalog: what the database is. On
 * PostgreSQL's wire {@code SELECT version()}, which CockroachDB answers "CockroachDB CCL v…" (as catalog-store-sql
 * tells the two apart); on Oracle the JDBC driver's product version; on MongoDB {@code buildInfo}. A database that
 * cannot be asked is said so, with its error, beside the backend {@code nuna.database.backend} names.
 *
 * <p>Beside them, the point-in-time recovery window the deployment declares, {@code nuna.governance.pitr-window}
 * (backlog 145): orphan removal and dropped tables' retention rely on it. It is never read from the database, whose
 * backup setting the catalog's role cannot read on most backends, so it is there when the database does not answer.
 */
final class StoreFacts {

    private static final Pattern COCKROACH_VERSION = Pattern.compile("\\bv\\d[^\\s(]*");
    private static final Pattern ORACLE_VERSION = Pattern.compile("\\bVersion (\\S+)");
    private static final Pattern ORACLE_RELEASE = Pattern.compile("\\bRelease (\\S+)");

    /** A backend as found, and its version. */
    record Found(String backend, String version) {
    }

    private final DatabaseSettings.Backend configured;
    private final Supplier<DataSource> dataSource;
    private final Supplier<MongoClient> mongoClient;
    private final StoreSelfCheck selfCheck;
    private final Duration pitrWindow;

    StoreFacts(DatabaseSettings.Backend configured, Supplier<DataSource> dataSource,
               Supplier<MongoClient> mongoClient, StoreSelfCheck selfCheck, Duration pitrWindow) {
        this.configured = configured;
        this.dataSource = dataSource;
        this.mongoClient = mongoClient;
        this.selfCheck = selfCheck;
        this.pitrWindow = pitrWindow;
    }

    /**
     * {@code configured}, the backend and version found or why they could not be, the point-in-time recovery window
     * declared, and the self-check at start.
     */
    ObjectNode read() {
        ObjectNode store = JsonNodeFactory.instance.objectNode();
        store.put("configured", configured.name().toLowerCase(Locale.ROOT));
        try {
            Found found = found();
            store.put("backend", found.backend());
            store.put("version", found.version());
        } catch (SQLException | RuntimeException e) {
            store.put("unreadable", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        store.put("pitr-window-seconds", pitrWindow.toSeconds());
        ObjectNode check = store.putObject("self-check");
        check.put("at", selfCheck.at().toString());
        ArrayNode allowed = check.putArray("allowed");
        for (SafetyFinding finding : selfCheck.allowed()) {
            allowed.addObject().put("code", finding.code()).put("detail", finding.detail());
        }
        return store;
    }

    private Found found() throws SQLException {
        return switch (configured) {
            case POSTGRESQL -> {
                try (Connection connection = dataSource.get().getConnection();
                     Statement statement = connection.createStatement();
                     ResultSet row = statement.executeQuery("SELECT version()")) {
                    row.next();
                    yield postgresWire(row.getString(1));
                }
            }
            case ORACLE -> {
                try (Connection connection = dataSource.get().getConnection()) {
                    yield oracle(connection.getMetaData().getDatabaseProductVersion());
                }
            }
            case MONGODB -> new Found("MongoDB", mongoClient.get().getDatabase("admin")
                    .runCommand(new Document("buildInfo", 1)).getString("version"));
        };
    }

    /** PostgreSQL's or CockroachDB's answer to {@code SELECT version()}. */
    static Found postgresWire(String version) {
        if (version.startsWith("CockroachDB")) {
            Matcher number = COCKROACH_VERSION.matcher(version);
            return new Found("CockroachDB", number.find() ? number.group() : version);
        }
        String[] words = version.split("\\s+");
        return new Found("PostgreSQL", words.length > 1 && words[0].equals("PostgreSQL") ? words[1] : version);
    }

    /** Oracle's product version: its last "Version" line when there is one, as 19c and later give, else its release. */
    static Found oracle(String productVersion) {
        Matcher version = ORACLE_VERSION.matcher(productVersion);
        String found = null;
        while (version.find()) {
            found = version.group(1);
        }
        if (found == null) {
            Matcher release = ORACLE_RELEASE.matcher(productVersion);
            found = release.find() ? release.group(1) : productVersion.lines().findFirst().orElse(productVersion);
        }
        return new Found("Oracle", found);
    }
}

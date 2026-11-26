package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The backend switch: what {@code nuna.database.*} accepts, a MongoDB deployment without a DataSource, and the
 * self-check findings a test or demo database is allowed.
 */
class DatabaseSettingsTest {

    /** The addresses' password, made per run so no credential-shaped literal sits in the source. */
    private static final String SECRET = "pw-" + UUID.randomUUID();
    private static final String GOOD_URI = "mongodb://catalog:" + SECRET + "@h1,h2,h3/catalog?replicaSet=rs0&socketTimeoutMS=30000";

    @Test
    void postgresqlIsTheDefault() {
        assertThat(bind(Map.of()).backend()).isEqualTo(DatabaseSettings.Backend.POSTGRESQL);
        assertThat(bind(Map.of("nuna.database.backend", "oracle")).backend()).isEqualTo(DatabaseSettings.Backend.ORACLE);
    }

    @Test
    void mongodbNeedsAUriThatNamesTheDatabaseAndBoundsEveryWait() {
        DatabaseSettings settings = bind(Map.of("nuna.database.backend", "mongodb", "nuna.database.mongodb.uri", GOOD_URI));
        assertThat(settings.mongodb().connectionString().getDatabase()).isEqualTo("catalog");

        assertRefused(Map.of("nuna.database.backend", "mongodb"), "nuna.database.mongodb.uri is required");
        assertRefused(Map.of("nuna.database.backend", "mongodb", "nuna.database.mongodb.uri",
                "mongodb://catalog:" + SECRET + "@h1/?socketTimeoutMS=30000"), "must name the catalog's database");
        assertRefused(Map.of("nuna.database.backend", "mongodb", "nuna.database.mongodb.uri",
                "mongodb://catalog:" + SECRET + "@h1/catalog?replicaSet=rs0"), "must set socketTimeoutMS");
    }

    @Test
    void noUnsafeDatabaseIsAllowedUnlessItsFindingsAreNamed() {
        assertThat(bind(Map.of()).allowUnsafe()).isEmpty();
        assertThat(bind(Map.of("nuna.database.allow-unsafe", "pg.no-sync-standby,pg.fsync-off")).allowUnsafe())
                .containsExactlyInAnyOrder("pg.no-sync-standby", "pg.fsync-off");
        assertThat(bind(Map.of("nuna.database.allow-unsafe[0]", "mongo.too-few-voters")).allowUnsafe())
                .containsExactly("mongo.too-few-voters");

        // As a container sets it: an environment variable.
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("NUNA_DATABASE_ALLOWUNSAFE", "ora.no-data-guard")));
        assertThat(Binder.get(environment).bindOrCreate("nuna.database", DatabaseSettings.class).allowUnsafe())
                .containsExactly("ora.no-data-guard");
    }

    @Test
    void onlyAMongodbDeploymentLeavesOutTheDataSource() {
        assertThat(excludedAfterPostProcessing(Map.of())).isNull();
        assertThat(excludedAfterPostProcessing(Map.of("nuna.database.backend", "oracle"))).isNull();
        assertThat(excludedAfterPostProcessing(Map.of("nuna.database.backend", "mongodb")))
                .isEqualTo(MongodbHasNoDataSource.DATA_SOURCE);
        assertThat(excludedAfterPostProcessing(Map.of("nuna.database.backend", "MongoDB",
                MongodbHasNoDataSource.EXCLUDE, "a.First,b.Second")))
                .isEqualTo("a.First,b.Second," + MongodbHasNoDataSource.DATA_SOURCE);

        // As a container sets it: an environment variable.
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("NUNA_DATABASE_BACKEND", "mongodb")));
        new MongodbHasNoDataSource().postProcessEnvironment(environment, null);
        assertThat(environment.getProperty(MongodbHasNoDataSource.EXCLUDE)).isEqualTo(MongodbHasNoDataSource.DATA_SOURCE);
    }

    private static DatabaseSettings bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("nuna.database", DatabaseSettings.class);
    }

    /** Refused with {@code message}, which never quotes the URI's password. */
    private static void assertRefused(Map<String, String> properties, String message) {
        assertThatThrownBy(() -> bind(properties))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining(message).message().doesNotContain(SECRET);
    }

    private static String excludedAfterPostProcessing(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        new MongodbHasNoDataSource().postProcessEnvironment(environment, null);
        return environment.getProperty(MongodbHasNoDataSource.EXCLUDE);
    }
}

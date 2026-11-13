package keel.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** The backend switch: what {@code keel.database.*} accepts, and a MongoDB deployment without a DataSource. */
class DatabaseSettingsTest {

    private static final String GOOD_URI = "mongodb://catalog:s3cret@h1,h2,h3/catalog?replicaSet=rs0&socketTimeoutMS=30000";

    @Test
    void postgresqlIsTheDefault() {
        assertThat(bind(Map.of()).backend()).isEqualTo(DatabaseSettings.Backend.POSTGRESQL);
        assertThat(bind(Map.of("keel.database.backend", "oracle")).backend()).isEqualTo(DatabaseSettings.Backend.ORACLE);
    }

    @Test
    void mongodbNeedsAUriThatNamesTheDatabaseAndBoundsEveryWait() {
        DatabaseSettings settings = bind(Map.of("keel.database.backend", "mongodb", "keel.database.mongodb.uri", GOOD_URI));
        assertThat(settings.mongodb().connectionString().getDatabase()).isEqualTo("catalog");

        assertRefused(Map.of("keel.database.backend", "mongodb"), "keel.database.mongodb.uri is required");
        assertRefused(Map.of("keel.database.backend", "mongodb", "keel.database.mongodb.uri",
                "mongodb://catalog:s3cret@h1/?socketTimeoutMS=30000"), "must name the catalog's database");
        assertRefused(Map.of("keel.database.backend", "mongodb", "keel.database.mongodb.uri",
                "mongodb://catalog:s3cret@h1/catalog?replicaSet=rs0"), "must set socketTimeoutMS");
    }

    @Test
    void onlyAMongodbDeploymentLeavesOutTheDataSource() {
        assertThat(excludedAfterPostProcessing(Map.of())).isNull();
        assertThat(excludedAfterPostProcessing(Map.of("keel.database.backend", "oracle"))).isNull();
        assertThat(excludedAfterPostProcessing(Map.of("keel.database.backend", "mongodb")))
                .isEqualTo(MongodbHasNoDataSource.DATA_SOURCE);
        assertThat(excludedAfterPostProcessing(Map.of("keel.database.backend", "MongoDB",
                MongodbHasNoDataSource.EXCLUDE, "a.First,b.Second")))
                .isEqualTo("a.First,b.Second," + MongodbHasNoDataSource.DATA_SOURCE);

        // As a container sets it: an environment variable.
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("KEEL_DATABASE_BACKEND", "mongodb")));
        new MongodbHasNoDataSource().postProcessEnvironment(environment, null);
        assertThat(environment.getProperty(MongodbHasNoDataSource.EXCLUDE)).isEqualTo(MongodbHasNoDataSource.DATA_SOURCE);
    }

    private static DatabaseSettings bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("keel.database", DatabaseSettings.class);
    }

    /** Refused with {@code message}, which never quotes the URI's password. */
    private static void assertRefused(Map<String, String> properties, String message) {
        assertThatThrownBy(() -> bind(properties))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining(message).message().doesNotContain("s3cret");
    }

    private static String excludedAfterPostProcessing(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        new MongodbHasNoDataSource().postProcessEnvironment(environment, null);
        return environment.getProperty(MongodbHasNoDataSource.EXCLUDE);
    }
}

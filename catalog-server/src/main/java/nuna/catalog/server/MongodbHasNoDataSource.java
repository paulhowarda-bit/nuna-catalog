package nuna.catalog.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * A MongoDB deployment has no JDBC database, so it leaves out Boot's DataSource, which would refuse to start without
 * {@code spring.datasource.url}. Whatever else {@code spring.autoconfigure.exclude} lists stays excluded.
 * Registered in {@code META-INF/spring.factories}; it runs after the configuration files are read.
 */
public final class MongodbHasNoDataSource implements EnvironmentPostProcessor {

    static final String DATA_SOURCE = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";
    static final String EXCLUDE = "spring.autoconfigure.exclude";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Binder binder = Binder.get(environment);
        boolean mongodb = binder.bind("nuna.database.backend", DatabaseSettings.Backend.class)
                .map(backend -> backend == DatabaseSettings.Backend.MONGODB).orElse(false);
        if (!mongodb) {
            return;
        }
        List<String> excluded = new ArrayList<>(binder.bind(EXCLUDE, Bindable.listOf(String.class)).orElse(List.of()));
        excluded.add(DATA_SOURCE);
        environment.getPropertySources().addFirst(new MapPropertySource("nuna-mongodb-has-no-datasource",
                Map.of(EXCLUDE, String.join(",", excluded))));
    }
}

package nuna.changefeed;

import java.util.List;
import java.util.Map;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.SourceConnector;

/**
 * A SingleStore connector pipeline that copies one table's changes into an Iceberg table through the catalog:
 * {@code CREATE PIPELINE ... AS LOAD DATA CONNECTOR 'ChangeFeedConnector' CONFIG '{...}' ... INTO TABLE <receipts>}.
 * The configuration is described on {@code nuna.changefeed.engine.FeedConfig}. With {@code singlestore.tables} in
 * place of {@code singlestore.table} it copies each table listed into an Iceberg table of the same name, in one
 * extractor ({@link ChangeFeedTask}).
 */
public final class ChangeFeedConnector extends SourceConnector {

    static final String VERSION = "0.1.0";

    private Map<String, String> props;

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public void start(Map<String, String> props) {
        this.props = Map.copyOf(props);
    }

    @Override
    public Class<? extends Task> taskClass() {
        return ChangeFeedTask.class;
    }

    /**
     * One task, whatever tasks.max says: a table's change feed is one ordered stream with one Iceberg writer, and
     * the tables of {@code singlestore.tables} share the task so they share one extractor's memory.
     */
    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        return List.of(props);
    }

    @Override
    public void stop() {}

    @Override
    public ConfigDef config() {
        return new ConfigDef();
    }
}

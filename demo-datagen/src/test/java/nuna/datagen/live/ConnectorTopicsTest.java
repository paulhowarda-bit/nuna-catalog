package nuna.datagen.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nuna.datagen.world.Feed;
import org.junit.jupiter.api.Test;

/** The Iceberg sink (demo/brokerage/connector.json) reads every topic the world sends on, and no other (item 153). */
class ConnectorTopicsTest {

    @Test
    void theSinkReadsEveryFeedsTopic() throws IOException {
        String json = Files.readString(Path.of("..", "demo", "brokerage", "connector.json"));
        Matcher m = Pattern.compile("\"topics\":\\s*\"([^\"]*)\"").matcher(json);
        assertThat(m.find()).isTrue();
        assertThat(Arrays.asList(m.group(1).split(","))).containsExactlyInAnyOrderElementsOf(
            Arrays.stream(Feed.values()).filter(f -> f.topic != null).map(f -> f.topic).toList());
    }
}

package nuna.catalog.tests.kafka;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The Kafka broker a test starts: Testcontainers' {@link KafkaContainer} on the image named by the system
 * property {@code kafka.image}, with a start command that survives Docker still writing the starter script.
 *
 * <p>Testcontainers starts the container with a shell that waits for {@value #STARTER} and runs it as soon as
 * it exists. Docker creates that file before it has finished writing it, and running a file still open for
 * writing fails with "Text file busy", exit 126: the container stops and the test fails at startup. The fast
 * build lost tests to that exit code on 2026-11-11, 11-12 and 11-13, twice with the demo stack busy in the same
 * Docker, and a 100 MB starter script reproduces it every time. This command runs the script again while it
 * fails with 126, for up to 10 s. Exec refuses a busy file before the script runs a line, so the retry starts
 * Kafka once; only a script that itself ended with 126 would run again.
 */
public final class TestKafka {

    static final String STARTER = "/tmp/testcontainers_start.sh";

    private static final String START = "while [ ! -f " + STARTER + " ]; do sleep 0.1; done; tries=0; until "
            + STARTER + "; do status=$?; tries=$((tries + 1)); "
            + "if [ \"$status\" -ne 126 ] || [ \"$tries\" -ge 100 ]; then exit \"$status\"; fi; sleep 0.1; done";

    private TestKafka() {
    }

    /** A broker, not yet started, whose heap is {@code heap} (such as {@code 256m}) at its least and most. */
    public static KafkaContainer container(String heap) {
        return new KafkaContainer(DockerImageName.parse(System.getProperty("kafka.image")))
                .withCommand("sh", "-c", START)
                .withEnv("KAFKA_HEAP_OPTS", "-Xms" + heap + " -Xmx" + heap);
    }
}

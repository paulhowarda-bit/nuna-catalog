package nuna.catalog.tests.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testcontainers.DockerClientFactory;

/** Through the named pipe, as every test JVM on Windows reaches Docker (backlog 291). */
@Tag("heavy")
@EnabledOnOs(OS.WINDOWS)
class FreshPipeStrategyTest {

    /**
     * Podman closes a connection idle for 10 s; a POST, which the HTTP client never sends twice, then went out on it
     * and found no answer. Twelve seconds idle, then one.
     */
    @Test
    void aPostAfterTwelveIdleSecondsIsAnswered() throws Exception {
        DockerClientFactory factory = DockerClientFactory.instance();
        DockerClient docker = factory.client();
        assertThat(factory.isUsing(FreshPipeStrategy.class)).as("the strategy the root build names").isTrue();
        docker.pingCmd().exec();

        Thread.sleep(12_000);

        String id;
        try {
            id = docker.createNetworkCmd().withName("nuna-fresh-pipe-" + UUID.randomUUID()).exec().getId();
        } catch (RuntimeException e) {
            // The failure, not its stack: Testcontainers' client is on that stack, and bites/run.sh reads a failure with
            // Testcontainers on it as fixtures that did not start (backlog 304).
            throw new AssertionError("a POST after 12 s idle went out on a pooled connection Podman had closed, and found no answer: "
                    + chain(e));
        }
        docker.removeNetworkCmd(id).exec();
    }

    /** Each throwable in the cause chain, as its class and message. */
    private static String chain(Throwable e) {
        StringBuilder text = new StringBuilder(e.toString());
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            text.append("; caused by ").append(cause);
        }
        return text.toString();
    }
}

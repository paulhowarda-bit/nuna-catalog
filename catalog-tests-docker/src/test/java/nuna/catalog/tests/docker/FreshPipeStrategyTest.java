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

        String id = docker.createNetworkCmd().withName("nuna-fresh-pipe-" + UUID.randomUUID()).exec().getId();
        docker.removeNetworkCmd(id).exec();
    }
}

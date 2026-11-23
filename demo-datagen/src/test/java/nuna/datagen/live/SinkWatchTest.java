package nuna.datagen.live;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SinkWatchTest {

    @Test
    void aFailedTaskOrConnectorIsSeen() {
        assertThat(SinkWatch.failed("""
            {"name":"brokerage-lake","connector":{"state":"RUNNING","worker_id":"connect:8083"},\
            "tasks":[{"id":0,"state":"FAILED","worker_id":"connect:8083","trace":"RESTException"}],"type":"sink"}"""))
            .isTrue();
        assertThat(SinkWatch.failed("{\"connector\": {\"state\": \"FAILED\"}, \"tasks\": []}")).isTrue();
    }

    @Test
    void aRunningOrRestartingSinkIsLeftAlone() {
        assertThat(SinkWatch.failed("""
            {"name":"brokerage-lake","connector":{"state":"RUNNING","worker_id":"connect:8083"},\
            "tasks":[{"id":0,"state":"RUNNING","worker_id":"connect:8083"}],"type":"sink"}""")).isFalse();
        assertThat(SinkWatch.failed("{\"connector\":{\"state\":\"RUNNING\"},\"tasks\":[{\"state\":\"RESTARTING\"}]}"))
            .isFalse();
    }
}

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

    /**
     * The owners' sink is started again only once beneficial_owners has a classification of its own (item 152, D27): the
     * one it inherits from brokerage.party does not open it.
     */
    @Test
    void aTableIsClassifiedOnlyByAClassificationAtItsOwnLevel() {
        String inherited = """
            {"target":{"kind":"table","id":"7f0c"},"attributes":[
              {"attribute":"classification","level":"namespace:11ab","value":"restricted"},
              {"attribute":"owner","level":"table:7f0c","value":"party-operations"}],"effective":{},"holds":[]}""";
        String own = """
            {"target":{"kind":"table","id":"7f0c"},"attributes":[
              {"attribute":"classification","level":"namespace:11ab","value":"restricted"},
              {"attribute":"classification","level":"table:7f0c","value":"restricted"}],"effective":{},"holds":[]}""";
        assertThat(SinkWatch.classified(inherited)).isFalse();
        assertThat(SinkWatch.classified(own)).isTrue();
        assertThat(SinkWatch.classified("{\"target\":{\"kind\":\"table\",\"id\":\"7f0c\"},\"attributes\":[]}")).isFalse();
    }
}

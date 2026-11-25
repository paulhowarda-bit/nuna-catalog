package nuna.catalog.audit.publish;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.LogStamp;
import nuna.catalog.audit.SampleEvents;
import nuna.catalog.audit.StampedEvent;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

/**
 * What the audit topic and the change stream are sent for each record, without a broker: the log's line byte for byte,
 * its run and number included (D23, amended 2026-11-25; item 276), keyed by the record's subject.
 */
class KafkaRecordTest {

    private static final UUID RUN = UUID.fromString("a0000000-0000-4000-8000-000000000276");

    @Test
    void eachRecordIsTheLogsLineKeyedByItsSubject() {
        for (CatalogEvent event : SampleEvents.events()) {
            StampedEvent logged = new StampedEvent(event, new LogStamp(RUN, 7));

            ProducerRecord<byte[], byte[]> record = KafkaEventPublisher.record("nuna.catalog.audit", logged);

            assertThat(record.topic()).isEqualTo("nuna.catalog.audit");
            assertThat(record.value()).as("the log's line for %s", event.type()).isEqualTo(logged.encode());
            assertThat(EventCodec.envelope(record.value()).stamp()).contains(new LogStamp(RUN, 7));
            assertThat(EventCodec.decode(record.value())).isEqualTo(event);
            assertThat(record.key()).isEqualTo(event.subject() == null ? null : event.subject().getBytes(UTF_8));
            assertThat(new String(record.headers().lastHeader(KafkaEventPublisher.CONTENT_TYPE_HEADER).value(), UTF_8))
                    .isEqualTo(EventCodec.MEDIA_TYPE);
        }
    }
}

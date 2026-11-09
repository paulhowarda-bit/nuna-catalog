package fidice.catalog.server;

import fidice.catalog.audit.AuthFailure;
import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.audit.Denial;
import fidice.catalog.audit.EventCodec;
import fidice.catalog.server.rest.AuditPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A test sink behind the server's {@link AuditPublisher} seam: it records every synchronous audit event (denials,
 * authentication failures) after a round trip through {@link EventCodec}, so a recorded event is one the
 * catalog-audit wire format accepts.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuditSinkForTests {

    /** The events published so far, in order. */
    public static final class Sink implements AuditPublisher {

        private final List<CatalogEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(CatalogEvent event) {
            events.add(EventCodec.decode(EventCodec.encode(event)));
        }

        public void clear() {
            events.clear();
        }

        public List<CatalogEvent> events() {
            return List.copyOf(events);
        }

        public List<Denial> denials() {
            return events.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                    .toList();
        }

        public List<AuthFailure> authFailures() {
            return events.stream().map(CatalogEvent::data).filter(AuthFailure.class::isInstance)
                    .map(AuthFailure.class::cast).toList();
        }
    }

    @Bean
    @Primary
    Sink recordingAudit() {
        return new Sink();
    }
}

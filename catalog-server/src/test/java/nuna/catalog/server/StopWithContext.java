package nuna.catalog.server;

import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Stops what an initializer started for a test context when that context closes (backlog 123). This module's tests
 * keep one context at a time ({@code spring.properties}), and Spring closes the last one when a class needs another,
 * so a container started for one class stops when the next class starts, instead of idling in Docker until the test
 * JVM exits. Idle containers held that way (Oracle, MongoDB, two Keycloaks, a Kafka and a PostgreSQL for every
 * context) filled Docker's memory and swap while another module's tests ran beside these, and each test that then
 * waited on a container waited out a stall.
 *
 * <p>The stop comes after the context's own beans are destroyed: an initializer registers it before any bean exists,
 * and Spring destroys in the reverse order, so the server and its pools close before the database they use.
 */
final class StopWithContext {

    private StopWithContext() {
    }

    static void register(ConfigurableApplicationContext context, String name, Runnable stop) {
        ((DefaultSingletonBeanRegistry) context.getBeanFactory()).registerDisposableBean(name, stop::run);
    }
}

package fidice.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** The endpoints list in /v1/config must name exactly the routes this server serves (D13). */
@SpringBootTest
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class})
class AdvertisedEndpointsTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Test
    void advertisesExactlyTheServedRoutes() {
        Set<String> served = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, method) -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/v1/")) {
                    continue; // the management API (M7) is not part of the Iceberg REST surface
                }
                if (methods.isEmpty()) {
                    served.add("ANY " + path);
                }
                methods.forEach(m -> served.add(m.name() + " " + path));
            }
        });
        served.remove("GET /v1/config");
        served.remove("ANY /error");
        served.remove("ANY /v1/oauth/tokens");
        // The management API (grants, engines) is not part of the Iceberg REST spec, so it is never advertised.
        served.removeIf(route -> route.contains(" /management/"));

        assertThat(served).containsExactlyInAnyOrderElementsOf(AdvertisedEndpoints.ALL);
    }
}

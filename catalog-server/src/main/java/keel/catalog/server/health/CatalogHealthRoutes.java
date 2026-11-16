package keel.catalog.server.health;

import keel.catalog.security.Separation;
import keel.catalog.server.rest.Callers;
import keel.catalog.server.rest.RequestContext;
import keel.catalog.server.security.Denials;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Catalog health for the console's health page (D38; docs/observability/health-page.md), for platform operators only
 * (SEC-1, {@code keel.security.platform-operator-groups}). Anyone else is refused with 403, audited like every refusal
 * on a management route, before anything is gathered.
 *
 * <ul>
 *   <li>{@code GET /management/v1/health/server}: this server's own figures over the last day.</li>
 *   <li>{@code GET /management/v1/health/catalog}: the whole deployment's, this server's added to those of every other
 *       server that answers ({@link Peers}), with each server and whether it answered.</li>
 * </ul>
 */
@RestController
class CatalogHealthRoutes {

    static final String SERVER = "/management/v1/health/server";
    static final String CATALOG = "/management/v1/health/catalog";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HealthWindow window;
    private final Peers peers;
    private final Separation separation;
    private final Callers callers;
    private final Denials denials;

    CatalogHealthRoutes(HealthWindow window, Peers peers, Separation separation, Callers callers, Denials denials) {
        this.window = window;
        this.peers = peers;
        this.separation = separation;
        this.callers = callers;
        this.denials = denials;
    }

    /** This server's figures over the last day, for the server gathering them; platform operators only. */
    @GetMapping(path = SERVER, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> server(HttpServletRequest http) {
        operator(http, "readServerHealth");
        HealthWindow.Window own = window.lastDay();
        ObjectNode body = JSON.createObjectNode();
        ObjectNode server = body.putObject("server");
        server.put("id", own.id().toString());
        server.put("name", own.name());
        server.put("started-at", own.startedAt().toString());
        body.put("from", own.from().toString());
        body.put("to", own.to().toString());
        ObjectNode figures = body.putObject("figures");
        own.figures().forEach(figures::put);
        return json(body);
    }

    /** The whole deployment's figures over the last day, and which servers they come from; platform operators only. */
    @GetMapping(path = CATALOG, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> catalog(HttpServletRequest http) {
        operator(http, "readCatalogHealth");
        HealthWindow.Window own = window.lastDay();
        List<Peers.Answer> answers = peers.ask(http.getHeader("Authorization"));

        Map<String, Double> sum = new TreeMap<>(own.figures());
        ArrayNode servers = JSON.createArrayNode();
        servers.add(server(own, null).put("this-server", true));
        Set<UUID> counted = new HashSet<>(Set.of(own.id()));
        int silent = 0;
        for (Peers.Answer answer : answers) {
            switch (answer) {
                case Peers.Answered answered -> {
                    // This server, reached through the headless Service, or one reached at two addresses: once.
                    if (counted.add(answered.window().id())) {
                        answered.window().figures().forEach((name, value) -> sum.merge(name, value, Double::sum));
                        servers.add(server(answered.window(), answered.address()));
                    }
                }
                case Peers.Silent quiet -> {
                    silent++;
                    servers.add(JSON.createObjectNode().put("address", quiet.address()).put("answered", false)
                            .put("why", quiet.why()));
                }
            }
        }
        ObjectNode body = Figures.shape(sum);
        body.put("to", own.to().toString());
        body.put("answered", counted.size());
        body.put("silent", silent);
        body.set("servers", servers);
        return json(body);
    }

    private static ObjectNode server(HealthWindow.Window window, String address) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", window.id().toString());
        node.put("name", window.name());
        if (address != null) {
            node.put("address", address);
        }
        node.put("answered", true);
        node.put("started-at", window.startedAt().toString());
        node.put("from", window.from().toString());
        return node;
    }

    /** A platform operator (SEC-1); anyone else is refused, audited, before anything is read or asked. */
    private void operator(HttpServletRequest http, String operation) {
        RequestContext request = RequestContext.of(http, callers);
        if (request.caller().subject().groups().stream().noneMatch(separation.platformOperatorGroups()::contains)) {
            throw denials.refuse(request.actor(), operation, null, null, "no_privilege",
                    "only platform operators may call " + operation);
        }
    }

    private static ResponseEntity<String> json(ObjectNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(body));
    }
}

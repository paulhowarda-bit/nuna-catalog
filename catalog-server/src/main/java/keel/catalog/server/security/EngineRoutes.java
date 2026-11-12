package keel.catalog.server.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import keel.catalog.audit.CatalogEvent;
import keel.catalog.audit.EngineChange;
import keel.catalog.audit.EventCodec;
import keel.catalog.core.CatalogError;
import keel.catalog.security.Separation;
import keel.catalog.server.rest.Callers;
import keel.catalog.server.rest.RequestContext;
import keel.catalog.server.rest.RestSettings;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.Change;
import keel.catalog.store.CommitOutcomeUnknownException;
import keel.catalog.store.OutboxEvent;
import keel.catalog.store.TrustedEngine;
import keel.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The trusted-engine registry (D17): which clients may name the end user they act for. Run by platform
 * operators only (D18), who hold no data access. Every change goes through the outbox; every refusal is audited.
 */
@RestController
class EngineRoutes {

    static final String ENGINES = "/management/v1/engines";

    record EngineSpec(String issuer, @JsonProperty("client-id") String clientId,
                      @JsonProperty("end-user-claim") String endUserClaim, List<String> flags) {
    }

    private final CatalogStore store;
    private final Separation separation;
    private final Set<String> issuers;
    private final Callers callers;
    private final Denials denials;
    private final RestSettings settings;
    private final Clock clock;

    EngineRoutes(CatalogStore store, Separation separation, SecuritySettings security, Callers callers,
                 Denials denials, RestSettings settings, Clock clock) {
        this.store = store;
        this.separation = separation;
        this.issuers = security.issuers().stream().map(SecuritySettings.Issuer::issuer).collect(Collectors.toSet());
        this.callers = callers;
        this.denials = denials;
        this.settings = settings;
        this.clock = clock;
    }

    /** Registers an engine, or replaces its registration. The issuer must be one the catalog trusts. */
    @PutMapping(ENGINES)
    ResponseEntity<String> register(@RequestBody(required = false) String body, HttpServletRequest http) {
        RequestContext request = operator(http, "registerEngine");
        EngineSpec spec = GrantRoutes.read(body, EngineSpec.class);
        if (spec.issuer() == null || spec.clientId() == null || spec.clientId().isBlank()) {
            throw CatalogError.badRequest("An engine is named by its issuer and client-id");
        }
        if (!issuers.contains(spec.issuer())) {
            throw CatalogError.badRequest("Not a trusted issuer: " + spec.issuer());
        }
        TrustedEngine engine;
        try {
            engine = new TrustedEngine(spec.issuer(), spec.clientId(),
                    spec.endUserClaim() == null ? "sub" : spec.endUserClaim(),
                    spec.flags() == null ? Set.of() : Set.copyOf(spec.flags()));
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest(e.getMessage());
        }
        WriteResult result = write(() -> store.putTrustedEngine(engine, change(EngineChange.Action.REGISTERED,
                engine, request)));
        if (!(result instanceof WriteResult.Applied) && !(result instanceof WriteResult.Unchanged)) {
            throw new IllegalStateException("putTrustedEngine answered " + result);
        }
        return ResponseEntity.noContent().build();
    }

    /** Removes an engine: its clients may no longer name an end user. */
    @DeleteMapping(ENGINES)
    ResponseEntity<String> remove(@RequestParam String issuer, @RequestParam(name = "client-id") String clientId,
                                  HttpServletRequest http) {
        RequestContext request = operator(http, "removeEngine");
        Optional<TrustedEngine> engine = store.findTrustedEngine(issuer, clientId);
        if (engine.isEmpty()) {
            throw CatalogError.of(404, "NotFoundException", "No such engine: " + clientId + " of " + issuer);
        }
        WriteResult result = write(() -> store.removeTrustedEngine(issuer, clientId,
                change(EngineChange.Action.REMOVED, engine.get(), request)));
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Missing missing -> throw CatalogError.of(404, "NotFoundException",
                    "No such engine: " + clientId + " of " + issuer);
            default -> throw new IllegalStateException("removeTrustedEngine answered " + result);
        };
    }

    /** Every registered engine. */
    @GetMapping(ENGINES)
    ResponseEntity<String> list(HttpServletRequest http) {
        operator(http, "listEngines");
        List<Map<String, Object>> engines = store.listTrustedEngines().stream()
                .sorted(Comparator.comparing(TrustedEngine::issuer).thenComparing(TrustedEngine::clientId))
                .map(engine -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("issuer", engine.issuer());
                    out.put("client-id", engine.clientId());
                    out.put("end-user-claim", engine.endUserClaim());
                    out.put("flags", engine.flags().stream().sorted().toList());
                    return out;
                }).toList();
        return GrantRoutes.json(Map.of("engines", engines));
    }

    /** The request's context, when its subject is a platform operator; otherwise an audited refusal. */
    private RequestContext operator(HttpServletRequest http, String operation) {
        RequestContext request = RequestContext.of(http, callers);
        Set<String> groups = request.caller().subject().groups();
        if (groups.stream().noneMatch(separation.platformOperatorGroups()::contains)) {
            throw denials.refuse(request.actor(), operation, null, null, "no_privilege",
                    "only platform operators run the trusted-engine registry");
        }
        return request;
    }

    private Change change(EngineChange.Action action, TrustedEngine engine, RequestContext request) {
        Instant now = clock.instant();
        CatalogEvent event = CatalogEvent.create(settings.auditSource(), now, new EngineChange(action,
                engine.issuer(), engine.clientId(), engine.endUserClaim(), engine.flags().stream().sorted().toList(),
                request.actor()));
        return new Change(UUID.randomUUID(), now, List.of(new OutboxEvent(event.type().typeName(),
                new String(EventCodec.encode(event), StandardCharsets.UTF_8))), Optional.empty());
    }

    private static WriteResult write(java.util.function.Supplier<WriteResult> write) {
        try {
            return write.get();
        } catch (CommitOutcomeUnknownException e) {
            throw CatalogError.of(500, "ServiceFailureException",
                    "The change may or may not have been applied; repeat it: " + e.getMessage());
        }
    }
}

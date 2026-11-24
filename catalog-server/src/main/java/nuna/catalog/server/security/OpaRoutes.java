package nuna.catalog.server.security;

import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Wire;
import nuna.catalog.security.AuthenticationFailure;
import nuna.catalog.security.Authorizer.Target;
import nuna.catalog.security.TokenAuthenticator;
import nuna.catalog.security.TrustedEngine;
import nuna.catalog.server.StoreOutage;
import nuna.catalog.server.rest.OnRef;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RowColumnRule;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trino's OPA access control, answered by nuna itself (D20, backlog 115): Trino's {@code access-control.properties}
 * points {@code opa.policy.uri}, {@code opa.policy.row-filters-uri} and {@code opa.policy.batch-column-masking-uri}
 * here. Allowing is always yes: nuna's own routes decide what a user reaches. Row filters and column masks come from
 * the rules the grant admin set ({@link RuleRoutes}), for the user whose own token Trino forwards
 * ({@link OpaAnswers}). OPA's calls carry no token of their own, so these routes are outside authentication
 * ({@link Authentication}); what they answer depends only on a token they verify themselves, and fails closed.
 */
@RestController
class OpaRoutes {

    static final String ALLOW = "/opa/v1/allow";
    static final String ROW_FILTERS = "/opa/v1/row-filters";
    static final String COLUMN_MASKS = "/opa/v1/column-masks";

    private static final Logger LOG = LoggerFactory.getLogger(OpaRoutes.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OpaAnswers answers;

    OpaRoutes(OpaSettings settings, TokenAuthenticator authenticator, CatalogStore store, Holdings holdings,
              Clock clock) {
        OpaAnswers.Users users = token -> token.flatMap(bearer -> user(authenticator, store, bearer));
        OpaAnswers.Rules rules = (warehouse, levels, table, user) ->
                table(store, holdings, clock, warehouse, levels, table, user)
                        .map(object -> store.rulesOn(object.id()).stream().map(RowColumnRule.Stored::rule).toList());
        this.answers = new OpaAnswers(settings.catalogs(), users, rules);
    }

    @PostMapping(value = ALLOW, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> allow() {
        return ResponseEntity.ok(answers.allow());
    }

    @PostMapping(value = ROW_FILTERS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> rowFilters(@RequestBody(required = false) String body) {
        return answer(body, answers::rowFilters);
    }

    @PostMapping(value = COLUMN_MASKS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> columnMasks(@RequestBody(required = false) String body) {
        return answer(body, answers::columnMasks);
    }

    private static ResponseEntity<String> answer(String body, Function<JsonNode, String> ask) {
        JsonNode json;
        try {
            json = JSON.readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(Wire.error(400, "BadRequestException", "The body is not JSON"));
        }
        try {
            return ResponseEntity.ok(ask.apply(json == null ? JSON.createObjectNode() : json));
        } catch (AuthenticationFailure keysUnavailable) {
            // The user's token could not be checked: no answer, so Trino fails the query rather than run it
            // with rules that may be wrong.
            LOG.warn("OPA answer withheld: {}", keysUnavailable.getMessage());
            return ResponseEntity.status(503).header("Retry-After", StoreOutage.RETRY_AFTER_SECONDS)
                    .contentType(MediaType.APPLICATION_JSON).body(Wire.error(503, "ServiceUnavailableException",
                            "The user's token could not be checked; retry later"));
        }
    }

    /**
     * The user a token names, as themselves: whoever sends it, an engine's standing is not theirs. A token not
     * accepted names no one; one that could not be checked (its issuer's keys out of reach) is no answer at all.
     */
    private static Optional<Caller> user(TokenAuthenticator authenticator, CatalogStore store, String token) {
        try {
            Principal principal = authenticator.authenticate(token, Optional.empty(),
                    (TrustedEngine engine) -> store.findTrustedEngine(engine.issuer(), engine.clientId())
                            .isPresent()).principal();
            return Optional.of(new Caller(principal, "opa", false, Optional.empty()));
        } catch (AuthenticationFailure failure) {
            if (failure.status() == 503) {
                throw failure;
            }
            LOG.info("OPA: the token forwarded was not accepted ({}); the query gets no rows", failure.getMessage());
            return Optional.empty();
        }
    }

    /** The table the name holds, through the ref the warehouse names, when the user holds something on it. */
    private static Optional<CatalogObject> table(CatalogStore store, Holdings holdings, Clock clock, String warehouse,
                                                 List<String> levels, String table, Caller user) {
        Optional<CatalogRecord> catalog = store.findCatalog(Names.normalize(OnRef.catalogName(warehouse)))
                .filter(found -> holdings.inCatalog(user, found.id()));
        if (catalog.isEmpty()) {
            return Optional.empty();
        }
        List<String> path = levels.stream().map(Names::normalize).toList();
        String name = Names.normalize(table);
        Optional<String> ref = OnRef.refName(warehouse);
        Optional<CatalogObject> object = ref.isEmpty()
                ? store.findNamespace(catalog.get().id(), path).flatMap(ns -> store.findObject(ns.id(), name))
                : store.findRef(catalog.get().id(), Names.normalize(ref.get()))
                        .filter(found -> !found.expiredAt(clock.instant()))
                        .flatMap(found -> store.resolveOnRef(found.id(), path, name))
                        .filter(entry -> entry.kind() == RefEntry.of(CatalogObject.Kind.TABLE))
                        .flatMap(entry -> store.readObject(entry.id()))
                        .filter(found -> found.state() != CatalogObject.State.UNREGISTERED);
        return object.filter(found -> found.kind() == CatalogObject.Kind.TABLE)
                .filter(found -> holdings.something(user, new Target.TableOrView(found)));
    }
}

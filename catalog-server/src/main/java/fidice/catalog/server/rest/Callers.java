package fidice.catalog.server.rest;

import fidice.catalog.core.principal.Caller;
import fidice.catalog.core.principal.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;

/**
 * Who a request comes from. M5 replaces the anonymous default with token validation (D15-D17); until
 * then every request is the same anonymous caller and nothing is authorized.
 */
@FunctionalInterface
public interface Callers {

    Caller ANONYMOUS_CALLER = new Caller(new Principal("anonymous", Set.of()), "unknown", false, Optional.empty());

    Callers ANONYMOUS = request -> ANONYMOUS_CALLER;

    Caller caller(HttpServletRequest request);
}

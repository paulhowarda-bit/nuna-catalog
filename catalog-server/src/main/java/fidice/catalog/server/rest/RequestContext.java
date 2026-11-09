package fidice.catalog.server.rest;

import fidice.catalog.audit.Actor;
import fidice.catalog.core.principal.Caller;
import fidice.catalog.core.principal.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** Who a request comes from, and its id: the client's {@code X-Request-ID}, or one made up here. */
public record RequestContext(Caller caller, String requestId) {

    public static RequestContext of(HttpServletRequest request, Callers callers) {
        String id = request.getHeader(Http.REQUEST_ID);
        if (id == null || id.isBlank() || id.length() > 128) {
            id = UUID.randomUUID().toString();
        }
        return new RequestContext(callers.caller(request), id);
    }

    public Actor actor() {
        return new Actor(caller.principal().id(), caller.endUser().map(Principal::id).orElse(null), caller.engine(),
                requestId);
    }
}

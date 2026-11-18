package nuna.catalog.server.rest;

import nuna.catalog.audit.Actor;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.server.telemetry.RequestIds;
import jakarta.servlet.http.HttpServletRequest;

/** Who a request comes from, and its id, as {@link RequestIds} decided it. */
public record RequestContext(Caller caller, String requestId) {

    public static RequestContext of(HttpServletRequest request, Callers callers) {
        return new RequestContext(callers.caller(request), RequestIds.of(request));
    }

    public Actor actor() {
        return new Actor(caller.principal().id(), caller.endUser().map(Principal::id).orElse(null), caller.engine(),
                requestId);
    }
}

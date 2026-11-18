package nuna.catalog.server.rest;

import nuna.catalog.core.principal.Caller;
import jakarta.servlet.http.HttpServletRequest;

/** Who a request comes from: the caller its bearer token established (M5, D16, D17). */
@FunctionalInterface
public interface Callers {

    Caller caller(HttpServletRequest request);
}

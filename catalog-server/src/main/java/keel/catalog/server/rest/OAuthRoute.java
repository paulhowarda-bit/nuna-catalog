package keel.catalog.server.rest;

import keel.catalog.iceberg.Wire;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /v1/oauth/tokens} is never served (D13): tokens come from the organisation's IdP, and the
 * catalog only validates them (D15). Any call gets a fixed OAuth error that says where to go instead.
 * It is not advertised in {@code /v1/config}.
 */
@RestController
class OAuthRoute {

    static final String TOKENS = "/v1/oauth/tokens";

    private final RestSettings settings;

    OAuthRoute(RestSettings settings) {
        this.settings = settings;
    }

    @RequestMapping(TOKENS)
    ResponseEntity<String> tokens() {
        return Http.json(400, Wire.oauthError("unsupported_grant_type", "This catalog does not issue tokens. Get an "
                + "access token from " + settings.identityProvider() + " and send it as a bearer token."));
    }
}

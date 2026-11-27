package nuna.catalog.server.ui;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the web console ({@code nuna.ui.*}). The console signs in like any other API caller: with an
 * IdP token, which the server validates against {@code nuna.security.issuers}. Nothing here is secret; all of
 * it reaches the browser through {@code /ui/config.json}.
 *
 * @param devTokenLogin offer a box to paste a bearer token, for local development only; off unless set
 * @param oidc          the IdP the console signs people in with (authorization code with PKCE), or null
 * @param docsUrl       where the console's documentation links go: {@code /docs/} (the default) is the site the
 *                      server serves itself, an http(s) URL is a copy hosted elsewhere, and {@code none} hides every
 *                      documentation link
 * @param grafanaUrl    the Grafana that holds nuna's dashboards, an http(s) URL; the Service page links to it, and
 *                      without it (the default) shows no link (backlog 319)
 */
@ConfigurationProperties("nuna.ui")
public record UiSettings(@DefaultValue("false") boolean devTokenLogin, Oidc oidc,
                         @DefaultValue(UiSettings.OWN_DOCS) String docsUrl, String grafanaUrl) {

    /** The documentation site this server serves (UiWiring). */
    public static final String OWN_DOCS = "/docs/";

    public UiSettings {
        if (docsUrl == null || docsUrl.isBlank()) {
            docsUrl = OWN_DOCS;
        }
        docsUrl = docsUrl.strip();
        if (!docsUrl.equalsIgnoreCase("none") && !docsUrl.startsWith("/") && !docsUrl.startsWith("https://")
                && !docsUrl.startsWith("http://")) {
            throw new IllegalArgumentException("nuna.ui.docs-url must be a path on this server, an http(s) URL or "
                    + "none, not " + docsUrl);
        }
        if (!docsUrl.endsWith("/") && !docsUrl.equalsIgnoreCase("none")) {
            docsUrl = docsUrl + "/";
        }
        grafanaUrl = grafanaUrl == null || grafanaUrl.isBlank() ? null : grafanaUrl.strip();
        if (grafanaUrl != null && !grafanaUrl.startsWith("https://") && !grafanaUrl.startsWith("http://")) {
            throw new IllegalArgumentException("nuna.ui.grafana-url must be an http(s) URL, not " + grafanaUrl);
        }
    }

    /** The documentation's root URL for the console, or null when its links are hidden. */
    public String docsLink() {
        return docsUrl.equalsIgnoreCase("none") ? null : docsUrl;
    }

    /**
     * A public OIDC client: no client secret, PKCE instead.
     *
     * @param issuer      the issuer whose {@code /.well-known/openid-configuration} the browser reads; its origin
     *                    is the only one besides the server the console may call
     * @param clientId    the console's client id at the IdP
     * @param scopes      what to ask for; the access token must be one the catalog accepts (its audience)
     * @param redirectUri where the IdP sends the browser back, after signing in and after signing out (so its client
     *                    must allow it as a redirect URI and as a post-logout redirect URI); empty means the
     *                    console's own {@code /ui/} URL
     */
    public record Oidc(URI issuer, String clientId, @DefaultValue("openid") List<String> scopes, URI redirectUri) {

        public Oidc {
            Objects.requireNonNull(issuer, "nuna.ui.oidc.issuer");
            Objects.requireNonNull(clientId, "nuna.ui.oidc.client-id");
            if (!"https".equals(issuer.getScheme()) && !"http".equals(issuer.getScheme())) {
                throw new IllegalArgumentException("nuna.ui.oidc.issuer must be an http(s) URL, not " + issuer);
            }
            scopes = List.copyOf(scopes);
        }

        /** The issuer's origin, which the console's Content-Security-Policy lets it connect to. */
        String origin() {
            return issuer.getScheme() + "://" + issuer.getRawAuthority();
        }
    }
}

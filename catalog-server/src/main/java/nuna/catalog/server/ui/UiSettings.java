package nuna.catalog.server.ui;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * @param brand         an install's brand pack, or null for Ice line, the console's own look (backlog 333)
 */
@ConfigurationProperties("nuna.ui")
public record UiSettings(@DefaultValue("false") boolean devTokenLogin, Oidc oidc,
                         @DefaultValue(UiSettings.OWN_DOCS) String docsUrl, String grafanaUrl, Brand brand) {

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
     * @param name        the IdP's name in words, such as "the demo's Keycloak", which the sign-in button, "Going to…"
     *                    and the sign-out messages show; empty means the issuer's host (backlog 332)
     */
    public record Oidc(URI issuer, String clientId, @DefaultValue("openid") List<String> scopes, URI redirectUri,
                       String name) {

        public Oidc {
            Objects.requireNonNull(issuer, "nuna.ui.oidc.issuer");
            Objects.requireNonNull(clientId, "nuna.ui.oidc.client-id");
            if (!"https".equals(issuer.getScheme()) && !"http".equals(issuer.getScheme())) {
                throw new IllegalArgumentException("nuna.ui.oidc.issuer must be an http(s) URL, not " + issuer);
            }
            scopes = List.copyOf(scopes);
            name = name == null || name.isBlank() ? null : name.strip();
        }

        /** The issuer's origin, which the console's Content-Security-Policy lets it connect to. */
        String origin() {
            return issuer.getScheme() + "://" + issuer.getRawAuthority();
        }
    }

    /**
     * An install's brand pack (backlog 333): a folder on the server whose files the console serves under
     * {@code /ui/brand/} and the documentation under {@code /docs/assets/brand/}, in place of the empty ones the jar
     * holds. Its {@code brand.css} sets tokens only (docs/site/configure/brand.html gives the contract); its
     * {@code favicon.svg}, if any, replaces the tab's icon. Files are read on every request, so a changed pack is
     * served without a restart. Unset, the console and the documentation are Ice line, as before.
     *
     * @param dir         the folder holding the pack; it must exist
     * @param name        the product's name in words, in titles, messages and beside the mark; "nuna" unless set
     * @param markLabel   the header mark's accessible name, before ", overview"; the product's name unless set
     * @param showName    whether the product's name sits beside the mark in the header, as "nuna" does beside the
     *                    peak; false for a mark that already spells it, and each page's eyebrow then starts with it
     * @param toggleLabel the words of the header's button that turns the pack on and off for one viewer (Paul,
     *                    2026-11-28; D56), such as "Harbour mode"; "Brand mode" unless set
     */
    public record Brand(String dir, @DefaultValue("nuna") String name, String markLabel,
                        @DefaultValue("true") boolean showName, @DefaultValue("Brand mode") String toggleLabel) {

        public Brand {
            if (dir == null || dir.isBlank()) {
                throw new IllegalArgumentException("nuna.ui.brand.dir must name the folder holding the brand pack");
            }
            dir = dir.strip();
            if (!Files.isDirectory(Path.of(dir))) {
                throw new IllegalArgumentException("nuna.ui.brand.dir must be a folder on the server, not " + dir);
            }
            name = name == null || name.isBlank() ? "nuna" : name.strip();
            markLabel = markLabel == null || markLabel.isBlank() ? name : markLabel.strip();
            toggleLabel = toggleLabel == null || toggleLabel.isBlank() ? "Brand mode" : toggleLabel.strip();
        }

        /** The pack's folder as a location for Spring's resource handlers: a file URL ending in a slash. */
        String location() {
            String url = Path.of(dir).toAbsolutePath().toUri().toString();
            return url.endsWith("/") ? url : url + "/";
        }
    }
}

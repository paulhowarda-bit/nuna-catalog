package nuna.catalog.server.ui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.util.DigestUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The web console's static files under {@code /ui/}, served by the catalog server itself: same origin, so no
 * CORS. The files need no sign-in; every API call the console makes does. Every {@code /ui} response carries a
 * strict Content-Security-Policy: scripts, styles and fonts only from the server's own files (no inline code), and
 * connections only to the server and the configured IdP.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UiSettings.class)
public class UiWiring implements WebMvcConfigurer {

    /** Where the console's files are on the classpath. */
    static final String FILES = "classpath:/nuna/ui/";

    /** The console's own paths: its page and its flat set of files. Nothing else skips authentication. */
    private static final Pattern PUBLIC = Pattern.compile("/ui/?|/ui/[a-z0-9-]+\\.(html|js|css|json|svg|woff2|txt)");

    /** Each console file's ETag, by the file's description; the jar's files never change while the server runs. */
    private static final Map<String, String> ETAGS = new ConcurrentHashMap<>();

    /** Whether {@code path} (without the context path) is one of the console's files, which need no token. */
    public static boolean isConsoleFile(String path) {
        return PUBLIC.matcher(path).matches();
    }

    /** The console's Content-Security-Policy; {@code idpOrigin} is null when no IdP is configured. */
    static String contentSecurityPolicy(String idpOrigin) {
        return "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; "
                + "connect-src 'self'" + (idpOrigin == null ? "" : " " + idpOrigin) + "; "
                + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    }

    /**
     * The console's files, revalidated on every use and answered by an ETag of their bytes (backlog 203). Not by
     * Last-Modified: Gradle's reproducible jars give every entry the same 1980 date, so a browser that loaded the
     * console before an upgrade got 304 for every file after it and went on running the old console.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ui/*").addResourceLocations(FILES).setCacheControl(CacheControl.noCache())
                .setUseLastModified(false).setEtagGenerator(UiWiring::etag);
    }

    /** A strong ETag of a file's bytes; the files are read from the jar, so each is hashed once. */
    static String etag(Resource file) {
        return ETAGS.computeIfAbsent(file.getDescription(), d -> {
            try (InputStream in = file.getInputStream()) {
                return "\"" + DigestUtils.md5DigestAsHex(in) + "\"";
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Bean
    FilterRegistrationBean<ConsoleHeaders> consoleHeaders(UiSettings settings) {
        FilterRegistrationBean<ConsoleHeaders> registration = new FilterRegistrationBean<>(new ConsoleHeaders(
                contentSecurityPolicy(settings.oidc() == null ? null : settings.oidc().origin())));
        registration.addUrlPatterns("/ui", "/ui/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** The security headers of every {@code /ui} response, set before anything else answers. */
    static final class ConsoleHeaders extends OncePerRequestFilter {

        private final String policy;

        ConsoleHeaders(String policy) {
            this.policy = policy;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("Content-Security-Policy", policy);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
            chain.doFilter(request, response);
        }
    }
}

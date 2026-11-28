package nuna.catalog.server.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The documentation site as {@link UiWiring} serves it under {@code /docs/}, in a bare Spring MVC context, so this
 * needs only Java: the pages the build copies into the jar, without the site's tools or design notes, and the setting
 * that points the console's links at it.
 */
@SpringJUnitWebConfig(classes = {UiWiring.class, DocsSiteTest.Mvc.class})
class DocsSiteTest {

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc {
    }

    @Autowired
    private WebApplicationContext context;

    @Test
    void theSitesPagesAreServedAndItsToolsAreNot() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        MockHttpServletResponse page = mvc.perform(get("/docs/console/table.html")).andReturn().getResponse();
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getContentAsString()).contains("<h1");
        assertThat(page.getHeader("ETag")).matches("\"[0-9a-f]{32}\"");
        assertThat(mvc.perform(get("/docs/assets/docs.js")).andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(mvc.perform(get("/docs/")).andReturn().getResponse().getForwardedUrl()).isEqualTo("/docs/index.html");
        MockHttpServletResponse bare = mvc.perform(get("/docs")).andReturn().getResponse();
        assertThat(bare.getStatus()).isEqualTo(302);
        assertThat(bare.getRedirectedUrl()).isEqualTo("/docs/");

        assertThat(mvc.perform(get("/docs/tools/check.py")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/docs/DESIGN.md")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void theSiteNeedsNoTokenAndAllowsNoConnections() throws Exception {
        assertThat(UiWiring.isDocsFile("/docs")).isTrue();
        assertThat(UiWiring.isDocsFile("/docs/console/table.html")).isTrue();
        assertThat(UiWiring.isDocsFile("/docsx")).isFalse();
        assertThat(UiWiring.isDocsFile("/management/v1/docs")).isFalse();

        MockHttpServletResponse response = new MockHttpServletResponse();
        new UiWiring.ConsoleHeaders(UiWiring.DOCS_POLICY)
                .doFilter(new MockHttpServletRequest("GET", "/docs/index.html"), response, new MockFilterChain());
        assertThat(response.getHeader("Content-Security-Policy")).startsWith("default-src 'none';")
                .doesNotContain("connect-src").doesNotContain("unsafe");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    void theDocsLinkIsTheServersOwnSiteAnotherCopyOrNone() {
        assertThat(new UiSettings(false, null, null, null, null).docsLink()).isEqualTo("/docs/");
        assertThat(new UiSettings(false, null, " ", null, null).docsLink()).isEqualTo("/docs/");
        assertThat(new UiSettings(false, null, "https://docs.nuna.example/v1", null, null).docsLink())
                .isEqualTo("https://docs.nuna.example/v1/");
        assertThat(new UiSettings(false, null, "None", null, null).docsLink()).isNull();
        assertThatThrownBy(() -> new UiSettings(false, null, "javascript:alert(1)", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nuna.ui.docs-url");
    }

    @Test
    void theGrafanaLinkIsAnHttpUrlOrNone() {
        assertThat(new UiSettings(false, null, null, null, null).grafanaUrl()).isNull();
        assertThat(new UiSettings(false, null, null, " ", null).grafanaUrl()).isNull();
        assertThat(new UiSettings(false, null, null, " https://grafana.nuna.example ", null).grafanaUrl())
                .isEqualTo("https://grafana.nuna.example");
        assertThatThrownBy(() -> new UiSettings(false, null, null, "javascript:alert(1)", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nuna.ui.grafana-url");
    }
}

package nuna.catalog.server.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The console's files as {@link UiWiring} serves them, in a bare Spring MVC context, so this needs only Java: each
 * answers by an ETag of its bytes and never by the jar's 1980 Last-Modified, so a browser revalidating after an
 * upgrade gets the new file (backlog 203).
 */
@SpringJUnitWebConfig(classes = {UiWiring.class, ConsoleFilesCacheTest.Mvc.class})
class ConsoleFilesCacheTest {

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc {
    }

    @Autowired
    private WebApplicationContext context;

    @Test
    void aFileAnswersByAnEtagOfItsBytesAndNeverByLastModified() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        MockHttpServletResponse first = mvc.perform(get("/ui/app.js")).andReturn().getResponse();
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(first.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(first.getHeader("Last-Modified")).isNull();
        String etag = first.getHeader("ETag");
        assertThat(etag).matches("\"[0-9a-f]{32}\"");
        assertThat(mvc.perform(get("/ui/api.js")).andReturn().getResponse().getHeader("ETag"))
                .as("another file, another tag").isNotEqualTo(etag);

        // The browser revalidates with the tag it holds: the same bytes answer 304, changed bytes 200 with the file.
        MockHttpServletResponse same = mvc.perform(get("/ui/app.js").header("If-None-Match", etag)).andReturn()
                .getResponse();
        assertThat(same.getStatus()).isEqualTo(304);
        MockHttpServletResponse stale = mvc.perform(get("/ui/app.js").header("If-None-Match", "\"0123\"")
                .header("If-Modified-Since", "Fri, 01 Feb 1980 00:00:00 GMT")).andReturn().getResponse();
        assertThat(stale.getStatus()).isEqualTo(200);
        assertThat(stale.getContentAsString()).isEqualTo(first.getContentAsString()).isNotEmpty();
    }
}

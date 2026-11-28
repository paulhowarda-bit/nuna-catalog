package nuna.catalog.server.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The brand slot as {@link UiWiring} serves it with a pack set (backlog 333), in a bare Spring MVC context, so this
 * needs only Java: the neutral test pack in {@code src/test/resources/brand-pack}, copied to a folder of its own, is
 * served at the console's and the documentation's fixed brand paths, a file it lacks falls back to the jar's, and a
 * file changed under the running server is served fresh. {@link ConsoleFilesCacheTest} checks the slot with no pack.
 */
@SpringJUnitWebConfig(classes = {UiWiring.class, BrandSlotTest.Mvc.class})
class BrandSlotTest {

    /** The test pack's files, copied here before the context starts, as an install's mounted folder. */
    private static final Path PACK = copyOfThePack();

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc {
    }

    @DynamicPropertySource
    static void pack(DynamicPropertyRegistry registry) {
        registry.add("nuna.ui.brand.dir", PACK::toString);
        registry.add("nuna.ui.brand.name", () -> "Larchmere");
        registry.add("nuna.ui.brand.toggle-label", () -> "Larchmere mode");
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UiSettings settings;

    @Test
    void thePacksFilesAreServedAtBothBrandPathsAndWhatItLacksFallsBackToTheJars() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String pack = Files.readString(PACK.resolve("brand.css"));
        for (String path : List.of("/ui/brand/brand.css", "/docs/assets/brand/brand.css")) {
            MockHttpServletResponse css = mvc.perform(get(path)).andReturn().getResponse();
            assertThat(css.getStatus()).as(path).isEqualTo(200);
            assertThat(css.getContentAsString()).as(path).isEqualTo(pack).contains("--brand-pack");
            assertThat(css.getHeader("Cache-Control")).isEqualTo("no-cache");
            assertThat(css.getHeader("Last-Modified")).isNull();
        }
        assertThat(mvc.perform(get("/ui/brand/mark.svg")).andReturn().getResponse().getContentAsString())
                .contains("LARCHMERE");

        // The pack has no favicon here, so the tab keeps Ice line's, from the jar: no 404.
        Files.deleteIfExists(PACK.resolve("favicon.svg"));
        MockHttpServletResponse icon = mvc.perform(get("/ui/brand/favicon.svg")).andReturn().getResponse();
        assertThat(icon.getStatus()).isEqualTo(200);
        assertThat(icon.getContentAsString()).isEqualTo(resource("/nuna/ui/favicon.svg"));
        assertThat(mvc.perform(get("/ui/brand/nothing.css")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aPackFileChangedUnderTheRunningServerIsServedFresh() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        Path css = PACK.resolve("brand.css");
        String before = Files.readString(css);
        MockHttpServletResponse first = mvc.perform(get("/ui/brand/brand.css")).andReturn().getResponse();
        String etag = first.getHeader("ETag");
        assertThat(etag).matches("\"[0-9a-f]{32}\"");
        assertThat(mvc.perform(get("/ui/brand/brand.css").header("If-None-Match", etag)).andReturn().getResponse()
                .getStatus()).isEqualTo(304);
        try {
            Files.writeString(css, before + "\n:root { --accent: #123456; }\n");
            MockHttpServletResponse changed = mvc.perform(get("/ui/brand/brand.css").header("If-None-Match", etag))
                    .andReturn().getResponse();
            assertThat(changed.getStatus()).as("a changed pack answers with the new file").isEqualTo(200);
            assertThat(changed.getContentAsString()).contains("#123456");
            assertThat(changed.getHeader("ETag")).isNotEqualTo(etag);
        } finally {
            Files.writeString(css, before);
        }
    }

    @Test
    void theBrandsPathsAndFontsNeedNoToken() {
        for (String path : List.of("/ui/brand/brand.css", "/ui/brand/favicon.svg", "/ui/brand/mark.svg",
                "/ui/brand/pack-sans.woff2", "/ui/brand/pack-sans.woff", "/ui/brand/pack-sans.ttf",
                "/ui/brand/pack-sans.otf", "/ui/brand.js")) {
            assertThat(UiWiring.isConsoleFile(path)).as(path).isTrue();
        }
        assertThat(UiWiring.isConsoleFile("/ui/brand/../config.json")).isFalse();
        assertThat(UiWiring.isConsoleFile("/ui/brand/sub/brand.css")).isFalse();
        assertThat(UiWiring.isConsoleFile("/ui/brand/brand.js")).as("a pack holds no script").isFalse();
    }

    @Test
    void thePacksWordsHaveTheirDefaultsAndTheFolderMustExist() {
        assertThat(settings.brand().name()).isEqualTo("Larchmere");
        assertThat(settings.brand().markLabel()).isEqualTo("Larchmere");
        assertThat(settings.brand().showName()).isTrue();
        assertThat(settings.brand().toggleLabel()).isEqualTo("Larchmere mode");

        UiSettings.Brand bare = new UiSettings.Brand(" " + PACK + " ", " ", null, true, "");
        assertThat(bare.name()).isEqualTo("nuna");
        assertThat(bare.markLabel()).isEqualTo("nuna");
        assertThat(bare.toggleLabel()).isEqualTo("Brand mode");
        assertThat(bare.location()).startsWith("file:").endsWith("/");
        assertThatThrownBy(() -> new UiSettings.Brand(PACK.resolve("missing").toString(), null, null, true, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nuna.ui.brand.dir");
        assertThatThrownBy(() -> new UiSettings.Brand(" ", null, null, true, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nuna.ui.brand.dir");
    }

    private static Path copyOfThePack() {
        try {
            Path folder = Files.createTempDirectory("nuna-brand-pack");
            folder.toFile().deleteOnExit();
            for (String file : List.of("brand.css", "mark.svg", "favicon.svg")) {
                Path to = folder.resolve(file);
                Files.writeString(to, resource("/brand-pack/" + file));
                to.toFile().deleteOnExit();
            }
            return folder;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String resource(String path) {
        try (InputStream in = BrandSlotTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

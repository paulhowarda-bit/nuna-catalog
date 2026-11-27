package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** How the server answers a request its routes cannot bind (backlog 311). */
class ErrorResponsesTest {

    @RestController
    static class Routes {

        @GetMapping("/count")
        ResponseEntity<String> count(@RequestParam(required = false) Integer jobs) {
            return ResponseEntity.ok(String.valueOf(jobs));
        }

        @GetMapping("/objects/{id}")
        ResponseEntity<String> object(@PathVariable UUID id) {
            return ResponseEntity.ok(id.toString());
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Routes())
            .setControllerAdvice(new ErrorResponses()).build();

    @Test
    void aParameterThatIsNotANumberIsA400NamingIt() throws Exception {
        MvcResult result = mvc.perform(get("/count").param("jobs", "abc")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .contains("BadRequestException", "Parameter jobs must be a whole number, not 'abc'");
        assertThat(mvc.perform(get("/count").param("jobs", "7")).andReturn().getResponse().getContentAsString())
                .isEqualTo("7");
    }

    @Test
    void aPathVariableOfTheWrongTypeIsA400NamingIt() throws Exception {
        MvcResult result = mvc.perform(get("/objects/not-a-uuid")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("BadRequestException", "Parameter id must be a UUID");
    }
}

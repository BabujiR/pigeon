package com.pigeon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigeon.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Every response, success or error, carries the correlation id; errors repeat it in the body. */
class ErrorResponseTests extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void callerCorrelationIdComesBackOnAnError() throws Exception {
        mvc.perform(get("/v1/communications/{id}", UUID.randomUUID()).header("X-Correlation-Id", "support-ticket-42"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "support-ticket-42"))
                .andExpect(jsonPath("$.correlationId").value("support-ticket-42"));
    }

    @Test
    void generatedCorrelationIdMatchesBetweenHeaderAndBody() throws Exception {
        MvcResult r = mvc.perform(post("/v1/communications").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())   // no Idempotency-Key
                .andReturn();

        String header = r.getResponse().getHeader("X-Correlation-Id");
        String body = json.readTree(r.getResponse().getContentAsString()).path("correlationId").asText();
        assertThat(header).isNotBlank().isEqualTo(body);
    }

    @Test
    void validationErrorsCarryTheCorrelationIdToo() throws Exception {
        mvc.perform(post("/v1/communications").header("Idempotency-Key", "err-" + UUID.randomUUID())
                        .header("X-Correlation-Id", "corr-validation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"templateId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").value("corr-validation"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void errorResponsesFromOtherControllersCarryItToo() throws Exception {
        mvc.perform(get("/v1/suppressions/{a}", "nobody-" + UUID.randomUUID() + "@example.com")
                        .header("X-Correlation-Id", "corr-supp"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").value("corr-supp"));
        mvc.perform(get("/v1/metrics").param("window", "soon").header("X-Correlation-Id", "corr-metrics"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").value("corr-metrics"));
    }

    @Test
    void nonApiEndpointsAlsoGetAnId() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(header().exists("X-Correlation-Id"));
    }
}

package com.pigeon.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigeon.support.FullStackIntegrationTest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 6 "done when": one request's log lines, from the API through the worker to the SES event,
 * all carry the same correlation id, and no full email address appears anywhere in the logs.
 */
@ExtendWith(OutputCaptureExtension.class)
class TraceabilityTests extends FullStackIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(45);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private UUID send(String recipient, String correlationId) throws Exception {
        String body = """
                {"templateId":"payment-receipt","recipient":"%s","stream":"TRANSACTIONAL",
                 "variables":{"firstName":"Dana","amount":"$412.60","paidOn":"November 6, 2026","last4":"4417"}}
                """.formatted(recipient);
        MvcResult r = mvc.perform(post("/v1/communications")
                        .header("Idempotency-Key", "trace-" + UUID.randomUUID())
                        .header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).get("requestId").asText());
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from message_record where id = ?", String.class, id);
    }

    private static List<String> linesWith(CapturedOutput output, String text) {
        return Arrays.stream(output.getAll().split("\\R")).filter(l -> l.contains(text)).toList();
    }

    @Test
    void oneCorrelationIdFollowsTheEmailFromApiToSesEvent(CapturedOutput output) throws Exception {
        String t = UUID.randomUUID().toString().substring(0, 8);
        String corr = "trace-" + t;
        UUID id = send("trace.person+" + t + "@simulator.amazonses.com", corr);

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(statusOf(id)).isEqualTo("DELIVERED");
            List<String> lines = linesWith(output, "[" + corr + "]");
            assertThat(lines).anySatisfy(l -> assertThat(l).contains("Accepted message " + id));
            assertThat(lines).anySatisfy(l -> assertThat(l).contains("Sent message " + id));
            assertThat(lines).anySatisfy(l -> assertThat(l).contains("SES Delivery event for message " + id));
        });

        // The sender and event lines also carry the message id in their own column.
        assertThat(linesWith(output, "[" + corr + "] [" + id + "]")).isNotEmpty();
    }

    @Test
    void noFullEmailAddressIsEverLogged(CapturedOutput output) throws Exception {
        String t = UUID.randomUUID().toString().substring(0, 8);
        String delivered = "private.person+" + t + "@simulator.amazonses.com";
        String bounced = "bounce+private" + t + "@simulator.amazonses.com";

        UUID a = send(delivered, "privacy-a-" + t);
        UUID b = send(bounced, "privacy-b-" + t);

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(statusOf(a)).isEqualTo("DELIVERED");
            assertThat(statusOf(b)).isEqualTo("BOUNCED");
            assertThat(linesWith(output, "Suppressed")).isNotEmpty();
        });

        assertThat(output.getAll())
                .doesNotContain(delivered)
                .doesNotContain(bounced)
                .contains("p***@simulator.amazonses.com", "b***@simulator.amazonses.com");
    }
}

package com.pigeon.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigeon.support.FullStackIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Phase 4 end to end, entirely through the running platform:
 * API -> outbox -> SQS -> sender -> SES (LocalStack) -> simulated SES event -> SNS -> SQS
 * -> event processor -> status, history and suppression.
 *
 * <p>Addresses use SES mailbox simulator names with a random label, so every test is independent.
 */
class FeedbackLoopTests extends FullStackIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(45);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private static String simulator(String scenario) {
        return scenario + "+" + UUID.randomUUID().toString().substring(0, 8) + "@simulator.amazonses.com";
    }

    private ResultActions post(String recipient) throws Exception {
        String body = """
                {"templateId":"payment-receipt","recipient":"%s","stream":"TRANSACTIONAL",
                 "variables":{"firstName":"Dana","amount":"$412.60","paidOn":"November 6, 2026","last4":"4417"}}
                """.formatted(recipient);
        return mvc.perform(MockMvcRequestBuilders.post("/v1/communications")
                .header("Idempotency-Key", "loop-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID accept(String recipient) throws Exception {
        MvcResult r = post(recipient).andExpect(status().isAccepted()).andReturn();
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).get("requestId").asText());
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from message_record where id = ?", String.class, id);
    }

    private List<String> historyOf(UUID id) {
        return jdbc.queryForList("select event_type from message_event where message_id = ? order by id", String.class, id);
    }

    private String suppressionReason(String address) {
        List<String> r = jdbc.queryForList("select reason from suppression where address = ?", String.class, address);
        return r.isEmpty() ? null : r.get(0);
    }

    @Test
    void successAddressEndsDelivered() throws Exception {
        UUID id = accept(simulator("success"));

        await().atMost(WAIT).untilAsserted(() -> assertThat(statusOf(id)).isEqualTo("DELIVERED"));
        assertThat(historyOf(id)).containsExactly("ACCEPTED", "QUEUED", "SENT", "DELIVERY");
    }

    @Test
    void hardBounceSuppressesTheAddressAndBlocksTheNextRequest() throws Exception {
        String to = simulator("bounce");
        UUID id = accept(to);

        await().atMost(WAIT).untilAsserted(() -> assertThat(statusOf(id)).isEqualTo("BOUNCED"));
        assertThat(suppressionReason(to)).isEqualTo("HARD_BOUNCE");
        assertThat(jdbc.queryForObject("select detail from suppression where address = ?", String.class, to))
                .contains("5.1.1");
        assertThat(historyOf(id)).endsWith("BOUNCE");

        // The loop is closed: the next request to that address is never sent.
        post(to).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUPPRESSED"))
                .andExpect(jsonPath("$.suppressionReason").value("HARD_BOUNCE"));
    }

    @Test
    void complaintSuppressesEvenThoughTheEmailWasDelivered() throws Exception {
        String to = simulator("complaint");
        UUID id = accept(to);

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(statusOf(id)).isEqualTo("COMPLAINED");
            assertThat(historyOf(id)).contains("DELIVERY", "COMPLAINT");
        });
        assertThat(suppressionReason(to)).isEqualTo("COMPLAINT");
    }

    @Test
    void accountSuppressionListBounceIsRecordedWithItsOwnReason() throws Exception {
        String to = simulator("suppressionlist");
        UUID id = accept(to);

        await().atMost(WAIT).untilAsserted(() -> assertThat(statusOf(id)).isEqualTo("BOUNCED"));
        assertThat(suppressionReason(to)).isEqualTo("ACCOUNT_SUPPRESSION_LIST");
    }

    @Test
    void threeSoftBouncesSuppressButOneDoesNot() throws Exception {
        String to = "dana.mailboxfull+" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

        UUID first = accept(to);
        await().atMost(WAIT).untilAsserted(() -> assertThat(statusOf(first)).isEqualTo("BOUNCED"));
        assertThat(suppressionReason(to)).isNull();

        UUID second = accept(to);
        await().atMost(WAIT).untilAsserted(() -> assertThat(statusOf(second)).isEqualTo("BOUNCED"));
        UUID third = accept(to);
        await().atMost(WAIT).untilAsserted(() -> assertThat(statusOf(third)).isEqualTo("BOUNCED"));

        await().atMost(WAIT).untilAsserted(() -> assertThat(suppressionReason(to)).isEqualTo("REPEATED_SOFT_BOUNCE"));
        assertThat(jdbc.queryForObject("select count(*) from soft_bounce where address = ?", Integer.class, to))
                .isEqualTo(3);
    }
}

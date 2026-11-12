package com.pigeon.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigeon.support.FullStackIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 5 end to end: a batch with a few bad addresses goes all the way through SES and the
 * feedback loop; the bad addresses end up suppressed and the metrics count every send.
 */
class BatchEndToEndTests extends FullStackIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Test
    void dirtyBatchIsSentAndItsBadAddressesAreSuppressed() throws Exception {
        String t = UUID.randomUUID().toString().substring(0, 8);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            items.add(Map.of("recipient", "success+" + t + "-" + i + "@simulator.amazonses.com",
                    "variables", Map.of("firstName", "Member " + i)));
        }
        for (int i = 0; i < 3; i++) {
            items.add(Map.of("recipient", "bounce+" + t + "-" + i + "@simulator.amazonses.com",
                    "variables", Map.of("firstName", "Old " + i)));
        }
        Map<String, Object> body = Map.of("templateId", "payment-receipt", "stream", "BULK",
                "sharedVariables", Map.of("amount", "$412.60", "paidOn", "November 6, 2026", "last4", "4417"),
                "recipients", items);

        mvc.perform(post("/v1/communications/batch").header("Idempotency-Key", "e2e-batch-" + t)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.counts.QUEUED").value(10));

        String like = "%+" + t + "-%@simulator.amazonses.com";
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from message_record where recipient like ? and status = 'DELIVERED'",
                    Integer.class, like)).isEqualTo(7);
            assertThat(jdbc.queryForObject("select count(*) from message_record where recipient like ? and status = 'BOUNCED'",
                    Integer.class, like)).isEqualTo(3);
        });
        assertThat(jdbc.queryForObject("select count(*) from suppression where address like ? and reason = 'HARD_BOUNCE'",
                Integer.class, "bounce+" + t + "-%")).isEqualTo(3);

        mvc.perform(get("/v1/metrics").param("window", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.sent").value(org.hamcrest.Matchers.greaterThanOrEqualTo(10)))
                .andExpect(jsonPath("$.streams[?(@.configurationSet=='pigeon-bulk-prod')].rates.hardBounced")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.greaterThanOrEqualTo(3))));
    }
}

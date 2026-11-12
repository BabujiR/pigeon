package com.pigeon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigeon.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class BatchApiTests extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ResultActions batch(String key, Object body) throws Exception {
        return mvc.perform(post("/v1/communications/batch")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> item(String recipient, Map<String, String> variables) {
        return variables == null ? Map.of("recipient", recipient) : Map.of("recipient", recipient, "variables", variables);
    }

    private static Map<String, Object> receiptBatch(List<Map<String, Object>> items) {
        return Map.of("templateId", "payment-receipt", "stream", "TRANSACTIONAL",
                "sharedVariables", Map.of("amount", "$412.60", "paidOn", "November 6, 2026", "last4", "4417"),
                "recipients", items);
    }

    @Test
    void mixedBatchQueuesGoodItemsAndReportsTheRest() throws Exception {
        String t = tag();
        String suppressed = "batch-supp-" + t + "@example.com";
        jdbc.update("insert into suppression(address, reason, source) values (?, 'HARD_BOUNCE', 'test')", suppressed);

        List<Map<String, Object>> items = List.of(
                item("a-" + t + "@example.com", Map.of("firstName", "Ana")),
                item("b-" + t + "@example.com", Map.of("firstName", "Ben")),
                item("not-an-email", Map.of("firstName", "Cy")),
                item("d-" + t + "@example.com", null),                         // no firstName
                item(suppressed, Map.of("firstName", "Eve")));

        batch("batch-" + t, receiptBatch(items))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.counts.QUEUED").value(2))
                .andExpect(jsonPath("$.counts.REJECTED").value(2))
                .andExpect(jsonPath("$.counts.SUPPRESSED").value(1))
                .andExpect(jsonPath("$.items[2].error", containsString("recipient")))
                .andExpect(jsonPath("$.items[3].missingFields[0]").value("firstName"))
                .andExpect(jsonPath("$.items[4].suppressionReason").value("HARD_BOUNCE"));

        assertThat(jdbc.queryForObject("select count(*) from outbox o join message_record m on m.id = o.message_id "
                + "where m.recipient like ?", Integer.class, "%-" + t + "@example.com")).isEqualTo(2);
    }

    @Test
    void itemVariablesOverrideSharedOnes() throws Exception {
        String t = tag();
        MvcResult r = batch("batch-" + t, receiptBatch(List.of(
                item("over-" + t + "@example.com", Map.of("firstName", "Ana", "amount", "$1.00")))))
                .andExpect(status().isAccepted()).andReturn();

        String id = json.readTree(r.getResponse().getContentAsString()).at("/items/0/requestId").asText();
        assertThat(jdbc.queryForObject("select variables->>'amount' from message_record where id = ?::uuid", String.class, id))
                .isEqualTo("$1.00");
        assertThat(jdbc.queryForObject("select variables->>'last4' from message_record where id = ?::uuid", String.class, id))
                .isEqualTo("4417");
    }

    @Test
    void retryingTheSameBatchQueuesNothingTwice() throws Exception {
        String t = tag();
        Map<String, Object> body = receiptBatch(List.of(
                item("r1-" + t + "@example.com", Map.of("firstName", "Ana")),
                item("r2-" + t + "@example.com", Map.of("firstName", "Ben"))));

        JsonNode first = json.readTree(batch("batch-" + t, body).andReturn().getResponse().getContentAsString());
        JsonNode second = json.readTree(batch("batch-" + t, body).andReturn().getResponse().getContentAsString());

        assertThat(second.at("/items/0/requestId")).isEqualTo(first.at("/items/0/requestId"));
        assertThat(second.at("/items/1/replayed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from message_record where recipient like ?",
                Integer.class, "%-" + t + "@example.com")).isEqualTo(2);
    }

    @Test
    void moreThanOneThousandItemsIsRejected() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            items.add(item("x" + i + "@example.com", Map.of("firstName", "X")));
        }
        batch("batch-" + tag(), receiptBatch(items)).andExpect(status().isBadRequest());
    }

    @Test
    void unknownTemplateRejectsTheWholeBatch() throws Exception {
        Map<String, Object> body = Map.of("templateId", "no-such-template", "stream", "TRANSACTIONAL",
                "recipients", List.of(item("a@example.com", Map.of())));

        batch("batch-" + tag(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.templateId").value("no-such-template"));
    }

    @Test
    void emptyBatchIsRejected() throws Exception {
        batch("batch-" + tag(), receiptBatch(List.of())).andExpect(status().isBadRequest());
    }
}

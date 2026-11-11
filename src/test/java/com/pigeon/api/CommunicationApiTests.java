package com.pigeon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigeon.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 2: the Communication API against a real database.
 * Every test uses its own Idempotency-Key and recipient, so tests never interfere.
 */
class CommunicationApiTests extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    private static String recipient() {
        return "dana+" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private static String receipt(String recipient) {
        return """
                {"templateId":"payment-receipt","recipient":"%s","stream":"TRANSACTIONAL",
                 "variables":{"firstName":"Dana","amount":"$412.60","paidOn":"November 6, 2026","last4":"4417"}}
                """.formatted(recipient);
    }

    private MvcResult send(String key, String body) throws Exception {
        return mvc.perform(post("/v1/communications")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private UUID requestId(MvcResult result) throws Exception {
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get("requestId").asText());
    }

    // --- Happy path -------------------------------------------------------------------------

    @Test
    void validRequestIsAcceptedQueuedAndWrittenToTheOutbox() throws Exception {
        String to = recipient();

        MvcResult result = mvc.perform(post("/v1/communications")
                        .header("Idempotency-Key", key())
                        .header("X-Correlation-Id", "corr-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(receipt(to)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", startsWith("/v1/communications/")))
                .andExpect(header().string("X-Correlation-Id", "corr-123"))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.templateId").value("payment-receipt"))
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.configurationSet").value("pigeon-txn-prod"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();

        UUID id = requestId(result);
        assertThat(jdbc.queryForObject("select status from message_record where id = ?", String.class, id))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForList("select destination from outbox where message_id = ? and status = 'PENDING'",
                String.class, id)).containsExactly("pigeon-send-queue");
        assertThat(jdbc.queryForObject("select payload->>'messageId' from outbox where message_id = ?",
                String.class, id)).isEqualTo(id.toString());
        assertThat(jdbc.queryForList("select event_type from message_event where message_id = ? order by id",
                String.class, id)).containsExactly("ACCEPTED", "QUEUED");
    }

    @Test
    void queuePayloadCarriesIdsOnlyNeverMergeValues() throws Exception {
        UUID id = requestId(send(key(), receipt(recipient())));

        String payload = jdbc.queryForObject("select payload::text from outbox where message_id = ?", String.class, id);
        assertThat(payload).contains("messageId", "correlationId").doesNotContain("Dana", "4417", "$412.60");
    }

    @Test
    void recipientIsStoredLowerCased() throws Exception {
        UUID id = requestId(send(key(), receipt("Dana.MIXED@Example.COM")));

        assertThat(jdbc.queryForObject("select recipient from message_record where id = ?", String.class, id))
                .isEqualTo("dana.mixed@example.com");
    }

    @Test
    void bulkStreamUsesTheBulkConfigurationSet() throws Exception {
        String body = receipt(recipient()).replace("TRANSACTIONAL", "BULK");

        mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.configurationSet").value("pigeon-bulk-prod"));
    }

    @Test
    void numericMergeValuesAreAccepted() throws Exception {
        String body = """
                {"templateId":"password-reset","recipient":"%s","stream":"TRANSACTIONAL",
                 "variables":{"firstName":"Dana","resetLink":"https://member.example.com/r/1","expiresMinutes":30}}
                """.formatted(recipient());

        mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void xmlRequestIsAccepted() throws Exception {
        String xml = """
                <communication>
                  <templateId>enrollment-confirmation</templateId>
                  <recipient>%s</recipient>
                  <stream>TRANSACTIONAL</stream>
                  <variables>
                    <firstName>Dana</firstName>
                    <planName>Gold PPO 2027</planName>
                    <effectiveDate>January 1, 2027</effectiveDate>
                    <memberId>AB-448210</memberId>
                  </variables>
                </communication>
                """.formatted(recipient());

        mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_XML).content(xml)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.templateVersion").value(2));
    }

    @Test
    void xmlResponseWhenAskedFor() throws Exception {
        mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(receipt(recipient()))
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<status>QUEUED</status>")));
    }

    @Test
    void defaultsToJsonForWildcardAccept() throws Exception {
        mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(receipt(recipient()))
                        .accept(MediaType.ALL))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    // --- Idempotency ------------------------------------------------------------------------

    @Test
    void sameKeyAndSameBodyReturnsTheOriginalAndSendsNothingNew() throws Exception {
        String key = key();
        String body = receipt(recipient());

        UUID first = requestId(send(key, body));
        MvcResult again = send(key, body);

        assertThat(again.getResponse().getStatus()).isEqualTo(202);
        assertThat(again.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(requestId(again)).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from outbox where message_id = ?", Integer.class, first))
                .isEqualTo(1);
    }

    @Test
    void sameKeyDifferentJsonKeyOrderAndCaseIsStillTheSameRequest() throws Exception {
        String key = key();
        String to = recipient();
        UUID first = requestId(send(key, receipt(to)));

        String reordered = """
                {"stream":"TRANSACTIONAL","variables":{"last4":"4417","paidOn":"November 6, 2026",
                 "amount":"$412.60","firstName":"Dana"},"recipient":"%s","templateId":"payment-receipt"}
                """.formatted(to.toUpperCase());

        assertThat(requestId(send(key, reordered))).isEqualTo(first);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        String key = key();
        send(key, receipt(recipient()));

        mvc.perform(post("/v1/communications").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(receipt(recipient())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://pigeon.local/problems/idempotency-key-reused"))
                .andExpect(jsonPath("$.idempotencyKey").value(key));
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateExactlyOneMessage() throws Exception {
        String key = key();
        String to = recipient();
        String body = receipt(to);
        int callers = 6;

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                calls.add(() -> send(key, body));
            }
            Set<UUID> ids = new java.util.HashSet<>();
            for (Future<MvcResult> f : pool.invokeAll(calls)) {
                MvcResult r = f.get();
                assertThat(r.getResponse().getStatus()).isEqualTo(202);
                ids.add(requestId(r));
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from message_record where recipient = ?", Integer.class, to))
                .isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/v1/communications").contentType(MediaType.APPLICATION_JSON).content(receipt(recipient())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void overlongIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/v1/communications").header("Idempotency-Key", "k".repeat(201))
                        .contentType(MediaType.APPLICATION_JSON).content(receipt(recipient())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://pigeon.local/problems/invalid-idempotency-key"));
    }

    // --- Validation -------------------------------------------------------------------------

    @Test
    void missingMergeFieldsAreAllReportedAndNothingIsStored() throws Exception {
        String to = recipient();
        String body = """
                {"templateId":"payment-receipt","recipient":"%s","stream":"TRANSACTIONAL",
                 "variables":{"firstName":"Dana"}}
                """.formatted(to);

        mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://pigeon.local/problems/missing-merge-fields"))
                .andExpect(jsonPath("$.missingFields", containsInAnyOrder("amount", "last4", "paidOn")));

        assertThat(jdbc.queryForObject("select count(*) from message_record where recipient = ?", Integer.class, to))
                .isZero();
    }

    @Test
    void nullMergeValueCountsAsMissing() throws Exception {
        String body = """
                {"templateId":"payment-receipt","recipient":"%s","stream":"TRANSACTIONAL",
                 "variables":{"firstName":"Dana","amount":null,"paidOn":"November 6, 2026","last4":"4417","extra":null}}
                """.formatted(recipient());

        mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.missingFields", containsInAnyOrder("amount")));
    }

    @Test
    void unknownTemplateIsRejected() throws Exception {
        String body = receipt(recipient()).replace("payment-receipt", "no-such-template");

        mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.templateId").value("no-such-template"));
    }

    @Test
    void invalidFieldsAreAllListed() throws Exception {
        String body = """
                {"templateId":"","recipient":"not-an-email","variables":{}}
                """;

        mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("templateId", "recipient", "stream")));
    }

    @Test
    void unknownStreamValueIsRejected() throws Exception {
        String body = receipt(recipient()).replace("TRANSACTIONAL", "MARKETING");

        mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    // --- Suppression ------------------------------------------------------------------------

    @Test
    void suppressedRecipientIsRecordedButNeverQueued() throws Exception {
        String to = recipient();
        jdbc.update("insert into suppression(address, reason, source, detail) values (?, 'HARD_BOUNCE', 'test', ?)",
                to, "smtp; 550 5.1.1 user unknown");

        MvcResult result = mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(receipt(to.toUpperCase())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUPPRESSED"))
                .andExpect(jsonPath("$.suppressionReason").value("HARD_BOUNCE"))
                .andReturn();

        UUID id = requestId(result);
        assertThat(jdbc.queryForObject("select count(*) from outbox where message_id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForList("select event_type from message_event where message_id = ? order by id",
                String.class, id)).containsExactly("ACCEPTED", "SUPPRESSED");
    }

    // --- Status -----------------------------------------------------------------------------

    @Test
    void statusShowsCurrentStateAndHistory() throws Exception {
        UUID id = requestId(send(key(), receipt(recipient())));

        mvc.perform(get("/v1/communications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.events[0].type").value("ACCEPTED"))
                .andExpect(jsonPath("$.events[1].type").value("QUEUED"))
                .andExpect(jsonPath("$.events[1].detail.destination").value("pigeon-send-queue"))
                .andExpect(jsonPath("$.variables").doesNotExist());
    }

    @Test
    void unknownRequestIdIsNotFound() throws Exception {
        mvc.perform(get("/v1/communications/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://pigeon.local/problems/communication-not-found"));
    }

    @Test
    void malformedRequestIdIsBadRequest() throws Exception {
        mvc.perform(get("/v1/communications/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsafeCorrelationIdIsReplaced() throws Exception {
        MvcResult r = mvc.perform(post("/v1/communications").header("Idempotency-Key", key())
                        .header("X-Correlation-Id", "bad id\r\nX-Injected: 1")
                        .contentType(MediaType.APPLICATION_JSON).content(receipt(recipient())))
                .andExpect(status().isAccepted())
                .andReturn();

        String used = r.getResponse().getHeader("X-Correlation-Id");
        assertThat(used).isNotNull().doesNotContain("\n", " ", "Injected");
        assertThat(UUID.fromString(used)).isNotNull();   // a fresh UUID replaced the unsafe value
    }
}

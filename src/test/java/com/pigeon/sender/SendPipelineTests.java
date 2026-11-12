package com.pigeon.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigeon.support.FullStackIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 3 end to end: API -> outbox -> relay -> SQS -> sender -> SES (LocalStack).
 * LocalStack keeps every email SES "sent"; GET /_aws/ses returns them for inspection.
 */
class SendPipelineTests extends FullStackIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    SendService sendService;

    private final HttpClient http = HttpClient.newHttpClient();

    private static String recipient() {
        return "pipeline+" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private UUID accept(String recipient, String stream) throws Exception {
        String body = """
                {"templateId":"payment-receipt","recipient":"%s","stream":"%s",
                 "variables":{"firstName":"Dana","amount":"$412.60","paidOn":"November 6, 2026","last4":"4417"}}
                """.formatted(recipient, stream);
        MvcResult r = mvc.perform(post("/v1/communications")
                        .header("Idempotency-Key", "pipe-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).get("requestId").asText());
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from message_record where id = ?", String.class, id);
    }

    /** Emails LocalStack recorded for one recipient. */
    private List<JsonNode> sesMessagesTo(String recipient) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(localstackUrl("/_aws/ses"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode m : json.readTree(res.body()).path("messages")) {
            for (JsonNode to : m.path("Destination").path("ToAddresses")) {
                if (to.asText().equals(recipient)) {
                    found.add(m);
                }
            }
        }
        return found;
    }

    private UUID insertQueuedMessage(String recipient, String leaseSql) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into message_record(id, correlation_id, template_id, template_version, recipient, stream, status,
                                           variables, configuration_set, send_lease_until)
                values (?, 'corr-direct', 'payment-receipt', 1, ?, 'TRANSACTIONAL', 'QUEUED',
                        '{"firstName":"Dana","amount":"$1.00","paidOn":"today","last4":"0000"}'::jsonb,
                        'pigeon-txn-prod', %s)
                """.formatted(leaseSql), id, recipient);
        return id;
    }

    @Test
    void acceptedEmailIsPublishedSentAndRecorded() throws Exception {
        String to = recipient();
        UUID id = accept(to, "TRANSACTIONAL");

        await().atMost(WAIT).untilAsserted(() -> assertThat(statusOf(id)).isEqualTo("SENT"));

        String sesId = jdbc.queryForObject("select ses_message_id from message_record where id = ?", String.class, id);
        assertThat(sesId).isNotBlank();
        assertThat(jdbc.queryForObject("select attempt_count from message_record where id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from outbox where message_id = ?", String.class, id))
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForList("select event_type from message_event where message_id = ? order by id",
                String.class, id)).containsExactly("ACCEPTED", "QUEUED", "SENT");

        List<JsonNode> emails = sesMessagesTo(to);
        assertThat(emails).hasSize(1);
        JsonNode email = emails.get(0);
        assertThat(email.path("Subject").asText()).isEqualTo("Payment received: $412.60");
        assertThat(email.path("Source").asText()).contains("billing@notify.pigeon.local");
        assertThat(email.path("Body").path("html_part").asText()).contains("Dana", "4417");
        assertThat(email.path("Body").path("text_part").asText()).contains("Sent by Pigeon");
        assertThat(email.path("Id").asText()).isEqualTo(sesId);
    }

    @Test
    void bulkEmailIsSentThroughTheBulkConfigurationSet() throws Exception {
        UUID id = accept(recipient(), "BULK");

        await().atMost(WAIT).untilAsserted(() -> assertThat(statusOf(id)).isEqualTo("SENT"));
        assertThat(jdbc.queryForObject("select configuration_set from message_record where id = ?", String.class, id))
                .isEqualTo("pigeon-bulk-prod");
    }

    @Test
    void duplicateDeliveryOfAnAlreadySentMessageSendsNothing() throws Exception {
        String to = recipient();
        UUID id = accept(to, "TRANSACTIONAL");
        await().atMost(WAIT).untilAsserted(() -> assertThat(statusOf(id)).isEqualTo("SENT"));

        SendOutcome outcome = sendService.process(new SendCommand(id, "corr-dup"));

        assertThat(outcome.kind()).isEqualTo(SendOutcome.Kind.SKIPPED);
        assertThat(jdbc.queryForObject("select attempt_count from message_record where id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(sesMessagesTo(to)).hasSize(1);
    }

    @Test
    void recipientSuppressedAfterAcceptIsNotSent() throws Exception {
        String to = recipient();
        UUID id = insertQueuedMessage(to, "null");
        jdbc.update("insert into suppression(address, reason, source) values (?, 'COMPLAINT', 'test')", to);

        SendOutcome outcome = sendService.process(new SendCommand(id, "corr-supp"));

        assertThat(outcome.kind()).isEqualTo(SendOutcome.Kind.SKIPPED);
        assertThat(statusOf(id)).isEqualTo("SUPPRESSED");
        assertThat(jdbc.queryForList("select event_type from message_event where message_id = ?", String.class, id))
                .containsExactly("SUPPRESSED");
        assertThat(sesMessagesTo(to)).isEmpty();
    }

    @Test
    void activeLeaseHeldByAnotherWorkerMakesThisOneBackOff() throws Exception {
        String to = recipient();
        UUID id = insertQueuedMessage(to, "now() + interval '1 minute'");

        SendOutcome outcome = sendService.process(new SendCommand(id, "corr-lease"));

        assertThat(outcome.kind()).isEqualTo(SendOutcome.Kind.RETRY);
        assertThat(statusOf(id)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select attempt_count from message_record where id = ?", Integer.class, id))
                .isZero();
        assertThat(sesMessagesTo(to)).isEmpty();
    }

    @Test
    void expiredLeaseIsTakenOver() throws Exception {
        String to = recipient();
        UUID id = insertQueuedMessage(to, "now() - interval '1 minute'");

        SendOutcome outcome = sendService.process(new SendCommand(id, "corr-expired"));

        assertThat(outcome.kind()).isEqualTo(SendOutcome.Kind.SENT);
        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(sesMessagesTo(to)).hasSize(1);
    }

    @Test
    void unknownMessageIdIsRejectedSoItEndsInTheDeadLetterQueue() {
        assertThatThrownBy(() -> sendService.process(new SendCommand(UUID.randomUUID(), "corr-unknown")))
                .isInstanceOf(UnknownMessageException.class);
    }
}

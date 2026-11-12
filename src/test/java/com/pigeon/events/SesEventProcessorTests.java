package com.pigeon.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigeon.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The event processor's edge cases, called directly with hand-built SES events.
 * No AWS needed: events are passed in as the SQS message body would arrive.
 */
class SesEventProcessorTests extends PostgresIntegrationTest {

    @Autowired
    SesEventProcessor processor;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private record Msg(UUID id, String sesId, String recipient) {
    }

    private Msg insertMessage(String status) {
        UUID id = UUID.randomUUID();
        String sesId = "ses-" + UUID.randomUUID();
        String recipient = "evt+" + id.toString().substring(0, 8) + "@example.com";
        jdbc.update("""
                insert into message_record(id, correlation_id, template_id, template_version, recipient, stream,
                                           status, variables, configuration_set, ses_message_id)
                values (?, 'corr', 'payment-receipt', 1, ?, 'TRANSACTIONAL', ?, '{}'::jsonb, 'pigeon-txn-prod', ?)
                """, id, recipient, status, sesId);
        return new Msg(id, sesId, recipient);
    }

    private String sesEvent(String type, Msg m, boolean withTag, Map<String, Object> section) throws Exception {
        Map<String, Object> mail = withTag
                ? Map.of("messageId", m.sesId(), "timestamp", Instant.now().toString(), "destination", List.of(m.recipient()),
                         "tags", Map.of("messageRecordId", List.of(m.id().toString())))
                : Map.of("messageId", m.sesId(), "timestamp", Instant.now().toString(), "destination", List.of(m.recipient()));
        String sectionName = switch (type) {
            case "Delivery" -> "delivery";
            case "Bounce" -> "bounce";
            case "Complaint" -> "complaint";
            default -> type.toLowerCase();
        };
        return json.writeValueAsString(Map.of("eventType", type, "mail", mail, sectionName, section));
    }

    private String sns(String snsMessageId, String sesEventJson) throws Exception {
        return json.writeValueAsString(Map.of(
                "Type", "Notification",
                "MessageId", snsMessageId,
                "TopicArn", "arn:aws:sns:us-east-1:000000000000:pigeon-events-topic",
                "Message", sesEventJson));
    }

    private Map<String, Object> delivery() {
        return Map.of("timestamp", Instant.now().toString(), "smtpResponse", "250 OK", "recipients", List.of());
    }

    private Map<String, Object> complaint(String recipient) {
        return Map.of("timestamp", Instant.now().toString(), "complaintFeedbackType", "abuse",
                "complainedRecipients", List.of(Map.of("emailAddress", recipient)));
    }

    private Map<String, Object> bounce(String recipient, String type, String subType) {
        return Map.of("timestamp", Instant.now().toString(), "bounceType", type, "bounceSubType", subType,
                "bouncedRecipients", List.of(Map.of("emailAddress", recipient, "status", "5.1.1",
                        "diagnosticCode", "smtp; 550 5.1.1 user unknown")));
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from message_record where id = ?", String.class, id);
    }

    private int historyCount(UUID id, String type) {
        Integer n = jdbc.queryForObject("select count(*) from message_event where message_id = ? and event_type = ?",
                Integer.class, id, type);
        return n == null ? 0 : n;
    }

    @Test
    void deliveryMovesSentToDelivered() throws Exception {
        Msg m = insertMessage("SENT");

        SesEventProcessor.Result r = processor.process(sns(UUID.randomUUID().toString(), sesEvent("Delivery", m, true, delivery())));

        assertThat(r).isEqualTo(SesEventProcessor.Result.APPLIED);
        assertThat(statusOf(m.id())).isEqualTo("DELIVERED");
        assertThat(historyCount(m.id(), "DELIVERY")).isEqualTo(1);
    }

    @Test
    void redeliveredEventIsAppliedOnlyOnce() throws Exception {
        Msg m = insertMessage("SENT");
        String body = sns(UUID.randomUUID().toString(), sesEvent("Delivery", m, true, delivery()));

        assertThat(processor.process(body)).isEqualTo(SesEventProcessor.Result.APPLIED);
        assertThat(processor.process(body)).isEqualTo(SesEventProcessor.Result.DUPLICATE);
        assertThat(historyCount(m.id(), "DELIVERY")).isEqualTo(1);
    }

    @Test
    void lateDeliveryDoesNotUndoAComplaint() throws Exception {
        Msg m = insertMessage("SENT");

        processor.process(sns(UUID.randomUUID().toString(), sesEvent("Complaint", m, true, complaint(m.recipient()))));
        processor.process(sns(UUID.randomUUID().toString(), sesEvent("Delivery", m, true, delivery())));

        assertThat(statusOf(m.id())).isEqualTo("COMPLAINED");
        assertThat(historyCount(m.id(), "DELIVERY")).isEqualTo(1);   // still recorded in history
    }

    @Test
    void eventBeforeSentIsCommittedIsRetriedNotLost() throws Exception {
        Msg m = insertMessage("QUEUED");
        String body = sns(UUID.randomUUID().toString(), sesEvent("Delivery", m, true, delivery()));

        assertThatThrownBy(() -> processor.process(body)).isInstanceOf(EventNotReadyException.class);

        // The dedupe key was rolled back with the failed attempt, so the redelivery is processed.
        jdbc.update("update message_record set status = 'SENT' where id = ?", m.id());
        assertThat(processor.process(body)).isEqualTo(SesEventProcessor.Result.APPLIED);
        assertThat(statusOf(m.id())).isEqualTo("DELIVERED");
    }

    @Test
    void bounceForAnUnknownMessageStillSuppressesTheAddress() throws Exception {
        Msg ghost = new Msg(UUID.randomUUID(), "ses-unknown-" + UUID.randomUUID(), "ghost+" + UUID.randomUUID() + "@example.com");

        processor.process(sns(UUID.randomUUID().toString(), sesEvent("Bounce", ghost, false,
                bounce(ghost.recipient(), "Permanent", "General"))));

        assertThat(jdbc.queryForObject("select reason from suppression where address = ?", String.class, ghost.recipient()))
                .isEqualTo("HARD_BOUNCE");
    }

    @Test
    void rawEventWithoutTagIsMatchedBySesMessageId() throws Exception {
        Msg m = insertMessage("SENT");

        processor.process(sesEvent("Bounce", m, false, bounce(m.recipient(), "Permanent", "General")));

        assertThat(statusOf(m.id())).isEqualTo("BOUNCED");
        assertThat(jdbc.queryForObject("select source from suppression where address = ?", String.class, m.recipient()))
                .isEqualTo("ses-event:" + m.sesId());
    }

    @Test
    void oneTransientBounceIsCountedNotSuppressed() throws Exception {
        Msg m = insertMessage("SENT");

        processor.process(sns(UUID.randomUUID().toString(), sesEvent("Bounce", m, true,
                bounce(m.recipient(), "Transient", "MailboxFull"))));

        assertThat(statusOf(m.id())).isEqualTo("BOUNCED");
        assertThat(jdbc.queryForObject("select count(*) from soft_bounce where address = ?", Integer.class, m.recipient()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from suppression where address = ?", Integer.class, m.recipient()))
                .isZero();
    }

    @Test
    void mixedCaseAddressInEventIsSuppressedLowerCased() throws Exception {
        Msg m = insertMessage("SENT");

        processor.process(sns(UUID.randomUUID().toString(), sesEvent("Complaint", m, true,
                complaint(m.recipient().toUpperCase()))));

        assertThat(jdbc.queryForObject("select count(*) from suppression where address = ?", Integer.class, m.recipient()))
                .isEqualTo(1);
    }

    @Test
    void unreadableBodyIsRejected() {
        assertThatThrownBy(() -> processor.process("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.process("{\"hello\":\"world\"}")).isInstanceOf(IllegalArgumentException.class);
    }
}

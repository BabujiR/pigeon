package com.pigeon.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigeon.message.MessageEvent;
import com.pigeon.message.MessageEventRepository;
import com.pigeon.message.MessageRecord;
import com.pigeon.message.MessageRecordRepository;
import com.pigeon.message.MessageStatus;
import com.pigeon.suppression.SuppressionReason;
import com.pigeon.suppression.SuppressionService;
import com.pigeon.support.Pii;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns SES events into message status, history and suppression.
 *
 * <p>Accepts the SNS envelope that SQS receives ({@code {"Type":"Notification","MessageId":...,
 * "Message":"<SES event JSON>"}}) or a raw SES event (raw message delivery). Understands both SES
 * event publishing ({@code eventType}) and the older identity notifications ({@code notificationType}).
 *
 * <p>Review notes:
 * <ul>
 *   <li><b>Exactly-once effect on at-least-once delivery.</b> The SNS MessageId is inserted into
 *       processed_event in the same transaction as the changes. A redelivered event finds the key
 *       and does nothing; a failed one rolls the key back and is retried.</li>
 *   <li><b>Out of order is normal.</b> A Complaint can arrive before its Delivery. Status only
 *       moves forward (see {@link MessageRecord#applyOutcome}).</li>
 *   <li><b>Too early.</b> If the event beats our own SENT commit, we throw so SQS redelivers it
 *       a little later instead of losing it.</li>
 *   <li><b>Suppression is per address</b>, applied even when the email cannot be matched to a
 *       Pigeon record.</li>
 * </ul>
 */
@Service
public class SesEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(SesEventProcessor.class);

    public enum Result { APPLIED, DUPLICATE, IGNORED }

    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final MessageRecordRepository messages;
    private final MessageEventRepository events;
    private final SuppressionService suppression;
    private final EventProperties props;
    private final TransactionTemplate tx;

    public SesEventProcessor(ObjectMapper json,
                             JdbcTemplate jdbc,
                             MessageRecordRepository messages,
                             MessageEventRepository events,
                             SuppressionService suppression,
                             EventProperties props,
                             PlatformTransactionManager transactionManager) {
        this.json = json;
        this.jdbc = jdbc;
        this.messages = messages;
        this.events = events;
        this.suppression = suppression;
        this.props = props;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Result process(String body) {
        Parsed parsed = parse(body);
        return tx.execute(status -> apply(parsed));
    }

    // --- Parsing ----------------------------------------------------------------------------

    private record Parsed(String eventKey, String type, JsonNode event) {
    }

    private Parsed parse(String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode event = root;
            String key = null;
            if ("Notification".equals(root.path("Type").asText())) {
                key = "sns:" + root.path("MessageId").asText();
                event = json.readTree(root.path("Message").asText());
            }
            String type = event.hasNonNull("eventType")
                    ? event.path("eventType").asText()
                    : event.path("notificationType").asText();
            if (key == null) {
                // Raw delivery has no SNS id: derive a stable one from the event itself.
                key = "raw:" + event.path("mail").path("messageId").asText() + ":" + type + ":" + eventTime(event, type);
            }
            if (type.isBlank()) {
                throw new IllegalArgumentException("Not an SES event: no eventType or notificationType");
            }
            return new Parsed(truncate(key, 200), type, event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unreadable SES event (will go to the DLQ after 3 tries)", e);
        }
    }

    // --- Applying ---------------------------------------------------------------------------

    private Result apply(Parsed p) {
        int firstTime = jdbc.update(
                "insert into processed_event (event_key, event_type) values (?, ?) on conflict do nothing",
                p.eventKey(), truncate(p.type(), 40));
        if (firstTime == 0) {
            log.debug("Duplicate SES event {} ignored", p.eventKey());
            return Result.DUPLICATE;
        }

        JsonNode mail = p.event().path("mail");
        String sesMessageId = mail.path("messageId").asText(null);
        Optional<MessageRecord> found = findMessage(mail, sesMessageId);

        if (found.isPresent() && found.get().getStatus() == MessageStatus.QUEUED) {
            throw new EventNotReadyException(found.get().getId(), p.type());
        }
        if (found.isEmpty()) {
            log.info("SES {} event for unknown message {}: applying suppression only", p.type(), sesMessageId);
        }

        Instant at = eventTime(p.event(), p.type());
        return switch (p.type()) {
            case "Delivery" -> delivery(p.event(), found, at);
            case "Bounce" -> bounce(p.event(), found, sesMessageId, at);
            case "Complaint" -> complaint(p.event(), found, sesMessageId, at);
            case "Reject", "Rendering Failure" -> reject(p.event(), p.type(), found, at);
            case "DeliveryDelay" -> historyOnly(found, "DELIVERY_DELAY",
                    Map.of("delayType", p.event().path("deliveryDelay").path("delayType").asText("")), at);
            case "Open" -> historyOnly(found, "OPEN", Map.of(), at);
            case "Click" -> historyOnly(found, "CLICK",
                    Map.of("link", p.event().path("click").path("link").asText("")), at);
            default -> Result.IGNORED;   // Send, Subscription: nothing to record
        };
    }

    private Optional<MessageRecord> findMessage(JsonNode mail, String sesMessageId) {
        String tagged = mail.path("tags").path("messageRecordId").path(0).asText(null);
        if (tagged != null) {
            try {
                Optional<MessageRecord> byTag = messages.findForUpdate(UUID.fromString(tagged));
                if (byTag.isPresent()) {
                    return byTag;
                }
            } catch (IllegalArgumentException ignored) {
                // not one of our ids
            }
        }
        return sesMessageId == null ? Optional.empty() : messages.findBySesMessageId(sesMessageId);
    }

    private Result delivery(JsonNode event, Optional<MessageRecord> found, Instant at) {
        JsonNode d = event.path("delivery");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("smtpResponse", d.path("smtpResponse").asText(""));
        detail.put("processingTimeMillis", d.path("processingTimeMillis").asLong(0));
        detail.put("reportingMTA", d.path("reportingMTA").asText(""));
        found.ifPresent(m -> {
            m.applyOutcome(MessageStatus.DELIVERED, at);
            events.save(MessageEvent.of(m.getId(), "DELIVERY", detail, at));
        });
        return Result.APPLIED;
    }

    private Result bounce(JsonNode event, Optional<MessageRecord> found, String sesMessageId, Instant at) {
        JsonNode b = event.path("bounce");
        String bounceType = b.path("bounceType").asText("Undetermined");
        String subType = b.path("bounceSubType").asText("General");
        boolean permanent = "Permanent".equals(bounceType);

        for (JsonNode r : b.path("bouncedRecipients")) {
            String address = r.path("emailAddress").asText();
            String diagnostic = r.path("diagnosticCode").asText(r.path("status").asText(""));
            if (permanent) {
                SuppressionReason reason = "OnAccountSuppressionList".equals(subType)
                        ? SuppressionReason.ACCOUNT_SUPPRESSION_LIST
                        : SuppressionReason.HARD_BOUNCE;
                if (suppression.suppress(address, reason, "ses-event:" + sesMessageId, diagnostic)) {
                    log.info("Suppressed {} ({})", Pii.maskEmail(address), reason);
                }
            } else {
                // Transient or Undetermined: SES already retried for hours. Count it.
                int recent = suppression.recordSoftBounce(address, sesMessageId, subType, at, props.softBounceWindow());
                if (recent >= props.softBounceThreshold()
                        && suppression.suppress(address, SuppressionReason.REPEATED_SOFT_BOUNCE,
                                "ses-event:" + sesMessageId,
                                recent + " transient bounces within " + props.softBounceWindow().toDays() + " days")) {
                    log.info("Suppressed {} after {} soft bounces", Pii.maskEmail(address), recent);
                }
            }
        }

        found.ifPresent(m -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("bounceType", bounceType);
            detail.put("bounceSubType", subType);
            JsonNode first = b.path("bouncedRecipients").path(0);
            detail.put("status", first.path("status").asText(""));
            detail.put("diagnosticCode", first.path("diagnosticCode").asText(""));
            m.applyOutcome(MessageStatus.BOUNCED, at);
            events.save(MessageEvent.of(m.getId(), "BOUNCE", detail, at));
        });
        return Result.APPLIED;
    }

    private Result complaint(JsonNode event, Optional<MessageRecord> found, String sesMessageId, Instant at) {
        JsonNode c = event.path("complaint");
        String feedbackType = c.path("complaintFeedbackType").asText("");
        for (JsonNode r : c.path("complainedRecipients")) {
            String address = r.path("emailAddress").asText();
            if (suppression.suppress(address, SuppressionReason.COMPLAINT, "ses-event:" + sesMessageId,
                    feedbackType.isEmpty() ? "complaint" : "complaint: " + feedbackType)) {
                log.info("Suppressed {} (complaint)", Pii.maskEmail(address));
            }
        }
        found.ifPresent(m -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("complaintFeedbackType", feedbackType);
            detail.put("userAgent", c.path("userAgent").asText(""));
            m.applyOutcome(MessageStatus.COMPLAINED, at);
            events.save(MessageEvent.of(m.getId(), "COMPLAINT", detail, at));
        });
        return Result.APPLIED;
    }

    private Result reject(JsonNode event, String type, Optional<MessageRecord> found, Instant at) {
        String reason = "Reject".equals(type)
                ? event.path("reject").path("reason").asText("rejected")
                : event.path("failure").path("errorMessage").asText("rendering failure");
        found.ifPresent(m -> {
            m.markRejectedBySes(type + ": " + reason, at);
            events.save(MessageEvent.of(m.getId(), "REJECT", Map.<String, Object>of("type", type, "reason", reason), at));
        });
        return Result.APPLIED;
    }

    private Result historyOnly(Optional<MessageRecord> found, String eventType, Map<String, Object> detail, Instant at) {
        found.ifPresent(m -> events.save(MessageEvent.of(m.getId(), eventType, detail, at)));
        return found.isPresent() ? Result.APPLIED : Result.IGNORED;
    }

    // --- Helpers ----------------------------------------------------------------------------

    /** The time the event happened, from its own section; falls back to the mail timestamp, then now. */
    private static Instant eventTime(JsonNode event, String type) {
        String section = switch (type) {
            case "Delivery" -> "delivery";
            case "Bounce" -> "bounce";
            case "Complaint" -> "complaint";
            case "DeliveryDelay" -> "deliveryDelay";
            case "Open" -> "open";
            case "Click" -> "click";
            default -> "mail";
        };
        for (String text : new String[] {event.path(section).path("timestamp").asText(null),
                event.path("mail").path("timestamp").asText(null)}) {
            if (text != null) {
                try {
                    return Instant.parse(text);
                } catch (DateTimeParseException ignored) {
                    // try the next one
                }
            }
        }
        return Instant.now();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

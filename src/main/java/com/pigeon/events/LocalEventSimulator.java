package com.pigeon.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigeon.config.PigeonAwsProperties;
import com.pigeon.sender.EmailSentEvent;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.model.MessageTag;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Local stand-in for SES event publishing (enabled in the local profile only).
 *
 * <p>On AWS, the configuration set publishes Delivery, Bounce and Complaint events to the SNS
 * topic. LocalStack's free edition does not, so after each send this class publishes events in
 * the real SES event JSON format to pigeon-events-topic, chosen by recipient the way the SES
 * mailbox simulator behaves:
 *
 * <pre>
 *   success@simulator.amazonses.com          Delivery
 *   bounce@simulator.amazonses.com           Bounce, Permanent, 550 5.1.1
 *   complaint@simulator.amazonses.com        Delivery, then Complaint
 *   suppressionlist@simulator.amazonses.com  Bounce, Permanent, OnAccountSuppressionList
 *   ooto@simulator.amazonses.com             Delivery (the auto-reply is not simulated)
 *   any address containing "mailboxfull"     Bounce, Transient, MailboxFull, 4.2.2
 *   any other address                        Delivery
 * </pre>
 *
 * Labels work as on SES: {@code bounce+test1@simulator.amazonses.com} bounces.
 * The event processor cannot tell these events from real ones, which is the point.
 */
@Component
@ConditionalOnProperty(prefix = "pigeon.events.simulator", name = "enabled", havingValue = "true")
public class LocalEventSimulator {

    private static final Logger log = LoggerFactory.getLogger(LocalEventSimulator.class);
    private static final String SIMULATOR_DOMAIN = "simulator.amazonses.com";

    enum Scenario { DELIVERY, HARD_BOUNCE, COMPLAINT, ACCOUNT_SUPPRESSED, SOFT_BOUNCE }

    private final SnsClient sns;
    private final ObjectMapper json;
    private final PigeonAwsProperties aws;
    private final EventProperties props;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ses-event-simulator");
        t.setDaemon(true);
        return t;
    });
    private volatile String topicArn;

    public LocalEventSimulator(SnsClient sns, ObjectMapper json, PigeonAwsProperties aws, EventProperties props) {
        this.sns = sns;
        this.json = json;
        this.aws = aws;
        this.props = props;
    }

    @EventListener
    public void onEmailSent(EmailSentEvent sent) {
        long delay = props.simulator().delay().toMillis();
        for (String recipient : sent.request().destination().toAddresses()) {
            Scenario scenario = scenarioFor(recipient);
            List<Map<String, Object>> events = eventsFor(scenario, sent, recipient);
            for (int i = 0; i < events.size(); i++) {
                Map<String, Object> event = events.get(i);
                executor.schedule(() -> publish(event), delay * (i + 1), TimeUnit.MILLISECONDS);
            }
            log.info("Simulating {} for message {}", scenario, sent.messageId());
        }
    }

    static Scenario scenarioFor(String recipient) {
        String address = recipient.toLowerCase(Locale.ROOT);
        int at = address.lastIndexOf('@');
        String local = at < 0 ? address : address.substring(0, at);
        String domain = at < 0 ? "" : address.substring(at + 1);
        if (address.contains("mailboxfull")) {
            return Scenario.SOFT_BOUNCE;
        }
        if (!domain.equals(SIMULATOR_DOMAIN)) {
            return Scenario.DELIVERY;
        }
        String base = local.contains("+") ? local.substring(0, local.indexOf('+')) : local;
        return switch (base) {
            case "bounce" -> Scenario.HARD_BOUNCE;
            case "complaint" -> Scenario.COMPLAINT;
            case "suppressionlist" -> Scenario.ACCOUNT_SUPPRESSED;
            default -> Scenario.DELIVERY;   // success, ooto, anything else
        };
    }

    // --- SES event JSON (same shape as SES event publishing) --------------------------------

    List<Map<String, Object>> eventsFor(Scenario scenario, EmailSentEvent sent, String recipient) {
        List<Map<String, Object>> out = new ArrayList<>();
        switch (scenario) {
            case DELIVERY -> out.add(delivery(sent, recipient));
            case COMPLAINT -> {
                out.add(delivery(sent, recipient));
                out.add(complaint(sent, recipient));
            }
            case HARD_BOUNCE -> out.add(bounce(sent, recipient, "Permanent", "General", "5.1.1",
                    "smtp; 550 5.1.1 user unknown"));
            case ACCOUNT_SUPPRESSED -> out.add(bounce(sent, recipient, "Permanent", "OnAccountSuppressionList", "5.1.1",
                    "Amazon SES did not send the message to this address because it is on the suppression list for your account."));
            case SOFT_BOUNCE -> out.add(bounce(sent, recipient, "Transient", "MailboxFull", "4.2.2",
                    "smtp; 452 4.2.2 Mailbox full"));
        }
        return out;
    }

    private Map<String, Object> mail(EmailSentEvent sent, String recipient) {
        SendEmailRequest req = sent.request();
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("ses:configuration-set", List.of(req.configurationSetName()));
        for (MessageTag tag : req.emailTags()) {
            tags.put(tag.name(), List.of(tag.value()));
        }
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("timestamp", sent.sentAt().toString());
        mail.put("source", req.fromEmailAddress());
        mail.put("sendingAccountId", "000000000000");
        mail.put("messageId", sent.sesMessageId());
        mail.put("destination", List.of(recipient));
        mail.put("headersTruncated", false);
        mail.put("tags", tags);
        return mail;
    }

    private Map<String, Object> delivery(EmailSentEvent sent, String recipient) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("timestamp", Instant.now().toString());
        delivery.put("processingTimeMillis", 742);
        delivery.put("recipients", List.of(recipient));
        delivery.put("smtpResponse", "250 2.6.0 Message received");
        delivery.put("reportingMTA", "a8-31.smtp-out.amazonses.com");
        return envelope("Delivery", sent, recipient, "delivery", delivery);
    }

    private Map<String, Object> bounce(EmailSentEvent sent, String recipient, String type, String subType,
                                       String status, String diagnostic) {
        Map<String, Object> bounced = new LinkedHashMap<>();
        bounced.put("emailAddress", recipient);
        bounced.put("action", "failed");
        bounced.put("status", status);
        bounced.put("diagnosticCode", diagnostic);
        Map<String, Object> bounce = new LinkedHashMap<>();
        bounce.put("bounceType", type);
        bounce.put("bounceSubType", subType);
        bounce.put("bouncedRecipients", List.of(bounced));
        bounce.put("timestamp", Instant.now().toString());
        bounce.put("feedbackId", "local-" + sent.sesMessageId() + "-bounce");
        bounce.put("reportingMTA", "dsn; a8-31.smtp-out.amazonses.com");
        return envelope("Bounce", sent, recipient, "bounce", bounce);
    }

    private Map<String, Object> complaint(EmailSentEvent sent, String recipient) {
        Map<String, Object> complaint = new LinkedHashMap<>();
        complaint.put("complainedRecipients", List.of(Map.of("emailAddress", recipient)));
        complaint.put("timestamp", Instant.now().toString());
        complaint.put("feedbackId", "local-" + sent.sesMessageId() + "-complaint");
        complaint.put("complaintFeedbackType", "abuse");
        complaint.put("userAgent", "Pigeon local simulator");
        return envelope("Complaint", sent, recipient, "complaint", complaint);
    }

    private Map<String, Object> envelope(String eventType, EmailSentEvent sent, String recipient,
                                         String section, Map<String, Object> body) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", eventType);
        event.put("mail", mail(sent, recipient));
        event.put(section, body);
        return event;
    }

    // --- Publishing -------------------------------------------------------------------------

    private void publish(Map<String, Object> event) {
        try {
            String message = json.writeValueAsString(event);
            sns.publish(b -> b.topicArn(topicArn()).message(message));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Simulator could not publish {} event: {}", event.get("eventType"), e.getMessage());
        }
    }

    private String topicArn() {
        if (topicArn == null) {
            // CreateTopic is idempotent and returns the ARN of the existing topic.
            topicArn = sns.createTopic(b -> b.name(aws.eventsTopic())).topicArn();
        }
        return topicArn;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}

package com.pigeon.sender;

import com.pigeon.message.MessageEvent;
import com.pigeon.message.MessageEventRepository;
import com.pigeon.message.MessageRecord;
import com.pigeon.message.MessageRecordRepository;
import com.pigeon.message.MessageStatus;
import com.pigeon.suppression.Suppression;
import com.pigeon.suppression.SuppressionService;
import com.pigeon.support.Pii;
import com.pigeon.template.MissingMergeFieldsException;
import com.pigeon.template.RenderedEmail;
import com.pigeon.template.TemplateNotFoundException;
import com.pigeon.template.TemplateService;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Sends one queued message through SES.
 *
 * <p>Three short steps, so no database transaction or row lock is held while SES is called:
 * <ol>
 *   <li><b>Claim</b> (transaction, row locked): skip if the message is no longer QUEUED, back
 *       off if another worker holds the lease, suppress if the address was suppressed after the
 *       request was accepted, otherwise take the lease and render the recorded template version.</li>
 *   <li><b>Send</b> (no transaction): wait for the rate limiter, call SES.</li>
 *   <li><b>Record</b> (transaction): SENT with the SES message id, or the failure.</li>
 * </ol>
 *
 * <p>Review note on delivery guarantees: SES SendEmail has no idempotency token. If the process
 * dies after SES accepted the email but before step 3 commits, the lease expires and the
 * message is sent again. We accept that narrow at-least-once window: for receipts and
 * enrollment confirmations a rare duplicate is far better than a lost email.
 */
@Service
public class SendService {

    private static final Logger log = LoggerFactory.getLogger(SendService.class);

    private final MessageRecordRepository messages;
    private final MessageEventRepository events;
    private final SuppressionService suppression;
    private final TemplateService templates;
    private final SesEmailFactory emailFactory;
    private final EmailGateway gateway;
    private final SenderProperties props;
    private final RateLimiter rateLimiter;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher publisher;
    private final Clock clock = Clock.systemUTC();

    public SendService(MessageRecordRepository messages,
                       MessageEventRepository events,
                       SuppressionService suppression,
                       TemplateService templates,
                       SesEmailFactory emailFactory,
                       EmailGateway gateway,
                       SenderProperties props,
                       RateLimiterRegistry rateLimiters,
                       PlatformTransactionManager transactionManager,
                       ApplicationEventPublisher publisher) {
        this.messages = messages;
        this.events = events;
        this.suppression = suppression;
        this.templates = templates;
        this.emailFactory = emailFactory;
        this.gateway = gateway;
        this.props = props;
        this.rateLimiter = rateLimiters.rateLimiter("ses", RateLimiterConfig.custom()
                .limitForPeriod(props.ratePerSecond())
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(30))
                .build());
        this.tx = new TransactionTemplate(transactionManager);
        this.publisher = publisher;
    }

    public SendOutcome process(SendCommand command) {
        Claim claim = tx.execute(status -> claim(command));
        if (claim.outcome() != null) {
            return claim.outcome();
        }

        String sesMessageId;
        try {
            RateLimiter.waitForPermission(rateLimiter);
            sesMessageId = gateway.send(claim.request());
        } catch (RuntimeException e) {
            return handleFailure(command, claim, e);
        }

        // Outside the try on purpose: if recording fails, SES has already accepted the email, so
        // this must not be treated as an SES failure. The exception reaches the listener, SQS
        // redelivers after the lease expires, and that is the documented at-least-once window.
        tx.executeWithoutResult(status -> recordSent(command, sesMessageId, claim.attempt()));
        log.info("Sent message {} to {} via {} (attempt {}, SES id {})", command.messageId(),
                Pii.maskEmail(claim.recipient()), gateway.name(), claim.attempt(), sesMessageId);
        // After the commit: lets the local event simulator (Phase 4) react as SES would.
        publisher.publishEvent(new EmailSentEvent(command.messageId(), sesMessageId, claim.request(), clock.instant()));
        return SendOutcome.sent(sesMessageId);
    }

    // --- Step 1: claim ----------------------------------------------------------------------

    private Claim claim(SendCommand command) {
        Instant now = clock.instant();
        MessageRecord message = messages.findForUpdate(command.messageId())
                .orElseThrow(() -> new UnknownMessageException(command.messageId()));

        if (message.getStatus() != MessageStatus.QUEUED) {
            // Duplicate SQS delivery after a successful send, or suppressed/failed meanwhile.
            return Claim.done(SendOutcome.skipped("status is " + message.getStatus()));
        }
        if (message.isLeasedAt(now)) {
            return Claim.done(SendOutcome.retry("another worker holds the lease", props.lease()));
        }

        Optional<Suppression> suppressed = suppression.find(message.getRecipient());
        if (suppressed.isPresent()) {
            message.markSuppressed(now);
            events.save(MessageEvent.of(message.getId(), "SUPPRESSED", Map.<String, Object>of(
                    "reason", suppressed.get().getReason().name(), "stage", "send"), now));
            return Claim.done(SendOutcome.skipped("recipient suppressed after accept"));
        }

        message.claimForSend(now, props.lease());
        RenderedEmail email;
        try {
            // Always the version recorded at accept time, not whatever is active now.
            email = templates.render(message.getTemplateId(), message.getTemplateVersion(), message.getVariables());
        } catch (MissingMergeFieldsException | TemplateNotFoundException e) {
            message.markFailed("Render failed: " + e.getMessage(), now);
            events.save(MessageEvent.of(message.getId(), "FAILED", Map.<String, Object>of(
                    "stage", "render", "error", e.getMessage()), now));
            return Claim.done(SendOutcome.failed("render failed"));
        }

        SendEmailRequest request = emailFactory.build(message, email);
        return Claim.ready(request, message.getAttemptCount(), message.getRecipient());
    }

    // --- Step 3: record ---------------------------------------------------------------------

    private void recordSent(SendCommand command, String sesMessageId, int attempt) {
        Instant now = clock.instant();
        MessageRecord message = messages.findForUpdate(command.messageId())
                .orElseThrow(() -> new UnknownMessageException(command.messageId()));
        if (message.getStatus() != MessageStatus.QUEUED) {
            log.warn("Message {} changed to {} while sending; SES id {} recorded in history only",
                    message.getId(), message.getStatus(), sesMessageId);
        } else {
            message.markSent(sesMessageId, now);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sesMessageId", sesMessageId);
        detail.put("configurationSet", message.getConfigurationSet());
        detail.put("api", gateway.name());
        detail.put("attempt", attempt);
        events.save(MessageEvent.of(message.getId(), "SENT", detail, now));
    }

    private SendOutcome handleFailure(SendCommand command, Claim claim, RuntimeException error) {
        SesErrorClassifier.Decision decision = SesErrorClassifier.classify(error);
        String description = SesErrorClassifier.describe(error);
        boolean giveUp = decision == SesErrorClassifier.Decision.PERMANENT || claim.attempt() >= props.maxAttempts();

        tx.executeWithoutResult(status -> {
            Instant now = clock.instant();
            MessageRecord message = messages.findForUpdate(command.messageId())
                    .orElseThrow(() -> new UnknownMessageException(command.messageId()));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("stage", "ses");
            detail.put("attempt", claim.attempt());
            detail.put("error", description);
            if (giveUp && message.getStatus() == MessageStatus.QUEUED) {
                detail.put("decision", decision == SesErrorClassifier.Decision.PERMANENT ? "permanent" : "retries exhausted");
                message.markFailed(description, now);
                events.save(MessageEvent.of(message.getId(), "FAILED", detail, now));
            } else if (!giveUp) {
                message.recordSendFailure(description, now);
                events.save(MessageEvent.of(message.getId(), "SEND_RETRY", detail, now));
            }
        });

        if (giveUp) {
            log.error("Message {} FAILED after attempt {}: {}", command.messageId(), claim.attempt(), description);
            return SendOutcome.failed(description);
        }
        Duration delay = props.backoffFor(claim.attempt());
        log.warn("Message {} attempt {} failed, retrying in {}s: {}", command.messageId(), claim.attempt(),
                delay.toSeconds(), description);
        return SendOutcome.retry(description, delay);
    }

    /** Result of step 1: either a final outcome, or a request ready to send. */
    private record Claim(SendOutcome outcome, SendEmailRequest request, int attempt, String recipient) {

        static Claim done(SendOutcome outcome) {
            return new Claim(outcome, null, 0, null);
        }

        static Claim ready(SendEmailRequest request, int attempt, String recipient) {
            return new Claim(null, request, attempt, recipient);
        }
    }
}

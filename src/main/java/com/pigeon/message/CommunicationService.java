package com.pigeon.message;

import com.pigeon.config.PigeonAwsProperties;
import com.pigeon.suppression.Suppression;
import com.pigeon.suppression.SuppressionService;
import com.pigeon.support.Pii;
import com.pigeon.template.TemplateRenderer;
import com.pigeon.template.TemplateService;
import com.pigeon.template.TemplateVersion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts a communication request: dedupe, validate, check suppression, record, enqueue.
 *
 * <p>Review notes:
 * <ul>
 *   <li><b>One transaction</b> writes the message, its history, the outbox row and the
 *       idempotency key. Either all exist or none do.</li>
 *   <li><b>Concurrent retries.</b> Two requests with the same key can both pass the "seen
 *       before?" check. The idempotency key's primary key makes the second insert fail; we roll
 *       that transaction back and answer with the first request's result. No locks, no
 *       duplicate email.</li>
 *   <li><b>Validation uses the renderer itself</b>, so the API can never accept a request the
 *       worker would fail to render.</li>
 *   <li>The queue payload carries ids only. Merge values (PII) stay in the database.</li>
 * </ul>
 */
@Service
public class CommunicationService {

    public static final int MAX_KEY_LENGTH = 200;
    private static final Logger log = LoggerFactory.getLogger(CommunicationService.class);

    private final MessageRecordRepository messages;
    private final MessageEventRepository events;
    private final OutboxRepository outbox;
    private final IdempotencyRepository idempotency;
    private final TemplateService templates;
    private final TemplateRenderer renderer;
    private final SuppressionService suppression;
    private final PigeonAwsProperties aws;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    private final Duration keyTtl;
    private final Clock clock;

    public CommunicationService(MessageRecordRepository messages,
                                MessageEventRepository events,
                                OutboxRepository outbox,
                                IdempotencyRepository idempotency,
                                TemplateService templates,
                                TemplateRenderer renderer,
                                SuppressionService suppression,
                                PigeonAwsProperties aws,
                                PlatformTransactionManager transactionManager,
                                @Value("${pigeon.idempotency.ttl:24h}") Duration keyTtl) {
        this.messages = messages;
        this.events = events;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.templates = templates;
        this.renderer = renderer;
        this.suppression = suppression;
        this.aws = aws;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.keyTtl = keyTtl;
        this.clock = Clock.systemUTC();
    }

    public AcceptResult accept(String idempotencyKey, String correlationId, String templateId,
                               String recipient, MessageStream stream, Map<String, String> variables) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(MAX_KEY_LENGTH);
        }
        String fingerprint = RequestFingerprint.of(templateId, recipient, stream, variables);
        AcceptResult result;
        try {
            result = tx.execute(status -> acceptInTransaction(
                    idempotencyKey, fingerprint, correlationId, templateId, recipient, stream, variables));
        } catch (DataIntegrityViolationException raceLost) {
            // Another request with the same key committed first. Answer with its result.
            result = readOnlyTx.execute(status -> replay(idempotencyKey, fingerprint))
                    .orElseThrow(() -> raceLost);
        }
        MessageRecord m = result.message();
        log.info("Accepted message {} for {}: {} ({} v{}, {}){}", m.getId(), Pii.maskEmail(m.getRecipient()),
                m.getStatus(), m.getTemplateId(), m.getTemplateVersion(), m.getStream(),
                result.replayed() ? " [idempotent replay]" : "");
        return result;
    }

    private AcceptResult acceptInTransaction(String key, String fingerprint, String correlationId,
                                             String templateId, String recipient, MessageStream stream,
                                             Map<String, String> variables) {
        Instant now = clock.instant();

        Optional<IdempotencyRecord> seen = idempotency.findById(key);
        if (seen.isPresent()) {
            if (!seen.get().isExpired(now)) {
                return replayOf(seen.get(), key, fingerprint);
            }
            idempotency.delete(seen.get());
            idempotency.flush();
        }

        TemplateVersion version = templates.activeVersion(templateId);   // unknown template -> 400
        renderer.render(version.toContent(), variables);                   // missing fields -> 400

        String normalizedRecipient = SuppressionService.normalize(recipient);
        MessageRecord message = MessageRecord.accept(UUID.randomUUID(), correlationId, templateId,
                version.getVersion(), normalizedRecipient, stream, variables, configurationSetFor(stream), now);
        messages.saveAndFlush(message);
        events.save(MessageEvent.of(message.getId(), "ACCEPTED",
                Map.<String, Object>of("templateVersion", version.getVersion(), "stream", stream.name()), now));

        String suppressionReason = null;
        Optional<Suppression> suppressed = suppression.find(normalizedRecipient);
        if (suppressed.isPresent()) {
            suppressionReason = suppressed.get().getReason().name();
            message.markSuppressed(now);
            events.save(MessageEvent.of(message.getId(), "SUPPRESSED",
                    Map.<String, Object>of("reason", suppressionReason, "since", suppressed.get().getCreatedAt().toString()), now));
        } else {
            String queue = aws.queues().send();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageId", message.getId().toString());
            payload.put("correlationId", correlationId);
            outbox.save(OutboxEntry.pending(message.getId(), queue, payload, now));
            message.markQueued(now);
            events.save(MessageEvent.of(message.getId(), "QUEUED", Map.<String, Object>of("destination", queue), now));
        }

        // Flush here so a duplicate key fails inside this transaction and rolls everything back.
        idempotency.saveAndFlush(new IdempotencyRecord(key, fingerprint, message.getId(), now, now.plus(keyTtl)));
        return new AcceptResult(message, false, suppressionReason);
    }

    private Optional<AcceptResult> replay(String key, String fingerprint) {
        return idempotency.findById(key).map(record -> replayOf(record, key, fingerprint));
    }

    private AcceptResult replayOf(IdempotencyRecord record, String key, String fingerprint) {
        if (!record.matches(fingerprint)) {
            throw new IdempotencyConflictException(key);
        }
        MessageRecord original = messages.findById(record.getMessageId())
                .orElseThrow(() -> new MessageNotFoundException(record.getMessageId()));
        return new AcceptResult(original, true, null);
    }

    private String configurationSetFor(MessageStream stream) {
        return stream == MessageStream.BULK
                ? aws.configurationSets().bulk()
                : aws.configurationSets().transactional();
    }

    /** Current state and full history of one message. */
    public MessageView status(UUID id) {
        return readOnlyTx.execute(s -> {
            MessageRecord m = messages.findById(id).orElseThrow(() -> new MessageNotFoundException(id));
            List<MessageEvent> history = events.findByMessageIdOrderByOccurredAtAscIdAsc(id);
            return new MessageView(m, history);
        });
    }

    public record AcceptResult(MessageRecord message, boolean replayed, String suppressionReason) {
    }

    public record MessageView(MessageRecord message, List<MessageEvent> events) {
    }
}

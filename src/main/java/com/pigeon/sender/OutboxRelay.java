package com.pigeon.sender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigeon.message.MessageEvent;
import com.pigeon.message.MessageEventRepository;
import com.pigeon.message.MessageRecordRepository;
import com.pigeon.message.MessageStatus;
import com.pigeon.message.OutboxEntry;
import com.pigeon.message.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Moves PENDING outbox rows onto their SQS queue.
 *
 * <p>Review notes:
 * <ul>
 *   <li><b>At least once.</b> A row is marked PUBLISHED in the same transaction that locked it,
 *       after SQS accepted the message. If the commit fails, the row is published again on the
 *       next poll; the worker's status check and lease make that duplicate harmless.</li>
 *   <li><b>Several instances</b> can run the relay: {@code FOR UPDATE SKIP LOCKED} hands each one
 *       different rows.</li>
 *   <li><b>Backoff when SQS is down.</b> After a failure the relay pauses (doubling up to
 *       {@code maxBackoff}) instead of hammering SQS every 500 ms and burning through attempts.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "pigeon.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final MessageRecordRepository messages;
    private final MessageEventRepository events;
    private final SqsAsyncClient sqs;
    private final ObjectMapper json;
    private final OutboxRelayProperties props;
    private final TransactionTemplate tx;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, String> queueUrls = new ConcurrentHashMap<>();

    private int consecutiveFailures;
    private Instant pausedUntil = Instant.EPOCH;

    public OutboxRelay(OutboxRepository outbox,
                       MessageRecordRepository messages,
                       MessageEventRepository events,
                       SqsAsyncClient sqs,
                       ObjectMapper json,
                       OutboxRelayProperties props,
                       PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.messages = messages;
        this.events = events;
        this.sqs = sqs;
        this.json = json;
        this.props = props;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${pigeon.outbox.relay.poll-interval-ms:500}")
    public void relay() {
        if (clock.instant().isBefore(pausedUntil)) {
            return;
        }
        BatchResult result;
        do {
            result = tx.execute(status -> publishBatch());
        } while (result != null && result.failed() == 0 && result.published() == props.batchSize());

        if (result != null && result.failed() > 0) {
            consecutiveFailures++;
            long pauseMs = Math.min(props.maxBackoff().toMillis(), props.pollIntervalMs() * (1L << Math.min(consecutiveFailures, 10)));
            pausedUntil = clock.instant().plusMillis(pauseMs);
            log.warn("Outbox relay: {} publish failure(s); pausing {} ms", result.failed(), pauseMs);
        } else {
            consecutiveFailures = 0;
        }
    }

    private BatchResult publishBatch() {
        List<OutboxEntry> batch = outbox.lockPendingBatch(props.batchSize());
        Instant now = clock.instant();
        int published = 0;
        int failed = 0;
        for (OutboxEntry entry : batch) {
            try {
                publish(entry);
                entry.markPublished(now);
                published++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failed++;
                String error = SesErrorClassifier.describe(e);
                entry.recordFailure(error, props.maxAttempts());
                if (entry.getStatus() == OutboxEntry.Status.FAILED) {
                    giveUp(entry, error, now);
                }
                // Stop this batch: if SQS is unreachable the rest would fail the same way.
                break;
            }
        }
        return new BatchResult(published, failed);
    }

    private void publish(OutboxEntry entry) throws Exception {
        String body = json.writeValueAsString(entry.getPayload());
        Object correlationId = entry.getPayload().get("correlationId");
        sqs.sendMessage(b -> b
                        .queueUrl(queueUrl(entry.getDestination()))
                        .messageBody(body)
                        .messageAttributes(Map.of("correlationId", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(correlationId == null ? "none" : correlationId.toString())
                                .build())))
                .get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private String queueUrl(String queueName) {
        return queueUrls.computeIfAbsent(queueName,
                name -> sqs.getQueueUrl(b -> b.queueName(name)).join().queueUrl());
    }

    private void giveUp(OutboxEntry entry, String error, Instant now) {
        log.error("Outbox row {} for message {} FAILED after {} attempts: {}",
                entry.getId(), entry.getMessageId(), entry.getAttempts(), error);
        messages.findForUpdate(entry.getMessageId()).ifPresent(message -> {
            if (message.getStatus() == MessageStatus.QUEUED) {
                message.markFailed("Could not publish to " + entry.getDestination() + ": " + error, now);
                events.save(MessageEvent.of(message.getId(), "FAILED", Map.<String, Object>of(
                        "stage", "outbox", "attempts", entry.getAttempts(), "error", error), now));
            }
        });
    }

    private record BatchResult(int published, int failed) {
    }
}

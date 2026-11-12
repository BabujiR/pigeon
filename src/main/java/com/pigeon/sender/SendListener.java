package com.pigeon.sender;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes pigeon-send-queue.
 *
 * <p>Acknowledgement is ON_SUCCESS: returning normally deletes the SQS message; throwing leaves
 * it on the queue. So the listener returns for SENT, SKIPPED and FAILED (the database now holds
 * the final answer) and throws only for RETRY, after pushing the message's visibility out by
 * the backoff delay. Messages that can never be processed (unparseable body, unknown id) keep
 * throwing until SQS moves them to pigeon-send-dlq after maxReceiveCount (3).
 */
@Component
@ConditionalOnProperty(prefix = "pigeon.sender", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SendListener {

    private static final Logger log = LoggerFactory.getLogger(SendListener.class);

    private final SendService sendService;
    private final ObjectMapper json;

    public SendListener(SendService sendService, ObjectMapper json) {
        this.sendService = sendService;
        this.json = json;
    }

    @SqsListener(value = "${pigeon.aws.queues.send}", maxConcurrentMessages = "${pigeon.sender.concurrency:10}")
    public void onMessage(String body, Visibility visibility) {
        SendCommand command = parse(body);
        MDC.put("correlationId", command.correlationId());
        MDC.put("messageId", String.valueOf(command.messageId()));
        try {
            SendOutcome outcome = sendService.process(command);
            if (outcome.kind() == SendOutcome.Kind.RETRY) {
                int seconds = (int) Math.max(1, Math.min(outcome.retryAfter().toSeconds(), 43_200));
                try {
                    visibility.changeTo(seconds).join();
                } catch (RuntimeException e) {
                    log.warn("Could not change visibility for {}: {}", command.messageId(), e.getMessage());
                }
                throw new RetryLaterException("Retry " + command.messageId() + " in " + seconds + "s: " + outcome.detail());
            }
            if (outcome.kind() == SendOutcome.Kind.SKIPPED) {
                log.info("Skipped message {}: {}", command.messageId(), outcome.detail());
            }
        } finally {
            MDC.remove("correlationId");
            MDC.remove("messageId");
        }
    }

    private SendCommand parse(String body) {
        try {
            SendCommand command = json.readValue(body, SendCommand.class);
            if (command.messageId() == null) {
                throw new IllegalArgumentException("Send command has no messageId");
            }
            return command;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unreadable send command (will go to the DLQ after 3 tries)", e);
        }
    }
}

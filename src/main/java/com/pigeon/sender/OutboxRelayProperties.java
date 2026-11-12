package com.pigeon.sender;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbox relay settings, bound from {@code pigeon.outbox.relay.*}.
 *
 * @param enabled        run the relay (tests that do not need AWS turn it off)
 * @param pollIntervalMs delay between polls when the outbox is empty
 * @param batchSize      rows locked and published per transaction
 * @param maxAttempts    publish attempts before a row (and its message) is marked FAILED
 * @param sendTimeout    how long to wait for SQS to accept one message
 * @param maxBackoff     longest pause after consecutive failures (SQS down)
 */
@ConfigurationProperties(prefix = "pigeon.outbox.relay")
public record OutboxRelayProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500") long pollIntervalMs,
        @DefaultValue("50") int batchSize,
        @DefaultValue("20") int maxAttempts,
        @DefaultValue("5s") Duration sendTimeout,
        @DefaultValue("30s") Duration maxBackoff) {
}

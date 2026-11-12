package com.pigeon.sender;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sender worker settings, bound from {@code pigeon.sender.*}.
 *
 * @param enabled            start the SQS listener (tests that do not need AWS turn it off)
 * @param concurrency        messages processed in parallel per instance
 * @param ratePerSecond      SES calls per second per instance; keep the fleet total under the
 *                           account's MaxSendRate (SES GetAccount -> SendQuota)
 * @param maxAttempts        SES attempts before a message is marked FAILED
 * @param lease              how long a worker owns a message while sending
 * @param baseBackoff        delay before the first retry; doubles each attempt
 * @param maxBackoff         cap for the retry delay
 * @param fromDisplayName    name shown next to the From address
 * @param unsubscribeUrl     one-click unsubscribe endpoint used in BULK List-Unsubscribe headers
 * @param unsubscribeMailbox mailto fallback for List-Unsubscribe
 */
@ConfigurationProperties(prefix = "pigeon.sender")
public record SenderProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int concurrency,
        @DefaultValue("14") int ratePerSecond,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("10s") Duration baseBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("Acme Benefits") String fromDisplayName,
        @DefaultValue("https://member.acmebenefits.example/unsubscribe") String unsubscribeUrl,
        @DefaultValue("unsubscribe@notify.pigeon.local") String unsubscribeMailbox) {

    /** 10s, 20s, 40s ... capped at maxBackoff. */
    public Duration backoffFor(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        Duration delay = baseBackoff.multipliedBy(factor);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}

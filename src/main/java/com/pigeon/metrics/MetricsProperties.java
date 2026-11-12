package com.pigeon.metrics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reputation thresholds, bound from {@code pigeon.metrics.*}. Rates are percentages.
 *
 * <p>The review and pause values mirror what SES enforces on an account. The warning values are
 * our own earlier alarm, so a human looks before AWS does.
 *
 * @param minimumVolume       sends in the window needed before rates are judged at all
 * @param bounceWarning       early warning, hard bounce rate %
 * @param bounceReview        SES puts the account under review, hard bounce rate %
 * @param bouncePause         SES may pause sending, hard bounce rate %
 * @param complaintWarning    early warning, complaint rate %
 * @param complaintReview     SES review threshold, complaint rate %
 * @param complaintPause      SES pause threshold, complaint rate %
 * @param defaultWindow       window when the caller gives none
 * @param maxWindow           largest window a caller may ask for
 * @param refreshIntervalMs   how often the Prometheus gauges are recomputed
 */
@ConfigurationProperties(prefix = "pigeon.metrics")
public record MetricsProperties(
        @DefaultValue("20") int minimumVolume,
        @DefaultValue("2.0") double bounceWarning,
        @DefaultValue("5.0") double bounceReview,
        @DefaultValue("10.0") double bouncePause,
        @DefaultValue("0.05") double complaintWarning,
        @DefaultValue("0.1") double complaintReview,
        @DefaultValue("0.5") double complaintPause,
        @DefaultValue("24h") Duration defaultWindow,
        @DefaultValue("31d") Duration maxWindow,
        @DefaultValue("30000") long refreshIntervalMs) {
}

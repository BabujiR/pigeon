package com.pigeon.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the last-24-hours reputation as Prometheus gauges ({@code /actuator/prometheus}):
 *
 * <pre>
 *   pigeon_reputation_bounce_rate_percent      hard bounce rate, %
 *   pigeon_reputation_complaint_rate_percent   complaint rate, %
 *   pigeon_reputation_status                   0 HEALTHY, 1 UNDER_REVIEW, 2 PAUSED
 *   pigeon_reputation_early_warning            1 when approaching review levels
 *   pigeon_messages_queued                     messages waiting to be sent
 *   pigeon_outbox_pending                      rows the relay has not published yet
 * </pre>
 *
 * Alarm on these well before SES acts. Values are recomputed on a schedule, not per scrape, so a
 * busy Prometheus cannot load the database.
 */
@Component
@ConditionalOnProperty(prefix = "pigeon.metrics.gauges", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReputationGauges {

    private static final Logger log = LoggerFactory.getLogger(ReputationGauges.class);

    private final ReputationService reputation;
    private final MetricsProperties props;
    private final AtomicReference<ReputationService.Report> latest = new AtomicReference<>();

    public ReputationGauges(ReputationService reputation, MetricsProperties props, MeterRegistry registry) {
        this.reputation = reputation;
        this.props = props;
        Gauge.builder("pigeon.reputation.bounce.rate.percent", latest, r -> value(r.get(), Field.BOUNCE))
                .description("Hard bounce rate over the last 24h, percent").register(registry);
        Gauge.builder("pigeon.reputation.complaint.rate.percent", latest, r -> value(r.get(), Field.COMPLAINT))
                .description("Complaint rate over the last 24h, percent").register(registry);
        Gauge.builder("pigeon.reputation.status", latest, r -> value(r.get(), Field.STATUS))
                .description("0 HEALTHY, 1 UNDER_REVIEW, 2 PAUSED").register(registry);
        Gauge.builder("pigeon.reputation.early.warning", latest, r -> value(r.get(), Field.WARNING))
                .description("1 when rates approach the SES review levels").register(registry);
        Gauge.builder("pigeon.messages.queued", latest, r -> value(r.get(), Field.QUEUED))
                .description("Messages accepted but not yet sent").register(registry);
        Gauge.builder("pigeon.outbox.pending", latest, r -> value(r.get(), Field.OUTBOX))
                .description("Outbox rows not yet published to SQS").register(registry);
    }

    private enum Field { BOUNCE, COMPLAINT, STATUS, WARNING, QUEUED, OUTBOX }

    private static double value(ReputationService.Report r, Field f) {
        if (r == null) {
            return Double.NaN;
        }
        return switch (f) {
            case BOUNCE -> r.totals().bounceRatePercent();
            case COMPLAINT -> r.totals().complaintRatePercent();
            case STATUS -> r.status().ordinal();
            case WARNING -> r.earlyWarning() ? 1 : 0;
            case QUEUED -> r.pipeline().queuedNow();
            case OUTBOX -> r.pipeline().outboxPending();
        };
    }

    @Scheduled(initialDelay = 5_000, fixedDelayString = "${pigeon.metrics.refresh-interval-ms:30000}")
    public void refresh() {
        try {
            Instant now = Instant.now();
            ReputationService.Report report = reputation.report(now.minus(props.defaultWindow()), now);
            ReputationService.Report previous = latest.getAndSet(report);
            if (previous != null && previous.status() != report.status()) {
                log.warn("Reputation status changed {} -> {}: {}", previous.status(), report.status(), report.reasons());
            }
        } catch (RuntimeException e) {
            log.warn("Could not refresh reputation gauges: {}", e.getMessage());
        }
    }
}

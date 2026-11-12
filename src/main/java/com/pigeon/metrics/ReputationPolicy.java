package com.pigeon.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure rules: counts in, verdict out. No database, so every threshold is unit-tested.
 *
 * <p>Like SES, the bounce rate counts <b>hard</b> bounces only, and both rates are divided by
 * emails sent. Below {@code minimumVolume} sends the rates are reported but not judged, because
 * 1 bounce out of 3 sends is noise, not a reputation problem.
 */
public final class ReputationPolicy {

    private ReputationPolicy() {
    }

    public record Verdict(ReputationStatus status, boolean earlyWarning, boolean representative,
                          double bounceRatePercent, double complaintRatePercent, List<String> reasons) {
    }

    public static Verdict judge(long sent, long hardBounced, long complained, MetricsProperties t) {
        double bounce = percent(hardBounced, sent);
        double complaint = percent(complained, sent);
        List<String> reasons = new ArrayList<>();

        if (sent < t.minimumVolume()) {
            reasons.add("Only " + sent + " emails sent in the window; at least " + t.minimumVolume()
                    + " are needed before rates are judged");
            return new Verdict(ReputationStatus.HEALTHY, false, false, bounce, complaint, reasons);
        }

        ReputationStatus status = ReputationStatus.HEALTHY;
        if (bounce >= t.bouncePause()) {
            status = ReputationStatus.PAUSED;
            reasons.add("Hard bounce rate " + bounce + "% is at or above the SES pause level of " + t.bouncePause() + "%");
        } else if (bounce >= t.bounceReview()) {
            status = ReputationStatus.UNDER_REVIEW;
            reasons.add("Hard bounce rate " + bounce + "% is at or above the SES review level of " + t.bounceReview() + "%");
        }
        if (complaint >= t.complaintPause()) {
            status = ReputationStatus.PAUSED;
            reasons.add("Complaint rate " + complaint + "% is at or above the SES pause level of " + t.complaintPause() + "%");
        } else if (complaint >= t.complaintReview()) {
            status = worst(status, ReputationStatus.UNDER_REVIEW);
            reasons.add("Complaint rate " + complaint + "% is at or above the SES review level of " + t.complaintReview() + "%");
        }

        boolean warning = status == ReputationStatus.HEALTHY
                && (bounce >= t.bounceWarning() || complaint >= t.complaintWarning());
        if (warning) {
            reasons.add("Early warning: rates are approaching SES review levels (bounce warning "
                    + t.bounceWarning() + "%, complaint warning " + t.complaintWarning() + "%)");
        }
        return new Verdict(status, warning, true, bounce, complaint, reasons);
    }

    static ReputationStatus worst(ReputationStatus a, ReputationStatus b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** Percentage with 3 decimals (0.1% complaint thresholds need that precision). */
    static double percent(long part, long whole) {
        if (whole <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}

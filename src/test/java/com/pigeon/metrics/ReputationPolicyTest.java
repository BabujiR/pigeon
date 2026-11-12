package com.pigeon.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Every threshold, as plain arithmetic. */
class ReputationPolicyTest {

    private static final MetricsProperties T = new MetricsProperties(20, 2.0, 5.0, 10.0, 0.05, 0.1, 0.5,
            Duration.ofHours(24), Duration.ofDays(31), 30_000);

    @Test
    void cleanSendingIsHealthy() {
        ReputationPolicy.Verdict v = ReputationPolicy.judge(1000, 5, 0, T);

        assertThat(v.status()).isEqualTo(ReputationStatus.HEALTHY);
        assertThat(v.earlyWarning()).isFalse();
        assertThat(v.bounceRatePercent()).isEqualTo(0.5);
    }

    @Test
    void approachingReviewRaisesAnEarlyWarningButStaysHealthy() {
        ReputationPolicy.Verdict v = ReputationPolicy.judge(1000, 25, 0, T);   // 2.5%

        assertThat(v.status()).isEqualTo(ReputationStatus.HEALTHY);
        assertThat(v.earlyWarning()).isTrue();
    }

    @Test
    void fivePercentHardBouncesIsUnderReview() {
        assertThat(ReputationPolicy.judge(100, 5, 0, T).status()).isEqualTo(ReputationStatus.UNDER_REVIEW);
        assertThat(ReputationPolicy.judge(100, 4, 0, T).status()).isEqualTo(ReputationStatus.HEALTHY);
    }

    @Test
    void tenPercentHardBouncesIsPaused() {
        ReputationPolicy.Verdict v = ReputationPolicy.judge(100, 10, 0, T);

        assertThat(v.status()).isEqualTo(ReputationStatus.PAUSED);
        assertThat(v.reasons()).anySatisfy(r -> assertThat(r).contains("pause level"));
    }

    @Test
    void complaintThresholdsUseThreeDecimals() {
        assertThat(ReputationPolicy.judge(1000, 0, 1, T).status()).isEqualTo(ReputationStatus.UNDER_REVIEW);   // 0.1%
        assertThat(ReputationPolicy.judge(1000, 0, 5, T).status()).isEqualTo(ReputationStatus.PAUSED);         // 0.5%
        assertThat(ReputationPolicy.judge(10_000, 0, 9, T).status()).isEqualTo(ReputationStatus.HEALTHY);      // 0.09%
        assertThat(ReputationPolicy.judge(10_000, 0, 9, T).complaintRatePercent()).isEqualTo(0.09);
    }

    @Test
    void worstOfBounceAndComplaintWins() {
        ReputationPolicy.Verdict v = ReputationPolicy.judge(1000, 60, 6, T);   // bounce review, complaint pause

        assertThat(v.status()).isEqualTo(ReputationStatus.PAUSED);
        assertThat(v.reasons()).hasSize(2);
    }

    @Test
    void lowVolumeIsReportedButNotJudged() {
        ReputationPolicy.Verdict v = ReputationPolicy.judge(5, 5, 0, T);

        assertThat(v.representative()).isFalse();
        assertThat(v.status()).isEqualTo(ReputationStatus.HEALTHY);
        assertThat(v.bounceRatePercent()).isEqualTo(100.0);
        assertThat(v.reasons()).singleElement().asString().contains("Only 5 emails");
    }

    @Test
    void nothingSentMeansZeroRates() {
        ReputationPolicy.Verdict v = ReputationPolicy.judge(0, 0, 0, T);

        assertThat(v.bounceRatePercent()).isZero();
        assertThat(v.complaintRatePercent()).isZero();
    }
}

package com.pigeon.metrics;

/** Mirrors how SES treats an account. Ordered from best to worst. */
public enum ReputationStatus {
    /** Rates below the SES review thresholds. */
    HEALTHY,
    /** At or above a review threshold: SES places the account under review. */
    UNDER_REVIEW,
    /** At or above a pause threshold: SES may pause all sending from the account. */
    PAUSED
}

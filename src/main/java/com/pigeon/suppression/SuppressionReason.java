package com.pigeon.suppression;

/** Why an address is suppressed. Values match ck_suppression_reason in V3. */
public enum SuppressionReason {
    HARD_BOUNCE,
    COMPLAINT,
    REPEATED_SOFT_BOUNCE,
    ACCOUNT_SUPPRESSION_LIST,
    MANUAL
}

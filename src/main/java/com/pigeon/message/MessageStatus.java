package com.pigeon.message;

/** Lifecycle of one email. Values match the ck_message_status constraint in V2. */
public enum MessageStatus {
    /** Request validated and stored. */
    ACCEPTED,
    /** Outbox row written; the relay will publish it to pigeon-send-queue (Phase 3). */
    QUEUED,
    /** SES accepted the email and returned a message id. */
    SENT,
    DELIVERED,
    BOUNCED,
    COMPLAINED,
    /** Recipient is on the suppression list. Nothing was sent. */
    SUPPRESSED,
    /** Permanent failure before or at SES (for example a rejected message). */
    FAILED
}

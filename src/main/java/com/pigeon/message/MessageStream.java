package com.pigeon.message;

/**
 * Traffic stream. Each stream sends through its own SES configuration set, so a bad bulk
 * campaign cannot damage the reputation that password resets and receipts depend on.
 */
public enum MessageStream {
    TRANSACTIONAL,
    BULK
}

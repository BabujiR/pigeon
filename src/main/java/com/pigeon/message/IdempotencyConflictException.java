package com.pigeon.message;

/**
 * The same Idempotency-Key was reused with a different request body. That is a caller bug:
 * returning the original message would hide it, and sending a new one would break the key's
 * promise. Mapped to HTTP 422.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String key;

    public IdempotencyConflictException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
        this.key = key;
    }

    public String key() {
        return key;
    }
}

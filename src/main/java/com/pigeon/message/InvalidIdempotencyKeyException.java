package com.pigeon.message;

/** The Idempotency-Key header is blank or too long. Mapped to HTTP 400. */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(int maxLength) {
        super("Idempotency-Key must be 1 to " + maxLength + " characters");
    }
}

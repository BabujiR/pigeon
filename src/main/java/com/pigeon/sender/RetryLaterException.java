package com.pigeon.sender;

/** Thrown by the listener so SQS keeps the message and redelivers it after the backoff. */
public class RetryLaterException extends RuntimeException {

    public RetryLaterException(String message) {
        super(message, null, false, false);   // no stack trace: this is flow control, not a bug
    }
}

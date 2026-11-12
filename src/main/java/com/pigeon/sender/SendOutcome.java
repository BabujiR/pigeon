package com.pigeon.sender;

import java.time.Duration;

/** What the worker did with one SQS delivery. Only RETRY leaves the message on the queue. */
public record SendOutcome(Kind kind, String detail, Duration retryAfter) {

    public enum Kind { SENT, SKIPPED, FAILED, RETRY }

    static SendOutcome sent(String sesMessageId) {
        return new SendOutcome(Kind.SENT, sesMessageId, null);
    }

    static SendOutcome skipped(String reason) {
        return new SendOutcome(Kind.SKIPPED, reason, null);
    }

    static SendOutcome failed(String reason) {
        return new SendOutcome(Kind.FAILED, reason, null);
    }

    static SendOutcome retry(String reason, Duration after) {
        return new SendOutcome(Kind.RETRY, reason, after);
    }
}

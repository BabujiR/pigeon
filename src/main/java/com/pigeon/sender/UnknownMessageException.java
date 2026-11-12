package com.pigeon.sender;

import java.util.UUID;

/**
 * A send command points at a message that does not exist. Never succeeds on retry, so after
 * the queue's maxReceiveCount it lands in pigeon-send-dlq for someone to inspect.
 */
public class UnknownMessageException extends RuntimeException {

    public UnknownMessageException(UUID id) {
        super("No message_record with id " + id);
    }
}

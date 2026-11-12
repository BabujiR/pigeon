package com.pigeon.events;

import java.util.UUID;

/**
 * An SES event arrived before Pigeon committed the SENT status for that message (SES can be
 * that fast). Thrown so SQS redelivers the event after its visibility timeout.
 */
public class EventNotReadyException extends RuntimeException {

    public EventNotReadyException(UUID messageId, String eventType) {
        super(eventType + " event for message " + messageId + " arrived before SENT was recorded; will retry",
                null, false, false);
    }
}

package com.pigeon.message;

import java.util.UUID;

/** No message with this requestId. Mapped to HTTP 404. */
public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException(UUID id) {
        super("No communication with requestId " + id);
    }
}

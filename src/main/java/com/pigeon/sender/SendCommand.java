package com.pigeon.sender;

import java.util.UUID;

/**
 * The SQS message body on pigeon-send-queue: {"messageId":"...","correlationId":"..."}.
 * Ids only. The worker loads everything else from the database, so no PII is on the queue.
 */
public record SendCommand(UUID messageId, String correlationId) {
}

package com.pigeon.sender;

import java.time.Instant;
import java.util.UUID;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Published inside the application after SES accepted an email and the SENT status committed.
 * Carries the exact request sent, so a listener can see recipient, configuration set and tags.
 */
public record EmailSentEvent(UUID messageId, String sesMessageId, SendEmailRequest request, Instant sentAt) {
}

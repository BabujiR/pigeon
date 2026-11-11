package com.pigeon.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.pigeon.message.CommunicationService.MessageView;
import com.pigeon.message.MessageRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response to {@code GET /v1/communications/{requestId}}: current status plus full history.
 * Merge values are deliberately not returned; they are PII and the caller already has them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "communication")
public record CommunicationStatusResponse(
        UUID requestId,
        String status,
        String templateId,
        int templateVersion,
        String recipient,
        String stream,
        String configurationSet,
        String sesMessageId,
        String correlationId,
        int attemptCount,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt,
        @JacksonXmlElementWrapper(localName = "events")
        @JacksonXmlProperty(localName = "event")
        List<Event> events) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(String type, Instant occurredAt, Map<String, Object> detail) {
    }

    static CommunicationStatusResponse from(MessageView view) {
        MessageRecord m = view.message();
        List<Event> events = view.events().stream()
                .map(e -> new Event(e.getEventType(), e.getOccurredAt(), e.getDetail()))
                .toList();
        return new CommunicationStatusResponse(
                m.getId(), m.getStatus().name(), m.getTemplateId(), m.getTemplateVersion(),
                m.getRecipient(), m.getStream().name(), m.getConfigurationSet(), m.getSesMessageId(),
                m.getCorrelationId(), m.getAttemptCount(), m.getLastError(),
                m.getCreatedAt(), m.getUpdatedAt(), m.getSentAt(), events);
    }
}

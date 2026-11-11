package com.pigeon.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only history entry for a message: a status change or an SES event. */
@Entity
@Table(name = "message_event")
public class MessageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @Column(name = "occurred_at", nullable = false, columnDefinition = "timestamptz")
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, columnDefinition = "timestamptz")
    private Instant recordedAt;

    protected MessageEvent() {
        // for JPA
    }

    public static MessageEvent of(UUID messageId, String eventType, Map<String, Object> detail, Instant occurredAt) {
        MessageEvent e = new MessageEvent();
        e.messageId = messageId;
        e.eventType = eventType;
        e.detail = detail;
        e.occurredAt = occurredAt;
        e.recordedAt = Instant.now();
        return e;
    }

    public Long getId() {
        return id;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public String getEventType() {
        return eventType;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}

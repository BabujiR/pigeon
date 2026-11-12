package com.pigeon.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A "please publish this" row, written in the same transaction as the message.
 * The outbox relay (Phase 3) publishes PENDING rows to SQS and marks them PUBLISHED.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntry {

    public enum Status { PENDING, PUBLISHED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(nullable = false)
    private String destination;

    /** Ids only (messageId, correlationId). The worker loads the rest, so no PII travels on the queue. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "published_at", columnDefinition = "timestamptz")
    private Instant publishedAt;

    protected OutboxEntry() {
        // for JPA
    }

    public static OutboxEntry pending(UUID messageId, String destination, Map<String, Object> payload, Instant now) {
        OutboxEntry o = new OutboxEntry();
        o.messageId = messageId;
        o.destination = destination;
        o.payload = payload;
        o.status = Status.PENDING;
        o.createdAt = now;
        return o;
    }

    public void markPublished(Instant now) {
        this.attempts++;
        this.status = Status.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    /** Counts a failed publish; gives up (FAILED) after {@code maxAttempts}. */
    public void recordFailure(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null || error.length() <= 1000 ? error : error.substring(0, 1000);
        if (attempts >= maxAttempts) {
            this.status = Status.FAILED;
        }
    }

    public String getLastError() {
        return lastError;
    }

    public Long getId() {
        return id;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public String getDestination() {
        return destination;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}

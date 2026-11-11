package com.pigeon.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** Remembers which message an Idempotency-Key produced, and a fingerprint of the request body. */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(name = "idem_key")
    private String key;

    @Column(name = "request_hash", nullable = false, columnDefinition = "bpchar(64)")
    private String requestHash;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamptz")
    private Instant expiresAt;

    @Transient
    private boolean isNew = true;

    protected IdempotencyRecord() {
        // for JPA
    }

    public IdempotencyRecord(String key, String requestHash, UUID messageId, Instant createdAt, Instant expiresAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.messageId = messageId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean matches(String hash) {
        return requestHash.equals(hash);
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return key;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getMessageId() {
        return messageId;
    }
}

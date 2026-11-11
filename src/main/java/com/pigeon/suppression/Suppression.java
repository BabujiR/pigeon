package com.pigeon.suppression;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** An address Pigeon must never send to again. Written by the event processor (Phase 4). */
@Entity
@Table(name = "suppression")
public class Suppression {

    @Id
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SuppressionReason reason;

    @Column(nullable = false)
    private String source;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    protected Suppression() {
        // for JPA
    }

    public String getAddress() {
        return address;
    }

    public SuppressionReason getReason() {
        return reason;
    }

    public String getSource() {
        return source;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

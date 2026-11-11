package com.pigeon.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A template's stable identity and which version is live. Read-only in the application:
 * templates change through migrations (or a future admin API), never as a side effect of sending.
 */
@Entity
@Table(name = "template")
public class Template {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "from_address", nullable = false)
    private String fromAddress;

    @Column(name = "active_version", nullable = false)
    private int activeVersion;

    protected Template() {
        // for JPA
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public int getActiveVersion() {
        return activeVersion;
    }
}

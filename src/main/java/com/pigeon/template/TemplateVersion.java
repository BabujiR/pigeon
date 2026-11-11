package com.pigeon.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One immutable version of a template's wording. */
@Entity
@Table(name = "template_version")
@IdClass(TemplateVersion.Key.class)
public class TemplateVersion {

    @Id
    @Column(name = "template_id")
    private String templateId;

    @Id
    @Column(name = "version")
    private int version;

    @Column(nullable = false)
    private String subject;

    @Column(name = "html_body", nullable = false)
    private String htmlBody;

    @Column(name = "text_body", nullable = false)
    private String textBody;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "required_fields", nullable = false)
    private String[] requiredFields;

    protected TemplateVersion() {
        // for JPA
    }

    public TemplateContent toContent() {
        return new TemplateContent(subject, htmlBody, textBody, Arrays.asList(requiredFields));
    }

    public String getTemplateId() {
        return templateId;
    }

    public int getVersion() {
        return version;
    }

    /** Composite key (template_id, version). */
    public static class Key implements Serializable {

        private String templateId;
        private int version;

        protected Key() {
            // for JPA
        }

        public Key(String templateId, int version) {
            this.templateId = templateId;
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && version == k.version && Objects.equals(templateId, k.templateId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(templateId, version);
        }
    }
}

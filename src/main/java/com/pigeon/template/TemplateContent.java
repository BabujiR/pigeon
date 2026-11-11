package com.pigeon.template;

import java.util.List;

/**
 * The raw content of one template version, independent of how it is stored.
 * Keeps {@link TemplateRenderer} free of JPA so it can be tested as plain Java.
 */
public record TemplateContent(String subject, String htmlBody, String textBody, List<String> requiredFields) {

    public TemplateContent {
        requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
    }
}

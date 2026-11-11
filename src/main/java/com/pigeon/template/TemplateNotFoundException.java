package com.pigeon.template;

/** No template with this id, or its active version is missing. Phase 2 maps this to HTTP 400. */
public class TemplateNotFoundException extends RuntimeException {

    private final String templateId;

    public TemplateNotFoundException(String templateId) {
        super("Unknown template: " + templateId);
        this.templateId = templateId;
    }

    public String templateId() {
        return templateId;
    }
}

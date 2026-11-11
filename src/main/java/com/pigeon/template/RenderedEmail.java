package com.pigeon.template;

/**
 * A ready-to-send email. Carries the template id and version so the message record can prove
 * exactly which wording the recipient received.
 */
public record RenderedEmail(
        String templateId,
        int templateVersion,
        String fromAddress,
        String subject,
        String html,
        String text) {
}

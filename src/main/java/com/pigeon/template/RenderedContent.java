package com.pigeon.template;

/** Subject, HTML and text after merge fields are filled in. */
public record RenderedContent(String subject, String html, String text) {
}

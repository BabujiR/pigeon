package com.pigeon.support;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * One id that follows a request through every hop: HTTP request -> message record -> outbox ->
 * SQS message attribute -> sender log lines -> SES tags -> SES event -> event processor log lines.
 * Search the logs for it and you see the whole life of an email.
 *
 * <p>Stored in the logging MDC under {@link #MDC_KEY}, so every log line carries it, in the plain
 * pattern and in the JSON (ECS) format alike.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String MESSAGE_ID_KEY = "messageId";

    /** Accept a caller's id only if it is short and safe to put in logs and headers. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private CorrelationId() {
    }

    /** The caller's id if it is safe, otherwise a new UUID. */
    public static String resolve(String supplied) {
        return supplied != null && SAFE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
    }

    /** The id for the current thread, creating one if none is set (e.g. called outside a request). */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        if (id == null) {
            id = UUID.randomUUID().toString();
            MDC.put(MDC_KEY, id);
        }
        return id;
    }

    /** Puts both ids on the current thread's log context. */
    public static void bind(String correlationId, Object messageId) {
        if (correlationId != null) {
            MDC.put(MDC_KEY, correlationId);
        }
        if (messageId != null) {
            MDC.put(MESSAGE_ID_KEY, messageId.toString());
        }
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
        MDC.remove(MESSAGE_ID_KEY);
    }
}

package com.pigeon.events;

import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes pigeon-events-queue (subscribed to pigeon-events-topic, the SES event destination).
 * Returning deletes the message. Throwing leaves it for redelivery, and after 3 receives SQS moves
 * it to pigeon-events-dlq.
 */
@Component
@ConditionalOnProperty(prefix = "pigeon.events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SesEventListener {

    private static final Logger log = LoggerFactory.getLogger(SesEventListener.class);

    private final SesEventProcessor processor;

    public SesEventListener(SesEventProcessor processor) {
        this.processor = processor;
    }

    @SqsListener("${pigeon.aws.queues.events}")
    public void onEvent(String body) {
        SesEventProcessor.Result result = processor.process(body);
        log.debug("SES event processed: {}", result);
    }
}

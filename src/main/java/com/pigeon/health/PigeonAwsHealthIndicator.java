package com.pigeon.health;

import com.pigeon.config.PigeonAwsProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Reports DOWN unless every queue and the events topic (with a subscriber) exist.
 *
 * <p>Review note: a platform that cannot reach its queues must not look healthy, or a load
 * balancer will route traffic to an instance that accepts requests it can never send.
 * Shows up as {@code components.pigeonAws} in {@code /actuator/health}.
 */
@Component("pigeonAws")
public class PigeonAwsHealthIndicator implements HealthIndicator {

    private static final long TIMEOUT_SECONDS = 5;

    private final SqsAsyncClient sqs;
    private final SnsClient sns;
    private final PigeonAwsProperties props;

    public PigeonAwsHealthIndicator(SqsAsyncClient sqs, SnsClient sns, PigeonAwsProperties props) {
        this.sqs = sqs;
        this.sns = sns;
        this.props = props;
    }

    @Override
    public Health health() {
        try {
            Map<String, String> queues = new LinkedHashMap<>();
            for (String name : props.queues().all()) {
                String url = sqs.getQueueUrl(b -> b.queueName(name))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .queueUrl();
                queues.put(name, url);
            }

            String topicArn = sns.listTopics().topics().stream()
                    .map(Topic::topicArn)
                    .filter(arn -> arn.endsWith(":" + props.eventsTopic()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "SNS topic not found: " + props.eventsTopic()));

            int subscriptions = sns.listSubscriptionsByTopic(b -> b.topicArn(topicArn))
                    .subscriptions().size();
            if (subscriptions == 0) {
                return Health.down()
                        .withDetail("error", "No subscribers on " + props.eventsTopic()
                                + ": delivery events would be lost")
                        .build();
            }

            return Health.up()
                    .withDetail("queues", queues)
                    .withDetail("eventsTopic", topicArn)
                    .withDetail("eventsTopicSubscriptions", subscriptions)
                    .withDetail("configurationSets", props.configurationSets())
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("error", "Interrupted while checking AWS resources").build();
        } catch (ExecutionException e) {
            return Health.down().withDetail("error", e.getCause().getMessage()).build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}

package com.pigeon.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every AWS resource name in one typed place, bound from {@code pigeon.aws.*}.
 *
 * <p>Review note: resource names are configuration, not constants. The same build must run
 * against LocalStack, a dev account and production, and the app fails fast at startup if a
 * name is missing instead of failing on the first send.
 */
@Validated
@ConfigurationProperties(prefix = "pigeon.aws")
public record PigeonAwsProperties(
        @Valid @NotNull Queues queues,
        @NotBlank String eventsTopic,
        @Valid @NotNull ConfigurationSets configurationSets,
        @NotBlank String identity) {

    public record Queues(
            @NotBlank String send,
            @NotBlank String sendDlq,
            @NotBlank String events,
            @NotBlank String eventsDlq) {

        public List<String> all() {
            return List.of(send, sendDlq, events, eventsDlq);
        }
    }

    /** One SES configuration set per traffic stream, so each stream keeps its own reputation. */
    public record ConfigurationSets(
            @NotBlank String transactional,
            @NotBlank String bulk) {
    }
}

package com.pigeon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Pigeon: Trusted delivery, built on AWS SES.
 *
 * <p>Package layout (one deployable, clear boundaries so it can be split into services later):
 * <ul>
 *   <li>{@code api}         - Communication API: accepts and validates requests</li>
 *   <li>{@code template}    - versioned templates and merge-field rendering</li>
 *   <li>{@code sender}      - SQS consumer that calls SES</li>
 *   <li>{@code events}      - SES delivery event processing (the feedback loop)</li>
 *   <li>{@code suppression} - addresses we must never send to</li>
 *   <li>{@code metrics}     - reputation metrics mirroring SES enforcement</li>
 *   <li>{@code config}, {@code health} - wiring and operational checks</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PigeonApplication {

    public static void main(String[] args) {
        SpringApplication.run(PigeonApplication.class, args);
    }
}

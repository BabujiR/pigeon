package com.pigeon.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * AWS clients that Spring Cloud AWS does not auto-configure.
 *
 * <p>SQS ({@code SqsAsyncClient}) and SNS ({@code SnsClient}) come from the Spring Cloud AWS
 * starters. SES v2 does not, so it is built here from the same credentials, region and endpoint
 * settings ({@code spring.cloud.aws.*}). That keeps one switch between LocalStack and real AWS:
 * set {@code spring.cloud.aws.endpoint} locally, leave it empty on AWS.
 */
@Configuration
public class AwsClientConfig {

    @Bean
    SesV2Client sesV2Client(AwsCredentialsProvider credentialsProvider,
                            AwsRegionProvider regionProvider,
                            @Value("${spring.cloud.aws.endpoint:}") String endpoint) {
        var builder = SesV2Client.builder()
                .credentialsProvider(credentialsProvider)
                .region(regionProvider.getRegion());
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}

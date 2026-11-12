package com.pigeon.support;

import java.time.Duration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base for end-to-end tests: real Postgres and real LocalStack (SQS, SNS, SES), with the outbox
 * relay and SQS listener running exactly as in production. Containers are started once per test
 * run and shared by every subclass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
public abstract class FullStackIntegrationTest {

    private static final String INIT_SCRIPT = "localstack/init/ready.d/01-pigeon-resources.sh";

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14"))
                    .withEnv("SERVICES", "sqs,sns,ses")
                    .withEnv("AWS_DEFAULT_REGION", "us-east-1")
                    .withCopyFileToContainer(MountableFile.forHostPath(INIT_SCRIPT, 0755),
                            "/etc/localstack/init/ready.d/01-pigeon-resources.sh")
                    .waitingFor(Wait.forHttp("/_localstack/init/ready")
                            .forPort(4566)
                            .forResponsePredicate(body -> body.matches("(?s).*\"completed\":\\s*true.*"))
                            .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        POSTGRES.start();
        LOCALSTACK.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        // Faster polling keeps the tests quick; production uses the defaults.
        registry.add("pigeon.outbox.relay.poll-interval-ms", () -> "100");
        registry.add("spring.cloud.aws.sqs.listener.poll-timeout", () -> "1s");
    }

    /** Base URL of LocalStack, e.g. for its /_aws/ses inspection endpoint. */
    protected static String localstackUrl(String path) {
        return LOCALSTACK.getEndpoint().toString() + path;
    }
}

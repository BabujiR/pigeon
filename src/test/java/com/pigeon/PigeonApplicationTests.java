package com.pigeon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Phase 0 proof: the app starts against real Postgres and LocalStack containers, the same init
 * script as docker-compose creates the resources, and /actuator/health reports UP.
 *
 * <p>Review note: the test reuses the production init script instead of creating resources in
 * Java, so the script itself is under test. Requires Docker Desktop to be running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class PigeonApplicationTests {

    private static final String INIT_SCRIPT = "localstack/init/ready.d/01-pigeon-resources.sh";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14"))
                    .withEnv("SERVICES", "sqs,sns,ses")
                    .withEnv("AWS_DEFAULT_REGION", "us-east-1")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(INIT_SCRIPT, 0755),
                            "/etc/localstack/init/ready.d/01-pigeon-resources.sh")
                    // Wait for the init script to finish, not just for the port to open.
                    .waitingFor(Wait.forHttp("/_localstack/init/ready")
                            .forPort(4566)
                            .forResponsePredicate(body -> body.matches("(?s).*\"completed\":\\s*true.*"))
                            .withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.endpoint", () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", localstack::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", localstack::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", localstack::getSecretKey);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthIsUpAndAllPigeonResourcesExist() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"status\":\"UP\"")
                .contains("pigeon-send-queue", "pigeon-send-dlq", "pigeon-events-queue", "pigeon-events-dlq")
                .contains("pigeon-events-topic");
    }

    @Test
    void infoShowsPigeonTagline() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/info", String.class);

        assertThat(response.getBody()).contains("Trusted delivery, built on AWS SES.");
    }
}

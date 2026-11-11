You are a Lead Software Engineer and Enterprise Email SME (AWS SES, DNS, Java/Spring Boot).
Build "Pigeon" (tagline: "Trusted delivery, built on AWS SES"), a runnable local learning
project that models an enterprise transactional email platform on AWS SES. It must run on
my laptop with Docker and no real AWS account.

## Naming
- Base Java package: com.pigeon; Maven groupId com.pigeon, artifactId pigeon
- Spring application name: pigeon
- AWS resources:
  - SQS: pigeon-send-queue, pigeon-send-dlq, pigeon-events-queue, pigeon-events-dlq
  - SNS: pigeon-events-topic
  - SES configuration sets: pigeon-txn-prod (transactional), pigeon-bulk-prod (bulk)
  - SES identity: notify.pigeon.local (local only)
- Database: pigeon (Postgres)

## Stack
- Java 21, Spring Boot 3.x, Maven 3.9.3
- Spring Cloud AWS 3.x (SQS listeners) and AWS SDK v2 (SesV2Client, SnsClient)
- PostgreSQL 16 with Flyway migrations, Spring Data JPA
- LocalStack (community) for SES, SQS and SNS
- docker-compose.yml that starts Postgres and LocalStack, plus a LocalStack init script that
  creates every AWS resource above on startup (queues with redrive policies, the SNS topic
  subscribed to pigeon-events-queue, the verified identity and both configuration sets)
- Testcontainers for integration tests

## Architecture (one Maven project, packages under com.pigeon)
1. api - Communication API: POST /v1/communications accepts JSON and XML
   { templateId, recipient, stream: TRANSACTIONAL|BULK, variables{} }
   - requires an Idempotency-Key header; a repeated key returns the original result
   - validates required merge fields per template, returns 400 with clear errors
   - checks the suppression list before queueing (status SUPPRESSED, no send)
   - saves a message record (ACCEPTED -> QUEUED), sends to pigeon-send-queue,
     returns 202 with requestId
   - GET /v1/communications/{id} returns status and full event history
2. template - Template service: 3 sample templates (enrollment-confirmation,
   password-reset, payment-receipt) with {{mergeFields}}, versioned, stored in Postgres,
   HTML and text parts, Pigeon branding in the footer.
3. sender - Sender worker: @SqsListener on pigeon-send-queue, renders the template,
   calls SES SendEmail with configuration set pigeon-txn-prod or pigeon-bulk-prod and
   email tags (messageRecordId, template, correlationId).
   - BULK stream adds List-Unsubscribe and List-Unsubscribe-Post headers
   - rate limit sends (Resilience4j); throttling errors are retried with backoff
   - after 3 failed attempts the message moves to pigeon-send-dlq
4. events - Event processing: pigeon-events-topic -> pigeon-events-queue -> processor.
   - Delivery -> DELIVERED
   - Permanent bounce -> BOUNCED and suppress the address
   - Transient bounce -> count it; suppress after 3 in 7 days
   - Complaint -> COMPLAINED and suppress
   - idempotent: ignore duplicate events (SNS delivers at least once)
   IMPORTANT: LocalStack does not produce real bounce or complaint events. Add a
   LocalEventSimulator (only active in the "local" Spring profile) that publishes
   realistic SES event JSON to pigeon-events-topic after each send, based on the
   recipient, matching the real SES mailbox simulator:
     success@simulator.amazonses.com -> Delivery
     bounce@simulator.amazonses.com -> Permanent bounce 5.1.1
     complaint@simulator.amazonses.com -> Delivery then Complaint
     suppressionlist@simulator.amazonses.com -> Bounce OnAccountSuppressionList
     ooto@simulator.amazonses.com -> Delivery
     any address containing "mailboxfull" -> Transient bounce 4.2.2
   Use the real SES event JSON format so the processor code would work unchanged on AWS.
5. suppression - table with address, reason, source, createdAt;
   REST endpoints to list, add and remove entries.
6. metrics - GET /v1/metrics returns sent, delivered, bounce rate and complaint rate,
   and a status of HEALTHY / UNDER_REVIEW (bounce >= 5% or complaint >= 0.1%) /
   PAUSED (bounce >= 10% or complaint >= 0.5%), mirroring SES enforcement.
   Expose Micrometer metrics (pigeon.* names) through /actuator/prometheus.
7. Batch endpoint: POST /v1/communications/batch takes up to 1,000 recipients to
   demonstrate high-volume asynchronous processing.

## Engineering standards
- Transactional outbox so the database record and the queue message cannot drift
- Structured JSON logging with a correlationId on every line; never log full email
  addresses (mask them, e.g. d***@example.com)
- Global exception handler with consistent error responses
- No credentials in code; the local profile uses LocalStack test credentials
- Unit tests for template rendering, validation and event handling; one end-to-end
  integration test: send to bounce@simulator -> event processed -> address suppressed

## Deliverables
- README.md that opens with:
    # Pigeon
    **Trusted delivery, built on AWS SES.**
  then: architecture diagram (Mermaid), prerequisites, one-command start
  (docker compose up -d, then mvn spring-boot:run "-Dspring-boot.run.profiles=local"), and a "Try it" section with curl
  commands for every simulator scenario, idempotency, suppression, metrics and batch
- requests.http file (IntelliJ / VS Code REST Client) with the same scenarios
- docs/DNS.md: the SPF, DKIM (Easy DKIM CNAMEs), custom MAIL FROM (MX + SPF) and DMARC
  records a real domain would need for Pigeon, and why alignment matters
- docs/AWS-DEPLOY.md: what changes to run against a real SES sandbox account (verified
  identity, configuration sets, SNS event destination, least-privilege IAM policy scoped
  to pigeon-* resources, production access request)

## How to work
1. First show me the plan: folder structure, database schema and build order.
   Wait for my OK before writing code.
2. Build in small steps. After each step, compile and run the tests and fix any
   failures before moving on.
3. At the end, start everything yourself, run every "Try it" scenario, and show me
   the results.
4. Explain key design decisions in short comments, the way a lead would in a code review.
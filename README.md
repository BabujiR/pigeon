# Pigeon
**Trusted delivery, built on AWS SES.**

An enterprise transactional email platform built with Java 21 and Spring Boot 3: asynchronous
sending through SQS, event-driven bounce and complaint processing, suppression management,
reputation monitoring, and SPF / DKIM / DMARC domain authentication.

## Build status
| Phase | Scope | Status |
|---|---|---|
| 0 | Project skeleton, Docker, LocalStack resources, health check | Done |
| 1 | Database schema, seeded templates, TemplateRenderer | Done |
| 2 | Communication API: validation, idempotency, suppression check, outbox | Planned |
| 3 | Sender worker calling SES, rate limiting, retries, DLQ | Planned |
| 4 | Event pipeline, suppression rules, local event simulator | Planned |
| 5 | Reputation metrics, suppression API, batch sending | Planned |
| 6 | Structured logging, PII masking, error handling | Planned |
| 7 | Docs, demo scripts, full run-through | Planned |

## Pigeon Lab (interactive)

Start the app and open **http://localhost:8080/**. The lab is served by Pigeon itself
(`src/main/resources/static/index.html`) and runs entirely in your browser:

| Tab | What you can do |
|---|---|
| 1. Architecture | The platform on one page. Select any component to see its job and the requirement it covers |
| 2. Send simulator | Send to the SES mailbox simulator addresses, watch each pipeline step, the SES event JSON, suppression and reputation rates; push a clean or dirty batch |
| 3. Domain authentication | Toggle Easy DKIM, custom MAIL FROM, SPF and the DMARC policy; see alignment, the DNS records and Gmail's header |
| 4. Java code | Reference Spring Boot code for each component, with review notes |
| 5. Lead playbook | Production readiness checklist, numbers to know, risks and interview questions |

## Architecture

Solid arrows carry an email out. Dashed arrows carry delivery events back.

Two versions of the overview are below. Pick the one you prefer and delete the other.

### Option A: detailed (component groups)

Shows which parts are Pigeon code, PostgreSQL and AWS, splits the database into messaging and reputation tables, and includes the Metrics API.

```mermaid
flowchart LR
    client["Client apps<br/>enrollment, billing"]

    subgraph pigeon["Pigeon (Spring Boot)"]
        api["Communication API<br/>POST /v1/communications"]
        relay["Outbox relay"]
        worker["Sender worker"]
        processor["Event processor"]
        metrics["Metrics API<br/>GET /v1/metrics"]
    end

    subgraph data["PostgreSQL"]
        db[("message_record · message_event<br/>idempotency_key · outbox<br/>template · template_version")]
        supp[("suppression · soft_bounce<br/>processed_event")]
    end

    subgraph aws["AWS (LocalStack locally)"]
        sendq[["SQS<br/>pigeon-send-queue"]]
        senddlq[["SQS<br/>pigeon-send-dlq"]]
        ses["Amazon SES<br/>pigeon-txn-prod · pigeon-bulk-prod"]
        topic(["SNS<br/>pigeon-events-topic"])
        evq[["SQS<br/>pigeon-events-queue"]]
        evdlq[["SQS<br/>pigeon-events-dlq"]]
    end

    inbox["Mailbox providers<br/>Gmail, Outlook, gateways"]

    client -->|"1 request + Idempotency-Key"| api
    api -->|"2 check"| supp
    api -->|"3 record + outbox row<br/>(one transaction)"| db
    api -.->|"202 requestId"| client
    relay -->|"4 poll pending"| db
    relay -->|"5 publish"| sendq
    sendq -->|"6 consume"| worker
    worker -->|"7 render active version"| db
    worker -->|"8 SendEmail"| ses
    sendq -->|"after 3 failures"| senddlq
    ses -->|"9 deliver, DKIM-signed"| inbox

    ses -.->|"10 delivery / bounce / complaint"| topic
    topic -.-> evq
    evq -.->|"11 consume"| processor
    evq -.->|"after 3 failures"| evdlq
    processor -.->|"12 update status + history"| db
    processor -.->|"13 suppress hard bounces<br/>and complaints"| supp
    metrics --- db

    classDef awsNode fill:#fff4e5,stroke:#d9822b,color:#1b2430
    classDef appNode fill:#e8eefc,stroke:#2b4fc4,color:#1b2430
    classDef dataNode fill:#eaf6ef,stroke:#1d7347,color:#1b2430
    class sendq,senddlq,ses,topic,evq,evdlq awsNode
    class api,relay,worker,processor,metrics appNode
    class db,supp dataNode
```

### Option B: simplified (top to bottom)

One straight send path with the event loop returning to the database. Easier to read on a small screen or in a slide.

```mermaid
flowchart TB
    client["Client apps<br/>enrollment, billing"]
    api["Communication API<br/>validate · dedupe · suppress"]
    db[("PostgreSQL<br/>messages · outbox · templates<br/>suppression")]
    relay["Outbox relay"]
    sendq[["SQS<br/>pigeon-send-queue"]]
    worker["Sender worker<br/>render · rate limit"]
    ses["Amazon SES<br/>txn / bulk config sets"]
    inbox["Mailbox providers<br/>Gmail · Outlook"]
    topic(["SNS<br/>pigeon-events-topic"])
    evq[["SQS<br/>pigeon-events-queue"]]
    processor["Event processor<br/>status · suppression"]
    dlq1[["pigeon-send-dlq"]]
    dlq2[["pigeon-events-dlq"]]

    client -->|"1 POST + Idempotency-Key"| api
    api -->|"2 record + outbox<br/>one transaction"| db
    db -->|"3 pending rows"| relay
    relay -->|"4 publish"| sendq
    sendq -->|"5 consume"| worker
    worker -->|"6 SendEmail"| ses
    ses -->|"7 deliver"| inbox

    ses -.->|"8 delivery · bounce · complaint"| topic
    topic -.-> evq
    evq -.->|"9 consume"| processor
    processor -.->|"10 update status,<br/>suppress bad addresses"| db

    sendq -.->|"3 failures"| dlq1
    evq -.->|"3 failures"| dlq2

    classDef aws fill:#fff4e5,stroke:#d9822b,color:#1b2430
    classDef app fill:#e8eefc,stroke:#2b4fc4,color:#1b2430
    classDef data fill:#eaf6ef,stroke:#1d7347,color:#1b2430
    classDef dlq fill:#fdecea,stroke:#b02a1e,color:#1b2430
    class sendq,ses,topic,evq aws
    class api,relay,worker,processor app
    class db data
    class dlq1,dlq2 dlq
```

### One email, start to finish

```mermaid
sequenceDiagram
    autonumber
    participant C as Client app
    participant A as Communication API
    participant DB as PostgreSQL
    participant Q as pigeon-send-queue
    participant W as Sender worker
    participant S as Amazon SES
    participant E as SNS → pigeon-events-queue
    participant P as Event processor

    C->>A: POST /v1/communications (Idempotency-Key)
    A->>DB: Key seen before? Address suppressed?
    A->>DB: Insert message_record (ACCEPTED) + outbox row
    A-->>C: 202 Accepted { requestId }
    Note over DB,Q: Outbox relay publishes pending rows
    DB->>Q: SendCommand
    Q->>W: Receive message
    W->>DB: Load template version, render
    W->>S: SendEmail (configuration set, tags)
    S-->>W: SES MessageId
    W->>DB: Status SENT
    S-)E: Delivery / Bounce / Complaint event
    E->>P: Receive event
    P->>DB: Already processed? Skip
    P->>DB: Status DELIVERED / BOUNCED / COMPLAINED
    P->>DB: Hard bounce or complaint → suppression
```

### Why it is built this way
| Decision | Reason |
|---|---|
| API returns 202 and queues the work | Callers never wait on SES; open-enrollment spikes are absorbed by SQS |
| Idempotency-Key on every request | A caller retrying after a timeout never sends a member a duplicate email |
| Transactional outbox | The message row and its queue message commit together, so neither can exist without the other |
| Suppression checked before queueing | Known-bad addresses cost nothing and never touch sender reputation |
| Two configuration sets | Transactional and bulk mail keep separate reputations; a bad campaign cannot hurt password resets |
| Dead-letter queue after 3 attempts | Failing messages are kept for inspection and replay, never silently lost |
| Events processed idempotently | SNS delivers at least once; a redelivered bounce must not be counted twice |
| Versioned templates | Every message records the exact wording it was sent with |

### Local vs AWS
| Concern | Local (this repo) | AWS |
|---|---|---|
| SES, SQS, SNS | LocalStack 4.14 on port 4566 | Real services, IAM role credentials |
| Delivery events | `LocalEventSimulator` publishes SES-format events (Phase 4) | SES configuration set event destination → SNS |
| Database | Postgres 16 container | Amazon RDS / Aurora PostgreSQL |
| Domain authentication | Not needed | SPF, DKIM (Easy DKIM), custom MAIL FROM, DMARC |

The full schema is in [docs/SCHEMA.md](docs/SCHEMA.md).

## Prerequisites
- Java 21, Maven 3.9+ (`mvn -v` must show Java 21)
- Docker Desktop, running
- IntelliJ IDEA (optional)

## Start locally (PowerShell)
```powershell
docker compose up -d
docker compose ps                     # wait until both services show "healthy"
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```
In IntelliJ, use the **Pigeon (local)** run configuration instead of the last line.

## Check it is running
```powershell
# Resources created by localstack/init/ready.d/01-pigeon-resources.sh
docker logs pigeon-localstack 2>&1 | Select-String "pigeon-init"

# Queues, as the app sees them
docker exec pigeon-localstack awslocal sqs list-queues

# Health: status UP, with components.pigeonAws listing every queue and the events topic
curl.exe -s http://localhost:8080/actuator/health
curl.exe -s http://localhost:8080/actuator/info
```

## Tests
```powershell
mvn verify
```
Integration tests start their own Postgres and LocalStack containers (Testcontainers), so Docker
must be running. They reuse the same init script as docker-compose.

## Local AWS resources
| Type | Name | Purpose |
|---|---|---|
| SQS | `pigeon-send-queue` | Requests waiting to be sent; redrives to `pigeon-send-dlq` after 3 attempts |
| SQS | `pigeon-events-queue` | SES delivery events; redrives to `pigeon-events-dlq` after 3 attempts |
| SNS | `pigeon-events-topic` | SES event destination, subscribed by `pigeon-events-queue` |
| SES | `notify.pigeon.local` | Sending domain identity |
| SES | `pigeon-txn-prod`, `pigeon-bulk-prod` | One configuration set per traffic stream |

LocalStack is pinned to `4.14`, the last release that runs without a LocalStack account.

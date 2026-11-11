# Pigeon database schema

Flyway owns the schema (`src/main/resources/db/migration`). Hibernate only validates it.

| Migration | Contents |
|---|---|
| `V1__create_templates.sql` | `template`, `template_version` |
| `V2__create_messaging.sql` | `message_record`, `message_event`, `idempotency_key`, `outbox` |
| `V3__create_suppression.sql` | `suppression`, `soft_bounce`, `processed_event` |
| `V4__seed_templates.sql` | 3 templates; `enrollment-confirmation` has v1 (retired) and v2 (active) |

```mermaid
erDiagram
    template ||--|{ template_version : "has versions"
    template_version ||--o{ message_record : "rendered as"
    message_record ||--o{ message_event : "history"
    message_record ||--o| idempotency_key : "deduplicated by"
    message_record ||--o{ outbox : "published via"

    template {
        varchar id PK
        varchar name
        varchar from_address
        int active_version FK
    }
    template_version {
        varchar template_id PK
        int version PK
        text subject
        text html_body
        text text_body
        text_array required_fields
    }
    message_record {
        uuid id PK
        varchar correlation_id
        varchar template_id FK
        int template_version FK
        varchar recipient
        varchar stream
        varchar status
        jsonb variables
        varchar ses_message_id UK
        int attempt_count
    }
    message_event {
        bigint id PK
        uuid message_id FK
        varchar event_type
        jsonb detail
        timestamptz occurred_at
    }
    idempotency_key {
        varchar idem_key PK
        char request_hash
        uuid message_id FK
        timestamptz expires_at
    }
    outbox {
        bigint id PK
        uuid message_id FK
        varchar destination
        jsonb payload
        varchar status
    }
    suppression {
        varchar address PK
        varchar reason
        varchar source
        text detail
    }
    soft_bounce {
        bigint id PK
        varchar address
        varchar ses_message_id
        timestamptz occurred_at
    }
    processed_event {
        varchar event_key PK
        varchar event_type
    }
```

`suppression`, `soft_bounce` and `processed_event` stand alone on purpose: suppression is keyed by
address, not by message, so it also holds addresses imported from another platform or added by hand.

## Rules the database enforces
- A message always points at a real template **version**, so you can prove what was sent.
- `template.active_version` must exist (deferred foreign key).
- Status, stream and suppression reason are limited to known values (check constraints).
- Email addresses are stored lower-cased, so `Dana@Example.com` and `dana@example.com` are one address.
- Template ids are kebab-case (`payment-receipt`).

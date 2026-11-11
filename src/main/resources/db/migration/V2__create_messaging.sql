-- Pigeon V2: the life of one email, plus the two tables that make sending safe:
-- idempotency_key (no duplicates on retry) and outbox (no lost or phantom queue messages).

create table message_record (
    id                 uuid         primary key,           -- returned to callers as requestId
    correlation_id     varchar(100) not null,              -- ties API, worker and event logs together
    template_id        varchar(64)  not null,
    template_version   integer      not null,
    recipient          varchar(320) not null,              -- stored lower-cased
    stream             varchar(20)  not null,
    status             varchar(20)  not null,
    variables          jsonb        not null default '{}', -- merge values; contains PII, never logged
    configuration_set  varchar(64),
    ses_message_id     varchar(100) unique,                -- set once SES accepts the email
    attempt_count      integer      not null default 0,
    last_error         text,
    created_at         timestamptz  not null default now(),
    updated_at         timestamptz  not null default now(),
    sent_at            timestamptz,
    constraint fk_message_template_version
        foreign key (template_id, template_version) references template_version (template_id, version),
    constraint ck_message_stream check (stream in ('TRANSACTIONAL', 'BULK')),
    constraint ck_message_status check (status in
        ('ACCEPTED', 'QUEUED', 'SENT', 'DELIVERED', 'BOUNCED', 'COMPLAINED', 'SUPPRESSED', 'FAILED')),
    constraint ck_message_recipient_lower check (recipient = lower(recipient))
);

create index ix_message_status_created on message_record (status, created_at);
create index ix_message_recipient      on message_record (recipient);
create index ix_message_correlation    on message_record (correlation_id);

-- Append-only history: every status change and every SES event for a message.
create table message_event (
    id           bigserial    primary key,
    message_id   uuid         not null references message_record (id),
    event_type   varchar(40)  not null,   -- ACCEPTED, QUEUED, SENT, DELIVERY, BOUNCE, COMPLAINT, SUPPRESSED, FAILED ...
    detail       jsonb,                   -- e.g. bounce type, SMTP diagnostic code
    occurred_at  timestamptz  not null,   -- when it happened (SES timestamp for SES events)
    recorded_at  timestamptz  not null default now()
);

create index ix_message_event_message on message_event (message_id, occurred_at);

-- Same Idempotency-Key + same body  -> return the original message, send nothing.
-- Same Idempotency-Key + different body -> reject (the caller has a bug).
create table idempotency_key (
    idem_key      varchar(200) primary key,
    request_hash  char(64)     not null,  -- SHA-256 of the normalized request body
    message_id    uuid         not null references message_record (id),
    created_at    timestamptz  not null default now(),
    expires_at    timestamptz  not null   -- keys are kept for a window (e.g. 24h), then purged
);

create index ix_idempotency_expires on idempotency_key (expires_at);

-- Transactional outbox: the message row and its "please send" row commit together.
-- A relay publishes PENDING rows to SQS, so a crash can never leave one without the other.
create table outbox (
    id            bigserial    primary key,
    message_id    uuid         not null references message_record (id),
    destination   varchar(100) not null,  -- queue name, e.g. pigeon-send-queue
    payload       jsonb        not null,
    status        varchar(20)  not null default 'PENDING',
    attempts      integer      not null default 0,
    last_error    text,
    created_at    timestamptz  not null default now(),
    published_at  timestamptz,
    constraint ck_outbox_status check (status in ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- Partial index: the relay only ever scans pending rows, oldest first.
create index ix_outbox_pending on outbox (created_at) where status = 'PENDING';

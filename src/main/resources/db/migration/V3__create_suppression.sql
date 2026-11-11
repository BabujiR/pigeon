-- Pigeon V3: reputation protection.

-- Addresses Pigeon must never send to again. Checked before every send.
create table suppression (
    address     varchar(320) primary key,   -- lower-cased
    reason      varchar(30)  not null,
    source      varchar(100) not null,      -- e.g. ses-event:<sesMessageId>, api:manual, import:legacy-platform
    detail      text,                       -- e.g. "smtp; 550 5.1.1 user unknown"
    created_at  timestamptz  not null default now(),
    constraint ck_suppression_reason check (reason in
        ('HARD_BOUNCE', 'COMPLAINT', 'REPEATED_SOFT_BOUNCE', 'ACCOUNT_SUPPRESSION_LIST', 'MANUAL')),
    constraint ck_suppression_address_lower check (address = lower(address))
);

-- One row per transient (soft) bounce. Counting rows in a time window
-- ("3 in 7 days") is simpler and more auditable than keeping a counter.
create table soft_bounce (
    id               bigserial    primary key,
    address          varchar(320) not null,
    ses_message_id   varchar(100),
    bounce_sub_type  varchar(50),           -- MailboxFull, MessageTooLarge, General ...
    occurred_at      timestamptz  not null,
    constraint ck_soft_bounce_address_lower check (address = lower(address))
);

create index ix_soft_bounce_address_time on soft_bounce (address, occurred_at);

-- SNS delivers at least once. Recording each SNS MessageId lets the event processor
-- skip duplicates, so a redelivered bounce never double-counts.
create table processed_event (
    event_key     varchar(200) primary key,   -- SNS MessageId
    event_type    varchar(40)  not null,
    processed_at  timestamptz  not null default now()
);

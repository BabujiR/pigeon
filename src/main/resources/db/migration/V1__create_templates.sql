-- Pigeon V1: versioned templates.
-- A template is the stable name callers use ("enrollment-confirmation").
-- A template_version is immutable once a message has used it, so we can always prove
-- exactly which wording a member received. Publishing new wording = insert a new version
-- and move active_version; never update an old version in place.

create table template (
    id              varchar(64)  primary key,              -- caller-facing id, e.g. enrollment-confirmation
    name            varchar(200) not null,
    from_address    varchar(320) not null,                 -- must belong to a verified SES identity
    active_version  integer      not null,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    constraint ck_template_id_format check (id ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

create table template_version (
    template_id      varchar(64) not null references template (id),
    version          integer     not null check (version > 0),
    subject          text        not null,
    html_body        text        not null,
    text_body        text        not null,                 -- always send a text part: better deliverability and accessibility
    required_fields  text[]      not null default '{}',    -- merge fields the caller must supply
    created_at       timestamptz not null default now(),
    primary key (template_id, version)
);

-- active_version must point at a real version. Deferred, so a template and its first
-- version can be inserted in the same transaction in either order.
alter table template
    add constraint fk_template_active_version
    foreign key (id, active_version) references template_version (template_id, version)
    deferrable initially deferred;

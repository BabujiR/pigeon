-- Pigeon V6: indexes for the reputation metrics (Phase 5).
-- Metrics count messages by sent_at window and look up their outcome events by type.
create index ix_message_sent_at on message_record (sent_at) where sent_at is not null;
create index ix_message_event_type on message_event (message_id, event_type);
create index ix_suppression_created on suppression (created_at desc);

-- Pigeon V5: send lease.
-- SQS delivers at least once, so two workers can receive the same message at the same time.
-- Before calling SES a worker claims the message until send_lease_until. A second worker that
-- finds an active lease backs off instead of sending a duplicate email.
alter table message_record add column send_lease_until timestamptz;

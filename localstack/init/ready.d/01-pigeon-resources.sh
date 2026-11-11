#!/bin/bash
# Pigeon - creates every local AWS resource the platform needs.
# LocalStack runs this automatically from /etc/localstack/init/ready.d once its services are up.
# Must keep LF line endings (enforced by .gitattributes) or bash fails with "\r" errors.
set -euo pipefail

REGION="${AWS_DEFAULT_REGION:-us-east-1}"
ACCOUNT="000000000000"          # LocalStack's fixed account id
DOMAIN="notify.pigeon.local"
MAX_RECEIVES=3                  # after 3 failed attempts a message moves to its DLQ
TMP="$(mktemp -d)"

log() { echo "[pigeon-init] $*"; }
queue_arn() { echo "arn:aws:sqs:${REGION}:${ACCOUNT}:$1"; }

# Dead-letter queues keep failed messages for 14 days so operations can inspect and replay them.
create_dlq() {
  awslocal sqs create-queue --queue-name "$1" \
    --attributes MessageRetentionPeriod=1209600 >/dev/null
  log "SQS   $1  (dead-letter queue, 14-day retention)"
}

# Work queues: visibility timeout must exceed the time a consumer needs for one message,
# otherwise SQS hands the same message to a second consumer while the first is still working.
create_queue() {
  local name="$1" dlq="$2" visibility="$3"
  cat > "${TMP}/${name}.json" <<EOF
{
  "VisibilityTimeout": "${visibility}",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$(queue_arn "$dlq")\",\"maxReceiveCount\":\"${MAX_RECEIVES}\"}"
}
EOF
  awslocal sqs create-queue --queue-name "$name" --attributes "file://${TMP}/${name}.json" >/dev/null
  log "SQS   $name  (visibility ${visibility}s, redrive to $dlq after ${MAX_RECEIVES} attempts)"
}

log "Creating Pigeon resources in ${REGION}"

# --- SQS ---------------------------------------------------------------------
create_dlq   pigeon-send-dlq
create_dlq   pigeon-events-dlq
create_queue pigeon-send-queue   pigeon-send-dlq   60
create_queue pigeon-events-queue pigeon-events-dlq 30

# --- SNS: SES events fan out from this topic ------------------------------
TOPIC_ARN="$(awslocal sns create-topic --name pigeon-events-topic --query TopicArn --output text)"
log "SNS   pigeon-events-topic  (${TOPIC_ARN})"

# On real AWS the queue must allow the topic to send to it. LocalStack does not enforce
# this, but we set it anyway so the setup matches production.
cat > "${TMP}/events-policy.json" <<EOF
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$(queue_arn pigeon-events-queue)\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"${TOPIC_ARN}\"}}}]}"
}
EOF
EVENTS_QUEUE_URL="$(awslocal sqs get-queue-url --queue-name pigeon-events-queue --query QueueUrl --output text)"
awslocal sqs set-queue-attributes \
  --queue-url "$EVENTS_QUEUE_URL" \
  --attributes "file://${TMP}/events-policy.json" >/dev/null

awslocal sns subscribe --topic-arn "$TOPIC_ARN" --protocol sqs \
  --notification-endpoint "$(queue_arn pigeon-events-queue)" >/dev/null
log "SNS   subscription pigeon-events-topic -> pigeon-events-queue"

# --- SES ---------------------------------------------------------------------
# On AWS a domain identity is verified through DNS (Easy DKIM CNAMEs). LocalStack verifies instantly.
awslocal ses verify-domain-identity --domain "$DOMAIN" >/dev/null
log "SES   domain identity ${DOMAIN}"
for sender in enrollment security billing; do
  awslocal ses verify-email-identity --email-address "${sender}@${DOMAIN}" >/dev/null
  log "SES   email identity ${sender}@${DOMAIN}"
done

# Two configuration sets = two traffic streams with separate reputation.
# No SES event destination is attached here: locally, the LocalEventSimulator (Phase 4)
# publishes SES-format events to pigeon-events-topic instead, so events are never doubled.
for cs in pigeon-txn-prod pigeon-bulk-prod; do
  if awslocal ses create-configuration-set --configuration-set "Name=${cs}" >/dev/null 2>&1; then
    log "SES   configuration set ${cs}"
  else
    log "SES   configuration set ${cs} (already exists)"
  fi
done

# --- Summary -----------------------------------------------------------------
log "Summary"
log "  queues:     $(awslocal sqs list-queues --query 'QueueUrls' --output text | tr '\t' ' ')"
log "  topics:     $(awslocal sns list-topics --query 'Topics[].TopicArn' --output text | tr '\t' ' ')"
log "  identities: $(awslocal ses list-identities --query 'Identities' --output text | tr '\t' ' ')"
log "  config sets: $(awslocal ses list-configuration-sets --query 'ConfigurationSets[].Name' --output text | tr '\t' ' ')"
rm -rf "$TMP"
log "PIGEON INIT COMPLETE"

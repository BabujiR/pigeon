# Running Pigeon on AWS

This takes Pigeon from LocalStack to a real AWS account. Code does not change: the differences are
configuration, AWS resources and DNS. Commands use AWS CLI v2 in PowerShell, region `us-east-1`
and account `111122223333`; replace them. Check current SES pricing and quotas before you start.

## What changes from local

| | Local | AWS |
|---|---|---|
| Profile | `local` | `json` (structured logs), no `local` |
| `spring.cloud.aws.endpoint` | `http://localhost:4566` | unset (real AWS endpoints) |
| Credentials | `test` / `test` | IAM role of the ECS task (never keys in config) |
| `pigeon.ses.api` | `v1` (LocalStack free tier has no SES v2) | unset, so `v2` |
| `pigeon.events.simulator.enabled` | `true` | unset, so `false`: real SES events arrive through SNS |
| Database | Postgres container | Amazon RDS for PostgreSQL 16 |

Application settings in AWS (environment variables on the task):

```
SPRING_PROFILES_ACTIVE=json
AWS_REGION=us-east-1
PIGEON_DB_URL=jdbc:postgresql://pigeon-db.xxxxxxxx.us-east-1.rds.amazonaws.com:5432/pigeon
PIGEON_DB_USERNAME=pigeon_app
PIGEON_DB_PASSWORD=<from AWS Secrets Manager, injected by ECS>
```

## 1. Sandbox first

New SES accounts start in the sandbox: you can send only to verified addresses, up to 200
messages a day at 1 per second. That is enough to prove the whole loop.

```powershell
aws sesv2 get-account --query "{production:ProductionAccessEnabled, quota:SendQuota}"
aws sesv2 create-email-identity --email-identity you@yourcompany.com   # click the link SES emails you
```

## 2. Sending domain with Easy DKIM and custom MAIL FROM

```powershell
aws sesv2 create-email-identity --email-identity notify.acmebenefits.com `
  --query "DkimAttributes.Tokens"
# -> three tokens: create the CNAME records from DNS.md

aws sesv2 put-email-identity-mail-from-attributes --email-identity notify.acmebenefits.com `
  --mail-from-domain bounce.notify.acmebenefits.com --behavior-on-mx-failure USE_DEFAULT_VALUE
# -> create the MX and SPF records from DNS.md, then the DMARC record
```

Wait until `aws sesv2 get-email-identity --email-identity notify.acmebenefits.com` shows
`VerifiedForSendingStatus: true` and DKIM `SUCCESS`. Update the templates' `from_address` (a new
Flyway migration) to addresses on this domain.

## 3. Event topic and queues

Same names as local, so `application.yml` needs no change.

```powershell
$topic = aws sns create-topic --name pigeon-events-topic --query TopicArn --output text

# Let SES publish to the topic (only from this account)
aws sns set-topic-attributes --topic-arn $topic --attribute-name Policy --attribute-value (@"
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ses.amazonaws.com"},
 "Action":"sns:Publish","Resource":"$topic","Condition":{"StringEquals":{"AWS:SourceAccount":"111122223333"}}}]}
"@ -replace "`r?`n","")

aws sqs create-queue --queue-name pigeon-send-dlq   --attributes MessageRetentionPeriod=1209600
aws sqs create-queue --queue-name pigeon-events-dlq --attributes MessageRetentionPeriod=1209600
# Then create pigeon-send-queue and pigeon-events-queue with the same RedrivePolicy
# (maxReceiveCount 3) and VisibilityTimeout values as localstack/init/ready.d/01-pigeon-resources.sh,
# set the pigeon-events-queue policy to allow sns.amazonaws.com from $topic, and subscribe:
aws sns subscribe --topic-arn $topic --protocol sqs `
  --notification-endpoint arn:aws:sqs:us-east-1:111122223333:pigeon-events-queue
```

Pigeon accepts the SNS envelope (the default) or raw message delivery; either works.

## 4. Configuration sets with event destinations

```powershell
foreach ($cs in "pigeon-txn-prod", "pigeon-bulk-prod") {
  aws sesv2 create-configuration-set --configuration-set-name $cs `
    --reputation-options ReputationMetricsEnabled=true `
    --sending-options SendingEnabled=true `
    --delivery-options TlsPolicy=REQUIRE
}
```

`event-destination.json`:
```json
{
  "Enabled": true,
  "MatchingEventTypes": ["SEND", "DELIVERY", "BOUNCE", "COMPLAINT", "REJECT", "DELIVERY_DELAY", "RENDERING_FAILURE"],
  "SnsDestination": { "TopicArn": "arn:aws:sns:us-east-1:111122223333:pigeon-events-topic" }
}
```
```powershell
foreach ($cs in "pigeon-txn-prod", "pigeon-bulk-prod") {
  aws sesv2 create-configuration-set-event-destination --configuration-set-name $cs `
    --event-destination-name pigeon-events --event-destination file://event-destination.json
}
aws sesv2 put-account-suppression-attributes --suppressed-reasons BOUNCE COMPLAINT
```

The account-level suppression list is a second safety net behind Pigeon's own list; its bounces
arrive as `OnAccountSuppressionList` and Pigeon records them as `ACCOUNT_SUPPRESSION_LIST`.

## 5. IAM: least privilege for the task role

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SendOnlyAsOurDomainThroughOurConfigurationSets",
      "Effect": "Allow",
      "Action": ["ses:SendEmail"],
      "Resource": [
        "arn:aws:ses:us-east-1:111122223333:identity/notify.acmebenefits.com",
        "arn:aws:ses:us-east-1:111122223333:configuration-set/pigeon-txn-prod",
        "arn:aws:ses:us-east-1:111122223333:configuration-set/pigeon-bulk-prod"
      ],
      "Condition": { "StringLike": { "ses:FromAddress": "*@notify.acmebenefits.com" } }
    },
    {
      "Sid": "OwnQueuesOnly",
      "Effect": "Allow",
      "Action": ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage",
                 "sqs:ChangeMessageVisibility", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"],
      "Resource": "arn:aws:sqs:us-east-1:111122223333:pigeon-*"
    },
    {
      "Sid": "HealthCheckReadsTopic",
      "Effect": "Allow",
      "Action": ["sns:ListTopics", "sns:ListSubscriptionsByTopic"],
      "Resource": "*"
    }
  ]
}
```

No `ses:*`, no `sqs:*`, no access keys. Database credentials come from Secrets Manager.

## 6. Database

Amazon RDS for PostgreSQL 16 in private subnets, encryption at rest on, automated backups on,
Multi-AZ for production. Create database `pigeon` and a `pigeon_app` user. Flyway creates and
migrates the schema when the first task starts.

## 7. Run it

Build the image (`mvn spring-boot:build-image` or a Dockerfile), push to Amazon ECR, and run it on
ECS Fargate behind an Application Load Balancer health-checking `/actuator/health`. Two or more
tasks are fine: the outbox relay uses `SKIP LOCKED`, the sender uses leases, and the event processor
is idempotent. Keep `pigeon.sender.rate-per-second` times the task count under the account's
`MaxSendRate`.

Smoke test in the sandbox: send to your verified address and to `bounce@simulator.amazonses.com`.
The first should end `DELIVERED`, the second `BOUNCED` with the address suppressed, the same as locally,
but now with real SES events. The Pigeon Console works unchanged against the deployed URL.

## 8. Alarms

SES publishes the account's own reputation to CloudWatch (`AWS/SES`, `Reputation.BounceRate` and
`Reputation.ComplaintRate`, as fractions). Alarm well below the SES review levels:

```powershell
aws cloudwatch put-metric-alarm --alarm-name pigeon-ses-bounce-rate --namespace AWS/SES `
  --metric-name Reputation.BounceRate --statistic Average --period 3600 --evaluation-periods 1 `
  --threshold 0.02 --comparison-operator GreaterThanOrEqualToThreshold --alarm-actions <sns-topic-for-on-call>
```

Also alarm on: `ApproximateNumberOfMessagesVisible` of both DLQs above 0, age of the oldest message
on `pigeon-send-queue`, and Pigeon's own gauges at `/actuator/prometheus`
(`pigeon_reputation_status`, `pigeon_outbox_pending`) if you scrape them.

## 9. Production access

```powershell
aws sesv2 put-account-details --production-access-enabled --mail-type TRANSACTIONAL `
  --website-url https://www.acmebenefits.com `
  --use-case-description "Transactional enrollment, billing and security emails to members who have an account. Bounces and complaints are processed automatically through SNS and suppressed; recipients are never purchased lists."
```

AWS reviews the request, usually within a day. Describe your bounce and complaint handling
concretely; that is what reviewers look for.

## 10. Optional, at volume

- **Virtual Deliverability Manager** (`aws sesv2 put-account-vdm-attributes --vdm-attributes VdmEnabled=ENABLED`)
  for inbox-placement insight per mailbox provider.
- **Dedicated IPs**, managed (`aws sesv2 create-dedicated-ip-pool --pool-name pigeon-txn --scaling-mode MANAGED`)
  and attached through the configuration set's `SendingPoolName`, once volume is steady and high.
  Shared IPs are better below that; dedicated IPs need warm-up.
- **Migrating from an old platform:** import its suppression list into Pigeon
  (`POST /v1/suppressions`) before the first send, then move traffic stream by stream.

# DNS for Pigeon: SPF, DKIM, DMARC and custom MAIL FROM

Locally, LocalStack treats every identity as verified, so none of this is needed. On AWS, these
records decide whether mailbox providers trust Pigeon's email. The examples use
`notify.acmebenefits.com` as the sending domain and `us-east-1` as the SES region; replace both.

## Why a subdomain per stream

| Stream | From domain | Configuration set |
|---|---|---|
| Transactional (receipts, enrollment, password resets) | `notify.acmebenefits.com` | `pigeon-txn-prod` |
| Bulk (notices, newsletters) | `news.acmebenefits.com` | `pigeon-bulk-prod` |

Mailbox providers build reputation per domain. Separate subdomains mean a complaint-heavy bulk
send cannot drag down the domain that password resets come from, and the organizational domain
`acmebenefits.com` (used by people's own mailboxes) is not put at risk by either.

## The records

All three DKIM tokens come from SES when the identity is created (see `AWS-DEPLOY.md`, step 2).

| Purpose | Type | Name | Value |
|---|---|---|---|
| DKIM key 1 | CNAME | `<token1>._domainkey.notify.acmebenefits.com` | `<token1>.dkim.amazonses.com` |
| DKIM key 2 | CNAME | `<token2>._domainkey.notify.acmebenefits.com` | `<token2>.dkim.amazonses.com` |
| DKIM key 3 | CNAME | `<token3>._domainkey.notify.acmebenefits.com` | `<token3>.dkim.amazonses.com` |
| MAIL FROM bounces | MX | `bounce.notify.acmebenefits.com` | `10 feedback-smtp.us-east-1.amazonses.com` |
| SPF | TXT | `bounce.notify.acmebenefits.com` | `"v=spf1 include:amazonses.com ~all"` |
| DMARC | TXT | `_dmarc.notify.acmebenefits.com` | `"v=DMARC1; p=none; rua=mailto:dmarc-reports@acmebenefits.com; adkim=r; aspf=r"` |

Repeat the same set for `news.acmebenefits.com` with its own tokens.

### What each record does

- **DKIM (Easy DKIM).** SES signs every message with a private key; the CNAMEs point receivers at
  the public keys, which AWS rotates for you. A valid signature with `d=notify.acmebenefits.com`
  proves the message came from you and was not changed in transit.
- **Custom MAIL FROM.** By default the envelope sender (Return-Path) is an `amazonses.com` address.
  Setting it to `bounce.notify.acmebenefits.com` makes bounces come back through your domain and,
  more importantly, makes SPF pass **for your domain** instead of for Amazon's.
- **SPF.** Lists who may send for the MAIL FROM domain. It goes on `bounce.notify…`, not on the
  From domain, because SPF is checked against the envelope sender.
- **DMARC.** Tells receivers what to do when a message claiming to be from your domain fails
  authentication, and where to send daily aggregate reports (`rua`).

## Alignment: why SPF and DKIM can both pass and DMARC still fail

DMARC passes only if SPF **or** DKIM passes **and** the domain that passed matches the visible
From domain (same organizational domain, with relaxed alignment `adkim=r; aspf=r`).

| Setup | SPF result | DKIM result | DMARC |
|---|---|---|---|
| SES defaults, no Easy DKIM, no MAIL FROM | pass for `amazonses.com` (not aligned) | `d=amazonses.com` (not aligned) | **fail** |
| Easy DKIM only | pass for `amazonses.com` (not aligned) | `d=notify.acmebenefits.com` (aligned) | pass |
| Easy DKIM + custom MAIL FROM (Pigeon) | pass for `bounce.notify.acmebenefits.com` (aligned) | aligned | pass, twice over |

Having both aligned means one can break (a DNS change, a forwarding hop that breaks SPF) and mail
still passes. The Pigeon Lab's "Domain authentication" tab lets you toggle these and see the result.

## Rolling out DMARC safely

1. Start with `p=none` and a `rua` address. Nothing is blocked; you receive reports.
2. Read the reports for 2 to 4 weeks. Every legitimate sender of the domain (Pigeon, but also a
   CRM, a ticketing tool, a payroll system) must show aligned passes. Fix any that do not.
3. Move to `p=quarantine; pct=25`, then raise `pct` to 100.
4. Move to `p=reject`. Spoofed mail using your domain is now refused.

Jumping straight to `p=reject` is a classic outage: a forgotten legitimate sender silently stops
reaching customers.

## Mailbox provider requirements for bulk senders

Gmail and Yahoo require senders of roughly 5,000 or more messages a day to have:
SPF and DKIM, a DMARC record (at least `p=none`), alignment of the From domain with SPF or DKIM,
one-click unsubscribe (RFC 8058 `List-Unsubscribe` and `List-Unsubscribe-Post`) on marketing mail,
and a spam complaint rate kept under 0.3% (aim for under 0.1%). Pigeon adds the unsubscribe
headers to every BULK message (see `SesEmailFactory`). Check the providers' current guidelines
before go-live; they change.

## Checking the records (PowerShell)

```powershell
Resolve-DnsName -Type CNAME <token1>._domainkey.notify.acmebenefits.com
Resolve-DnsName -Type MX    bounce.notify.acmebenefits.com
Resolve-DnsName -Type TXT   bounce.notify.acmebenefits.com
Resolve-DnsName -Type TXT   _dmarc.notify.acmebenefits.com

# What SES thinks of the identity
aws sesv2 get-email-identity --email-identity notify.acmebenefits.com `
  --query "{verified:VerifiedForSendingStatus, dkim:DkimAttributes.Status, mailFrom:MailFromAttributes.MailFromDomainStatus}"
```

Then send a real message to a Gmail address you own, open it, choose **Show original**, and look
for `SPF: PASS`, `DKIM: PASS` and `DMARC: PASS` with your domain in each.

## Optional extras

- **BIMI** shows your logo in Gmail and Apple Mail. It needs DMARC at `quarantine` or `reject` and
  usually a Verified Mark Certificate.
- **Require TLS** on the configuration set (`TlsPolicy=REQUIRE`) refuses to deliver over an
  unencrypted connection. Sensible for benefits and billing mail.

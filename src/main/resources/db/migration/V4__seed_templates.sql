-- Pigeon V4: sample templates.
-- enrollment-confirmation has two versions to show versioning: v1 stays for audit, v2 is active.
-- Sender addresses match the SES identities created by the LocalStack init script.

insert into template (id, name, from_address, active_version) values
    ('enrollment-confirmation', 'Enrollment confirmation', 'enrollment@notify.pigeon.local', 2),
    ('password-reset',          'Password reset',          'security@notify.pigeon.local',   1),
    ('payment-receipt',         'Premium payment receipt', 'billing@notify.pigeon.local',    1);

-- ---------------------------------------------------------------------------
-- enrollment-confirmation v1 (retired)
insert into template_version (template_id, version, subject, required_fields, text_body, html_body) values
('enrollment-confirmation', 1,
 'Your {{planName}} enrollment is confirmed',
 '{firstName,planName,effectiveDate,memberId}',
$$Hi {{firstName}},

You are enrolled in {{planName}}. Coverage starts {{effectiveDate}}.
Your member ID is {{memberId}}.

Acme Benefits$$,
$$<p>Hi {{firstName}},</p>
<p>You are enrolled in <strong>{{planName}}</strong>. Coverage starts {{effectiveDate}}.</p>
<p>Your member ID is {{memberId}}.</p>
<p>Acme Benefits</p>$$);

-- enrollment-confirmation v2 (active): adds a help line and the Pigeon footer
insert into template_version (template_id, version, subject, required_fields, text_body, html_body) values
('enrollment-confirmation', 2,
 'Your {{planName}} enrollment is confirmed',
 '{firstName,planName,effectiveDate,memberId}',
$$Hi {{firstName}},

You are enrolled in {{planName}}. Coverage starts {{effectiveDate}}.
Your member ID is {{memberId}}. Keep it handy when you visit a provider.

Questions? Reply to this email or call the number on your member card.

Acme Benefits

--
Sent by Pigeon. Trusted delivery, built on AWS SES.
This is a service message about your coverage.$$,
$$<!doctype html>
<html><body style="margin:0;padding:24px;font-family:Arial,Helvetica,sans-serif;color:#1b2430;background:#f4f6f9">
<div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #d9dee6;border-radius:6px;padding:24px">
<p>Hi {{firstName}},</p>
<p>You are enrolled in <strong>{{planName}}</strong>. Coverage starts <strong>{{effectiveDate}}</strong>.</p>
<p>Your member ID is <strong>{{memberId}}</strong>. Keep it handy when you visit a provider.</p>
<p>Questions? Reply to this email or call the number on your member card.</p>
<p>Acme Benefits</p>
</div>
<p style="max-width:560px;margin:16px auto 0;font-size:12px;color:#6b7686">Sent by Pigeon. Trusted delivery, built on AWS SES.<br>This is a service message about your coverage.</p>
</body></html>$$);

-- ---------------------------------------------------------------------------
-- password-reset v1
insert into template_version (template_id, version, subject, required_fields, text_body, html_body) values
('password-reset', 1,
 'Reset your Acme Benefits password',
 '{firstName,resetLink,expiresMinutes}',
$$Hi {{firstName}},

Use this link to reset your password. It expires in {{expiresMinutes}} minutes.
{{resetLink}}

If you did not ask for this, ignore this email. Your password stays the same.

--
Sent by Pigeon. Trusted delivery, built on AWS SES.
This is a security message about your account.$$,
$$<!doctype html>
<html><body style="margin:0;padding:24px;font-family:Arial,Helvetica,sans-serif;color:#1b2430;background:#f4f6f9">
<div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #d9dee6;border-radius:6px;padding:24px">
<p>Hi {{firstName}},</p>
<p>Use the button below to reset your password. It expires in {{expiresMinutes}} minutes.</p>
<p><a href="{{resetLink}}" style="display:inline-block;background:#2b4fc4;color:#ffffff;padding:10px 18px;border-radius:4px;text-decoration:none">Reset password</a></p>
<p style="font-size:13px;color:#6b7686">If the button does not work, copy this link: {{resetLink}}</p>
<p>If you did not ask for this, ignore this email. Your password stays the same.</p>
</div>
<p style="max-width:560px;margin:16px auto 0;font-size:12px;color:#6b7686">Sent by Pigeon. Trusted delivery, built on AWS SES.<br>This is a security message about your account.</p>
</body></html>$$);

-- ---------------------------------------------------------------------------
-- payment-receipt v1
insert into template_version (template_id, version, subject, required_fields, text_body, html_body) values
('payment-receipt', 1,
 'Payment received: {{amount}}',
 '{firstName,amount,paidOn,last4}',
$$Hi {{firstName}},

We received your premium payment of {{amount}} on {{paidOn}} from the card ending {{last4}}.

Acme Benefits Billing

--
Sent by Pigeon. Trusted delivery, built on AWS SES.
This is a receipt for a payment on your account.$$,
$$<!doctype html>
<html><body style="margin:0;padding:24px;font-family:Arial,Helvetica,sans-serif;color:#1b2430;background:#f4f6f9">
<div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #d9dee6;border-radius:6px;padding:24px">
<p>Hi {{firstName}},</p>
<p>We received your premium payment.</p>
<table style="border-collapse:collapse;font-size:14px">
<tr><td style="padding:4px 16px 4px 0;color:#6b7686">Amount</td><td><strong>{{amount}}</strong></td></tr>
<tr><td style="padding:4px 16px 4px 0;color:#6b7686">Date</td><td>{{paidOn}}</td></tr>
<tr><td style="padding:4px 16px 4px 0;color:#6b7686">Card</td><td>ending {{last4}}</td></tr>
</table>
<p>Acme Benefits Billing</p>
</div>
<p style="max-width:560px;margin:16px auto 0;font-size:12px;color:#6b7686">Sent by Pigeon. Trusted delivery, built on AWS SES.<br>This is a receipt for a payment on your account.</p>
</body></html>$$);

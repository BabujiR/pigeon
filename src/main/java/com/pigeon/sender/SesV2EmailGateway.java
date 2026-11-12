package com.pigeon.sender;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/** Production path: SES API v2 SendEmail, with custom headers, tags and configuration set. */
@Component
@ConditionalOnProperty(prefix = "pigeon.ses", name = "api", havingValue = "v2", matchIfMissing = true)
public class SesV2EmailGateway implements EmailGateway {

    private final SesV2Client ses;

    public SesV2EmailGateway(SesV2Client ses) {
        this.ses = ses;
    }

    @Override
    public String send(SendEmailRequest request) {
        return ses.sendEmail(request).messageId();
    }

    @Override
    public String name() {
        return "sesv2";
    }
}

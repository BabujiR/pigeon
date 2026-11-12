package com.pigeon.sender;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.MessageTag;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;

/**
 * Local path: SES API v1 SendEmail, selected with {@code pigeon.ses.api=v1}.
 *
 * <p>Why it exists: LocalStack's free edition emulates SES v1 but answers every SES v2 call
 * with HTTP 501. Production keeps v2. This adapter translates the v2 request field by field:
 * From, To, subject, HTML and text parts, configuration set and tags.
 *
 * <p>Known gap: v1 SendEmail cannot carry custom headers, so List-Unsubscribe is not sent
 * locally. The headers are still built (and unit-tested) on the v2 request; this adapter logs
 * once that they are dropped.
 */
@Component
@ConditionalOnProperty(prefix = "pigeon.ses", name = "api", havingValue = "v1")
public class SesV1EmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(SesV1EmailGateway.class);

    private final SesClient ses;
    private volatile boolean headersWarned;

    public SesV1EmailGateway(SesClient ses) {
        this.ses = ses;
    }

    @Override
    public String send(software.amazon.awssdk.services.sesv2.model.SendEmailRequest request) {
        List<MessageHeader> headers = request.content().simple().headers();
        if (!headersWarned && headers != null && !headers.isEmpty()) {
            headersWarned = true;
            log.warn("SES v1 SendEmail cannot send custom headers; dropping {} locally. Production uses SES v2.",
                    headers.stream().map(MessageHeader::name).toList());
        }
        return ses.sendEmail(toV1(request)).messageId();
    }

    @Override
    public String name() {
        return "sesv1";
    }

    /** Field-by-field translation from the v2 model. Package-private for tests. */
    static SendEmailRequest toV1(software.amazon.awssdk.services.sesv2.model.SendEmailRequest v2) {
        var simple = v2.content().simple();
        return SendEmailRequest.builder()
                .source(v2.fromEmailAddress())
                .destination(Destination.builder().toAddresses(v2.destination().toAddresses()).build())
                .message(Message.builder()
                        .subject(content(simple.subject()))
                        .body(Body.builder()
                                .html(content(simple.body().html()))
                                .text(content(simple.body().text()))
                                .build())
                        .build())
                .configurationSetName(v2.configurationSetName())
                .tags(v2.emailTags().stream()
                        .map(t -> MessageTag.builder().name(t.name()).value(t.value()).build())
                        .toList())
                .build();
    }

    private static Content content(software.amazon.awssdk.services.sesv2.model.Content c) {
        return c == null ? null : Content.builder().data(c.data()).charset(c.charset()).build();
    }
}

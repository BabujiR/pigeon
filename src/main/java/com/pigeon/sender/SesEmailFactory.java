package com.pigeon.sender;

import com.pigeon.message.MessageRecord;
import com.pigeon.message.MessageStream;
import com.pigeon.template.RenderedEmail;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;
import software.amazon.awssdk.services.sesv2.model.MessageTag;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Builds the SES v2 SendEmail request for one message.
 *
 * <p>Review notes:
 * <ul>
 *   <li><b>Configuration set</b> comes from the message (chosen at accept time by stream), so the
 *       email uses the right IP pool and its events go to the right destination.</li>
 *   <li><b>Message tags</b> come back on every SES event. {@code messageRecordId} lets the event
 *       processor find our row without a lookup by SES id. Tag values allow only letters, digits,
 *       underscore and hyphen, so anything else is replaced.</li>
 *   <li><b>BULK mail carries one-click unsubscribe headers</b> (RFC 8058), which Gmail and Yahoo
 *       require from bulk senders. Transactional mail (receipts, resets) does not.</li>
 * </ul>
 */
@Component
public class SesEmailFactory {

    private static final Pattern TAG_UNSAFE = Pattern.compile("[^A-Za-z0-9_-]");
    private static final int TAG_MAX = 256;

    private final SenderProperties props;

    public SesEmailFactory(SenderProperties props) {
        this.props = props;
    }

    public SendEmailRequest build(MessageRecord message, RenderedEmail email) {
        List<MessageHeader> headers = new ArrayList<>();
        headers.add(header("X-Pigeon-Message-Id", message.getId().toString()));
        if (message.getStream() == MessageStream.BULK) {
            headers.add(header("List-Unsubscribe",
                    "<" + props.unsubscribeUrl() + "?m=" + message.getId() + ">, "
                            + "<mailto:" + props.unsubscribeMailbox() + "?subject=unsubscribe-" + message.getId() + ">"));
            headers.add(header("List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
        }

        return SendEmailRequest.builder()
                .fromEmailAddress(from(email.fromAddress()))
                .destination(d -> d.toAddresses(message.getRecipient()))
                .content(c -> c.simple(m -> m
                        .subject(utf8(email.subject()))
                        .body(b -> b.html(utf8(email.html())).text(utf8(email.text())))
                        .headers(headers)))
                .configurationSetName(message.getConfigurationSet())
                .emailTags(
                        tag("messageRecordId", message.getId().toString()),
                        tag("template", message.getTemplateId()),
                        tag("templateVersion", Integer.toString(message.getTemplateVersion())),
                        tag("stream", message.getStream().name()),
                        tag("correlationId", message.getCorrelationId()))
                .build();
    }

    private String from(String address) {
        String name = props.fromDisplayName();
        return name == null || name.isBlank() ? address : "\"" + name.replace("\"", "") + "\" <" + address + ">";
    }

    static String tagValue(String value) {
        if (value == null || value.isEmpty()) {
            return "none";
        }
        String safe = TAG_UNSAFE.matcher(value).replaceAll("_");
        return safe.length() <= TAG_MAX ? safe : safe.substring(0, TAG_MAX);
    }

    private static MessageTag tag(String name, String value) {
        return MessageTag.builder().name(name).value(tagValue(value)).build();
    }

    private static MessageHeader header(String name, String value) {
        return MessageHeader.builder().name(name).value(value).build();
    }

    private static Content utf8(String data) {
        return Content.builder().data(data).charset("UTF-8").build();
    }
}

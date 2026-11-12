package com.pigeon.sender;

import static org.assertj.core.api.Assertions.assertThat;

import com.pigeon.message.MessageRecord;
import com.pigeon.message.MessageStream;
import com.pigeon.template.RenderedEmail;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ses.model.MessageTag;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SesException;

/** The v1 adapter must carry over everything v1 can express, and errors must classify the same way. */
class SesV1EmailGatewayTest {

    private static final SenderProperties PROPS = new SenderProperties(true, 10, 14, 3,
            Duration.ofMinutes(2), Duration.ofSeconds(10), Duration.ofMinutes(5),
            "Acme Benefits", "https://member.example/unsubscribe", "unsubscribe@notify.pigeon.local");

    private software.amazon.awssdk.services.sesv2.model.SendEmailRequest v2Request(MessageStream stream) {
        MessageRecord m = MessageRecord.accept(UUID.fromString("11111111-2222-3333-4444-555555555555"), "corr-1",
                "payment-receipt", 1, "dana@example.com", stream, Map.of("firstName", "Dana"),
                stream == MessageStream.BULK ? "pigeon-bulk-prod" : "pigeon-txn-prod", Instant.now());
        RenderedEmail email = new RenderedEmail("payment-receipt", 1, "billing@notify.pigeon.local",
                "Payment received: $412.60", "<p>Hi Dana</p>", "Hi Dana");
        return new SesEmailFactory(PROPS).build(m, email);
    }

    @Test
    void translatesEveryFieldV1CanCarry() {
        SendEmailRequest v1 = SesV1EmailGateway.toV1(v2Request(MessageStream.TRANSACTIONAL));

        assertThat(v1.source()).isEqualTo("\"Acme Benefits\" <billing@notify.pigeon.local>");
        assertThat(v1.destination().toAddresses()).containsExactly("dana@example.com");
        assertThat(v1.message().subject().data()).isEqualTo("Payment received: $412.60");
        assertThat(v1.message().subject().charset()).isEqualTo("UTF-8");
        assertThat(v1.message().body().html().data()).isEqualTo("<p>Hi Dana</p>");
        assertThat(v1.message().body().text().data()).isEqualTo("Hi Dana");
        assertThat(v1.configurationSetName()).isEqualTo("pigeon-txn-prod");
        assertThat(v1.tags()).extracting(MessageTag::name)
                .containsExactly("messageRecordId", "template", "templateVersion", "stream", "correlationId");
        assertThat(v1.tags()).filteredOn(t -> t.name().equals("messageRecordId"))
                .extracting(MessageTag::value).containsExactly("11111111-2222-3333-4444-555555555555");
    }

    @Test
    void bulkKeepsItsConfigurationSet() {
        assertThat(SesV1EmailGateway.toV1(v2Request(MessageStream.BULK)).configurationSetName())
                .isEqualTo("pigeon-bulk-prod");
    }

    private static SesException v1Error(String code, int status) {
        return (SesException) SesException.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(code).build())
                .build();
    }

    @Test
    void v1ThrottlingIsRetried() {
        assertThat(SesErrorClassifier.classify(v1Error("Throttling", 400))).isEqualTo(SesErrorClassifier.Decision.RETRY);
        assertThat(SesErrorClassifier.classify(v1Error("MaxSendingRateExceeded", 400))).isEqualTo(SesErrorClassifier.Decision.RETRY);
    }

    @Test
    void v1RejectionsArePermanent() {
        assertThat(SesErrorClassifier.classify(v1Error("MessageRejected", 400))).isEqualTo(SesErrorClassifier.Decision.PERMANENT);
        assertThat(SesErrorClassifier.classify(v1Error("ConfigurationSetDoesNotExist", 400))).isEqualTo(SesErrorClassifier.Decision.PERMANENT);
    }

    @Test
    void v1ServerErrorsAreRetriedUnknownClientErrorsAreNot() {
        assertThat(SesErrorClassifier.classify(v1Error("SomethingNew", 503))).isEqualTo(SesErrorClassifier.Decision.RETRY);
        assertThat(SesErrorClassifier.classify(v1Error("SomethingNew", 400))).isEqualTo(SesErrorClassifier.Decision.PERMANENT);
    }
}

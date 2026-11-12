package com.pigeon.sender;

import static org.assertj.core.api.Assertions.assertThat;

import com.pigeon.message.MessageRecord;
import com.pigeon.message.MessageStream;
import com.pigeon.support.Pii;
import com.pigeon.template.RenderedEmail;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException;
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.MessageTag;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

/**
 * Plain unit tests for the sender's decisions. No Spring, no containers.
 * (Kept flat on purpose: Surefire did not run JUnit @Nested classes in this build.)
 */
class SenderUnitTests {

    private static final SenderProperties PROPS = new SenderProperties(true, 10, 14, 3,
            Duration.ofMinutes(2), Duration.ofSeconds(10), Duration.ofMinutes(5),
            "Acme Benefits", "https://member.example/unsubscribe", "unsubscribe@notify.pigeon.local");

    // --- ErrorClassification ------------------------------------------------------------

    @Test
    void throttlingIsRetried() {
        assertThat(SesErrorClassifier.classify(TooManyRequestsException.builder().message("slow down").build()))
                .isEqualTo(SesErrorClassifier.Decision.RETRY);
        assertThat(SesErrorClassifier.classify(RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("t"))))
                .isEqualTo(SesErrorClassifier.Decision.RETRY);
    }

    @Test
    void networkErrorsAreRetriedEvenWhenWrapped() {
        assertThat(SesErrorClassifier.classify(new CompletionException(SdkClientException.create("connection reset"))))
                .isEqualTo(SesErrorClassifier.Decision.RETRY);
    }

    @Test
    void serverErrorsAreRetriedClientErrorsAreNot() {
        assertThat(SesErrorClassifier.classify(SesV2Exception.builder().statusCode(503).message("unavailable").build()))
                .isEqualTo(SesErrorClassifier.Decision.RETRY);
        assertThat(SesErrorClassifier.classify(SesV2Exception.builder().statusCode(400).message("bad").build()))
                .isEqualTo(SesErrorClassifier.Decision.PERMANENT);
    }

    @Test
    void rejectedMessagesAndUnverifiedDomainsArePermanent() {
        assertThat(SesErrorClassifier.classify(MessageRejectedException.builder().message("Email address is not verified").build()))
                .isEqualTo(SesErrorClassifier.Decision.PERMANENT);
        assertThat(SesErrorClassifier.classify(MailFromDomainNotVerifiedException.builder().message("x").build()))
                .isEqualTo(SesErrorClassifier.Decision.PERMANENT);
    }

    @Test
    void accountSuspensionIsRetriedSoMailResumesAfterReinstatement() {
        assertThat(SesErrorClassifier.classify(AccountSuspendedException.builder().message("x").build()))
                .isEqualTo(SesErrorClassifier.Decision.RETRY);
    }

    @Test
    void describeIsShortAndUnwrapped() {
        assertThat(SesErrorClassifier.describe(new CompletionException(SdkClientException.create("timeout"))))
                .isEqualTo("SdkClientException: timeout");
    }

    // --- Backoff ------------------------------------------------------------

    @Test
    void doublesFromBaseAndIsCapped() {
        assertThat(PROPS.backoffFor(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(PROPS.backoffFor(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(PROPS.backoffFor(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(PROPS.backoffFor(20)).isEqualTo(Duration.ofMinutes(5));
    }

    // --- EmailRequest ------------------------------------------------------------

    private final SesEmailFactory factory = new SesEmailFactory(PROPS);

    private MessageRecord message(MessageStream stream) {
        return MessageRecord.accept(UUID.fromString("11111111-2222-3333-4444-555555555555"), "corr.with:odd chars",
                "payment-receipt", 1, "dana@example.com", stream, Map.of("firstName", "Dana"),
                stream == MessageStream.BULK ? "pigeon-bulk-prod" : "pigeon-txn-prod", Instant.now());
    }

    private final RenderedEmail email = new RenderedEmail("payment-receipt", 1, "billing@notify.pigeon.local",
            "Payment received: $412.60", "<p>Hi Dana</p>", "Hi Dana");

    @Test
    void transactionalRequestHasConfigurationSetTagsAndBothParts() {
        SendEmailRequest req = factory.build(message(MessageStream.TRANSACTIONAL), email);

        assertThat(req.configurationSetName()).isEqualTo("pigeon-txn-prod");
        assertThat(req.fromEmailAddress()).isEqualTo("\"Acme Benefits\" <billing@notify.pigeon.local>");
        assertThat(req.destination().toAddresses()).containsExactly("dana@example.com");
        assertThat(req.content().simple().subject().data()).isEqualTo("Payment received: $412.60");
        assertThat(req.content().simple().body().html().data()).isEqualTo("<p>Hi Dana</p>");
        assertThat(req.content().simple().body().text().data()).isEqualTo("Hi Dana");
        assertThat(req.emailTags()).extracting(MessageTag::name)
                .containsExactly("messageRecordId", "template", "templateVersion", "stream", "correlationId");
        assertThat(req.content().simple().headers()).extracting(MessageHeader::name)
                .containsExactly("X-Pigeon-Message-Id");
    }

    @Test
    void tagValuesAreMadeSafeForSes() {
        SendEmailRequest req = factory.build(message(MessageStream.TRANSACTIONAL), email);

        assertThat(req.emailTags()).filteredOn(t -> t.name().equals("correlationId"))
                .extracting(MessageTag::value).containsExactly("corr_with_odd_chars");
        assertThat(req.emailTags()).allSatisfy(t -> assertThat(t.value()).matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void bulkRequestCarriesOneClickUnsubscribeHeaders() {
        SendEmailRequest req = factory.build(message(MessageStream.BULK), email);

        assertThat(req.configurationSetName()).isEqualTo("pigeon-bulk-prod");
        assertThat(req.content().simple().headers()).extracting(MessageHeader::name)
                .contains("List-Unsubscribe", "List-Unsubscribe-Post");
        assertThat(req.content().simple().headers()).filteredOn(h -> h.name().equals("List-Unsubscribe-Post"))
                .extracting(MessageHeader::value).containsExactly("List-Unsubscribe=One-Click");
        assertThat(req.content().simple().headers()).filteredOn(h -> h.name().equals("List-Unsubscribe"))
                .extracting(MessageHeader::value).first().asString()
                .contains("https://member.example/unsubscribe?m=11111111-2222-3333-4444-555555555555", "mailto:");
    }

    // --- LogMasking ------------------------------------------------------------

    @Test
    void emailIsMasked() {
        assertThat(Pii.maskEmail("dana.smith@example.com")).isEqualTo("d***@example.com");
        assertThat(Pii.maskEmail("nonsense")).isEqualTo("***");
        assertThat(Pii.maskEmail(null)).isNull();
    }
}

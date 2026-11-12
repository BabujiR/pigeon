package com.pigeon.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class PrivacyAndTracingUnitTests {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void masksEveryAddressInsideFreeText() {
        String sesError = "MessageRejected: Email address is not verified. The following identities failed "
                + "the check in region US-EAST-1: Dana.Smith+test@Example.com, ops@notify.pigeon.local";

        assertThat(Pii.maskEmailsIn(sesError))
                .contains("D***@Example.com", "o***@notify.pigeon.local")
                .doesNotContain("Dana.Smith", "ops@");
    }

    @Test
    void textWithoutAddressesIsUnchanged() {
        assertThat(Pii.maskEmailsIn("Throttling: Maximum sending rate exceeded")).isEqualTo("Throttling: Maximum sending rate exceeded");
        assertThat(Pii.maskEmailsIn(null)).isNull();
    }

    @Test
    void safeCallerCorrelationIdIsKept() {
        assertThat(CorrelationId.resolve("order-123_A.b")).isEqualTo("order-123_A.b");
    }

    @Test
    void unsafeOrMissingCorrelationIdIsReplacedWithAUuid() {
        for (String bad : new String[] {null, "", "has space", "x".repeat(101), "inject\r\nHeader: 1"}) {
            String resolved = CorrelationId.resolve(bad);
            assertThat(UUID.fromString(resolved)).isNotNull();
        }
    }

    @Test
    void currentCreatesOneIdAndThenReusesIt() {
        String first = CorrelationId.current();
        assertThat(CorrelationId.current()).isEqualTo(first);
        CorrelationId.clear();
        assertThat(CorrelationId.current()).isNotEqualTo(first);
    }

    @Test
    void bindPutsBothIdsOnTheLogContext() {
        UUID id = UUID.randomUUID();
        CorrelationId.bind("corr-1", id);

        assertThat(MDC.get("correlationId")).isEqualTo("corr-1");
        assertThat(MDC.get("messageId")).isEqualTo(id.toString());
        CorrelationId.clear();
        assertThat(MDC.get("correlationId")).isNull();
    }
}

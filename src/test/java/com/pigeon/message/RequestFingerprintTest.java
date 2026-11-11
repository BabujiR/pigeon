package com.pigeon.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void isA64CharacterHexSha256() {
        assertThat(RequestFingerprint.of("t", "a@b.com", MessageStream.TRANSACTIONAL, Map.of()))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void variableOrderDoesNotMatter() {
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, String> ba = new LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");

        assertThat(RequestFingerprint.of("t", "x@y.com", MessageStream.BULK, ab))
                .isEqualTo(RequestFingerprint.of("t", "x@y.com", MessageStream.BULK, ba));
    }

    @Test
    void recipientCaseAndWhitespaceDoNotMatter() {
        assertThat(RequestFingerprint.of("t", "  Dana@Example.COM ", MessageStream.TRANSACTIONAL, Map.of()))
                .isEqualTo(RequestFingerprint.of("t", "dana@example.com", MessageStream.TRANSACTIONAL, Map.of()));
    }

    @Test
    void anyMeaningfulDifferenceChangesTheFingerprint() {
        String base = RequestFingerprint.of("t", "a@b.com", MessageStream.TRANSACTIONAL, Map.of("x", "1"));

        assertThat(RequestFingerprint.of("u", "a@b.com", MessageStream.TRANSACTIONAL, Map.of("x", "1"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("t", "c@b.com", MessageStream.TRANSACTIONAL, Map.of("x", "1"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("t", "a@b.com", MessageStream.BULK, Map.of("x", "1"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("t", "a@b.com", MessageStream.TRANSACTIONAL, Map.of("x", "2"))).isNotEqualTo(base);
    }
}

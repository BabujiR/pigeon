package com.pigeon.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pigeon.suppression.SuppressionService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 of a normalized request. Two requests that mean the same thing get the same
 * fingerprint even if JSON key order or recipient letter case differ.
 *
 * <p>Used to tell an honest retry (same key, same body: return the original) from a caller bug
 * (same key, different body: reject with 422) instead of silently returning the wrong message.
 */
public final class RequestFingerprint {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private RequestFingerprint() {
    }

    public static String of(String templateId, String recipient, MessageStream stream, Map<String, String> variables) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("templateId", templateId);
        canonical.put("recipient", SuppressionService.normalize(recipient));
        canonical.put("stream", stream == null ? null : stream.name());
        canonical.put("variables", new TreeMap<>(variables == null ? Map.of() : variables));
        try {
            byte[] json = CANONICAL.writeValueAsBytes(canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint request", e);
        }
    }
}

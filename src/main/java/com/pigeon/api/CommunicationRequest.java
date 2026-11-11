package com.pigeon.api;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.pigeon.message.MessageStream;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Body of {@code POST /v1/communications}, as JSON:
 * <pre>{"templateId":"payment-receipt","recipient":"dana@example.com","stream":"TRANSACTIONAL",
 *  "variables":{"firstName":"Dana","amount":"$412.60"}}</pre>
 * or as XML:
 * <pre>&lt;communication&gt;&lt;templateId&gt;payment-receipt&lt;/templateId&gt;...
 *  &lt;variables&gt;&lt;firstName&gt;Dana&lt;/firstName&gt;&lt;/variables&gt;&lt;/communication&gt;</pre>
 */
@JacksonXmlRootElement(localName = "communication")
public record CommunicationRequest(
        @NotBlank @Size(max = 64) String templateId,
        @NotBlank @Email @Size(max = 320) String recipient,
        @NotNull MessageStream stream,
        Map<String, String> variables) {

    /** A merge value sent as JSON null is treated as absent, so it is reported as missing if required. */
    public CommunicationRequest {
        Map<String, String> cleaned = new LinkedHashMap<>();
        if (variables != null) {
            variables.forEach((k, v) -> {
                if (k != null && v != null) {
                    cleaned.put(k, v);
                }
            });
        }
        variables = Map.copyOf(cleaned);
    }
}

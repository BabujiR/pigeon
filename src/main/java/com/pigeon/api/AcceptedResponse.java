package com.pigeon.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.pigeon.message.CommunicationService.AcceptResult;
import com.pigeon.message.MessageRecord;
import java.util.UUID;

/** Response to {@code POST /v1/communications}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "accepted")
public record AcceptedResponse(
        UUID requestId,
        String status,
        String templateId,
        int templateVersion,
        String stream,
        String configurationSet,
        String correlationId,
        String suppressionReason,
        boolean replayed,
        String statusUrl) {

    static AcceptedResponse from(AcceptResult result) {
        MessageRecord m = result.message();
        return new AcceptedResponse(
                m.getId(),
                m.getStatus().name(),
                m.getTemplateId(),
                m.getTemplateVersion(),
                m.getStream().name(),
                m.getConfigurationSet(),
                m.getCorrelationId(),
                result.suppressionReason(),
                result.replayed(),
                CommunicationController.BASE_PATH + "/" + m.getId());
    }
}

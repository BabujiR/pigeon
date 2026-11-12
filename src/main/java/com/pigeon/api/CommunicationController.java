package com.pigeon.api;

import com.pigeon.message.CommunicationService;
import com.pigeon.message.CommunicationService.AcceptResult;
import com.pigeon.message.MessageRecord;
import com.pigeon.message.MessageStatus;
import com.pigeon.support.CorrelationId;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The front door for every application that needs to email someone.
 *
 * <p>Review note: the API never talks to SES. It answers 202 within milliseconds whatever SES
 * is doing, and the send happens asynchronously from the outbox. Callers poll
 * {@code GET /v1/communications/{requestId}} (or, later, receive a webhook) for the outcome.
 */
@RestController
@RequestMapping(CommunicationController.BASE_PATH)
public class CommunicationController {

    static final String BASE_PATH = "/v1/communications";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String CORRELATION_ID = CorrelationId.HEADER;   // set by CorrelationIdFilter
    static final String REPLAYED = "Idempotent-Replayed";

    private final CommunicationService service;

    public CommunicationController(CommunicationService service) {
        this.service = service;
    }

    @PostMapping(
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public ResponseEntity<AcceptedResponse> submit(
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody CommunicationRequest request) {

        AcceptResult result = service.accept(idempotencyKey, CorrelationId.current(),
                request.templateId(), request.recipient(), request.stream(), request.variables());

        AcceptedResponse body = AcceptedResponse.from(result);
        return ResponseEntity.accepted()
                .location(URI.create(body.statusUrl()))
                .header(REPLAYED, String.valueOf(result.replayed()))
                .body(body);
    }

    /** One row of {@code GET /v1/communications}. History and merge values stay on the detail call. */
    public record MessageSummary(UUID requestId, String status, String recipient, String templateId,
                                 int templateVersion, String stream, String correlationId, int attemptCount,
                                 Instant createdAt, Instant updatedAt) {
        static MessageSummary of(MessageRecord m) {
            return new MessageSummary(m.getId(), m.getStatus().name(), m.getRecipient(), m.getTemplateId(),
                    m.getTemplateVersion(), m.getStream().name(), m.getCorrelationId(), m.getAttemptCount(),
                    m.getCreatedAt(), m.getUpdatedAt());
        }
    }

    /** Recent messages, newest first: {@code ?status=BOUNCED&limit=50} (limit 1 to 200). */
    @GetMapping(produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public List<MessageSummary> recent(@RequestParam(required = false) MessageStatus status,
                                       @RequestParam(defaultValue = "50") int limit) {
        return service.recent(status, limit).stream().map(MessageSummary::of).toList();
    }

    @GetMapping(path = "/{requestId}", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public CommunicationStatusResponse status(@PathVariable UUID requestId) {
        return CommunicationStatusResponse.from(service.status(requestId));
    }
}

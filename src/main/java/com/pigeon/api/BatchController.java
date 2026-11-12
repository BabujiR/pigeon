package com.pigeon.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pigeon.message.CommunicationService;
import com.pigeon.message.CommunicationService.AcceptResult;
import com.pigeon.message.IdempotencyConflictException;
import com.pigeon.message.MessageStream;
import com.pigeon.template.MissingMergeFieldsException;
import com.pigeon.template.TemplateService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/communications/batch}: up to 1,000 recipients of one template in one call.
 *
 * <p>Review notes:
 * <ul>
 *   <li><b>Each recipient is its own message</b>, accepted through the same code path as a single
 *       request (validation, suppression, outbox), so a batch can never bypass a rule.</li>
 *   <li><b>One bad row does not fail the batch.</b> Invalid addresses or missing merge fields are
 *       reported per item; everything else is queued. Problems with the batch as a whole
 *       (unknown template, more than 1,000 items) reject it up front with 400.</li>
 *   <li><b>Idempotent.</b> Item keys are derived from the batch key ({@code key#index}), so
 *       retrying the whole batch after a timeout queues nothing twice.</li>
 *   <li>Returns 202 quickly: the work is a few database writes per item; sending is asynchronous.</li>
 * </ul>
 */
@RestController
public class BatchController {

    private static final Logger log = LoggerFactory.getLogger(BatchController.class);
    static final int MAX_ITEMS = 1000;
    private static final int MAX_BATCH_KEY = CommunicationService.MAX_KEY_LENGTH - 6;   // room for "#999"

    private final CommunicationService communications;
    private final TemplateService templates;
    private final Validator validator;

    public BatchController(CommunicationService communications, TemplateService templates, Validator validator) {
        this.communications = communications;
        this.templates = templates;
        this.validator = validator;
    }

    public record BatchRequest(
            @NotBlank @Size(max = 64) String templateId,
            @NotNull MessageStream stream,
            Map<String, String> sharedVariables,
            @NotEmpty @Size(max = MAX_ITEMS) List<Item> recipients) {

        /** One recipient. Its variables override sharedVariables with the same name. */
        public record Item(String recipient, Map<String, String> variables) {
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemResult(int index, String recipient, UUID requestId, String status, Boolean replayed,
                             String suppressionReason, String error, List<String> missingFields) {
    }

    public record BatchResponse(String batchKey, String correlationId, String templateId, int total,
                                Map<String, Integer> counts, List<ItemResult> items) {
    }

    @PostMapping(path = "/v1/communications/batch",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public ResponseEntity<BatchResponse> submit(
            @RequestHeader(CommunicationController.IDEMPOTENCY_KEY) String batchKey,
            @RequestHeader(value = CommunicationController.CORRELATION_ID, required = false) String correlationId,
            @Valid @RequestBody BatchRequest request) {

        if (batchKey.isBlank() || batchKey.length() > MAX_BATCH_KEY) {
            throw badRequest("invalid-idempotency-key", "Invalid Idempotency-Key",
                    "Batch Idempotency-Key must be 1 to " + MAX_BATCH_KEY + " characters");
        }
        templates.template(request.templateId());   // unknown template -> 400 for the whole batch

        String corr = CommunicationController.resolveCorrelationId(correlationId);
        Map<String, String> shared = request.sharedVariables() == null ? Map.of() : request.sharedVariables();
        List<ItemResult> results = new ArrayList<>(request.recipients().size());

        for (int i = 0; i < request.recipients().size(); i++) {
            BatchRequest.Item item = request.recipients().get(i);
            String recipient = item == null ? null : item.recipient();
            Map<String, String> vars = new LinkedHashMap<>(shared);
            if (item != null && item.variables() != null) {
                vars.putAll(item.variables());
            }
            // Same rules as a single request, checked per item.
            CommunicationRequest single = new CommunicationRequest(request.templateId(), recipient, request.stream(), vars);
            Set<ConstraintViolation<CommunicationRequest>> violations = validator.validate(single);
            if (!violations.isEmpty()) {
                String error = violations.stream()
                        .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .reduce((a, b) -> a + "; " + b).orElse("invalid");
                results.add(new ItemResult(i, recipient, null, "REJECTED", null, null, error, null));
                continue;
            }
            try {
                AcceptResult r = communications.accept(batchKey + "#" + i, corr, single.templateId(),
                        single.recipient(), single.stream(), single.variables());
                results.add(new ItemResult(i, r.message().getRecipient(), r.message().getId(),
                        r.message().getStatus().name(), r.replayed(), r.suppressionReason(), null, null));
            } catch (MissingMergeFieldsException e) {
                results.add(new ItemResult(i, recipient, null, "REJECTED", null, null, e.getMessage(), e.missingFields()));
            } catch (IdempotencyConflictException e) {
                results.add(new ItemResult(i, recipient, null, "REJECTED", null, null,
                        "Item key " + e.key() + " was already used with a different recipient or variables", null));
            }
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemResult r : results) {
            counts.merge(r.status(), 1, Integer::sum);
        }
        log.info("Batch {} ({} items, template {}): {}", batchKey, results.size(), request.templateId(), counts);

        BatchResponse body = new BatchResponse(batchKey, corr, request.templateId(), results.size(), counts, results);
        return ResponseEntity.accepted().header(CommunicationController.CORRELATION_ID, corr).body(body);
    }

    private static ErrorResponseException badRequest(String type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle(title);
        pd.setType(URI.create("https://pigeon.local/problems/" + type));
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, pd, null);
    }
}

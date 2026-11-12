package com.pigeon.suppression;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import com.pigeon.support.Pii;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Suppression list administration.
 *
 * <pre>
 *   GET    /v1/suppressions?reason=HARD_BOUNCE&page=0&size=50   list, newest first
 *   GET    /v1/suppressions/{address}                           one entry, 404 if not suppressed
 *   POST   /v1/suppressions                                     add (201 new, 200 already there)
 *   DELETE /v1/suppressions/{address}[?force=true]              remove (409 for complaints without force)
 * </pre>
 *
 * In production these endpoints belong behind an admin role; removal is logged for audit.
 */
@RestController
@RequestMapping(path = "/v1/suppressions", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
public class SuppressionController {

    private static final Logger log = LoggerFactory.getLogger(SuppressionController.class);
    private static final int MAX_PAGE_SIZE = 200;

    private final SuppressionService service;

    public SuppressionController(SuppressionService service) {
        this.service = service;
    }

    public record SuppressionView(String address, String reason, String source, String detail, Instant createdAt) {
        static SuppressionView of(Suppression s) {
            return new SuppressionView(s.getAddress(), s.getReason().name(), s.getSource(), s.getDetail(), s.getCreatedAt());
        }
    }

    public record SuppressionPage(List<SuppressionView> items, int page, int size, long totalItems, int totalPages) {
    }

    /** Body of POST. Reason defaults to MANUAL. */
    public record AddSuppression(@NotBlank @Email @Size(max = 320) String address,
                                 SuppressionReason reason,
                                 @Size(max = 500) String detail) {
    }

    @GetMapping
    public SuppressionPage list(@RequestParam(required = false) SuppressionReason reason,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Page<Suppression> result = service.list(reason, Math.max(page, 0), safeSize);
        return new SuppressionPage(result.getContent().stream().map(SuppressionView::of).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/{address:.+}")
    public SuppressionView get(@PathVariable String address) {
        return service.find(address).map(SuppressionView::of)
                .orElseThrow(() -> new SuppressionNotFoundException(SuppressionService.normalize(address)));
    }

    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public ResponseEntity<SuppressionView> add(@Valid @RequestBody AddSuppression request) {
        SuppressionReason reason = request.reason() == null ? SuppressionReason.MANUAL : request.reason();
        boolean added = service.suppress(request.address(), reason, "api:manual", request.detail());
        SuppressionView view = service.find(request.address()).map(SuppressionView::of).orElseThrow();
        log.info("Suppression {} via API: {} ({})", added ? "added" : "already present", Pii.maskEmail(view.address()), view.reason());
        return ResponseEntity.status(added ? HttpStatus.CREATED : HttpStatus.OK).body(view);
    }

    @DeleteMapping("/{address:.+}")
    public ResponseEntity<Void> remove(@PathVariable String address,
                                       @RequestParam(defaultValue = "false") boolean force) {
        Suppression removed = service.remove(address, force);
        log.warn("AUDIT suppression removed via API: {} (was {}, force={})", Pii.maskEmail(removed.getAddress()), removed.getReason(), force);
        return ResponseEntity.noContent().build();
    }
}

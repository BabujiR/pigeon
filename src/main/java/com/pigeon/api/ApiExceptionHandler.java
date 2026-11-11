package com.pigeon.api;

import com.pigeon.message.IdempotencyConflictException;
import com.pigeon.message.InvalidIdempotencyKeyException;
import com.pigeon.message.MessageNotFoundException;
import com.pigeon.template.MissingMergeFieldsException;
import com.pigeon.template.TemplateNotFoundException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error is an RFC 9457 problem document ({@code application/problem+json}) with a stable
 * {@code type}, a human {@code detail}, and machine-readable extras where they help the caller
 * fix the request (for example {@code missingFields}).
 *
 * <p>Review note: the caller should be able to fix a 400 from the response alone, without
 * reading our logs. Framework errors (missing header, malformed JSON, bad UUID) are handled by
 * the {@link ResponseEntityExceptionHandler} base class in the same format.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String TYPE_BASE = "https://pigeon.local/problems/";

    @ExceptionHandler(MissingMergeFieldsException.class)
    ProblemDetail missingFields(MissingMergeFieldsException e) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "missing-merge-fields", "Missing merge fields", e.getMessage());
        pd.setProperty("missingFields", e.missingFields());
        return pd;
    }

    @ExceptionHandler(TemplateNotFoundException.class)
    ProblemDetail unknownTemplate(TemplateNotFoundException e) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "unknown-template", "Unknown template", e.getMessage());
        pd.setProperty("templateId", e.templateId());
        return pd;
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ProblemDetail invalidKey(InvalidIdempotencyKeyException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-idempotency-key", "Invalid Idempotency-Key", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reused",
                "Idempotency-Key reused with a different request", e.getMessage());
        pd.setProperty("idempotencyKey", e.key());
        return pd;
    }

    @ExceptionHandler(MessageNotFoundException.class)
    ProblemDetail notFound(MessageNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "communication-not-found", "Communication not found", e.getMessage());
    }

    /** Bean Validation failures on the request body: list every field error, not just the first. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail pd = ex.getBody();
        pd.setType(URI.create(TYPE_BASE + "invalid-request"));
        pd.setTitle("Invalid request");
        pd.setDetail("One or more fields are invalid");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
                .toList();
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + type));
        pd.setTitle(title);
        return pd;
    }
}

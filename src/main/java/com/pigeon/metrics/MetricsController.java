package com.pigeon.metrics;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/metrics}: sending reputation the way SES judges it.
 *
 * <pre>
 *   GET /v1/metrics                       last 24 hours
 *   GET /v1/metrics?window=7d             last 7 days (also 12h, PT30M ...)
 *   GET /v1/metrics?from=...&to=...       explicit ISO-8601 instants, e.g. for a report
 * </pre>
 */
@RestController
public class MetricsController {

    private final ReputationService reputation;
    private final MetricsProperties props;

    public MetricsController(ReputationService reputation, MetricsProperties props) {
        this.reputation = reputation;
        this.props = props;
    }

    @GetMapping(path = "/v1/metrics", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public ReputationService.Report metrics(@RequestParam(required = false) String window,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        Instant end;
        Instant start;
        if (from != null || to != null) {
            end = to == null ? Instant.now() : instant("to", to);
            start = from == null ? end.minus(props.defaultWindow()) : instant("from", from);
        } else {
            end = Instant.now();
            start = end.minus(window == null ? props.defaultWindow() : duration(window));
        }
        if (!start.isBefore(end)) {
            throw badRequest("'from' must be before 'to'");
        }
        if (Duration.between(start, end).compareTo(props.maxWindow()) > 0) {
            throw badRequest("Window is longer than the maximum of " + props.maxWindow().toDays() + " days");
        }
        return reputation.report(start, end);
    }

    private static Instant instant(String name, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw badRequest("'" + name + "' must be an ISO-8601 instant such as 2026-11-01T00:00:00Z");
        }
    }

    private static Duration duration(String value) {
        try {
            Duration d = DurationStyle.detectAndParse(value);
            if (d.isNegative() || d.isZero()) {
                throw badRequest("'window' must be positive");
            }
            return d;
        } catch (IllegalArgumentException e) {
            throw badRequest("'window' must be a duration such as 24h, 7d or PT30M");
        }
    }

    private static ErrorResponseException badRequest(String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Invalid metrics window");
        pd.setType(java.net.URI.create("https://pigeon.local/problems/invalid-metrics-window"));
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, pd, null);
    }
}

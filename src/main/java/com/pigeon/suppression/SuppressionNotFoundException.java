package com.pigeon.suppression;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** HTTP 404 as a problem document; handled by Spring's built-in ErrorResponse support. */
public class SuppressionNotFoundException extends ErrorResponseException {

    public SuppressionNotFoundException(String address) {
        super(HttpStatus.NOT_FOUND, body(address), null);
    }

    private static ProblemDetail body(String address) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, address + " is not suppressed");
        pd.setTitle("Address not suppressed");
        pd.setType(URI.create("https://pigeon.local/problems/suppression-not-found"));
        return pd;
    }
}

package com.pigeon.suppression;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** HTTP 409: removing a complaint or account-level suppression needs an explicit force=true. */
public class SuppressionRemovalBlockedException extends ErrorResponseException {

    public SuppressionRemovalBlockedException(String address, SuppressionReason reason) {
        super(HttpStatus.CONFLICT, body(address, reason), null);
    }

    private static ProblemDetail body(String address, SuppressionReason reason) {
        String why = reason == SuppressionReason.COMPLAINT
                ? "the recipient marked a previous email as spam"
                : "SES itself suppresses this address at the account level and will not deliver to it";
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                address + " is suppressed because " + why + ". Add force=true to remove it anyway.");
        pd.setTitle("Suppression removal needs confirmation");
        pd.setType(URI.create("https://pigeon.local/problems/suppression-removal-blocked"));
        pd.setProperty("reason", reason.name());
        return pd;
    }
}

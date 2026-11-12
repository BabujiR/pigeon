package com.pigeon.sender;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.Set;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.LimitExceededException;
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendingPausedException;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

/**
 * Decides whether a failed SES call is worth retrying.
 *
 * <p>Review note: retrying a permanent error wastes quota and delays the alert; giving up on a
 * temporary one loses an email. When in doubt we retry, because attempts are capped.
 */
public final class SesErrorClassifier {

    public enum Decision { RETRY, PERMANENT }

    /** SES v1 reports these as error codes on a generic SesException rather than as subclasses. */
    private static final Set<String> RETRY_CODES = Set.of(
            "Throttling", "ThrottlingException", "TooManyRequestsException", "LimitExceeded",
            "LimitExceededException", "MaxSendingRateExceeded", "ServiceUnavailable", "InternalFailure");
    private static final Set<String> PERMANENT_CODES = Set.of(
            "MessageRejected", "MailFromDomainNotVerifiedException", "ConfigurationSetDoesNotExist",
            "InvalidParameterValue", "ValidationError");

    private SesErrorClassifier() {
    }

    public static Decision classify(Throwable error) {
        Throwable e = unwrap(error);

        // Our own rate limiter or SES throttling: slow down and try again.
        if (e instanceof RequestNotPermitted
                || e instanceof TooManyRequestsException
                || e instanceof LimitExceededException) {
            return Decision.RETRY;
        }
        // Account-level stops. Retry so mail flows again once the account is reinstated or
        // sending is resumed; the caps and the alarm on lastError surface it to a human.
        if (e instanceof SendingPausedException || e instanceof AccountSuspendedException) {
            return Decision.RETRY;
        }
        // The request itself is wrong: same request, same answer.
        if (e instanceof MessageRejectedException
                || e instanceof MailFromDomainNotVerifiedException
                || e instanceof BadRequestException
                || e instanceof NotFoundException) {
            return Decision.PERMANENT;
        }
        if (e instanceof SesV2Exception ses) {
            return ses.statusCode() >= 500 ? Decision.RETRY : Decision.PERMANENT;
        }
        // SES v1 (and any other AWS service error): decide by error code, then by HTTP status.
        if (e instanceof AwsServiceException aws) {
            String code = aws.awsErrorDetails() == null ? null : aws.awsErrorDetails().errorCode();
            if (code != null && RETRY_CODES.contains(code)) {
                return Decision.RETRY;
            }
            if (code != null && PERMANENT_CODES.contains(code)) {
                return Decision.PERMANENT;
            }
            return aws.statusCode() >= 500 || aws.statusCode() == 429 ? Decision.RETRY : Decision.PERMANENT;
        }
        // Network, DNS, timeouts.
        if (e instanceof SdkClientException) {
            return Decision.RETRY;
        }
        return Decision.RETRY;
    }

    private static Throwable unwrap(Throwable e) {
        Throwable current = e;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Short, log-safe description: exception type and message, no stack trace. */
    public static String describe(Throwable error) {
        Throwable e = unwrap(error);
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}

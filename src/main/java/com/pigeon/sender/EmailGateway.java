package com.pigeon.sender;

import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Where emails leave Pigeon. The rest of the sender only knows this interface.
 *
 * <p>The request type is the SES v2 model, because v2 is what Pigeon uses on AWS.
 * {@link SesV1EmailGateway} translates it for environments that only offer the v1 API
 * (LocalStack's free edition).
 */
public interface EmailGateway {

    /** Sends the email and returns the SES message id. Throws the SDK's exception on failure. */
    String send(SendEmailRequest request);

    /** Short name for logs and the message history, e.g. "sesv2". */
    String name();
}

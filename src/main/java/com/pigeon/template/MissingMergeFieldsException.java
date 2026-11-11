package com.pigeon.template;

import java.util.List;

/**
 * The caller did not supply every merge field. Phase 2 maps this to HTTP 400 and returns
 * {@link #missingFields()} so the caller can fix all of them in one go.
 */
public class MissingMergeFieldsException extends RuntimeException {

    private final List<String> missingFields;

    public MissingMergeFieldsException(List<String> missingFields) {
        super("Missing merge fields: " + String.join(", ", missingFields));
        this.missingFields = List.copyOf(missingFields);
    }

    public List<String> missingFields() {
        return missingFields;
    }
}

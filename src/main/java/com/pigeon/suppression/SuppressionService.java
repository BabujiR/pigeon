package com.pigeon.suppression;

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppression lookups. Phase 2 only reads; Phase 4 adds writes from bounce and complaint events
 * and Phase 5 adds the admin endpoints.
 */
@Service
@Transactional(readOnly = true)
public class SuppressionService {

    private final SuppressionRepository repository;

    public SuppressionService(SuppressionRepository repository) {
        this.repository = repository;
    }

    /** Email addresses are compared case-insensitively; the table stores them lower-cased. */
    public static String normalize(String address) {
        return address == null ? null : address.strip().toLowerCase(Locale.ROOT);
    }

    public Optional<Suppression> find(String address) {
        return repository.findById(normalize(address));
    }
}

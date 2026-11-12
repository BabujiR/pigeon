package com.pigeon.suppression;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The suppression list: addresses Pigeon must never send to again.
 *
 * <p>Review notes:
 * <ul>
 *   <li>Writes are idempotent ({@code ON CONFLICT DO NOTHING}). SES events can repeat, and the
 *       first reason recorded for an address is kept.</li>
 *   <li>Suppression is per address, not per message. A bounce for an email Pigeon cannot match
 *       to a record (for example sent by another system through the same configuration set)
 *       still protects the address.</li>
 *   <li>Soft bounces are stored one row each and counted in a window, so the rule ("3 in 7 days")
 *       can change without migrating data.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class SuppressionService {

    private final SuppressionRepository repository;
    private final JdbcTemplate jdbc;

    public SuppressionService(SuppressionRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /** Email addresses are compared case-insensitively; the table stores them lower-cased. */
    public static String normalize(String address) {
        return address == null ? null : address.strip().toLowerCase(Locale.ROOT);
    }

    public Optional<Suppression> find(String address) {
        return repository.findById(normalize(address));
    }

    /**
     * Adds an address. Returns true if it was newly suppressed, false if it already was.
     * Joins the caller's transaction.
     */
    @Transactional
    public boolean suppress(String address, SuppressionReason reason, String source, String detail) {
        int inserted = jdbc.update("""
                        insert into suppression (address, reason, source, detail)
                        values (?, ?, ?, ?)
                        on conflict (address) do nothing
                        """,
                normalize(address), reason.name(), truncate(source, 100), detail);
        return inserted == 1;
    }

    /** Records one transient bounce and returns how many this address had within {@code window}. */
    @Transactional
    public int recordSoftBounce(String address, String sesMessageId, String subType, Instant occurredAt,
                                Duration window) {
        String normalized = normalize(address);
        jdbc.update("""
                        insert into soft_bounce (address, ses_message_id, bounce_sub_type, occurred_at)
                        values (?, ?, ?, ?)
                        """,
                normalized, sesMessageId, subType, Timestamp.from(occurredAt));
        Integer count = jdbc.queryForObject("""
                        select count(*) from soft_bounce
                        where address = ? and occurred_at > ?
                        """,
                Integer.class, normalized, Timestamp.from(occurredAt.minus(window)));
        return count == null ? 0 : count;
    }

    /** Newest first, optionally filtered by reason. */
    public Page<Suppression> list(SuppressionReason reason, int page, int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return reason == null ? repository.findAll(request) : repository.findByReason(reason, request);
    }

    /**
     * Removes an address so Pigeon may email it again, and resets its soft-bounce count.
     *
     * <p>Complaints and SES account-level suppressions are protected: the person asked not to be
     * emailed, or SES itself will refuse the address. Removing them needs {@code force}, which the
     * API exposes as an explicit query parameter so it never happens by accident.
     */
    @Transactional
    public Suppression remove(String address, boolean force) {
        String normalized = normalize(address);
        Suppression existing = repository.findById(normalized)
                .orElseThrow(() -> new SuppressionNotFoundException(normalized));
        if (!force && (existing.getReason() == SuppressionReason.COMPLAINT
                || existing.getReason() == SuppressionReason.ACCOUNT_SUPPRESSION_LIST)) {
            throw new SuppressionRemovalBlockedException(normalized, existing.getReason());
        }
        repository.delete(existing);
        jdbc.update("delete from soft_bounce where address = ?", normalized);
        return existing;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

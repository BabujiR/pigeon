package com.pigeon.message;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEntry, Long> {

    /**
     * Oldest pending rows, locked. SKIP LOCKED lets several Pigeon instances run the relay at
     * once: each takes a different batch instead of waiting on (or double-publishing) the same rows.
     */
    @Query(value = """
            select * from outbox
            where status = 'PENDING'
            order by id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEntry> lockPendingBatch(@Param("limit") int limit);
}

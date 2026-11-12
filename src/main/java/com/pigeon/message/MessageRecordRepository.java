package com.pigeon.message;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRecordRepository extends JpaRepository<MessageRecord, UUID> {

    /** SELECT ... FOR UPDATE: serializes workers that received the same SQS message. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MessageRecord m where m.id = :id")
    Optional<MessageRecord> findForUpdate(@Param("id") UUID id);

    /** Fallback lookup for SES events that lack our messageRecordId tag. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MessageRecord> findBySesMessageId(String sesMessageId);
}

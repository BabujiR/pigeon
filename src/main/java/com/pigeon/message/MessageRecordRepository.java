package com.pigeon.message;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRecordRepository extends JpaRepository<MessageRecord, UUID> {
}

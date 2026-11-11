package com.pigeon.message;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageEventRepository extends JpaRepository<MessageEvent, Long> {

    List<MessageEvent> findByMessageIdOrderByOccurredAtAscIdAsc(UUID messageId);
}

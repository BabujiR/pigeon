package com.pigeon.suppression;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuppressionRepository extends JpaRepository<Suppression, String> {

    Page<Suppression> findByReason(SuppressionReason reason, Pageable pageable);
}

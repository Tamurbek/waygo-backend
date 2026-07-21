package com.waygo.backend.repository;

import com.waygo.backend.entity.PaynetTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaynetTransactionRepository extends JpaRepository<PaynetTransaction, Long> {
    Optional<PaynetTransaction> findByPaynetTransactionId(Long paynetTransactionId);
    List<PaynetTransaction> findByCreatedAtBetween(LocalDateTime dateFrom, LocalDateTime dateTo);
}

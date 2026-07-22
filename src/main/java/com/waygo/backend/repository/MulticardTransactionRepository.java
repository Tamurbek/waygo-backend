package com.waygo.backend.repository;

import com.waygo.backend.entity.MulticardTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MulticardTransactionRepository extends JpaRepository<MulticardTransaction, Long> {
    Optional<MulticardTransaction> findByInvoiceId(String invoiceId);
    Optional<MulticardTransaction> findByUuid(String uuid);
}

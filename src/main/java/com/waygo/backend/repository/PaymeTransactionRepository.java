package com.waygo.backend.repository;

import com.waygo.backend.entity.PaymeTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymeTransactionRepository extends JpaRepository<PaymeTransaction, Long> {
    Optional<PaymeTransaction> findByPaymeId(String paymeId);
    List<PaymeTransaction> findAllByTimeBetween(Long from, Long to);
}

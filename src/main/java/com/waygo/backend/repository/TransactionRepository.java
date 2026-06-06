package com.waygo.backend.repository;

import com.waygo.backend.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findBySenderIdOrReceiverIdOrderByCreatedAtDesc(Long senderId, Long receiverId);

    List<Transaction> findByTypeOrderByCreatedAtDesc(Transaction.TransactionType type);

    List<Transaction> findByTypeAndCreatedAtBetweenOrderByCreatedAtDesc(Transaction.TransactionType type, LocalDateTime start, LocalDateTime end);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.type = :type AND t.status = 'SUCCESS'")
    BigDecimal sumAmountByType(@Param("type") Transaction.TransactionType type);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.type = :type AND t.status = 'SUCCESS' AND t.createdAt BETWEEN :start AND :end")
    BigDecimal sumAmountByTypeAndCreatedAtBetween(@Param("type") Transaction.TransactionType type, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.type = :type AND t.status = 'SUCCESS'")
    long countByType(@Param("type") Transaction.TransactionType type);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.type = :type AND t.status = 'SUCCESS' AND t.createdAt BETWEEN :start AND :end")
    long countByTypeAndCreatedAtBetween(@Param("type") Transaction.TransactionType type, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.type = 'TARIFF_PURCHASE' AND t.status = 'SUCCESS' AND t.createdAt >= :from")
    BigDecimal sumTariffRevenueFrom(@Param("from") LocalDateTime from);
}

package com.waygo.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "multicard_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MulticardTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", unique = true)
    private String uuid; // Идентификатор транзакции от Multicard

    @Column(name = "invoice_id", unique = true, nullable = false)
    private String invoiceId; // Наш внутренний ID инвойса

    @Column(nullable = false)
    private Long amount; // Сумма в тийинах (long)

    @Column(nullable = false)
    private String status; // draft, progress, success, error, revert

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user; // Водитель

    @Column(length = 1000)
    private String checkoutUrl;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;
}

package com.waygo.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "paynet_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaynetTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paynet_transaction_id", unique = true, nullable = false)
    private Long paynetTransactionId;

    private Integer serviceId;

    @Column(nullable = false)
    private Long amount; // in tiyins

    @Column(nullable = false)
    private Integer state; // 1 = Success, 2 = Cancelled

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime cancelTime;
}

package com.waygo.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "points_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointsTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private Integer amount; // Can be positive or negative

    @Enumerated(EnumType.STRING)
    private TransactionType type;

    private String description;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum TransactionType {
        REFERRAL_BONUS,
        WELCOME_BONUS,
        TRIP_PAYMENT,
        GIFT_REDEEM,
        ADMIN_ADJUSTMENT
    }
}
